/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import api.dwix
import javax.script.ScriptEngineManager
import jdk.nashorn.api.scripting.{ClassFilter, NashornScriptEngineFactory}
import razie.audit.Audit
import razie.base.scriptingx.ScalaScript
import razie.wiki.model.{WikiEntry, WikiUser}
import razie.{CSTimer, Logging, csys}
import scala.util.Try

/** simple scripts runner - I do customize it further only to setup some context available to the scripts... */
object MiniScripster extends Logging {

  //todo deprecate
  def isfiddleMap(script: String, lang: String, we: Option[WikiEntry], au: Option[WikiUser], q: Map[String, String], typed: Option[Map[String, Any]] = None) =
    newsfiddleMap(script, lang, we, au, q, typed, false)

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def newsfiddleMap(
                       script: String,
                       lang: String,
                       we: Option[WikiEntry],
                       au: Option[WikiUser],
                       q: Map[String, String],
                       typed: Option[Map[String, Any]] = None,
                       doAudit: Boolean = true) = {
    val wix = api.wix(we, au, q, "")
    val c = new CSTimer("script", "?")
    c.start()

    if (lang == "js") {

      val jscript = s"""${wix.json}\n$script"""

      try {
        //        val factory = new ScriptEngineManager()
        //        val engine = factory.getEngineByName("JavaScript")
        val factory = new NashornScriptEngineFactory();
        val engine = factory.getScriptEngine(new MyCF());

        val bindings = engine.createBindings()

        // query parms added also as individual values...
        q.foreach(t => bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))))

        // attempt to use typed bindings, if available
        val qm = q.map(t => (t._1, typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))))
        val qjm = razie.js toJava qm

        bindings.put("queryParms", qjm)
        bindings.put("wixj", wix)

        val res = engine.eval(jscript, bindings)

        (true, if (res != null) res.toString else "")
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript", t)
          // don't include the script body - security issue
          if(au.exists(_.isActive))
            (false, t /* + "\n\n" + jscript */)
          else
            (false, t /* + "\n\n" + jscript */)
        }
      } finally {
        c.stop()
        audit("SFIDDLE_EXEC JS" + (c.last - c.beg) + " msec" + jscript)
        if (doAudit)
          Audit.logdb("SFIDDLE_EXEC", "JS", (c.last - c.beg) + " msec", jscript.takeRight(300))
      }

    } else if (lang == "ruby") {

      val qj = qtojson(q)
      val jscript = "" //s"""var queryParms = $qj;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("rb")
        val res = engine.eval(jscript)
        var bindings = engine.createBindings()
        q.foreach(t => bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(t._2)))
        Audit.logdb("SFIDDLE_EXEC", "ruby", jscript)
        (true, res.toString)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript", t)
          (false, t + "\n\n" + jscript)
        }
      }

    } else if (lang == "scala") {

      try {
        val res = WikiScripster.implScala.runScriptAny(script, lang, we, au, q, true)
        Audit.logdb("SFIDDLE_EXEC", "scala", script)
        //        (true, res.toString)
        (true, res)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$script", t)
          //          throw t
          (false, t + "\n\n" + script)
        }
      }

    } else (false, script)
  }

  class MyCF extends ClassFilter {
    override def exposeToScripts(s: String): Boolean = {
      if (s.startsWith("api." /* WixUtils" */)) true;
      else {
        Audit.logdb("ERR_DENIED", "js class access denied ", s)
        false
      }
    }
  }

  def typeSafe(v: String): String = {
    if (v.trim.startsWith("\"") || v.trim.startsWith("'") || v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try {
      v.toInt.toString
    } getOrElse {
      "'" + v + "'"
    }
  }

  def jstypeSafe(v: String): Any = {
    if (v.trim.startsWith("\"") || v.trim.startsWith("'")) v.replaceFirst("[\"']([^\"']*)[\"']", "$1")
    else if (v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try {
      v.toInt
    } getOrElse {
      v
    } //"'"+v+"'" }
  }

  def qtojson(q: Map[String, String]) = "{" + q.map(t => s"""${t._1} : ${typeSafe(t._2)} """).mkString(",") + "}"

  def qtourl(q: Map[String, String]) = q.map(t => s"""${t._1}=${t._2}""").mkString("&")

}

/** specific for Scala scripts */
trait ScalaScripster {

  // to reset the parser every 20 times
  var count = 0

  // todo this must be initialized later, but i'm not taking advantage of doing it on separate threads...
  // this is a var: it contains the parser instance and it's being reinintialized on errors and other conditions
  var wikiCtx: Option[razie.base.scriptingx.NoBindSbtScalaContext] = None

  private def ctx = {
    if (!wikiCtx.isDefined) {
      wikiCtx = Some(new razie.base.scriptingx.NoBindSbtScalaContext())
    }
    wikiCtx.get
  }

  /** run the given script in the context of the given page and user as well as the query map */
  def runScriptAny (s:String, lang: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): Any = synchronized {

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
        wikiCtx = None
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
}
