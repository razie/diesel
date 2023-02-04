package mod.diesel.controllers

import com.google.inject.Singleton
import controllers._
import mod.notes.controllers.{Notes, NotesLocker, NotesTags}
import model.{MiniScripster, _}
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import org.bson.types.ObjectId
import play.api.mvc._
import razie.audit.Audit
import razie.diesel.snakk.FFDPayload
import razie.hosting.Website
import razie.tconf.Visibility
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.{Logging, Snakk}
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
      ok <- au.isDev orCorr (cNoPermission)
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
      ok <- au.isDev orCorr (cNoPermission)
    ) yield f(au)(errCollector)(request)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      Msg("You need more karma...", "Open a karma request")
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

  object razscr {
    def dec(s: String) = {
      s.replaceAll("scrRAZipt", "script").replaceAll("%3B", ";").replaceAll("%2B", "+").replaceAll("%27", "'")
    }
  }
}

/** this is not the controller... */
object SFiddles {
  def typeSafe(v: String): String = {
    if (v.trim.startsWith("\"") || v.trim.startsWith("'") || v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try {
      v.toInt.toString
    } getOrElse {
      "'" + v + "'"
    }
  }

  def qtojson(q: Map[String, String]) = "{" + q.map(t => s"""${t._1} : ${typeSafe(t._2)} """).mkString(",") + "}"

  def qtourl(q: Map[String, String]) = q.map(t => s"""${t._1}=${t._2}""").mkString("&")
}

/** controller for server side fiddles / services */
@Singleton
class SFiddles extends SFiddleBase with Logging {
  import NotesTags._

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(NotesLocker.book, au._id, SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id, SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      notes.headOption.filter(x => au.isDev).fold(
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
      Some(1).filter(x => au.isDev).fold(
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
    MiniScripster.isfiddleMap(script, lang, we, Some(au), q)
  }

  /** display the play sfiddle screen */
  def play2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
      val q = request.queryString.map(t => (t._1, t._2.mkString))

      Some(1).filter(x => au.isDev).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        ROK.s reactorLayout12 {implicit stok=>
          views.html.fiddle.playServerFiddle(lang, "", q)
        }
      }
  }

  /** display the play sfiddle screen */
  def play3(lang: String) = FAUPR { implicit request =>
    val q = request.queryString.map(t => (t._1, t._2.mkString))

    val f = Fiddle("SFiddle", lang, request.realm, "", request.au)
    ROK.k reactorLayout12 {
      views.html.fiddle.playServerFiddle(lang, f.content, request.query)
    }
  }

  val DFLT_DWL=
"""
      |form: FIXEDWIDTH
      |name: 'AUTH_REQUEST'
      |values:
      |- { name: 'Header Type', usage: M, type: String, length: 4 }
      |- { name: 'Request Type', usage: M, type: String, length: 2 }
      |- { name: 'Byte Offset to Data', usage: M, type: String, length: 4 }
      |- { name: 'Packet Length', usage: M, type: String, length: 8 }
      |""".stripMargin

  val DFLT_DWL_INPUT =
"""
      |^^PSSR917999999999
      |""".stripMargin

  /** display the play sfiddle screen */
  def playFFD(what:String) = RAction { implicit request =>
    val f1 = Fiddle("SFiddle", "FFD-input", request.realm, "", request.au)
      .withDefault(DFLT_DWL_INPUT)
    val f2 = Fiddle("SFiddle", "FFD-schema", request.realm, "", request.au)
      .withDefault(DFLT_DWL)

    if(what == "play") {
      WikiCount.findOneForTemplate("Play:playFFDFiddle").map (Services.!)

      // paint the screen
      ROK.k reactorLayout12FullPage  {
        views.html.fiddle.playFFDFiddle(
          "FFD",
          "Mulesoft Dataweave Fixed Format fiddle",
          "/sfiddle/playFFD?what=input",
          Map(
            "input" -> f1,
            "schema" -> f2
          )
        )
      }
    } else {
      // must have been posted
      (for(
        i <- request.fParm("input");
        s <- request.fParm("schema")
      ) yield {
        Fiddle("SFiddle", "FFD-input", request.realm, "", request.au).autosave(i, "")
        Fiddle("SFiddle", "FFD-schema", request.realm, "", request.au).autosave(s, "")

        val ffd = new FFDPayload(i, s)
        val res = ffd.show

        retj << Map(
          "res" -> res,
          "ffdcount" -> ffd.fields.map(_.length).sum
        )
      }) getOrElse Unauthorized("input/schema missing")
    }
  }


  /** display the play sfiddle screen */
  def restFiddle(lang:String, wpath: String) = RAction { implicit request =>
    val url = Fiddle("RestFiddleUrl", "snakk", request.realm, wpath, request.au)
//        .withDefault("http://localhost:4041/simMappedData?deviceType=enb&deviceId=MAC&section=system")
        .withDefault(
          "http://spark:4041/enb/simMappedData?deviceType=enb&deviceId=0c:a1:38:00:04:24&section=system")
    val f = Fiddle("RestFiddle", "snakk", request.realm, wpath, request.au)
    ROK.k reactorLayout12FullPage  {
      views.html.fiddle.playRestFiddle(
        "RestFiddle",
        "snakk",
        url.content,
        f.content,
        f.tags,
        request.query,
        f.isAuto,
        f.we
      )
    }
  }

