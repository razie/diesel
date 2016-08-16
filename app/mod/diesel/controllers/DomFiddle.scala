package mod.diesel.controllers

import java.io.File
import java.util
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props}
import controllers._
import difflib.{Patch, DiffUtils}
import mod.diesel.controllers.DomWorker.AutosaveSet
import mod.diesel.model.RDExt._
import mod.diesel.model._
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import admin._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.scalatest.fixture
import play.libs.Akka
import razie.db._
import razie.db.RazSalatContext.ctx
import razie.diesel.RDOM.O
import razie.diesel.RDomain
import razie.wiki.Enc
import razie.wiki.Sec.EncryptedS
import play.api.mvc._
import razie.wiki.dom.WikiDomain
import razie.wiki.util.VErrors
import razie.{CSTimer, js, cout, Logging}
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.parsing.input.{CharArrayReader, Positional}
import razie.wiki.model.{WID, Wikis, WikiEntry, WikiUser}
import razie.wiki.admin.{SecLink, Audit}

/** controller for server side fiddles / services */
object DomFiddles extends mod.diesel.controllers.SFiddleBase  with Logging {

  val SAMPLE_STORY=
    """
      |Stories are told in terms of input messages and expected messages...
      |
      |On one beautiful summer eve, a guest arrived:
      |
      |$msg home.guest_arrived(name="Jane")
      |
      |Naturally, the lights must have come on:
      |
      |$expect $msg lights.on
      |$expect $val (lights=="bright")
      |
      |Let's add a chimes system, in charge with greeting Jane:
      |
      |$expect $val (greetings=="Greetings, Jane")
      |$expect $msg chimes.welcome(name=="Jane")
      |""".stripMargin
  val SAMPLE_SPEC =
    """
      |## Specifications
      |
      |Specifications, like [[Spec:lights-spec]] deal with the actual *implementation* of the system.
      |
      |Our system will turn the lights on when a guest arrives:
      |
      |$when home.guest_arrived(name) => lights.on
      |$when home.guest_arrived(name=="Jane") => chimes.welcome(name="Jane")
      |
      |Then we have a sensor which we check to see if they're truly on:
      |
      |$when lights.on => lights.check
      |
      |We can also *mock* the messages that we don't have access to yet:
      |
      |$mock lights.check => (lights="bright")
      |$mock chimes.welcome => (greetings="Greetings, "+name)
      |
      |As you can see, specifications are simply wiki topics, with special annotations for messages, conditions, mocks and such.
      |
      |""".stripMargin

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAUPR(f: RazRequest => Result) = Action { implicit request =>
    implicit val stok = new RazRequest(request)
    (for (
      au <- stok.au;
      isA <- checkActive(au);
      ok <-
      ((au hasPerm Perm.domFiddle) ||
        (au hasPerm Perm.codeMaster) ||
        (au hasPerm Perm.adminDb)) orCorr(cNoPermission)
    ) yield f(stok)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
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

  /** display the play sfiddle screen */
  def playDom(reactor: String, iSpecWpath:String, iStoryWpath:String) = FAUPR { implicit stok =>
//    implicit errCollector => implicit request =>
      val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))

        //1. which wids were you looking at last?
        val wids = Autosave.OR("DomFidPath."+reactor, stok.au.get._id, Map(
          "specWpath"  -> """""",
          "storyWpath" -> """"""
        ))

        var specWpath = wids("specWpath")
        var storyWpath = wids("storyWpath")

        // need settings?
        if(iSpecWpath != "?" && iSpecWpath != specWpath) {
          DomWorker later AutosaveSet("DomFidPath."+reactor, stok.au.get._id, Map(
            "specWpath"  -> iSpecWpath,
            "storyWpath" -> storyWpath
          ))
          specWpath = iSpecWpath
        }


        // need settings?
        if(iStoryWpath != "?" && iStoryWpath != storyWpath) {
          DomWorker later AutosaveSet("DomFidPath."+reactor, stok.au.get._id, Map(
            "specWpath"  -> specWpath,
            "storyWpath" -> iStoryWpath
          ))
          storyWpath = iStoryWpath
        }

        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
        val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)

