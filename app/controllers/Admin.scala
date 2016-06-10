package controllers

import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier
import akka.cluster.Cluster
import com.mongodb.casbah.Imports.{DBObject, IntOk}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.grater
import difflib.DiffUtils
import mod.notes.controllers.NotesLocker
import org.json.{JSONArray, JSONObject}
import play.api.libs.concurrent.Akka
import play.api.libs.json.JsObject
import play.twirl.api.Html
import razie.db.{RMany, RazMongo}
import razie.db.RazSalatContext.ctx
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc.{Action, AnyContent, Request, Result}
import razie.g.snakked
import razie.wiki.util.{VErrors}
import razie.js
import razie.wiki.Enc
import razie.wiki.model.{WikiTrash, WID, WikiEntry, Wikis}
import razie.wiki.admin.{MailSession, GlobalData, Audit, SendEmail}
import admin.RazAuditService
import model.{WikiScripster, Users, Perm, User}
import admin.Config
import x.context

import scala.util.Try
import razie.Snakk._
import razie.wiki.Sec._
import scala.collection.JavaConversions._

object Admin extends RazController {
  protected def hasPerm(p: Perm)(implicit request: Request[_]): Boolean = auth.map(_.hasPerm(p)) getOrElse false

  protected def forAdmin[T](body: => play.api.mvc.Result)(implicit request: Request[_]) = {
    if (hasPerm(Perm.adminDb)) body
    else noPerm(HOME)
  }

