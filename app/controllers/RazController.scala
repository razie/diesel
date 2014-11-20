package controllers

import admin.{Audit, Config, IgnoreErrors, RazAuthService, Services, VErrors}
import com.mongodb.WriteResult
import model._
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import play.api.mvc._
import razie.{cdebug, Logging}


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

  def noPerm(wid: WID, more: String = "", shouldAudit: Boolean = true)(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {
    if (errCollector.hasCorrections) {
      val uname = auth.map(_.userName).getOrElse(if(isFromRobot) "ROBOT" else "")
      if (shouldAudit)
	Services.audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format(uname, wid.toString, more + " " + errCollector.mkString, request.headers))
      Unauthorized(views.html.util.utilMsg(
	s"""
Sorry, you don't have the permission to do this!

${errCollector.mkString}

$more
""", Some(controllers.Wiki.w(wid).toString), auth))
    } else noPermOLD(wid, more + " " + errCollector.mkString)
  }

  private def noPermOLD(wid: WID, more: String = "")(implicit request: Request[_]) = {
    Services.audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), wid.toString, more, request.headers))
    Unauthorized(views.html.util.utilMsg(
      s"""
Sorry, you don't have the permission to do this!

<font style="color:red">
>> $more
</font>

If you got this message in error, please describe the issue in a <a href="/doe/support">support request</a> and we'll take care of it! Thanks!

""", Some(controllers.Wiki.w(wid).toString), auth))
  }

  def unauthorized(more: String = "", shouldAudit:Boolean=true)(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {
    if(shouldAudit)
    Services.audit.unauthorized("BY %s - Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))
    Unauthorized(views.html.util.utilMsg(
      s"""
$more

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
    Ok(views.html.util.utilMsg(msg, None, auth))
  }

  def Msg2C(msg: String, page: Option[Call], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    Ok(views.html.util.utilMsg(msg, page.map(_.toString), if (u.isDefined) u else auth))
  }

  def vPorn: Constraint[String] = Constraint[String]("constraint.noporn") { o =>
    if (Wikis.hasporn(o))
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

  def FAU(f: User => VErrors => Request[AnyContent] => SimpleResult) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield f(au)(errCollector)(request)
      ) getOrElse unauthorized("CAN'T")
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
    ).flatten getOrElse unauthorized("CAN'T "+msg)
  }

  // todo enhance this - collect robot suspicions and store them in a proposal table
  protected def isFromRobot(implicit request: Request[_]) = {
    (request.headers.get("User-Agent").exists(ua => Config.robotUserAgents.exists(ua.contains(_))))
  }

  val HOME = WID("Admin", "home")
}

object RkViewService extends RazController with ViewService {
  def utilMsg (msg:String, link:Option[String], user:Option[WikiUser], linkNO:Option[(String,String)]=None)(implicit request: Request[_]): play.api.mvc.SimpleResult =
    Ok(views.html.util.utilMsg(msg, link, user.map(_.asInstanceOf[User]) orElse auth, linkNO))
}
