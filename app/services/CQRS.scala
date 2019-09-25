package services

import java.util.concurrent.TimeUnit
import mod.diesel.controllers.DomFiddles
import akka.actor.{Actor, Props}
import controllers.Emailer
import model.EventNeedsQuota
import razie.{clog, cout}
import razie.wiki.{EventProcessor, Services}
import play.libs.Akka
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import com.google.inject.Singleton
import mod.diesel.guard.DomGuardian.worker
import razie.audit.Audit
import razie.diesel.engine.DomEngineSettings
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget, ScheduledDieselMsg}
import razie.wiki.model.features.WikiCount
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

class InitAlligator

/** a request to broadcast another event */
case class BCast(ev: WikiEventBase)

/** async wiki observers
  *
  * it will also pub/sub in cluster, specific events
  */
class WikiAsyncObservers extends Actor {

  lazy val pubSub = Akka.system.actorOf(Props[WikiPubSub], name = "WikiPubSub")

  def nodeName = Some(Services.config.node) //Play.current.actorSystem.name)

  def receive = {
    case wc: WikiConfigChanged => {
      val ev = new WikiConfigChanged(nodeName.mkString)
      WikiObservers.after(ev)
      pubSub ! BCast(ev)
    }

    case wa: WikiAudit => {
      val ev = wa.copy(node = nodeName)
      ev.create
      WikiObservers.after(ev.toEvent)
      clusterize(ev.toEvent)
    }

    case ev1: WikiEvent[_] => {
      WikiObservers.after(ev1)
      clusterize(ev1.copy(node = nodeName.mkString))
    }

    case a: Audit => {
      a.copy(node = nodeName).create
    }

    case wc: WikiCount => wc.inc

    case init: InitAlligator => {
      //todo why do i need this?
      clog << self.path
      clog << pubSub.path
    }

    case EventNeedsQuota(s1, s2, _) =>
      SendEmail.withSession("rk") { implicit mailSession =>
        Emailer.sendEmailNeedQuota(s1, s2)
      }

    case m@DieselMsgString(s, target, _, set) => {
      runMsg(m, target, set)
    }

    case m@DieselMsg(e, a, p, target, osettings) => {
      runMsg(m.toMsgString, target, osettings)
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

    case x@_ => {
      Audit.logdb("ERR_ALLIGATOR", x.getClass.getName)
    }
  }

  def runMsg(m:DieselMsgString, target:DieselTarget, osettings:Option[DomEngineSettings] = None) = {
    // todo auth/auth
    cout << "======== DIESEL MSG: " + m
    val settings = osettings.getOrElse(new DomEngineSettings())
    settings.realm = Some(target.realm)
    // important to use None here, to let the engines use the envList setting otherwise
    settings.env = if(target.env == DieselTarget.DEFAULT) None else Some(target.env)

    val ms = m.mkMsgString

    DomFiddles
        .runDom(ms, target.specs, target.stories, settings)
        .map { res =>
      // don't audit these frequent ones
      if(
        m.msg.startsWith(DieselMsg.WIKI_UPDATED) ||
        m.msg.startsWith(DieselMsg.CRON_TICK) ||
        m.msg.startsWith(DieselMsg.GUARDIAN_POLL) ||
false//        m.msg.startsWith(DieselMsg.REALM_LOADED)
      ) {
        clog << "DIESEL_MSG: " + m + " : RESULT: " + res.get("value").mkString.take(500)
      } else {
        val id = res.get("engineId").mkString
        // this will also clog it - no need to clog it
        Audit.logdb(
          "DIESEL_MSG",
          m.toString,
          s"[[DieselEngine:$id]]",
          "result-length: "+res.get("value").mkString.length,
          res.get("value").mkString.take(500)
        )
      }
    }
  }


  def clusterize(ev: WikiEvent[_]) = {
    if (
      Services.config.clusterMode == "yes" &&
        WikiAudit.isUpd(ev.action) ||
        ev.action == "AUTH_CLEAN"
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
        Audit.logdb("WARNING", "maxCount messed up - event skipped", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))
      }
    }
    case x@_ => Audit.logdb("DEBUG", "ERR_CLUSTER_BRUTE", x.getClass.getName)
  }
}