  /** display the play sfiddle screen */
  def stringFiddle(lang:String, wpath: String) = RAction { implicit request =>
    val f = Fiddle("StringFiddle", "string", request.realm, wpath, request.au)
    ROK.k reactorLayout12FullPage  {
      views.html.fiddle.playStringFiddle(
        "StringFiddle",
        "string",
        f.content,
        f.tags,
        request.query,
        f.isAuto,
        f.we
      )
    }
  }

  /** display the play sfiddle screen */
  def playInBrowser(lang: String, wpath: String) = RAction { implicit request =>
    // used in a blog, so no auth
    val f = Fiddle("JSFiddle", lang, request.realm, wpath, request.au)
    ROK.k reactorLayout12FullPage  {
      views.html.fiddle.playBrowserFiddle(
        "JSFiddle",
        "js",
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
    val what = request.formParm("what")
    var tags = Tags(request.formParm("tags"))
    val f = Fiddle(what, lang, request.realm, wpath, request.au)

    if (!tags.contains("js")) tags = Seq("js") ++ tags
    if (!tags.contains("fiddle")) tags = Seq("fiddle") ++ tags

    if (wpath.isEmpty) {
      val we = f.mkWiki(request.au.get, j, request.realm, tags)
      we.create
      Ok(we.wid.wpath)
    } else WID.fromPath(wpath).flatMap { wid =>
      wid.page.map { we =>
        we.update(we.copy(content = j, tags = tags.toSeq))
        f.clearAutosave
        Ok(we.wid.wpath)
      }
    }.getOrElse(Ok("can't find WID to update"))
  }

  /** save the fiddle from client */
  def saveFiddle(reactor: String, what: String, wpath:String) = FAUR { implicit stok=>
    val lang = stok.formParm("l")
    val j = stok.formParm("j")
    val t = stok.formParm("tags")
    val au = stok.au.get

    val f = Fiddle (what, lang, reactor, wpath, stok.au)

    Some(1).filter(x => au.isDev).fold(
      Ok(s"no sfiddle for ")
    ) { we =>
      f.autosave(j, t)
      Ok(s"saved")
    }
  }

  /** display the play sfiddle screen */
  def runFiddle(reactor: String, what: String, wpath:String) = FAUR { implicit stok=>
    val lang = stok.formParm("l")
    val j = stok.formParm("j")
    val u = stok.formParm("u")
    val v = stok.formParm("v")
    val t = stok.formParm("tags")
    val au = stok.au.get

    try {
      clog << "snakking " + v + " - " + u
      val res = Snakk.body(Snakk.url(u, Map.empty, v), Some(j))
      clog << "snakked: " + res
      Ok(res)
    } catch {
      case e : Exception => Ok("ERROR: " + e)
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

      Some(1).filter(x => au.isDev).fold(
        Ok(s"no sfiddle for ")
      ) { we =>
        val g = "grammar g;\nmain: 'a'|'b' ;"
        val id = java.lang.System.currentTimeMillis().toString()
        val res = processDsl(id, g)
        ROK.s reactorLayout12 {implicit stok=>
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
    formWithErrors => Msg(formWithErrors.toString + "Oops! some error"), {
      case (hh, g, c, j) =>
        var res = processDsl(id, g)

        //          Ok(res);

        ROK.s reactorLayout12 { implicit stok=>
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
      Msg(formWithErrors.toString + "Oops! some error"), {
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
      Msg(formWithErrors.toString + "Oops! some error"), {
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
      Msg(formWithErrors.toString + "Oops! some error"), {
      case content =>
        Ok(razscr dec content).as("text/html").withHeaders(
          "Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
          "X-XSS-Protection" -> "0",
          "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
    })
  }
}

/** represents a fiddle - can be autosaved and can be a topic/note */
case class Fiddle (what:String, lang:String, realm:String, wpath:String, au:Option[User]) {
  val we = WID.fromPath(wpath).flatMap(_.page)
  def wid = WID.fromPath(wpath).map(_.defaultRealmTo(realm)).getOrElse(
    WID("Fiddle", what+"."+lang).r(realm)
  )

  private var default : Option[(String,String)] = None

  def withDefault(content:String, tags:String="") = {
    default = Some((content, tags))
    this
  }

  private def defaults = Map(
    "content" -> default.map(_._1).orElse(we.map(_.content)).getOrElse("1+2"),
    "tags" -> default.map(_._2).orElse(we.map(_.tags.mkString(","))).mkString
  )

  def script =
    au.map(au =>
      Autosave.OR(what+"." + lang , wid, au._id,
        defaults
      )).getOrElse(
      defaults
    )

  def autosave (content:String, tags:String) =
    au.map{au=>
      Autosave.set(what + "." + lang, wid, au._id, Map(
        "content" -> content,
        "tags" -> tags
      ))
    }

  def clearAutosave =
    au.map{au=>
      Autosave.delete(what + "." + lang, wid, au._id)
    }

  def content = script.getOrElse("content", "")
  def tags = script.getOrElse("tags", "")

  def isAuto = Autosave.find(what+"." + lang, wid, au.map(_._id)).isDefined

  /** make a wiki note to contain this fiddle */
  def mkWiki(au:User, content: String, realm:String, tags: Seq[String]) = {
    val id = new ObjectId()
    var we = WikiEntry(
      "Note", id.toString, id.toString, Wikis.JS,
      content, au._id, tags.toSeq, "notes"
    ).copy(_id = id)
    we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
    val vis = if(tags contains "public") Visibility.PUBLIC else Visibility.PRIVATE
    we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
    we = we.cloneProps(we.props ++ Map("wvis" -> Visibility.PRIVATE), au._id)
    we
  }
}


