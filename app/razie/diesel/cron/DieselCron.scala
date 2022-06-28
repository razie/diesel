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
import razie.{Logging, Snakk, clog}
import razie.diesel.engine.{CachedEngingPrep, DieselAppContext, DieselException, DomEngine}
import razie.diesel.guard.DieselDebug
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.GlobalData
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration, _}
import scala.concurrent.{Await, Future}

/** in-mem representation of an on-going schedule, see [[msg:diesel.cron.set]] */
case class DomSchedule(
  schedId: String,
  schedExpr: String,
  cronExpr: String,
  timeExpr: String,
  endTime: String,
  cronMsg: Either[DieselMsg, DieselMsgString],
  doneMsg: Either[DieselMsg, DieselMsgString],
  realm: String,
  env: String,
  dieselParentId: String,
  inSequence:Boolean=false,
  count: Long = 0l,
  singleton: Boolean = true
) {
  var ref: Option[Cancellable] = None
  var currCount: Long = 0l
  var actualSched = "?"

  var lastFuture:Option[Future[Option[DomEngine]]] = None // future of last tick. if the schedule is sync, chain futures

  def isOnce = timeExpr.nonEmpty && schedExpr.isEmpty && cronExpr.trim.isEmpty

  def toJson = Map(
    "realm" -> realm,
    "env" -> env,
    "schedId" -> schedId,
    "schedExpr" -> schedExpr,
    "timeExpr" -> timeExpr,
    "actualSched" -> actualSched,
    "count" -> count,
    "currCount" -> currCount,
    "dieselParentId" -> dieselParentId,
    "cronMsg" -> cronMsg.fold(_.toJson, _.toString),
    "doneMsg" -> doneMsg.fold(_.toJson, _.toString)
  )

  // don't change, need the count for debugging
  override def toString = s"$schedId - count $currCount"

  def toFullString = super.toString
}

/** Diesel Quartz Job */
class DieselQJob extends Job {

  override def execute(context: JobExecutionContext) = {
    val data = context.getJobDetail.getJobDataMap
    val key = data.getString("jobKey")
    DieselCron.withRealmSchedules(_.get(key)).map(sc => {
      clog << s"===================================Quartz job triggered $key"
      DieselCron.worker ! sc
    }).getOrElse({
      clog << s"===================================Quartz job not found $key"
      clog << s"... so cancel job $key"
      context.getScheduler.unscheduleJob(context.getTrigger.getKey)
    })
  }
}

/** generic scheduler, using akka schedules
  *
  * a schedule has a handle and an update/create will reset/replace that handle.
  *
  * this is so that running the same logic all the time won't create million schedules
  * */
object DieselCron extends Logging {

  def ISENABLED = DieselDebug.Cron.ISENABLED

  import DieselAppContext.executionContext

  implicit val timeout = Timeout(10 seconds)

  // all schedules, key is realm+id
  private val realmSchedules = new TrieMap[String, DomSchedule]

  def withRealmSchedules[T](f: TrieMap[String, DomSchedule] => T): T = {
    f(realmSchedules)
  }

  // main worker
  lazy val worker = DieselAppContext.actorOf(Props[CronActor], name = "CronActor")

  // per realm schedule handlers
  val realmActors = new scala.collection.concurrent.TrieMap[String, ActorRef]()

  protected val d5 = Duration.create(5, TimeUnit.MINUTES)
  protected val d30 = Duration.create(30, TimeUnit.SECONDS)
  protected val d10s = Duration.create(10, TimeUnit.SECONDS)

  import org.quartz.impl.StdSchedulerFactory

  val qsf = new StdSchedulerFactory
  val qsched = qsf.getScheduler

  /** message to cancel a cron
    *
    * @param realm
    * @param schedId
    * @param alreadyRemoved some may need to remove it first and then ask kindly...
    */
  case class CancelMsg(realm: String, schedId: String, alreadyRemoved:Option[DomSchedule]=None)

