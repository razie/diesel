package mod.diesel.controllers

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props, _}
import mod.diesel.controllers.DomUtils.{SAMPLE_SPEC, SAMPLE_STORY}
import mod.diesel.model.DomEngineHelper
import razie.diesel.dom.AstKinds._
import razie.diesel.engine.RDExt._
import razie.diesel.dom._
import model._
import play.api.Play.current
import play.api.mvc._
import play.libs.Akka
import razie.diesel.dom.WikiDomain
import razie.diesel.engine.{DieselAppContext, RDExt}
import razie.diesel.utils.SpecCache
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{CSTimer, Logging, js}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

/** controller for server side fiddles / services */
object DomFiddles extends DomApi with Logging {

  /** display the play sfiddle screen */
  def playDom(iSpecWpath:String, iStoryWpath:String, line:String, col:String) = FAUPRAPI(true) { implicit stok =>
    val reactor = stok.realm

    //1. which wids were you looking at last?
    val wids = Autosave.OR("DomFidPath", reactor, "", stok.au.get._id, Map(
      "specWpath"  -> "",
      "storyWpath" -> ""
    ))

    var specWpath = wids("specWpath")
    var storyWpath = wids("storyWpath")

    // need settings?
    if(iSpecWpath != "?" && iSpecWpath != specWpath) {
      DomWorker later AutosaveSet("DomFidPath",reactor,"", stok.au.get._id, Map(
        "specWpath"  -> iSpecWpath,
        "storyWpath" -> storyWpath
      ))
      specWpath = iSpecWpath
    }

    // need saved?
    if(iStoryWpath != "?" && iStoryWpath != storyWpath) {
      DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
        "specWpath"  -> specWpath,
        "storyWpath" -> iStoryWpath
      ))
      storyWpath = iStoryWpath
    }

    val origSpec = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
    val origStory = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)

    //2 their contents
    val spec = Autosave.OR("DomFidSpec",reactor,specWpath, stok.au.get._id, Map(
      "content"  -> origSpec
    )).apply("content")

    val story = Autosave.OR("DomFidStory",reactor,storyWpath, stok.au.get._id, Map(
      "content"  -> origStory
    )).apply("content")

    val capture = Autosave.OR("DomFidCapture",reactor,"", stok.au.get._id, Map(
      "content"  -> "Paste AST capture here"
    )).apply("content")

    val id = java.lang.System.currentTimeMillis().toString()

    ROK.k reactorLayout12FullPage {
      val wp = if(iSpecWpath.length > 1) iSpecWpath else iStoryWpath
      views.html.fiddle.playDomFiddle(
        reactor,
        stok.query,
        origSpec,
        spec,
        origStory,
        story,
        capture,
        specWpath,
        storyWpath,
        (origSpec != spec),
        (origStory != story),
        Some(""),
        id,
        wp,
        line,
        col)
    }
  }

  /**
    * ESP
    *
    * current active pairs are kept here, indexed per a unique fiddle id.
    * they purge themselves when done
    *
    * todo review - this doesn't work well - different users may clash with eachother etc
    */

  // active clients, for broadcasting
  val clients = new mutable.HashMap[String, ActorRef]()

  /**
   * 1. split a page. the page will then open a channel espOpenClient
   * the server will send with buildDom2
   *
   * do this to start a new page with session:
   * window.open('/diesel/fiddle/startESP/specs/1478275158003');//?spec=specs.Spec:flow_spec')
   */
  def startESP(reactor:String, id: String) = FAUPR { implicit stok =>

    ROK.k reactorLayout12 {
      views.html.fiddle.playESPDomFiddle(reactor, stok.query, Some(""), id)
    }
  }

  /**
   * 2. client page start an ESP socket - this is the client page connecting through here
   *
   * @param reactor
   * @param id
   * @return
   */

  def espOpen(reactor:String, id:String) = WebSocket.acceptWithActor[String, String] { request => out =>
    Props(new MyWebSocketActor(out, id, false))
  }

  /** 3. websocket handler - one per socket
    *
    * it's a simple relay - send it Maps and it will toJson and send them over
    *
    * it will also ping the pages on a schedule and die and close socket when client no longer there
    *
    * see playESPDomFiddle
    */
  class MyWebSocketActor(out: ActorRef, id:String, client:Boolean) extends Actor {
    clients.put(id, self)

    def receive = {
      case msg: String =>
        out ! (js.tojson(Map("ping" -> true, "msg" -> msg)).toString)
      case m : Map[_,_] => //      case m: Map[String,Any] =>
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

  /** fiddle screen - spec changed, rerun and resend new tree */
  def fiddleSpecUpdated(id: String) = FAUPRAPI(true) { implicit stok=>
    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = stok.formParm("spec")
    val story = stok.formParm("story")
    val capture = stok.formParm("capture")

    //autosave which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
    val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

    //autosave their contents
    DomWorker later AutosaveSet("DomFidSpec", reactor, specWpath, stok.au.get._id, Map(
      "content"  -> spec
    ))
    DomWorker later AutosaveSet("DomFidCapture", reactor, "", stok.au.get._id, Map(
      "content"  -> capture
    ))

    val page = new WikiEntry("Spec", specName, specName, "md", spec, stok.au.get._id, Seq("dslObject"), stok.realm)
    val dom = WikiDomain.domFrom(page).get.revise addRoot

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, stok.au.get._id, Seq("dslObject"), stok.realm)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = Wikis.format(page.wid, page.markup, null, Some(page), stok.au)
    retj << Map(
      "res" -> res,
      // todo should respect blenderMode ?
      "ca" -> RDExt.toCAjmap(dom plus idom), // C.assist options
      "specChanged" -> (specWpath.length > 0 && spw.replaceAllLiterally("\r", "") != spec)
    )
  }

  /** fiddle screen - story changed
    *
    * todo perf: DB - parsing about 50-50
    * todo perf actor for async queued DB updates
    * todo perf specialized parser with just the DOM rules and no wiki/markdown, using WikiParserMini instead of WikiParserT
    *
    * @param id - unique session / page Id, used to identify WebSocket customers too
    * @return
    */
  def fiddleStoryUpdated(id: String) : Action[AnyContent] = RAction.async { implicit stok=>
    val stimer = new CSTimer("buildDomStory", id)
    stimer start "heh"

    val settings = DomEngineHelper.settingsFrom(stok)

    val saveMode = stok.formParm("saveMode").toBoolean
    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = stok.formParm("spec")
    val story = stok.formParm("story")
    val capture = stok.formParm("capture")
    val realTime = stok.formParm("realTime").toBoolean

    val uid = stok.au.map(_._id).getOrElse(NOUSER)

    if(saveMode && stok.au.exists(_.isActive)) {
      DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
        "specWpath"  -> specWpath,
        "storyWpath" -> storyWpath
      ))

      //2 their contents
      DomWorker later AutosaveSet("DomFidStory", reactor,storyWpath, stok.au.get._id, Map(
        "content"  -> story
      ))
      DomWorker later AutosaveSet("DomFidCapture",reactor,"", stok.au.get._id, Map(
        "content"  -> capture
      ))
    }

    stimer snap "1_parse_req"

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
    val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

    val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), stok.realm)

    val pages =
      if(settings.blenderMode) {
        //        val spw = WID.fromPath(specWpath)
        //        val d = Wikis(reactor).pages("Spec").filter(_.name != spw.map(_.name).mkString).toList.map { p =>
        val d = Wikis(reactor).pages("Spec").toList.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("DomFidSpec", reactor, p.wid.wpath, uid).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }

        d
      } else
        List(page)

    // todo is adding page twice...
    val dom = pages.flatMap(p=>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a,b) => a.plus(b)).revise.addRoot

    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), stok.realm)

    stimer snap "3_parse_story"

    var res = ""
    var captureTree = ""

    val root = if(capture startsWith "{") {
      // is this a captured tree?
      val m = js.parse(capture)
      // is teh map from a debug session or just the AST
      val d = (
        if(m contains "tree") DieselJsonFactory.fromj(m("tree").asInstanceOf[Map[String,Any]]).asInstanceOf[DomAst]
        else DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
        ).withDetails("(from capture)")
      captureTree = d.toHtml
      addStoryToAst(d, List(ipage), true)
      d
    } else {
      val d = DomAst("root", ROOT).withDetails("(from story)")
      addStoryToAst(d, List(ipage))
      d
    }

    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec)
    setHostname(engine.ctx.root)

    // decompose all tree or just testing? - if there is a capture, I will only test it
    val fut =
