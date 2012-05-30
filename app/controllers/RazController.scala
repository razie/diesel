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
import admin.Base64Codec
import razie.Logging
import com.mongodb.WriteResult
import model.Perm
import admin.Validation
import admin.Config

/** common razie controller utilities */
class RazController extends Controller with Logging with Validation {

  final val SUPPORT = Config.SUPPORT
    
  //================ encription
  case class EncryptedS(s: String) {
    def enc = Users.enc(s)
    def dec = Users.dec(s)
    def encBase64 = Base64Codec.encode(s)
    def decBase64 = Base64Codec.decode(s)
  }
  implicit def toENCR(o: String) = { EncryptedS(o) }

  
  def dbop(r: WriteResult) = log("DB_RESULT: " + r.getError)


  //================= auth
  def auth(implicit request: Request[_]): Option[User] = {
    debug ("AUTH connected=" + request.session.get("connected"))
    request.session.get("connected").flatMap (Api.findUser(_))
  }

  def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  def noPerm(c: String, n: String, more:String="")(implicit request: Request[_]) = {
    Audit.auth("Permission fail: %s:%s %s HEADERS: %s".format(c,n,more, request.headers))
    Unauthorized (views.html.util.utilMsg(
"""
Sorry, you don't have the permission to do this! 

You can describe the issue in a support request and we'll take care of it! Thanks!

""" + more, Some(controllers.Wiki.w(c, n).toString), auth))
  }

  //========= utils
  def Oops(msg: String, cat: String, name: String)(implicit request: Request[_]) = {
    error(msg)
    Ok (views.html.util.utilErr(msg, controllers.Wiki.w(cat, name), auth))
  }

  def Msg(msg: String, cat: String, name: String, u: Option[User] = None)(implicit request: Request[_]) : play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Msg2(msg, Some(controllers.Wiki.w(cat, name)), if (u.isDefined) u else auth)(request)
  }
  
  def Msg2(msg: String, page:Option[String] = None, u: Option[User] = None)(implicit request: Request[_])  : play.api.mvc.SimpleResult[play.api.templates.Html] = {
    Ok (views.html.util.utilMsg(msg, page, if (u.isDefined) u else auth))
  }
    
  def vldSpec (s:String) = !(s.contains('<') || s.contains('>'))
  def vldEmail (s:String) = s.matches("[^@]+@[^@]+\\.[^@]+")
  
  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

}
