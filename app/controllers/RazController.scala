package controllers

import admin.{Config, RazAuthService}
import com.mongodb.WriteResult
import model._
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import play.api.mvc._
import play.twirl.api.Html
import razie.{cdebug, Logging}
import razie.wiki.model.{Reactors, Wikis, WikiUser, WID}
import razie.wiki.util.IgnoreErrors
import razie.wiki.util.VErrors
import razie.wiki.Services
import razie.wiki.admin.{WikiEvent, Audit}

import scala.collection.mutable
import scala.concurrent.Future

/** common razie controller utilities */
class RazController extends RazControllerBase with Logging {

//  implicit def betterrazRequest[A] (implicit request:Request[A]) = new BetterRazRequest[A](request)

// CANNOT have the below implicit - it will fuck up all stoks implicitly
  def razRequest (implicit request:Request[_]) = request match {
    case rr : RazRequest => rr
    case r : Request[_] => new RazRequest(request)
  }

//  implicit def xxx (implicit request:RazRequest) : RequestHeader = request.ireq
  // allow calling old methods from new code
  implicit def xxx (implicit request:RazRequest) : Request[_] = request.ireq

  def rhRequest (implicit request:RequestHeader) = request match {
    case rr : RazRequest => rr
    case r : Request[_] => new RazRequest(r)
    case _ => throw new IllegalArgumentException("")
  }

  implicit def errCollector (implicit req : RazRequest) = req.errCollector
//  implicit def stok (implicit req: RazRequest) = req.stok

  object BetterFAUR extends ActionBuilder[BetterRazRequest] with ActionTransformer[Request, BetterRazRequest] {
    def transform[A](request: Request[A]) = Future.successful {
      new BetterRazRequest(request)
    }
  }

  def SUPPORT = Config.SUPPORT

  //================ stuff

  override def error (message: => String) = Audit.logdb("ERR_?", message)

  def error (code:String, message: => String) (implicit errCollector: VErrors = IgnoreErrors) =
    Audit.logdb(code, message + " : " + errCollector.mkString)

  def verror (message: => String) (implicit errCollector: VErrors = IgnoreErrors) =
    Audit.logdb("ERR_?", message + " : " + errCollector.mkString)

  def dbop(r: WriteResult) = {}//log("DB_RESULT: " + r.getError)

  /** clean the cache for current user - probably a profile change */
  def cleanAuth (u: Option[User] = None)(implicit request: RequestHeader) {
    Services ! WikiEvent("AUTH_CLEAN", "User", u.map(_._id).mkString)
    Services.auth.cleanAuth(u)(request)
  }

  /** authentication - find the user currently logged in */
  def xauth(implicit request: RequestHeader): Option[User] = {
    val au = request match {
      case rr : RazRequest => rr.au
      case r:RequestHeader => Services.auth.authUser (request).asInstanceOf[Option[User]]
    }
    au
  }

  /** authentication - find the user currently logged in */
  def auth(implicit request: Request[_]): Option[User] = {
    val au = request match {
      case rr : RazRequest => rr.au
      case r:RequestHeader => Services.auth.authUser (request).asInstanceOf[Option[User]]
    }
    au
  }

  /** authentication - find the user currently logged in AND active */
  def activeUser(implicit request: Request[_], errCollector: VErrors = IgnoreErrors): Option[User] = {
    val au = auth(request) orCorr cNoAuth
    (au flatMap checkActive) map (_ => au.get)
  }

  def checkActive(au: WikiUser)(implicit errCollector: VErrors = IgnoreErrors) =
    toON2(au.isActive) orCorr (
      if (au.userName == "HarryPotter")
        cDemoAccount
      else
        cAccountNotActive)

  //================= RESPONSES

