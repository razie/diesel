/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import java.util.Properties
import admin._
import controllers._
import razie.db._
import model._
import play.api.Application
import play.api._
import play.api.mvc._
import razie.wiki.util.{IgnoreErrors, VErrors}
import razie.{cout, Log, cdebug, clog}
import play.libs.Akka
import akka.actor.Props
import akka.actor.Actor
import com.mongodb.casbah.{MongoDB, MongoConnection}
import com.mongodb.casbah.Imports._
import java.io.File
import scala.concurrent.ExecutionContext
import controllers.ViewService
import razie.wiki.model.WikiCount
import razie.wiki.admin.{SMTPAuthenticator, SendEmail, GlobalData, Audit}
import razie.wiki.{WikiConfig, Alligator, EncryptService, Services}
import razie.wiki.model.WikiAudit
import razie.wiki.model.WikiUsers
import razie.wiki.model.WikiUser
import razie.wiki.model.Reactors
import razie.wiki.model.WikiEntry
import razie.wiki.model.Reactor
import razie.wiki.Sec._

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
            api.wix.user.map(_.userName).mkString, m)

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
    if (! request.path.startsWith( "/assets/"))
      cdebug << ("ROUTE_REQ.START: " + request.toString)
    val res = super.onRouteRequest(request)
    if (! request.path.startsWith( "/assets/"))
      cdebug << ("ROUTE_REQ.STOP: " + request.toString)
    res
  }

  override def onStart(app: Application) = {
    // automated restart / patch / update handling
    try { new File("../updating").delete() }
    super.onStart(app)

    // todo  SendEmail.initialize
  }

  override def beforeStart(app: Application) {
    // register the later actor
    //    val auditor = Akka.system.actorOf(Props[model.WikiAuditor], name = "WikiAuditor")

//    WikiConfig.RK = "rk"

    Services.auth = RazAuthService
    Services.config = Config

    /************** MONGO INIT *************/
    RazMongo.setInstance {
      val UPGRADE_AGAIN = false
      val mongoDbVer = 16 // normal is one higher than the last one
      val mongoUpgrades: Map[Int, UpgradeDb] = Map(
          1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5,
          6 -> U6, 7 -> U7, 8 -> U8, 9 -> U9, 10 -> U10, 11 -> U11, 12 -> U12, 13 -> U13,
          14 -> U14, 15 -> U15)

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
        if (UPGRADE_AGAIN) dbVer = dbVer.map(_ - 1)

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

    Services.mkReactor = { (realm, fallBack)=>
      realm match {
        case Reactors.DFLT | Reactors.NOTES | Reactors.WIKI => new RkReactor(realm, fallBack)
        case _ => new RkReactor(realm, fallBack)
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
    Wiki.authImpl = RazWikiAuthorization

    //todo look these up in Website
    Services.isSiteTrusted = {s=>
      s.startsWith("www.racerkidz.com") ||
        s.startsWith("www.enduroschool.com") ||
        s.startsWith("www.nofolders.net") ||
        s.startsWith("www.askicoach.com") ||
        s.startsWith("www.dieselreactor.net") ||
        s.startsWith("www.wikireactor.net") ||
        s.startsWith("www.coolscala.com") ||
        Config.trustedSites.exists(x=>s.startsWith(x))
    }

    WikiScripster.impl = new RazWikiScripster
    Services.runScriptImpl = (s: String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean) => {
      WikiScripster.impl.runScript(s, page, user, query, devMode)
    }

    //    U11.upgradeWL(RazMongo.db)
    //    U11.upgradeRaz(RazMongo.db)
    //    U11.upgradeRk(RazMongo.db)
    //    U11.upgradeGlacierForums(RazMongo.db)
    //        U11.upgradeGlacierForums2()

    Services.audit = RazAuditService
    Services.alli = RazAlligator

    Notif add new Notif {
      override def entityCreateAfter[A](e: A)(implicit errCollector: VErrors = IgnoreErrors):Unit = {e match {case we:WikiEntry => up(we)}}
      override def entityUpdateAfter[A](e: A, what: String)(implicit errCollector: VErrors = IgnoreErrors):Unit = {e match {case we:WikiEntry=>up(we)}}

      private def up(we:WikiEntry):Unit = {
        if("Site" == we.category) Website.clean(we.name)

        if("Reactor" == we.category) {
          Reactors.reload(we.name);
          Website.clean(we.name+".wikireactor.com")
        }
      }
    }
  }

  object RazAlligator extends Alligator {
    lazy val auditor = Akka.system.actorOf(Props[WikiAuditor], name = "Alligator")

    def !(a: Any) {
//      this receive a
      // TODO enable async audits
      auditor ! a
    }

    def !?(a: Any) {
      this receive a
    }

    def receive: PartialFunction[Any, Unit] = {
      case wa: WikiAudit => wa.create
      case a: Audit => a.create
      case wc: WikiCount => wc.inc
      case e: Emailing => e.send
      case x @ _ => Audit("a", "ERR_ALLIGATOR", x.getClass.getName).create
    }

    class WikiAuditor extends Actor {
      def receive = RazAlligator.receive
    }
  }
}

object LoggingFilter extends Filter {
  import ExecutionContext.Implicits.global

  def apply(next: (RequestHeader) => scala.concurrent.Future[SimpleResult])(rh: RequestHeader) = {
    val start = System.currentTimeMillis
    if (! rh.uri.startsWith( "/assets/"))
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

    def logTime(what: String)(result: SimpleResult): Result = {
      val time = System.currentTimeMillis - start
      if (!isAsset) {
        clog << s"LF.STOP $what ${rh.method} ${rh.uri} took ${time}ms and returned ${result.header.status}"
        servedPage
      }
      served
      result.withHeaders("Request-Time" -> time.toString)
    }

    def isAsset = rh.uri.startsWith( "/assets/") || rh.uri.startsWith("/favicon")

    try {
      next(rh) match {
        //TODO restore these
//        case plain: Future[SimpleResult] => logTime("plain")(plain)
        // TODO enable this
//        case async: AsyncResult => async.transform(logTime("async"))
        case res @ _ => {
          clog << s"LF.STOP.WHAT? ${rh.method} ${rh.uri} returned ${res}"
          if (! isAsset) servedPage
          served
          res
        }
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
