/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package razie.diesel.cron

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
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
import services.DieselCluster

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
  singleton: Boolean = false,
  tags:Array[String] = Array.empty,
  node:String = DieselCluster.clusterNodeSimple,
  clusterMode: String = DieselCron.MODE_ALL
) {
  var ref: Option[Cancellable] = None
  var currCount: Long = 0l
  var actualSched = "?"

  var lastFuture:Option[Future[Option[DomEngine]]] = None // future of last tick. if the schedule is sync, chain futures

  def isOnce = timeExpr.nonEmpty && schedExpr.isEmpty && cronExpr.trim.isEmpty

  def toJson = Map(
    "node" -> node,
    "name" -> schedId,
    "realm" -> realm,
    "env" -> env,
    "schedId" -> schedId,
    "cronExpr" -> cronExpr,
    "scheduleExpr" -> schedExpr,
    "timeExpr" -> timeExpr,
    "endTime" -> endTime,
    "actualSched" -> actualSched,
    "count" -> count,
    "tags" -> tags.toList,
    "clusterMode" -> clusterMode,
    "singleton" -> singleton,
    "currCount" -> currCount,
    "dieselParentId" -> dieselParentId,
    "cronMsg" -> cronMsg.fold(_.toJson, _.toString),
    "doneMsg" -> doneMsg.fold(_.toJson, _.toString)
  )

  // don't change, need the count for debugging
  override def toString = s"$schedId - count $currCount"

  def toFullString = super.toString
}

/** Diesel Quartz Job - used by the quartz scheduler */
class DieselQJob extends Job {

  override def execute(context: JobExecutionContext): Unit = {
    val data = context.getJobDetail.getJobDataMap
    val key = data.getString("jobKey")
    DieselCron.withRealmSchedules(_.get(key)).map(sc => {
      clog << s"===================================Quartz job triggered $key"
      DieselCron.worker ! CronTickMsg(sc)
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

  final val MODE_LOCAL = "local"
  final val MODE_ALL = "all"
  final val MODE_SINGLETON = "singleton"
  final val MODE_LB = "lb"
  final val MODE_VALUES = Array(MODE_ALL, MODE_LOCAL, MODE_SINGLETON, MODE_LB)
//}

  /** generic scheduler, using akka schedules
  *
  * a schedule has a handle and an update/create will reset/replace that handle.
  *
  * this is so that running the same logic all the time won't create million schedules
  * */
//@Singleton class DieselCron @Inject() (system:ActorSystem) extends Logging {
//  import razie.diesel.cron.DieselCron._

  import razie.diesel.engine.DieselAppContext.executionContext

  implicit val timeout: Timeout = Timeout(10.seconds)

  // all schedules, key is realm-id
  protected[diesel] val realmSchedules = new TrieMap[String, DomSchedule]

  /** all schedules, key is realm+id **/
  def withRealmSchedules[T](f: TrieMap[String, DomSchedule] => T): T = {
    f(realmSchedules)
  }

  // main worker
  lazy val worker = DieselAppContext.actorOf(Props[CronActor], name = "CronActor")

  // per realm schedule handlers
  val realmActors = new scala.collection.concurrent.TrieMap[String, ActorRef]()

  protected[diesel] val d5 = Duration.create(5, TimeUnit.MINUTES)
  protected[diesel] val d30 = Duration.create(30, TimeUnit.SECONDS)
  protected[diesel] val d10s = Duration.create(10, TimeUnit.SECONDS)

  import org.quartz.impl.StdSchedulerFactory

  val qsf = new StdSchedulerFactory
  val qsched = qsf.getScheduler


  def cronStats = {
    Map(
      "realmSchedules" -> realmSchedules.size,
      "realmActors" -> realmActors.size
    )
  }

  protected [diesel] def kickoffDoneMsg (curr: DomSchedule, args:Map[String,Any]) = {
    info(s"... kickoffDoneMsg for DomSchedule: $curr")

    val fs = curr.doneMsg.fold(
      m => m.withArgs(args).toMsgString.startMsg.map(_._1).map(Option.apply),

      // don't start if doneMsg is ""
      m => if (m.msg.contains(".")) m.withContext(args).startMsg.map(_._1).map(Option.apply) else Future.successful(None)
    )

    GlobalData.dieselCronsTotal.incrementAndGet()

    fs
  }

  protected [diesel] def kickoffCronMsg (curr: DomSchedule, currCount:Long, previous:Option[CachedEngingPrep]) = {
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
  protected[diesel] def runAndMaybeWaitOBSOLETE (realm:String, sc: DomSchedule, curr: DomSchedule, shouldAwait:Boolean) = {

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
        case _: Throwable =>// ignore it
      }
    }
  }

  // optimistic approach - nice when it doesn't happen a lot
  // no chain futures, but if previous not done, just skip - the counters keep ticking at the same count until it goes through
  // more friction in these actors, but the crons only expire as executed
  // other problem is having to wait another tick when completing between ticks... it won'y release the next tick right away
  protected[diesel] def runAndMaybeWait2 (realm:String, sc: DomSchedule, curr: DomSchedule, shouldAwait:Boolean) = {

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

  protected[diesel] def runDoneMsg (realm:String, curr: DomSchedule, args:Map[String,Any]): Unit = {
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
        Website.getRealmProp(realm, "diesel.cron.await").contains("false"))
  }

  /** simple await helper - not work with actors that await... */
  def await[S] (a: ActorRef, message: Any): S = Await.result(
    (worker ? message),
    20.seconds
  ).asInstanceOf[S]

  /** create a schedule for a realm - should be called once per realm */
  def cancelSchedule (realm: String, schedId: String) =
    await[Option[DomSchedule]](worker, CronCancelMsg(realm, schedId))

  /** create a schedule for a realm - should be called once per realm
    *
    * See http://specs.dieselapps.com/wiki/specs.Spec:Default_executors diesel.cron.set
    *
    * @return
    */
  def createSchedule (sched: CronCreateMsg) = {
    info("DieselCron creating")
    val s = await[String](worker, sched)
    if (s.startsWith("ERROR")) throw new DieselException(s)
    s
  }

  def defaultDoneMsg(schedId: String, realm: String, env: String) = DieselMsg(
    DieselMsg.CRON.ENTITY,
    DieselMsg.CRON.STOP,
    Map("name" -> schedId, "realm" -> realm, "env" -> env),
    DieselTarget.ENV(realm, env)
  )

  def init() : Unit = {
    worker
  }
}