  /** message to create a cron */
  case class CreateScheduleMsg(schedId: String,
                            schedExpr: String,
                            cronExpr: String,
                            time: String,
                            endTime: String,
                            realm: String,
                            env: String,
                            parent: String,
                            inSequence:Boolean=false,
                            count: Long,
                            cronMsg: Either[DieselMsg, DieselMsgString],
                            doneMsg: Either[DieselMsg, DieselMsgString]
                           )

  def toj = {
    Map(
      "realmSchedules" -> realmSchedules.size
    )
  }

  /** the worker - main manager of all crons, sync for management and dispatch for work.
    *
    * it creates and manages schedules
    *
    * it creates and manages per realm cron actors
    *
    * also dispatches the ticks to per realm actors
    */
  class CronActor extends Actor {

    def receive = {

      case sc : DomSchedule => {

        // schedule triggered - forward to realm actor, so it runs in seq per realm, not holding up others
        info(s"DomSchedule triggered - forward to realm actor: $sc ?")
        trace(s"DomSchedule triggered - forward to realm actor: ${sc.toFullString}")

        if (!realmActors.contains(sc.realm)) {
          info(s"Creating realmActor for: ${sc.realm}")
          val ra = DieselAppContext.actorOf(Props(new CronRealmActor(sc.realm)), name = "CronRealmActor-" + sc.realm)
          realmActors.put(sc.realm, ra)
        }

        // dispatch it to realm actor
        debug(s"  Dispatching to realmActor for: ${sc.realm}")
        realmActors.get(sc.realm).map(_ ! sc)
      }

      case cs @ CreateScheduleMsg(schedId, schedExpr, cronExpr, time, endTime, realm, env, parent, inSequence, count, cronMsg,
      doneMsg) =>

        // create schedule request
        val isLocalhost = Services.config.isLocalhost

        log(s"CronActor CreateSchedule request: $cs")

        try {
          // remove first, to overwrite
          val removed = realmSchedules.remove(realm + "-" + schedId)

          removed.toList.map {curr=>
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

          val sc = DomSchedule(schedId, sexpr, cronExpr, time, endTime, cronMsg, doneMsg, realm, env, parent, inSequence, count)

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
              Akka.system.scheduler.schedule(
                startDur,
                d,
                DieselCron.worker,
                sc
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
              Akka.system.scheduler.scheduleOnce(
                startDur,
                DieselCron.worker,
                sc
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
        }
        catch {
          case t: Throwable => {
            sender ! s"ERROR Schedule $schedId for $realm-$env at $schedExpr Exception: $t"
          }
        }


      case can @ CancelMsg(realm, id, alreadyRemoved) => {

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
        } else {
          sender ! None
        }
      }
    }
  }

  /** this is one per realm - make sure ticks go in sequence and wait */
  class CronRealmActor(val realm: String) extends Actor {

    def shouldAwait = shouldAwaitRealm(realm)

    def receive = {

      // schedule triggered - should we send the message?
      case sc: DomSchedule => {

        info(s"($realm) received DomSchedule: $sc")

        val key = sc.realm + "-" + sc.schedId

        if (realmSchedules.contains(key)) { // wasn't cancelled or something
          val curr = realmSchedules(key)

            val isCountOk = curr.count < 0 || curr.currCount < curr.count
            val isEndTimeOk = sc.endTime.isEmpty || DateTime.parse(sc.endTime).isAfterNow

            if (isCountOk && isEndTimeOk) { // fine... trigger it

              if (!curr.singleton || DieselCron.isMasterNode(Website.forRealm(sc.realm).get)) {

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

              worker ! CancelMsg(realm, curr.schedId, realmSchedules.remove(curr.schedId))

              val args = Map(
                "cronCount" -> curr.currCount,
                "cronDoneReason" -> s"by count: ${!isCountOk}, by endTime:${!isEndTimeOk}"
              )

              runDoneMsg(realm, curr, args)
              // not waiting
            }

            if (curr.isOnce) {
              worker ! CancelMsg(realm, curr.schedId)
            }
        } else {
          info(s"($realm) DomSchedule not found anymore: $sc")
          qsched.getTriggerKeys(GroupMatcher.anyGroup())
          worker ! CancelMsg(sc.realm, sc.schedId)
        }
      }
    }
  }

  private def kickoffDoneMsg (curr: DomSchedule, args:Map[String,Any]) = {
    info(s"... kickoffDoneMsg for DomSchedule: $curr")

    val fs = curr.doneMsg.fold(
      m => m.withArgs(args).toMsgString.startMsg.map(_._1).map(Option.apply),

      // don't start if doneMsg is ""
      m => if (m.msg.contains(".")) m.withContext(args).startMsg.map(_._1).map(Option.apply) else Future.successful(None)
    )

    GlobalData.dieselCronsTotal.incrementAndGet()

    fs
  }

  private def kickoffCronMsg (curr: DomSchedule, currCount:Long, previous:Option[CachedEngingPrep]) = {
    info(s"... kickoffCronMsg for DomSchedule:${curr.schedId} count: ${currCount}")

    // check the time again, maybe too much quequeing
    val isEndTimeOk = curr.endTime.isEmpty || DateTime.parse(curr.endTime).isAfterNow

    val fs = if (isEndTimeOk) { // fine... trigger it

      curr.cronMsg.fold(

        _.withArgs(Map(
          "env" -> curr.env,
          "cronCount" -> currCount
        )).toMsgString
            .withCachedPrep(previous)
            .startMsg.map(_._1).map(Option.apply),

        _.withContext(Map(
          "cronCount" -> currCount
        ))
            .withCachedPrep(previous)
            .startMsg.map(_._1).map(Option.apply)
      )
    } else {
      info(s"... kickoffCronMsg EXPIRED for DomSchedule: ${curr.schedId} count: ${currCount}")
      Future.successful(None)
    }

    GlobalData.dieselCronsTotal.incrementAndGet()

    fs
  }

  private def dfltTimeout (realm:String) = Website.getRealmProp(realm, "diesel.cron.tout").getOrElse("300 seconds")

  // pessimistic
  // this one chains futures but counts now
  // problem is crons can expire before they're executed so on restart they're gone!!
  private def runAndMaybeWait1 (realm:String, sc: DomSchedule, curr: DomSchedule, shouldAwait:Boolean) = {

    info(s"($realm) runAndMaybeWait: ${sc.schedId}")

    curr.currCount = curr.currCount + 1
    val c = curr.currCount // capture value for closure

    curr.lastFuture = if(curr.inSequence && curr.lastFuture.isDefined) {
      debug(s"($realm) inSequence -> chain futures ${curr.schedId}")

      Some(curr.lastFuture.get.andThen {
        case _ => kickoffCronMsg(curr, c, None)
      })
    } else {
      debug(s"($realm) NOT inSequence ${curr.schedId}")
      Some(kickoffCronMsg(curr, c, None))
    }

    val fs = curr.lastFuture.get

    if (!shouldAwait) {

      info(s"($realm) - Awaiting disabled for ${sc.schedId}")

    } else {

      val tout = dfltTimeout(realm)

      info(s"($realm) - Awaiting with tout ${tout} for ${sc.schedId}")

      // waiting... makes cron jobs sync per realm
      // waiting to not allow each realm to run amock and/or overload system- set the diesel.cron.await
      try {
        Await.result(fs, Duration.create(tout))
      } catch {
        case t: Throwable =>// ignore it
      }
    }
  }

  // optimistic approach - nice when it doesn't happen a lot
  // no chain futures, but if previous not done, just skip - the counters keep ticking at the same count until it goes through
  // more friction in these actors, but the crons only expire as executed
  // other problem is having to wait another tick when completing between ticks... it won'y release the next tick right away
  private def runAndMaybeWait2 (realm:String, sc: DomSchedule, curr: DomSchedule, shouldAwait:Boolean) = {

    info(s"($realm) runAndMaybeWait: ${sc.schedId}")

    if(!curr.inSequence ||
        curr.inSequence && (curr.lastFuture.isEmpty || curr.lastFuture.exists(_.isCompleted))) {

      curr.currCount = curr.currCount + 1
      val c = curr.currCount // capture value for closure

      curr.lastFuture = Some(kickoffCronMsg(curr, c, None))

      val fs = curr.lastFuture.get

      if (!shouldAwait) {

        info(s"($realm) - Awaiting disabled for ${sc.schedId}")

      } else {

        val tout = dfltTimeout(realm)

        info(s"($realm) - Awaiting with tout ${tout} for ${sc.schedId}")

        // waiting... makes cron jobs sync per realm
        // waiting to not allow each realm to run amock and/or overload system- set the diesel.cron.await
        try {
          Await.result(fs, Duration.create(tout))
        } catch {
          case t: Throwable =>// ignore it
        }
      }
    } else {
      debug(s"($realm) WAITING - skip $curr")
    }

  }

  private def runDoneMsg (realm:String, curr: DomSchedule, args:Map[String,Any]) = {
    curr.lastFuture = if(curr.inSequence && curr.lastFuture.isDefined) {
      debug(s"($realm) inSequence -> chain futures ${curr.schedId}")

      Some(curr.lastFuture.get.andThen {
        case _ => kickoffDoneMsg(curr, args)
      })
    } else {
      debug(s"($realm) NOT inSequence ${curr.schedId}")
      Some(kickoffDoneMsg(curr, args))
    }

    val fs = curr.lastFuture.get
  }

  /** should NOT await realm crons if in local AND await was set to false explicitely */
  def shouldAwaitRealm (realm:String) = {
    // set this to false to allow parallel timers - by default is not disabled, so we wait
    !(Services.config.isLocalhost &&
        Website.getRealmProp(realm, "diesel.cron.await").exists(_ == "false"))
  }

  /** simple await helper - not work with actors that await... */
  def await[S] (a: ActorRef, message: Any): S = Await.result(
    (worker ? message),
    20 seconds
  ).asInstanceOf[S]

  /** create a schedule for a realm - should be called once per realm */
  def cancelSchedule (realm: String, schedId: String) =
    await[Option[DomSchedule]](worker, CancelMsg(realm, schedId))

  /** create a schedule for a realm - should be called once per realm
    *
    * See http://specs.dieselapps.com/wiki/specs.Spec:Default_executors diesel.cron.set
    *
    * @return
    */
  def createSchedule (schedId: String, schedExpr: String, cronExpr: String, time: String, endTime: String,
                     realm: String, env: String,
                     dieselParentId: String, inSequence:Boolean, count: Long,
                     cronMsg: Either[DieselMsg, DieselMsgString],
                     doneMsg: Either[DieselMsg, DieselMsgString]) = {
    info("DieselCron creating")
    val s = await[String](
      worker,
      CreateScheduleMsg(schedId, schedExpr, cronExpr, time, endTime, realm, env, dieselParentId, inSequence, count, cronMsg, doneMsg))
    if (s.startsWith("ERROR")) throw new DieselException(s)
    s
  }

  def defaultDoneMsg(schedId: String, realm: String, env: String) = DieselMsg(
    DieselMsg.CRON.ENTITY,
    DieselMsg.CRON.STOP,
    Map("name" -> schedId, "realm" -> realm, "env" -> env),
    DieselTarget.ENV(realm, env)
  )

  // todo optimize this - we need to avoid calling REST every time...
  var masterNodeStatus: Option[Boolean] = None

  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isMasterNode(w: Website) = {
    // todo use akka singleton or something
    masterNodeStatus.getOrElse {
      val me = InetAddress.getLocalHost.getHostName
      val url =
        (if (Services.config.isLocalhost) "http://" + Services.config.hostport
        else
          w.url) + "/diesel/engine/whoami"

      var active = ""

      try {
        active = Snakk.body(Snakk.url(url))
      } catch {
        case ex: CommRtException if ex.httpCode == 302 => {
          // redirect
          log("================ REDIRECTING... " + ex.httpCode + ex.location302)
          active = Snakk.body(Snakk.url(ex.location302))
        }
        case ex: CommRtException => {
          log("================ SOME ERROR..." + ex.httpCode + ex.location302)
          throw ex
        }
      }

      val res = me equals active
      debug(s"isMasterNode: $res $me =? $active url = ${w.url}")
      masterNodeStatus = Some(me equals active)
      masterNodeStatus.get
    }
  }
}
