/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.Validation
import model.WikiUser
import play.api.mvc.Controller
import razie.Logging
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import admin.Services
import razie.NoStaticS

/** used as a threadlocal for the theme */
case class DarkLight(css: String)

/** common razie controller utilities */
class RazControllerBase extends Controller with Logging with Validation {

    /** authentication - find the user currently logged in */
  def wauth(implicit request: Request[_]): Option[WikiUser] = {
    val au = Services.auth.authUser (request)
    au.foreach(u=> NoStaticS.put[WikiUser](u))
    au
  }

  /** result a page to display this message and optionally continue to a page */
  def Msg2(msg: String, page: Option[String], u: Option[WikiUser] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    ViewService.impl.utilMsg(msg, page, if (u.isDefined) u else wauth)
  }

  /** result a page to display this message and optionally continue to a page */
  def Msg3(msg: String, page: Option[String], pageNO:Option[(String,String)], u: Option[WikiUser] = None)(implicit request: Request[_]): play.api.mvc.SimpleResult = {
    ViewService.impl.utilMsg(msg, page, if (u.isDefined) u else wauth, pageNO)
  }
}

/** some views you have to provide for the flow */
trait ViewService {
  def utilMsg (msg:String, link:Option[String], user:Option[WikiUser], link2:Option[(String,String)]=None)(implicit request: Request[_]): play.api.mvc.SimpleResult
}

object ViewService {
  var impl : ViewService = null
}
