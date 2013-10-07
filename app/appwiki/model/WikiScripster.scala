/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.casbah.Imports._
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import db.RTable
import com.tristanhunt.knockoff.DefaultDiscounter.knockoff
import com.tristanhunt.knockoff.DefaultDiscounter.toXHTML
import razie.base.scriptingx.ScalaScript
import admin.WikiConfig
import razie.cout
import razie.Logging
import admin.VError
import admin.Validation
import db.Mongo
import admin.Audit
import admin.Services

/** execute wiki scala scripts */
trait WikiScripster {

  /** run the given script in the context of the given page and user as well as the query map */
  def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String]): String
}

/** simple scripts runner - I do customize it firther only to setup some context available tothe scripts... */
object WikiScripster {
  var impl = new WikiScripster {

    var wikiCtx: Option[razie.base.scriptingx.NoBindSbtScalaContext] = None

    private def ctx = {
      if (!wikiCtx.isDefined) {
        wikiCtx = Some(new razie.base.scriptingx.NoBindSbtScalaContext())
      }
      wikiCtx.get
    }

    /** run the given script in the context of the given page and user as well as the query map */
    def runScript(s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String]): String = synchronized {
      import razie.base.scriptingx.ScalaScriptContext;
      import razie.base.scriptingx._

      try {
        val res = (ScalaScript(s).interactive(ctx) getOrElse "?").toString
        ctx.clear // make sure there's nothing for hackers
        res
      } catch {
        case _: Throwable => { // any exceptions, get a new parser
          wikiCtx = None
          "?"
        }
      }
    }
  }
}

case class QueryParms(q: Map[String, Seq[String]])