//      if(! realTime) {
        // don't process or wait
//        Future.successful(engine)
//      } else {
        if (capture startsWith "{") {
          engine.processTests
        } else {
          engine.process
        }
//      }

    fut.map {engine =>
      res += engine.root.toHtml

      val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

      stimer snap "4_engine_expand"

      val wiki = Wikis.format(ipage.wid, ipage.markup, null, Some(ipage), stok.au)

      stimer snap "5_format_page"

      val m = Map(
        "res" -> res,
        "capture" -> captureTree,
        "wiki" -> wiki,
        "ca" -> RDExt.toCAjmap(dom plus idom), // in blenderMode dom is full
        "failureCount" -> engine.failedTestCount,
        "storyChanged" -> (storyWpath.length > 0 && stw.replaceAllLiterally("\r", "") != story),
        "realTime" -> realTime
      )

      clients.get(id).foreach(_ ! m)
      clients.values.foreach(_ ! m) // todo WTF am I broadcasting?
      retj << m
    }
  }

  /** save the draft story/spec (what) to original, as new version */
  def save(id: String, what:String) = FAUPR { implicit stok=>
    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val newName = stok.formParm("newName")
    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = stok.formParm("spec")
    val story = stok.formParm("story")

    if(!stok.au.get.isAdmin && reactor=="specs") {
      Msg("You can't save in this reactor - if you want to create stories, please create your own")
    } else {
    //1. which wids were you looking at last?
      DomWorker later AutosaveSet("DomFidPath",reactor,"", stok.au.get._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    import razie.db.tx.txn

    def vis(p:WikiEntry) = {
      var we = p
      val vis = Wikis.mkVis(we.wid, we.realm)
      val wvis = Wikis.mkwVis(we.wid, we.realm)

      // visibility?
      if (! we.props.get("visibility").exists(_ == vis))
        we = we.cloneProps(we.props ++ Map("visibility" -> vis), stok.au.get._id)
      if (! we.props.get("wvis").exists(_ == wvis))
        we = we.cloneProps(we.props ++ Map("wvis" -> wvis), stok.au.get._id)

      we
    }

    if(newName.length > 0) {
      if(what == "Spec") {
        var we = WikiEntry("Spec", newName, newName, "md", spec, stok.au.get._id, Seq("spec", "dsl"), stok.realm, 1)
        vis(we).create
        Services ! WikiAudit(WikiAudit.UPD_EDIT, we.wid.wpathFull, Some(stok.au.get._id), None, Some(we), None)
      } else if (what == "Story") {
        var we = WikiEntry("Story", newName, newName, "md", story, stok.au.get._id, Seq("story", "dsl"), stok.realm, 1)
        vis(we).create
        Services ! WikiAudit(WikiAudit.UPD_EDIT, we.wid.wpathFull, Some(stok.au.get._id), None, Some(we), None)
      }
    } else {
      if(what == "Spec") {
        val spw = WID.fromPath(specWpath).flatMap(_.page)
        spw.map{old=>
          val n = old.copy(content=spec, ver=old.ver+1)
          old.update(n, Some("saved diesel fiddle"))
          Services ! WikiAudit(WikiAudit.UPD_EDIT, n.wid.wpathFull, Some(stok.au.get._id), None, Some(n), Some(old))
        }
      } else if (what == "Story") {
        val stw = WID.fromPath(storyWpath).flatMap(_.page)
        stw.map{old=>
          val n = old.copy(content=story, ver=old.ver+1)
          old.update(n, Some("saved diesel fiddle"))
          Services ! WikiAudit(WikiAudit.UPD_EDIT, n.wid.wpathFull, Some(stok.au.get._id), None, Some(n), Some(old))
        }
      }
    }

    // delete the drafts

    if(what == "Spec") {
      Autosave.delete("DomFidSpec",reactor,specWpath, stok.au.get._id)
    } else if (what == "Story") {
      Autosave.delete("DomFidStory",reactor,storyWpath, stok.au.get._id)
    }

    Ok("done")
    }
  }

  /** revert the temp/auto draft to the original */
  def revert(id: String, what:String) = FAUPR { implicit stok=>
    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("reactor").mkString)
    val specWpath = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("specWpath").mkString)
    val storyWpath = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("storyWpath").mkString)
    val spec = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("spec").mkString)
    val story = razscr.dec(stok.req.body.asFormUrlEncoded.get.apply("story").mkString)

    //1. which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath",reactor, "", stok.au.get._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    // delete the drafts

    if(what == "Spec") {
      Autosave.delete("DomFidSpec",reactor,specWpath, stok.au.get._id)
    } else if (what == "Story") {
      Autosave.delete("DomFidStory",reactor,storyWpath, stok.au.get._id)
    }

    // used to reset, but better to delete drafts?

