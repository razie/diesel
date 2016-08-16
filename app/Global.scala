/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import java.util.Properties
import admin._
import akka.cluster.{MemberStatus, Cluster}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import controllers._
import mod.book.Progress
import razie.db._
import model._
import play.api.Application
import play.api._
import play.api.mvc._
import razie.wiki.util.{PlayTools, IgnoreErrors, VErrors}
import razie.{cout, Log, cdebug, clog}
import play.libs.Akka
import akka.actor.{RootActorPath, Props, Actor}
import com.mongodb.casbah.{MongoDB, MongoConnection}
import com.mongodb.casbah.Imports._
import java.io.File
import scala.concurrent.{Future, ExecutionContext}
import controllers.ViewService
import razie.wiki.model.WikiCount
import razie.wiki.admin._
import razie.wiki.{WikiConfig, Alligator, EncryptService, Services}
import razie.wiki.model.WikiAudit
import razie.wiki.model.WikiUsers
import razie.wiki.model.WikiUser
import razie.wiki.model.Reactors
import razie.wiki.model.WikiEntry
import razie.wiki.model.Reactor
import razie.wiki.Sec._

import scala.util.Try

/** customize some global handling errors */
object Global extends WithFilters(LoggingFilter) {
  // EMAIL BACKOFF stuff
  val ERR_DELTA1 = 5 * 60 * 1000 // 5 min
  val ERR_DELTA2 = 6 * 60 * 60 * 1000 // 6 hours
  val ERR_EMAILS = 5 // per DELTA2
  var errEmails = 0 // sent per DELTA2
  var lastErrorTime = System.currentTimeMillis - ERR_DELTA1 // time last error email went out - just one every 5 min, eh
  var firstErrorTime = System.currentTimeMillis - ERR_DELTA2 // time first error email went out - just one every 5 min, eh
  var lastErrorCount = 0 // time last error email went out - just one every 5 min, eh

  override def onError(request: RequestHeader, ex: Throwable) = {
    clog << "ERR_onError - trying to log/audit in DB... " + "request:" + request.toString + "headers:" + request.headers + "ex:" + ex.toString
    Audit.logdb("ERR_onError", "request:" + request.toString, "headers:" + request.headers, "ex:" + ex.toString)
    val m = ("ERR_onError", "Current count: " + lastErrorCount + " Request:" + request.toString, "headers:" + request.headers, "ex:" + ex.toString).toString
    if (System.currentTimeMillis - lastErrorTime >= ERR_DELTA1) {
      if (errEmails <= ERR_EMAILS || System.currentTimeMillis - firstErrorTime >= ERR_DELTA2) {
        SendEmail.withSession { implicit mailSession =>
          Emailer.tellRaz("ERR_onError",
            Services.auth.authUser (Request(request, "")).map(_.userName).mkString, m)

          synchronized {
            if (errEmails == ERR_EMAILS || System.currentTimeMillis - firstErrorTime >= ERR_DELTA2) {
              errEmails = 0
              firstErrorTime = lastErrorTime
            }
            errEmails = errEmails + 1
            lastErrorTime = System.currentTimeMillis()
            lastErrorCount = 0
          }
        }
      } else {
        lastErrorCount = 0
      }
    } else {
      lastErrorCount = 0
    }

    super.onError(request, ex)
  }

  override def onHandlerNotFound(request: RequestHeader)= {
    clog << "ERR_onHandlerNotFound " + "request:" + request.toString + "headers:" + request.headers
    Audit.logdb("ERR_onHandlerNotFound", "request:" + request.toString, "headers:" + request.headers)
    super.onHandlerNotFound(request)
  }

  override def onBadRequest(request: RequestHeader, error: String)= {
    clog << ("ERR_onBadRequest " + "request:" + request.toString + "headers:" + request.headers + "error:" + error)
    Audit.logdb("ERR_onBadRequest", "request:" + request.toString, "headers:" + request.headers, "error:" + error)
    super.onBadRequest(request, error)
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    if (! request.path.startsWith( "/assets/") && !request.path.startsWith( "/razadmin/ping/shouldReload"))
      cdebug << ("ROUTE_REQ.START: " + request.toString)

    /** get the host that was forwarded here - used for multi-site hosting */

    val host = PlayTools.getHost(request).orElse(Some(Config.hostport))
    val redirected = host.flatMap(x => Config.urlrewrite(x + request.path))

    val res = redirected.map { x =>
      clog << ("URL - REDIRECTING? - " + host.mkString+request.path)
      clog << ("URL -   TO         - " + redirected)
      EssentialAction {rh=>
      Action { rh:play.api.mvc.RequestHeader =>
        Results.Redirect(x)
        }.apply(rh)
      }
    } orElse {
      super.onRouteRequest(request)
    }

    if (! request.path.startsWith( "/assets/") && !request.path.startsWith( "/razadmin/ping/shouldReload"))
      cdebug << ("ROUTE_REQ.STOP: " + request.toString)

    res
  }

