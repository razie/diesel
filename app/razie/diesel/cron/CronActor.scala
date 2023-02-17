/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package razie.diesel.cron

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.razie.pub.comms.CommRtException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.joda.time.DateTime
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.JobBuilder.newJob
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.{Job, JobExecutionContext, JobKey, TriggerKey}
import play.libs.Akka
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.{Logging, Snakk, clog}
import razie.diesel.engine.{CachedEngingPrep, DieselAppContext, DieselException, DomEngine}
import razie.diesel.guard.DieselDebug
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.diesel.samples.DomEngineUtils
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.GlobalData
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration, _}
import scala.concurrent.{Await, Future}
import services.{DieselCluster, TACB}
import services.DieselSingleton.{DSMsg, DSStartedOn, currentSingletonNode}

/** generic base for cron messages */
trait CronMsg
trait CronRemoteMsg

/** message to create a cron */
case class CronCreateMsg(schedId: String,
                         schedExpr: String,
                         cronExpr: String,
                         time: String,
                         endTime: String,
                         realm: String,
                         env: String,
                         parent: String,
                         inSequence: Boolean = false,
                         count: Long,
                         cronMsg: Either[DieselMsg, DieselMsgString],
                         doneMsg: Either[DieselMsg, DieselMsgString],
                         tags: String = "",
                         node: String = DieselCluster.clusterNodeSimple,
                         clusterMode: String = DieselCron.MODE_ALL
                        ) extends CronMsg

/** message to cancel a cron
  *
  * @param realm
  * @param schedId
  * @param alreadyRemoved some may need to remove it first and then ask kindly...
  */
case class CronCancelMsg(realm: String, schedId: String, alreadyRemoved: Option[DomSchedule] = None) extends CronMsg

/** a tick for a schedule */
case class CronTickMsg(cron: DomSchedule) extends CronMsg

/** a remote tick */
case class CronRemoteCreateMsg(cs: CronCreateMsg, fromNode:String = DieselCluster.clusterNodeSimple) extends CronRemoteMsg

case class CronRemoteCancelMsg(cs: CronCancelMsg, fromNode:String = DieselCluster.clusterNodeSimple) extends CronRemoteMsg

/** the worker - main manager of all crons, sync for management and dispatch for work.
  *
  * it creates and manages schedules
  *
  * it creates and manages per realm cron actors
  *
  * also dispatches the ticks to per realm actors
  */
class CronActor extends Actor with Logging {

  import razie.diesel.cron.DieselCron._
  import razie.diesel.engine.DieselAppContext.executionContext

  override def preStart(): Unit = {
    // subscribe for cron cluster messages
    DieselCluster.pubSub.subscribe(TACB.withActor(self, msgCls = classOf[CronRemoteMsg]))
  }

  override def receive = {

    case CronTickMsg(sc: DomSchedule) => {
      // local schedule triggered - forward to realm actor, so it runs in seq per realm, not holding up others

      info(s"DomSchedule triggered - forward to realm actor: $sc ?")
      trace(s"DomSchedule triggered - forward to realm actor: ${sc.toFullString}")

      if (!realmActors.contains(sc.realm)) {
        info(s"Creating realmActor for: ${sc.realm}")
        val ra = DieselAppContext.actorOf(Props(new CronRealmActor(sc.realm)), name = "CronRealmActor-" + sc.realm)
        realmActors.put(sc.realm, ra)
      }

      // dispatch it to realm actor
      debug(s"  Dispatching to realmActor for: ${sc.realm}")
      realmActors.get(sc.realm).map(_ ! CronTickMsg(sc))
    }

    case cs: CronCreateMsg => crc(cs)

    case can: CronCancelMsg => canc(can)

    case CronRemoteCreateMsg(cs, fromNode) => {
      info(s"... CronRemoteCreateMsg from $fromNode : $cs")

      crc(cs, notify = false)

      Services ! DieselMsg(
        "diesel.cron.remote",
        "create",
        Map(
          "realm" -> cs.realm,
          "env" -> cs.env,
          "cron" -> cs.toString,
          "fromNode" -> fromNode
        ),
        DieselTarget.ENV(cs.realm, cs.env)
      )
    }

    case CronRemoteCancelMsg(cs, fromNode) => {
      info(s"... CronRemoteCancelMsg from $fromNode : $cs")

      val env = canc(cs, notify = false).map(_.env).getOrElse("local")

      Services ! DieselMsg(
        "diesel.cron.remote",
        "cancel",
        Map(
          "realm" -> cs.realm,
          "env" -> env,
          "schedId" -> cs.schedId,
          "fromNode" -> fromNode
        ),
        DieselTarget.ENV(cs.realm, env)
      )
    }
  }

