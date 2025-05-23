/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.nodes

import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.RDOM.{O, P}
import razie.diesel.dom.RDomain.DOM_LIST
import razie.diesel.dom.{RDomain, WikiDomain}
import razie.diesel.engine.DomEngineSettings.DEFAULT_TQ_SPECS
import razie.diesel.engine.DomEngineView.{errorCount, failedTestCount, successTestCount, todoTestCount, totalTestedCount}
import razie.diesel.engine._
import razie.diesel.expr.ScopeECtx
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.diesel.utils.{DomUtils, SpecCache}
import razie.tconf.{DSpec, EPos, TSpecRef, TagQuery}
import razie.wiki.admin.Autosave
import razie.wiki.model._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** story summary */
case class StoryTestStats(
  failed: Int,
  total: Int,
  success: Int,
  errors: Int,
  todo: Int
)

/** nice links to stories in AST trees */
case class StoryNode(path: TSpecRef) extends CanHtml with InfoNode {
  def x = s"""<a id="${path.wpath.replaceAll("^.*:", "")}"></a>""" // from wpath leave just name

  // calculated stats
  var stats: Option[StoryTestStats] = None

  /** get succ/fail. Stats become available once the story ends, see calculateStats */
  def getStats: StoryTestStats = stats.getOrElse(StoryTestStats(0, 0, 0, 0, 0))

  /** calculate succ/fail - called at end of story by engine, populates the stats */
  def calculateStats(ast: DomAst): StoryTestStats = {
    if (stats.isEmpty) stats = Some(calculateTempStats(ast))
    stats.get
  }

  /** calculate succ/fail - called at end of story by engine, populates the stats */
  def calculateTempStats(ast: DomAst): StoryTestStats = {
      val nodes = ast.children
      val failed = failedTestCount(nodes)
      val total = totalTestedCount(nodes)
      val success = successTestCount(nodes)
      val errors = errorCount(nodes)
      val todo = todoTestCount(nodes)
      StoryTestStats(failed, total, success, errors, todo)
  }

  override def toHtml = x + s"""Story ${path.ahref.mkString}"""

  override def toString = "Story " + path.wpath
}

/** engine prep utilities: load stories, parse DOMs etc */
object EnginePrep extends Logging {

