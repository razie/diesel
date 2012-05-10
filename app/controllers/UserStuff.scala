package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import model.Api
import model.User
import admin.Audit
import model.Users
import model.Registration
import model.RegdEmail
import model.WikiLink
import model.WID

/** profile related control */
object UserStuff extends RazController {

  def task(id: String, name: String) = Action { implicit request =>
    auth match {
      case su @ Some(u) =>
        name match {
          case "addParent" => Profile.addParent
          case _ =>
            Oops ("I don't know yet how to " + name + " !!!", "Page", "home")
        }
      case None =>
        Oops ("Not your task?", "Page", "home")
    }
  }

  def show(email: String, what: String, name: String) = {
    { Audit.missingPage(email + what + name); TODO }
  }

  def wiki(email: String, cat: String, name: String) =
    Wiki.show ("WikiLink", WikiLink(WID("User", email), WID(cat, name), "").wname)

}