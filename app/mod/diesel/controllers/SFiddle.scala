package mod.diesel.controllers

import java.io.File
import java.util
import controllers._
import difflib.DiffUtils
import mod.diesel.model.RDExt._
import mod.diesel.model._
import mod.notes.controllers.{Notes, NotesTags, NotesLocker}
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import admin._
import model._
import org.scalatest.fixture
import razie.db._
import razie.db.RazSalatContext.ctx
import razie.diesel.RDOM.O
import razie.diesel.RDomain
import razie.wiki.Enc
import razie.wiki.Sec.EncryptedS
import play.api.mvc._
import razie.wiki.dom.WikiDomain
import razie.wiki.util.VErrors
import razie.{js, cout, Logging}
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Try
import scala.util.parsing.input.{CharArrayReader, Positional}
import razie.wiki.model.{WID, Wikis, WikiEntry, WikiUser}
import razie.wiki.admin.Audit

/** controller for server side fiddles / services */
class SFiddleBase extends RazController {

  import NotesTags._

  def test = "haha"

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

  object razscr {
    def dec(s: String) = {
      s.replaceAll("scrRAZipt", "script").replaceAll("%3B", ";").replaceAll("%2B", "+").replaceAll("%27", "'")
    }
  }

}

/** controller for server side fiddles / services */
object SFiddles extends SFiddleBase with Logging {
  import NotesTags._

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(NotesLocker.book, au._id,SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id,SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      notes.headOption.filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for $path")
      ) {we=>
        val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
        val lang = if(we.tags contains "js") "js" else if(we.tags contains "scala") "scala" else ""
        val (_,res) = isfiddle(script, lang, Some(we))
        Ok(res.toString)
      }
  }

  /** run a fiddle for testing */
  def sfiddle2(id: String) = Action.async { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield {
        Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
          Future.successful(Ok(s"no sfiddle for id $id"))
        ) {we=>
          val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
          val j = razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
          val (_,res) = isfiddle(j, lang)(request,au)
          // special stuff for calling actions
          // todo is this a security hole?
          if(res.isInstanceOf[Action[_]])
            res.asInstanceOf[Action[_]](request).run
          else
            Future.successful(Ok(res.toString))
        }

      }
      ) getOrElse Future.successful(unauthorized("CAN'T"))
  }

  /** run a fiddle */
  private def isfiddle(script: String, lang:String, we:Option[WikiEntry] = None)(implicit request:Request[_], au:User) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    isfiddleMap (script, lang, we, Some(au), q)
  }

  //todo deprecate
  def isfiddleMap(script: String, lang:String, we:Option[WikiEntry], au:Option[WikiUser], q:Map[String,String], typed:Option[Map[String,Any]] = None) =
    newsfiddleMap(script, lang, we, au, q, typed, false)

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def newsfiddleMap(script: String, lang:String, we:Option[WikiEntry], au:Option[WikiUser], q:Map[String,String], typed:Option[Map[String,Any]] = None, doAudit:Boolean=true) = {
    val wix = api.wix(we, au, q, "")
    if(lang == "js") {
      val qj = qtojson(q)
      val jscript = s"""var queryParms = $qj;\n${wix.json}\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("JavaScript")
        var bindings = engine.createBindings()
        // attempt to use typed bindings, if available
        q.foreach(t=>bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))))
        bindings.put("wixj", wix)
        val res = engine.eval(jscript, bindings)
        if(doAudit) Audit.logdb("SFIDDLE_EXEC", "JS", jscript)
        (true, if(res != null) res.toString else "")
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript",t)
          (false, t + "\n\n" + jscript)
        }
      }
    } else if(lang == "ruby") {
      val qj = qtojson(q)
      val jscript = ""//s"""var queryParms = $qj;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("rb")
        val res = engine.eval(jscript)
        var bindings = engine.createBindings()
        q.foreach(t=>bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(t._2)))
        Audit.logdb("SFIDDLE_EXEC", "ruby", jscript)
        (true, res.toString)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript",t)
          (false, t + "\n\n" + jscript)
        }
      }
    } else if(lang == "scala") {
      try {
        val res = WikiScripster.implScala.runScriptAny(lang, script, we, au, q, true)
        Audit.logdb("SFIDDLE_EXEC", "scala", script)
//        (true, res.toString)
        (true, res)
      } catch {
        case t:Throwable => {
          log(s"while executing script\n$script",t)
//          throw t
          (false, t + "\n\n" + script)
        }
      }
    } else (false, script)
  }

  /** display the play sfiddle screen */
  def play2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playServerFiddle(lang, "", q)
        }
      }
  }

  /** display the play sfiddle screen */
  def play3(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playServerFiddle(lang, "", q)
        }
      }
  }

  /** display the play sfiddle screen */
  def playInBrowser(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        ROK.s reactorLayout12 { implicit stok =>

          val script = Autosave.OR("JSFiddle."+lang+"."+stok.realm, au._id, Map(
            "content" -> """1+2"""
          ))

          views.html.fiddle.playBrowserFiddle(lang, script("content"), q )
        }
      }
  }

  /** display the play sfiddle screen */
  def saveFiddle(reactor:String, what:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = request.body.asFormUrlEncoded.get.apply("j").mkString

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
          Autosave.set(what+"."+lang+"."+Website.realm, au._id, Map(
            "content" -> j
          ))
        Ok(s"saved")
        }
      }

  //[id [asset, content]]
  var assets = new mutable.HashMap[String, Map[String,String]]()

  def tempAsset(id: String, name:String) = FAU { implicit au => implicit errCollector => implicit request =>
    def ctype =
      if(name endsWith "js") "application/javascript"
    else "application/text"
    Ok(assets.get(id).flatMap(_.get(name)).mkString).as(ctype)
  }

  /** display the play sfiddle screen */
  def playDsl(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        val g = "grammar g;\nmain: 'a'|'b' ;"
        val id = java.lang.System.currentTimeMillis().toString()
        val res = processDsl(id, g)
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playDslFiddle("", q, ("", g, "", ""), auth, Some(res), id)
        }
      }
  }

  private def f(path:String, fn:String) = {
    val source = scala.io.Source.fromFile(path+fn)
    val lines = try source.mkString finally source.close()
    lines
  }

  /** process the grammar and populate the assets (lexer, parser) */
  def processDsl(id: String, g:String) = {
        import org.antlr.v4.Tool

        val tool = new Tool(Array("-Dlanguage=JavaScript"))
        val errors = new StringBuffer()

        tool.addListener(new ANTLRToolListener() {

          override def info(msg:String ) : Unit = { }

          override def error(msg:ANTLRMessage ):Unit= {
            errors.append(format(msg));
          }

          private def format(msg:ANTLRMessage ) :String={
            msg.toString
          }

          override def warning(msg:ANTLRMessage ):Unit ={
            errors.append(format(msg));
          }
        });

        var res = ""
        val path = "/Users/raz/w/racerkidz/gen/we/"
        val name = "g"

        tool.outputDirectory = "/Users/raz/w/racerkidz/gen/we"
        val gast = tool.parseGrammarFromString(g)

        if(gast != null) {
          //            if(tool.checkForRuleIssues(g)) {
          val gr = tool.createGrammar(gast)
          gr.fileName = path+name+".g4"
          tool.process(gr, true)
          res = "Processed: "+ errors.toString

          val l = f(path,name+"Lexer.js")
          val p = f(path,name+"Parser.js")

          assets.put(id, Map("gLexer.js" -> l, "gParser.js" -> p))
        } else {
          res = "Grammar issues: "+errors.toString()
        }
    res;
  }

  /** display the play sfiddle screen */
  def buildDsl(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    lform.bindFromRequest.fold(
    formWithErrors => Msg2(formWithErrors.toString + "Oops! some error"),
    {
      case (hh, g, c, j) =>
        var res = processDsl(id, g)

        //          Ok(res);

        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playDslFiddle("", q, (hh, g, c, j), auth, Some(res))
        }
    })
  }

  //===== html fid

  import play.api.data.Forms._
  import play.api.data._


  def lform(implicit request: Request[_]) = Form {
    tuple(
      "hh" -> text,
      "h" -> text,
      "c" -> text,
      "j" -> text)
  }

  def buildhtml(id: String) = Action { implicit request =>
    lform.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops! some error"),
    {
      case (hh, h, c, j) =>
        xbuildhtml(id, hh, h, c, j)
    })
  }

  def xbuildhtml(id: String, hh: String, h: String, c: String, j: String)(implicit request: Request[_]) = {
    val hhx = razscr dec hh
    val hx = razscr dec h
    val cx = razscr dec c
    val jx = razscr dec j

    val res = s"""
<html>
  <head>
$hhx
  <style>
$cx
</style>
</head>
<body onload="onLoad_$id()">
<script>
function onLoad_$id() {
$jx
}
</script>
$hx
</body>
</html>
"""

    // stupid security disallow XSS by requiring a referer
    // TODO better security -
    if (request.headers.get("Referer").exists(r =>
      (r matches s"""^http[s]?://${Config.hostport}.*""") ||
        (r matches s"""^http[s]?://${request.headers.get("X-Forwarded-Host").getOrElse("NOPE")}.*""")))
      Ok(res).as("text/html")
    else {
      Audit.logdb("ERR_BUILDHTML", "Referer", request.headers.get("Referer"), "Host", request.host)
      Ok("n/a").as("text/html")
    }
  }

  def buildhtml(id: String, hh: String, h: String, c: String, j: String) = Action { implicit request =>
    xbuildhtml(id, hh, h, c, j)
  }

  def playjs(id: String) = Action { implicit request =>
    lform.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops! some error"),
    {
      case (hh, h, c, j) =>
        ROK.r noLayout { implicit stok =>
          views.html.fiddle.playHtmlFiddle("", Map(), (hh, h, c, j))
        }
    })
  }

  // ==== not used - was for testing

  def jsecho(echo: String) = Action { implicit request =>
    println("EEEEEEEEEEEECHO: " + echo)
    Ok(razscr dec echo).as("text/html").withHeaders(
      "Content-Security-Policy" -> "script-src http: 'unsafe-inline'",
      "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
      "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
  }

  val OneForm = Form("echof" -> nonEmptyText)

  def jsechof = Action { implicit request =>
    OneForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops! some error"),
    {
      case content =>
        Ok(razscr dec content).as("text/html").withHeaders(
          "Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
    })
  }

}


