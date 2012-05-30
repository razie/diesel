package controllers

import admin.Audit
import admin.Init
import model.Mongo
import model.Perm
import model.User
import play.api.data.Forms._
import play.api.mvc._
import play.api._

object Admin extends RazController {

  def show(page: String) = Action { implicit request =>
    page match {
      case "db"    => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_db()) else noPerm("Page", "home")

      case "index" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_index("", auth)) else noPerm("Page", "home")

      case "users" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_users(auth)) else noPerm("Page", "home")

      case "audit" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_audit(auth)) else noPerm("Page", "home")

      case "init" => {
        if (("yeah" == System.getProperty("devmode") && hasPerm(Perm.adminDb)) || !Mongo.db.collectionExists("User")) {
          Init.initDb()
          Redirect ("/")
        } else Msg2("Nope - hehe")
      }

      case _ => { Audit.missingPage(page); Redirect("/") }
    }
  }

  def user(id: String) = Action { implicit request =>
    Ok(views.html.admin.admin_user(model.Users.findUserById(id), auth))
  }

  def col(name: String) = Action { implicit request =>
    Ok(views.html.admin.admin_col(name, model.Mongo(name).m))
  }

  def clearaudit(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Audit.clearAudit(id, auth.get.id)
      Ok(views.html.admin.admin_audit(auth))
    } else noPerm("Page", "home")

  }

}
