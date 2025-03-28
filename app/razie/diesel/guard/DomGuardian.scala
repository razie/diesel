/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.guard

import akka.actor.{Actor, Props}
import api.dwix
import controllers.RazRequest
import DieselDebug.Guardian
import DieselDebug.Guardian.{ISAUTO, ISENABLED, ISENABLED_LOCALHOST, ISSCHED}
import model.{User, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.Logging
import razie.diesel.Diesel
import razie.diesel.cron.{CronCreateMsg, DieselCron}
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.nodes.{EInfo, EVal, EnginePrep}
import razie.diesel.engine.{DieselAppContext, DomEngine, DomEngineSettings}
import razie.diesel.expr.SimpleECtx
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.diesel.utils.{DieselData, DomCollector}
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.model._
import razie.wiki.util.PlayTools
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import services.DieselCluster


/** this is the default engine per reactor and user, continuously running all the stories */
object DomGuardian extends Logging {

  // (realm, label, tag query, "")
  val tagList = new ListBuffer[(String, String, String, String)]()

  // (realm, tag) - tags to skip, will add to list of ignore when running sanity etc
  val skipTagList = new ListBuffer[(String, String)]()

  // todo optimize so we don't parse every time
  def autoRealms =
    Services.config.weprop("diesel.guardian.auto.realms.regex", "wiki|specs|devnetlinq")

  def enabledRealms = Services.config.prop("diesel.guardian.enabled.realms.regex", ".*")

  /** excluding these */
  def excludedRealms =
    Services.config.weprop("diesel.guardian.excluded.realms.regex", "nobody")

  /** excluding these */
  def excludedAutoRealms =
    Services.config.weprop("diesel.guardian.excluded.auto.realms.regex", "nobody")

  /** is it enabled for this realm
    * if true,
    */
  def enabled(realm: String) = (!Services.config.isLocalhost && ISENABLED || ISENABLED_LOCALHOST) && {
    realm match {
      case "specs" | "wiki" => true // always these two
      case _ => realm.matches(enabledRealms) && !realm.matches(excludedRealms)
    }
  }

  /** */
  def onSched(realm: String) = ISAUTO && {
    realm match {
      case "specs" | "wiki" => true // always these two
      case _ =>
        ISSCHED && realm.matches(autoRealms) && !realm.matches(
          excludedAutoRealms
        )
    }
  }

  /** */
  def onAuto(realm: String) = ISAUTO && {
    realm match {
      case "specs" | "wiki" => true // always these two
      case _ =>
        ISAUTO && realm.matches(autoRealms) && !realm.matches(
          excludedAutoRealms
        )
    }
  }

  def setHostname(ctx: SimpleECtx)(implicit stok: RazRequest): Unit = {
    ctx._hostname = Some(
      // on localhost, it shouldn't go out
      if (Services.config.isLocalhost) "localhost:9000"
      else PlayTools.getHost(stok.req).mkString
    )
  }

  /** start a check run. the first time, it will init the guardian and listeners */
  def startCheck(realm: String, au: Option[User], tq: String, osettings: Option[DomEngineSettings] = None,  q:Map[String,String] = Map.empty, oneStory:Option[String] = None ):
  (Future[Report], Option[DomEngine], List[Any]) = {
    if (!DomGuardian.init) {

      // first time, init the guardian

      if (enabled(realm) && onAuto(realm)) {
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

                // todo optimize to run just the one story if only one changed and replace it in reports
                // that may require story dependencies etc

                if (enabled(realm) && onAuto(realm))
                  DomGuardian.lastRuns
                      .filter(_._2.realm == we.realm)
                      .headOption
                      .map { t =>
                        startCheck(
                          t._2.realm,
                          Users.findUserByUsername(t._2.userName),
                          Guardian.autoQuery(realm)
                        )
                      }
              }
            }
          }
        }
      }
    }

    if (!enabled(realm)) {
      throw new IllegalStateException("GUARDIAN IS DISABLED")
    }

    clog << s"DIESEL startCheck ${realm} for ${au.map(_.userName)}"

    // these are debounced in there...
    DomGuardian.runReq(au, realm, "", tq, oneStory, q, auto = false, osettings)
  }

  case class Report(req: Option[RunReq],
                    userName: String,
                    engine: DomEngine,
                    realm: String,
                    env: String,
                    failed: Int,
                    total: Int,
                    duration: Long,
                    when: DateTime = DateTime.now()) {
    def key = realm + "." + env + "." + userName

    override def toString = super.toString
  }

  final val EMPTY_REPORT =
    Report(None, "DISABLED", null, "DISABLED", "?", 0, 0, 0, DateTime.now())

  final val NOT_ACTIVE_REPORT =
    Report(None, "I'm not the active Guardian", null, "not active", "?", 0, 0, 0, DateTime.now())

  case class RunReq(au: Option[WikiUser],
                    userName: String,
                    realm: String,
                    ienv: String,
                    auto: Boolean = false, // autos will send emails
                    tq: Option[String] = None,
                    oneStory : Option[String] = None,
                    osettings: Option[DomEngineSettings] = None,
                    qparms:Map[String, String] = Map.empty,
                    when: DateTime = DateTime.now()
                   ) {

    def env = if (ienv.nonEmpty) ienv else dwix.dieselEnvFor(realm, au)

    def key = realm + "." + env + "." + userName

    // actually run a reactor test
    def run: (Future[Report], Option[DomEngine]) = DomGuardian.synchronized {
      val started = System.currentTimeMillis()

      log(s"RunReq.run() start $realm")

      val settings = mkSettings(osettings)
      settings.tagQuery = tq
      settings.realm = Some(realm)

      if (Services.config.isLocalhost)
        settings.dieselHost = Some("http://" + Services.config.hostport)
      else
        settings.dieselHost = Website.forRealm(realm).map(_.url)

      val addFiddles = Website
          .forRealm(realm)
          .flatMap(_.bprop("guardian.settings.fiddles"))
          .getOrElse(false)

      // override for this flow
      settings.env = Some(env)

      val stories = EnginePrep.loadStories(settings, realm, au.map(_._id), oneStory.mkString)

      val starts = new WikiEntry("Story", DieselMsg.GUARDIAN.STARTS_STORY, DieselMsg.GUARDIAN.STARTS_STORY, "md",
        s"""
           |$$send diesel.guardian.starts(realm="$realm", env="$env")
           |$$send diesel.setEnv(env="$env", user="")
 """.stripMargin,
        new ObjectId(), Seq("dslObject"), realm)

      // a reactor without tests... skip it
      if (stories.size == 0) {
        return (Future.successful(Report(Some(this), "?", null, "?", "?", 0, 0, 0)), None)
      }

      val ends = new WikiEntry("Story", DieselMsg.GUARDIAN.ENDS_STORY, DieselMsg.GUARDIAN.ENDS_STORY, "md",
        s"""
           |$$send diesel.guardian.ends(realm="$realm", env="$env")
           |$$send ctx.echo(msg="Done... realm=$realm, env=$env")
 """.stripMargin,
        new ObjectId(), Seq("dslObject"), realm)

      // run all stories not just the tests
      val engine = EnginePrep.prepEngine(
        new ObjectId().toString,
        settings,
        realm,
        None,
        false,
        au,
        s"Guardian:${realm}-${env}-${au.map(_.userName).getOrElse("auto")}:" + tq.orElse(oneStory).mkString,
        None,
        starts :: (stories ::: (ends :: Nil)),
        None,
        addFiddles
      )

      // preset parameters sent in:
      qparms.foreach(t=>engine.ctx.put (P.fromSmartTypedValue(t._1, t._2)))

      DomCollector.collectAst("guardian", realm, engine)

      import scala.concurrent.ExecutionContext.Implicits.global

      // decompose all nodes, not just the tests
      val fut = engine.process.map { engine =>
        val failed = engine.failedTestCount
        val success = engine.successTestCount

        val r = Report(
          Some(this),
          au.get.userName,
          engine,
          realm,
          env,
          failed,
          failed + success,
          System.currentTimeMillis - started
        )
        worker ! r
        r
      }

      curRun = Some((engine.id, engine, fut))

      (fut, Some(engine))
    }
  }

  // used for each run
  var mkSettings: Option[DomEngineSettings] => DomEngineSettings = { settings: Option[DomEngineSettings] =>
    settings.map { s =>
      s.draftMode = false
      s
    }.getOrElse {
      new DomEngineSettings(
        mockMode = true,
        blenderMode = true,
        draftMode = false,
        sketchMode = false,
        execMode = "sync"
      )
    }
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

  private var curRun: Option[(String, DomEngine, Future[DomGuardian.Report])] =
    None

  /** just run, no checks */
  def runReqUnsafe(au: Option[WikiUser], realm: String, env: String, tq: Option[String] = None): (Future[Report],
      Option[DomEngine]) = {
    DomGuardian.synchronized {
      val rr = RunReq(au, au.map(_.userName).mkString, realm, env, false, tq)
      val k = rr.key
      debug(s"Guardian created a RunReq $k")

      debug(
        s"GuardianActor.RunReq ${rr.realm}.${rr.userName} - append to debouncer"
      )

      val x@(fut, engine) = rr.run
//      debouncer.append((k, rr, fut, engine))
      x
    }
  }

  /** if no test is currently running, start one */
  def runReq(
    au: Option[WikiUser],
    realm: String,
    env: String,
    tq: String,
    oneStory: Option[String] = None,
    qparms: Map[String,String] = Map.empty,
    auto: Boolean = false,
    settings: Option[DomEngineSettings]): (Future[Report], Option[DomEngine], List[Any]) = {
    val res = new ListBuffer[Any]()

    if (! Services.cluster.isSingletonNode (Website.forRealm(realm)))
      throw new IllegalStateException("I'm not the active Guardian")

    DomGuardian.synchronized {
      val q = if (tq.isEmpty) None else Some(tq)
      //        Some(tq.getOrElse(Guardian.autoQuery(realm)))

      val rr = RunReq(au, au.map(_.userName).mkString, realm, env, auto, q, oneStory, osettings = settings, qparms)
      val k = rr.key
      res append EInfo(s"Guardian created a RunReq $k")

      res append EInfo("Guardian - debouncer before: " + debugDebouncer)

      val ret = debouncer.find(_._1 == k).filter(!_._3.isCompleted).map { rr =>
        res append EInfo(s"Guardian.RunReq ${rr._1} - reused in progress ")
        (rr._3, rr._4) // one in progress, return its Future
      } getOrElse {
        res append EInfo(
          s"Guardian.RunReq $k - append to debouncer"
        )
        // maybe clean
        debouncer = debouncer.filter(_._1 != k)

        // run it
        val x@(fut, engine) = rr.run

        res append EInfo(
          s"Guardian started runreq $k - engine:${engine.map(_.id)}"
        )

        debouncer.append((k, rr, fut, engine))
        x
      }

      res append EInfo("Guardian - debouncer after: " + debugDebouncer)
      (ret._1, ret._2, res.toList)
    }
  }

  /** debug debouncer */
  def debugDebouncer = debouncer
      .map(x => x._1 + ":done=" + x._3.isCompleted)
      .mkString(" , ")

  lazy val worker =
    DieselAppContext.actorOf(Props[GuardianActor], name = "GuardianActor")

  /** [(key, req, F, engine)] */
  private var debouncer =
    new mutable.ListBuffer[(String, RunReq, Future[Report], Option[DomEngine])]()

  /** actually does the work */
  class GuardianActor extends Actor {

    def receive = {
//      case rr: RunReq=> DomGuardian.synchronized {
//        debug("GuardianActor received a RunReq")
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

      case r: Report =>
        DomGuardian.synchronized {
          debug(
            s"GuardianActor received a Report ${r.realm}.${r.userName} engine: ${r.engine.id}"
          )
          lastRun.put(r.realm + "." + r.userName, r)

          val k = r.key
          debouncer = debouncer.filter(_._1 != k)
          debug(
            "GuardianActor - debouncer after report: " + debouncer
                .map(x => x._1 + ":done=" + x._3.isCompleted)
                .mkString(" , ")
          )
        }
    }
  }

  final val GUARDIAN_POLL = "GuardianPoll"

  /** if the poll is different, run tests */
  def polled(realm: String, env: String, itstamp: String, au: Option[User], tquery: String) = {
    val tstamp = itstamp.take(100)
    val old =
      DieselData
          .find(GUARDIAN_POLL, realm, realm + "-" + env)

    val oldTstamp = old
        .flatMap(t => t.contents.get("value"))
        .getOrElse("initialValueNothingLikeThisEh")

    val oldStatus = old
        .flatMap(t => t.contents.get("status"))
        .getOrElse("")

    val x = if (oldTstamp != tstamp || oldStatus.isEmpty && !tstamp.isEmpty) {
      // if tstamp different or nothing found last time - meaning no run completed
      // save new stamp... unless new stamp is also empty
      DieselData.set(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("value" -> tstamp))
      // we don't set a state - so if the first run bombs, there will be another etc
      // todo what about poison pills if the first run always bombs?

      info(s"Guardian - starting a run $realm-$env - new $tstamp vs old $oldTstamp")

      val tq = if (tquery.trim.isEmpty) Map.empty else Map("tagQuery" -> tquery.trim)

      Services ! DieselMsg(
        DieselMsg.GUARDIAN.ENTITY,
        DieselMsg.GUARDIAN.RUN,
        Map("realm" -> realm, "env" -> env) ++ tq,
        DieselTarget.ENV(realm)
      )

      EInfo(s"Change detected - starting a run... ($tstamp)")
    } else {
      EInfo(s"No change ($tstamp)")
    }


    EInfo(s"Guardian - polled $realm-$env - new $tstamp vs old $oldTstamp oldStatus $oldStatus") ::
        x ::
        EVal(P(Diesel.PAYLOAD, x.msg)) ::
        Nil
  }

  /** create a guardian poller schedule */
  def createPollSchedule (schedExpr: String, realm: String, env: String, inLocal: String) = {
    if (
      "local" == env && !Services.config.isLocalhost ||
          "local" != env && Services.config.isLocalhost && "yes" != inLocal ||
//          "sandbox" != env || // testing
          false
    ) {
      val msg =
        s"""isLocalhost? Cannot create guardian schedule for env=$env for realm $realm - for isLocalhost add
           |inLocal="yes" """.stripMargin
      error(msg)
      msg
    } else {
      val name = s"guardian.auto-$realm-$env"
      DieselCron.createSchedule(
        CronCreateMsg (
        s"guardian.auto-$realm-$env", schedExpr, "", "", "", realm, env, "", false, -1,
        Left(DieselMsg(
          DieselMsg.GUARDIAN.ENTITY,
          DieselMsg.GUARDIAN.POLL,
          Map("realm" -> realm, "env" -> env),
          DieselTarget.ENV(realm, env)
        )),
        Left(DieselCron.defaultDoneMsg(name, realm, env)),
        tags = "guardian"
        ))
    }
  }

}
