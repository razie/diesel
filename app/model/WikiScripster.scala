/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import model.WikiScripster.count
import razie.base.scriptingx.{NoBindSbtScalaContext, SBTScalaScriptContext, ScalaScriptContext}
import razie.wiki.model.WikiUser
import razie.wiki.model.WikiEntry

/** execute wiki scripts - they're related to a wikipage */
trait WikiScripster {
  /** run the given script in the context of the given page and user as well as the query map */
  def runScript   (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String
  def runScriptTyped(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): Any

  def mk: WikiScripster
}

/** simple scripts runner - I do customize it further only to setup some context available to the scripts... */
object WikiScripster extends razie.Logging {
  var count = 0
  var impl: WikiScripster = new JSWikiScripster
  var implScalaWiki: WikiScripster = new CWikiScripster
  var implScalaDiesel: WikiScripster = new DWikiScripster

  class JSWikiScripster extends WikiScripster {

    override def mk = new JSWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    override def runScriptTyped(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): Any = synchronized {
      count += 1
      try {
        val t = if(typed.size > 0) Some(typed) else None
        val res = MiniScripster.newsfiddleMap(s, lang, page, user, query, t, false)
        if(res._1) res._2 else "ERR: " + res._2
      } catch {
        case ex: Throwable => { // any exceptions, get a new parser
          razie.Log.log("ERR WikiScripster: ", ex)
          throw new RuntimeException("ERR " + ex.getMessage).initCause(ex)
        }
      }
    }

    /** run the given script in the context of the given page and user as well as the query map */
    override def runScript(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String = synchronized {
      runScriptTyped(s, lang, page, user, query, typed, devMode).toString
    }
  }

  class CWikiScripster extends WikiScripster with ScalaScripster {
    override val mkCtx:() => ScalaScriptContext = () => new NoBindSbtScalaContext()

    override def mk = new CWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    override def runScript(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String = {
      count += 1
      runScriptTyped(s, lang, page, user, query, typed, devMode).toString
    }
  }

  class DWikiScripster extends CWikiScripster {
    override val mkCtx:() => ScalaScriptContext = () => new SBTScalaScriptContext()

    override def mk = new DWikiScripster
  }

}