  def crc(cs: CronCreateMsg, notify: Boolean = true): Unit = {

    val CronCreateMsg(schedId, schedExpr, cronExpr, time, endTime, realm, env, parent, inSequence, count, cronMsg,
    doneMsg, tags, node, clusterMode) = cs

    // create schedule request
    val isLocalhost = Services.config.isLocalhost

    log(s"CronActor CreateSchedule request: $cs")

    try {
      // remove first, to overwrite
      val removed = realmSchedules.remove(realm + "-" + schedId)

      removed.toList.foreach { curr =>
        curr.ref.foreach(_.cancel())
      }

      var sexpr = schedExpr
      var d = Duration.Zero

      if (schedExpr.nonEmpty) {
// free realms at least 5min
        d = Duration.apply(schedExpr).asInstanceOf[FiniteDuration]
        val myRealms = "wiki,specs,oss,herc-cc,devblinq,devnetlinq,netlinqdemo,blinq" // todo better for me and paid
// realms

        if (isLocalhost) {
// leave them as requested on localhost
        } else if (d < d5 && !myRealms.contains(realm)) {
          d = d5
          sexpr = "5 minutes"
        } else if (d < d30) {
          d = d30
          sexpr = "30 seconds"
        }
      }

      val jobKey = realm + "-" + schedId

      val sc = DomSchedule(
        schedId, sexpr, cronExpr, time, endTime, cronMsg, doneMsg, realm, env, parent,
        inSequence, count,
        singleton = MODE_SINGLETON == clusterMode,
        tags.split(","), node, clusterMode)

      // start in a random time from now, but not too far in the future
// todo why?

      val startDur =
        if (time.nonEmpty) {
          // even if sched, will start on time, if time passed in
          val sec = org.joda.time.Seconds.secondsBetween(DateTime.now, DateTime.parse(time))
          var seconds = sec.getSeconds
          if (seconds < 0) seconds = 2 // if in past but was accepted, do it now
          sc.actualSched = seconds + " Seconds"
          Duration.create(seconds, TimeUnit.SECONDS)
        } else if (d > d10s)
//            Duration.create(/*30 + */ (Math.random() * 30).toInt, TimeUnit.SECONDS)
//            Duration.create(/*30 + */ (Math.random() * 5).toInt, TimeUnit.SECONDS)
          d
        else
          d //Duration.Zero

// put in map before creating schedule in case some tick asap
      info(s"Create schedule: ${sc.schedId} count ${sc.currCount}")
      realmSchedules.put(jobKey, sc)  // see copy above

      val akkaRef = if (schedExpr.trim.nonEmpty) {
// start plus schedule, using akka scheduler
        clog << "============ Akka creating job " + sc
        sc.actualSched = d.toString
        Some(
          Services.system.scheduler.schedule(
            startDur,
            d,
            DieselCron.worker,
            CronTickMsg(sc)
          ))
      } else if (cronExpr.trim.nonEmpty) {
// start plus cron expr schedule
        clog << "============ Quartz creating job " + sc

        if (!qsched.isStarted) qsched.start() // initialize quartz

        val job1 = newJob(classOf[DieselQJob]).withIdentity(jobKey, "dieselq").build
        val trigger1 = newTrigger()
            .withIdentity(jobKey, "dieselq")
            .startAt(new java.util.Date(java.lang.System.currentTimeMillis() + startDur.toMillis))
            .withSchedule(cronSchedule(cronExpr))
            .build

        job1.getJobDataMap.put("jobKey", jobKey)
        qsched.unscheduleJob(new TriggerKey(jobKey, "dieselq"))
        qsched.scheduleJob(job1, trigger1)

        None
      } else if (time.trim.nonEmpty) {
// just a given time - kick once!
        Some(
          Services.system.scheduler.scheduleOnce(
            startDur,
            DieselCron.worker,
            CronTickMsg(sc)
          ))
      } else {
        throw new IllegalArgumentException("either schedule or time should be non-empty")
      }

      val as = sc.actualSched
      sc.ref = akkaRef
      sc.actualSched = as

//        removed.foreach { r => sc.currCount = r.currCount } // copy old count

      GlobalData.dieselCronsActive.set(realmSchedules.size)
      sender ! s"Schedule $schedId for $realm-$env at $schedExpr"

// only replicate "all" and "singleton"
      if (cs.clusterMode == MODE_ALL || cs.clusterMode == MODE_SINGLETON)
        if (notify)
          DieselCluster.pubSub ! CronRemoteCreateMsg(cs)
    }
    catch {
      case t: Throwable => {
        sender ! s"ERROR Schedule $schedId for $realm-$env at $schedExpr Exception: $t"
      }
    }
  }

