/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
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
object WikiDom extends RazController with Logging {
  import play.api.libs.json._

  object retj {
    def <<(x: String) = Ok(x).as("application/json")
    def <<(x: List[Any]) = Ok(js.tojson(x).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  def dom(realm:String) = Action { implicit request =>
    retj << WikiDomain(realm).dom.toString
  }
}
