/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

package services

import akka.actor.{Actor, ActorRef, Props, SupervisorStrategy}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.google.inject.Singleton
import razie.audit.Audit
import razie.{Logging, Snakk, clog}
import razie.wiki.model._
import razie.wiki.{Config, EventProcessor, Services}
import scala.collection.mutable.ListBuffer
import services.DieselSingleton.{DSEnd, DSStartedOn, DSWhois, DSAskWhois, singletonProxyActor}

/** using classic akka singleton
  *
  * https://doc.akka.io/docs/akka/current/cluster-singleton.html
  *
  * https://doc.akka.io/docs/akka/2.5/cluster-singleton.html
  *
  * todo some more config options in https://doc.akka.io/docs/akka/current/typed/cluster-singleton.html#configuration
  */
object DieselSingleton extends Logging with EventProcessor with TACB.Notifier {

  var singletonHistory = ListBuffer[String]()

  var currentSingletonNode = "?"

  def addSingletonHistory (str: String) = {
    singletonHistory = singletonHistory.takeRight(20)
    singletonHistory += str
  }

  // termination message (when singleton moves)
  class DSMsg
  object DSEnd extends DSMsg
  case class DSStartedOn (node:String) extends DSMsg
  case class DSWhois     (node:String) extends DSMsg
  case class DSAskWhois  (node:String) extends DSMsg

  lazy val singletonManagerActor = Services.system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props[DieselSingletonActor],
      terminationMessage = DSEnd,
      settings = ClusterSingletonManagerSettings(Services.system)/*.withRole("worker")*/),
    name = "consumer")

  lazy val singletonProxyActor = Services.system.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = "/user/consumer",
      settings = ClusterSingletonProxySettings(Services.system)/*.withRole("worker")*/),
    name = "consumerProxy")

  /** send a message to the singleton actor - it better know what to do with it */
  override def ! (a: Any): Unit = {
    singletonProxyActor ! a
  }

  // called in order after cluster init
  def init():Unit = {
    singletonManagerActor
    singletonProxyActor
    singletonProxyActor ! "Other node just came up..."
  }

  // init after cluster is ready
  def clusterReady():Unit = {

    Services.cluster.pubSub.subscribe (TACB.withFunc({
      case DSStartedOn (node) => currentSingletonNode = node
    }, msgCls = classOf[DSMsg]))

    singletonProxyActor ! DSWhois(DieselCluster.clusterNodeSimple) // ask who is it

    // todo weird - the singleton messages buffered before the singleton is connected are not sent??
    razie.Threads.fork {
      while (currentSingletonNode == "?") {
        Thread.sleep(1000)
        singletonProxyActor ! DSWhois(DieselCluster.clusterNodeSimple) // ask who is it
      }
    }
    // todo should ask in loop in case the real singleton is not up...
  }
}

/** the singleton processor - this actor is managed and only processes messages on the singleton */
class DieselSingletonActor extends Actor with razie.Logging {

  def nodeName = Services.config.node
  def localQuiet = Services.config.localQuiet
  def localhost = Services.config.isLocalhost

  override def postStop(): Unit = {
    DieselCluster.masterNodeStatus = Some(false)
    DieselSingleton.addSingletonHistory ("SingletonActor postStopped")
  }

  override def preStart(): Unit = {
    DieselCluster.masterNodeStatus = Some(true)
    DieselSingleton.addSingletonHistory ("SingletonActor started")
    DieselSingleton.currentSingletonNode = DieselCluster.clusterNodeSimple
    DieselPubSub ! DSStartedOn (DieselCluster.clusterNodeSimple)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    DieselCluster.masterNodeStatus = Some(true)
    DieselSingleton.addSingletonHistory ("SingletonActor pre-started")
  }

  override def receive = {

    case m@DSEnd => {
      debug (s"Received $m")
      DieselCluster.masterNodeStatus = Some(false)
    }

    case m@DSWhois(asker) => {
      debug (s"Received $m")
      DieselPubSub ! DSStartedOn (DieselCluster.clusterNodeSimple)
    } // I'm processing it so it must be me

    case m@_ => {
      debug (s"Received $m")
      DieselSingleton.eat(m)
    } // received other messages
  }
}
