/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import java.util.Properties

import admin._
import com.google.inject.Guice
import controllers._
import mod.book.Progress
import razie.db._
import model._
import play.api.Application
import play.api._
import play.api.mvc._
import razie.wiki.util.PlayTools
import razie.{Log, cdebug, clog, cout}
import akka.actor._
import com.mongodb.casbah.{MongoConnection, MongoDB}
import com.mongodb.casbah.Imports._
import java.io.File

import services.{InitAlligator, RkCqrs}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import controllers.ViewService
import razie.audit.Audit
import razie.wiki.model._
import razie.wiki.admin._
import razie.wiki.{EncryptService, Services, WikiConfig}
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
        SendEmail.withSession(Website.xrealm(request)) { implicit mailSession =>
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

    val host = PlayTools.getHost(request).orElse(Some(Services.config.hostport))
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
      if(request.headers.get("X-Forwarded-For").exists(Config.badIps.contains(_))) {
        clog << "ERR_BADIP " + "request:" + request.toString + "headers:" + request.headers
        Audit.logdb("ERR_BADIP", "request:" + request.toString, "headers:" + request.headers)
        Some(EssentialAction {rh=>
          Action { rh:play.api.mvc.RequestHeader =>
            Results.Unauthorized("heh")
          }.apply(rh)
        })
      } else {
        super.onRouteRequest(request)
      }
    }

    if (! request.path.startsWith( "/assets/") && !request.path.startsWith( "/razadmin/ping/shouldReload"))
      cdebug << ("ROUTE_REQ.STOP: " + request.toString)

    res
  }

  override def onStart(app: Application) = {
    // automated restart / patch / update handling
    Try { new File("../updating").delete() }.isSuccess
    super.onStart(app)

    Services ! new InitAlligator

    // todo  SendEmail.initialize
  }

  override def beforeStart(app: Application) {
    clog << "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ beforeStart"

//    val injector = Guice.createInjector(new Module)

//    Services.auth = new RazAuthService ()
//    Services.config = Config

    Services.mkReactor = { (realm, fallBacks, we)=>
      realm match {
        case WikiReactors.RK | WikiReactors.NOTES | WikiReactors.WIKI => new RkReactor(realm, fallBacks, we)
        case _ => new RkReactor(realm, fallBacks, we)
      }
    }

    SendEmail.mkSession = (test:Boolean,debug:Boolean) => {
        val props = new Properties();

        val session = if(test) {
          props.put("mail.smtp.host", "localhost");
          javax.mail.Session.getInstance(props,
            new SMTPAuthenticator("your@email.com", "big secret"))
        } else {
          props.put("mail.smtp.auth", "true");
          props.put("mail.smtp.starttls.enable", "true");
          props.put("mail.smtp.host", "smtp.gmail.com");
          props.put("mail.smtp.port", "587");
          import razie.wiki.Sec._
          javax.mail.Session.getInstance(props, new SMTPAuthenticator(Config.SUPPORT, "zlMMCe7HLnMYOvbjYpPp6w==".dec))
        }

        session.setDebug(debug || test);
        session
      }

    WikiScripster.impl = new RazWikiScripster
    Services.runScriptImpl = (s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean) => {
      WikiScripster.impl.runScript(s, lang, page, user, query, devMode)
    }

    Services.initCqrs(new RkCqrs)

    WikiObservers mini {
      case event @ WikiEvent(_, "WikiEntry", _, Some(x), _, _, _) => {
        val we = x.asInstanceOf[WikiEntry]
        if("Site" == we.category) {
          Audit.logdb("DEBUG", "event.cleansite", we.wid.wpath)
          Website.clean(we.name)
        }

        if("Reactor" == we.category) {
          Audit.logdb("DEBUG", "event.reloadreactor", we.wid.wpath)
          WikiReactors.reload(we.name);
          Website.clean (we.name+".dieselapps.com")
          new Website(we).prop("domain").map (Website.clean)
        }
      }
    }

    WikiObservers mini {
      case WikiEvent("AUTH_CLEAN", "User", id, _, _, _, _) => {
        Services.auth.cleanAuth2(Users.findUserById(new ObjectId(id)).get)
      }
    }

    WikiRefinery.init()

    Progress.init

    SendEmail.init
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
