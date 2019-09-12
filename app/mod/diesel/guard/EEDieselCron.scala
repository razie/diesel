/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.guard

import admin.Config
import akka.actor.{Actor, Cancellable, Props}
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import play.libs.Akka
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.DomEngineSettings
import razie.diesel.ext.{MatchCollector, _}
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.hosting.Website
import razie.tconf.TagQuery
import razie.wiki.Services
import razie.{Logging, Snakk}
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}

/** generic scheduler, using akka schedules
  *
  * a schedule has a handle and an update/create will reset/replace that handle.
  *
  * this is so that running the same logic all the time won't create million schedules
  * */
object DieselCron extends Logging {

  val realmSchedules = new HashMap[String, DomSchedule]

  lazy val worker =
    Akka.system.actorOf(Props[CronActor], name = "CronActor")

  /** actually does the work */
  class CronActor extends Actor {

    def receive = {
      case sc @ DomSchedule (id, expr, msg, r, e, c, singleton, ak) => {
        info(s"DomSchedule: $sc")

        if(realmSchedules.contains(id)) { // it's still ok
          val curr = realmSchedules(id)
          curr.currCount = curr.currCount + 1

          if(curr.count < 0 || curr.currCount < curr.count) { // fine... trigger it
            if(!singleton || DieselCron.isMasterNode(Website.forRealm(r).get)) {
              Services ! msg
            } else {
              clog << "DieselCron - not on master node for job: " + sc
            }
          } else {
            // todo need to stop the akka schedule
            curr.ref.map(_.cancel())

            Services ! DieselMsg(
              DieselMsg.CRON.ENTITY,
              DieselMsg.CRON.STOP,
              Map("name" -> id, "realm" -> r, "env" -> e, "count" -> curr.count),
              DieselTarget.ENV(r, e)
            )
          }
        }
      }
    }
  }

  /** create a schedule for a realm - should be called once per realm */
  def cancelSchedule(schedId: String) = {
    // remove existing
    // todo use actor for sync - this is unsafe
    val remove = realmSchedules.remove(schedId)

    remove.toList.map {
      _.ref.foreach(_.cancel())
    }

    s"Cron cancelled: $remove"
  }

  /** create a schedule for a realm - should be called once per realm */
  def createSchedule(schedId: String, schedExpr: String, realm: String, env: String, count:Long, msg: DieselMsg) = {
    // remove existing
    // todo use actor for sync - this is unsafe
    val remove = realmSchedules.remove(schedId)

    remove.toList.map {
      _.ref.foreach(_.cancel())
    }

    // free realms at least 5min
    var d = Duration.apply(schedExpr).asInstanceOf[FiniteDuration]
    val d5 = Duration.create(5, TimeUnit.MINUTES)
    val d30 = Duration.create(30, TimeUnit.SECONDS)
    val myRealms = "wiki,specs,oss,herc-cc,devblinq" // todo better for me and paid realms
    var sexpr = schedExpr
    if (d < d5 && !myRealms.contains(realm)) {
      d = d5
      sexpr = "5 minutes"
    } else if (d < d30) {
      d = d30
      sexpr = "30 seconds"
    }

    val sc = DomSchedule(schedId, sexpr, msg, realm, env, count)

    // start in a random time from now, but not too far in the future
    val akkaRef = Akka.system.scheduler.schedule(
      Duration.create(/*30 + */ (Math.random() * 30).toInt, TimeUnit.SECONDS),
      d,
      DieselCron.worker,
      sc
    )

    realmSchedules.put(schedId, sc.copy(ref = Some(akkaRef)))
    s"Schedule $schedId for $realm-$env at $schedExpr"
  }

  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isMasterNode(w:Website) = {
    // todo use akka singleton or something
    val me = InetAddress.getLocalHost.getHostName
    val url =
      (if(Config.isLocalhost) "http://" + Config.hostport
      else
        w.url) + "/diesel/engine/whoami"

    val active = Snakk.body(Snakk.url(url))
    val res = me equals active
    debug(s"isMasterNode: $res $me =? $active url = ${w.url}")
    me equals active
  }
}

// realm, env, CMD, schedStr, cancellable
case class DomSchedule(
  schedId: String,
  schedExpr: String,
  msg:DieselMsg,
  realm: String,
  env: String,
  count:Long = 0l,
  singleton:Boolean = true,
  ref: Option[Cancellable] = None) {

  var currCount:Long = 0l
}


/** actual share table. Collection model:
  * coll
  * */
class EEDieselCron extends EExecutor("diesel.cron") {
  final val DT = "diesel.cron"

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DT
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    val realm = ctx.root.settings.realm.mkString

    in.met match {

      case "set" => {
        val name = ctx.getRequired("name")
        val schedule = ctx.getRequired("schedule")
        val env = ctx.get("env").mkString
        val scount = ctx.get("count").mkString
        val desc = ctx.get("description").mkString
        val tq = ctx.get("tquery").mkString
        val collect = ctx.get("collect").map(_.toInt).filter(_ < 50).getOrElse(10) // keep 10 default for cron jobs
        val count = if (scount.length == 0) -1l else scount.toLong

        val settings = new DomEngineSettings()
        settings.collect = Some(collect)

        val cid = DieselCron.createSchedule(name, schedule, realm, env, count,
          DieselMsg(
            DieselMsg.CRON.ENTITY,
            DieselMsg.CRON.TICK,
            Map("name" -> name, "realm" -> realm, "env" -> env),
            DieselTarget.TQSPECS(realm, env, new TagQuery(tq)),
            Some(settings)
          )
        )

        List(
          EVal(P.fromTypedValue("cronId", cid))
        )
      }

      case "list" => {
        EVal(P.fromTypedValue(
          "payload",
          DieselCron.realmSchedules.map(_._2.toString).toList
        )) :: Nil
      }

      case "cancel" => {
        val name = ctx.getRequired("name")
        List(
          EVal(P(
            "payload",
            DieselCron.cancelSchedule(name)
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
        EMsg(DT, "list") :: Nil
}

