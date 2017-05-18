package mod.diesel.controllers

import javax.script.ScriptEngineManager

import controllers._
import jdk.nashorn.api.scripting.{ClassFilter, NashornScriptEngineFactory}
import mod.notes.controllers.{Notes, NotesLocker, NotesTags}
import model._
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import org.bson.types.ObjectId
import play.api.mvc._
import razie.audit.Audit
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{CSTimer, Logging}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

/** controller for server side fiddles / services */
class SFiddleBase extends RazController {

  def test = "haha"

  def FAUPR(f: RazRequest => Result) = FAUPRAPI(false)(f)

  def FAUPRa(f: RazRequest => Future[Result]) = FAUPRaAPI(false)(f)
  def FAUPRaAPI(isApi:Boolean)(f: RazRequest => Future[Result]) = Action.async { implicit request =>
    implicit val stok = new RazRequest(request)
    (for (
      au <- stok.au;
      isA <- checkActive(au);
      ok <-
      ((au hasPerm Perm.domFiddle) ||
        (au hasPerm Perm.codeMaster) ||
        (au hasPerm Perm.adminDb)) orCorr(cNoPermission)
    ) yield f(stok)
      ) getOrElse Future {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      if(isApi)
        Unauthorized("You need more karma... " + stok.errCollector.mkString)
      else
        Msg("You need more karma...", "Open a karma request")
    }
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAUP(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au);
      ok <-
      ((au hasPerm Perm.domFiddle) ||
        (au hasPerm Perm.codeMaster) ||
        (au hasPerm Perm.adminDb)) orCorr(cNoPermission)
    ) yield f(au)(errCollector)(request)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      Msg("You need more karma...", "Open a karma request")
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

  object razscr {
    def dec(s: String) = {
      s.replaceAll("scrRAZipt", "script").replaceAll("%3B", ";").replaceAll("%2B", "+").replaceAll("%27", "'")
    }
  }

  import razie.js

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

}

/** controller for server side fiddles / services */
object SFiddles extends SFiddleBase with Logging {

