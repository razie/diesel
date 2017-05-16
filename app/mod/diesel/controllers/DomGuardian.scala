package mod.diesel.controllers

import akka.actor.{Actor, Props}
import controllers._
import mod.diesel.controllers.DomUtils.{SAMPLE_SPEC, SAMPLE_STORY}
import mod.diesel.model.AstKinds._
import mod.diesel.model._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import razie.Logging
import razie.diesel.dom.RDOM.O
import razie.diesel.dom.SimpleECtx
import razie.diesel.ext._
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.dom.WikiDomain
import razie.wiki.model._
import razie.wiki.util.PlayTools

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// todo make instance in Reactor instead of a static
/** this is the default engine per reactor and user, continuously running all the stories */
object DomGuardian extends Logging {

  // statically collecting the last 500 results sets
  var asts: List[(String, String, DomAst)] = Nil

  def setHostname(ctx: SimpleECtx)(implicit stok: RazRequest): Unit = {
    ctx._hostname =
      Some(
        // on localhost, it shouldn't go out
        if (Services.config.isLocalhost) "localhost:9000"
        else PlayTools.getHost(stok.req).mkString
      )
  }

  /** load all stories for reactor, either drafts or final */
  def loadStories (settings:DomEngineSettings, reactor:String, au:Option[User], storyWpath:String) = {
    val pages =
      if (settings.blenderMode) {
        val list =
          settings.tagQuery.map {tagQuery=>
            // todo how can we optimize for large reactors: if it starts with "story" use the Story category?
            val tq = new TagQuery(tagQuery)
            (if(tq.ltags.contains("Story"))
              Wikis(reactor).pages("Story")
            else
              Wikis(reactor).pages("*")
            )
            val x = Wikis(reactor).pages("*").filter(_.name contains "imple").toList
            x.filter(w=>tq.matches(w.tags)).toList
          } getOrElse {
            Wikis(reactor).pages("Story").toList
          }

        val maybeDrafts = list.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("DomFidStory." + reactor + "." + p.wid.wpath, au.get._id).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        maybeDrafts
      } else {
        val spw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
        val specName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("DomFidStory." + reactor + "." + storyWpath, au.get._id, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Story", specName, specName, "md", spec, au.get._id, Seq("dslObject"), reactor)
        List(page)
      }
    pages
  }

  /** prepare an engine
    */
  def prepEngine(id: String,
                 settings: DomEngineSettings,
                 reactor: String,
                 iroot: Option[DomAst],
                 justTests: Boolean,
                 au:Option[User],
                 useTheseStories:List[WikiEntry] = Nil,
                addFiddles:Boolean=false) = {

    val wids = Autosave.OR("DomFidPath." + reactor, au.get._id, Map(
      "specWpath" -> """""",
      "storyWpath" -> """"""
    ))

    var storyWpath = wids("storyWpath")

    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_STORY)
    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")

    val story = Autosave.OR("DomFidStory." + reactor + "." + storyWpath, au.get._id, Map(
      "content" -> stw
    )).apply("content")

    val id = java.lang.System.currentTimeMillis().toString()

    val pages =
      if (settings.blenderMode) {
        //      val spw = WID.fromPath(specWpath)
        //      val d = Wikis(reactor).pages("Spec").filter(_.name != spw.map(_.name).mkString).toList.map { p =>
        val d = Wikis(reactor).pages("Spec").toList.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("DomFidSpec." + reactor + "." + p.wid.wpath, au.get._id).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        d
      } else {
        var specWpath = wids("specWpath")
        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
        val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("DomFidSpec." + reactor + "." + specWpath, au.get._id, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Spec", specName, specName, "md", spec, au.get._id, Seq("dslObject"), reactor)
        List(page)
      }

    val dom = pages.flatMap(p =>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      WikiDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot

    //    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, au.get._id, Seq("dslObject"), reactor)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    //    stimer snap "3_parse_story"

    val root = iroot.getOrElse(DomAst("root", "root"))

    val stories = if(!useTheseStories.isEmpty) useTheseStories else List(ipage)
    addStoryToAst(root, stories, justTests, false, addFiddles)

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec)
    //    engine.ctx._hostname = settings.node
    //    setHostname(engine.ctx)

    engine
  }

  /** nice links to stories in AST trees */
  case class StoryNode (wpath:String) extends CanHtml {
    override def toHtml = "Story " + WID.fromPath(wpath).map(_.ahref).getOrElse(wpath)
    override def toString = "Story " + wpath
  }

  /* extract more nodes to run from the story - add them to root */
  def addStoryToAst(root: DomAst, stories: List[WikiEntry], justTests: Boolean = false, justMocks: Boolean = false, addFiddles:Boolean=false) = {
    var lastMsg: Option[EMsg] = None
    var lastAst: List[DomAst] = Nil
    var inSequence = true

    def addMsg(v: EMsg) = {
      lastMsg = Some(v);
      // withPrereq will cause the story messages to be ran in sequence
      lastAst = if (!(justTests || justMocks)) List(DomAst(v, RECEIVED).withPrereq({
        if (inSequence) lastAst.map(_.id)
        else Nil
      })) else Nil
      lastAst
    }

    def addStory (story:WikiEntry) = {

      if(stories.size > 1 || addFiddles)
        root.children appendAll {
          lastAst = List(DomAst(StoryNode(story.wid.wpath), "story").withPrereq(lastAst.map(_.id)))
          lastAst
        }

      root.children appendAll WikiDomain.domFilter(story) {
        case o: O if o.name != "context" => List(DomAst(o, RECEIVED))
        case v: EMsg if v.entity == "ctx" && v.met == "storySync" => {
          inSequence = true
          Nil
        }
        case v: EMsg if v.entity == "ctx" && v.met == "storyAsync" => {
          inSequence = false
          Nil
        }
        case v: EMsg => addMsg(v)
        case v: EVal => List(DomAst(v, RECEIVED))
        case v: ERule => List(DomAst(v, RULE))
        case v: EMock => List(DomAst(v, RULE))
        case e: ExpectM if (!justMocks) => {
          lastAst = List(DomAst(e.withGuard(lastMsg.map(_.asMatch)), "test").withPrereq(lastAst.map(_.id)))
          lastAst
        }
        case e: ExpectV if (!justMocks) => {
          lastAst = List(DomAst(e.withGuard(lastMsg.map(_.asMatch)), "test").withPrereq(lastAst.map(_.id)))
          lastAst
        }
      }.flatten
    }

    if(!addFiddles) {
      stories.foreach (addStory)
    } else {
      stories.foreach {story=>
        addStory(story)

        // add sections - for each fake a page
        story.sections.filter(s=>s.stype == "dfiddle" && (Array("spec","story") contains s.signature))
        .foreach {sec=>
          val newPage = new WikiEntry(story.wid.cat, "fiddle", "fiddle", "md",
              sec.content,
              story.by, Seq("dslObject"), story.realm)

          addStory(newPage)
        }
      }
    }
  }

  /** start a check run. the first time, it will init the guardian and listeners */
  def startCheck(realm:String, au:Option[User]) : (String, Future[Report]) = {
    if (! DomGuardian.init) {

      // listen to topic changes and re-run
      WikiObservers mini {
        case ev@WikiEvent(action, "WikiEntry", _, entity, oldEntity, _, _) => {
          val wid = WID.fromPath(ev.id).get
          val oldWid = ev.oldId.flatMap(WID.fromPath)

          if (entity.isDefined && entity.get.isInstanceOf[WikiEntry]) {
            val we = entity.get.asInstanceOf[WikiEntry]
            if (we.category == "Story" || we.tags.contains("story") ||
              we.category == "Spec" || we.tags.contains("spec")) {

              // re run all tests
              // todo use futures so we know if a test is already running
              // todo also cancel existing workflows
              DomGuardian.lastRuns.foreach {t=>
                val re="(\\w*)\\.(\\w*)".r
                val re(realm, uname) = t._1
                startCheck(t._2.realm, Users.findUserByUsername(uname))
              }
            }
          }
        }
      }
    }

    DomGuardian.runReq(au, realm)
  }

  case class RunReq (userName:String, engine:DomEngine, realm:String, failed:Int, total:Int, when:DateTime=DateTime.now())
  case class Report (userName:String, engine:DomEngine, realm:String, failed:Int, total:Int, duration:Long, when:DateTime=DateTime.now())

  // used for each run
  var mkSettings : () => DomEngineSettings = () => {
    new DomEngineSettings(
      mockMode = true,
      blenderMode = true,
      draftMode = false,
      sketchMode = false,
      execMode = "sync"
    )
  }

  private var isInit = false

  // (realm.username,report)
  private val lastRun = new mutable.HashMap[String,Report]()

  def stats = s"${lastRun.size} cached, ${curRun.size} in progress, ${asts.size} collected"

  def init = {
    val old = isInit
    if(!isInit) {
      isInit=true
    }
    old
  }

  def findLastRun (realm:String, uname:String) = synchronized {
    lastRun.get(realm+"."+uname)
  }

  def lastRuns = synchronized {
    lastRun.toMap
  }

  private var curRun : Option[(String,DomEngine, Future[DomGuardian.Report])] = None

  // statically collect more asts
  def collectAst (stream:String, xid:String, root:DomAst) = synchronized {
      if (asts.size > 100) asts = asts.take(99)
      asts = (stream, xid, root) :: asts
  }

  /** if no test is currently running, start one */
  def runReq (au:Option[User], realm:String) = synchronized {
    if (!curRun.exists(_._1 == au.map(_.userName).mkString)) {
      val started = System.currentTimeMillis()

      val settings = mkSettings()
      settings.tagQuery = Website.forRealm(realm).flatMap(_.prop("guardian.settings.query"))
      val addFiddles = Website.forRealm(realm).flatMap(_.bprop("guardian.settings.fiddles")).getOrElse(false)

      // run all stories not just the tests
      val engine = prepEngine(new ObjectId().toString, settings, realm, None, false, au,
        loadStories (settings, realm, au, ""),
        addFiddles
      )

      // decompose all nodes, not just the tests
      val fut = engine.process.map { engine =>
        collectAst("", engine.id, engine.root)

        val failed = engine.failedTestCount
        val success = engine.successTestCount

        val r = Report(au.get.userName, engine, realm, failed, failed+success, System.currentTimeMillis - started)
        worker ! r
        r
      }
      curRun = Some(engine.id, engine, fut)
    }
    (curRun.get._2.id, curRun.get._3)
  }

  lazy val worker = Akka.system.actorOf(Props[GuardianActor], name = "GuardianActor")

  class GuardianActor extends Actor {
    def receive = {
      case rr : Report => synchronized {
        lastRun.put (rr.realm+"."+rr.userName, rr)
      }
    }
  }
}

