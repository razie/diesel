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
import play.api.data.Forms._
import play.api.data._

object Admin extends RazController {
  def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  // routes do/:page
  def show(page: String) = Action { implicit request =>
    page match {
      case "reloadurlmap"    => if (hasPerm(Perm.adminDb)) {
        model.Wikis.reloadUrlMap
        Ok(views.html.admin.admin_index("", auth)) 
      }else noPerm(HOME)
      
      case "wikidx"    => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_wikidx(auth)) else noPerm(HOME)
      
      case "db"    => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_db(auth)) else noPerm(HOME)

      case "index" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_index("", auth)) else noPerm(HOME)

      case "users" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_users(auth)) else noPerm(HOME)

      case "audit" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_audit(auth)) else noPerm(HOME)
      
      case "terms" => Redirect ("/page/Terms_of_Service")
      
      case "join" =>  Redirect ("/doe/join")

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
    if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_user(model.Users.findUserById(id), auth)) else noPerm(HOME)
  }

  def udelete1(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_udelete(model.Users.findUserById(id), auth)) else noPerm(HOME)
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
    } else noPerm(HOME)
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

  case class AddPerm(perm: String)
  val permForm = Form {
    mapping(
      "perm" -> nonEmptyText.verifying(
        "starts with +/-", a=> ("+-" contains a(0))).verifying (
        "known perm", a=> Perm.all.contains(a.substring(1)))
      ) (AddPerm.apply)(AddPerm.unapply) 
        
  }

  def uperm(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    permForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that perm!"),
      {
        case we @ AddPerm(perm) =>
      (for (
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      goodS <- ("+-" contains perm(0)) && Perm.all.contains(perm.substring(1)) orErr ("bad perm");
      u <- Users.findUserById(id);
      pro <- u.profile
    ) yield {
      this dbop pro.update (pro.addPerm(perm))
      Redirect ("/admin/user/" + id)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER " + id)
      Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
    }
      })
  }

  def col(name: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Ok(views.html.admin.admin_col(name, model.Mongo(name).m))
    } else noPerm(HOME)
  }

  def delcoldb(table: String, id:String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      // TODO audit
      Audit.logdb("ADMIN_DELETE", "Table:"+table+" json:"+model.Mongo(table).m.findOne(Map("_id" -> new ObjectId(id))))
      model.Mongo(table).m.remove(Map("_id" -> new ObjectId(id)))
      Ok(views.html.admin.admin_col(table, model.Mongo(table).m))
    } else noPerm(HOME)
  }

  def clearaudit(id: String) = Action { implicit request =>
    if (hasPerm(Perm.adminDb)) {
      Audit.clearAudit(id, auth.get.id)
      Ok(views.html.admin.admin_audit(auth))
    } else noPerm(HOME)

  }

}

case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
                    _id: ObjectId = new ObjectId()) {
  def create = {
    Mongo ("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
