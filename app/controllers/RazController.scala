package controllers

import admin.{Config, RazAuthService}
import com.mongodb.WriteResult
import model._
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import play.api.mvc._
import play.api.templates.Html
import razie.{cdebug, Logging}
import razie.wiki.model.{Reactors, Wikis, WikiUser, WID}
import razie.wiki.util.IgnoreErrors
import razie.wiki.util.VErrors
import razie.wiki.Services
import razie.wiki.admin.Audit

import scala.collection.mutable

/** common razie controller utilities */
class RazController extends RazControllerBase with Logging {

  def SUPPORT = Config.SUPPORT

  //================ stuff

  override def error (message: => String) = Audit.logdb("ERR_?", message)

  def dbop(r: WriteResult) = {}//log("DB_RESULT: " + r.getError)

  /** clean the cache for current user - probably a profile change */
  def cleanAuth (u: Option[User] = None)(implicit request: Request[_]) {
    RazAuthService.cleanAuth(u)(request)
  }

  /** authentication - find the user currently logged in */
  def auth(implicit request: Request[_]): Option[User] = {
    val au = RazAuthService.authUser (request)
    au
  }

  /** authentication - find the user currently logged in AND active */
  def activeUser(implicit request: Request[_], errCollector: VErrors = IgnoreErrors): Option[User] = {
    val au = auth(request) orCorr cNoAuth
    (au flatMap checkActive) map (_ => au.get)
  }

  def checkActive(au: User)(implicit errCollector: VErrors = IgnoreErrors) =
    toON2(au.isActive) orCorr (
      if (au.userName == "HarryPotter")
        cDemoAccount
      else
        cAccountNotActive)

  //================= RESPONSES

  def noPerm(wid: WID, more: String = "", shouldAudit: Boolean = true, teaser:String = "")(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {
    if (errCollector.hasCorrections) {
      val uname = auth.map(_.userName).getOrElse(if(isFromRobot) "ROBOT" else "")
      val msg = Website.get.prop("msg.err.noPerm").getOrElse("Sorry, you don't have the permission to do this!")

      if (shouldAudit)
        Services.audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format(uname, wid.toString, more + " " + errCollector.mkString, request.headers))

      if(teaser.isEmpty)
        Unauthorized(views.html.util.utilMsg(
          s""" <span style="color:red">$msg</span>""",
          s"""
${errCollector.mkString}

>> $more
""", Some(controllers.Wiki.w(wid).toString), auth))
      else
        Unauthorized(views.html.util.utilMsg(
        teaser,
        s"""
$more

""".stripMargin, Some(controllers.Wiki.w(wid).toString), auth))
    } else noPermOLD(wid, more + " " + errCollector.mkString)
  }

