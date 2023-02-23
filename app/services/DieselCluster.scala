package services

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.{Cluster, Member, MemberStatus}
import com.razie.pub.comms.CommRtException
import java.net.InetAddress
import play.api.mvc.Cookies
import razie.{Logging, Snakk, clog}
import razie.hosting.{Website, WikiReactors}
import razie.wiki.model.DCNode
import razie.wiki.{Config, EventProcessor, Services}
import scala.collection.mutable.ListBuffer
import services.DieselCluster.masterNodeStatus


/** embed and simpliyfy cluster services usage, based on akka */
object DieselCluster {

  // cluster services
  def pubSub = Services.instance.dieselCluster.pubSub
  def singleton = Services.instance.dieselCluster.singleton

  def akkaCluster = Services.instance.dieselCluster.akkaCluster

  // todo deprecate
  var oldclusterNodes : List[DCMember] = Nil

  def clusterNodeDns = Services.cluster.clusterNodeDns
  def clusterNodeSimple = Services.cluster.clusterNodeSimple
  def clusterNodeIp = Services.cluster.clusterNodeIp
  def clusterModeBool = Services.cluster.clusterModeBool

  // todo more efficient
  def me = DCNode(Services.cluster.clusterNodeSimple)

  def clusterNodesJson = Services.cluster.clusterNodesJson

  // todo optimize this - we need to avoid calling REST every time...
  var masterNodeStatus: Option[Boolean] = None

  // cluster history - last events

  var clusterHistory = ListBuffer[String]()

  def addClusterHistory (msg: String) = {
    clog << "CLUSTER_HIST: " + msg
    clusterHistory = clusterHistory.takeRight(20)
    clusterHistory += msg
  }
}

class DieselCluster extends razie.Logging {
  def system = Services.system

  def getCurProxyNode(cookies:Option[Cookies]) = cookies.flatMap(_.get("dieselProxyNode")).map(_.value).getOrElse(clusterNodeSimple)

  var _isClusterReady = false
  def isClusterReady = totalNodes > 0

  lazy val pubSub = DieselPubSub
  lazy val singleton = DieselSingleton

  var akkaCluster:akka.cluster.Cluster = null // Cluster(system)
  lazy val clusterListener = system.actorOf(Props[SimpleClusterListener], name = "SimpleClusterListener")

  /** full name of cluster node */
  final val localNodeDns = java.net.InetAddress.getLocalHost.getCanonicalHostName
  final val localNodeSimple = java.net.InetAddress.getLocalHost.getHostName.replaceFirst("\\..*", "")
  final val localNodeIp = java.net.InetAddress.getLocalHost.getHostAddress

  private var _me : Option[DCMember] = None

  def init() : Unit = {
    clusterListener ! "started eh"
  }

  def me = {
    _me.getOrElse {
      val x = akkaCluster.selfMember
      _me = Option(new DCMember(x))
      _me.get
    }
  }

  def clusterNodeDns = me.dnsName
  def clusterNodeSimple = me.node
  def clusterNodeIp = me.ip

  final val clusterModeBool = Services.config.clusterModeBool

  def clusterStats = Map(
    "dieselLocalUrl" -> Services.config.dieselLocalUrl,
    "node" -> Services.config.node,

    "singleton" -> Map(
      "currentSingleton" -> DieselSingleton.currentSingletonNode,
      "isSingletonNode" -> isSingletonNode(w = Option(Website.dflt)),
      "singletonHistory" -> DieselSingleton.singletonHistory.toList.reverse.take(10)
    ),

    "clusterState" -> akkaCluster.state.toString,
    "clusterHistory" -> DieselCluster.clusterHistory.toList.reverse.take(10),

    "identity" -> Map(
      "localNodeDns" -> localNodeDns,
      "localNodeSimple" -> localNodeSimple,
      "localNodeIp" -> localNodeIp,
      "clusterNodeDns" -> clusterNodeDns,
      "clusterNodeSimple" -> clusterNodeSimple,
      "clusterNodeIp" -> clusterNodeIp,
    ),
    "clusterStyle" -> Services.config.clusterStyle,
    "clusterNodes" -> clusterNodes.map(_.toj),
    "akkaSystemName" -> system.name,
    "akkaSystem" -> system.toString
    )