  override def onStart(app: Application) = {
    // automated restart / patch / update handling
    Try { new File("../updating").delete() }.isSuccess
    super.onStart(app)

    Services ! new RazAlligator.InitAlligator

    // todo  SendEmail.initialize
  }

  override def beforeStart(app: Application) {
    // register the later actor
    //    val auditor = Akka.system.actorOf(Props[model.WikiAuditor], name = "WikiAuditor")

//    WikiConfig.RK = "rk"

    Services.auth = new RazAuthService ()
    Services.config = Config

    /************** MONGO INIT *************/
    RazMongo.setInstance {
      val UPGRADE_AGAIN = false // only the last upgrade
      val mongoUpgrades: Map[Int, UpgradeDb] = Map(
          1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5,
          6 -> U6, 7 -> U7, 8 -> U8, 9 -> U9, 10 -> U10, 11 -> U11, 12 -> U12, 13 -> U13,
          14 -> U14, 15 -> U15, 16 -> U16, 17 -> U17) /* NOTE as soon as you list it here, it will apply */

      def mongoDbVer = mongoUpgrades.keySet.max + 1

      lazy val conn = MongoConnection(admin.Config.mongohost)

      /** the actual database - done this way to run upgrades before other code uses it */
      com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
      com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

      // authenticate
      val db = conn(Config.mongodb)
      if (!db.authenticate(Config.mongouser, admin.Config.mongopass)) {
        clog << "ERR_MONGO_AUTHD"
        throw new Exception("Cannot authenticate. Login failed.")
      }

      //upgrading db version if needed
      def prep(adb:MongoDB) = {
        // upgrade if needed
        var dbVer = adb("Ver").findOne.map(_.get("ver").toString).map(_.toInt)
        if (UPGRADE_AGAIN) dbVer = dbVer.map(x => mongoDbVer - 1)

        var upgradingLoop = false // simple recursive protection

        // if i don't catch - there's no ending since it's a lazy val init...
        try {
          dbVer match {
            case Some(v) => {
              var ver = v
              while (ver < mongoDbVer && mongoUpgrades.contains(ver)) {
                if(upgradingLoop)
                  throw new IllegalStateException("already looping to update - recursive DB usage while upgrading, check code")
                upgradingLoop = true
                mongoUpgrades.get(ver).fold (
                  Log.error("NO UPGRADES FROM VER " + ver)
                ) { u =>
                  cout << "1 " + Thread.currentThread().getName()
                  Log audit s"UPGRADING DB from ver $ver to ${mongoDbVer}"
                  Thread.sleep(2000) // often screw up and goes in  a loop...
                  u.upgrade(adb)
                  adb("Ver").update(Map("ver" -> ver), Map("ver" -> mongoDbVer))
                  Log.audit("UPGRADING DB... DONE")
                }
                ver = ver + 1
                upgradingLoop = false
              }
            }
            case None => adb("Ver") += Map("ver" -> mongoDbVer) // create a first ver entry
          }
        } catch {
          case e: Throwable => {
            Log.error("Exception during DB migration - darn thing won't work at all probably\n" + e, e)
            e.printStackTrace()
          }
        }

        // that's it, db initialized?
        adb
      }

      prep(db)
    }

    RMongo.setInstance(RazAuditService)

    Services.mkReactor = { (realm, fallBacks, we)=>
      realm match {
        case Reactors.RK | Reactors.NOTES | Reactors.WIKI => new RkReactor(realm, fallBacks, we)
        case _ => new RkReactor(realm, fallBacks, we)
      }
    }

    SendEmail.mkSession = (debug: Boolean) => {
      val props = new Properties();
      props.put("mail.smtp.auth", "true");
      props.put("mail.smtp.starttls.enable", "true");
      props.put("mail.smtp.host", "smtp.gmail.com");
      props.put("mail.smtp.port", "587");

      import razie.wiki.Sec._
      val session = javax.mail.Session.getInstance(props, new SMTPAuthenticator(Config.SUPPORT, "zlMMCe7HLnMYOvbjYpPp6w==".dec))

      session.setDebug(debug);
      session
    }

    WikiUsers.impl = WikiUsersImpl

    EncryptService.impl = admin.CypherEncryptService
    ViewService.impl = RkViewService
    Services.wikiAuth = RazWikiAuthorization

    //todo look these up in Website
    Services.isSiteTrusted = {s=>
        Config.trustedSites.exists(x=>s.startsWith(x))
    }

    WikiScripster.impl = new RazWikiScripster
    Services.runScriptImpl = (s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean) => {
      WikiScripster.impl.runScript(s, lang, page, user, query, devMode)
    }

    //    U11.upgradeWL(RazMongo.db)
    //    U11.upgradeRaz(RazMongo.db)
    //    U11.upgradeRk(RazMongo.db)
    //    U11.upgradeGlacierForums(RazMongo.db)
    //        U11.upgradeGlacierForums2()

    Services.audit = RazAuditService
    Services.initAlli(RazAlligator)

    WikiObservers mini {
      case WikiEvent(_, "WikiEntry", _, Some(x), _, _) => {
        val we = x.asInstanceOf[WikiEntry]
        if("Site" == we.category) Website.clean(we.name)

        if("Reactor" == we.category) {
          Reactors.reload(we.name);
          Website.clean (we.name+".wikireactor.com")
          new Website(we).prop("domain").map (Website.clean)
        }
      }
    }

    WikiObservers mini {
      case WikiEvent("AUTH_CLEAN", "User", id, _, _, _) => {
        Services.auth.cleanAuth2(Users.findUserById(new ObjectId(id)).get)
      }
    }

    WikiRefinery.init()

    Progress.init
  }

