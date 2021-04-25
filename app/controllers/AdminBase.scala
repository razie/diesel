/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import model.User
import play.api.mvc.{Action, AnyContent, Request, Result}
import play.twirl.api.Html
import razie.wiki.Config
import razie.wiki.model.Perm

/** admin utilities shared by admin controllers */
class AdminBase extends RazController {
  protected def forAdmin[T](body: => play.api.mvc.Result)(implicit request: Request[_]) = {
    if (auth.map(_.hasPerm(Perm.adminDb)) getOrElse false || Config.isLocalhost && Config.trustLocalUsers) body
    else noPerm(HOME)
  }

  protected def FA[T](body: Request[_] => play.api.mvc.Result) = Action { implicit request =>
    forAdmin {
      body(request)
    }
  }

  def FAD(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      can <- au.hasPerm(Perm.adminDb) ||
          Config.isLocalhost && au.hasPerm(Perm.Moderator) ||
          Config.isLocalhost && Config.trustLocalUsers orErr "no permission"
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
      can <- au.hasPerm(Perm.adminDb) || Config.isLocalhost && Config.trustLocalUsers orErr "no permission"
    ) yield {
        f(req)
    }) getOrElse unauthorized("CAN'T")
  }

  // use my layout
  implicit class StokAdmin (s:StateOk) {
    def admin (content: StateOk => Html) = {
      RkViewService.Ok (views.html.admin.adminLayout(content(s))(s))
    }
  }
}
