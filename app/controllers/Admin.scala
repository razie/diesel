package controllers

import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.DBObject
import com.mongodb.casbah.Imports.IntOk
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.novus.salat.grater
import admin.Audit
import admin.Config
import admin.VError
import db.Mongo
import db.RazSalatContext.ctx
import model.Enc
import model.Perm
import model.User
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.mvc.Action
import play.api.mvc.Request
import razie.cout
import admin.RazAuditService
import admin.SendEmail
import model.Wikis

object Admin extends RazController {
  protected def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  protected def forAdmin[T](body: => play.api.mvc.SimpleResult[_])(implicit request: Request[_]) = {
    if (hasPerm(Perm.adminDb)) body
    else noPerm(HOME)
  }

  // routes do/:page
  def show(page: String) = Action { implicit request =>
    page match {
      case "reloadurlmap" => if (hasPerm(Perm.adminDb) || hasPerm(Perm.adminWiki)) {
        Config.reloadUrlMap
        Ok(views.html.admin.admin_index("", auth))
      } else Unauthorized("")

      case "wikidx" => if (hasPerm(Perm.adminDb) || hasPerm(Perm.adminWiki)) Ok(views.html.admin.admin_wikidx(auth)) else Unauthorized("")

      case "db" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_db(auth)) else Unauthorized("")

      case "index" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_index("", auth)) else Unauthorized("")

