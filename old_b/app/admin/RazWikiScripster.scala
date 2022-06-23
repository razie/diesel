/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import model.{WikiScripster}
import razie.wiki.model.{Wikis, WikiUser, WikiEntry}

/** run scripts in the RK specific context */
class RazWikiScripster extends WikiScripster.JSWikiScripster {

  override def mk = new RazWikiScripster

  /** run the given script in the context of the given page and user as well as the query map */
  override def runScriptTyped(s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false) = synchronized {
//    def r = page.map(we=>if(we.category == "Reactor") we.name else we.realm).getOrElse(Wikis.RK)

    super.runScriptTyped(s, lang, page, user, query, typed, devMode)
  }

  /** run the given script in the context of the given page and user as well as the query map */
  override def runScript(s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String = synchronized {
    runScriptTyped(s, lang, page, user, query, typed, devMode).toString
  }
}
