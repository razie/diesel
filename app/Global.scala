/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
import admin.Audit
import admin.Config
import admin.RazAuthService
import admin.Services
import controllers.{Wiki, RazWikiAuthorization, ViewService, RkViewService}
import db._
import model.EncryptService
import model.WikiUsers
import model.WikiUsersImpl
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import controllers.ViewService
import controllers.RkViewService
import controllers.WikiBase1
import controllers.WikiBase1
import controllers.Wiki
import controllers.RazWikiAuthorization
import model.WikiScripster
import admin.RazWikiScripster
import razie.{cdebug, clog, cout}
import play.libs.Akka
import akka.actor.Props
import admin.Alligator
import akka.actor.Actor
import model.WikiAudit
import model.WikiCount
import controllers.Emailing
import admin.RazAuditService
import com.mongodb.casbah.MongoConnection
import model.RacerKidz
import controllers.Emailer
import controllers.RazController
import java.io.File
import scala.concurrent.ExecutionContext
import admin.GlobalData

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

  // TODO per throwable, eh?
  override def onError(request: RequestHeader, ex: Throwable) = {
    clog << "ERR_onError - trying to log/audit in DB... " + "request:" + request.toString + "headers:" + request.headers + "ex:" + ex.toString
    Audit.logdb("ERR_onError", "request:" + request.toString, "headers:" + request.headers, "ex:" + ex.toString)
    val m = ("ERR_onError", "Current count: " + lastErrorCount + " Request:" + request.toString, "headers:" + request.headers, "ex:" + ex.toString).toString
    if (System.currentTimeMillis - lastErrorTime >= ERR_DELTA1) {
      if (errEmails <= ERR_EMAILS || System.currentTimeMillis - firstErrorTime >= ERR_DELTA2) {
        admin.SendEmail.withSession { implicit mailSession =>
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
  }

  override def beforeStart(app: Application) {
    // register the later actor
    //    val auditor = Akka.system.actorOf(Props[model.WikiAuditor], name = "WikiAuditor")

    Services.auth = RazAuthService
    Services.config = Config

    Services.mongoDbVer = 16 // normal is one higher than the last one
    Services.mongoUpgrades = Map(
      1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5,
      6 -> U6, 7 -> U7, 8 -> U8, 9 -> U9, 10 -> U10, 11 -> U11, 12 -> U12, 13 -> U13,
      14 -> U14, 15 -> U15)

    Services.mkDb = () => {
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

      db
    }

    WikiUsers.impl = WikiUsersImpl

    EncryptService.impl = admin.CypherEncryptService
    ViewService.impl = RkViewService
    Wiki.authImpl = RazWikiAuthorization

    WikiScripster.impl = new RazWikiScripster

    //    U11.upgradeWL(RazMongo.db)
    //    U11.upgradeRaz(RazMongo.db)
    //    U11.upgradeRk(RazMongo.db)
    //    U11.upgradeGlacierForums(RazMongo.db)
    //        U11.upgradeGlacierForums2()

    Services.audit = RazAuditService
    Services.alli = RazAlligator
  }

  object RazAlligator extends Alligator {
    lazy val auditor = Akka.system.actorOf(Props[WikiAuditor], name = "Alligator")

    def !(a: Any) {
      this receive a
      // TODO enable async audits
      //      auditor ! a
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

    def logTime(what: String)(result: SimpleResult): Result = {
      val time = System.currentTimeMillis - start
      if (! rh.uri.startsWith( "/assets/"))
        clog << s"LF.STOP $what ${rh.method} ${rh.uri} took ${time}ms and returned ${result.header.status}"
      served
      result.withHeaders("Request-Time" -> time.toString)
    }

    try {
      next(rh) match {
        //TODO restore these
//        case plain: Future[SimpleResult] => logTime("plain")(plain)
        // TODO enable this
//        case async: AsyncResult => async.transform(logTime("async"))
        case res @ _ => {
          clog << s"LF.STOP.WHAT? ${rh.method} ${rh.uri} returned ${res}"
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