      case "users" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_users(auth)) else Unauthorized("")

      case "audit" => if (hasPerm(Perm.adminDb)) Ok(views.html.admin.admin_audit(auth)) else Unauthorized("")

      case "terms" => Redirect("/page/Terms_of_Service")

      case "join" => Redirect("/doe/join")

      case "init.db.please" => {
        if (("yeah" == System.getProperty("devmode") && hasPerm(Perm.adminDb)) || !Mongo.db.collectionExists("User")) {
          admin.Init.initDb()
          Redirect("/")
        } else Msg2("Nope - hehe")
      }

      case _ => { Audit.missingPage(page); Redirect("/") }
    }
  }

  def user(id: String) = Action { implicit request =>
    forAdmin {
      Ok(views.html.admin.admin_user(model.Users.findUserById(id), auth))
    }
  }

  def udelete1(id: String) = Action { implicit request =>
    forAdmin {
      Ok(views.html.admin.admin_udelete(model.Users.findUserById(id), auth))
    }
  }

  def udelete2(id: String) = Action { implicit request =>
    forAdmin {
      Mongo.db("User").findOne(Map("_id" -> new ObjectId(id))).map { u =>
        OldStuff("User", auth.get._id, u).create
        Mongo.db("User").remove(Map("_id" -> new ObjectId(id)))
      }
      Mongo.db("Profile").findOne(Map("userId" -> new ObjectId(id))).map { u =>
        OldStuff("Profile", auth.get._id, u).create
        Mongo.db("Profile").remove(Map("userId" -> new ObjectId(id)))
      }
      Redirect("/razadmin")
    }
  }

  def ustatus(id: String, s: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      goodS <- s.length == 1 && ("as" contains s(0)) orErr ("bad status");
      u <- Users.findUserById(id)
    ) yield {
      Profile.updateUser(u, User(u.userName, u.firstName, u.lastName, u.yob, u.email, u.pwd, s(0), u.roles, u.addr, u.prefs, u._id))
      Redirect("/razadmin/user/" + id)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER " + id)
      unauthorized("Oops - cannot update this user... ")
    }
  }

  def su(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth;
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      u <- Users.findUserById(id)
    ) yield {
      Audit.logdb("ADMIN_SU", id)
      Application.razSu = au.email
      Application.razSuTime = System.currentTimeMillis()
      Redirect("/").withSession("connected" -> Enc.toSession(u.email),
          "extra" -> au.email)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER " + id)
      unauthorized("Oops - cannot update this user... ")
    }
  }

  case class UN(uname: String)
  val UNForm = Form {
    mapping("uname" -> nonEmptyText)(UN.apply)(UN.unapply)
  }

  case class AddPerm(perm: String)
  val permForm = Form {
    mapping(
      "perm" -> nonEmptyText.verifying(
        "starts with +/-", a => ("+-" contains a(0))).verifying(
          "known perm", a => Perm.all.contains(a.substring(1))))(AddPerm.apply)(AddPerm.unapply)

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
            // remove/flip existing permission or add a new one?
            this dbop pro.update(
              if (perm(0) == '-' && (pro.perms.contains("+" + perm.substring(1)))) {
                pro.removePerm("+" + perm.substring(1))
              } else if (perm(0) == '+' && (pro.perms.contains("-" + perm.substring(1)))) {
                pro.removePerm("-" + perm.substring(1))
              } else pro.addPerm(perm))
            Redirect("/razadmin/user/" + id)
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER " + id)
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  case class AddQuota(quota: Int)
  val quotaForm = Form {
    mapping(
      "quota" -> number(-1, 1000, true))(AddQuota.apply)(AddQuota.unapply)

  }

  def uquota(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    quotaForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
        case we @ AddQuota(quota) =>
          (for (
            can <- hasPerm(Perm.adminDb) orErr ("no permission");
            u <- Users.findUserById(id);
            pro <- u.profile
          ) yield {
            // remove/flip existing permission or add a new one?
            u.quota.reset(quota)
            Redirect("/razadmin/user/" + id)
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER " + id)
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  def uname(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    UNForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
        case we @ UN(uname) =>
          (for (
            can <- hasPerm(Perm.adminDb) orErr ("no permission");
            u <- Users.findUserById(id);
            pro <- u.profile;
            already <- !(u.userName == uname) orErr "Already updated"
          ) yield {
            // TODO transaction
            Profile.updateUser(u, u.copy(userName = uname))
            Wikis.updateUserName(u.userName, uname)
            cleanAuth(Some(u))

            Redirect("/razadmin/user/" + id)
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER " + id)
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  def col(name: String) = Action { implicit request =>
    forAdmin {
      Ok(views.html.admin.admin_col(name, Mongo(name).m))
    }
  }

  def colTab(name: String, cols: String) = Action { implicit request =>
    forAdmin {
      Ok(views.html.admin.admin_col_tab(name, Mongo(name).m, cols.split(",")))
    }
  }

  def delcoldb(table: String, id: String) = Action { implicit request =>
    forAdmin {
      // TODO audit
      Audit.logdb("ADMIN_DELETE", "Table:" + table + " json:" + Mongo(table).m.findOne(Map("_id" -> new ObjectId(id))))
      Mongo(table).m.remove(Map("_id" -> new ObjectId(id)))
      Ok(views.html.admin.admin_col(table, Mongo(table).m))
    }
  }

  def clearaudit(id: String) = Action { implicit request =>
    forAdmin {
      RazAuditService.clearAudit(id, auth.get.id)
      Redirect("/razadmin/audit")
    }
  }

  def clearauditAll() = Action { implicit request =>
    forAdmin {
      Mongo("Audit").m.find().map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
      Redirect("/razadmin/audit")
    }
  }

  def auditPurge1 = Action { implicit request =>
    forAdmin {
      val map = new scala.collection.mutable.HashMap[(String, String), Int]
      def count(t: String, s: String) = if (map.contains((s, t))) map.update((s, t), map((s, t)) + 1) else map.put((s, t), 1)

      Mongo("AuditCleared").m.find().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("ac", _))
      Mongo("WikiAudit").m.find().map(j => new DateTime(j.get("crDtm"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("w", _))
      Mongo("UserEvent").m.find().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("u", _))
      Ok(views.html.admin.admin_audit_purge1(map, auth))
    }
  }

  final val auditCols = Map("AuditCleared" -> "when", "WikiAudit" -> "crDtm", "UserEvent" -> "when")

  def auditPurge(ym: String) = Action { implicit request =>
    forAdmin {
      val Array(y, m, what) = ym.split("-")
      val (yi, mi) = (y.toInt, m.toInt)
      Ok(Mongo(what).m.find().filter(j =>
        { val d = new DateTime(j.get(auditCols(what))); d.getYear() == yi && d.getMonthOfYear() == mi }).take(20000).toList.map { x =>
        Mongo(what).m.remove(Map("_id" -> x.get("_id").asInstanceOf[ObjectId]))
        x
      }.mkString("\n"))
    }
  }

  def auditReport(d: String, what: Int) = Action { implicit request =>
    forUser { au =>
      val baseline = DateTime.now.minusDays(what)
      def f(j: DateTime) = j != null && (d == "d" && j.isAfter(baseline) || d == "y" && j.dayOfYear.get == baseline.dayOfYear.get)
      val sevents = {
        val events =
          (
            (Mongo("Audit").m.find().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
              ++
              Mongo("AuditCleared").m.find().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList).groupBy(_.get("msg"))).map { t =>
                (t._2.size, t._1)
              }.toList.sortWith(_._1 > _._1)
        events.map(_._1).sum + " Events:\n" +
          events.map(t => f"${t._1}%3d , ${t._2}").mkString("\n")
      }
      val sadds = {
        val adds =
          (
            (Mongo("Audit").m.find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
              ++
              Mongo("AuditCleared").m.find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList)).map(o=>
                ("msg"->o.get("msg"), "who"->o.get("details"), "when"->o.get("when"))).toList
        adds.size + " Adds:\n" +
          adds.mkString("\n")
      }
      val spages = {
        val pages =
          (
            Mongo("WikiAudit").m.find().filter(j => f(j.get("crDtm").asInstanceOf[DateTime])).toList.groupBy(x => (x.get("event"), x.get("wpath")))).map { t =>
              (t._2.size, t._1)
            }.toList.sortWith(_._1 > _._1)
        pages.map(_._1).sum + " Pages:\n" +
          pages.map(t => f"${t._1}%3d , ${t._2._1}, ${t._2._2}").mkString("\n")
      }
      Ok(sevents +
          "\n\n=========================================\n\n" +
          sadds +
          "\n\n=========================================\n\n" +
          spages)
    }
  }

  def config(what: String) = Action { implicit request =>
    forUser { au =>
      what match {
        case "noemails" => Ok(SendEmail.NO_EMAILS.toString)
      }
    }
  }

}

case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {
  def create = {
    Mongo("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
