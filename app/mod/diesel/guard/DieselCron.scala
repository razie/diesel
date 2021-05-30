/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package mod.diesel.guard

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.razie.pub.comms.CommRtException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.joda.time.DateTime
import org.joda.time.chrono.ISOChronology
import play.libs.Akka
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EError, EMsg, EVal, _}
import razie.diesel.engine.{DieselAppContext, DomAst, DomEngineSettings}
import razie.diesel.expr.ECtx
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.hosting.Website
import razie.tconf.TagQuery
import razie.wiki.admin.GlobalData
import razie.wiki.{Config, Services}
import razie.{Logging, Snakk, cdebug}
import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration, _}
import scala.collection.concurrent.TrieMap

/** in-mem representation of an on-going schedule */
case class DomSchedule(
  schedId: String,
  schedExpr: String,
  timeExpr: String,
  cronMsg: Either[DieselMsg, DieselMsgString],
  doneMsg: Either[DieselMsg, DieselMsgString],
  realm: String,
  env: String,
  dieselParentId: String,
  count: Long = 0l,
  singleton: Boolean = true,
  ref: Option[Cancellable] = None
) {
  var currCount: Long = 0l
  var actualSched = "?"

  def isOnce = timeExpr.nonEmpty && schedExpr.isEmpty

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

  def withRealmSchedules[T](f: TrieMap[String, DomSchedule] => T): T = /*synchronized*/ {
    f(realmSchedules)
  }

  // main worker
  lazy val worker = DieselAppContext.actorOf(Props[CronActor], name = "CronActor")

  // per realm schedule handlers
  val realmActors = new scala.collection.concurrent.TrieMap[String, ActorRef]()

  protected val d5 = Duration.create(5, TimeUnit.MINUTES)
  protected val d30 = Duration.create(30, TimeUnit.SECONDS)
  protected val d10s = Duration.create(10, TimeUnit.SECONDS)

  case class Cancel(realm: String, schedId: String)

  case class CreateSchedule(schedId: String,
                            schedExpr: String,
                            time: String,
                            realm: String,
                            env: String,
                            parent: String,
                            count: Long,
                            cronMsg: Either[DieselMsg, DieselMsgString],
                            doneMsg: Either[DieselMsg, DieselMsgString]
                           )

  def toj = {
    Map(
      "realmSchedules" -> realmSchedules.size
    )
  }

  /** main manager of crons */
  class CronActor extends Actor {

    def receive = {

      case sc@DomSchedule(id, expr, time, cronMsg, doneMsg, r, e, p, c, singleton, ak) => /*DieselCron.synchronized*/ {

        // schedule triggered - forward to realm actor, so it runs in seq per realm, not holding up others
        info(s"DomSchedule: $sc")

        if (!realmActors.contains(r)) {
          info(s"Creating realmActor for: $r")
          val ra = DieselAppContext.actorOf(Props(new CronRealmActor(r)), name = "CronRealmActor-" + r)
          realmActors.put(r, ra)
        }

        // dispatch it to realm actor
        debug(s"  Dispatching to realmActor for: $r")
        realmActors.get(r).map(_ ! sc)
      }

      case CreateSchedule(schedId, schedExpr, time, realm, env, parent, count, cronMsg, doneMsg) =>

        // create schedule request
        val isLocalhost = Config.isLocalhost

        /*DieselCron.synchronized*/
        {
          val removed = realmSchedules.remove(realm + "-" + schedId)

          removed.toList.map {
            _.ref.foreach(_.cancel())
          }

          var sexpr = schedExpr
          var d = Duration.Zero

          if (schedExpr.nonEmpty) {
            // free realms at least 5min
            d = Duration.apply(schedExpr).asInstanceOf[FiniteDuration]
            val myRealms = "wiki,specs,oss,herc-cc,devblinq" // todo better for me and paid realms

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

        // initial object
        var sc = DomSchedule(schedId, sexpr, time, cronMsg, doneMsg, realm, env, parent, count)

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

          val akkaRef = if (schedExpr.nonEmpty) {
            sc.actualSched = d.toString
            Akka.system.scheduler.schedule(
              startDur,
              d,
              DieselCron.worker,
              sc
            )
          } else if (time.nonEmpty) {
            Akka.system.scheduler.scheduleOnce(
              startDur,
              DieselCron.worker,
              sc
            )
          } else {
            throw new IllegalArgumentException("either schedule or time should be non-empty")
          }

          val as = sc.actualSched
          sc = sc.copy(ref = Some(akkaRef))
          sc.actualSched = as

//        removed.foreach { r => sc.currCount = r.currCount } // copy old count

          info(s"CronRealmActor start schedule: ${sc.schedId} count ${sc.currCount}")
          realmSchedules.put(realm + "-" + schedId, sc)
          GlobalData.dieselCronsActive.set(realmSchedules.size)
          sender ! s"Schedule $schedId for $realm-$env at $schedExpr"
        }

      case Cancel(realm, id) => /*DieselCron.synchronized*/ {
        if (realmSchedules.get(realm + "-" + id).exists(_.realm == realm)) {
          val removed = realmSchedules.remove(realm + "-" + id)
          removed.flatMap(_.ref).foreach(_.cancel())
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

    def receive = {

      // schedule triggered - should we send the message?
      case sc: DomSchedule => /*DieselCron.synchronized*/ {
        info(s"CronRealmActor received DomSchedule: ${sc.schedId}")
        val key = sc.realm + "-" + sc.schedId

        if (realmSchedules.contains(key)) { // wasn't cancelled or something
          val curr = realmSchedules(key)

          if (curr.count < 0 || curr.currCount < curr.count) { // fine... trigger it
            if (!curr.singleton || DieselCron.isMasterNode(Website.forRealm(sc.realm).get)) {
              if (ISENABLED) {
                info(s"CronRealmActor running DomSchedule: ${sc.schedId} count: ${curr.currCount}")
                curr.currCount = curr.currCount + 1

                val fs = curr.cronMsg.fold(
                  _.withArgs(
                    Map(
                      "env" -> curr.env,
                      "cronCount" -> curr.currCount
                    )).toMsgString.startMsg,
                  _.withContext(
                    Map(
                      "cronCount" -> curr.currCount.toString
                    )).startMsg
                )

                GlobalData.dieselCronsTotal.incrementAndGet()

                if (Config.isLocalhost && Website.getRealmProp(sc.realm, "diesel.cron.await").exists(
                  _ == "false")) {
                  info(s"DieselCron - Awaiting disabled for ${sc.realm}")
                } else {
                  val tout = Website.getRealmProp(sc.realm, "diesel.cron.tout").getOrElse("300 seconds")
                  info(s"DieselCron - Awaiting tout ${tout}")
                  // todo why am i waiting? to not allow each realm to run amock?
                  // just wait... this makes it sync
                  try {
                    val result = Await.result(fs, Duration.create(tout))
                  } catch {
                    case t: Throwable =>// ignore it
                  }
                }
              } else {
                info("DieselCron - not enabled job: " + sc)
              }
            } else {
              info("DieselCron - not on master node for job: " + sc)
            }
          } else {
            info(s"CronRealmActor removing DomSchedule: ${sc.schedId} count: ${curr.currCount}")
            realmSchedules.remove(key)
            curr.ref.map(_.cancel())

            info(s"CronRealmActor done with DomSchedule: ${sc.schedId}")
            curr.doneMsg.fold(
              m => m.withArgs(
                Map(
                  "cronCount" -> curr.currCount
                )).toMsgString.startMsg,

              // don't start if doneMsg is ""
              m => if (m.msg.contains(".")) m.withContext(
                Map(
                  "cronCount" -> curr.currCount.toString
                )).startMsg
            )
            // not waiting
          }

          if (curr.isOnce) {
            info(s"CronRealmActor removing DomSchedule: ${sc.schedId} count: ${curr.currCount}")
            realmSchedules.remove(key)
          }
        }
      }
    }
  }

  def await[S](a: ActorRef, message: Any): S = Await.result(
    (worker ? message),
    20 seconds
  ).asInstanceOf[S]

  /** create a schedule for a realm - should be called once per realm */
  def cancelSchedule(realm: String, schedId: String) =
    await[Option[DomSchedule]](worker, Cancel(realm, schedId))

  /** create a schedule for a realm - should be called once per realm
    *
    * @param schedId
    * @param schedExpr
    * @param time
    * @param realm
    * @param env
    * @param count
    * @param msg
    * @return
    */
  def createSchedule(schedId: String, schedExpr: String, time: String, realm: String, env: String,
                     dieselParentId: String, count: Long,
                     cronMsg: Either[DieselMsg, DieselMsgString],
                     doneMsg: Either[DieselMsg, DieselMsgString]) = {
    info("DieselCron creating")
    await[String](worker, CreateSchedule(schedId, schedExpr, time, realm, env, dieselParentId, count, cronMsg, doneMsg))
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
        (if (Config.isLocalhost) "http://" + Config.hostport
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
