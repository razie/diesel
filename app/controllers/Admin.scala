package controllers

import admin.Audit
import model.Mongo
import model.Perm
import model.User
import play.api.data.Forms._
import play.api.mvc._
import play.api._
import org.bson.types.ObjectId
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import model.Users
import admin.VError

object Admin extends RazController {
  def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  def show(page: String) = Action { implicit request =>
    page match {
      case "db"    => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_db()) else noPerm("Page", "home")

      case "index" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_index("", auth)) else noPerm("Page", "home")

      case "users" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_users(auth)) else noPerm("Page", "home")

      case "audit" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_audit(auth)) else noPerm("Page", "home")

      case "init" => {
        if (("yeah" == System.getProperty("devmode") && hasPerm(Perm.adminDb)) || !Mongo.db.collectionExists("User")) {
          //          Init.initDb()
          Redirect ("/")
        } else Msg2("Nope - hehe")
      }

      case _ => { Audit.missingPage(page); Redirect("/") }
    }
  }

  def user(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_user(model.Users.findUserById(id), auth)) else noPerm("Page", "home")
  }

  def udelete1(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_udelete(model.Users.findUserById(id), auth)) else noPerm("Page", "home")
  }

  def udelete2(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Mongo.db("User").findOne(Map("_id" -> new ObjectId(id))).map { u =>
        OldStuff("User", auth.get._id, u).create
        Mongo.db("User").remove(Map("_id" -> new ObjectId(id)))
      }
      Mongo.db("Profile").findOne(Map("userId" -> new ObjectId(id))).map { u =>
        OldStuff("Profile", auth.get._id, u).create
        Mongo.db("Profile").remove(Map("userId" -> new ObjectId(id)))
      }
      Redirect ("/admin")
    } else noPerm("Page", "home")
  }

  def ustatus(id: String, s: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      goodS <- s.length==1 && ("as" contains s(0)) orErr ("bad status");
      u <- Users.findUserById(id)
    ) yield {
      Profile.updateUser(u, User(u.userName, u.firstName, u.lastName, u.yob, u.email, u.pwd, s(0), u.roles, u.addr, u._id))
      Redirect ("/admin/user/" + id)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER " + id)
      Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
    }
  }

  def col(name: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Ok(views.html.admin.admin_col(name, model.Mongo(name).m))
    } else noPerm("Page", "home")
  }

  def clearaudit(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Audit.clearAudit(id, auth.get.id)
      Ok(views.html.admin.admin_audit(auth))
    } else noPerm("Page", "home")

  }

}

case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
                    _id: ObjectId = new ObjectId()) {
  def create = {
    Mongo ("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}

