package controllers

import com.google.inject.Inject
import model._
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import play.api.i18n.{Messages, MessagesApi}
import play.api.mvc._
import razie.Logging
import razie.audit.Audit
import razie.db.Txn
import razie.hosting.Website
import razie.wiki.model._
import razie.wiki.{Config, Services}
import scala.concurrent.Future
import razie.js

/** base controller class - common controller utilities for access etc */
class RazController extends RazControllerBase with play.api.i18n.I18nSupport with razie.Logging {

  @Inject() override val messagesApi: MessagesApi = null
  implicit def messagesProvider = this


  object retj {
    def <<(x: List[Any]) = Ok(js.tojson(x).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

// CANNOT have the below implicit - it will mess up all stoks implicitly
  def razRequest (implicit request:Request[_]) = request match {
    case rr : RazRequest => rr
    case r : Request[_] => new RazRequest(request)
  }

  // allow calling old methods from new code
  implicit def xxx (implicit request:RazRequest) : Request[_] = request.ireq

  implicit def txn (implicit request:RazRequest) : Txn = request.txn

  def rhRequest (implicit request:RequestHeader) = request match {
    case rr : RazRequest => rr
    case r : Request[_] => new RazRequest(r)
    case _ => throw new IllegalArgumentException("")
  }

  implicit def errCollector (implicit request : RazRequest) = request.errCollector

//  object BetterFAUR extends ActionBuilder[BetterRazRequest] with ActionTransformer[Request, BetterRazRequest] {
//    def transform[A](request: Request[A]) = Future.successful {
//      new BetterRazRequest(request)
//    }
//  }

  //================ override logging, to audit as well

  override def error (message: => String) = Audit.logdb("ERR_?", message)

  def error (code:String, message: => String) (implicit errCollector: VErrors = IgnoreErrors) =
    Audit.logdb(code, message + " : " + errCollector.mkString)

  def verror (message: => String) (implicit errCollector: VErrors = IgnoreErrors) =
    Audit.logdb("ERR_?", message + " : " + errCollector.mkString)

  /** clean the cache for current user - probably a profile change */
  def cleanAuth (u: Option[User] = None)(implicit request: RequestHeader) {
    val au = u.orElse(xauth(request))
    Services ! WikiEvent("AUTH_CLEAN", "User", au.map(_._id).mkString, au)
    Services.auth.cleanAuth(au)(request)
  }

  /** @deprecated see auth */
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

  def realmOf(implicit request: Request[_]): String = {
    val au = request match {
      case rr : RazRequest => rr.realm
      case r:RequestHeader => Website.getRealm(request)
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

  /** no permission */
  def noPerm(
              wid: WID,
              more: String = "",
              shouldAudit: Boolean = true,
              teaser:String = "",
              ostok:Option[RazRequest]=None,
              showContinue:Boolean=true
            )(implicit request: Request[_], errCollector: VErrors = IgnoreErrors) = {

    val stok = ostok.getOrElse(rhRequest)

    if (errCollector.hasCorrections) {
      val uname = auth.map(_.userName).getOrElse(if(isFromRobot) "ROBOT" else "")
      val r = realmOf
      val uperm = auth.map(_.forRealm(r)).map(_.membershipLevel).getOrElse("none")
      val member = if(uperm == "none") "" else auth.filter(_.asInstanceOf[User].realms.contains(r)).map(x=> "and you are member").getOrElse("but you are NOT a member of " + r)
      val exp = if(uperm == "none") "" else auth.filter(_.hasMembershipLevel(Perm.Expired.s)).map(x=> "but it is expired").getOrElse("and it is in good standing")
      val msg = Website.get.prop("msg.err.noPerm").getOrElse(s"Sorry, you don't have enough karma! (your membership level is $uperm $member $exp)")

      if (shouldAudit)
        Audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format(uname, wid.toString, more + " " + errCollector.mkString, request.headers))

      if(teaser.isEmpty)
        Unauthorized(
          views.html.util.reactorLayout12(
          views.html.util.utilMsg(
            s""" <span style="color:red">$msg</span>""",
            s"""
               |${errCollector.mkString}
               |>> $more
               |""".stripMargin +
                md("Admin:Unauthorized"),
            (if (showContinue) Some(wid.w.toString) else None),
            auth
          )(stok), Seq.empty)(stok))
      else
      // with teasers, don't send the Unauthorized answer anymore
        Ok(
          views.html.util.reactorLayout12(
            views.html.util.utilMsg(
              teaser,
              s"""
                 |$more
                 |""".stripMargin +
                  md("Admin:Unauthorized"),
              Some(wid.w.toString), auth)(stok), Seq.empty)(stok))
    } else
      noPermOLD(wid, more + " " + errCollector.mkString)
  }

  /** format a page as String */
  private def md (wpath:String)(implicit request: RequestHeader) : String =
    WID.fromPath(wpath).map(_.r(rhRequest(request).realm)).flatMap(_.page).map{p=>
      Wikis.format(p, None)
    }.getOrElse("")

  /** no permission */
  private def noPermOLD(wid: WID, more: String = "")(implicit request: Request[_]) = {
//    implicit val stok = razRequest
    Audit.auth(
      "BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), wid.toString,
        more, request.headers))
    val r = realmOf
    val uperm = auth.map(_.forRealm(r)).map(_.membershipLevel).getOrElse("none")
    val member = if (uperm == "none") "" else auth.filter(_.asInstanceOf[User].realms.contains(r)).map(
      x => "and you are member").getOrElse("but you are NOT a member of " + r)
    val exp = if (uperm == "none") "" else auth.filter(_.hasMembershipLevel(Perm.Expired.s)).map(
      x => "but it is expired").getOrElse("and it is in good standing")
    val msg = Website.get.prop("msg.err.noPerm").getOrElse(s"Sorry, you don't have enough karma! (your membership " +
        s"level is $uperm $member $exp)")
    Unauthorized(
      views.html.util.reactorLayout12(
        views.html.util.utilMsg(
          s""" <span style="color:red">$msg</span>""",
          s"""

>> $more

If you got this message in error, please describe the issue in a <a href="/doe/support?desc=No+permission">support
request</a> and we'll take care of it! Thanks!

""" + md("Admin:Unauthorized"), Some(wid.w.toString), auth)(rhRequest), Seq.empty)(rhRequest))
  }

  def unauthorized(more: String = "", shouldAudit:Boolean=true)(implicit request: RequestHeader, errCollector: VErrors = IgnoreErrors) = {
    //    implicit val stok = razRequest
    if(shouldAudit)
      Audit.unauthorized(
        "BY %s - Info: %s PATH: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), /*more + " " + */
          errCollector.mkString, request.path, request.headers))
    else
      log("UNAUTHORIZED BY %s - Info: %s PATH: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.path, request.headers))

    val details = s"""
<span style="color:red">
${errCollector.mkString}
</span>
<br><br>
"""

    Unauthorized(
      views.html.util.reactorLayout12(
        views.html.util.utilMsg(
          more,
          details+md("Admin:Unauthorized"), None, xauth)(rhRequest), Seq.empty)(rhRequest))
        .withHeaders("details" -> details.replaceAllLiterally("\n", ";"))
  }

  /** some error */
  def Oops(msg: String, wid: WID)(implicit request: Request[_]) = {
    //    implicit val stok = razRequest
    error(msg)
    Ok(
      views.html.util.reactorLayout12(
        views.html.util.utilErr(msg, wid.w)(rhRequest), Seq.empty)(rhRequest)
    )
  }

  def unauthorizedPOST(more: String = "", shouldAudit:Boolean=true)(implicit request: RequestHeader, errCollector: VErrors = IgnoreErrors) = {
//    implicit val stok = razRequest
    if(shouldAudit)
      Audit.unauthorized("BY %s - Info: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))
    else
      log("UNAUTHORIED: BY %s - Info: %s HEADERS: %s".format((xauth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))

    Unauthorized(errCollector.mkString)
  }

  /** message with a continuation to another page */
  def Msg(msg: String, wid: WID, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.Result = {
    Msg2(msg, Some(wid.w), if (u.isDefined) u else auth)(request)
  }

  /** simple message, no continuation */
  def Msg(msg: String)(implicit request: Request[_]): play.api.mvc.Result = {
    Msg(msg, "")
  }

  /** simple message with details, no continuation */
  def Msg(msg: String, details:String)(implicit request: RequestHeader): play.api.mvc.Result = {
    Ok(
      views.html.util.reactorLayout12(
      views.html.util.utilMsg(msg, details, None, xauth)(rhRequest)
      , Seq.empty)(rhRequest))
  }

  def Msg2C(msg: String, page: Option[Call], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.Result = {
    Ok(
      views.html.util.reactorLayout12(
      views.html.util.utilMsg(msg, "", page.map(_.toString), if (u.isDefined) u else auth)(rhRequest)
      , Seq.empty)(rhRequest))
  }

  // Reactor OK - understands reactor
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
  def vBadWords: Constraint[String] = Constraint[String]("constraint.noBadWords") { o =>
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

  //========= filters

  // Filter(noRobots) {...}
  def noRobots (stok:RazRequest) : Option[Result] = {
    if(isFromRobot(stok.req)) Some(Unauthorized("you're a robot"))
    else None
  }

  // Filter(noRobots) {...}
  def activeUser (stok:RazRequest) : Option[Result] = {
    if(! stok.au.exists(a=> checkActive(a).exists(_ == true)))
      Some(Unauthorized("Need to login / have an active account"))
    else None
  }


  def isMod (stok:RazRequest) : Option[Result] = {
    if(! stok.au.exists(a=> a.isMod))
      Some(Unauthorized("You are not a mod"))
    else None
  }

  def adminUser (stok:RazRequest) : Option[Result] = {
    if(! stok.au.exists(a=> checkActive(a).exists(_ == true) && a.isAdmin))
      Some(Unauthorized("Need to have an active admin account"))
    else None
  }

  /** mock teh action filters */
  class FilteredAction (val filter: RazRequest => Option[Result]) {
    def apply(f: RazRequest => Result) : Action[AnyContent] = RAction {implicit request=>
      val req = razRequest
      filter(req).map {res=>
        res
      } getOrElse {
        f(req)
      }
    }

    def async(f: RazRequest => Future[Result]) : Action[AnyContent] = RAction.async {implicit request=>
      filter(request).map {res=>
        Future.successful(res)
      } getOrElse {
        f(request)
      }
    }

    def async[A](bodyParser: BodyParser[A]) (f: RazRequest => Future[Result]) : Action[A] = RAction(bodyParser).async {implicit request=>
      filter(request).map {res=>
        Future.successful(res)
      } getOrElse {
        f(request)
      }
    }
  }

  /** mock teh action filters */
  def Filter(f: RazRequest => Option[Result]) = new FilteredAction(f)

  /** mock teh action filters */
  class RazAction[A] (val bodyParser: BodyParser[A], auth:Boolean = false, noRob:Boolean=false) {
    protected def isFromRobot(implicit request: RequestHeader) = {
      (request.headers.get("User-Agent").exists(ua => Services.config.robotUserAgents.exists(ua.contains(_))))
    }

    def doAuth(req:RazRequest):Option[Future[Result]] = {
      if(auth && req.au.isEmpty) {
        Some(Future.successful{
          Unauthorized("Need to login...")
        })
      } else if(noRob && isFromRobot(req.oreq.get)) {
        Some(Future.successful{
          Unauthorized("eh, robots...")
        })
      } else
      None
    }

    def apply(f: RazRequest => Result) : Action[A] = async {req=>
      doAuth(req).getOrElse {
        Future.successful(f(req))
      }
    }

    def async(f: RazRequest => Future[Result]) : Action[A] = Action.async(bodyParser) {implicit request=>
      val req = razRequest
      doAuth(req).getOrElse{
        val temp = f(req)
        // only if someone used it
        if (req.isTxnSet) req.txn.commit
        temp
      }
    }

    def withAuth =
      new RazAction(bodyParser, true, noRob)

    def noRobots =
      new RazAction(bodyParser, auth, true)
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def RAction = new RazAction (BodyParsers.parse.default)
  def RAction[A] (bodyParser: BodyParser[A])() = new RazAction (bodyParser)

  /** for active user */
  def FAUR(f: RazRequest => Result) : Action[AnyContent] =
    FAUR("")(r=> Some(f(r)))

  /** not truly member - also include base realms. Not good for auth everywhere, but import/diff should be allowed */
  def deemedMemberOfRealm(u:User, realm:String) = u.realms.contains(realm) || "wiki" == realm || "specs" == realm || "rk" == realm

  /** for active user */
  def FAUR(msg:String, isApi:Boolean=false)(f: RazRequest => Option[Result]) : Action[AnyContent] = Action { implicit request =>
    val req = razRequest
    var isAuth = true
    (for (
      au <- req.au.filter(x => x.isInRealm(req.realm) || deemedMemberOfRealm(x, req.realm))
    ) yield {
        if(msg.nonEmpty) cdebug << "START_FAU "+msg
        val temp = f(req)
        if(temp.isEmpty) isAuth = false
        req.txn.commit
        temp
    }
    ).flatten getOrElse {
      val more =
        if(isAuth) Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).getOrElse("No permission - you have to login!")
        else "No result!"

      if(isApi)
        if(isAuth) Unauthorized(s"OOPS $more [$msg]" + req.errCollector.mkString)
        else NotFound(s"OOPS $more [$msg]" + req.errCollector.mkString)
      else
        unauthorized(s"OOPS $more [$msg]", !isFromRobot)(request, req.errCollector)
    }
  }

  /** for active user */
  def FAU(f: User => VErrors => Request[AnyContent] => Result) : Action[AnyContent] =
    FAU(""){u:User => e:VErrors => r:Request[AnyContent] => Option(f(u)(e)(r))}

  /** for active user */
  def FAU(msg:String, isApi:Boolean=false, checkRealm:Boolean=true)(f: User => VErrors => Request[AnyContent] => Option[Result]) : Action[AnyContent] = Action { implicit request =>
    val req = razRequest
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      _ <- Some(au).filter(x=> !checkRealm || x.isInRealm(req.realm))
    ) yield {
      if(msg.nonEmpty) cdebug << "START_FAU "+msg
      val temp = f(au)(errCollector)(request)
      temp
    }
    ).flatten getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      if(isApi)
        Unauthorized(s"OOPS $more [$msg]" + errCollector.mkString)
      else
        unauthorized(s"OOPS [$msg] $more ", !isFromRobot)
    }
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FAUPRAPI(isApi:Boolean=false, checkRealm:Boolean=true)(f: RazRequest => Result) = Action { implicit request =>
    implicit val stok = new RazRequest(request)
    (for (
      au <- activeUser(stok.ireq, stok.errCollector);
      _ <- Some(au).filter(!checkRealm || deemedMemberOfRealm(_, stok.realm)) // MUST USE DEEMED here - see AdminImport.isu
    ) yield f(stok)
      ) getOrElse {
      val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
      if(isApi)
        Unauthorized("You need more karma... " + stok.errCollector.mkString)
      else
        unauthorized(s"OOPS [] $more ", !isFromRobot)
    }
  }

  // todo enhance this - collect robot suspicions and store them in a proposal table
  protected def isFromRobot(implicit request: RequestHeader) = {
    (request.headers.get("User-Agent").exists(ua => Services.config.robotUserAgents.exists(ua.contains(_))))
  }

  val HOME = WID("Admin", "home")

}

object RkViewService extends RazController with ViewService {
  def utilMsg(msg: String, details: String, link: Option[String], user: Option[WikiUser], linkNO: Option[(String, String)] = None)(implicit request: RequestHeader): play.api.mvc.Result = {
    //    implicit val stok = razRequest
    Ok(
      views.html.util.reactorLayout12(
        views.html.util.utilMsg(msg, details, link, user.map(_.asInstanceOf[User]) orElse xauth, linkNO)(rhRequest),
        Seq.empty)(rhRequest)
    )
  }

  def md (wpath:String)(implicit request: RequestHeader) : String =
    WID.fromPath(wpath).map(_.r(rhRequest(request).realm)).flatMap(_.page).map{p=>
      Wikis.format(p, None)
    }.getOrElse("")
}


