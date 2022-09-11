/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.samples

import org.bson.types.ObjectId
import razie.Log.log
import razie.audit.Audit
import razie.{clog, ctrace}
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.RDExt.DieselJsonFactory
import razie.diesel.engine._
import razie.diesel.engine.nodes.{EInfo, EMsg, EVal, EnginePrep}
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.tconf.DSpec
import razie.wiki.model.{WID, WikiEntry, Wikis}
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/** utils and examples of using the engine */
object DomEngineUtils {

  // todo maybe not have this sync option at all?
  def execAndWait (engine:DomEngine, timeoutSec:Int = 50) = {
    val future = engine.process

    Await.result(future, Duration.create(timeoutSec, "seconds"))
  }

  // todo maybe not have this sync option at all?
  def execTestsAndWait (engine:DomEngine, timeoutSec:Int = 50) = {
    val future = engine.processTests

    Await.result(future, Duration.create(timeoutSec, "seconds"))
  }

  // todo reuse DomFiddle.runDom
  def mkEngine(settings: DomEngineSettings, specs : List[DSpec], stories:List[DSpec]) : DomEngine = {
    // 2. the current domain (classes, entities, message specs etc)
    val dom = RDomain.domFrom(specs.head, specs.tail)

    // 2. create the process instance / root node
    val root = DomAst("root", AstKinds.ROOT)

    val engine = DieselAppContext.mkEngine(dom, root, settings, stories ::: specs, "simpleFlow")

    // 3. add the entry points / triggers to the process
    EnginePrep.addStoriesToAst(engine, stories)

    engine
  }

  final val NOUSER = new ObjectId()

  /** execute message - this is the typical use of an engine
    * execute message to given reactor
    *
    * @param msg      "entity.action(p=value,etc)
    * @param specs    the specs to use for rules
    * @param stories  any other stories to add (tests, engine settings etc)
    * @param settings engine settings
    * @return
    *
    * this is only used from the CQRS, internally - notice no request
    */
  def runDom(msg: String, specs: List[WID], stories: List[WID], settings: DomEngineSettings,
             omsg: Option[DieselMsgString] = None): Future[Map[String, Any]] = {
    // start processing all elements
    val engine = createEngine(msg, specs, stories, settings, omsg)

    engine.process.map { engine =>
      extractResult(msg, omsg, engine)
    }
  }


  /** execute message - this is the typical use of an engine
    * execute message to given reactor
    *
    * @param msg      "entity.action(p=value,etc)
    * @param specs    the specs to use for rules
    * @param stories  any other stories to add (tests, engine settings etc)
    * @param settings engine settings
    * @return
    *
    * this is only used from the CQRS, internally - notice no request
    */
  def createEngine(msg: String, specs: List[WID], stories: List[WID], settings: DomEngineSettings,
                   omsg: Option[DieselMsgString] = None, preppedCache:Option[CachedEngingPrep] = None): DomEngine = {

    // starting
    val t1 = System.currentTimeMillis()

    val realm = settings.realm getOrElse specs.headOption.map(_.getRealm).mkString
    val page = new WikiEntry("Spec", "fiddle", "fiddle", "md", "", NOUSER, Seq("dslObject"), realm)

    // don't audit diesel messages - too many
    if (
      msg.startsWith(DieselMsg.REALM.REALM_LOADED_MSG) ||
          msg.startsWith(DieselMsg.GUARDIAN_RUN) ||
          msg.startsWith(DieselMsg.GUARDIAN_POLL) ||
          msg.startsWith(DieselMsg.WIKI_UPDATED) ||
          msg.startsWith(DieselMsg.CRON_TICK) ||
          false
    ) {
      // clog?
    }
    else
      DieselMsg.logdb("DIESEL_FIDDLE_RUNDOM ", msg)

    var t2 = System.currentTimeMillis()
    var t3 = System.currentTimeMillis()

    val prepped = preppedCache.getOrElse({

      val pages = (specs ::: stories)
          .filter(_.section.isEmpty)
          .distinct
          .flatMap(w => Wikis.cachedPage(w, None))

      t2 = System.currentTimeMillis()

      // to domain
      val dom = WikiDomain.domFrom(page, pages)

      t3 = System.currentTimeMillis()

      // make up a story
      val FILTER = Array("sketchMode", "mockMode", "blenderMode", "draftMode")
      var story = if (msg.trim.startsWith("$msg") || msg.trim.startsWith("$send")) msg else "$msg " + msg
      ctrace << "STORY: " + story

      // todo this has no EPos - I'm loosing the epos on sections
      // put together all sections
      val story2 = (specs ::: stories).filter(_.section.isDefined).flatMap(_.content).mkString("\n")
      story = story + "\n" + story2.lines.filterNot(x =>
        x.trim.startsWith("$msg") || x.trim.startsWith("$send")
      ).mkString("\n") + "\n"

      val ipage = new WikiEntry("Story", "fiddle", "fiddle", "md", story, NOUSER, Seq("dslObject"), realm)
      val idom = WikiDomain.domFrom(ipage).get.revise addRoot

      var res = ""

      CachedEngingPrep(
        dom plus idom,
        ipage :: pages map WikiDomain.spec,
        List(ipage))
    })

    val root = DomAst("root", "root")

    // replace first line if ctx.set, better desc
    val desc = if (msg.startsWith("$msg ctx.set")) msg.replaceFirst("""^\$msg ctx.set.*""", "") else msg

    val t4 = System.currentTimeMillis()

    // start processing all elements
    val engine = DieselAppContext.mkEngine(
      prepped.dom,
      root,
      settings,
      prepped.ipages,
      DieselMsg.runDom + omsg.map(_.toString).getOrElse(desc).replaceAllLiterally("\n", ""))

    engine.root.prependAllNoEvents(List(
      DomAst(
        EInfo(s"Eng prep (crEng) total=${t4-t1} ", s"total=${t4-t1} getTopics=${t2-t1} domFromTopics=${t3-t2} msgCompile=${t4-t3}"),
        AstKinds.VERBOSE)
          .withStatus(DomState.SKIPPED)
    ))

    EnginePrep.addStoriesToAst(engine, prepped.storiesToAdd)

    engine
  }