  import NotesTags._

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(NotesLocker.book, au._id, SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id, SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      notes.headOption.filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for $path")
      ) { we =>
        val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
        val lang = if (we.tags contains "js") "js" else if (we.tags contains "scala") "scala" else ""
        val (_, res) = isfiddle(script, lang, Some(we))
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
        Some(1).filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
          Future.successful(Ok(s"no sfiddle for id $id"))
        ) { we =>
          val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
          val j = razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
          val (_, res) = isfiddle(j, lang)(request, au)
          // special stuff for calling actions
          // todo is this a security hole?
          if (res.isInstanceOf[Action[_]])
            res.asInstanceOf[Action[_]](request).run
          else
            Future.successful(Ok(res.toString))
        }

      }
      ) getOrElse Future.successful(unauthorized("CAN'T"))
  }

  /** run a fiddle */
  private def isfiddle(script: String, lang: String, we: Option[WikiEntry] = None)(implicit request: Request[_], au: User) = {
    val q = request.queryString.map(t => (t._1, t._2.mkString))
    isfiddleMap(script, lang, we, Some(au), q)
  }

  //todo deprecate
  def isfiddleMap(script: String, lang: String, we: Option[WikiEntry], au: Option[WikiUser], q: Map[String, String], typed: Option[Map[String, Any]] = None) =
    newsfiddleMap(script, lang, we, au, q, typed, false)

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def newsfiddleMap(script: String, lang: String, we: Option[WikiEntry], au: Option[WikiUser], q: Map[String, String], typed: Option[Map[String, Any]] = None, doAudit: Boolean = true) = {
    val wix = api.wix(we, au, q, "")
    val c = new CSTimer("script", "?")
    c.start()

    if (lang == "js") {
      val qj = qtojson(q)
      val jscript = s"""var queryParms = $qj;\n${wix.json}\n$script"""
      try {
        //        val factory = new ScriptEngineManager()
        //        val engine = factory.getEngineByName("JavaScript")
        val factory = new NashornScriptEngineFactory();
        val engine = factory.getScriptEngine(new MyCF());

        var bindings = engine.createBindings()
        // attempt to use typed bindings, if available
        q.foreach(t => bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))))
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
        val res = WikiScripster.implScala.runScriptAny(lang, script, we, au, q, true)
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
      };
    }
  }

  /** display the play sfiddle screen */
  def play2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      Some(1).filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playServerFiddle(lang, "", q)
        }
      }
  }

  /** display the play sfiddle screen */
  def play3(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      Some(1).filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playServerFiddle(lang, "", q)
        }
      }
  }

  /** represents a fiddle - can be autosaved and can be a topic/note */
  case class Fiddle (what:String, lang:String, realm:String, wpath:String, au:Option[User]) {
    val we = WID.fromPath(wpath).flatMap(_.page)

    private def defaults = Map(
      "content" -> we.map(_.content).getOrElse("1+2"),
      "tags" -> we.map(_.tags.mkString(",")).mkString
    )

    def script =
      au.map(au =>
        Autosave.OR(what+"." + lang + "." + realm+"."+wpath, au._id,
          defaults
        )).getOrElse(
          defaults
      )

    def autosave (content:String, tags:String) =
      au.map{au=>
      Autosave.set(what + "." + lang + "." + realm+"."+wpath, au._id, Map(
        "content" -> content,
        "tags" -> tags
      ))
      }

    def clearAutosave =
      au.map{au=>
        Autosave.delete(what + "." + lang + "." + realm+"."+wpath, au._id)
      }

    def content = script.getOrElse("content", "")
    def tags = script.getOrElse("tags", "")

    def isAuto = Autosave.find(what+"." + lang + "." + realm+"."+wpath, au.map(_._id)).isDefined

    def mkWiki(au:User, content: String, realm:String, tags: Seq[String]) = {
      val id = new ObjectId()
      var we = WikiEntry(
        "Note", id.toString, id.toString, "md",
        content, au._id, tags.toSeq, "notes"
      ).copy(_id = id)
      we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
      we = we.cloneProps(we.props ++ Map("visibility" -> Visibility.PRIVATE), au._id)
      we = we.cloneProps(we.props ++ Map("wvis" -> Visibility.PRIVATE), au._id)
      we
    }
  }

  /** display the play sfiddle screen */
  def playInBrowser(lang: String, wpath: String) = RAction { implicit request =>
    // used in a blog, so no auth
    val f = Fiddle("JSFiddle", lang, request.realm, wpath, request.au)
    ROK.k reactorLayout12 { implicit stok =>
      views.html.fiddle.playBrowserFiddle(
        lang,
        f.content,
        f.tags,
        request.query,
        f.isAuto,
        f.we
      )
    }
  }

  /** create/update note for fiddle */
  def updateFiddle(lang: String, wpath: String) = FAUR { implicit request =>
    val lang = request.formParm("l")
    val j = request.formParm("j")
    var tags = Tags(request.formParm("tags"))
    val f = Fiddle("JSFiddle", lang, request.realm, wpath, request.au)

    if (!tags.contains("js")) tags = Seq("js") ++ tags
    if (!tags.contains("fiddle")) tags = Seq("fiddle") ++ tags

    if (wpath.isEmpty) {
      val we = f.mkWiki(request.au.get, j, request.realm, tags)
      we.create
      Redirect(Wikis.w(we.wid))
    } else WID.fromPath(wpath).flatMap { wid =>
      wid.page.map { we =>
        we.update(we.copy(content = j, tags = tags.toSeq))
        f.clearAutosave
        Ok(s"saved")
      }
    }.getOrElse(Ok("can't find WID to update"))
  }

  /** display the play sfiddle screen */
  def saveFiddle(reactor: String, what: String, wpath:String) = FAUR { implicit stok=>
    val lang = stok.formParm("l")
    val j = stok.formParm("j")
    val t = stok.formParm("tags")
    val au = stok.au.get

    val f = Fiddle (what, lang, reactor, wpath, stok.au)

      Some(1).filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        f.autosave(j, t)
        Ok(s"saved")
      }
  }

  //[id [asset, content]]
  var assets = new mutable.HashMap[String, Map[String, String]]()

  def tempAsset(id: String, name: String) = FAU { implicit au => implicit errCollector => implicit request =>
    def ctype =
      if (name endsWith "js") "application/javascript"
      else "application/text"
    Ok(assets.get(id).flatMap(_.get(name)).mkString).as(ctype)
  }

  /** display the play sfiddle screen */
  def playDsl(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      Some(1).filter(x => (au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        val g = "grammar g;\nmain: 'a'|'b' ;"
        val id = java.lang.System.currentTimeMillis().toString()
        val res = processDsl(id, g)
        ROK.s reactorLayout12 { implicit stok =>
          views.html.fiddle.playDslFiddle("", q, ("", g, "", ""), auth, Some(res), id)
        }
      }
  }

  private def f(path: String, fn: String) = {
    val source = scala.io.Source.fromFile(path + fn)
    val lines = try source.mkString finally source.close()
    lines
  }

  /** process the grammar and populate the assets (lexer, parser) */
  def processDsl(id: String, g: String) = {
    import org.antlr.v4.Tool

    val tool = new Tool(Array("-Dlanguage=JavaScript"))
    val errors = new StringBuffer()

    tool.addListener(new ANTLRToolListener() {

      override def info(msg: String): Unit = {}

      override def error(msg: ANTLRMessage): Unit = {
        errors.append(format(msg));
      }

      private def format(msg: ANTLRMessage): String = {
        msg.toString
      }

      override def warning(msg: ANTLRMessage): Unit = {
        errors.append(format(msg));
      }
    });

    var res = ""
    val path = "/Users/raz/w/racerkidz/gen/we/"
    val name = "g"

    tool.outputDirectory = "/Users/raz/w/racerkidz/gen/we"
    val gast = tool.parseGrammarFromString(g)

    if (gast != null) {
      //            if(tool.checkForRuleIssues(g)) {
      val gr = tool.createGrammar(gast)
      gr.fileName = path + name + ".g4"
      tool.process(gr, true)
      res = "Processed: " + errors.toString

      val l = f(path, name + "Lexer.js")
      val p = f(path, name + "Parser.js")

      assets.put(id, Map("gLexer.js" -> l, "gParser.js" -> p))
    } else {
      res = "Grammar issues: " + errors.toString()
    }
    res;
  }

  /** display the play sfiddle screen */
  def buildDsl(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t => (t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    lform.bindFromRequest.fold(
    formWithErrors => Msg2(formWithErrors.toString + "Oops! some error"), {
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
      Msg2(formWithErrors.toString + "Oops! some error"), {
      case (hh, h, c, j) =>
        xbuildhtml(id, hh, h, c, j)
    })
  }

  def xbuildhtml(id: String, hh: String, h: String, c: String, j: String)(implicit request: Request[_]) = {
    val hhx = razscr dec hh
    val hx = razscr dec h
    val cx = razscr dec c
    val jx = razscr dec j

    val res =
      s"""
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
      (r matches s"""^http[s]?://${Services.config.hostport}.*""") ||
        (r matches s"""^http[s]?://${request.headers.get("X-Forwarded-Host").getOrElse("NOPE")}.*""")))
      Ok(res).as("text/html").withHeaders(
        "X-XSS-Protection" -> "0")
    //      Ok(res).as("text/html")
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
      Msg2(formWithErrors.toString + "Oops! some error"), {
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
      "X-XSS-Protection" -> "0",
      "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
  }

  val OneForm = Form("echof" -> nonEmptyText)

  def jsechof = Action { implicit request =>
    OneForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops! some error"), {
      case content =>
        Ok(razscr dec content).as("text/html").withHeaders(
          "Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-XSS-Protection" -> "0",
          "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
    })
  }

}