  private def noPermOLD(wid: WID, more: String = "")(implicit request: Request[_]) = {
    Services.audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), wid.toString, more, request.headers))
    val msg = Website.get.prop("msg.err.noPerm").getOrElse("Sorry, you don't have the permission to do this!")
    Unauthorized(views.html.util.utilMsg(
      s""" <span style="color:red">$msg</span>""",
      s"""

>> $more

If you got this message in error, please describe the issue in a <a href="/doe/support?desc=No+permission">support request</a> and we'll take care of it! Thanks!

""", Some(controllers.Wiki.w(wid).toString), auth))
  }

  def unauthorized(more: String = "", shouldAudit:Boolean=true)(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {
    if(shouldAudit)
      Services.audit.unauthorized("BY %s - Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))
    Unauthorized(views.html.util.utilMsg(
      more,
      s"""

${errCollector.mkString}

""", None, auth))
  }

  def Oops(msg: String, wid: WID)(implicit request: Request[_]) = {
    error(msg)
    Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid), auth))
  }

  def Msg(msg: String, wid: WID, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    Msg2(msg, Some(controllers.Wiki.w(wid, false)), if (u.isDefined) u else auth)(request)
  }

  def Msg2(msg: String)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    Ok(views.html.util.utilMsg(msg, "", None, auth))
  }

  def Msg2C(msg: String, page: Option[Call], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    Ok(views.html.util.utilMsg(msg, "", page.map(_.toString), if (u.isDefined) u else auth))
  }

  def vPorn: Constraint[String] = Constraint[String]("constraint.noBadWords") { o =>
    if (Wikis.hasBadWords(o))
      Invalid(ValidationError("Failed obscenity filter, eh?")) else Valid
  }

  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

  protected def forActiveUser[T](body: User => SimpleResult)(implicit request: Request[_]) = {
    implicit val errCollector = new VErrors()
    (for (
      au <- auth;
      isA <- checkActive(au)
    ) yield body(au)) getOrElse unauthorized("Can't... (not an active user)")
  }

  protected def forUser[T](body: User => SimpleResult)(implicit request: Request[_]) = {
    (for (
      au <- auth
    ) yield body(au)) getOrElse unauthorized("Oops - how did you get here? [no user]")
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAU(f: User => VErrors => Request[AnyContent] => SimpleResult) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield f(au)(errCollector)(request)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      unauthorized("OOPS "+more)
    }
  }

  def FAU(msg:String)(f: User => VErrors => Request[AnyContent] => Option[SimpleResult]) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield {
      cdebug << "START_FAU "+msg
      val temp = f(au)(errCollector)(request)
      temp
    }
    ).flatten getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      unauthorized("OOPS "+msg+more)
    }
  }

  // todo enhance this - collect robot suspicions and store them in a proposal table
  protected def isFromRobot(implicit request: Request[_]) = {
    (request.headers.get("User-Agent").exists(ua => Config.robotUserAgents.exists(ua.contains(_))))
  }

  val HOME = WID("Admin", "home")

  // Reactor OK - understands reqctor
  val ROK = new {
    def apply (msg: Seq[(String, String)])(implicit au: model.User, request: Request[_]) =
      new StateOk(msg, Website.realm, Some(au), Some(request))
    def apply (msg: (String, String))(implicit au: model.User, request: Request[_]) =
      new StateOk(Seq(msg), Website.realm, Some(au), Some(request))
    def apply () (implicit au: model.User, request: Request[_]) =
      new StateOk(Seq(), Website.realm, Some(au), Some(request))
    def apply (au: Option[model.User], request: Request[_]) =
      new StateOk(Seq(), Website.realm(request), au, Some(request))
  }
}

/** captures the current state of what to display - passed to all views */
class StateOk(val msg: Seq[(String, String)], val realm:String, val au: Option[model.User], val request: Option[Request[_]]) {
  var _title : String = "" // this is set by the body as it builds itself and used by the header, heh
  val _metas = new mutable.HashMap[String,String]() // moremetas

  /** set the title of this page */
  def title(s:String) = {this._title = s; ""}

  /** add a meta to this page's header */
  def meta(name:String, content:String) = {this._metas.put(name, content); ""}
  def metas = _metas.toMap

  def apply (content: StateOk => Html) = {
    RkViewService.Ok (views.html.util.reactorLayout(content(this), msg)(this))
  }

  def justLayout (content: StateOk => Html) = {
    views.html.util.reactorLayout(content(this), msg)(this)
  }

  def notFound (content: StateOk => Html) = {
    RkViewService.NotFound (views.html.util.reactorLayout(content(this), msg)(this))
  }

  def noLayout (content: StateOk => Html) = {
    RkViewService.Ok (content(this))
  }

  def website = Website.gets(this)
}

object RkViewService extends RazController with ViewService {
  def utilMsg (msg:String, details:String, link:Option[String], user:Option[WikiUser], linkNO:Option[(String,String)]=None)(implicit request: Request[_]): play.api.mvc.SimpleResult =
    Ok(views.html.util.utilMsg(msg, details, link, user.map(_.asInstanceOf[User]) orElse auth, linkNO))
}