//    if(what == "Spec") {
      //2 their contents
//      DomWorker later AutosaveSet("DomFidSpec",reactor,specWpath, stok.au.get._id, Map(
//        "content"  -> spw
//      ))
//    } else if (what == "Story") {
      //2 their contents
//      val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
//      DomWorker later AutosaveSet("DomFidStory",reactor,storyWpath, stok.au.get._id, Map(
//        "content"  -> stw
//      ))
//    }

    Ok("done")
  }

  def streamSimulator () = FAUR {implicit stok=>
    val id="aStream"
    val content = Autosave.OR("DomFidSim",stok.realm,id, stok.au.get._id, Map(
      "content"  -> "Paste AST capture here"
    )).apply("content")

    ROK.k reactorLayout12   {
      views.html.modules.diesel.streamSimulator()
    }
  }

  /** fiddle screen - spec changed */
  def streamCapture(id: String) = FAUPR { implicit stok=>
    val content = Autosave.OR("DomFidSim",stok.realm,id, stok.au.get._id, Map(
      "content"  -> "Paste AST capture here"
    )).apply("content")

    Ok(js.tojsons(js.parse(content))).as("application/text")
  }

  /** fiddle screen - spec changed */
  def streamUpdated(id: String) = FAUPR { implicit stok=>
    val capture = stok.formParm("capture")

    DomWorker later AutosaveSet("DomFidSim",stok.realm,id, stok.au.get._id, Map(
      "content"  -> capture
    ))

    Ok("ok")
  }
}


