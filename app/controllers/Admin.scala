package controllers

import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier

import admin.{Audit, Config, GlobalData, RazAuditService, SendEmail, VErrors}
import com.mongodb.casbah.Imports.{DBObject, IntOk}
import com.mongodb.casbah.commons.MongoDBObject
import com.novus.salat.grater
import db.RazMongo
import db.RazSalatContext.ctx
import model.{Enc, Perm, User, Users, WikiScripster, Wikis}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc.{Action, AnyContent, Request, Result}

object Admin extends RazController {
  protected def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  protected def forAdmin[T](body: => play.api.mvc.SimpleResult)(implicit request: Request[_]) = {
    if (hasPerm(Perm.adminDb)) body
    else noPerm(HOME)
  }

  protected def FA[T](body: Request[_] => play.api.mvc.SimpleResult) = Action { implicit request =>
    forAdmin {
      body(request)
    }
  }

  def FAD(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      can <- au.hasPerm(Perm.adminDb) orErr "no permission"
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T")
  }

  // routes do/:page
  def show(page: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      page match {
	case "reloadurlmap" => {
	  Config.reloadUrlMap
	  Ok(views.html.admin.admin_index("", auth))
	}

	case "resendEmails" => {
	  SendEmail.sender ! SendEmail.CMD_RESEND
	  Ok(views.html.admin.admin_index("", auth))
	}

	case "tickEmails" => {
	  SendEmail.sender ! SendEmail.CMD_TICK
	  Ok(views.html.admin.admin_index("", auth))
	}

	case "wikidx" => Ok(views.html.admin.admin_wikidx(auth))
	case "db" => Ok(views.html.admin.admin_db(auth))
	case "index" => Ok(views.html.admin.admin_index("", auth))
	case "users" => Ok(views.html.admin.admin_users(auth))

	case "init.db.please" => {
	  if ("yeah" == System.getProperty("devmode") || !RazMongo("User").exists) {
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
      db.tx { implicit txn =>
	RazMongo("User").findOne(Map("_id" -> new ObjectId(id))).map { u =>
	  OldStuff("User", auth.get._id, u).create
	  RazMongo("User").remove(Map("_id" -> new ObjectId(id)))
	}
	RazMongo("Profile").findOne(Map("userId" -> new ObjectId(id))).map { u =>
	  OldStuff("Profile", auth.get._id, u).create
	  RazMongo("Profile").remove(Map("userId" -> new ObjectId(id)))
	}
      }
      Redirect("/razadmin")
    }
  }

  def ustatus(id: String, s: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      goodS <- s.length == 1 && ("as" contains s(0)) orErr ("bad status");
      u <- Users.findUserById(id)
    ) yield {
//	Profile.updateUser(u, User(u.userName, u.firstName, u.lastName, u.yob, u.email, u.pwd, s(0), u.roles, u.addr, u.prefs, u._id))
      Profile.updateUser(u, u.copy(status=s(0)))
      Redirect("/razadmin/user/" + id)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id+" "+ errCollector.mkString)
      unauthorized("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id+" "+ errCollector.mkString)
    }
  }

  def su(id: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- auth;
      can <- hasPerm(Perm.adminDb) orErr ("no permission");
      u <- Users.findUserById(id)
    ) yield {
      Audit.logdb("ADMIN_SU", u.userName)
      Application.razSu = au.email
      Application.razSuTime = System.currentTimeMillis()
      Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(u.email),
	"extra" -> au.email)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER su " + id+" "+ errCollector.mkString)
      unauthorized("ERR_ADMIN_CANT_UPDATE_USER su " + id+" "+ errCollector.mkString)
    }
  }

  val OneForm = Form("val" -> nonEmptyText)

  case class AddPerm(perm: String)
  val permForm = Form {
    mapping(
      "perm" -> nonEmptyText.verifying(
	"starts with +/-", a => ("+-" contains a(0))).verifying(
	  "known perm", a => Perm.all.contains(a.substring(1))))(AddPerm.apply)(AddPerm.unapply)

  }

  def uperm(id: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
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
	    error("ERR_ADMIN_CANT_UPDATE_USER uperm " + id+" "+ errCollector.mkString)
	    Unauthorized("ERR_ADMIN_CANT_UPDATE_USER uperm " + id +" "+ errCollector.mkString)
	  }
      })
  }

  val quotaForm = Form(
    "quota" -> number(-1, 1000, true))

  def uquota(id: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    quotaForm.bindFromRequest.fold(
      formWithErrors =>
	Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
	case quota =>
	  (for (
	    can <- hasPerm(Perm.adminDb) orErr ("no permission");
	    u <- Users.findUserById(id);
	    pro <- u.profile
	  ) yield {
	    // remove/flip existing permission or add a new one?
	    u.quota.reset(quota)
	    Redirect("/razadmin/user/" + id)
	  }) getOrElse {
	    error("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id+" "+ errCollector.mkString)
	    Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id+" "+ errCollector.mkString)
	  }
      })
  }

  def uname(id: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>

      OneForm.bindFromRequest.fold(
      formWithErrors =>
	Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
	case uname =>
	  (for (
	    u <- Users.findUserById(id);
	    pro <- u.profile;
	    already <- !(u.userName == uname) orErr "Already updated"
	  ) yield {
	    // TODO transaction
	    db.tx("uname") { implicit txn =>
	      Profile.updateUser(u, u.copy(userName = uname))
	      Wikis.updateUserName(u.userName, uname)
	      cleanAuth(Some(u))
	    }
	    Redirect("/razadmin/user/" + id)
	  }) getOrElse {
	    error("ERR_ADMIN_CANT_UPDATE_USER.uname " + id+" "+ errCollector.mkString)
	    Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uname " + id+" " + errCollector.mkString)
	  }
      })
  }

  def urealms(id: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>

      OneForm.bindFromRequest.fold(
      formWithErrors =>
	Msg2(formWithErrors.toString + "Oops, can't !"),
      {
	case uname =>
	  (for (
	    u <- Users.findUserById(id);
	    pro <- u.profile
	  ) yield {
	    // TODO transaction
	    db.tx("urealms") { implicit txn =>
	      Profile.updateUser(u, u.copy(realms = uname.split("[, ]").toSet))
	      cleanAuth(Some(u))
	    }
	    Redirect("/razadmin/user/" + id)
	  }) getOrElse {
	    error("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id+" "+ errCollector.mkString)
	    Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id+" " + errCollector.mkString)
	  }
      })
  }

  def col(name: String) = Action { implicit request =>
    forAdmin {
      Ok(views.html.admin.admin_col(name, RazMongo(name).m))
    }
  }

  def colTab(name: String, cols: String) = FA { implicit request =>
    Ok(views.html.admin.admin_col_tab(name, RazMongo(name).m, cols.split(",")))
  }

  def delcoldb(table: String, id: String) = FA { implicit request =>
    // TODO audit
    Audit.logdb("ADMIN_DELETE", "Table:" + table + " json:" + RazMongo(table).findOne(Map("_id" -> new ObjectId(id))))
    RazMongo(table).remove(Map("_id" -> new ObjectId(id)))
    Ok(views.html.admin.admin_col(table, RazMongo(table).m))
  }

  def showAudit(msg: String) = FA { implicit request =>
    Ok(views.html.admin.admin_audit(if(msg.length>0)Some(msg) else None)(auth))
  }

  def clearaudit(id: String) = FA { implicit request =>
    RazAuditService.clearAudit(id, auth.get.id)
    Redirect("/razadmin/audit")
  }

  def clearauditSome(howMany:Int) = FA { implicit request =>
    RazMongo("Audit").findAll().sort(MongoDBObject("when" -> -1)).take(howMany).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    Redirect(routes.Admin.showAudit(""))
  }

  def clearauditAll(msg:String) = FA { implicit request =>
    //filter or all
    if (msg.length > 0)
      RazMongo("Audit").find(Map("msg" -> msg)).sort(MongoDBObject("when" -> -1)).take(1000).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    else
      RazMongo("Audit").findAll().sort(MongoDBObject("when" -> -1)).take(1000).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    Redirect("/razadmin/audit")
  }

  def auditPurge1 = FA { implicit request =>
    val map = new scala.collection.mutable.HashMap[(String, String), Int]
    def count(t: String, s: String) = if (map.contains((s, t))) map.update((s, t), map((s, t)) + 1) else map.put((s, t), 1)

    RazMongo("AuditCleared").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("ac", _))
    RazMongo("WikiAudit").findAll().map(j => new DateTime(j.get("crDtm"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("w", _))
    RazMongo("UserEvent").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("u", _))
    Ok(views.html.admin.admin_audit_purge1(map, auth))
  }

  final val auditCols = Map("AuditCleared" -> "when", "WikiAudit" -> "crDtm", "UserEvent" -> "when")

  def auditPurge(ym: String) = Action { implicit request =>
    forAdmin {
      val Array(y, m, what) = ym.split("-")
      val (yi, mi) = (y.toInt, m.toInt)
      Ok(RazMongo(what).findAll().filter(j =>
	{ val d = new DateTime(j.get(auditCols(what))); d.getYear() == yi && d.getMonthOfYear() == mi }).take(20000).toList.map { x =>
	RazMongo(what).remove(Map("_id" -> x.get("_id").asInstanceOf[ObjectId]))
	x
      }.mkString("\n"))
    }
  }

  def auditReport(d: String, what: Int) = Action { implicit request =>
    forAdmin {
      val baseline = DateTime.now.minusDays(what)
      def f(j: DateTime) = j != null && (d == "d" && j.isAfter(baseline) || d == "y" && j.dayOfYear.get == baseline.dayOfYear.get)
      val sevents = {
	val events =
	  (
	    (RazMongo("Audit").findAll().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
	      ++
	      RazMongo("AuditCleared").findAll().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList).groupBy(_.get("msg"))).map { t =>
		(t._2.size, t._1)
	      }.toList.sortWith(_._1 > _._1)
	events.map(_._1).sum + " Events:\n" +
	  events.map(t => f"${t._1}%3d , ${t._2}").mkString("\n")
      }
      val sadds = {
	val adds =
	  (
	    (RazMongo("Audit").find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
	      ++
	      RazMongo("AuditCleared").find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList)).map(o =>
		(o.get("msg"), o.get("details"), o.get("when"))).toList
	adds.size + " Adds:\n" +
	  adds.mkString("\n")
      }
      val spages = {
	val pages =
	  (
	    RazMongo("WikiAudit").findAll().filter(j => f(j.get("crDtm").asInstanceOf[DateTime])).toList.groupBy(x => (x.get("event"), x.get("wpath")))).map { t =>
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

  private def nice(l: Long) =
    if (l > 2L*(1024L*1024L*1024L))
      l / (1024L*1024L*1024L) + "G"
    else if (l > 2*(1024L*1024L))
      l / (1024L*1024L) + "M"
    else if (l > 1024)
      l / 1024 + "K"
    else
      l.toString

  def osusage = {
    var s = ""
    val osm: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    for (method <- osm.getClass().getDeclaredMethods()) {
      method.setAccessible(true);
      if (method.getName().startsWith("get") && Modifier.isPublic(method.getModifiers())) {
	val v = try {
	  method.invoke(osm).toString;
	} catch {
	  case e: Exception => e.toString
	} // try
	val vn = try {
	  v.toLong
	} catch {
	  case e: Exception => -1
	} // try
	s = s + (method.getName() -> (if(vn == -1) v else (nice(vn) + " - "+v))) + "\n";
      } // if
    } // for
    s"""$s\n\nwikis=${RazMongo("WikiEntry").m.size}\n
scriptsRun=${WikiScripster.count}\n
Global.serving=${GlobalData.serving}\n
Global.served=${GlobalData.served}\n
NotesLocker.autosaved=${NotesLocker.autosaved}\n
Global.servedPages=${GlobalData.servedPages}\n
Global.startedDtm=${GlobalData.startedDtm}\n
\n
SendEmail.curCount=${SendEmail.curCount}\n
SendEmail.state=${SendEmail.state}\n
"""
  }

  def system(what: String) = Action { implicit request =>
    forAdmin {
      Ok(osusage)
    }
  }

  // unsecured ping for
  def ping(what: String) = Action { implicit request =>
   what match {
     case "script1" => Ok(WikiScripster.impl.runScript("1+2", None, None, Map()))
     case _ => Ok(osusage)
   }
  }

  // TODO turn off emails during remote test
  def config(what: String) = Action { implicit request =>
    forActiveUser { au=>
      what match {
	case "noemails" => Ok(SendEmail.NO_EMAILS.toString)
	case "noemailstesting" => {
	  SendEmail.NOEMAILSTESTING = true
	  Ok(SendEmail.NOEMAILSTESTING.toString)
	}
	case "okemailstesting" => {
	  SendEmail.NOEMAILSTESTING = false
	  Ok(SendEmail.NOEMAILSTESTING.toString)
	}
      }
    }
  }
}

// TODO should I backup old removed entries ?
case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {
  def create = {
    RazMongo("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
