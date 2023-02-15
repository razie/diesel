package services

import akka.actor.{Actor, Props}
import com.google.inject.Singleton
import controllers.Emailer
import model.EventNeedsQuota
import play.libs.Akka
import razie.audit.Audit
import razie.clog
import razie.diesel.engine.exec.EEDbEvent
import razie.diesel.model.{DieselMsg, DieselMsgString, ScheduledDieselMsg, ScheduledDieselMsgString}
import razie.hosting.WikiReactors
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.wiki.{Config, EventProcessor, Services}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}

/** main event dispatcher implementation */
@Singleton
class RkCqrs extends EventProcessor {
  lazy val auditor = Services.system.actorOf(Props[WikiAsyncObservers], name = "WikiAsyncObservers")

  override def ! (a: Any): Unit = {
    auditor ! a
  }
}

class InitAlligator

/** async wiki observers
  *
  * it will also pub/sub in cluster, specific events
  */
class WikiAsyncObservers extends Actor {

  // todo someone initializes this twice...

  def nodeName = Some(Services.config.node)
  def localQuiet = Services.config.localQuiet

  def localhost = Services.config.isLocalhost

  override def receive = {

    case WikiConfigChanged (_, c) => {
      val ev = new WikiConfigChanged(nodeName.mkString, c)
      WikiObservers.after(ev)
      DieselPubSub ! BCast (ev)
    }

    case wa: WikiAudit => {

      val ev = wa.copy(node = nodeName)

      if (!localQuiet || !localhost) {
        ev.create
      } else {
        clog << "localQuiet !! WikiAudit"
      }

      WikiObservers.after(ev.toEvent)
      clusterize(ev.toEvent)
    }

    case ev1: WikiEvent[_] => {

      if (!ev1.consumedAlready) {
        WikiObservers.after(ev1)
      }
      clusterize(ev1.copy(node = nodeName.mkString))
    }

    case ev1: EEDbEvent => {

      if (!ev1.consumedAlready) {
        WikiObservers.after(ev1)
      }
      clusterize(ev1)
    }

    case a: Audit => {

      if (!localQuiet || !localhost) {

        // list of codes to not put in db:
        a.msg match {
          case "DIESEL_FIDDLE_iRUNDOM" =>
          case "ENTITY_CREATE" =>
          case "ENTITY_UPDATE" =>
          case "DEBUG" =>

          // all else in db
          case _ =>
            a.copy(node = nodeName).create
        }
      } else {
        clog << "localQuiet !! Audit"
      }
    }

    case wc: WikiCount => wc.inc

    case init: InitAlligator => {
      //todo why do i need this?
      clog << self.path
      clog << DieselPubSub.pubSub.path
      WikiReactors.init()
    }

    case EventNeedsQuota(s1, s2, _) =>
      SendEmail.withSession("rk") { implicit mailSession =>
        Emailer.sendEmailNeedQuota(s1, s2)
      }

    case m:DieselMsgString => {
      m.startMsg
    }

    case m:DieselMsg => {
      m.toMsgString.startMsg
    }

    case m@ScheduledDieselMsg(s, msgforLater) => {
      // todo auth/auth
      clog << s"======== SCHEDULE DIESEL MSG: $s - $m"
      context.system.scheduler.scheduleOnce(
        Duration.apply(s).asInstanceOf[FiniteDuration],
        this.self,
        msgforLater
      )
    }

    case m@ScheduledDieselMsgString(s, msgforLater) => {
      // todo auth/auth
      clog << s"======== SCHEDULE DIESEL MSG: $s - $m"
      context.system.scheduler.scheduleOnce(
        Duration.apply(s).asInstanceOf[FiniteDuration],
        this.self,
        msgforLater
      )
    }

    case x@_ => {
      Audit.logdb("ERR_ALLIGATOR", x.getClass.getName)
    }
  }

  def clusterize(ev: WikiEventBase) = {
    if (
      Services.config.clusterModeBool &&
          (
              ev.isInstanceOf[WikiEvent[_]] && {
                val ee = ev.asInstanceOf[WikiEvent[_]]
                WikiAudit.isUpd(ee.action) ||
                    ee.action == "AUTH_CLEAN"
              } ||
                  !ev.isInstanceOf[WikiEvent[_]]
              )// some other events
    )
      DieselPubSub ! BCast(ev)
  }
}
