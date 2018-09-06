/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.controllers

import java.util

import admin.Config
import akka.actor.{Actor, Props}
import controllers.RazRequest
import mod.diesel.controllers.DomUtils.{SAMPLE_SPEC, SAMPLE_STORY}
import model.{User, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import razie.Logging
import razie.diesel.dom.{DomAst, RDomain, SimpleECtx, WikiDomain}
import razie.diesel.engine.{DieselAppContext, DomEngine, DomEngineSettings, RDExt}
import razie.diesel.ext._
import razie.diesel.utils.{DomCollector, SpecCache}
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.wiki.util.PlayTools

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

// todo make instance in Reactor instead of a static
/** this is the default engine per reactor and user, continuously running all the stories */
object DomGuardian extends Logging {

  def setHostname(ctx: SimpleECtx)(implicit stok: RazRequest): Unit = {
    ctx._hostname =
      Some(
        // on localhost, it shouldn't go out
        if (Services.config.isLocalhost) "localhost:9000"
        else PlayTools.getHost(stok.req).mkString
      )
  }

  /** load all stories for reactor, either drafts or final and return the list of wikis */
  def loadStories(settings: DomEngineSettings, reactor: String, userId: Option[ObjectId], storyWpath: String) = {
    val uid = userId.getOrElse(new ObjectId())
    val pages =
      if (settings.blenderMode) {
        val list =
          settings.tagQuery.map { tagQuery =>
            // todo how can we optimize for large reactors: if it starts with "story" use the Story category?
            val tq = new TagQuery(tagQuery)

            // todo optimize
            val wl = (if (tq.ltags.contains("Story"))
              catPages("Story", reactor)
            else
              Wikis(reactor).pages("*")
              )

            // todo WTF is this imple?
            val x = wl /*.filter(_.name contains "imple")*/ .toList

            x.filter(w => tq.matches(w.tags))
          } getOrElse {
            // todo optimize
            //            Wikis(reactor).pages("Story").toList
            Wikis(reactor).pages("*").filter(w => w.tags.contains("story")).toList
          }

        val maybeDrafts = list.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("DomFidStory", reactor, p.wid.wpath, uid).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        maybeDrafts
      } else {
        val spw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
        val specName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("DomFidStory", reactor, storyWpath, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Story", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }
    pages
  }

  /** inherit specs from mixins */
  def catPages (cat:String, realm:String): List[WikiEntry] = {
    if("Spec" == cat) {
      val w = Wikis(realm)
      val l = (w.pages(cat).toList ::: w.mixins.flatten.flatMap(_.pages(cat).toList))
      // distinct
      val b = ListBuffer[WikiEntry]()
      val seen = mutable.HashSet[WikiEntry]()
      for (x <- l) {
        if (!seen.exists(y=>y.category == x.category && y.name == x.name)) {
          b append x
          seen += x
        }
      }
      b.toList
    } else {
      Wikis(realm).pages(cat).toList
    }
  }

  /**
    * prepare an engine, loading an entire reactor/realm
    */
  def prepEngine(id: String,
                 settings: DomEngineSettings,
                 reactor: String,
                 iroot: Option[DomAst],
                 justTests: Boolean,
                 au: Option[User],
                 useTheseStories: List[WikiEntry] = Nil,
                 addFiddles: Boolean = false) = {
    val uid = au.map(_._id).getOrElse(new ObjectId())

    // is there a current fiddle in this reactor/user?
    val wids = Autosave.OR("DomFidPath", reactor, "", uid, Map(
      "specWpath" -> """""",
      "storyWpath" -> """"""
    ))

    var storyWpath = wids("storyWpath")

    val stw =
      WID
        .fromPath(storyWpath)
        .flatMap(_.page)
        .map(_.content)
        .getOrElse(SAMPLE_STORY)

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")

    val story = Autosave.OR("DomFidStory", reactor, storyWpath, uid, Map(
      "content" -> stw
    )).apply("content")

    val id = java.lang.System.currentTimeMillis().toString()

    val pages =
      if (settings.blenderMode) { // blend all specs and stories
        val d = catPages("Spec", reactor).toList.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            // todo uid here is always anonymous - do we use the reactor owner as default?
            val a = Autosave.find("DomFidSpec", reactor, p.wid.wpath, uid)
            val c = a.flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        d
      } else {
        var specWpath = wids("specWpath")
        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(SAMPLE_SPEC)
        val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("DomFidSpec", reactor, specWpath, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }

    // from all the stories, we need to extract all the spec fiddles and add to the dom
    val specFiddles = useTheseStories.flatMap { p =>
      // add sections - for each fake a page
      // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag
      p :: sectionsToPages(p, p.sections.filter(s => s.stype == "dfiddle" && (Array("spec") contains s.signature)))
    }

    // finally build teh entire fom

    val dom = (pages ::: specFiddles).flatMap(p =>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot


    //    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), reactor)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    //    stimer snap "3_parse_story"

    val root = iroot.getOrElse(DomAst("root", "root"))

    val stories = if (!useTheseStories.isEmpty) useTheseStories else List(ipage)
    addStoryToAst(root, stories, justTests, false, addFiddles)

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec)
    //    engine.ctx._hostname = settings.node
    //    setHostname(engine.ctx)

    engine
  }

  /** nice links to stories in AST trees */
  case class StoryNode(wpath: String) extends CanHtml {
    override def toHtml = "Story " + WID.fromPath(wpath).map(_.ahref).getOrElse(wpath)

    override def toString = "Story " + wpath
  }

  /* extract more nodes to run from the story - add them to root */
  def sectionsToPages(story: WikiEntry, sections: List[WikiSection]): List[WikiEntry] = {
    sections
      .map { sec =>
        val newPage = new WikiEntry(story.wid.cat, story.wid.name + "#" + sec.name, "fiddle", "md",
          sec.content,
          story.by, Seq("dslObject"), story.realm)
        newPage
      }
  }

  /* extract more nodes to run from the story - add them to root */
  def addStoryToAst(root: DomAst, stories: List[WikiEntry], justTests: Boolean = false, justMocks: Boolean = false, addFiddles: Boolean = false) = {

    val allStories = if (!addFiddles) {
      stories
    } else {
      stories.flatMap { story =>
        // add sections - for each fake a page
        // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag
        story :: sectionsToPages(story, story.sections.filter(s => s.stype == "dfiddle" && (Array("story") contains s.signature)))
      }
    }

    RDExt.addStoryToAst(root, allStories, justTests, justMocks, addFiddles)
  }

  /** start a check run. the first time, it will init the guardian and listeners */
  def startCheck(realm: String, au: Option[User]) : Future[Report] = {
    if (!DomGuardian.init) {

      // listen to topic changes and re-run
      WikiObservers mini {
        case ev@WikiEvent(action, "WikiEntry", _, entity, oldEntity, _, _) => {
          val wid = WID.fromPath(ev.id).get
          val oldWid = ev.oldId.flatMap(WID.fromPath)

          if (entity.isDefined && entity.get.isInstanceOf[WikiEntry]) {
            val we = entity.get.asInstanceOf[WikiEntry]
            if (we.category == "Story" || we.tags.contains("story") ||
              we.category == "Spec" || we.tags.contains("spec")) {

              // re run all tests for current realm
              // todo also cancel existing workflows
              DomGuardian.lastRuns.filter(_._2.realm == we.realm).headOption.map { t =>
                val re = "(\\w*)\\.(\\w*)".r
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

  case class Report(userName: String, engine: DomEngine, realm: String, failed: Int, total: Int, duration: Long, when: DateTime = DateTime.now())

  case class RunReq(au:Option[User], userName: String, realm: String, when: DateTime = DateTime.now()) {
    def run : Future[Report]  = DomGuardian.synchronized {
      val started = System.currentTimeMillis()

      log("RunReq.run() start")

      val settings = mkSettings()
      settings.tagQuery = Website.forRealm(realm).flatMap(_.prop("guardian.settings.query"))
      settings.realm = Some(realm)

      if (Config.isLocalhost)
        settings.hostport = Some(Config.hostport)
      else
        settings.hostport = Website.forRealm(realm).map(_.domain)

      val addFiddles = Website.forRealm(realm).flatMap(_.bprop("guardian.settings.fiddles")).getOrElse(false)

      val stories = loadStories(settings, realm, au.map(_._id), "")

      // a reactor without tests... skip it
      if (stories.size == 0) {
        return Future.successful(Report("?", null, "?", 0, 0, 0))
        // return a random report
//        if (lastRun.values.head != null)
//          return ("", Future.successful(lastRun.values.head))
//        else
//          throw new IllegalArgumentException("No runs yet... try again later")
      }

      // run all stories not just the tests
      val engine = prepEngine(
        new ObjectId().toString,
        settings,
        realm,
        None,
        false,
        au,
        stories,
        addFiddles
      )

      // decompose all nodes, not just the tests
      val fut = engine.process.map { engine =>
        DomCollector.collectAst("guardian", engine.id, engine)

        val failed = engine.failedTestCount
        val success = engine.successTestCount

        val r = Report(au.get.userName, engine, realm, failed, failed + success, System.currentTimeMillis - started)
        worker ! r
        r
      }

      curRun = Some((engine.id, engine, fut))

      fut
    }
  }

  // used for each run
  var mkSettings: () => DomEngineSettings = () => {
    new DomEngineSettings(
      mockMode = true,
      blenderMode = true,
      draftMode = false,
      sketchMode = false,
      execMode = "sync"
    )
  }

  @volatile
  private var isInit = false

  // (realm.username,report)
  private val lastRun = new mutable.HashMap[String, Report]()

  def stats = DomCollector.withAsts { asts =>
    s"${lastRun.size} cached, ${curRun.size} in progress, ${asts.size} collected"
  }

  def init = {
    val old = isInit
    if (!isInit) {
      isInit = true
    }
    old
  }

  def findLastRun(realm: String, uname: String) = synchronized {
    lastRun.get(realm + "." + uname)
  }

  def lastRuns = synchronized {
    lastRun.toMap
  }

  private var curRun: Option[(String, DomEngine, Future[DomGuardian.Report])] = None

  /** if no test is currently running, start one */
  def runReq(au: Option[User], realm: String): Future[Report] = DomGuardian.synchronized {
    logger.debug("GuardianActor received a RunReq")
    val rr = RunReq (au, au.map(_.userName).mkString, realm)
    val k = rr.realm + "." + rr.userName

    debouncer.find(_._1 == k).filter(! _._3.isCompleted).map {rr=>
      rr._3 // one in progress, return its Future
    } getOrElse {
      // maybe clean
      debouncer = debouncer.filter(_._1 == k)
      val fut = rr.run
      debouncer.append((k, rr, fut))
      fut
    }
  }

  lazy val worker = Akka.system.actorOf(Props[GuardianActor], name = "GuardianActor")

  private var debouncer = new mutable.ListBuffer[(String, RunReq, Future[Report])]()

  /** actually does the work */
  class GuardianActor extends Actor {

    def receive = {
//      case rr: RunReq=> DomGuardian.synchronized {
//        logger.debug("GuardianActor received a RunReq")
//        rr.run
//        val k = rr.realm + "." + rr.userName
//
//        if (!queue.exists(_._1 == k)) {
//          queue.append((k, rr))
//          rr.run
//        } else {
//          p.success(Report("?", null, "?", 0, 0, 0)) // todo use an empty report
//        }
//      }

      case r: Report => DomGuardian.synchronized {
        logger.debug(s"GuardianActor received a Report ${r.realm}.${r.userName}")
        lastRun.put(r.realm + "." + r.userName, r)

        val k = r.realm+"."+r.userName
        debouncer = debouncer.filter(_._1 == k)
      }
    }
  }

}