  def totalNodes = akkaCluster.state.members.size
  def totalNodesUp = akkaCluster.state.members.count(_.status == MemberStatus.Up)

  def clusterNodes = akkaCluster.state.members/*.filter (_.status == MemberStatus.Up)*/.toList map {x =>
    new DCMember(x)
  }

  def clusterNodesJson = clusterNodes map (_.toj)

  /** is this the master / singleton node in a cluster? */
  def isSingletonNode (w: Option[Website] = None) : Boolean = Config.clusterStyle match {
    case "kube" => clusterNodeSimple == "diesel-0"
    case "port" if (w.isDefined) => isSingletonNodeApache(w.get)
    case "akka" => masterNodeStatus.getOrElse(false) // updated by akka singleton service
    case "none" => true // me is it
    case _ => throw new IllegalArgumentException("no Website or Config.clusterStyle unknown: " + Config.clusterStyle)
  }


  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isSingletonNodeApache (w: Website) : Boolean = {
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

  /** called when cluster is ready */
  def clusterIsReadyNow(): Unit = {
    Services.cluster._isClusterReady = true
    DieselCluster.addClusterHistory("Cluster is Up")
    DieselPubSub.clusterReady() // todo register for message instead
    DieselSingleton.clusterReady() // todo register for message instead
  }

  var curLbIdx = 0
  def routeToNode (routing:String) : Option[DCNode] = (routing match {
    case "local" => Some(DieselCluster.clusterNodeSimple)
    case "singleton" => Some(DieselSingleton.currentSingletonNode)
    case "other" => clusterNodes.find(_.node != clusterNodeSimple).map(_.node)
//    case "lb" => Option(clusterNodes((math.random()*clusterNodes.size).toInt).node) // todo smarter lb
    case "lb" => {
      if(clusterNodes.size > 0) {
        curLbIdx = (curLbIdx + 1) % clusterNodes.size
        Option(clusterNodes(curLbIdx).node)
      } else None
    } // todo smarter lb
  }).map(DCNode)

}

/** cluster member node */
case class DCMember (hostPort:String, node:String, dnsName:String, ip:String, roles:Set[String], system:String, status:MemberStatus) {

  def port = if(Services.config.clusterStyleKube || hostPort.endsWith("9002")) 9000 else 9001
  def url = dnsName + ":" + port

  def this(x:Member) = this(
    system = x.uniqueAddress.address.system,
    hostPort = x.uniqueAddress.address.hostPort,
      node =
        if(Services.config.clusterStyleKube) x.uniqueAddress.address.host.mkString.replaceFirst("\\..*", "")
        else x.uniqueAddress.address.hostPort.replaceFirst("^.*@", ""), // somehow it includes the system
      dnsName = x.uniqueAddress.address.host.mkString,
      ip = x.uniqueAddress.address.host.mkString,
      roles = x.roles,
      status = x.status)

    def toj = Map (
    "name" -> node,
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

class SimpleClusterListener extends Actor with razie.Logging {

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    DieselCluster.akkaCluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = DieselCluster.akkaCluster.unsubscribe(self)

  override def receive = {

    case MemberUp(member) => {
      val msg = s"Member is Up: ${member.address}"
      DieselCluster.addClusterHistory(msg)
      if (!Services.cluster._isClusterReady) {
        Services.cluster.clusterIsReadyNow()
      }
    }

    case UnreachableMember(member) =>
      val msg = ("Member detected as unreachable: " + member)
      DieselCluster.addClusterHistory(msg)

    case MemberRemoved(member, previousStatus) =>
      val msg = (s"Member is Removed: ${member.address} after ${previousStatus}")
      DieselCluster.addClusterHistory(msg)

    case state: CurrentClusterState => {
      val msg = (s"CurrentClusterState: $state")
      DieselCluster.addClusterHistory(msg)
//      DieselCluster.clusterNodes = state.members.filter(_.status == MemberStatus.Up) foreach register
      DieselCluster.oldclusterNodes = state.members.toList map { x => new DCMember(x) }
      if (!Services.cluster._isClusterReady && state.members.size > 0) {
        Services.cluster.clusterIsReadyNow()
      }
    }

    case _: MemberEvent => // ignore
  }
}