  /** extract result from engine
    */
  def extractResult(msg: String, omsg: Option[DieselMsgString], engine: DomEngine): Map[String, Any] = {
    val errors = new ListBuffer[String]()

    // find the spec and check its result
    // then find the resulting value.. if not, then json
    val oattrs = engine.dom.moreElements.collect {
      //      case n:EMsg if n.entity == e && n.met == a => n
      case n: EMsg if msg.startsWith(n.entity + "." + n.met) => n
    }.headOption.toList.flatMap(_.ret)

    if (oattrs.isEmpty) {
      errors append s"Can't find the spec for $msg"
    }

    // collect values
    val values = engine.root.collect {
      case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _) if oattrs.isEmpty || oattrs.find(
        _.name == p.name).isDefined => (p.name, p.currentStringValue)
    }

    val resValue = engine.extractFinalValue(omsg.flatMap(_.omsg).map(_.ea).mkString).map(_.currentStringValue).mkString

    var m = Map(
      "payload" -> resValue,
      "resValue" -> resValue,
      "value" -> engine.resultingValue,
      "values" -> values.toMap,
      "totalCount" -> (engine.totalTestCount),
      "failureCount" -> engine.failedTestCount,
      "errors" -> errors.toList,
      "root" -> engine.root,
      "engineId" -> engine.id,
      DieselJsonFactory.dieselTrace -> DieselTrace(engine.root, engine.settings.node, engine.id, "diesel", "runDom",
        engine.settings.parentNodeId).toJson
    )
    m
  }

  /**
    * extract just the payload
    */
  def extractP(res: Map[String,Any]): Option[P] = {
    res.get(Diesel.PAYLOAD)
        .map(p =>
          if (p.isInstanceOf[P]) p.asInstanceOf[P]
          else P.fromSmartTypedValue(Diesel.PAYLOAD, p.toString)
//          else P.fromSmartTypedValue(Diesel.PAYLOAD, new RuntimeException(p.toString))
        )
  }

  /**
    * run message sync
    */
  def runMsgSync(msg: DieselMsg, timeoutSec:Int): Option[P] = {
    val fut = msg.toMsgString.startMsg

    // get timeout max from realm settings: paid gets more etc
    try {
      val res = Await.result(fut, Duration.create(timeoutSec, "seconds"))
      extractP(res._2)
    } catch {
      case t:Throwable => {
        log("Await failed ", t)
        None
      }
    }
  }

  /**
    * run message sync
    */
  def runMsgSync(realm: String, m: EMsg, timeoutSec:Int): Option[P] = {
    runMsgSync(new DieselMsg(m, DieselTarget.ENV(realm)), timeoutSec)
  }

  /**
    * run message sync
    */
  def runMsgSync(realm: String, e: String, a: String, parms: Map[String, Any], timeoutSec:Int): Option[P] = {
    runMsgSync(DieselMsg(e, a, parms, DieselTarget.ENV(realm)), timeoutSec)
  }

  /**
    * run message async
    *
    * @param realm - the realm to run in
    * @param m     - a text message invocation i.e. "$msg a.b(c=3)"
    */
  def runMsgAsync(realm: String, m: String): Future[Map[String, Any]] = {
    DieselMsgString(m, DieselTarget.ENV(realm)).startMsg.map(_._2)
  }

}

