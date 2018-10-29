package controllers

import model.User
import play.api.mvc.{Action, AnyContent, Request, Result}
import play.twirl.api.Html
import razie.wiki.model.Perm

class AdminBase extends RazController {
  protected def forAdmin[T](body: => play.api.mvc.Result)(implicit request: Request[_]) = {
    if (auth.map(_.hasPerm(Perm.adminDb)) getOrElse false) body
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
      can <- au.hasPerm(Perm.adminDb) orErr "no permission"
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T")
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FADR(f: RazRequest => Result) = Action { implicit request =>
    val req = razRequest
    (for (
      au <- req.au;
      isA <- checkActive(au);
      can <- au.hasPerm(Perm.adminDb) orErr "no permission"
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
