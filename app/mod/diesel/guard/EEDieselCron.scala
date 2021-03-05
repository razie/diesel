/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  **/
package mod.diesel.guard

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.razie.pub.comms.CommRtException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.joda.time.DateTime
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
import razie.wiki.{Config, Services}
import razie.{Logging, Snakk, cdebug}
import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration, _}

/** in-mem representation of an on-going schedule */
case class DomSchedule(
  schedId: String,
  schedExpr: String,
  timeExpr: String,
  cronMsg: Either[DieselMsg, DieselMsgString],
  doneMsg: Either[DieselMsg, DieselMsgString],
  realm: String,
  env: String,
  count: Long = 0l,
  singleton: Boolean = true,
  ref: Option[Cancellable] = None) {
  var currCount:Long = 0l
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

  implicit val timeout = Timeout(2 seconds)

  // todo privatize and synchronize
  private val realmSchedules = new HashMap[String, DomSchedule]

  def withRealmSchedules[T] (f:HashMap[String, DomSchedule] => T) : T = /*synchronized*/ {
    f(realmSchedules)
  }

  lazy val worker = DieselAppContext.actorOf(Props[CronActor], name = "CronActor")
  val realmActors = new scala.collection.concurrent.TrieMap[String, ActorRef]()

  case class Cancel(realm:String, schedId:String)
  case class CreateSchedule(schedId: String,
                            schedExpr: String,
                            time: String,
                            realm: String,
                            env: String,
                            count: Long,
                            cronMsg: Either[DieselMsg, DieselMsgString],
                            doneMsg: Either[DieselMsg, DieselMsgString]
                           )

  class CronActor extends Actor {

    def receive = {

      // schedule triggered - should we send the message?
      case sc@DomSchedule(id, expr, time, cronMsg, doneMsg, r, e, c, singleton, ak) => /*DieselCron.synchronized*/ {
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

      case CreateSchedule(schedId, schedExpr, time, realm, env, count, cronMsg, doneMsg) =>
        /*DieselCron.synchronized*/ {
        val removed = realmSchedules.remove(schedId)

        removed.toList.map {
          _.ref.foreach(_.cancel())
        }

        var sexpr = schedExpr
        var d = Duration.Zero

        if (schedExpr.nonEmpty) {
          // free realms at least 5min
          d = Duration.apply(schedExpr).asInstanceOf[FiniteDuration]
          val d5 = Duration.create(5, TimeUnit.MINUTES)
          val d30 = Duration.create(30, TimeUnit.SECONDS)
          val myRealms = "wiki,specs,oss,herc-cc,devblinq" // todo better for me and paid realms

          if (Config.isLocalhost) {
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
        var sc = DomSchedule(schedId, sexpr, time, cronMsg, doneMsg, realm, env, count)

        // start in a random time from now, but not too far in the future
        // todo why?
        val d10s = Duration.create(10, TimeUnit.SECONDS)
        val startDur =
          if (d > d10s)
            Duration.create(/*30 + */ (Math.random() * 30).toInt, TimeUnit.SECONDS)
          else
            Duration.Zero

        val akkaRef = if (schedExpr.nonEmpty) {
          sc.actualSched = d.toString
          Akka.system.scheduler.schedule(
            startDur,
            d,
            DieselCron.worker,
            sc
          )
        } else if (time.nonEmpty) {
          val sec = org.joda.time.Seconds.secondsBetween(DateTime.now, DateTime.parse(time))
          var seconds = sec.getSeconds
          if (seconds < 0) seconds = 5 // if in past but was accepted, do it now

          sc.actualSched = seconds + " Seconds"
          Akka.system.scheduler.scheduleOnce(
            Duration.create(seconds, TimeUnit.SECONDS),
            DieselCron.worker,
            sc
          )
        } else {
          throw new IllegalArgumentException("either schedule or time should be non-empty")
        }

        val as = sc.actualSched
        sc = sc.copy(ref = Some(akkaRef))
        sc.actualSched = as

        removed.foreach{r=> sc.currCount = r.currCount} // copy old count

        realmSchedules.put(schedId, sc)
        sender ! s"Schedule $schedId for $realm-$env at $schedExpr"
      }

      case Cancel(r, id) => /*DieselCron.synchronized*/ {
        if(realmSchedules.get(id).exists(_.realm == r)) {
          val remove = realmSchedules.remove(id)
          remove.flatMap(_.ref).foreach(_.cancel())
          sender ! remove
        } else {
          sender ! None
        }
      }
    }
  }

  /** this is one per realm - make sure ticks go in sequence and wait */
  class CronRealmActor(val realm:String) extends Actor {

    def receive = {

      // schedule triggered - should we send the message?
      case sc@DomSchedule(id, expr, time, cronMsg, doneMsg, r, e, c, singleton, ak) => /*DieselCron.synchronized*/ {
        info(s"DomSchedule: $sc")

        if (realmSchedules.contains(id)) { // wasn't cancelled or something
          val curr = realmSchedules(id)
          curr.currCount = curr.currCount + 1

          if (curr.count < 0 || curr.currCount <= curr.count) { // fine... trigger it
            if (!singleton || DieselCron.isMasterNode(Website.forRealm(r).get)) {
              if (ISENABLED) {
                val fs = cronMsg.fold(
                  _.withArgs(Map("env" -> e)).toMsgString.startMsg,
//                  _.toMsgString.startMsg,
                  _.startMsg
                )

                // just wait... this makes it sync
                val result = Await.result(fs, Duration.create(5, TimeUnit.MINUTES))
              } else {
                clog << "DieselCron - not enabled job: " + sc
              }
            } else {
              clog << "DieselCron - not on master node for job: " + sc
            }
          } else {
            // todo need to stop the akka schedule
            curr.ref.map(_.cancel())
            realmSchedules.remove(id)

            val fs = doneMsg.fold(
              _.withArgs(Map("count" -> curr.count)).toMsgString.startMsg,
              _.startMsg
            )

            // not waiting
            // val result = Await.result(fs, Duration.create(5, TimeUnit.MINUTES))

//            Services ! DieselMsg(
//              DieselMsg.CRON.ENTITY,
//              DieselMsg.CRON.STOP,
//              Map("name" -> id, "realm" -> r, "env" -> e, "count" -> curr.count),
//              DieselTarget.ENV(r, e)
//            )
          }

          if(curr.isOnce) {
            realmSchedules.remove(id)
          }
        }
      }
    }
  }

  def await[S](a: ActorRef, message: Any): S = Await.result(
    (worker ? message),
    5 seconds
  ).asInstanceOf[S]

  /** create a schedule for a realm - should be called once per realm */
  def cancelSchedule(realm: String, schedId: String) = await[Option[DomSchedule]](worker, Cancel(realm, schedId))

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
  def createSchedule(schedId: String, schedExpr: String, time: String, realm: String, env: String, count: Long,
                     cronMsg: Either[DieselMsg, DieselMsgString],
                     doneMsg: Either[DieselMsg, DieselMsgString]) = {
    await[String](worker, CreateSchedule(schedId, schedExpr, time, realm, env, count, cronMsg, doneMsg))
  }

  def defaultDoneMsg(schedId: String, realm: String, env: String) = DieselMsg(
    DieselMsg.CRON.ENTITY,
    DieselMsg.CRON.STOP,
    Map("name" -> schedId, "realm" -> realm, "env" -> env),
    DieselTarget.ENV(realm, env)
  )

  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isMasterNode(w: Website) = {
    // todo use akka singleton or something
    val me = InetAddress.getLocalHost.getHostName
    val url =
      (if (Config.isLocalhost) "http://" + Config.hostport
      else
        w.url) + "/diesel/engine/whoami"

    var active = ""

    try {
      active = Snakk.body(Snakk.url(url))
    } catch {
      case ex:CommRtException if ex.httpCode == 302 => {
        // redirect
        log("================ REDIRECTING... " + ex.httpCode + ex.location302)
        active = Snakk.body(Snakk.url(ex.location302))
      }
      case ex:CommRtException => {
        log("================ SOME ERROR..." + ex.httpCode + ex.location302)
        throw ex
      }
    }

    val res = me equals active
    debug(s"isMasterNode: $res $me =? $active url = ${w.url}")
    me equals active
  }
}


/** actual share table. Collection model:
  * coll
  * */
class EEDieselCron extends EExecutor("diesel.cron") {
  final val DT = "diesel.cron"
  final val DFLT_COLLECT = 5

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DT
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] =  {
    val realm = ctx.root.settings.realm.mkString

    cdebug << "EEDiselCron: apply " + in

    in.ea match {

      case "diesel.cron.set" => {
        val name = ctx.getRequired("name")
        val env = ctx.get("env").mkString
        val acceptPast = ctx.getp("acceptPast").map(_.calculatedTypedValue.asBoolean).getOrElse(false)
        val scount = ctx.get("count").mkString
        val desc = ctx.get("description").mkString
        val tq = ctx.get("tquery").mkString
        val collectCount = ctx.get("collectCount").map(_.toInt).filter(_ < 50).getOrElse(
          DFLT_COLLECT) // keep 10 default for cron jobs
        val count = if (scount.length == 0) -1l else scount.toLong
        val cronMsg = ctx.get("cronMsg")
        val doneMsg = ctx.get("doneMsg")

        val schedule = ctx.get("schedule").mkString
        val time = ctx.get("time").mkString

        val now = DateTime.now()

        def dt = DateTime.parse(time)

        if (schedule.isEmpty && time.isEmpty) {
          List(EVal(P(Diesel.PAYLOAD, "Either schedule or time needs to be provided", WTypes.wt.EXCEPTION)))
        } else if (!acceptPast && time.nonEmpty && DateTime.parse(time).compareTo(DateTime.now()) <= 0) {
          List(EVal(P(Diesel.PAYLOAD, s"Time is in the past (${dt} vs ${now})", WTypes.wt.EXCEPTION)))
        } else if (!DieselCron.ISENABLED) {
          List(EVal(P(Diesel.PAYLOAD, "DieselCron is DISABLED", WTypes.wt.EXCEPTION)))
        } else {
          val settings = new DomEngineSettings()
          settings.collectCount = Some(collectCount)

          val m1: Either[DieselMsg, DieselMsgString] = cronMsg.map { s =>
            Right(DieselMsgString(
              // todo should escape unescaped double quotes?
              s,
              DieselTarget.TQSPECS(realm, env, new TagQuery(tq)),
              Map("name" -> name, "realm" -> realm, "env" -> env),
              Some(settings)
            ))
          }.getOrElse {
            Left(DieselMsg(
              DieselMsg.CRON.ENTITY,
              DieselMsg.CRON.TICK,
              Map("name" -> name, "realm" -> realm, "env" -> env),
              DieselTarget.TQSPECS(realm, env, new TagQuery(tq)),
              Some(settings)
            ))
          }

          val m2: Either[DieselMsg, DieselMsgString] = doneMsg.map { s =>
            Right(DieselMsgString(
              // todo should escape unescaped double quotes?
              s,
              DieselTarget.TQSPECS(realm, env, new TagQuery(tq)),
              Map("name" -> name, "realm" -> realm, "env" -> env),
              Some(settings)
            ))
          }.getOrElse {
            // count will be added at the end
            Left(DieselCron.defaultDoneMsg(name, realm, env))
          }

          cdebug << "EEDiselCron: set 1"
          val cid = DieselCron.createSchedule(name, schedule, time, realm, env, count, m1, m2)
          cdebug << "EEDiselCron: set 2"

          List(
            EVal(P.fromTypedValue("cronId", cid))
          )
        }
      }

      case "diesel.cron.list" => {
        val name = ctx.get("name").mkString

        cdebug << "EEDiselCron: list 1"
        val res = EVal(P.fromTypedValue(
          razie.diesel.Diesel.PAYLOAD,
          // need to filter by realm
          DieselCron.withRealmSchedules(_.filter(_._2.realm == realm).filter(x=> "" == name || name == x._1).map(o=> o._2.toJson).toList)
        )) :: Nil
        cdebug << "EEDiselCron: list 2"
        res
      }

      case "diesel.cron.cancel" => {
        val name = ctx.getRequired("name")
        val res = DieselCron.cancelSchedule(realm, name).mkString
        List(
          EVal(P(
            "payload",
            s"schedule cancelled: $res"
          ))
        )
      }

      case s@_ => {
        new EError(s"$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::dt "

  override val messages: List[EMsg] =
    EMsg(DT, "set") ::
    EMsg(DT, "cancel") ::
        EMsg(DT, "list") :: Nil
}

