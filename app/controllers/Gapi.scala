/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import model.Sec.EncryptedS
import play.api.mvc.Action
import razie.Logging
import model.Wikis
import model.WikiEntry
import model.WikiDomain
import razie.gg
import org.json.JSONArray
import scala.collection.mutable.ListBuffer
import org.json.JSONObject
import scala.collection.mutable.HashMap
import admin.js

/** graph wiki controller */
object WG extends Logging {
  import js._

  case class WNode(we: Option[WikiEntry], iname: String = "") {
    def name = we.map(_.name) getOrElse iname
  }

  case class WGraph(override val root: Int, override val nodes: Seq[WNode], override val links: Seq[(Int, Int, String)]) extends gg.VGraph[WNode, String](root, nodes, links) {

    lazy val tosg = new gg.SGraphLike[WNode, String](this)

    def tojmap = {
      val s = new ListBuffer[Any]()
      tosg.dag.foreachNode(
	(x: gg.SNode[WNode, String], v: Int) => {
	  s append Map(
	    "id" -> x.value.name,
	    "name" -> x.value.name,
	    "url" -> s"http://localhost:9000/gapi/d3dom/${x.value.name}",
	    "xurl" -> s"http://localhost:9000/gapi/d3dom/${x.value.name}",
	    "links" -> x.glinks.map(l => Map(
	      "to" -> l.z.value.name,
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
      val g: gg.NoCyclesGraphLike[gg.SNode[WNode, String], gg.SLink[WNode, String]] = tosg.dag

      def node(x: gg.SNode[WNode, String]): Map[String, Any] = {
	g.collect(x, None)
	Map(
	  "name" -> x.value.name,
	  "url" -> s"http://localhost:9000/gapi/d3dom/${x.value.name}",
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
  }

  // single domain node
  def dom1(cat: String, realm:String) = {
    val links = for (
      c <- Wikis(realm).category(cat).toList;
      x <- WikiDomain(realm).gzEnds(c.name).map(t => (cat, t._1, t._2)) ::: WikiDomain(realm).gaEnds(c.name).map(t => (t._1, cat, t._2))
    ) yield x
    val nodes = (links.map(_._1) ::: links.map(_._2)).distinct.flatMap(t => Some(WNode(Wikis(realm).category(t), t)))
    def n(c: String) = nodes.indexWhere(_.name == c)
    new WGraph(n(cat), nodes, links.map { t => (n(t._1), n(t._2), t._3) })
  }

  // entire domain - makes up a "Domain" node and links to all topics
  def domain(realm:String) = {
    val links = for (
      c <- Wikis(realm).categories.toList;
      x <- WikiDomain(realm).gzEnds(c.name).map(t => (c.name, t._1, t._2)) ::: WikiDomain(realm).gaEnds(c.name).map(t => (t._1, c.name, t._2))
    ) yield x
    val fakes = Wikis(realm).categories.toList.map(c => ("Domain", c.name, "fake"))
    val nodes = WNode(None, "Domain") :: Wikis(realm).categories.toList.map(t => WNode(Some(t), t.name))
    def n(c: String) = nodes.indexWhere(_.name == c)
    new WGraph(n("Domain"), nodes, (fakes ::: links).map { t => (n(t._1), n(t._2), t._3) })
  }
}

/** graph wiki controller */
object Gapi extends RazController with Logging {
  import play.api.libs.json._

  object retj {
    def <<(x: List[Any]) = Ok(js.tojson(x).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  def dom(cat: String, realm:String) = Action { implicit request =>
    retj << WG.dom1(cat, realm).tojmap
  }

  def jitdom(cat: String, realm:String) = Action { implicit request =>
    retj << WG.dom1(cat, realm).toJIT
  }

  def d3dom(cat: String, realm:String) = Action { implicit request =>
    retj << WG.dom1(cat, realm).tod3
  }

  def domain(realm:String) = Action { implicit request =>
    retj << WG.domain(realm).tojmap
  }

  def jitdomain(realm:String) = Action { implicit request =>
    retj << WG.domain(realm).toJIT
  }

  def d3domain(realm:String) = Action { implicit request =>
    retj << WG.domain(realm).tod3
  }

  def wp1(topic: String, realm:String) = Action { implicit request =>
    def wu(topic: String) = {
      val t = topic.encUrl
      s"http://en.wikipedia.org/w/api.php?format=json&action=query&titles=$t&prop=revisions&rvprop=content"
    }

    val c = razie.Snakk.body(razie.Snakk.url(
      s"http://en.wikipedia.org/w/api.php?format=json&action=query&titles=${wu(topic)}&prop=revisions&rvprop=content"))

    // find all links [[topic]] or [[topic|text]]
    val LPAT = """\[\[([^\]\|]*)(\|[^\]\]]*)?\]\]""".r
    val p1 = (LPAT.findAllIn(c).matchData.map { m =>
      Map("topic" -> m.group(1), "name" -> (if (m.group(2) != null) m.group(2).substring(1) else m.group(1)),
	"url" -> ("http://localhost:9000/gapi/wp1/"+realm+"/" + m.group(1)))
    }).toList

    val g = Map("name" -> topic, "children" -> p1)

    Ok(js.tojson(g).toString).as("application/json")
  }

  def g(g: String, url: String) = Action { implicit request =>
    g match {
      case "g1" => Ok(views.html.gv.g1(url, auth))
      case "g2" => Ok(views.html.gv.g2(url, auth))
      case "g3" => Ok(views.html.gv.g3(url, auth))
      case _ => Ok(s"UHHHHHHHH no such thing: $g")
    }
  }

}
