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

/** common razie controller utilities */
class RazController extends Controller with Logging {

  final val SUPPORT = "support@racerkidz.com"
    
  //================ encription
  case class EncryptedS(s: String) {
    def enc = Users.enc(s)
    def dec = Users.dec(s)
    def encBase64 = Base64Codec.encode(s)
    def decBase64 = Base64Codec.decode(s)
  }
  implicit def toENCR(o: String) = { EncryptedS(o) }

  //=================== collecting errors 
  class Error(var err: List[Corr] = Nil) {
    def add(s: Corr) = { err = err ::: s :: Nil; s }
    def add(s: String) = { err = err ::: Corr(s) :: Nil; s }
    def reset { err = Nil }

    def mkString = err.mkString(",")
  }

  case class Corr(err: String, action: Option[String] = None) {
    def this(e: String, l: String) = this (e, Some(l))
    override def toString = "[" + err + action.map(" -> " + _).getOrElse("") + "]"
  }

  final val cLogin = new Corr("Not logged in", "login")
  final val cExpired = new Corr("token expired", "get another token")
  final val cNoProfile = InternalErr("can't load the user profile")

  def dbop(r: WriteResult) = log("DB_RESULT: " + r.getError)

  object InternalErr {
    def apply(err: String) = Corr (err, Some("create a suppport request"))
  }

  def Nope(msg: String) = { error(msg); None }

  case class OptNope[A](o: Option[A]) {
    def orErr(msg: String)(implicit errCollector: Error = new Error): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg)) }
    def orCorr(msg: Corr)(implicit errCollector: Error = new Error): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg).err) }
  }
  implicit def toON[A](o: Option[A]) = { OptNope[A](o) }
  implicit def toON2(o: Boolean) = { OptNope(if (o) Some(o) else None) }

  //================= auth
  def auth(implicit request: Request[_]): Option[User] = {
    debug ("AUTH connected=" + request.session.get("connected"))
    request.session.get("connected").flatMap (Api.findUser(_))
  }

  def hasPerm(p: String)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  def noPerm(c: String, n: String, more:String="")(implicit request: Request[_]) = {
    Audit.auth("wiki user permission failed: %s:%s %s".format(c,n,more))
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
    
  def spec (s:String) = s.contains('<') || s.contains('>')
}
