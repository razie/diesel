/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import model.{NoCyclesGraphLike, SGraphLike, SLink, SNode, VGraph}
import razie.Logging
import org.json.JSONArray
import razie.wiki.model.{WID, WikiEntry, Wikis}
import razie.js
import scala.collection.mutable.ListBuffer
import org.json.JSONObject
import razie.diesel.dom.WikiDomain
import razie.diesel.dom.RDomain
import scala.collection.mutable.HashMap

/** graph wiki controller */
object WG extends Logging {
  import js._

  case class WNode(we: Option[WikiEntry], iname: String = "", props:Map[String,String]=Map.empty) {
    def name = we.map(_.name) getOrElse iname
  }

  case class WGraph(val realm:String, override val root: Int, override val nodes: Seq[WNode], override val links: Seq[(Int, Int, String)])
    extends VGraph[WNode, String](root, nodes, links) {

    lazy val tosg = new SGraphLike[WNode, String](this)

    def tojmap = {
      val s = new ListBuffer[Any]()
      tosg.dag.foreachNode(
        (x: SNode[WNode, String], v: Int) => {
          s append x.value.props ++ Map(
            "id" -> x.value.name,
            "name" -> x.value.name,
            "url" -> s"http://localhost:9000/gapi/fromCat/$realm/${x.value.name}",
            "xurl" -> s"http://localhost:9000/gapi/fromCat/$realm/${x.value.name}",
            "links" -> x.glinks.map(l => Map(
              "to" -> l.z.value.name,
              "zRole" -> l.value,
              "type" -> "?")).toList)
        })
      s.toList
    }

    import js._

    def toJIT = {
      jt(tojmap) {
        case ("/", "xurl", u) => ("data" -> Map(
          "$dim" -> "10",
          "url" -> u))
        case (path, "links", l: List[_]) => ("adjacencies" -> jt(l, path) {
          case (_, "to", t) => ("nodeTo", t)
          case (_, "type", t) => ("data" -> Map("weight" -> "1"))
        })
      }
    }

    def tod3 = {
      // custom tree/root traversal
      val g: NoCyclesGraphLike[SNode[WNode, String], SLink[WNode, String]] = tosg.dag

      def node(x: SNode[WNode, String]): Map[String, Any] = {
        g.collect(x, None)
        Map(
          "name" -> x.value.name,
          "url" -> s"http://localhost:9000/gapi/fromCat/$realm/${x.value.name}?as=d3",
          "children" -> (x.glinks.collect {
            case l if (!g.isNodeCollected(l.z)) => node(l.z)
            case l if (g.isNodeCollected(l.z)) => Map("name" -> l.z.value.name)
          }.toList))
      }
      jt(node(g.root)) {
        // prune empty lists
        case (_, "children", x: List[_]) if (x.isEmpty) => ("" -> x)
      }
    }

    def tostree = {
      // custom tree/root traversal
      val g: NoCyclesGraphLike[SNode[WNode, String], SLink[WNode, String]] = tosg.dag

      def node(x: SNode[WNode, String]): Map[String, Any] = {
        g.collect(x, None)
        Map(
          "id" -> x.value.name,
          "name" -> x.value.name,
          "url" -> s"http://localhost:9000/gapi/fromCat/$realm/${x.value.name}?as=d3",
          "children" -> (x.glinks.collect {
            case l if (!g.isNodeCollected(l.z)) => node(l.z)
            case l if (g.isNodeCollected(l.z)) => Map(
              "id" -> l.z.value.name,
              "name" -> l.z.value.name
            )
          }.toList))
      }
      node(g.root)
//      jt(node(g.root)) {
//         prune empty lists
//        case (_, "children", x: List[_]) if (x.isEmpty) => ("" -> x)
//      }
    }
  }

  // single domain node
  def fromCat(cat: String, realm:String) = {

    val links = WikiDomain(realm).rdom.assocs.map { t=>
      (t.a, t.z, t.zRole)
    }

    // collect the links zEnd when they point to non-existing classes, just add them dynamically
    val nodes = (WikiDomain(realm).rdom.classes.values.toList.map(_.name) ::: links.map(_._2)).distinct.map {
      t => WNode(Wikis(realm).category(t), t)
    }

    def n(c: String) = {
      val i = nodes.indexWhere(_.name == c)
      if(i < 0) throw new IllegalStateException("index not found for: "+c)
      i
    }

    if(nodes.find(_.name == cat).isDefined)
      new WGraph(realm, n(cat), nodes, links.map { t => (n(t._1), n(t._2), t._3) })
    else
      fromRealm(realm)
  }

  // entire domain - makes up a "Domain" node and links to all topics
  def fromRealm(realm:String) = {
    val links = WikiDomain(realm).rdom.assocs.map { t=>
      (t.a, t.z, t.zRole)
    }

    // these conect the fake root node to all nodes so you can see them
    val fakes = Wikis(realm).categories.toList.map(c => ("Domain", c.name, "fake"))

    // collect the links zEnd when they point to non-existing classes, just add them dynamically
    val nodes = WNode(None, "Domain", Map("realm"->realm)) :: (
      WikiDomain(realm).rdom.classes.values.toList.map(_.name) ::: links.map(_._2)).distinct.map {
      t => WNode(Wikis(realm).category(t), t)
    }

    def n(c: String) = {
      val i = nodes.indexWhere(_.name == c)
      if(i < 0) throw new IllegalStateException("index not found for: "+c)
      i
    }
    new WGraph(realm, n("Domain"), nodes, (fakes ::: links).map { t => (n(t._1), n(t._2), t._3) })
  }

  // entire domain - makes up a "Domain" node and links to all topics
  def rdomain(wpath:String, addRoot:Boolean=true) = {
    val r = (for (
      wid <- WID.fromPath(wpath);
      page <- wid.page;
      dom <- WikiDomain.domFrom(page)
    ) yield dom
    ) getOrElse RDomain.empty

    val rdom = if (addRoot) r.revise.addRoot else r.revise

    val links = rdom.assocs.map { t=>
      (t.a, t.z, t.zRole)
    }

    // collect the links zEnd when they point to non-existing classes, just add them dynamically
    val nodes = (rdom.classes.values.toList.map(_.name) ::: links.map(_._2)).distinct.map {
      t => WNode(None, t)
    }

    // these conect the fake root node to all nodes so you can see them
    val fakes = Nil //nodes.map(c => ("Domain", c.name, "fake"))

    def n(c: String) = {
      val i = nodes.indexWhere(_.name == c)
      if(i < 0) throw new IllegalStateException("index not found for: "+c)
      i
    }
    new WGraph("?", n("Domain"), nodes, (fakes ::: links).map { t => (n(t._1), n(t._2), t._3) })
  }
}
