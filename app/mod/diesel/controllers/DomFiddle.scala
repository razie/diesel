package mod.diesel.controllers

import akka.actor.{Actor, Props, _}
import controllers.{IgnoreErrors, VErrors, WikiAuthorization}
import java.util.concurrent.TimeUnit
import razie.diesel.utils.DomUtils.{SAMPLE_SPEC, SAMPLE_STORY}
import mod.diesel.model.DomEngineHelper
import org.joda.time.DateTime
import play.api.Play.current
import play.api.mvc._
import play.libs.Akka
import razie.diesel.engine.AstKinds._
import razie.diesel.engine._
import razie.diesel.dom.RDomain.DOM_LIST
import razie.diesel.dom.{WikiDomain, _}
import razie.diesel.engine.RDExt._
import razie.diesel.engine.{DieselAppContext, RDExt}
import razie.diesel.ext.{EnginePrep, HasPosition}
import razie.diesel.model.DieselMsg
import razie.diesel.utils.{AutosaveSet, DomCollector, DomWorker, SpecCache}
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{CSTimer, Logging, js}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Success

/** controller for server side fiddles / services */
object DomFiddles extends DomApi with Logging with WikiAuthorization {

  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry] = None)(implicit errCollector: VErrors = IgnoreErrors): Boolean =
    Services.wikiAuth.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canEdit(wid, u, w, props)(errCollector)

  /** display the play sfiddle screen */
  def playDom(iSpecWpath:String, iStoryWpath:String, line:String, col:String) = FAUR { implicit stok =>
    val reactor = stok.realm

    //1. which wids were you looking at last?
    val wids = Autosave.OR("DomFidPath", WID("", "").r(reactor), stok.au.get._id, Map(
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
    val spec = Autosave.OR("wikie",WID.fromPathWithRealm(specWpath, reactor).get, stok.au.get._id, Map(
      "content"  -> origSpec
    )).apply("content")

    val story = Autosave.OR("wikie",WID.fromPathWithRealm(storyWpath, reactor).get,stok.au.get._id, Map(
      "content"  -> origStory
    )).apply("content")

    val capture = Autosave.OR("DomFidCapture",WID("","").r(reactor), stok.au.get._id, Map(
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
  val clients = new TrieMap[String, ActorRef]()

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
    // was there another? don't leak them...
    clients.remove(id).map(DieselAppContext.getActorSystem.stop)

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
      DieselAppContext.getActorSystem.scheduler.schedule(
        Duration.create(30, TimeUnit.SECONDS),
        Duration.create(30, TimeUnit.SECONDS),
        this.self,
        "ping")
    }

    override def postStop() = {
      // socket closed
      if(clients.get(id).exists(_ eq this)) {
        clients.remove(id)
      }
    }
  }

  def getAstInfo(ipage:WikiEntry) = {
    val domList = ipage.collector.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].reverse
    val ast = domList.collect {
      case h: HasPosition if h.pos.isDefined => Map(
        "row" -> h.pos.get.line,
        "col" -> h.pos.get.col,
        "text" -> "?"
      )
    }.toList

    ast
  }

  /** fiddle screen - spec changed, parse spec and send new tree */
  def fiddleSpecUpdated(id: String) = FAUPRAPI(true) { implicit stok=>
    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = stok.formParm("spec")              // current text in textbox
    val story = stok.formParm("story")            // current text in textbox
    val capture = stok.formParm("capture")
    var timeStamp = stok.formParm("timeStamp")

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
    val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

    val now = DateTime.now
    val auto = AutosaveSet("wikie", reactor, specWpath, stok.au.get._id, Map(
      "content" -> spec
    ), Some(now)) // detect stale updates

    val autoRec = auto.rec // is there a draft ?

    // first check if it's newer - if the user clicks "back", a stale editor may overwrite a newer draft
    if (autoRec.exists(_.updDtm.isAfter(new DateTime(timeStamp.toLong)))) {
      // don't change "staleid" - used as search
      Conflict(s"staleid - please refresh page... $timeStamp - ${autoRec.get.updDtm.toInstant.getMillis}")
    } else {
      timeStamp = now.toInstant.getMillis.toString

      //autosave which wids were you looking at last?
      DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
        "specWpath" -> specWpath,
        "storyWpath" -> storyWpath
      ))

      //autosave draft - if none OR there are changes
      if (autoRec.nonEmpty || spec != spw) {
        DomWorker later auto
      }

      DomWorker later AutosaveSet("DomFidCapture", reactor, "", stok.au.get._id, Map(
        "content" -> capture
      ))

      val specPage = new WikiEntry("Spec", specName, specName, "md", spec, stok.au.get._id, Seq("dslObject"),
        stok.realm)
      val specDom = WikiDomain.domFrom(specPage).get.revise addRoot

      val storyPage = new WikiEntry("Story", storyName, storyName, "md", story, stok.au.get._id, Seq("dslObject"),
        stok.realm)
      val storyDom = WikiDomain.domFrom(storyPage).get.revise addRoot

      var res = Wikis.format(specPage.wid, specPage.markup, null, Some(specPage), stok.au)
      retj << Map(
        "res" -> res,
        // todo should respect blenderMode ?
        "ca" -> RDExt.toCAjmap(specDom plus storyDom), // C.assist options
        "specChanged" -> (specWpath.length > 0 && spw.replaceAllLiterally("\r", "") != spec),
        "ast" -> getAstInfo(specPage),
        "timeStamp" -> timeStamp
      )
    }
  }

  val WPATH_DEFAULT_EXECUTORS = "specs.Spec:Default_executors"

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
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
    val capture = stok.formParm("capture")
    val runEngine = stok.formParm("runEngine").toBoolean
    val scompileOnly = stok.formParm("compileOnly")
    val compileOnly = scompileOnly != "" && scompileOnly.toBoolean
    var timeStamp = stok.formParm("timeStamp")

    val uid = stok.au.map(_._id).getOrElse(NOUSER)

    val now = DateTime.now
    //autosave draft - if none and there are changes
    val auto = AutosaveSet("wikie", reactor, storyWpath, stok.au.get._id, Map(
      "content"  -> story
    ), Some(now)) // detect stale updates

    val autoRec = auto.rec // is there a draft ?

    // first check if it's newer - if the user clicks "back", a stale editor may overwrite a newer draft
    if (autoRec.exists(_.updDtm.isAfter(new DateTime(timeStamp.toLong)))) {
      // don't change "staleid" - used as search
      Future.successful(Conflict(s"staleid - please refresh page... $timeStamp - ${autoRec.get.updDtm.toInstant.getMillis}"))
    } else {
      timeStamp = now.toInstant.getMillis.toString

      if (saveMode && stok.au.exists(_.isActive)) {
        DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
          "specWpath" -> specWpath,
          "storyWpath" -> storyWpath
        ))

        if (autoRec.nonEmpty || story != stw) {
          DomWorker later auto
        }

        DomWorker later AutosaveSet("DomFidCapture", reactor, "", stok.au.get._id, Map(
          "content" -> capture
        ))
      }

      stimer snap "1_parse_req"

      val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
      val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

      // add the engine spec to be included in content assist
      val engSpec = WID.fromPath(WPATH_DEFAULT_EXECUTORS).flatMap(_.page).toList
      val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), stok.realm)

      val pages =
        if (settings.blenderMode) {
          val d = engSpec ::: EnginePrep.catPages("Spec", reactor).toList.map { p =>
            //         if draft mode, find the auto-saved version if any
            if (settings.draftMode) {
              val c = Autosave.find("wikie", p.wid, uid).flatMap(_.get("content")).mkString
              if (c.length > 0) p.copy(content = c)
              else p
            } else p
          }

          d
        } else
          engSpec ::: List(page)

      // todo is adding page twice...
      val dom = pages.flatMap(p =>
        SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
      ).foldLeft(
        RDomain.empty
      )((a, b) => a.plus(b)).revise.addRoot

      stimer snap "2_parse_specs"

      val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), stok.realm)

      val idom = WikiDomain.domFrom(ipage).get.revise addRoot

      stimer snap "3_parse_story"

      var captureTree = ""

      val root = if (capture startsWith "{") {
        // is this a captured tree?
        val m = js.parse(capture)
        // is teh map from a debug session or just the AST
        val d = (
            if (m contains "tree") DieselJsonFactory.fromj(
              m("tree").asInstanceOf[Map[String, Any]]).asInstanceOf[DomAst]
            else DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
            ).withDetails("(from capture)")
        captureTree = d.toHtml
        EnginePrep.addStoriesToAst(d, List(ipage), true)
        d
      } else {
        val d = DomAst("root", ROOT).withDetails("(from story)")
        EnginePrep.addStoriesToAst(d, List(ipage))
        d
      }

      stimer snap "4_build_dom_root"

      // start processing all elements
      val engine = DieselAppContext.mkEngine(
        dom,
        root,
        settings,
        ipage :: pages map WikiDomain.spec,
        DieselMsg.fiddleStoryUpdated)
      setHostname(engine.ctx.root)
      DomCollector.collectAst("fiddle", stok.realm, engine.id, stok.au.map(_.id), engine, stok.uri)

      // decompose all tree or just testing? - if there is a capture, I will only test it
      val fut =