  def noPerm(wid: WID, more: String = "", shouldAudit: Boolean = true, teaser:String = "")(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {
//    implicit val stok = razRequest
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
""", Some(controllers.Wiki.w(wid).toString), auth)(rhRequest))
      else
        Unauthorized(views.html.util.utilMsg(
        teaser,
        s"""
$more

""".stripMargin, Some(controllers.Wiki.w(wid).toString), auth)(rhRequest))
    } else noPermOLD(wid, more + " " + errCollector.mkString)
  }

  private def noPermOLD(wid: WID, more: String = "")(implicit request: Request[_]) = {
//    implicit val stok = razRequest
    Services.audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), wid.toString, more, request.headers))
    val msg = Website.get.prop("msg.err.noPerm").getOrElse("Sorry, you don't have the permission to do this!")
    Unauthorized(views.html.util.utilMsg(
      s""" <span style="color:red">$msg</span>""",
      s"""

>> $more

If you got this message in error, please describe the issue in a <a href="/doe/support?desc=No+permission">support request</a> and we'll take care of it! Thanks!

""", Some(controllers.Wiki.w(wid).toString), xauth)(rhRequest))
  }

  def unauthorized(more: String = "", shouldAudit:Boolean=true)(implicit request: RequestHeader, errCollector: VErrors = IgnoreErrors) = {
//    implicit val stok = razRequest
    if(shouldAudit)
      Services.audit.unauthorized("BY %s - Info: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))
    else
      log("UNAUTHORIED: BY %s - Info: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))

    Unauthorized(views.html.util.utilMsg(
      more,
      s"""

${errCollector.mkString}

""", None, xauth)(rhRequest))
  }

  def Oops(msg: String, wid: WID)(implicit request: Request[_]) = {
//    implicit val stok = razRequest
    error(msg)
    Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid))(rhRequest))
  }

  def Msg(msg: String, wid: WID, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.Result = {
    Msg2(msg, Some(controllers.Wiki.w(wid, false)), if (u.isDefined) u else auth)(request)
  }

  def Msg(msg: String)(implicit request: Request[_]): play.api.mvc.Result = {
    Msg(msg, "")
  }

  def Msg(msg: String, details:String)(implicit request: RequestHeader): play.api.mvc.Result = {
//    implicit val stok = razRequest
    Ok(views.html.util.utilMsg(msg, details, None, xauth)(rhRequest))
  }

  def Msg2(msg: String)(implicit request: RequestHeader): play.api.mvc.Result = {
//    implicit val stok = razRequest
    Ok(views.html.util.utilMsg(msg, "", None, xauth)(rhRequest))
  }

  def Msg2C(msg: String, page: Option[Call], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.Result = {
//    implicit val stok = razRequest
    Ok(views.html.util.utilMsg(msg, "", page.map(_.toString), if (u.isDefined) u else auth)(rhRequest))
  }

  def vPorn: Constraint[String] = Constraint[String]("constraint.noBadWords") { o =>
    if (Wikis.hasBadWords(o))
      Invalid(ValidationError("Failed obscenity filter, eh?")) else Valid
  }

  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

  protected def forActiveUser[T](body: User => Result)(implicit request: Request[_]) = {
    implicit val errCollector = new VErrors()
    (for (
      au <- auth;
      isA <- checkActive(au)
    ) yield body(au)) getOrElse unauthorized("Can't... (not an active user)")
  }

  protected def forUser[T](body: User => Result)(implicit request: Request[_]) = {
    (for (
      au <- auth
    ) yield body(au)) getOrElse unauthorized("Oops - how did you get here? [no user]")
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def RAction(f: RazRequest => Result) = Action { implicit request =>
    val req = razRequest
    f(req)
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAUR(f: RazRequest => Result) = Action { implicit request =>
    val req = razRequest
    (for (
      au <- req.au;
      isA <- checkActive(au)
    ) yield f(req)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      unauthorized("OOPS "+more, !isFromRobot)
    }
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAU(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield f(au)(errCollector)(request)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      unauthorized("OOPS "+more, !isFromRobot)
    }
  }

  def FAU(msg:String)(f: User => VErrors => Request[AnyContent] => Option[Result]) = Action { implicit request =>
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
      unauthorized("OOPS "+msg+more, !isFromRobot)
    }
  }

  // todo enhance this - collect robot suspicions and store them in a proposal table
  protected def isFromRobot(implicit request: RequestHeader) = {
    (request.headers.get("User-Agent").exists(ua => Config.robotUserAgents.exists(ua.contains(_))))
  }

  val HOME = WID("Admin", "home")

  // Reactor OK - understands reqctor
  val ROK = new {
    def apply (msg: Seq[(String, String)])(implicit au: model.User, request: Request[_]) = {
      new RazRequest(Website.realm, Some(au), request).msg(msg)
    }
    def apply (msg: (String, String))(implicit au: model.User, request: Request[_]) = {
      new RazRequest(Website.realm, Some(au), request).msg(msg)
    }
    def apply () (implicit au: model.User, request: Request[_]) =
      new RazRequest(Website.realm, Some(au), request)
    def apply (au: Option[model.User], request: Request[_]) =
      new RazRequest(Website.realm(request), au, request)
    def s (implicit au: model.User, request: Request[_]) =
      new RazRequest(Website.realm, Some(au), request)
    def r (implicit request: Request[_]) =
      new RazRequest(Website.realm, auth, request)
    def k (implicit stok: RazRequest) = stok
  }
}

/** captures the current state of what to display - passed to all views */
class StateOk(val realm:String, val au: Option[model.User], val request: Option[Request[_]]) {
  var msg: Seq[(String, String)] = Seq.empty
  var _title : String = "" // this is set by the body as it builds itself and used by the header, heh
  val _metas = new mutable.HashMap[String,String]() // moremetas
  var _css : Option[String] = None // if you determine you want a different CSS
  var stuff : List[String] = List() // keep temp status for this request

  /** set the title of this page */
  def title(s:String) = {
    this._title = s;
  }

  def msg (imsg: Seq[(String, String)]) : StateOk = {
    msg = imsg
    this
  }
  def msg (imsg: (String, String)) : StateOk = {
    msg = Seq(imsg)
    this
  }

  /** set the title of this page */
  def css(s:String) = {this._css = Some(s); ""}
  def maybeCss(s:Option[String]) = {this._css = s; ""}
  def css = {
    val ret = _css.getOrElse {
      // session settings override everything
      request.flatMap(_.session.get("css")) orElse (
        // then user
        au.flatMap(_.css)
        ) orElse (
        // or website settings
        request.flatMap(r=> Website(r)).flatMap(_.css)
        ) getOrElse ("light")
    }

    if(ret == null || ret.length <=0) "light" else ret
  }
  def isLight = css contains "light"

  /** add a meta to this page's header */
  def meta(name:String, content:String) = {this._metas.put(name, content); ""}
  def metas = _metas.toMap

//  def stokRequest (implicit stok:StateOk) = {
//    val r = new RazRequest(stok.request.get)
//    r.msg = stok.msg
//    r._title = stok._title
//    r._css = stok._css
//    r
//  }
  def reactorLayout12 (content: StateOk => Html) =
    RkViewService.Ok (views.html.util.reactorLayout12(content(this), msg)(this))

//  def apply (content: => Html) =
//    RkViewService.Ok (views.html.util.reactorLayout(content, msg)(this))

  def apply (content: StateOk => Html) =
    RkViewService.Ok (views.html.util.reactorLayout(content(this), msg)(this))

  def justLayout (content: StateOk => Html) =
    views.html.util.reactorLayout(content(this), msg)(this)

  def notFound (content: StateOk => Html) =
    RkViewService.NotFound (views.html.util.reactorLayout(content(this), msg)(this))

  /** use when handling forms */
//  def badRequest (content: => Html) =
//    RkViewService.BadRequest (views.html.util.reactorLayout(content, msg)(this))

  def badRequest (content: StateOk => Html) =
    RkViewService.BadRequest (views.html.util.reactorLayout(content(this), msg)(this))

  /** use for old templates with embedded layout OR plaint text */
  def noLayout (content: StateOk => Html) =
    RkViewService.Ok (content(this))

  def website = Website.gets(this)
}

object RkViewService extends RazController with ViewService {
  def utilMsg(msg: String, details: String, link: Option[String], user: Option[WikiUser], linkNO: Option[(String, String)] = None)(implicit request: RequestHeader): play.api.mvc.Result = {
//    implicit val stok = razRequest
    Ok(views.html.util.utilMsg(msg, details, link, user.map(_.asInstanceOf[User]) orElse xauth, linkNO)(rhRequest))
  }
}

class RazRequest (realm:String, au:Option[User], val ireq:Request[_]) extends StateOk (
  realm,
  au orElse Services.auth.authUser(ireq).asInstanceOf[Option[User]],
  Some(ireq))
//  with play.api.mvc.RequestHeader
{

  def this (ireq:Request[_]) = this(
    Website.getRealm (ireq),
    None,
    ireq
  )

  def req = ireq.asInstanceOf[Request[AnyContent]]
  def oreq = Some(ireq)
  //  lazy val au =

  lazy val stok = this //new controllers.StateOk(Seq(), realm, au, Some(request))
  lazy val errCollector = new razie.wiki.util.VErrors()

  def session = ireq.session
  def flash = ireq.flash

  def id : scala.Long = ireq.id
  def tags : scala.Predef.Map[scala.Predef.String, scala.Predef.String] = ireq.tags
  def headers : play.api.mvc.Headers = ireq.headers
  def queryString : scala.Predef.Map[scala.Predef.String, scala.Seq[scala.Predef.String]] = ireq.queryString
  def path : scala.Predef.String = ireq.path
  def uri : scala.Predef.String = ireq.uri
  def method : scala.Predef.String = ireq.method
  def version : scala.Predef.String = ireq.version
  def remoteAddress : scala.Predef.String = ireq.remoteAddress
  def secure : scala.Boolean = ireq.secure

//  override def id : scala.Long = ireq.id
//  override def tags : scala.Predef.Map[scala.Predef.String, scala.Predef.String] = ireq.tags
//  override def headers : play.api.mvc.Headers = ireq.headers
//  override def queryString : scala.Predef.Map[scala.Predef.String, scala.Seq[scala.Predef.String]] = ireq.queryString
//  override def path : scala.Predef.String = ireq.path
//  override def uri : scala.Predef.String = ireq.uri
//  override def method : scala.Predef.String = ireq.method
//  override def version : scala.Predef.String = ireq.version
//  override def remoteAddress : scala.Predef.String = ireq.remoteAddress
//  override def secure : scala.Boolean = ireq.secure
}

// todo I can't use this - when using it, too many errors from implicits and stuff
class BetterRazRequest[A] (val request:Request[A]) extends WrappedRequest[A] (request) {
  def oreq = Some(request)
  lazy val au = Services.auth.authUser(request).asInstanceOf[Option[User]]
  lazy val realm = Website.getRealm (request)
  lazy val stok = new controllers.StateOk(realm, au, Some(request))
  lazy val errCollector = new razie.wiki.util.VErrors()
}


