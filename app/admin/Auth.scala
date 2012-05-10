package admin

import play.api.mvc.Request
import model.User
import model.Api

/** all audit events - some of these may end up as emails or alerts.
 *
 *  TODO should have a configurable workflow for each of these - what's the pattern?
 */
object Auth {
  def apply(implicit request: Request[_]): Option[User] =
    request.session.get("connected").flatMap (Api.findUser(_))

  def perm[A](perm: String)(f: => A)(implicit request: Request[_]): Option[A] =
    if (apply(request).map(_.perms.contains(perm)).getOrElse(false))
      Some(f)
    else None

}