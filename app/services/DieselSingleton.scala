package services

import akka.actor.{Actor, ActorRef, Props, SupervisorStrategy}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.google.inject.Singleton
import com.razie.pub.comms.CommRtException
import controllers.Emailer
import java.net.InetAddress
import model.EventNeedsQuota
import play.libs.Akka
import razie.audit.Audit
import razie.{Logging, Snakk, clog}
import razie.diesel.engine.exec.EEDbEvent
import razie.diesel.model.{DieselMsg, DieselMsgString, ScheduledDieselMsg, ScheduledDieselMsgString}
import razie.hosting.{Website, WikiReactors}
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.wiki.{Config, EventProcessor, Services}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}

/** using classic akka singleton
  *
  * https://doc.akka.io/docs/akka/current/cluster-singleton.html
  */
object DieselSingleton extends Logging with EventProcessor {
  object DSEnd

  lazy val singletonManagerActor = Services.system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props[DieselSingletonActor],
      terminationMessage = DSEnd,
      settings = ClusterSingletonManagerSettings(Services.system)),
    name = "consumer")

  lazy val singletonProxyActor = Services.system.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = "/user/consumer",
      settings = ClusterSingletonProxySettings(Services.system)),
    name = "consumerProxy")

  /** send a message to the singleton actor - it better know what to do with it */
  override def ! (a: Any): Unit = {
    singletonProxyActor ! a
  }

}

/** the singleton processor */
class DieselSingletonActor extends Actor {

  def nodeName = Services.config.node
  def localQuiet = Services.config.localQuiet
  def localhost = Services.config.isLocalhost

  override def receive = {
    case WikiConfigChanged(_, c) => {
      val ev = new WikiConfigChanged(nodeName.mkString, c)
      WikiObservers.after(ev)
//      pubSub ! BCast(ev)
    }

    case ev1: WikiEvent[_] => {

      if (!ev1.consumedAlready) {
        WikiObservers.after(ev1)
      }
//      clusterize(ev1.copy(node = nodeName.mkString))
    }

    case init: InitAlligator => {
    }

//    case m:DieselMsgString => {
//      m.startMsg
//    }
//
//    case m:DieselMsg => {
//      m.toMsgString.startMsg
//    }

    case x@_ => {
      Audit.logdb("ERR_ALLIGATOR SINGLETON", x.getClass.getName)
    }
  }

//  def clusterize(ev: WikiEventBase) = {
//    if (
//      Services.config.clusterMode == "yes"
//    )
//      pubSub ! BCast(ev)
//  }
}

