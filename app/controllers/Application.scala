package controllers

import admin.Audit
import model.Api
import model.Mongo
import model.Registration
import model.User
import model.UserGroup
import model.Users
import model.WikiEntry
import play.api.data.Forms._
import play.api.mvc._
import play.api._
import admin.Init
import admin.CipherCrypt
import model.DoSec

/** main entry points */
object Application extends RazController {

  def index = Action { implicit request =>
    Ok(views.html.index("", auth))
  }

  def show(page: String) = {
    page match {
      case "index"        => index
      case "join"         => Profile.join
      case "basicprofile" => Profile.basicprofile
      case "profile"      => Profile.profile
      case "logout" | "signout" => Action { implicit request =>
        auth map (_.auditLogout)
        Redirect ("/").withNewSession
      }
      case "terms" => Wiki.show("Page", "Terms_And_Conditions")
      case "privacy" => Wiki.show("Page", "Privacy_Policy")
      case _       => { Audit.missingPage(page); TODO }
    }
  }

  def sec(whats: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    println(whats)
    (for (
      ds <- DoSec.find(whats) orErr "cantfindit";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("expired")
    ) yield Redirect(ds.link)
    ) getOrElse Msg("Link is invalid/expired... " + errCollector.mkString, "Page", "home")
  }

}
