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
import model.WID
import admin.VError
import admin.IgnoreErrors
import model.Enc
import play.api.mvc.CookieBaker
import play.api.libs.Crypto
import model.Base64

object RazController extends Logging {
  import admin.M._

  /** authentication - find the user currently logged in */
  def cleanAuth(u: Option[User] = None)(implicit request: Request[_]) {
    import play.api.Play.current
    request.session.get("connected").map { euid =>
      val uid = Enc.fromSession(euid)
      debug("AUTH connected=" + uid)
      (if (u.isDefined) u else Api.findUser(uid)).foreach { u =>
        Cache.set(u.email + ".connected", u, 120)
      }
    }
  }
  /** authentication - find the user currently logged in */
  def auth(connected: Option[String], authorization: Option[String]): Option[User] = {
    import play.api.Play.current
    debug("AUTH SESSION.connected=" + connected)
    connected.flatMap { euid =>
      val uid = Enc.fromSession(euid)
      Cache.getAs[User](uid + ".connected").map(u => Some(u)).getOrElse {
        debug("AUTH connecting=" + uid)
        Api.findUser(uid).map { u =>
          debug("AUTH connected=" + u)
          Cache.set(u.email + ".connected", u, 120)
          u
        }
      }
    } orElse authorization.flatMap { euid =>
      val e2 = euid.replaceFirst("Basic ", "")
      val e3 = new String(Base64 dec e2) //new sun.misc.BASE64Decoder().decodeBuffer(e2)
      println("xxxxxxxxxxxxxxxxxxxxxxxxxxx " + e3.toString)
      val EP = """([^:]*):(.*)""".r

      val EP(em, pa) = e3

      Api.findUser(Enc(em)).flatMap { u =>
        if (Enc(pa) == u.pwd) {
          u.auditLogin
          val uid = u.id
          debug("AUTH BASIC connected=" + u)
          Some(u)
        } else None
      }
    }
  }
}

/** common razie controller utilities */
class RazController extends Controller with Logging with Validation {

  final val SUPPORT = Config.SUPPORT

  //================ encription

  def dbop(r: WriteResult) = log("DB_RESULT: " + r.getError)

  /** authentication - find the user currently logged in */
  def auth(implicit request: Request[_]): Option[User] = {
    val au = RazController.auth(request.session.get("connected"), request.headers.get("Authorization"))
    val u: User = au.getOrElse(null)
    razie.NoStaticS.put(u)
    au
  }

  //================= auth

  def noPerm(wid: WID, more: String = "")(implicit request: Request[_], errCollector: VError = IgnoreErrors) = {
    if (errCollector.hasCorrections) {
      Audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), wid.toString, more + " " + errCollector.mkString, request.headers))
      Unauthorized(views.html.util.utilMsg(
        """
Sorry, you don't have the permission to do this! 

%s

%s
""".format(errCollector.mkString, more), Some(controllers.Wiki.w(wid).toString), auth))
    } else noPermOLD(wid, more + " " + errCollector.mkString)
  }

  private def noPermOLD(wid: WID, more: String = "")(implicit request: Request[_]) = {
    Audit.auth("BY %s - Permission fail Page: %s Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), wid.toString, more, request.headers))
    Unauthorized(views.html.util.utilMsg(
      """
Sorry, you don't have the permission to do this! 

<font style="color:red">
>> %s
</font>

If you got this message in error, please describe the issue in a <a href="/doe/support">support request</a> and we'll take care of it! Thanks!

""".format(more), Some(controllers.Wiki.w(wid).toString), auth))
  }

  //========= utils

  def unauthorized(more: String = "")(implicit request: Request[_], errCollector: VError = IgnoreErrors) = {
    Audit.unauthorized("BY %s - Info: %s HEADERS: %s".format((auth.map(_.userName).getOrElse("")), more + " " + errCollector.mkString, request.headers))
    Unauthorized(views.html.util.utilMsg(
      """
%s

%s

""".format(more, errCollector.mkString), None, auth))
  }

  def Oops(msg: String, wid: WID)(implicit request: Request[_]) = {
    error(msg)
    Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid), auth))
  }

  def Msg(msg: String, wid: WID, u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Msg2(msg, Some(controllers.Wiki.w(wid, false)), if (u.isDefined) u else auth)(request)
  }

  def Msg2(msg: String)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Ok(views.html.util.utilMsg(msg, None, auth))
  }

  def Msg2(msg: String, page: Option[String], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Ok(views.html.util.utilMsg(msg, page, if (u.isDefined) u else auth))
  }

  def Msg2C(msg: String, page: Option[Call], u: Option[User] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Ok(views.html.util.utilMsg(msg, page.map(_.toString), if (u.isDefined) u else auth))
  }

  def vldSpec(s: String) = !(s.contains('<') || s.contains('>'))
  def vldEmail(s: String) = s.matches("[^@]+@[^@]+\\.[^@]+")

  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

  val HOME = WID("Admin", "home")
}