  /** load all stories for reactor, either drafts or final and return the list of wikis */
  def loadStories(settings: DomEngineSettings, reactor: String, userId: Option[ObjectId], storyWpath: String) = {
    val uid = userId.getOrElse(new ObjectId())
    val pages = {
      // on blender or without a specific story - load tquery
      if (settings.blenderMode && storyWpath.length == 0) {
        val list =
          settings.tagQuery.map { tagQuery =>
            // todo how can we optimize for large reactors: if it starts with "story" use the Story category?
            val tq = new TagQuery(tagQuery)

            // reactor is this by default, but the tag query may overwrite with "realm.xxx"
            val r = tq.theRealm.getOrElse(reactor)

            // todo optimize
            val wl = (if (tq.ltags.contains("Story") || tq.ltags.contains("story"))
              catPages("Story", r)
            else
              Wikis(r).pages("*")
                )

            val x = wl.toList

            x.filter(w => tq.matches(w.tags))
          } getOrElse {
            // todo optimize
            Wikis(reactor).pages("*").filter(w => w.tags.contains("story")).toList
          }

        val maybeDrafts = list.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("wikie", p.wid.defaultRealmTo(reactor), uid).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        maybeDrafts
      } else {
        val spw = WID
            .fromPath(storyWpath)
            // todo not sure about this logic - clarify caching logic: if pages depends on users, they're not cached anyways...
            .flatMap(w => if(userId.isDefined) w.page else Wikis.cachedPage(w, None))
            .map(_.content)
            .getOrElse(DomUtils.SAMPLE_SPEC)

        val specName = WID
            .fromPath(storyWpath)
            .map(_.name)
            .getOrElse("fiddle")

        val spec = Autosave.OR("wikie", WID.fromPathWithRealm(storyWpath, reactor).get, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Story", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }
    }
    pages
  }

  /** filter pages if coming from different realms - overwrite base entries
    *
    * @param in              list of entries from all mixins
    * @param overwriteTopics if true will overwrite base realm topics with same name
    * @return
    */
  def mixinEntries(l: List[WikiEntry], overwriteTopics: Boolean = true): List[WikiEntry] = {

    // distinct in order - so I overwrite mixins
    // todo doesn't work unless the mixins are not in order - the mixins should always
    // be sorted

    val b = ListBuffer[WikiEntry]()
    val seen = mutable.HashSet[WikiEntry]()
    for (x <- l) {
      if (!seen.exists(
        y => (overwriteTopics || !overwriteTopics && y.realm == x.realm) &&
            y.category == x.category &&
            y.name == x.name)) {
        b append x
      }
      seen += x
    }
    b.toList
  }

  /** all pages of category - inherit ALL specs from mixins
    *
    * @param cat             cat to look for
    * @param realm
    * @param overwriteTopics if true will overwrite base realm topics with same name
    * @return
    */
  def catPages(cat: String, realm: String, overwriteTopics: Boolean = true): List[WikiEntry] = {
    val w = Wikis(realm)
    if ("Spec" == cat) {
      val m = w.mixins.flattened
      val l = (
          w.pages(cat).toList :::
              m.flatMap(_.pages(cat).toList)
                  .filter(x => !(x.tags.contains("private")))
          )

      mixinEntries(l).filter(x => !(x.tags.contains("exclude")))
    } else {
      w.pages(cat).toList
    }
  }

  /** all pages of category - inherit ALL specs from mixins
    *
    * filtered with default filters
    *
    * @param cat             cat to look for
    * @param realm
    * @param overwriteTopics if true will overwrite base realm topics with same name
    * @return
    */
  def catPagesFiltered(cat: String, realm: String, overwriteTopics: Boolean = true): List[WikiEntry] = {
    catPages(cat, realm, overwriteTopics)
        .filter(x => DEFAULT_TQ_SPECS.matches(x))
  }


  /**
    * prepare an engine, loading an entire reactor/realm
    *
    * globalStory is dumped at root level, the other stories are scoped
    */
  def prepEngine(id: String,
                 settings: DomEngineSettings,
                 reactor: String,
                 iroot: Option[DomAst],
                 justTests: Boolean,
                 au: Option[WikiUser],
                 description:String,
                 startStory:Option[WikiEntry]=None,
                 useTheseStories: List[WikiEntry] = Nil,
                 endStory:Option[WikiEntry]=None,
                 addFiddles: Boolean = false) : DomEngine = {

    var t1 = System.currentTimeMillis()

    val uid = au.map(_._id)
        .orElse(settings.configUserId)
        .getOrElse(new ObjectId())

    // is there a current fiddle in this reactor/user?
    val wids = Autosave.OR("DomFidPath", WID("", "").r(reactor), uid, Map(
      "specWpath" -> """""",
      "storyWpath" -> """"""
    ))

    var storyWpath = wids("storyWpath")

    val stw =
      WID
        .fromPath(storyWpath)
        .flatMap(w => Wikis.cachedPage(w, au))
        .map(_.content)
        .getOrElse(DomUtils.SAMPLE_STORY)

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")

    val story = Autosave.OR("wikie", WID.fromPathWithRealm(storyWpath, reactor).get, uid, Map(
      "content" -> stw
    )).apply("content")

    val specs =
      if (settings.blenderMode) { // blend all specs and stories
        val d = catPagesFiltered("Spec", reactor)
            .map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val a = Autosave.find("wikie", p.wid.defaultRealmTo(reactor), uid)
            val c = a.flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        d
      } else {
        val specWpath = wids("specWpath")
        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(DomUtils.SAMPLE_SPEC)
        val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("wikie", WID.fromPathWithRealm(specWpath,reactor).get, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }

    // from all the stories, we need to extract all the spec fiddles and add to the dom
    val specFiddles = useTheseStories.flatMap { p =>
      // add sections - for each fake a page
      // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag

      /* I used to include the story itself but it creates problems: $val in story become templates at the root
      context, see DIESEL_VALS
       */

//      p ::
      sectionsToPages (p, p.sections.filter(s => s.stype == "dfiddle" && (Array("spec") contains s.signature)))
    }

    var t2 = System.currentTimeMillis()

    // finally build teh entire fom

    val dom = (specs ::: specFiddles).flatMap(p =>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot

    var t3 = System.currentTimeMillis()

    //    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), reactor)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    //    stimer snap "3_parse_story"

    val root = iroot.getOrElse(DomAst("root", "root"))

    var t4 = System.currentTimeMillis()

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: specs map WikiDomain.spec, description)

    val stories = if (!useTheseStories.isEmpty) useTheseStories else List(ipage)

    startStory.map { we =>
      // main story adds to root, no scope wrappers - this is globals
      addStoryWithFiddlesToAst(engine, List(we), false, false, false)
    }

    addStoryWithFiddlesToAst(engine, stories, justTests, false, addFiddles)

    endStory.map { we =>
      // main story adds to root, no scope wrappers - this is globals
      addStoryWithFiddlesToAst(engine, List(we), false, false, false)
    }

    var t5 = System.currentTimeMillis()

    engine.root.prependAllNoEvents(List(
      DomAst(
        EInfo(s"Eng prep (prepEng) total=${t5-t1} ", s"total=${t5-t1} getTopics=${t2-t1} domFromTopics=${t3-t2} msgCompile=${t4-t3} addStories=${t5-t4}"),
        AstKinds.DEBUG)
          .withStatus(DomState.SKIPPED)
          .withDuration(t1, t5)
    ))

    engine.prepTime = t5-t1

    engine
  }

  final val FIDDLE = "fiddle"

  /* extract more nodes to run from the story - add them to root */
  def sectionsToPages(story: WikiEntry, sections: List[WikiSection]): List[WikiEntry] = {
    sections
      .map { sec =>
        // do NOT change the fiddle tag - is used elsewhere
        val newPage = new WikiEntry(story.wid.cat, story.wid.name + "#" + sec.name, FIDDLE, "md",
          sec.content,
          story.by, Seq("dslObject", FIDDLE), story.realm)
        newPage
      }
  }

  def hasFiddleStories (story:DSpec) =
    story.findSection(s => s.stype == "dfiddle" && (Array("story") contains s.tags)).isDefined

  /* extract more nodes to run from the story - add them to root */
  def listStoriesWithFiddles(stories: List[WikiEntry], addFiddles: Boolean = false) = {

    val allStories = if (!addFiddles) {
      stories
    } else {
      stories.flatMap { story =>
        // add sections - for each fake a page
        // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag
        story :: sectionsToPages(story, story.sections.filter(s => s.stype == "dfiddle" && (Array("story") contains s.signature)))
      }
    }

    allStories
  }

  /* extract more nodes to run from the story - add them to root */
  def addStoryWithFiddlesToAst(engine: DomEngine, stories: List[WikiEntry], justTests: Boolean = false,
                               justMocks: Boolean = false, addFiddles: Boolean = false) = {

    val allStories = listStoriesWithFiddles(stories, addFiddles)

    addStoriesToAst(engine, allStories, None, justTests, justMocks, addFiddles)
  }

  /* add a message */
  def addMsgToAst(root: DomAst, v : EMsg) = {
    val ast = DomAst(v, AstKinds.RECEIVED)
    root.appendAllNoEvents(List(ast))
    ast
  }

  /**
    * add all nodes from story and add them to root
    */
  def addStoriesToAst(engine: DomEngine, stories: List[DSpec], otherRoot:Option[DomAst] = None, justTests: Boolean = false, justMocks: Boolean =
  false, addFiddles: Boolean = false) = {

    engine.addedStories = engine.addedStories ::: stories

    var lastMsg: Option[EMsg] = None
    var lastMsgAst: Option[DomAst] = None
    var lastAst: List[DomAst] = Nil
    var inSequence = true
    val root = otherRoot.getOrElse(engine.root)

    /** hookup this new message to the last one */
    def addMsg(v: EMsg, kind: String = AstKinds.RECEIVED) = {
      lastMsg = Some(v);
      // withPrereq will cause the story messages to be ran in sequence
      lastMsgAst = if (!(justTests || justMocks)) Some(DomAst(v, kind).withPrereq({
        if (inSequence) lastAst.map(_.id)
        else Nil
      })) else None // need to reset it
      lastAst = lastMsgAst.toList
      lastAst
    }

    def addMsgPas(v: EMsgPas) = {
      // withPrereq will cause the story messages to be ran in sequence
      lastMsgAst =
          if (!(justTests || justMocks))
            Some(DomAst(v, AstKinds.RECEIVED)
                .withPrereq({
                  if (inSequence) lastAst.map(_.id)
                  else Nil
                })) else None // need to reset it
      lastAst = lastMsgAst.toList
      lastAst
    }

    // if the root already had something, we'll continue sequentially
    // this is important for diesel.guardian.starts + diese.setEnv - otherwise tehy run after the tests
    lastAst = root.children.toList

    // to put the stores in seq
    var lastStory: Option[DomAst] = None

    // add a single story
    def addStory(story: DSpec) = {
      var savedInSequence = inSequence

      story.parsed

      val forceStory = true

      var storyAst = root

      // add a node to represent the story
      val xstoryAst = DomAst(StoryNode(story.specRef), AstKinds.STORY)
          .withPrereq(lastAst.map(_.id))

      xstoryAst.withCtx(
        // story node has a scope
        new ScopeECtx(Nil, root.getCtx, Some(xstoryAst))
      )

      // fiddle is made up
      if (stories.size > 1 || forceStory && story.specRef.key != "fiddle" || addFiddles) {
        root.appendAllNoEvents {
          lastAst = List(xstoryAst)
          storyAst = xstoryAst // comment out to remove the story scope nodes
          lastAst
        }
      }

      // stories run in seq
      lastStory.foreach(x => storyAst.withPrereq(List(x.id)))
      lastStory = Some(storyAst)

      // markup all constructs - make sure there are no unparsed elements:
      if (
        !hasFiddleStories(story) &&
            !story.tags.contains(FIDDLE) &&
            story.contentPreProcessed.contains("$expect")) {

        // optimized to only do this expensive part for test stories, not regular messages
        // expect for fiddles (line numbers are very off)
        val domList = story.collector.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].reverse

        def findElemLine(row: Int) = {
          domList.exists { x =>
            x.isInstanceOf[HasPosition] && {
              val h = x.asInstanceOf[HasPosition]
              h.pos.isDefined && h.pos.get.line == row
            }
          }
        }

        // identify unparsed expects and add them as errors

        // we could do all, but don't care much about other elements, just the tests...
//        root appendAllNoEvents story.contentPreProcessed.lines.map(_.trim).zipWithIndex.filter(
        storyAst appendAllNoEvents story.contentPreProcessed.lines.map(_.trim).zipWithIndex.filter(
          _._1.startsWith("$expect")).collect {

          case (line, row) if !findElemLine(row + 1) =>
            DomAst(
              EError(s"Unparsed line #$row: " + line, story.specRef.wpath)
                .withPos(Some(EPos(story.specRef.wpath, row+1, 0))),
              AstKinds.ERROR)
                .withStatus(DomState.SKIPPED)
        }.toList
      }

      // add the actual elements
      storyAst appendAllNoEvents RDomain.domFilter(story) {
        case o: O if o.name != "context" => List(DomAst(o, AstKinds.RECEIVED))

        case v: EMsg if v.entity == "ctx" && v.met == "storySync" => {
          inSequence = true
          Nil
        }

        case v: EMsg if v.entity == "ctx" && v.met == "storyAsync" => {
          inSequence = false
          Nil
        }

        case v: EMsg => addMsg(v)

        case v: EMsgPas => addMsgPas(v)

        case v: EVal => {
          // story vals are also in sequence... because they use values in context
          lastAst = List(DomAst(v, AstKinds.RECEIVED).withPrereq(lastAst.map(_.id)))
          lastAst
        }

        case e: ExpectM if (!justMocks) => {
          // todo withGuard is connected in pexpect - is it really needed again?/
          lastAst = List(DomAst(
            e
                .withGuard(lastMsg.map(_.asMatch))
                .withTarget(lastMsgAst), AstKinds.TEST).withPrereq(lastAst.map(_.id)
          ))
          lastAst
        }

        case e: ExpectV if (!justMocks) => {
          // todo withGuard is connected in pexpect - is it really needed again?/
          lastAst = List(DomAst(
            e
                .withGuard(lastMsg.map(_.asMatch))
                .withTarget(lastMsgAst), AstKinds.TEST).withPrereq(lastAst.map(_.id)
          ))
          lastAst
        }

        case e: ExpectAssert if (!justMocks) => {
          // todo withGuard is connected in pexpect - is it really needed again?/
          lastAst = List(DomAst(
            e
              .withGuard(lastMsg.map(_.asMatch))
              .withTarget(lastMsgAst), AstKinds.TEST).withPrereq(lastAst.map(_.id)
          ))
          lastAst
        }

        case e: EInfo if (e.msg.startsWith("##")) => {
          addMsg(EMsg("diesel", "heading", List(P("msg", e.msg), P("background", "").withValue(""))))
        }

        // these don't wait - they don't run, they are collected together
        // todo this is a bit inconsistent - if one declares vals and then a mock then a val
//        case v: ERule => List(DomAst(v, AstKinds.RULE))
//        case v: EMock => List(DomAst(v, AstKinds.RULE))
      }.flatten

      // add the actual elements
      root appendAllNoEvents RDomain.domFilter(story) {

        // these don't wait - they don't run, they are collected together
        // todo this is a bit inconsistent - if one declares vals and then a mock then a val
        case v: ERule => List(DomAst(v, AstKinds.RULE))

        case v: EMock => List(DomAst(v, AstKinds.RULE))
      }.flatten

      // clean successful stories
      storyAst.childrenCol appendAll addMsg(EMsg(DieselMsg.ENGINE.DIESEL_CLEANSTORY), AstKinds.TRACE)

      inSequence = savedInSequence
    }

    // sort by tag "orderXXX" and alphabetical?
    var sortedStories = stories.sortBy(s =>
      s.tags.find(_.startsWith("testOrder")).getOrElse("testOrderLast") + s.specRef.key
    )

    // make sure guardian is first and last
    sortedStories =
      sortedStories.filter(_.specRef.wpath.contains(DieselMsg.GUARDIAN.STARTS_STORY)) :::
          sortedStories.filter(! _.specRef.wpath.contains(DieselMsg.GUARDIAN.STARTS_STORY))
    sortedStories =
      sortedStories.filter(! _.specRef.wpath.contains(DieselMsg.GUARDIAN.ENDS_STORY)) :::
      sortedStories.filter(_.specRef.wpath.contains(DieselMsg.GUARDIAN.ENDS_STORY))

    sortedStories.foreach(addStory)
  }

  /**
    * a simple strToDom parser
    */
  def strToDom[T] (str: String)(p:PartialFunction[Any,T]) : List[T] = {
    val story = new WikiEntry("Story", "temp", "temp", "md", str, new ObjectId(), Seq("dslObject"), "")

    story.parsed

    RDomain.domFilter(story) (p)
  }

  def msgFromStr (realm:String, s:String) = {
    strToDom (s) {
      case m : EMsg => m
    }
  }
}
