/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import model.User
import play.api.mvc.{Action, AnyContent, Request, Result}
import play.twirl.api.Html
import razie.wiki.{Config, Services}
import razie.wiki.model.Perm

/** admin utilities shared by admin controllers */
class AdminBase extends RazController {

  /** for admins only */
  protected def forAdmin[T](body: => play.api.mvc.Result)(implicit request: Request[_]) = {
    val au = auth
    if (
      (au.map(_.isAdmin) getOrElse false) ||
          au.isDefined && Services.config.isLocalhost && Config.trustLocalUsers // trust any local user as if they're admin?
    ) body
    else noPerm(HOME)
  }

  /** for admins only */
  protected def FA[T](body: Request[_] => play.api.mvc.Result) = Action { implicit request =>
    forAdmin {
      body(request)
    }
  }

  /** for mods only */
  def FAM(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      can <- au.hasPerm(Perm.Moderator) ||
          Services.config.isLocalhost && Config.trustLocalUsers orErr "no permission"
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse {
      log("UNAUTHORIZED MOD USER: " +
          activeUser.map(_.userName).mkString + " - " +
          activeUser.map(_.perms).mkString)
      unauthorized("CAN'T")
    }
  }

  /** for admins only or mods in local */
  def FAD(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      can <- au.isAdmin ||
          Services.config.isLocalhost && Config.trustLocalUsers orErr "no permission"
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse {
      log("UNAUTHORIZED ADMIN USER: " +
          activeUser.map(_.userName).mkString + " - " +
          activeUser.map(_.perms).mkString)
      unauthorized("CAN'T")
    }
  }

  /** for admin user */
  def FADR(f: RazRequest => Result) = Action { implicit request =>
    val req = razRequest
    (for (
      au <- req.au;
      isA <- checkActive(au);
      can <- au.isAdmin || Services.config.isLocalhost && Config.trustLocalUsers orErr "no permission"
    ) yield {
        f(req)
    }) getOrElse unauthorized("CAN'T")
  }

  /** use special admin layout when you use the "admin" below */
  implicit class StokAdmin (s:StateOk) {
    def admin (content: StateOk => Html) = {
      RkViewService.Ok (views.html.admin.adminLayout(content(s))(s))
    }
  }
}