//      if(! realTime) {
      // don't process or wait
//        Future.successful(engine)
//      } else {
        if (compileOnly || !runEngine) {
          // don't process or wait
          engine.discard
          Future.successful(engine)
        } else {
          if (capture startsWith "{") { // process tests on capture
            engine.processTests
          } else {
            engine.process // normal processing
          }
        }

      def sendResult (engine:DomEngine) = {
        val st = engine.status // copy so that if it completes wuile here, I'll still send again

        var res = engine.root.toHtml

        val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(
          "Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

        stimer snap "5_engine_expand"

        val wiki = Wikis.format(ipage.wid, ipage.markup, null, Some(ipage), stok.au)

        val m = Map(
          "clientId" -> id,
          "res" -> res,
          "capture" -> captureTree,
          "wiki" -> wiki,
          "ca" -> RDExt.toCAjmap(dom plus idom), // in blenderMode dom is full
          "totalCount" -> (engine.totalTestCount),
          "failureCount" -> engine.failedTestCount,
          "errorCount" -> engine.errorCount,
          "storyChanged" -> (storyWpath.length > 0 && stw.replaceAllLiterally("\r", "") != story),
          "ast" -> getAstInfo(ipage),
          "timeStamp" -> timeStamp,
          "engineId" -> engine.id,
          "engineStatus" -> st,
          "engineDone" -> DomState.isDone(st),
          "compileOnly" -> compileOnly
        )

        stimer snap "6_format_response"

        if(!compileOnly && DomState.isDone(st)) {
          log("  fiddleSU - sending WS: " + DomState.isDone(engine.status))
          clients.get(id).foreach(_ ! m)
          clients.values.foreach(_ ! m) // todo WTF am I broadcasting?
        }

        m
      }

      // wait at most 1 sec

      val delayedFuture = {
        akka.pattern.after(
          1000 millis,
          using = DieselAppContext.getActorSystem.scheduler)(Future.successful(engine))
      }

      val timeoutFuture = if(compileOnly) fut else Future firstCompletedOf Seq(fut, delayedFuture)

      // send if not sent already
      @volatile var sentWS = false

      if(! compileOnly) fut.onComplete {
        case Success(v) => {
          log("  fiddleSU - onComplete: " + DomState.isDone(engine.status))
          if(!sentWS) {
            sentWS = true
            sendResult(engine)
          }
          else {
            log("    fiddleSU - sent already")
          }
        }
      }

      timeoutFuture.map { engine =>
        val st = engine.status
        log("  fiddleSU - result: " + DomState.isDone(st))
        val m =  sendResult(engine)

        // dont' send WS again later
        if(DomState.isDone(st)) sentWS = true

        retj << m
      }
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

    // delete the drafts
    def deleteDrafts = {
      if (what == "Spec") {
        Autosave.delete("wikie", WID.fromPathWithRealm(specWpath, reactor).get, stok.au.get._id)
      } else if (what == "Story") {
        Autosave.delete("wikie", WID.fromPathWithRealm(storyWpath, reactor).get, stok.au.get._id)
      }
    }

    if(!stok.au.get.isAdmin && reactor=="specs") {
      Msg("You can't save in this reactor - if you want to create stories, please create your own")
    } else {
      //1. which wids were you looking at last?
      DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
        "specWpath" -> specWpath,
        "storyWpath" -> storyWpath
      ))

      import razie.db.tx.txn

      def vis(p: WikiEntry) = {
        var we = p
        val vis = Wikis.mkVis(we.wid, we.realm)
        val wvis = Wikis.mkwVis(we.wid, we.realm)

        // visibility?
        if (!we.props.get("visibility").exists(_ == vis))
          we = we.cloneProps(we.props ++ Map("visibility" -> vis), stok.au.get._id)
        if (!we.props.get("wvis").exists(_ == wvis))
          we = we.cloneProps(we.props ++ Map("wvis" -> wvis), stok.au.get._id)

        we
      }

      var saved = false

      // save as
      if (newName.length > 0) {

        if (what == "Spec") {
          var we = WikiEntry("Spec", newName, newName, "md", spec, stok.au.get._id, Seq("spec", "dsl"), stok.realm, 1)
          vis(we).create
          Services ! WikiAudit(WikiAudit.UPD_EDIT, we.wid.wpathFull, Some(stok.au.get._id), None, Some(we), None)
        } else if (what == "Story") {
          var we = WikiEntry("Story", newName, newName, "md", story, stok.au.get._id, Seq("story", "dsl"), stok.realm, 1)
          vis(we).create
          Services ! WikiAudit(WikiAudit.UPD_EDIT, we.wid.wpathFull, Some(stok.au.get._id), None, Some(we), None)
        }

        Ok("done")

      } else {
        // save existing

        def doit(spw: Option[WikiEntry], cont:String) = {
          spw.map { old =>
            if (canEdit(old.wid, stok.au, spw).exists(identity)) {
              saved = true
              val n = old.copy(content = cont, ver = old.ver + 1)
              old.update(n, Some("saved diesel fiddle"))
              Services ! WikiAudit(WikiAudit.UPD_EDIT, n.wid.wpathFull, Some(stok.au.get._id), None, Some(n), Some(old))

              if (saved) deleteDrafts
              Ok("done")
            } else
              Unauthorized("No permission to edit this...")
          } getOrElse
            Unauthorized("not found...")
        }

        if (what == "Spec") {
          val spw = WID.fromPath(specWpath).flatMap(_.page)
          doit(spw, spec)
        } else if (what == "Story") {
          val stw = WID.fromPath(storyWpath).flatMap(_.page)
          doit(stw, story)
        } else
          Unauthorized("what are you saving? " + what)
      }
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
      Autosave.delete("wikie",WID.fromPathWithRealm(specWpath, reactor).get, stok.au.get._id)
    } else if (what == "Story") {
      Autosave.delete("wikie",WID.fromPathWithRealm(storyWpath, reactor).get,stok.au.get._id)
    }

    // used to reset, but better to delete drafts?

//    if(what == "Spec") {
      //2 their contents
//      DomWorker later AutosaveSet("wikie",reactor,specWpath, stok.au.get._id, Map(
//        "content"  -> spw
//      ))
//    } else if (what == "Story") {
      //2 their contents
//      val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
//      DomWorker later AutosaveSet("wikie",reactor,storyWpath, stok.au.get._id, Map(
//        "content"  -> stw
//      ))
//    }

    Ok("done")
  }

  def streamSimulator () = FAUR {implicit stok=>
    val id="aStream"
    val content = Autosave.OR("DomFidSim", WID("", id).r(stok.realm), stok.au.get._id, Map(
      "content"  -> "Paste AST capture here"
    )).apply("content")

    ROK.k reactorLayout12   {
      views.html.modules.diesel.streamSimulator()
    }
  }

  /** fiddle screen - spec changed */
  def streamCapture(id: String) = FAUPR { implicit stok=>
    val content = Autosave.OR("DomFidSim", WID("", id).r(stok.realm), stok.au.get._id, Map(
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


