package services

import akka.actor.{Actor, ActorLogging, Props}
import akka.cluster.{Cluster, MemberStatus}
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

object DieselCluster extends razie.Logging {

  lazy val akkaCluster = Cluster(Akka.system)
  lazy val clusterListener = Akka.system.actorOf(Props[SimpleClusterListener], name = "SimpleClusterListener")

  /** full name of cluster node */
  final val clusterNodeDns = java.net.InetAddress.getLocalHost.getCanonicalHostName
  final val clusterNodeSimple = java.net.InetAddress.getLocalHost.getHostName.replaceFirst("\\..*", "")
  final val clusterNodeIp = java.net.InetAddress.getLocalHost.getHostAddress
  final val clusterModeBool = Config.clusterModeBool

  def clusterProps = Map(
      "dieselLocalUrl" -> Services.config.dieselLocalUrl,
      "node" -> Services.config.node,

    "clusterStyle" -> Services.config.clusterStyle,
    "clusterNodeDns" -> clusterNodeDns,
    "clusterNodeSimple" -> clusterNodeSimple,
    "clusterNodeIp" -> clusterNodeIp,
    "clusterNodes" -> clusterNodes.map(_.toj),
    "akkaSystemName" -> Akka.system().name,
    "akkaSystem" -> Akka.system().toString,
    "x" -> akkaCluster.settings.toString
    )

  // todo deprecate
  var oldclusterNodes : List[DCMember] = Nil

  def clusterNodes = akkaCluster.state.members/*.filter (_.status == MemberStatus.Up)*/.toList map {x =>
    DCMember(
      hostPort = x.uniqueAddress.address.hostPort,
      node = x.uniqueAddress.address.host.mkString.replaceFirst("\\..*", ""),
      dnsName = x.uniqueAddress.address.host.mkString,
      ip = x.uniqueAddress.address.system,
      roles = x.roles,
      status = x.status)
  }

  def clusterNodesJson = clusterNodes map (_.toj)

  /** is this the master / singleton node in a cluster? */
  def isSingletonNode (w: Website) = isSingletonNodeApache(w)

  // todo optimize this - we need to avoid calling REST every time...
  var masterNodeStatus: Option[Boolean] = None

  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isSingletonNodeApache (w: Website) = {
    // todo use akka singleton or something
    masterNodeStatus.getOrElse {
      val me = InetAddress.getLocalHost.getHostName
      val url =
        (if (Services.config.isLocalhost) "http://" + Services.config.hostport
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

/** cluster member node */
case class DCMember (hostPort:String, node:String, dnsName:String, ip:String, roles:Set[String], status:MemberStatus) {
  def toj = Map (
    "node" -> node,
    "dnsName" -> dnsName,
    "ip" -> ip,
    "hostport" -> hostPort,
    "roles" -> roles.toList,
    "status" -> status.toString
  )
}


import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

class SimpleClusterListener extends Actor with ActorLogging {

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
  DieselCluster.akkaCluster.subscribe(self, initialStateMode = InitialStateAsEvents,
    classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = DieselCluster.akkaCluster.unsubscribe(self)

  override def receive = {
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}",
        member.address, previousStatus)

    case state: CurrentClusterState =>
//      DieselCluster.clusterNodes = state.members.filter(_.status == MemberStatus.Up) foreach register
      DieselCluster.oldclusterNodes = state.members.toList map {x =>
        DCMember(
          hostPort = x.uniqueAddress.address.hostPort,
          node = x.uniqueAddress.address.host.mkString.replaceFirst("\\..*", ""),
          dnsName = x.uniqueAddress.address.host.mkString,
          ip = x.uniqueAddress.address.system,
          roles = x.roles,
          status = x.status)
      }

    case _: MemberEvent => // ignore
  }
}
