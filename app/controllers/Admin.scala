package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import model.Api
import model.User
import admin.Audit
import model.Mongo
import admin.Init

object Admin extends RazController {

  def show(page: String) = Action { implicit request =>
    page match {
      case "db"    => if (hasPerm("adminDb")) Ok(views.html.admin.admin_db()) else noPerm("Page", "home")

      case "index" => if (hasPerm("adminDb")) Ok(views.html.admin.admin_index("", auth)) else noPerm("Page", "home")

      case "users" => if (hasPerm("adminDb")) Ok(views.html.admin.admin_users(auth)) else noPerm("Page", "home")

      case "audit" => if (hasPerm("adminDb")) Ok(views.html.admin.admin_audit(auth)) else noPerm("Page", "home")

      case "init" => {
        if (("yeah" == System.getProperty("devmode") && hasPerm("adminDb")) || !Mongo.db.collectionExists("User")) {
          Init.initDb()
          Redirect ("/")
        } else Msg2("Nope - hehe")
      }

      case _ => { Audit.missingPage(page); Redirect("/") }
    }
  }

  def col(name: String) = Action { implicit request =>
    Ok(views.html.admin.admin_col(name, model.Mongo(name).m))
  }

  def clearaudit(id: String) = Action { implicit request =>
    if (hasPerm("adminDb")) {
      Audit.clearAudit(id, auth.get.id)
      Ok(views.html.admin.admin_audit(auth))
    } else noPerm("Page", "home")

  }

}
