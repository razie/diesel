/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import model.User
import model.WikiEntry
import model.WikiScripster
import model.WikiUser
import razie.base.scriptingx.ScalaScript

/** run scripts in the RK specific context */
class RazWikiScripster extends WikiScripster.CWikiScripster {

  override def mk = new RazWikiScripster
  
  /** run the given script in the context of the given page and user as well as the query map */
  override def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String]) = synchronized {
    import razie.base.scriptingx._

    api.wix.page = page
    api.wix.user = user.map(_.asInstanceOf[User])
    api.wix.query = query

    super.runScript(s, page, user, query)
  }
}