        //2 their contents
        val spec = Autosave.OR("DomFidSpec."+reactor+"."+specWpath, stok.au.get._id, Map(
          "content"  -> spw
        )).apply("content")

        val story = Autosave.OR("DomFidStory."+reactor+"."+storyWpath, stok.au.get._id, Map(
          "content"  -> stw
        )).apply("content")

        val id = java.lang.System.currentTimeMillis().toString()

        ROK.k reactorLayout12 {implicit stok=>
          views.html.fiddle.playDomFiddle(reactor, q, spec, story, specWpath, storyWpath, (spw != story), (stw != story), Some(""), id)
        }
  }

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  /** list cats */
  def domListCat(cat: String, reactor:String) = FAUR { implicit stok=>
    val what=cat.toLowerCase
    Ok(
      s"""<a href="/diesel/fiddle/playDom/$reactor?$what=">none (fiddle)</a><br>"""+
      Wikis(reactor).pageNames(cat).map(s=>
      s"""<a href="/diesel/fiddle/playDom/$reactor?$what=$reactor.$cat:$s">$s</a>"""
    ).mkString("<br>")
    )
  }

  /** display the play sfiddle screen */
  def buildDom1(id: String) = FAUPR { implicit stok=>
    val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("reactor").mkString)
    val specWpath = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("specWpath").mkString)
    val storyWpath = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("storyWpath").mkString)
    val spec = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("spec").mkString)
    val story = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("story").mkString)

    //1. which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath."+reactor, stok.au.get._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    //2 their contents
      DomWorker later AutosaveSet("DomFidSpec."+reactor+"."+specWpath, stok.au.get._id, Map(
      "content"  -> spec
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)

    val page = new WikiEntry("Spec", "temp", "temp", "md", spec, stok.au.get._id, Seq("dslObject"), "")
    val dom = WikiDomain.domFrom(page).get.revise addRoot

    var res = Wikis.format(page.wid, page.markup, null, Some(page), stok.au)
    retj << Map(
      "res" -> res,
      "ca" -> RDExt.toCAjmap(dom), // todo should respect blenderMode ?
    "specChanged" -> (specWpath.length > 0 && spw.replaceAllLiterally("\r", "") != spec)
    )
  }

  import play.api.mvc._
  import play.api.Play.current
  import scala.concurrent.Future
  import akka.actor._

  val clients = new mutable.HashMap[String, ActorRef]()