  protected def FA[T](body: Request[_] => play.api.mvc.Result) = Action { implicit request =>
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

  // use my layout
  implicit class StokAdmin (s:StateOk) {
    def admin (content: StateOk => Html) = {
      RkViewService.Ok (views.html.admin.adminLayout(content(s))(s))
    }
  }

  // routes do/:page
  def show(page: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      page match {
        case "reloadurlmap" => {
          Config.reloadUrlMap
          ROK() admin  {implicit stok=> views.html.admin.admin_index("")}
        }

        case "resendEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_RESEND
          ROK() admin  {implicit stok=> views.html.admin.admin_index("")}
        }

        case "tickEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_TICK
          ROK() admin  {implicit stok=> views.html.admin.admin_index("")}
        }

        case "wikidx" => ROK() admin  {implicit stok=> views.html.admin.admin_wikidx()}
        case "db" =>     ROK() admin  {implicit stok=> views.html.admin.admin_db()}
        case "index" =>  ROK() admin  {implicit stok=> views.html.admin.admin_index("")}
        case "users" =>  ROK() admin  {implicit stok=> views.html.admin.admin_users()}

        case "init.db.please" => {
          if ("yeah" == System.getProperty("devmode") || !RazMongo("User").exists) {
            admin.Init.initDb()
            Redirect("/")
          } else Msg2("Nope - hehe")
        }

        case _ => {
          Audit.missingPage(page); Redirect("/")
        }
      }
  }

  def user(id: String) = Action { implicit request =>
    forAdmin {
      ROK(auth, request) admin {implicit stok=> views.html.admin.admin_user(model.Users.findUserById(id))}
    }
  }

  def test() = Action { implicit request =>
    forAdmin {
      ROK(auth, request) admin {implicit stok=> views.html.admin.admin_test()}
    }
  }

  def udelete1(id: String) = Action { implicit request =>
    forAdmin {
      ROK(auth, request) admin {implicit stok=> views.html.admin.admin_udelete(model.Users.findUserById(id))}
    }
  }

  def udelete2(id: String) = Action { implicit request =>
    forAdmin {
      razie.db.tx { implicit txn =>
        RazMongo("User").findOne(Map("_id" -> new ObjectId(id))).map { u =>
          WikiTrash("User", u, auth.get.userName, txn.id).create
          RazMongo("User").remove(Map("_id" -> new ObjectId(id)))
        }
        RazMongo("Profile").findOne(Map("userId" -> new ObjectId(id))).map { u =>
          WikiTrash("Profile", u, auth.get.userName, txn.id).create
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
      //        Profile.updateUser(u, User(u.userName, u.firstName, u.lastName, u.yob, u.email, u.pwd, s(0), u.roles, u.addr, u.prefs, u._id))
      Profile.updateUser(u, u.copy(status = s(0)))
      Redirect("/razadmin/user/" + id)
    }) getOrElse {
      error("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id + " " + errCollector.mkString)
      unauthorized("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id + " " + errCollector.mkString)
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
      error("ERR_ADMIN_CANT_UPDATE_USER su " + id + " " + errCollector.mkString)
      unauthorized("ERR_ADMIN_CANT_UPDATE_USER su " + id + " " + errCollector.mkString)
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
      Msg2(formWithErrors.toString + "Oops, can't add that perm!"), {
      case we@AddPerm(perm) =>
        (for (
          can <- hasPerm(Perm.adminDb) orErr ("no permission");
          goodS <- ("+-" contains perm(0)) && Perm.all.contains(perm.substring(1)) orErr ("bad perm");
          u <- Users.findUserById(id);
          pro <- u.profile
        ) yield {
          // remove/flip existing permission or add a new one?
          pro.update(
            if (perm(0) == '-' && (pro.perms.contains("+" + perm.substring(1)))) {
              pro.removePerm("+" + perm.substring(1))
            } else if (perm(0) == '+' && (pro.perms.contains("-" + perm.substring(1)))) {
              pro.removePerm("-" + perm.substring(1))
            } else pro.addPerm(perm))
          Redirect("/razadmin/user/" + id)
        }) getOrElse {
          error("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
          Unauthorized("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
        }
    })
  }

  val quotaForm = Form(
    "quota" -> number(-1, 1000, true))

  def uquota(id: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    quotaForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops, can't add that quota!"), {
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
          error("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id + " " + errCollector.mkString)
          Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id + " " + errCollector.mkString)
        }
    })
  }

  def uname(id: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      OneForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"), {
        case uname =>
          (for (
            u <- Users.findUserById(id);
            pro <- u.profile;
            already <- !(u.userName == uname) orErr "Already updated"
          ) yield {
            // TODO transaction
            razie.db.tx("uname") { implicit txn =>
              Profile.updateUser(u, u.copy(userName = uname))
              Wikis.updateUserName(u.userName, uname)
              cleanAuth(Some(u))
            }
            Redirect("/razadmin/user/" + id)
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
            Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
          }
      })
  }

  def urealms(id: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>

      OneForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't !"), {
        case uname =>
          (for (
            u <- Users.findUserById(id);
            pro <- u.profile
          ) yield {
            // TODO transaction
            razie.db.tx("urealms") { implicit txn =>
              Profile.updateUser(u, u.copy(realms = uname.split("[, ]").toSet))
              cleanAuth(Some(u))
            }
            Redirect("/razadmin/user/" + id)
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id + " " + errCollector.mkString)
            Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id + " " + errCollector.mkString)
          }
      })
  }

  def col(name: String) = Action { implicit request =>
    forAdmin {
      ROK(auth, request) admin {implicit stok=> views.html.admin.admin_col(name, RazMongo(name).findAll)}
    }
  }

  def dbFind(value: String) = FA { implicit request =>
    ROK(auth, request) noLayout  {implicit stok=> views.html.admin.admin_db_find(value)}
  }

  def colEntity(name: String, id: String) = FA { implicit request =>
    ROK(auth, request) admin {implicit stok=> views.html.admin.admin_col_entity(name, id, RazMongo(name).findOne(Map("_id" -> new ObjectId(id))))}
  }

  def colTab(name: String, cols: String) = FA { implicit request =>
    ROK(auth, request) admin {implicit stok=> views.html.admin.admin_col_tab(name, RazMongo(name).findAll, cols.split(","))}
  }

  def delcoldb(table: String, id: String) = FA { implicit request =>
    // TODO audit
    Audit.logdb("ADMIN_DELETE", "Table:" + table + " json:" + RazMongo(table).findOne(Map("_id" -> new ObjectId(id))))
    RazMongo(table).remove(Map("_id" -> new ObjectId(id)))
    ROK(auth, request) admin {implicit stok=> views.html.admin.admin_col(table, RazMongo(table).findAll)}
  }

  def showAudit(msg: String) = FA { implicit request =>
    ROK(auth, request) admin {implicit stok=> views.html.admin.admin_audit(if (msg.length > 0) Some(msg) else None)}
  }

  def clearaudit(id: String) = FA { implicit request =>
    RazAuditService.clearAudit(id, auth.get.id)
    Redirect("/razadmin/audit")
  }

  def clearauditSome(howMany: Int) = FA { implicit request =>
    RazMongo("Audit").findAll().sort(MongoDBObject("when" -> -1)).take(howMany).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    Redirect(routes.Admin.showAudit(""))
  }

  def clearauditAll(msg: String) = FA { implicit request =>
    //filter or all
    if (msg.length > 0)
      RazMongo("Audit").find(Map("msg" -> msg)).sort(MongoDBObject("when" -> -1)).take(1000).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    else
      RazMongo("Audit").findAll().sort(MongoDBObject("when" -> -1)).take(1000).map(_.get("_id").toString).toList.foreach(RazAuditService.clearAudit(_, auth.get.id))
    Redirect("/razadmin/audit#bottom")
  }

  def auditPurge1 = FA { implicit request =>
    val map = new scala.collection.mutable.HashMap[(String, String), Int]
    def count(t: String, s: String) = if (map.contains((s, t))) map.update((s, t), map((s, t)) + 1) else map.put((s, t), 1)

    RazMongo("AuditCleared").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("ac", _))
    RazMongo("WikiAudit").findAll().map(j => new DateTime(j.get("crDtm"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("w", _))
    RazMongo("UserEvent").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("u", _))
    ROK(auth,request) admin {implicit stok=> views.html.admin.admin_audit_purge1(map)}
  }

  final val auditCols = Map("AuditCleared" -> "when", "WikiAudit" -> "crDtm", "UserEvent" -> "when")

  def auditPurge(ym: String) = Action { implicit request =>
    forAdmin {
      val Array(y, m, what) = ym.split("-")
      val (yi, mi) = (y.toInt, m.toInt)
      Ok(RazMongo(what).findAll().filter(j => {
        val d = new DateTime(j.get(auditCols(what))); d.getYear() == yi && d.getMonthOfYear() == mi
      }).take(20000).toList.map { x =>
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

  def auditSummary = {
     RazMongo("Audit").findAll().toList.groupBy(_.get("msg")).map { t =>
       (t._2.size, t._1.toString)
     }.toList.sortWith(_._1 > _._1)
  }

  private def nice(l: Long) =
    if (l > 2L * (1024L * 1024L * 1024L))
      l / (1024L * 1024L * 1024L) + "G"
    else if (l > 2 * (1024L * 1024L))
      l / (1024L * 1024L) + "M"
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
        s = s + (method.getName() -> (if (vn == -1) v else (nice(vn) + " - " + v))) + "\n";
      } // if
    } // for
    import play.api.libs.concurrent.Execution.Implicits.defaultContext
    s"""$s\n\nwikis=${RazMongo("WikiEntry").size}\n
scriptsRun=${WikiScripster.count}\n
Global.serving=${GlobalData.serving}\n
Global.served=${GlobalData.served}\n
Global.wikiOptions=${GlobalData.wikiOptions}\n
NotesLocker.autosaved=${NotesLocker.autosaved}\n
Global.servedPages=${GlobalData.servedPages}\n
Global.startedDtm=${GlobalData.startedDtm}\n
\n
SendEmail.curCount=${SendEmail.curCount}\n
SendEmail.state=${SendEmail.state}\n
\n
Threads=${defaultContext.toString}\n
ClusterStatus=${GlobalData.clusterStatus}\n
"""
  }

  def system(what: String) = Action { implicit request =>
    forAdmin {
      Ok(osusage)
    }
  }

  val reloadt=System.currentTimeMillis(); // reset when classloader reloads

  // unsecured ping for
  def ping(what: String) = Action { implicit request =>
    what match {
      case "script1" => Ok(WikiScripster.impl.runScript("1+2", "js", None, None, Map()))
      case "shouldReload" => {
        Ok(reloadt.toString).as("application/text")
      }
      case _ => Ok(osusage)
    }
  }

  // TODO turn off emails during remote test
  def config(what: String) = Action { implicit request =>
    forActiveUser { au =>
      what match {
        case "noemails" => Ok(SendEmail.NO_EMAILS.toString)
        case "noemailstesting" => {
          SendEmail.NO_EMAILS_TESTNG = true
          Ok(SendEmail.NO_EMAILS_TESTNG.toString)
        }
        case "okemailstesting" => {
          SendEmail.NO_EMAILS_TESTNG = false
          Ok(SendEmail.NO_EMAILS_TESTNG.toString)
        }
      }
    }
  }

  // diffing

  case class WEAbstract (id:String, cat:String, name:String, realm:String, ver:Int, updDtm:DateTime, hash:Int, tags:String) {
    def this (we:WikiEntry) = this(we._id.toString, we.category, we.name, we.realm, we.ver, we.updDtm, we.content.hashCode, we.tags.mkString)
    def j = js.tojson(Map("id"->id, "cat"->cat, "name"->name, "realm"->realm, "ver"->ver, "updDtm" -> updDtm, "hash" -> hash.toString, "tags" -> tags))
  }

  def wput(reactor:String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      Ok("")
  }

  def wlist(reactor:String, hostname:String, me:String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
    if (hostname.isEmpty) {
      val l = RMany[WikiEntry]().filter(we=> reactor.isEmpty || reactor == "all" || we.realm == reactor).map(x=>new WEAbstract(x)).toList
      val list = l.map(_.j)
      Ok(js.tojson(list).toString).as("application/json")
    } else if(hostname != me) {
      val b = body(url(s"http://$hostname/razadmin/wlist/$reactor?me=${request.host}").basic("H-"+au.email.dec, "H-"+au.pwd.dec))
      Ok(b).as("application/json")
    }  else {
      NotFound("same host again?")
    }
  }

  def difflist(reactor:String, target:String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      try {
        val b = body(url(s"http://$target/razadmin/wlist/$reactor").basic("H-"+au.email.dec, "H-"+au.pwd.dec))

        val gd = new JSONArray(b)
        val ldest = js.fromArray(gd).collect {
          case m : Map[_, _] => {
            val x = m.asInstanceOf[Map[String,String]]
            WEAbstract(x("id"), x("cat"), x("name"), x("realm"), x("ver").toInt, new DateTime(x("updDtm")), x("hash").toInt, x("tags"))
          }
        }

        val lsrc = RMany[WikiEntry]().filter(we=> reactor.isEmpty || reactor == "all" || we.realm == reactor).map(x=>new WEAbstract(x)).toList

        val lnew = lsrc.filter(x=> ldest.find(y=> y.id == x.id).isEmpty)
        val lremoved = ldest.filter(x=> lsrc.find(y=> y.id == x.id).isEmpty)

        val lchanged = for(x <- lsrc; y <- ldest if y.id == x.id && (y.ver != x.ver || y.updDtm != x.updDtm))
          yield (x,y, if(x.hash == y.hash && x.tags == y.tags) "-" else if (y.ver < x.ver || y.updDtm.isBefore(x.updDtm)) "L" else "R")

          ROK() admin {implicit stok=> views.html.admin.admin_difflist(reactor, target, lnew, lremoved, lchanged.sortBy(_._3))}
      } catch {
        case x : Throwable => Ok ("error " + x)
      }
  }

  def showDiff(target:String, wid:WID) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      getWE(target, wid).fold({t=>
        val b = t._1.content
        val p = DiffUtils.diff(wid.content.get.lines.toList, b.lines.toList)
        ROK() admin {implicit stok=> views.html.admin.admin_showDiff(wid.content.get, b, p, wid.page.get, t._1)}
      },{err=>
        Ok ("ERR: " + err)
      })
  }

  // create the remote
  def applyDiffCr(target:String, wid:WID) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      try {
        val content = wid.content.get

        val b = body(url(s"http://$target/wikie/setContent/${wid.wpathFull}").form(Map("we" -> wid.page.get.grated.toString)).basic("H-"+au.email.dec, "H-"+au.pwd.dec))

        Ok(b + " <a href=\"" + s"http://$target${wid.urlRelative}" + "\">" + wid.wpath + "</a>")
      } catch {
        case x : Throwable => Ok ("error " + x)
      }
  }

  // to remote
  def applyDiff(target:String, wid:WID) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      try {
        val b = body(url(s"http://$target/wikie/setContent/${wid.wpathFull}").form(Map("we" -> wid.page.get.grated.toString)).basic("H-"+au.email.dec, "H-"+au.pwd.dec))

        if(b contains "ok")
          //todo redirect to list
          Ok(b + " <a href=\"" + s"http://$target${wid.urlRelative}" + "\">" + wid.wpath + "</a>")
        else
          Ok(b + " <a href=\"" + s"http://$target${wid.urlRelative}" + "\">" + wid.wpath + "</a>")
      } catch {
        case x : Throwable => Ok ("error " + x)
      }
  }

  private def getWE(target:String, wid:WID)(implicit au:User):Either[(WikiEntry, String), String] = {
    try {
      val remote = s"http://$target/wikie/json/${wid.wpathFull}"
      val wes = body(url(remote).basic("H-"+au.email.dec, "H-"+au.pwd.dec))

      if(! wes.isEmpty) {
        val dbo = com.mongodb.util.JSON.parse(wes).asInstanceOf[DBObject];
        val remote = grater[WikiEntry].asObject(dbo)
        Left((remote -> wes))
      } else {
        Right("Couldnot read remote content from: " + remote)
      }
    } catch {
      case x : Throwable => {
        Right("error " + x)
      }
    }
  }

  // from remote
  def applyDiff2(target:String, wid:WID) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      getWE(target, wid).fold({t =>
        val b = body(url(s"http://localhost:9000/wikie/setContent/${wid.wpathFull}").form(Map("we" -> t._2)).basic("H-"+au.email.dec, "H-"+au.pwd.dec))
        Ok(b + wid.ahrefRelative)
      }, {err=>
        Ok ("ERR: "+err)
      })
  }

  def testEmail() = FAD { implicit au => implicit errCollector => implicit request =>
    SendEmail.withSession { implicit mailSession =>
      Emailer.tellRaz("Notify It's " + System.currentTimeMillis(), au.userName, "notification")
      Emailer.sendRaz("Send It's " + System.currentTimeMillis(), au.userName, "regular email")
    }
    Ok ("ok")
  }

  def proxyTest(what:String, url:String) = FAD { implicit au => implicit errCollector => implicit request =>
    what match {
      case "" => ""
      case "Joe" => "" //"Authorization": "Basic " + btoa('H-@Dec(stok.au.get.email)', + ":" + 'H-@Dec(stok.au.get.pwd)')
      case "" => ""
    }
    Ok ("")
  }

//  def setContentFromDiff(target:String, wid:WID) = FAD { implicit au =>
//    implicit errCollector => implicit request =>
//      try {
//        val b = body(url(s"http://$target/wikie/content/${wid.wpathFull}").basic("H-"+au.email.dec, "H-"+au.pwd.dec))
//
//        val p = DiffUtils.diff(b.lines.toList, wid.content.get.lines.toList)
//
//        Ok(views.html.admin.admin_showDiff(b, wid.content.get, p)(auth))
//      } catch {
//        case x : Throwable => Ok ("error " + x)
//      }
//  }
}

// TODO should I backup old removed entries ?
case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
                    _id: ObjectId = new ObjectId()) {
  def create = {
    RazMongo("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
