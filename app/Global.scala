/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */

import Global.isShouldDebug
import admin._
import com.mongodb.casbah.Imports._
import controllers._
import java.util.Properties
import mod.book.Progress
import model._
import play.api.Application
import play.api.mvc._
import razie.audit.Audit
import razie.db._
import razie.diesel.DieselRateLimiter
import razie.diesel.DieselRateLimiter.getGroup
import razie.hosting.{BannedIps, Website, WikiReactors}
import razie.wiki.admin._
import razie.wiki.model._
import razie.wiki.util.PlayTools
import razie.wiki.{Config, Services}
import razie.{cdebug, clog}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import services.{InitAlligator, RkCqrs}

/** customize some global handling errors */
object Global extends WithFilters(LoggingFilter) {
  // EMAIL BACKOFF stuff
  val ERR_DELTA1 = 5 * 60 * 1000 // 5 min
  val ERR_DELTA2 = 6 * 60 * 60 * 1000 // 6 hours
  val ERR_EMAILS = 5 // per DELTA2
  var errEmails = 0 // sent per DELTA2
  var lastErrorTime = System.currentTimeMillis - ERR_DELTA1 // time last error email went out - just one every 5 min, eh
  var firstErrorTime = System.currentTimeMillis - ERR_DELTA2 // time first error email went out - just one every 5
  // min, eh
  var lastErrorCount = 0 // time last error email went out - just one every 5 min, eh

