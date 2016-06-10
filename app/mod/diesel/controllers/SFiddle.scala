package mod.diesel.controllers

import java.io.File
import java.util

import controllers._
import mod.diesel.model.RDExt._
import mod.diesel.model._
import mod.notes.controllers.{Notes, NotesTags, NotesLocker}
import NotesLocker._
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin._
import model._
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
import razie.wiki.model.{Wikis, WikiEntry, WikiUser}
import razie.wiki.admin.Audit

/** controller for server side fiddles / services */
object SFiddles extends RazController with Logging {
  import NotesTags._

  def test = "haha"

  def typeSafe(v:String) : String = {
    if(v.trim.startsWith("\"") || v.trim.startsWith("'") || v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try { v.toInt.toString } getOrElse { "'"+v+"'" }
  }
  def jstypeSafe(v:String) : Any = {
    if(v.trim.startsWith("\"") || v.trim.startsWith("'")) v.replaceFirst("[\"']([^\"']*)[\"']", "$1")
    else if (v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try { v.toInt } getOrElse { v } //"'"+v+"'" }
  }
  def qtojson (q:Map[String,String]) = "{" + q.map(t=>s"""${t._1} : ${typeSafe(t._2)} """).mkString(",") + "}"
  def qtourl (q:Map[String,String]) = q.map(t=>s"""${t._1}=${t._2}""").mkString("&")

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

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def isfiddleMap(script: String, lang:String, we:Option[WikiEntry], au:Option[WikiUser], q:Map[String,String], typed:Option[Map[String,Any]] = None) = {
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
        Audit.logdb("SFIDDLE_EXEC", "JS", jscript)
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
        ROK() reactorLayout12 { implicit stok =>
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
        ROK() reactorLayout12 { implicit stok =>
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
        ROK() reactorLayout12 { implicit stok =>
          views.html.fiddle.playBrowserFiddle(lang, "", q )
        }
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
  def playDom(lang: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        val x = Autosave.OR("playDom", au._id, Map(
          "dom"  -> """$when a.b -> c.d""",
          "inst" -> """$msg a.b"""
        ))
        val g = x("dom")
        val i = x("inst")

        val id = java.lang.System.currentTimeMillis().toString()

        ROK() reactorLayout12 { implicit stok =>
          views.html.fiddle.playDomFiddle("", q, g, i, auth, Some(""), id)
        }
      }
  }

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  /** display the play sfiddle screen */
  def buildDom1(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val g = razscr.dec(request.body.asFormUrlEncoded.get.apply("g").mkString)
    val i = razscr.dec(request.body.asFormUrlEncoded.get.apply("i").mkString)
    Autosave.set("playDom", au._id, Map("dom" -> g, "inst" -> i))

    val page = new WikiEntry("temp", "temp", "temp", "md", g, au._id, Seq("dslObject"), "")
    val dom = WikiDomain.domFrom(page).get.revise addRoot

    var res = Wikis.format(page.wid, page.markup, null, Some(page), auth)
    retj << Map("res" -> res, "ca" -> RDExt.toCAjmap(dom))
  }

  case class TestResult (value:String) {
    override def toString =
      if(value == "ok")
        s"""<span class="label label-success">$value</span>"""
    else if(value == "fail")
        s"""<span class="label label-danger">$value</span>"""
    else
        s"""<span class="label label-warning">$value</span>"""
  }

  case class DomAst (value:Any, kind:String, children:ListBuffer[DomAst] = new ListBuffer[DomAst]()) {
    def tos(level:Int):String = ("  " * level)+kind+"::"+value.toString+"\n"+children.map(_.tos(level+1)).mkString
    override def toString = tos(0)
    def collect[T](f: PartialFunction[DomAst,T]) = {
      val res = new ListBuffer[T]()
      def inspect(d:DomAst, level:Int):Unit = {
        if(f.isDefinedAt(d)) res append f(d)
        d.children.map(inspect(_,level+1))
      }
      inspect(this, 0)
      res
    }
  }

  /** display the play sfiddle screen */
  def buildDom2(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val g = razscr.dec(request.body.asFormUrlEncoded.get.apply("g").mkString)
    val i = razscr.dec(request.body.asFormUrlEncoded.get.apply("i").mkString)
    Autosave.set("playDom", au._id, Map("dom" -> g, "inst" -> i))

    val page = new WikiEntry("temp", "temp", "temp", "md", g, au._id, Seq("dslObject"), "")
    val dom = WikiDomain.domFrom(page).get.revise addRoot

    val ipage = new WikiEntry("temp", "temp", "temp", "md", i, au._id, Seq("dslObject"), "")
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    val rules = WikiDomain.domFilter(page) {
      case e:ERule => e
    }

    var res = ""

    val root=DomAst("root", "root")

    // transform one element / one step
    def step (a:DomAst, recurse:Boolean=true):Unit = a.value match {
      case n:EMsg => { // look for mocks
         implicit val ctx=ECtx()
        var mocked=false
          root.collect {
            case d@DomAst(m:EMock, _, _) if m.rule.e.test(n)  && a.children.isEmpty => {
              mocked=true
              // run the mock
              val values = m.rule.i.apply(n, None).collect {
                // collect resulting values
                case x:EMsg => a.children appendAll x.attrs.map(x=> DomAst(EVal(x), "generated"))
              }
            }
          }

        if (!mocked && rules.exists(_.e.test(n))) {
          rules.filter(_.e.test(n)).map{r=>
            val spec = dom.moreElements.collect {
              case x:EMsg if x.entity == r.i.cls && x.met == r.i.met => x
            }.headOption

            // each rule may recurse and add stuff
            implicit val ctx = ECtx((root.collect {
              case d@DomAst(v:EVal, _, _) => v.p
            }).toList)

            val news = r.i.apply(n, spec).map(x=>DomAst(x, "generated"))
            if(recurse) news.foreach{n=>
              a.children append n
              step(n, recurse)
            }
            true
          }
        }

      }

      case e:ExpectM => {
        root.collect {
          case d@DomAst(n:EMsg, "generated", _) =>
            if(e.m.test(n))
              a.children append DomAst(TestResult("ok"), "test")
        }
        if(a.children.isEmpty) a.children append DomAst(TestResult("fail"), "test")
      }

      case e:ExpectV => {
        root.collect {
          case d@DomAst(n:EVal, "generated", _) =>
            if(e.test(n.p))
              a.children append DomAst(TestResult("ok"), "test")
        }
        if(a.children.isEmpty) a.children append DomAst(TestResult("fail"), "test")
      }
      case _ => false
    }


    root.children appendAll WikiDomain.domFilter(ipage) {
      case o:O if o.name != "context" => DomAst(o, "input")
      case v:EMsg => DomAst(v, "input")
      case v:EMock => DomAst(v, "input")
      case e:ExpectM => DomAst(e, "test")
      case e:ExpectV => DomAst(e, "test")
    }

    // start processing all elements
    root.children.foreach(step(_, true))
    res += root.toString

    retj << Map("res" -> res, "ca" -> RDExt.toCAjmap(dom plus idom))
  }

  private def rule(r:ERule, n:EMsg) = {

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
        ROK() reactorLayout12 { implicit stok =>
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

        ROK() reactorLayout12 { implicit stok =>
          views.html.fiddle.playDslFiddle("", q, (hh, g, c, j), auth, Some(res))
        }
    })
  }

  //===== html fid

  import play.api.data.Forms._
  import play.api.data._

  object razscr {
    def dec(s: String) = {
      s.replaceAll("scrRAZipt", "script").replaceAll("%3B", ";").replaceAll("%2B", "+").replaceAll("%27", "'")
    }
  }

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
        Ok(views.html.fiddle.playHtmlFiddle("", Map(), (hh, h, c, j), auth))
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