  /** my dispatcher implementation */
  object RazAlligator extends Alligator {
    lazy val auditor = Akka.system.actorOf(Props[WikiAuditor], name = "Alligator")
//    lazy val clusterBrute = Akka.system.actorOf(Props[ClusterBrute], name = "ClusterBrute")

    class InitAlligator

    def !(a: Any) {
//      this receive a
      // TODO enable async audits
      auditor ! a
    }

    def !?(a: Any) {
      this receive a
    }

    def receive: PartialFunction[Any, Unit] = {
      case wa: WikiAudit => {
        wa.create
        WikiObservers.after(wa.toEvent)
        clusterize(wa.toEvent)
      }
      case ev1: WikiEvent[_] => clusterize(ev1)
      case a: Audit => a.create
      case wc: WikiCount => wc.inc
      case init: InitAlligator => {
        clog << auditor.path
//        clog << clusterBrute.path
      }
      case e: Emailing => e.send
      case x @ _ => Audit("a", "ERR_ALLIGATOR", x.getClass.getName).create
    }

    def clusterize (ev:WikiEvent[_]) = {
//      clusterBrute ! ev
    }

    class WikiAuditor extends Actor {
      def receive = RazAlligator.receive
    }

    /** receives events from cluster members */
    class ClusterBrute extends Actor {
        val cluster = Cluster(context.system)

        override def preStart(): Unit =
          cluster.subscribe(self, classOf[MemberUp])
        override def postStop(): Unit =
          cluster.unsubscribe(self)

        def receive = {
          case state: CurrentClusterState ⇒
            state.members.filter(_.status == MemberStatus.Up) foreach register
          case MemberUp(m) ⇒ register(m)

          // actual work
          case ev1: WikiEvent[_] => {
            if(sender.compareTo(self) != 0) {
              clog << "CLUSTER_BRUTE " + ev1.toString
              WikiObservers.after(ev1)
            } else {
              clog << "CLUSTER_BRUTE SELF_NO_DO" + ev1.toString
            }
          }
          case x @ _ => Audit("a", "ERR_CLUSTER_BRUTE", x.getClass.getName).create
        }

        def register(member: akka.cluster.Member): Unit = {
          clog << "CLUSTER_REG " + member.toString()
//          if (member.hasRole("front"))
//            context.actorSelection(RootActorPath(member.address) /
//              "user" / "frontend") ! "haha" //BackendRegistration
        }
    }
  }
}

object LoggingFilter extends Filter {
  import ExecutionContext.Implicits.global

  private def isFromRobot(request: RequestHeader) = {
    (request.headers.get("User-Agent").exists(ua => Config.robotUserAgents.exists(ua.contains(_))))
  }

  def apply(next: (RequestHeader) => scala.concurrent.Future[Result])(rh: RequestHeader) = {
    val start = System.currentTimeMillis
    if (! rh.uri.startsWith( "/assets/") && !rh.uri.startsWith( "/razadmin/ping/shouldReload"))
      cdebug << s"LF.START ${rh.method} ${rh.uri}"
    GlobalData.synchronized {
      GlobalData.serving = GlobalData.serving + 1
    }

    def served {
      GlobalData.synchronized {
        GlobalData.served = GlobalData.served + 1
        GlobalData.serving = GlobalData.serving - 1
      }
    }

    def servedPage {
      GlobalData.synchronized {
        GlobalData.servedPages = GlobalData.servedPages + 1
      }
    }

    def logTime(rh:RequestHeader)(what: String)(result: Result): Result = {
      val time = System.currentTimeMillis - start
      if (rh.uri.startsWith("/razadmin/ping/shouldReload") || isFromRobot(rh)) {} else {
        clog << s"LF.STOP.$what ${rh.method} ${rh.host}${rh.uri} took ${time}ms and returned ${result.header.status}"
      }
      if (! isAsset) servedPage
      served
      result.withHeaders("Request-Time" -> time.toString)
    }

    def isAsset = rh.uri.startsWith( "/assets/") || rh.uri.startsWith("/favicon")

    try {
      next(rh) map { res =>
        logTime(rh)(if(isAsset) "ASSET" else "PAGE")(res)
      }
    } catch {
      case t: Throwable => {
        clog << s"LF.STOP.EXCEPTION ${rh.method} ${rh.uri} threw ${t.toString} \n ${com.razie.pub.base.log.Log.getStackTraceAsString(t)}"
        served
        throw t
      }
    }
  }
}
