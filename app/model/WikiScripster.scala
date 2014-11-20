/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import admin.Audit
import razie.{CSTimer, csys}

/** execute wiki scala scripts */
trait WikiScripster {
  /** run the given script in the context of the given page and user as well as the query map */
  def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String
  def mk: WikiScripster
}

/** simple scripts runner - I do customize it further only to setup some context available to the scripts... */
object WikiScripster {
  var count = 0
  var impl: WikiScripster = new CWikiScripster

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
    def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String = synchronized {
      import razie.base.scriptingx._

      Audit.logdb("WIKI_SCRIPSTER", "exec", s)
      try {
	val c = new CSTimer("script", "?")
	c.start()
	val res = (ScalaScript(s).interactive(ctx) getOrElse "?").toString
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
	  if(devMode) throw ex
	  else "?"
	}
      }
    }
  }
}

case class QueryParms(q: Map[String, Seq[String]])
