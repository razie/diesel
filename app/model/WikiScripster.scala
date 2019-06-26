/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import razie.wiki.model.WikiUser
import razie.wiki.model.WikiEntry

/** execute wiki scala scripts */
trait WikiScripster {
  /** run the given script in the context of the given page and user as well as the query map */
  def runScript   (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String
  def runScriptAny(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any

  def mk: WikiScripster
}

/** simple scripts runner - I do customize it further only to setup some context available to the scripts... */
object WikiScripster extends razie.Logging {
  var count = 0
  var impl: WikiScripster = new JSWikiScripster
  var implScala: WikiScripster = new CWikiScripster

  class JSWikiScripster extends WikiScripster {

    def mk = new JSWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    def runScriptAny (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any = synchronized {
      try {
        val res = MiniScripster.newsfiddleMap(s, lang, page, user, query, None, false)
        if(res._1) res._2 else "ERR: " + res._2
      } catch {
        case ex: Throwable => { // any exceptions, get a new parser
          razie.Log.log("ERR WikiScripster: ", ex)
          if(true || devMode) throw ex
          else "?"
        }
      }
    }

    /** run the given script in the context of the given page and user as well as the query map */
    def runScript(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String = synchronized {
      runScriptAny(s, lang, page, user, query, devMode).toString
    }
  }

  class CWikiScripster extends WikiScripster with ScalaScripster {

    def mk = new CWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    def runScript(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String = {
      runScriptAny(s, lang, page, user, query, devMode).toString
    }
  }

}
