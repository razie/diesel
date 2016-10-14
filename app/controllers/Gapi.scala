/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.Config
import mod.diesel.model.{WG}
import mod.diesel.model.WG.WGraph
import razie.diesel.dom.RDOM
import razie.wiki.{Services, Enc, EncUrl}
import razie.wiki.Sec.EncryptedS
import play.api.mvc.Action
import razie.Logging
import razie.gg
import org.json.JSONArray
import razie.wiki.dom.WikiDomain
import razie.wiki.model.{WID, Wikis, WikiEntry}
import razie.js
import razie.wiki.util.PlayTools
import scala.collection.mutable.ListBuffer
import org.json.JSONObject
import scala.collection.mutable.HashMap

/** graph wiki controller */
object Gapi extends RazController with Logging {
  import play.api.libs.json._

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojsons(x).toString).as("application/json")
  }

    def AS (as:String, wg:WGraph) = as match {
      case "d3" => retj << wg.tod3
      case "jit" => retj << wg.toJIT
      case "spacetree" => retj << wg.tostree
      case _ => retj << wg.tojmap
  }

  def fromCat(realm: String, cat:String, as:String) = Action { implicit request =>
    AS(as, WG.fromCat(cat, realm))
  }

  def fromRealm(realm:String, as:String) = Action { implicit request =>
    AS(as, WG.fromRealm(realm))
  }

  def fromWpathYuml(wpath:String, as:String) = Action { implicit request =>
    val wid = WID.fromPath(wpath).get
    val page = wid.page.get
    val rdom = WikiDomain.domFrom(page).get.revise

    var s = rdom.classes.values.map{c=> "["+c.name+
      (if(c.parms.size>0) "|"+c.parms.map{p=>p.name}.mkString(";") else "")+
      (if(c.methods.size>0) "|"+c.methods.map{p=>p.name+"()"}.mkString(";") else "")+
      "]"}.mkString(", ") +
      (if(rdom.assocs.size>0) (", " + rdom.assocs.map{a=>
      "["+a.a+"]" + "++-0..*>"+"["+a.z+"]"
    }.mkString(", ")) else "")

    val u = "http://yuml.me/diagram/plain/class/"+Enc.toUrl(s)
    Ok(s+"<br>http://yuml.me/diagram/plain/class/"+Enc.toUrl(s)+s"""<br><img src="$u"/>""").as("text/html")
    Redirect(u)
  }

  def fromWpath(wpath:String, as:String) = Action { implicit request =>
    AS(as, WG.rdomain(wpath))
  }

  def rdomain(realm:String) = Action { implicit request =>
    retj << WG.rdomain(realm).tojmap
  }

  def reactordomain(realm:String) = Action { implicit request =>
    retj << WG.rdomain(realm).tojmap
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

  def g(g: String, iurl: String) = Action { implicit request =>

    val url =
      if(iurl startsWith "http") iurl
      else if(Services.config.isLocalhost) "http://"+Services.config.hostport+iurl
      else "http://" + PlayTools.getHost.mkString + iurl

    g match {
      case "g1" => Ok(views.html.gv.g1(url, auth))
      case "g2" => Ok(views.html.gv.g2(url, auth))
      case "g3" => Ok(views.html.gv.g3(url, auth))
      case "spacetree" => Ok(views.html.gv.spacetree(url, auth))
      case "domA" =>  Ok(WikiDomain(url).rdom.toString).as("application/json")
      case _ => Ok(s"UHHHHHHHH no such thing: $g")
    }
  }

  /** URL is /gapi/demo */
  def demo = Action { implicit request =>
    Ok(views.html.gv.demo())
  }


}
