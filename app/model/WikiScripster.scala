/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import mod.diesel.controllers.SFiddles
import razie.{CSTimer, csys}
import razie.wiki.model.WikiUser
import razie.wiki.model.WikiEntry
import razie.wiki.admin.Audit

/** execute wiki scala scripts */
trait WikiScripster {
  /** run the given script in the context of the given page and user as well as the query map */
  def runScript   (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String
  def runScriptAny(s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any

  def mk: WikiScripster
}

/** simple scripts runner - I do customize it further only to setup some context available to the scripts... */
object WikiScripster {
  var count = 0
  var impl: WikiScripster = new JSWikiScripster
  var implScala: WikiScripster = new CWikiScripster

  class JSWikiScripster extends WikiScripster {

    def mk = new JSWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    def runScriptAny (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any = synchronized {
      Audit.logdb("WIKI_SCRIPSTER", "execJS", lang+":"+s)
      try {
        val c = new CSTimer("script", "?")
        c.start()
        val res = SFiddles.isfiddleMap(s, lang, page, user, query)
        c.stop()
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

  class CWikiScripster extends WikiScripster {

    //TODO this must be initialized later, but i'm not taking advantage of doing it on separate threads...
    var wikiCtx: Option[razie.base.scriptingx.NoBindSbtScalaContext] = None
    private def ctx = {
      if (!wikiCtx.isDefined) {
        wikiCtx = Some(new razie.base.scriptingx.NoBindSbtScalaContext())
      }
      wikiCtx.get
    }

    def mk = new CWikiScripster

    /** run the given script in the context of the given page and user as well as the query map */
    def runScriptAny (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any = synchronized {
      import razie.base.scriptingx._

      Audit.logdb("WIKI_SCRIPSTER", "exec", lang+":"+s)
      try {
        val c = new CSTimer("script", "?")
        c.start()
        val res = (ScalaScript(s).interactive(ctx) getOrElse "?")
        ctx.clear // make sure there's nothing for hackers
        c.stop()

        // must get new parser every 50 times
        count = count + 1
        if (count % 20 == 0) {
          impl = mk // separate promise will create in background
          csys << "newParser"
        }
        res
      } catch {
        case ex: Throwable => { // any exceptions, get a new parser
          wikiCtx = None
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
}
