/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import model.{User, WikiScripster}
import razie.wiki.model.{Wikis, WikiUser, WikiEntry}
import razie.db.RTable

/** run scripts in the RK specific context */
class RazWikiScripster extends WikiScripster.CWikiScripster {

  override def mk = new RazWikiScripster

  /** run the given script in the context of the given page and user as well as the query map */
  override def runScriptAny(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false) = synchronized {
    def r = page.map(we=>if(we.category == "Reactor") we.name else we.realm).getOrElse(Wikis.RK)

    api.wix.init(page, user.map(_.asInstanceOf[User]), query, r)

    super.runScriptAny(s, page, user, query, devMode)
  }

  /** run the given script in the context of the given page and user as well as the query map */
  override def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String = synchronized {
    runScriptAny(s, page, user, query, devMode).toString
  }
}
