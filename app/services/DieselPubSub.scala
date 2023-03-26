package services

import akka.actor.{Actor, Props}
import com.google.inject.Singleton
import controllers.Emailer
import model.EventNeedsQuota
import play.libs.Akka
import razie.SM.cdebug
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

/**
  * send the pubSub a BCast(msg) to have the msg distributed in cluster
  */
object DieselPubSub extends razie.Logging with EventProcessor with TACB.Notifier {
  lazy val pubSub = Services.system.actorOf(Props[WikiPubSubActor], name = "WikiPubSub")

  /** send a message to the singleton actor - it better know what to do with it */
  override def ! (a: Any): Unit = {
    pubSub ! BCast (a)
  }

  def init():Unit = {}

  // init after cluster is ready
  def clusterReady():Unit = {
  }
}

/** a request to broadcast another event */
case class BCast (ev: Any, topic:Option[String] = None)

/** pub/sub events to/from cluster members
  *
  * this is needed to update local caches on remote changes
  *
  * not to use for critical work, duh - use ehcache/kafka for that
  */
class WikiPubSubActor extends Actor {
  var maxCount = 520 // supid protection against
  val TOPIC = "WikiEvents"

  import akka.cluster.pubsub.DistributedPubSub
  import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}

  val mediator = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(TOPIC, self)

  override def receive = {

    // local nod wants to broadcast
    case pub@BCast (ev, topic) => {
      cdebug << "PUBSUB_BCAST " + ev.toString
      //Audit.logdb("DEBUG", "event.bcast", "me: " + self.path + " from: " + sender.path, ev.toString().take(150))
      mediator ! Publish (TOPIC, ev)
    }

    // actual work - message came from another node
    case ev1: WikiEventBase if (sender.compareTo(self) != 0) => {
      cdebug << s"PUBSUB_RECEIVED ${ev1.toString} from $self"
      if (maxCount > 0) {
        maxCount -= 1
        Audit.logdb("DEBUG", "exec.event", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))

        WikiObservers.after(ev1)

      } else if (maxCount > -23) {
        maxCount -= 1
        Audit.logdb("WARNING", "maxCount messed up - event skipped", "me: " + self.path + " from: " + sender.path,
          ev1.toString.take(250))
      }
    }

    // actual work - message came from another node
    case ev1: WikiConfigChanged if (sender.compareTo(self) != 0) => {
      cdebug << s"PUBSUB_RECEIVED ${ev1.toString} from $self"
      if (maxCount > 0) {
        maxCount -= 1
        Audit.logdb("DEBUG", "exec.event", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))

        Services.config.reloadUrlMap // this will regenerate locally

      } else if (maxCount > -23) {
        maxCount -= 1
        Audit.logdb("WARNING", "maxCount messed up - event skipped", "me: " + self.path + " from: " + sender.path,
          ev1.toString.take(250))
      }
    }

    // actual work - message came from another node
    case ev1: Any if (sender.compareTo(self) != 0) => {
      cdebug << s"PUBSUB_RECEIVED ${ev1.toString} from $sender"
//      Audit.logdb("DEBUG", "exec.event", "me: " + self.path + " from: " + sender.path, ev1.toString().take(250))
      DieselPubSub.eat (ev1)
    }

    case ev1:Any if (sender.compareTo(self) == 0) =>
      cdebug << s"PUBSUB_RECEIVED_SELF (will ignore) ${ev1.toString} from $sender"

    case x@_ => Audit.logdb("DEBUG", "ERR_PUBSUB", x.getClass.getName)
  }
}
