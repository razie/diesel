package controllers

import admin.Audit
import admin.VError
import model.DoSec
import play.api.mvc.Action
import model.Users

/** main entry points */
object Application extends RazController {

  def index = doeIndexItem(1)

  def doeIndexItem(i: Int) = Action { implicit request =>
    Ok(views.html.index("", auth, i, session.get("mobile").isDefined))
  }

  // login as harry p.
  def doeHarry = Action { implicit request =>
    (for (u <- Users.findUserById("4fdb5d410cf247dd26c2a784")) yield {
      Redirect("/").withSession("connected" -> u.email)
    }) getOrElse Msg2("Can't find Harry Potter - sorry!")
  }

  def doeSpin = Action { implicit request =>
    Msg2("You can take me for a spin, pretending you are Harry Potter :) \n\n When you're done, if you want to create an account, just sign out first. \n\n Ready?", Some("/doe/harry"))
  }

  def mobile(m: Boolean) = Action { implicit request =>
    Redirect ("/").withSession (
      if (m) session + ("mobile" -> "yes")
      else session - "mobile")
  }

  def show(page: String) = {
    page match {
      case "index"   => index
      case "profile" => Profile.profile
      case "logout" | "signout" => Action { implicit request =>
        auth map (_.auditLogout)
        Redirect ("/").withNewSession
      }
      case _ => { Audit.missingPage(page); TODO }
    }
  }

  def sec(whats: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    println(whats)
    (for (
      ds <- DoSec.find(whats) orErr "cantfindit";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("expired")
    ) yield Redirect(ds.link)) getOrElse Msg("Link is invalid/expired... " + errCollector.mkString, "Page", "home")
  }

}