  override def onError(request: RequestHeader, ex: Throwable) = {
    clog << "ERR_onError - trying to log/audit in DB... " + "request:" + request.toString + "headers:" + request
        .headers + "ex:" + ex.toString
    Audit.logdb("ERR_onError", "request:" + request.toString, "headers:" + request.headers, "ex:" + ex.toString)
    val m = ("ERR_onError", "Current count: " + lastErrorCount + " Request:" + request.toString, "headers:" + request
        .headers, "ex:" + ex.toString).toString
    if (System.currentTimeMillis - lastErrorTime >= ERR_DELTA1) {
      if (errEmails <= ERR_EMAILS || System.currentTimeMillis - firstErrorTime >= ERR_DELTA2) {
        SendEmail.withSession(Website.xrealm(request)) { implicit mailSession =>
          Emailer.tellAdmin("ERR_onError",
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
    clog << s"ERR_onHandlerNotFound " + "request:" + request.toString + "headers:" + request.headers
    Audit.logdb(
      "ERR_onHandlerNotFound", s"ip=${request.headers.get("X-Forwarded-For")}",
      "request:" + request.toString, "headers:" + request.headers)
    super.onHandlerNotFound(request)
  }

  override def onBadRequest(request: RequestHeader, error: String)= {
    clog << (s"ERR_onBadRequest " + "request:" + request.toString + "headers:" + request.headers + "error:" + error)
    Audit.logdb(
      "ERR_onBadRequest", s"ip=${request.headers.get("X-Forwarded-For")}",
      "request:" + request.toString, "headers:" + request.headers, "error:" + error)
    super.onBadRequest(request, error)
  }

  /** some requests are too frequent and safe, no debug to inflate logs */
  def isShouldDebug(path: String) =
    !path.startsWith("/assets/") &&
        !path.startsWith("/razadmin/ping/shouldReload") &&
        !path.startsWith("/diesel/status")

  /** intercepting routing of requests to see about forwards and proxies */
  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    val shouldDebug = isShouldDebug(request.path)

    if (shouldDebug) cdebug << ("ROUTE_REQ.START: " + request.toString)

    /** get the host that was forwarded here - used for multi-site hosting */

    val host = PlayTools.getHost(request).orElse(Some(Services.config.hostport))
    val redirected = Try {
      host.flatMap(x => Config.urlrewrite(x + request.path))
    } getOrElse
      None

    val SPROXY = "snakkproxy.dieselapps.com"
    val SSPROXY = "ssnakkproxy.dieselapps.com"

    val res = redirected.map { x =>
      clog << ("URL - REDIRECTING? - " + host.mkString+request.path)
      clog << ("URL -   TO         - " + redirected)
      EssentialAction {rh=>
      Action { rh:play.api.mvc.RequestHeader =>
        // todo maybe change the header and process here (see proxy below)
        // as this can be hacked to redirect to random sites...?
        Results.Redirect(x)
        }.apply(rh)
      }
    } orElse {

      // not redirected, serve it

      if (request.path.contains(
        "removebadip")) { // && Services.auth.authUser(request).isDefined) { // in case i ban myself
        // bad ip

        request.headers.get("X-Forwarded-For").flatMap(BannedIps.findIp).map(_.delete(tx.auto))
        Audit.logdb("BANNED_IP_REMOVED",
          List("request:" + request.toString, "headers:" + request.headers).mkString("<br>"))
        Some(EssentialAction { rh =>
          Action { rh: play.api.mvc.RequestHeader =>
            Results.Unauthorized("deh")
          }.apply(rh)
        })

      } else if (request.headers.get("X-Forwarded-For").exists(Config.badIps.contains(_))) {
        // bad ip

        clog << "ERR_BADIP " + "request:" + request.toString + "headers:" + request.headers
        Audit.logdb("ERR_BADIP", "request:" + request.toString, "headers:" + request.headers)
        Some(EssentialAction { rh =>
          Action { rh: play.api.mvc.RequestHeader =>
            Results.Unauthorized("heh")
          }.apply(rh)
        })

      } else if (request.host.endsWith(SPROXY) || request.host.endsWith(SSPROXY)) {
        // SS proxy stuff

        // change request
        val host =
          if (request.host.endsWith(SSPROXY)) request.host.replaceFirst("." + SSPROXY, "")
          else request.host.replaceFirst("." + SPROXY, "")
        val protocol = if (request.host.endsWith(SSPROXY)) "https" else "http"
        val rh = request.copy(path = "/snakk/proxy/" + protocol + "/" + host + request.path)
        clog << ("SNAKKPROXY to " + request)
        super.onRouteRequest(rh)

      } else { // normal request

        DieselRateLimiter.serveOrLimit(request, true)((rh, group) => {
          group.foreach(_.decServing())
          super.onRouteRequest(request)
        })((group, count) => {
          // request limiting here will avoid overloading the internal execution queue as well
          Some(EssentialAction { rh =>
            Action { rh: play.api.mvc.RequestHeader =>
//              Audit.logdb("ERR_RATE_LIMIT", s"group: $group curr: $count request: $request headers: ${request
//              .headers}")
              Results.TooManyRequest("Rate limiting - in progress: " + count)
            }.apply(rh)
          })
        })
      }
    }

    if (shouldDebug) cdebug << ("ROUTE_REQ.STOP: " + request.toString)

    res
  }

  def isBadIp (request:RequestHeader) : Boolean = {
    request.headers.get("X-Forwarded-For").exists(Config.badIps.contains(_)) ||
      (request.toString startsWith "REMOTE HI_SRDK_DEV_")

  }

  override def onStart(app: Application) = {
    // automated restart / patch / update handling
    // no longer removing here, but in first successful diesel/ping
    //Try {new File("../updating").delete()}.isSuccess

    super.onStart(app)

    Services ! new InitAlligator

    Try {
      clog << "Awaiting for reactors to load..."
      Await.result(GlobalData.reactorsLoadedF, Duration("20 seconds"))
    }

    // reset all settings
    DieselRateLimiter.RATELIMIT // just access it to initialize the object - we're sending it an update
    clog << "sending WikiConfigChanged..."
    Services ! new WikiConfigChanged("", Config)

    // not needed - just example for starting stuff
    // WARNING - this may have caused some weird issues because it was ran during startup ?
    // - not sure. Test well if using!
//    Services ! ScheduledDieselMsg("10 seconds", DieselMsg(
//      "diesel.props",
//      "configReload",
//      Map("realm" -> "wiki"),
//      DieselTarget.ENV("wiki")
//    ))

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


    SendEmail.mkSession = (msession:BaseMailSession, test:Boolean,debug:Boolean)=> {
      val XW = "1mrTyLJfbe4VoG2jXu4vdg"
      val props = new Properties();

        val session = if(test) {

          props.put("mail.smtp.host", "localhost");
          javax.mail.Session.getInstance(props,
            new SMTPAuthenticator("your@email.com", "big secret"))

        } else {

          val website = msession.realm.flatMap(Website.forRealm).getOrElse(Website.dflt)

          def p(name:String, dflt:String) = website.prop(name).getOrElse(dflt)

          props.put("mail.smtp.auth", p("mail.smtp.auth", "true"));
          props.put("mail.smtp.starttls.enable", p("mail.smtp.starttls.enable", "true"));
          props.put("mail.smtp.host", p("mail.smtp.host", "smtp.gmail.com"));
          props.put("mail.smtp.port", p("mail.smtp.port", "587"));

          import razie.wiki.Sec._

          val user = website.prop("mail.smtp.user").getOrElse(Config.SUPPORT)
          val pwd = website.prop("mail.smtp.pwd").getOrElse(XW).dec

          javax.mail.Session.getInstance(props, new SMTPAuthenticator(user, pwd))
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
      case WikiEvent("AUTH_CLEAN", "User", id, _, _, _, _) => {
        Services.auth.cleanAuth2(Users.findUserById(new ObjectId(id)).get)
      }
    }

    WikiRefinery.init()

    Progress.init

    SendEmail.init
  }

}

/** this filter is called to execute the request, we can handle before and after */
object LoggingFilter extends Filter {
  import ExecutionContext.Implicits.global

  private def isFromRobot(request: RequestHeader) = {
    (request.headers.get("User-Agent").exists(ua => Config.robotUserAgents.exists(ua.contains(_))))
  }

  def apply(next: (RequestHeader) => scala.concurrent.Future[Result])(rh: RequestHeader) = {
    val start = System.currentTimeMillis
    val shouldDebug = isShouldDebug(rh.uri)
    val apiRequest = DieselRateLimiter.isApiRequest(rh.uri)
    val isAsset = rh.uri.startsWith("/assets/") || rh.uri.startsWith("/favicon")

    if (!rh.uri.contains("/ping/shouldReload") && !rh.uri.startsWith("/diesel/status")) {
      clog << s"LF.START ${rh.method} ${rh.host} ${rh.uri}"
    }

    def served {
      if (GlobalData.serving.get() > GlobalData.maxServing.get()) {
        GlobalData.maxServing.set(GlobalData.serving.get())
      }

      if (GlobalData.servingApiRequests.get() > GlobalData.maxServingApiRequests.get()) {
        GlobalData.maxServingApiRequests.set(GlobalData.servingApiRequests.get())
      }

      GlobalData.serving.decrementAndGet()
      GlobalData.served.incrementAndGet()

      if (apiRequest) {
        GlobalData.servingApiRequests.decrementAndGet()
        GlobalData.servedApiRequests.incrementAndGet()
      }

      // keep stats per group
      getGroup(rh).foreach { group =>
        if (group.servingCount.get() > group.maxServedCount.get()) {
          group.maxServedCount.set(group.servingCount.get())
        }

        group.decServing()
        group.servedCount.incrementAndGet()
      }
    }

    def servedPage {
      GlobalData.servedRequests.incrementAndGet()
    }

    def logTime(rh:RequestHeader)(what: String)(result: Result): Result = {
      val time = System.currentTimeMillis - start

      if (!isFromRobot(rh) && !rh.uri.contains("razadmin/ping/shouldReload") && !rh.uri.startsWith("/diesel/status")) {
        clog << s"LF.STOP.$what ${rh.method} ${rh.host}${rh.uri} took ${time}ms and returned ${result.header.status}"
      }

      served

      var res = result.withHeaders("Request-Time" -> time.toString)

      // See @Config.HEADERS
      Config.HEADERS.split(",").filter(_.length > 0).map {h =>
        val v = Config.prop(s"wiki.header.$h.value")
        val r = Config.prop(s"wiki.header.$h.regex")

        if(r.length <= 0 || rh.path.matches(r))
          res = res.withHeaders(h -> v)
      }

      res
    }

    def getType =
      if(isAsset) "ASSET"
      else "PAGE"

    // execute or filter out
    try {

      var err: Option[Result] = None

      // first check rate limiting - if limited, populate err
      err = DieselRateLimiter.serveOrLimit(rh)((rh, group) => {
        None.asInstanceOf[Option[Result]]
      })((group, count) => {
        // more expensive stuff outside the sync block
        // rate limiting API requests
//        Audit.logdb("ERR_RATE_LIMIT2", s"group: $group count: $count request: $rh headers: + ${rh.headers}")

        // log LF.STOP here as well so it matches STARTS even for errors
        if (!isFromRobot(rh)) {
          clog << s"LF.STOP.WITH-ERR ${rh.method} ${rh.host}${rh.uri}"
        }

        clog << s"LF.ERR.429 ${rh.method} ${rh.uri}"

        Some(
          Results.TooManyRequest("Rate limiting 2 - in progress group: $group count: $count")
        )
      })

      if (err.nonEmpty) {
        Future.successful {
          err.get // return the err
        }
      } else {
        // actually serving it

        // run the executor that was meant to be
        val executed = next(rh)

        executed map { res =>
          if (res.header.status == 200)
            logTime(rh)(getType)(res)
          else
            logTime(rh)(getType)(res)
        }
      }
    } catch {
      case t: Throwable => {
        clog <<
            s"""LF.ERR.EXCEPTION ${rh.method} ${rh.uri} threw ${t.toString} \n
               | ${com.razie.pub.base.log.Log.getStackTraceAsString(t)}""".stripMargin
        served
        throw t
      }
    }
  }
}
