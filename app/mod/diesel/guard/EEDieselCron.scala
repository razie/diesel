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
import razie.wiki.{Config, Services}
import razie.{Logging, Snakk, cdebug}
import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration, _}
import scala.collection.concurrent.TrieMap

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

        val now = new DateTime(ISOChronology.getInstanceUTC()) //DateTime.now()

        def dt = if (time.trim.isEmpty) now else DateTime.parse(time)

        def diff = dt.compareTo(now)

        val vdt = dt
        val vdiff = diff

        if (schedule.isEmpty && time.trim.isEmpty) {
          List(EVal(P(Diesel.PAYLOAD, "Either schedule or time needs to be provided", WTypes.wt.EXCEPTION)))
        } else if (!acceptPast && time.trim.nonEmpty && diff <= 0) {
          List(EVal(P(Diesel.PAYLOAD, s"Time is in the past (${dt} vs ${now} is ${diff})", WTypes.wt.EXCEPTION)))
        } else if (!DieselCron.ISENABLED) {
          List(EVal(P(Diesel.PAYLOAD, "DieselCron is DISABLED", WTypes.wt.EXCEPTION)))
        } else {
          val settings = new DomEngineSettings()
          settings.collectCount = Some(collectCount)

          val tickM: Either[DieselMsg, DieselMsgString] = cronMsg.map { s =>
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

          val doneM: Either[DieselMsg, DieselMsgString] = doneMsg.map { s =>
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
          val cid = DieselCron.createSchedule(name, schedule, time, realm, env, ctx.root.engine.map(_.id).mkString,
            count, tickM, doneM)
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
        val res = DieselCron.cancelSchedule(realm, name).map(_.schedId).mkString
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

