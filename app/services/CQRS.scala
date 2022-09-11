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
  lazy val auditor = Akka.system.actorOf(Props[WikiAsyncObservers], name = "WikiAsyncObservers")

  def !(a: Any) {
    auditor ! a
  }
}

object StaticsEh {
  lazy val pubSub = Akka.system.actorOf(Props[WikiPubSub], name = "WikiPubSub")
}

class InitAlligator

/** a request to broadcast another event */
case class BCast(ev: WikiEventBase)

/** async wiki observers
  *
  * it will also pub/sub in cluster, specific events
  */
class WikiAsyncObservers extends Actor {

  // todo someone initializes this twice...
  lazy val pubSub = StaticsEh.pubSub

  def nodeName = Some(Services.config.node) //Play.current.actorSystem.name)
  def localQuiet = Services.config.localQuiet

  def localhost = Services.config.isLocalhost

  def receive = {
    case WikiConfigChanged(_, c) => {
      val ev = new WikiConfigChanged(nodeName.mkString, c)
      WikiObservers.after(ev)
      pubSub ! BCast(ev)
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
      clog << pubSub.path
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
      Services.config.clusterMode == "yes" &&
          (
              ev.isInstanceOf[WikiEvent[_]] && {
                val ee = ev.asInstanceOf[WikiEvent[_]]
                WikiAudit.isUpd(ee.action) ||
                    ee.action == "AUTH_CLEAN"
              } ||
                  !ev.isInstanceOf[WikiEvent[_]]
              )// some other events
    )
      pubSub ! BCast(ev)
  }
}

/** pub/sub events to/from cluster members
  *
  * this is needed to update local caches on remote changes
  *
  * not to use for critical work, duh - use ehcache/kafka for that
  */
class WikiPubSub extends Actor {
  var maxCount = 520 // supid protection against
  val TOPIC = "WikiEvents"

  import akka.cluster.pubsub.DistributedPubSub
  import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}

  val mediator = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(TOPIC, self)

  def receive = {
    // to broadcast
    case pub@BCast(ev) => {
      clog << "CLUSTER_BRUTE_BCAST " + ev.toString
//      Audit.logdb("DEBUG", "event.bcast", "me: " + self.path + " from: " + sender.path, ev.toString().take(150))
      mediator ! Publish(TOPIC, ev)
    }

    // actual work - message came from another node
    case ev1: WikiEventBase if (sender.compareTo(self) != 0) => {
      clog << s"CLUSTER_BRUTE_RECEIVED ${ev1.toString} from $self"
      if (maxCount > 0) {
        maxCount -= 1
        Audit.logdb("DEBUG", "exec.event", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))

        WikiObservers.after(ev1)

      } else if (maxCount > -23) {
        maxCount -= 1
        Audit.logdb("WARNING", "maxCount messed up - event skipped", "me: " + self.path + " from: " + sender.path,
          ev1.toString().take(250))
      }
    }

    // actual work - message came from another node
    case ev1: WikiConfigChanged if (sender.compareTo(self) != 0) => {
      clog << s"CLUSTER_BRUTE_RECEIVED ${ev1.toString} from $self"
      if (maxCount > 0) {
        maxCount -= 1
        Audit.logdb("DEBUG", "exec.event", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))

        Services.config.reloadUrlMap // this will regenerate locally

      } else if (maxCount > -23) {
        maxCount -= 1
        Audit.logdb("WARNING", "maxCount messed up - event skipped", "me: " + self.path + " from: " + sender.path,
          ev1.toString().take(250))
      }
    }

    case x@_ => Audit.logdb("DEBUG", "ERR_CLUSTER_BRUTE", x.getClass.getName)
  }
}