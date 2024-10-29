/*  ____    __    ____  ____  ____  ___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.diesel.dom.DieselAssets
import razie.wiki.model.{DCNode, WID}
import scala.Option.option2Iterable
import services.DieselCluster
//import scala.concurrent.ExecutionContext.Implicits.global

object DomRefs {
  final val CAT_DIESEL_ENGINE = "DieselEngine"
  final val CAT_DIESEL_NODE = "DieselNode"

  def isLocal(node: Option[DCNode]): Boolean = node.isEmpty || isLocal(node.get)

  def isLocal(node: DCNode): Boolean = {
    node.name.compareTo(DieselCluster.clusterNodeSimple) == 0 || node.name.trim.isEmpty
  }

  def isRemote(node: Option[DCNode]): Boolean = !isLocal(node)

  def setNode (node:String) = {
    if (node == DieselCluster.clusterNodeSimple || node.trim.isEmpty) None else Option(node)
  }

  def parseDomAssetRef (str:String) = {
    WID.widFromSegWithNode(str) match {
      case Some((n,r,c,i,s)) => Option(DomAssetRef (c, i, r, s, n))
      case None => None
    }
  }
}

/** this is a reference inside another engine, a correlation point
  *
  * with node hint: format is parent/(world,node)realm.cat:name#section
  *
  * @param id         engine id
  * @param section  for engines, node inside the engine (suspended there or just referenced)
  * @param node     node that manages the other engine hopefully, None means local (default)
  */
case class DomAssetRef (
  cat:String,
  id:String,
  realm:Option[String] = None,
  section:Option[String] = None,
  node:Option[DCNode] = None
) {
  @transient val isLocal:Boolean = DomRefs.isLocal(node) // optimize this comparison
  @transient val isRemote:Boolean = !DomRefs.isLocal(node) // optimize this comparison

  def mkString:String = node.map(x=> s"($x)$cat:").mkString + id + section.map(x=>"#"+x).mkString
  def mkEngRef:String = node.map(x=> s"($x)$cat:").mkString + id

  /** fully qualified ref to send to other node */
  def fullNodeRef = if (node.isEmpty) this.copy (node=Option(DCNode(DieselCluster.clusterNodeSimple))) else this

  def withNode (node:DCNode) = {
    val other = if (DomRefs.isLocal(node)) None else Option(node)
    this.copy (node=other)
  }

  def href =
    if(isLocal) DieselAssets.mkAhref(WID(cat, this.id))
    else DieselAssets.mkAhref(WID(cat, this.id), mkEngRef)

  def toj = Map (
    "cat" -> cat,
    "id" -> id,
  ) ++ realm.map (x=> Map("realm" -> x)).getOrElse(Map.empty
  ) ++ section.map (x=> Map("section" -> x)).getOrElse(Map.empty
  ) ++ node.map (x=> Map("node" -> x.name)).getOrElse(Map.empty
  )
}
