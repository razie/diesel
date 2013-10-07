/**
 * ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
import admin.Audit
import admin.Config
import admin.RazAuthService
import admin.Services
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
import razie.clog
import razie.cout
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

/** customize some global handling errors */
object Global extends GlobalSettings {
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

  override def onHandlerNotFound(request: RequestHeader): Result = {
     Audit.logdb("ERR_onHandlerNotFound", "request:" + request.toString, "headers:" + request.headers)
    super.onHandlerNotFound(request)
  }

  override def onBadRequest(request: RequestHeader, error: String): Result = {
    Audit.logdb("ERR_onBadRequest", "request:" + request.toString, "headers:" + request.headers, "error:" + error)
    super.onBadRequest(request, error)
  }

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    clog << ("ROUTE_REQ: " + request.toString)
    super.onRouteRequest(request)
  }

  override def onStart(app: Application) = {
    try { new File("../updating").delete() }
    super.onStart(app)
  }

  override def beforeStart(app: Application) {
    // register the later actor
    //    val auditor = Akka.system.actorOf(Props[model.WikiAuditor], name = "WikiAuditor")

    Services.auth = RazAuthService
    Services.config = Config

    Services.mongoDbVer = 12 // one higher than the last one
    Services.mongoUpgrades = Map(1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5, 6 -> U6, 7 -> U7, 8 -> U8, 9 -> U9, 10 -> U10, 11 -> U11)

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

    WikiScripster.impl = RazWikiScripster

    //    U11.upgradeWL(Mongo.db)
    //    U11.upgradeRaz(Mongo.db)
    //    U11.upgradeRk(Mongo.db)
    //    U11.upgradeGlacierForums(Mongo.db)

    Services.audit = RazAuditService
    Services.alli = MyAlligator
  }

  object MyAlligator extends Alligator {
    lazy val auditor = Akka.system.actorOf(Props[WikiAuditor], name = "Alligator")

    def !(a: Any) {
      auditor ! a
    }

  }

  class WikiAuditor extends Actor {
    def receive = {
      case wa: WikiAudit => wa.create
      case a: Audit => a.create
      case wc: WikiCount => wc.inc
      case e: Emailing => e.send
      case x @ _ => Audit("a", "ERR_ALLIGATOR", x.getClass.getName).create
    }
  }
}