  def canc(can: CronCancelMsg, notify: Boolean = true): Option[DomSchedule] = {
    val CronCancelMsg(realm, id, alreadyRemoved) = can

    val key = realm + "-" + id
    info(s"... removing DomSchedule: $key")

    if (alreadyRemoved.isDefined || realmSchedules.get(key).exists(_.realm == realm)) {
      val removed = alreadyRemoved.orElse(realmSchedules.remove(key))
      removed.flatMap(_.ref).foreach(_.cancel())

      // cancel quartz job, if any
      clog << s"============= Quartz cancel job $key"
      qsched.unscheduleJob(new TriggerKey(key, "dieselq"))
      qsched.deleteJob(new JobKey(key, "dieselq"))

      GlobalData.dieselCronsActive.set(realmSchedules.size)
      sender ! removed

      if (notify)
        DieselCluster.pubSub ! CronRemoteCancelMsg(can)

      removed
    } else {
      sender ! None
      None
    }
  }

}

/** this is one per realm - make sure ticks go in sequence and wait */
class CronRealmActor(val realm: String) extends Actor with Logging {

  import razie.diesel.cron.DieselCron._

  def shouldAwait = shouldAwaitRealm(realm)

  override def receive = {

    // schedule ticked - should we send the message?
    case CronTickMsg(sc: DomSchedule) => {

      info(s"($realm) tick for DomSchedule: $sc")

      val key = sc.realm + "-" + sc.schedId

      if (realmSchedules.contains(key)) { // wasn't cancelled or something
        val curr = realmSchedules(key)

        val isCountOk = curr.count < 0 || curr.currCount < curr.count
        val isEndTimeOk = sc.endTime.isEmpty || DateTime.parse(sc.endTime).isAfterNow

        if (isCountOk && isEndTimeOk) { // fine... trigger it

          if (!curr.singleton || Services.cluster.isSingletonNode(Website.forRealm(sc.realm))) {

            if (ISENABLED) {
              runAndMaybeWait2(realm, sc, curr, shouldAwait)
            } else {
              info(s"($realm) - not enabled, skipping job: " + sc)
            }
          } else {
            info(s"($realm) - not on master node for job: " + sc)
          }

        } else { // not fine - done now.

          info(s"($realm) done with DomSchedule: $sc")

          worker ! CronCancelMsg(realm, curr.schedId, realmSchedules.remove(curr.schedId))

          val args = Map(
            "cronCount" -> curr.currCount,
            "cronDoneReason" -> s"by count: ${!isCountOk}, by endTime:${!isEndTimeOk}"
          )

          runDoneMsg(realm, curr, args)
          // not waiting
        }

        if (curr.isOnce) worker ! CronCancelMsg(realm, curr.schedId)

      } else {
        info(s"($realm) DomSchedule not found anymore: $sc")
        qsched.getTriggerKeys(GroupMatcher.anyGroup())
        worker ! CronCancelMsg(sc.realm, sc.schedId)

      }
    }
  }
}