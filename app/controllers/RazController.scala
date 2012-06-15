package controllers

import admin.Audit
import model.Api
import model.User
import model.Wikis
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import model.Users
import razie.Logging
import com.mongodb.WriteResult
import model.Perm
import admin.Validation
import admin.Config
import play.api.cache.Cache

object RazController extends Logging {
  /** authentication - find the user currently logged in */
  def cleanAuth(u:Option[User]=None)(implicit request: Request[_]) {
    import play.api.Play.current
    request.session.get("connected").map { uid =>
      debug ("AUTH connected=" + uid)
      (if(u.isDefined) u else Api.findUser(uid)).foreach { u =>
        Cache.set(u.email + ".connected", u, 120)
      }
    }
  }
}
/** common razie controller utilities */
class RazController extends Controller with Logging with Validation {

  final val SUPPORT = Config.SUPPORT

  //================ encription

  def dbop(r: WriteResult) = log("DB_RESULT: " + r.getError)

  //================= auth

  /** authentication - find the user currently logged in */
  def auth(implicit request: Request[_]): Option[User] = {
    import play.api.Play.current
    request.session.get("connected").flatMap { uid =>
      Cache.getAs[User](uid + ".connected").map(u => Some(u)).getOrElse {
        debug ("AUTH connected=" + uid)
        Api.findUser(uid).map { u =>
          Cache.set(u.email + ".connected", u, 120)
          u
        }
      }
    }
  }

  def noPerm(cat: String, name: String, more: String = "")(implicit request: Request[_]) = {
    Audit.auth("Permission fail: %s:%s %s HEADERS: %s".format(cat, name, more, request.headers))
    Unauthorized (views.html.util.utilMsg(
      """
Sorry, you don't have the permission to do this! 

You can describe the issue in a support request and we'll take care of it! Thanks!

""" + more, Some(controllers.Wiki.w(cat, name).toString), auth))
  }

  //========= utils
  def Oops(msg: String, cat: String, name: String)(implicit request: Request[_]) = {
    error(msg)
    Ok (views.html.util.utilErr(msg, controllers.Wiki.w(cat, name), auth))
  }

  def Msg(msg: String, cat: String, name: String, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Msg2(msg, Some(controllers.Wiki.w(cat, name)), if (u.isDefined) u else auth)(request)
  }

  def Msg2(msg: String, page: Option[String] = None, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Ok (views.html.util.utilMsg(msg, page, if (u.isDefined) u else auth))
  }

  def vldSpec(s: String) = !(s.contains('<') || s.contains('>'))
  def vldEmail(s: String) = s.matches("[^@]+@[^@]+\\.[^@]+")

  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

}