//  def espOpen(reactor:String, id:String) = WebSocket.tryAcceptWithActor[String, String] { request =>
//    Future.successful(
// todo auth
//      Option(1) match {
//        case None => Left(Forbidden)
//        case Some(_) => {
//Right({out:ActorRef =>
//val x = new MyWebSocketActor(out, false)
//clients.put(id, x.self)
//Props(x)
//})
//          }
//      })
//)

  def espOpen(reactor:String, id:String) = WebSocket.acceptWithActor[String, String] { request => out =>
    Props(new MyWebSocketActor(out, id, false))
  }

  class MyWebSocketActor(out: ActorRef, id:String, client:Boolean) extends Actor {
    clients.put(id, self)

    def receive = {
      case msg: String =>
        out ! (js.tojson(Map("ping" -> true, "msg" -> msg)).toString)
      case m: Map[String,Any] =>
        out ! (js.tojson(m).toString)
    }

    // ping myself on a timer to clear dead connections
    override def preStart(): Unit = {
      import scala.concurrent.ExecutionContext.Implicits.global
      Akka.system.scheduler.schedule(
        Duration.create(30, TimeUnit.SECONDS),
        Duration.create(30, TimeUnit.SECONDS),
        this.self,
        "ping")
    }

    override def postStop() = {
      // socket closed
      clients.remove(id)
    }

  }


  /**
   * split a page. the page will then open a channel espOpenClient
   * the server will send with buildDom2
   */
  def startESP(reactor:String, id: String) = FAUPR { implicit stok =>
    val q = stok.req.queryString.map(t => (t._1, t._2.mkString))

    ROK.k reactorLayout12 {implicit stok=>
      views.html.fiddle.playESPDomFiddle(reactor, q, Some(""), id)
    }
  }

  val cachel = new mutable.ListBuffer[String]()
  val cachem = new mutable.HashMap[String,(WikiEntry,Option[RDomain])]()

  def orcached (we:WikiEntry, d: =>Option[RDomain]) : Option[RDomain] = {
    cachem.get(we.content).flatMap(_._2).orElse {
      val x = d
      cachem.put(we.content, (we, d))
      cachel.append(we.content)
      if(cachel.size > 100) {
        cachem.remove(cachel.remove(0))
      }
      d
    }
  }

  /** compute the instance
    *
    * todo perf: DB - parsing about 50-50
    * todo perf actor for async queued DB updates
    * todo perf specialized parser with just the DOM rules and no wiki/markdown, using WikiParserMini instead of WikiParserT
    *
    * @param id
    * @return
    */
  def buildDom2(id: String) = FAUPR { implicit stok=>
    val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String)=
      stok.req.body.asFormUrlEncoded.get.apply(name).mkString

    val stimer = new CSTimer("buildDom2", id)
    stimer start "heh"

    val settings = DomEngineSettings.fromRequest(stok.req)

    val saveMode = fParm("saveMode").toBoolean
    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    if(saveMode) {
    DomWorker later AutosaveSet("DomFidPath."+reactor, stok.au.get._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    //2 their contents
    DomWorker later AutosaveSet("DomFidStory."+reactor+"."+storyWpath, stok.au.get._id, Map(
      "content"  -> story
    ))
    }

    stimer snap "1_parse_req"

    val page = new WikiEntry("Spec", "temp", "temp", "md", spec, stok.au.get._id, Seq("dslObject"), "")
    val dom =
      if(settings.blenderMode) {
      val spw = WID.fromPath(specWpath)
      val d = Wikis(reactor).pages("Spec").filter(_.name != spw.map(_.name).mkString).toList.map{ p=>
//         if draft mode, find the auto-saved version if any
        if(settings.draftMode) {
          val c = Autosave.find("DomFidSpec."+reactor+"."+p.wid.wpath, stok.au.get._id).flatMap(_.get("content")).mkString
          if(c.length > 0)  p.copy(content=c)
          else p
        } else p
      }.flatMap(
//         to domain
        p=> orcached(p, WikiDomain.domFrom(p)).toList
      ).foldLeft(
        orcached(page, WikiDomain.domFrom(page)).get
      )((a,b) => a.plus(b)).revise.addRoot
      d
    } else
      orcached(page, WikiDomain.domFrom(page)).get.revise addRoot

    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", "temp", "temp", "md", story, stok.au.get._id, Seq("dslObject"), "")
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    stimer snap "3_parse_story"

    var res = ""

    val root=DomAst("root", "root")

    // not parse again.. take from dom
    root.children appendAll WikiDomain.domFilter(ipage) {
      case o:O if o.name != "context" => DomAst(o, "received")
      case v:EMsg => DomAst(v, "received")
      case v:EMock => DomAst(v, "mock")
      case e:ExpectM => DomAst(e, "test")
      case e:ExpectV => DomAst(e, "test")
    }

    // start processing all elements
    val engine = new DomEngine(dom, root, settings)

    root.children.foreach(engine.expand(_, true, 1))
    res += root.toHtml

    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    stimer snap "4_engine_expand"

    val wiki = Wikis.format(ipage.wid, ipage.markup, null, Some(ipage), stok.au)

    stimer snap "5_format_page"

    val m = Map(
      "res" -> res,
      "wiki" -> wiki,
      "ca" -> RDExt.toCAjmap(dom plus idom), // in blenderMode dom is full
      "failureCount" -> (root.collect {
        case d@DomAst(n:TestResult, _, _) if n.value.startsWith("fail") => n
      }).size,
      "storyChanged" -> (storyWpath.length > 0 && stw.replaceAllLiterally("\r", "") != story)
    )

    clients.get(id).foreach(_ ! m)
    clients.values.foreach(_ ! m)
    retj << m
  }

  /** compute the instance
    *
    * todo perf: DB - parsing about 50-50
    * todo perf actor for async queued DB updates
    * todo perf specialized parser with just the DOM rules and no wiki/markdown, using WikiParserMini instead of WikiParserT
    *
    * @param id
    * @return
    */
  def runWiki(cwid:CMDWID) = Action.async { implicit request =>
    val errors = new ListBuffer[String]()

    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    val resultMode = q.getOrElse("resultMode", "")

    cwid.wid.flatMap(_.page).map {we=>
      val PAT = """(\w*)/(\w*)""".r
      val PAT(e,a) = cwid.rest

      val nw = if(q.contains("dfiddle")) cwid.wid.get.copy(section=q.get("dfiddle")) else cwid.wid.get
      irunDom(we.realm, e, a, Some(nw)).apply(request)
    } getOrElse {
      errors append "WPath not found: [["+cwid.wpath.mkString+"]]"

      val ret = if("value" == resultMode) {
        Ok("")
      } else {
        // multiple values as json
        var m = Map(
          "values" -> Map.empty,
          "failureCount" -> 0,
          "errors" -> errors.toList
        )

        retj << m
      }

      Future.successful(ret)
    }
  }

  def runDom(reactor:String, e:String, a:String) = Action.async { implicit request =>
    irunDom(reactor, e, a, None).apply(request)
  }

  /** compute the instance
    *
    * todo perf: DB - parsing about 50-50
    * todo perf actor for async queued DB updates
    * todo perf specialized parser with just the DOM rules and no wiki/markdown, using WikiParserMini instead of WikiParserT
    *
    * @param id
    * @return
    */
  private def irunDom(reactor:String, e:String, a:String, useThisOne:Option[WID]) = FAUP { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String, dflt:String="") = q.getOrElse(name, dflt)

    val settings = DomEngineSettings.fromRequest(request)

    val RES_API = """
                    |Send result mode to control output:
                    | * val one single value, no matter what
                    | * json one or more values as Json
                    | * tree include the tree
                    |
                    | The Json always includes any errors.
                  """.stripMargin

    val resultMode = q.getOrElse("resultMode", "")

    val page = new WikiEntry("Spec", "temp", "temp", "md", "", au._id, Seq("dslObject"), "")

    val dom = if(settings.blenderMode) {
      //      val spw = WID.fromPath(specWpath)
      val stories = if(settings.sketchMode) Wikis(reactor).pages("Story")./*filter(_.name != stw.get.name).*/toList else Nil
      val d = (Wikis(reactor).pages("Spec")./*filter(_.name != spw.get.name).*/toList:::stories).map{
        // if draft mode, find the auto-saved version if any
        p=> if(settings.draftMode) {
          val c = Autosave.find("DomFid"+p.category+"."+reactor+"."+p.wid.wpath, au._id).flatMap(_.get("content")).mkString
          if(c.length > 0)  p.copy(content=c)
          else p
        } else p
      }.flatMap(
        // to domain
        p=> WikiDomain.domFrom(p).toList
      ).foldLeft(WikiDomain.domFrom(page).get)((a,b) => a.plus(b)).revise.addRoot
      d
    } else {
      //the contents of the fiddle
      val spec =
        if(useThisOne.isDefined) ""
        else Autosave.find("DomFidSpec."+reactor+".", au._id).mkString
      val page = new WikiEntry("Spec", "temp", "temp", "md", spec, au._id, Seq("dslObject"), "")
      WikiDomain.domFrom(page).get.revise.addRoot
    }

    val FILTER = Array("sketchMode", "mockMode", "blenderMode", "draftMode")
    var story = "$msg "+e+"."+a+" ("+q.filter(x=> ! FILTER.contains(x._1)).map(t=>t._1+"=\""+t._2+"\"").mkString(",")+")\n"
    clog << "STORY: " + story

    val story2 = if(settings.sketchMode) {
      // in sketch mode, add the temp fiddle tests - filter out messages, as we already have one
        useThisOne.map {p=>
          Autosave.find("DomFidStory."+reactor+"."+p.wpath, au._id).flatMap(_.get("content")) getOrElse p.content.mkString
        } getOrElse
          Autosave.find("DomFidStory."+reactor+".", au._id).mkString
    } else if(useThisOne.isDefined) {
      useThisOne.get.content.mkString
    } else ""

    story = story + story2.lines.filterNot(x=>
      x.trim.startsWith("$msg") || x.trim.startsWith("$receive")
    ).mkString("\n")

    val ipage = new WikiEntry("Story", "temp", "temp", "md", story, au._id, Seq("dslObject"), "")
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = ""

    val root=DomAst("root", "root")

    // not again.. take from dom
    root.children appendAll WikiDomain.domFilter(ipage) {
      case o:O if o.name != "context" => DomAst(o, "received")
      case v:EMsg => DomAst(v, "received")
      case v:EMock => DomAst(v, "mock")
      case e:ExpectM => DomAst(e, "test")
      case e:ExpectV => DomAst(e, "test")
    }

    // start processing all elements
    val engine = new DomEngine(dom, root, settings)

    root.children.foreach(engine.expand(_, true, 1))
    //    res += root.toString

    val errors = new ListBuffer[String]()

    // find the spec and check its result
    // then find the resulting value.. if not, then json
    val oattrs = dom.moreElements.collect{
      case n:EMsg if n.entity == e && n.met == a => n
    }.headOption.toList.flatMap(_.ret)

    if(oattrs.isEmpty) {
      errors append s"Can't find the spec for $e.$a"
    }

    import RDExt.stripQuotes

    // collect values
    val values = root.collect {
      case d@DomAst(EVal(p), /*"generated"*/ _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => (p.name, p.dflt)
    }

    if("value" == resultMode || "" == resultMode && oattrs.size == 1) {
      // one value
      val res = values.headOption.map(_._2).getOrElse("")
      Ok(stripQuotes(res))
    } else {
      // multiple values as json
      var m = Map(
        "values" -> values.toMap,
        "failureCount" -> (root.collect {
          case d@DomAst(n:TestResult, _, _) if n.value.startsWith("fail") => n
        }).size,
        "errors" -> errors.toList
      )

      if("treeHtml" == resultMode) m = m + ("tree" -> root.toHtml)
      if("treeJson" == resultMode) m = m + ("tree" -> root.toJson)

      if("debug" == resultMode) {
        Ok(root.toString).as("application/json")
      } else
        Ok(js.tojson(m).toString).as("application/json")
    }
  }

  /** display the play sfiddle screen */
  def diffDom(id: String) = FAUP { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String)= razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    //1. which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    import scala.collection.JavaConversions._

    def diffTable (p:Patch) = s"""<small>${views.html.admin.diffTable(p, Some(("How", "Orig", "Autosaved")))}</small>"""

    def diffT = diffTable(DiffUtils.diff(stw.lines.toList, story.lines.toList))
    def diffP = diffTable(DiffUtils.diff(spw.lines.toList, spec.lines.toList))

    retj << Map(
      "specDiff" -> (if(specWpath.length > 0) diffP else ""),
      "storyDiff" -> (if(storyWpath.length > 0) diffT else "")
    )
  }

  /** display the play sfiddle screen */
  def save(id: String, what:String) = FAUP { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    /** get parm from formrequest body or empty string */
    def fParm(name:String) =
      razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)


    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val newName = fParm("newName")
    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    if(!(au.isAdmin && reactor=="specs")) {
      Msg("You can't save in this reactor - if you want to create stories, please create your own")
    } else {
    //1. which wids were you looking at last?
      DomWorker later AutosaveSet("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    import razie.db.tx.txn

    if(newName.length > 0) {
      if(what == "Spec") {
        var we = WikiEntry("Spec", newName, newName, "md", spec, au._id, Seq("spec", "dsl"), Website.realm, 1)
        we.create
      } else if (what == "Story") {
        var we = WikiEntry("Story", newName, newName, "md", story, au._id, Seq("story", "dsl"), Website.realm, 1)
        we.create
      }
    } else {
      if(what == "Spec") {
        val spw = WID.fromPath(specWpath).flatMap(_.page)
        spw.map(_.update(spw.get.copy(content=spec), Some("saved diesel fiddle")))
      } else if (what == "Story") {
        val stw = WID.fromPath(storyWpath).flatMap(_.page)
        stw.map(_.update(stw.get.copy(content=story), Some("saved diesel fiddle")))
      }
    }

    Ok("done")
    }
  }

  /** revert the temp/auto to the original */
  def revert(id: String, what:String) = FAUP { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = razscr.dec(request.body.asFormUrlEncoded.get.apply("reactor").mkString)
    val specWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("specWpath").mkString)
    val storyWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("storyWpath").mkString)
    val spec = razscr.dec(request.body.asFormUrlEncoded.get.apply("spec").mkString)
    val story = razscr.dec(request.body.asFormUrlEncoded.get.apply("story").mkString)

    //1. which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    if(what == "Spec") {
      //2 their contents
      val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
      DomWorker later AutosaveSet("DomFidSpec."+reactor+"."+specWpath, au._id, Map(
        "content"  -> spw
      ))
    } else if (what == "Story") {
      //2 their contents
      val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
      DomWorker later AutosaveSet("DomFidStory."+reactor+"."+storyWpath, au._id, Map(
        "content"  -> stw
      ))
    }

    Ok("done")
  }

  /** display the play sfiddle screen */
  def invited = Action { implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    /** get parm from formrequest body or empty string */
    def fParm(name:String) =
      razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)
    def fqParm(name:String) =
      q.get(name).getOrElse(fParm(name))

    val email = fqParm("email").trim
    val invite = fqParm("invitation").trim

    SecLink.find(invite).map {secLink=>
      auth.map{au=>
        if(au.hasPerm(Perm.domFiddle))
          Redirect("/wiki/Admin:UserHome")
        else {
          if(secLink.link contains Enc.toUrl(au.email.dec)) {
            au.profile.map{p=>
              p.update(p.addPerm('+'+Perm.domFiddle))
              cleanAuth(Some(au))
              Emailer.withSession { implicit mailSession =>
                Emailer.tellRaz("reactivestories invitation used", s"email: $email   invite: $invite")
              }
              Redirect("/wiki/Admin:UserHome")
            } getOrElse Msg("No profile !!!???")
          } else Msg("Not your invite...")
        }
      } getOrElse {
        if(secLink.link contains Enc.toUrl(email))
          Msg("Ok - please proceed to create an account", "...and then click this link again to activate it")
        else
          Msg("Not your invite...")
      }
    } getOrElse
      Msg("No invitation found...")
  }

  /** display the play sfiddle screen */
  def invite(email:String) = FAU { implicit au => implicit errCollector => implicit request =>
    if(au.isAdmin && email != "-") {
      val id = new ObjectId()
      val link = "/diesel/invited?email="+Enc.toUrl(email)+"&invitation="+id.toString
      val sec = SecLink(link, Some("specs.dieselapps.com"),
        10, DateTime.now.plusDays(5), 0, DateTime.now, id)
      Msg("Invite link: "+sec.secUrl, "   code: "+id.toString)
    } else
      Msg("Ask an admin for an invite, please.")
  }
}

object DomWorker {
  // should be lazy because of akka's bootstrap
  lazy val worker = Akka.system.actorOf(Props[Worker], name = "DomWorker")

  case class AutosaveSet(name:String, userId: ObjectId, c:Map[String,String])

  def later (autosaveSet: AutosaveSet) = {worker ! autosaveSet}

  /**
   * doing stuff later
    */
  private class Worker extends Actor {
    def receive = {
      case a: AutosaveSet => {
        Autosave.set(a.name, a.userId, a.c)
      }
    }

    // upon start, reload ALL messages to send - whatever was not sent last time
//    override def preStart(): Unit = {
//      Akka.system.scheduler.schedule(
//        Duration.create(30, TimeUnit.SECONDS),
//        Duration.create(30, TimeUnit.MINUTES),
//        this.self,
//        CMD_TICK)
//      Akka.system.scheduler.scheduleOnce(
//        Duration.create(10, TimeUnit.SECONDS),
//        this.self,
//        CMD_RESTARTED)
//    }
  }
}

