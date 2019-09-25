package mod.diesel.guard

import java.util.concurrent.TimeUnit
import admin.Config
import akka.actor.{Actor, Cancellable, Props}
import api.dwix
import controllers.RazRequest
import java.net.InetAddress
import model.{User, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import razie.{Logging, Snakk}
import razie.diesel.dom.SimpleECtx
import razie.diesel.engine.{DomEngECtx, DomEngine, DomEngineSettings}
import razie.diesel.ext.EnginePrep
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.diesel.utils.{DieselData, DomCollector}
import razie.hosting.Website
import razie.tconf.SpecPath
import razie.wiki.Services
import razie.wiki.model._
import razie.wiki.util.PlayTools
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

/** this is the default engine per reactor and user, continuously running all the stories */
object DomGuardian extends Logging {

  // todo optimize so we don't parse every time
  def autoRealms =
    Config.prop("diesel.guardian.auto.realms.regex", "wiki|specs")

  def enabledRealms = Config.prop("diesel.guardian.enabled.realms.regex", ".*")

  def excludedRealms =
    Config.prop("diesel.guardian.excluded.realms.regex", "nobody")

  def excludedAutoRealms =
    Config.prop("diesel.guardian.excluded.auto.realms.regex", "wiki|specs")

  def ISAUTO = Config.prop("diesel.guardian.auto", "true").toBoolean

  def ISENABLED = Config.prop("diesel.guardian.enabled", "true").toBoolean

  def enabled(realm: String) = ISENABLED && {
    realm match {
      case "specs" | "wiki" => true // always these two
      case _ => realm.matches(enabledRealms) && !realm.matches(excludedRealms)
    }
  }

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
  def startCheck(realm: String, au: Option[User]): (Future[Report], DomEngine) = {
    if (!DomGuardian.init) {

      // first time, init the guardian

      if (enabled(realm) && onAuto(realm)) {
        // listen to topic changes and re-run
        WikiObservers mini {
          case ev@WikiEvent( action, "WikiEntry", _, entity, oldEntity, _, _ ) => {
            val wid = WID.fromPath(ev.id).get
            val oldWid = ev.oldId.flatMap(WID.fromPath)

            if (entity.isDefined && entity.get.isInstanceOf[WikiEntry]) {
              val we = entity.get.asInstanceOf[WikiEntry]
              if (we.category == "Story" || we.tags.contains("story") ||
                  we.category == "Spec" || we.tags.contains("spec")) {

                // re run all tests for current realm

                // todo also cancel existing workflows

                // todo optimize to rnu just the one story if only one changed and replace it in reports
                // that may require story dependencies etc

                if (enabled(realm) && onAuto(realm))
                  DomGuardian.lastRuns
                      .filter(_._2.realm == we.realm)
                      .headOption
                      .map { t =>
                        startCheck(t._2.realm, Users.findUserByUsername(t._2.userName))
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
    DomGuardian.runReq(au, realm, "")
  }

  case class Report(req:Option[RunReq],
                    userName: String,
                    engine: DomEngine,
                    realm: String,
                    env:String,
                    failed: Int,
                    total: Int,
                    duration: Long,
                    when: DateTime = DateTime.now()) {
    def key = realm + "." + env + "." + userName
    override def toString = super.toString
  }

  final val EMPTY_REPORT =
    Report(None, "DISABLED", null, "DISABLED", "?", 0, 0, 0, DateTime.now())

  case class RunReq(au: Option[User],
                    userName: String,
                    realm: String,
                    ienv: String,
                    auto:Boolean = false, // autos will send emails
                    when: DateTime = DateTime.now()) {

    def env = if (ienv.nonEmpty) ienv else dwix.dieselEnvFor(realm, au)

    def key = realm + "." + env + "." + userName

    def run: (Future[Report], DomEngine) = DomGuardian.synchronized {
      val started = System.currentTimeMillis()

      log(s"RunReq.run() start $realm")

      val settings = mkSettings()
      settings.tagQuery =
          Website.forRealm(realm).flatMap(_.prop("guardian.settings.query"))
      settings.realm = Some(realm)

      if (Config.isLocalhost)
        settings.hostport = Some(Config.hostport)
      else
        settings.hostport = Website.forRealm(realm).map(_.domain)

      val addFiddles = Website
          .forRealm(realm)
          .flatMap(_.bprop("guardian.settings.fiddles"))
          .getOrElse(false)

      val stories = EnginePrep.loadStories(settings, realm, au.map(_._id), "")
      val me = new WikiEntry("Story", "temp", "temp", "md",
        s"""
           |$$send diesel.guardian.starts(realm="$realm", env="$env")
           |$$send ctx.set(diesel.env="$env")
           |$$send diesel.setEnv(env="$env", user="")
 """.stripMargin,
        new ObjectId(), Seq("dslObject"), realm)

      // a reactor without tests... skip it
      if (stories.size == 0) {
        return (Future.successful(Report(Some(this), "?", null, "?", "?", 0, 0, 0)), null)
        // return a random report
//        if (lastRun.values.head != null)
//          return ("", Future.successful(lastRun.values.head))
//        else
//          throw new IllegalArgumentException("No runs yet... try again later")
      }

      val last = new WikiEntry("Story", "temp", "temp", "md",
        s"""
           |$$send diesel.guardian.ends(realm="$realm", env="$env")
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
        s"Guardian:${realm}-${env}-${au.map(_.userName).getOrElse("auto")}-",
        Some(me),
        stories,
        Some(last),
        addFiddles
      )

      DomCollector
          .collectAst("guardian", realm, engine.id, au.map(_.id), engine)

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

      (fut, engine)
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

  private var curRun: Option[(String, DomEngine, Future[DomGuardian.Report])] =
    None

  /** if no test is currently running, start one */
  def runReq(au: Option[User], realm: String, env: String, auto:Boolean = false): (Future[Report], DomEngine) =
    if(DieselCron.isMasterNode(Website.forRealm(realm).get)) {
      DomGuardian.synchronized {
        val rr = RunReq(au, au.map(_.userName).mkString, realm, env, auto)
        val k = rr.key
        debug(s"GuardianActor received a RunReq $k")

        debug(
          "GuardianActor - debouncer before: " + debouncer
              .map(x => x._1 + ":done=" + x._3.isCompleted)
              .mkString(" , ")
        )

        val ret = debouncer.find(_._1 == k).filter(!_._3.isCompleted).map { rr =>
          debug(s"GuardianActor.RunReq ${rr._1} - reused in progress ")
          (rr._3, rr._4) // one in progress, return its Future
        } getOrElse {
          debug(
            s"GuardianActor.RunReq ${rr.realm}.${rr.userName} - append to debouncer"
          )
          // maybe clean
          debouncer = debouncer.filter(_._1 != k)
          val x @ (fut, engine) = rr.run
          debouncer.append((k, rr, fut, engine))
          x
        }

        debug(
          "GuardianActor - debouncer after: " + debouncer
              .map(x => x._1 + ":done=" + x._3.isCompleted)
              .mkString(" , ")
        )
        ret
      }
    } else {
      (Future.failed(new IllegalStateException("I'm not the active guardian!")), null)
    }

  lazy val worker =
    Akka.system.actorOf(Props[GuardianActor], name = "GuardianActor")

  private var debouncer =
    new mutable.ListBuffer[(String, RunReq, Future[Report], DomEngine)]()

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
  def polled(realm: String, env: String, tstamp: String, au: Option[User], tquery:String) = {
    val old =
      DieselData
          .find(GUARDIAN_POLL, realm, realm + "-" + env)

    val oldTstamp = old
        .flatMap(t=> t.contents.get("value"))
        .getOrElse("initialValueNothingLikeThisEh")

    val oldStatus = old
          .flatMap(t=> t.contents.get("status"))
          .getOrElse("")

    info(s"Guardian - polled $realm-$env - new $tstamp vs old $oldTstamp oldStatus $oldStatus")

    // if tstamp different or nothing found last time - meaning no run completed
    if (oldTstamp != tstamp || oldStatus.isEmpty ) {
      // save new stamp
      DieselData.set(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("value" -> tstamp))
      // we don't set a state - so if the first run bombs, there will be another etc
      // todo what about poison pills if the first run always bombs?

      info(s"Guardian - starting a run $realm-$env - new $tstamp vs old $oldTstamp")

      Services ! DieselMsg(
        DieselMsg.GUARDIAN.ENTITY,
        "run",
        Map("realm" -> realm, "env" -> env),
        DieselTarget.ENV(realm)
      )

      s"Change detected - starting a run... ($tstamp)"
    } else {
      s"No change ($tstamp)"
    }
  }

  /** create a guardian poller schedule */
  def createPollSchedule(schedExpr:String, realm: String, env: String, inLocal:String) = {
    if (
      "local" == env && !Config.isLocalhost ||
          "local" != env && Config.isLocalhost && "yes" != inLocal ||
//          "sandbox" != env || // testing
          false
    ) {
      val msg = s"isLocalhost? Cannot create guardian schedule for env=$env for realm $realm "
      error(msg)
      msg
    } else {
      DieselCron.createSchedule(s"guardian.auto-$realm-$env", schedExpr, realm, env, -1,
        Left(DieselMsg(
          DieselMsg.GUARDIAN.ENTITY,
          DieselMsg.GUARDIAN.POLL,
          Map("realm" -> realm, "env" -> env),
          DieselTarget.ENV(realm, env)
        ))
      )
    }
  }

}

