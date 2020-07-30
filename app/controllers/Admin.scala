/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier
import com.google.inject.Singleton
import com.mongodb.casbah.Imports.DBObject
import com.novus.salat.grater
import java.nio.file.{Files, Paths}
import mod.diesel.guard.{DieselCron, DomGuardian}
import mod.notes.controllers.NotesLocker
import mod.snow.Regs
import model.{Users, WikiScripster}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.mvc.Action
import razie.audit.Audit
import razie.db.RazMongo
import razie.db.RazSalatContext.ctx
import razie.diesel.utils.DomCollector
import razie.hosting.{Website, WikiReactors}
import razie.wiki.Services
import razie.wiki.admin.{GlobalData, SendEmail}
import razie.wiki.model._
import scala.io.Source

//@Singleton
class Admin extends AdminBase {

  private def escNL(s:String) = s.replaceAllLiterally("\n", " - ").replaceAllLiterally(",", " - ")

  def realmUsers = FAU { implicit au => implicit errCollector => implicit request =>
      val stok = razRequest
        if(au.isAdmin || au.isMod) {
          if(request.getQueryString("format").contains("csv")) {
            val (headers, data) = usersData(stok.realm)
              Ok(
                headers.mkString(",") +
                    "\n" +
                    data.map(
                      _.map(escNL)
                          .mkString(",")
                    ).mkString("\n")
              ).as("text/csv")
          } else {
            ROK.s admin { implicit stok =>
              views.html.admin.adminRealmUsers(stok.realm)
            }
          }
        } else {
          unauthorized("CAN'T")
        }
  }

  def usersData(realm: String): (List[String], List[List[String]]) = {
    val users = Users.findUsersForRealm(realm)
    val cols = "userName,_id,date,email,firstName,lastName,yob,extId,perms".split(",").toList

    // actual rows L[L[String]]
    val res = users.map(_.forRealm(realm)).map { u =>
      List(
        u.userName,
        u._id.toString,
        u.realmSet.get(realm).flatMap(_.crDtm).map(d=>DateTimeFormat.forPattern("yyyy-MM-dd").print(d)).mkString,
        u.emailDec,
        u.firstName,
        u.lastName,
        u.yob.toString,
        u.profile.flatMap(_.newExtLinks.find(_.realm == realm)).map{_.extAccountId}.mkString,
        u.perms.mkString(" ") // not ,
      )
    }.toList

    (cols, res)
  }

  // routes razadmin/page/:page
  def show(page: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      page match {
        case "reloadurlmap" => {
          Services.config.reloadUrlMap
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "send10" => {
          SendEmail.emailSender ! SendEmail.CMD_SEND10
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "stopEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_STOPEMAILS
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "resendEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_RESEND
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "tickEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_TICK
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "wikidx" => ROK.s admin { implicit stok => views.html.admin.adminWikiIndex() }
        case "db" => ROK.s admin { implicit stok => views.html.admin.adminDb() }
        case "index" => ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        case "users" => ROK.s admin { implicit stok => views.html.admin.adminUsers() }

        case "init.db.please" => {
          if ("yeah" == System.getProperty("devmode") || !RazMongo("User").exists) {
            admin.Init.initDb()
            Redirect("/")
          } else Msg("Nope - hehe")
        }

        case _ => {
          Audit.missingPage(page);
          Redirect("/")
        }
      }
  }

  def showImage(file: String) = Action { implicit request =>
    log("Showing image: " + file)
    val f = Files.readAllBytes(Paths.get("/" + file))
    Ok(f).as("image/jpeg")
  }
}

/** admin the audit tables directly */
@Singleton
class AdminSys extends AdminBase {
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

    razie.js.tojsons(Map(
    "wikis" -> RazMongo("WikiEntry").size,
    "scriptsRun" -> WikiScripster.count,
    "Global.serving" -> GlobalData.serving,
    "Global.served" -> GlobalData.served,
    "Global.wikiOptions" -> GlobalData.wikiOptions,
    "NotesLocker.autosaved" -> NotesLocker.autosaved,
    "Global.servedPages" -> GlobalData.servedPages,
    "Global.startedDtm" -> GlobalData.startedDtm,
    "SendEmail.curCount" -> SendEmail.curCount,
    "SendEmail.state" -> SendEmail.state,
    "DieselCron.size" -> DieselCron.withRealmSchedules(_.size),
    "DomGuardian.size" -> DomGuardian.lastRuns.size,
    "DomCollector.size" -> DomCollector.withAsts(_.size),
    "Threads" -> defaultContext.toString,
    "ClusterStatus" -> GlobalData.clusterStatus,
    "allReactors" -> WikiReactors.allReactors.keys.mkString(","),
    "loadedReactors" -> WikiReactors.reactors.keys.mkString(",")
    ))
  }

  def system(what: String) = Action { implicit request =>
    forAdmin {
      Ok(osusage).as("application/json")
    }
  }

  val reloadt=System.currentTimeMillis(); // reset when classloader reloads

  def ping2(what: String) = ping(what)

  // unsecured ping for
  def ping(what: String) = Action { implicit request =>
    what match {
      case "script1" => Ok(WikiScripster.impl.runScript("1+2", "js", None, None, Map()))
      case "shouldReload" => {
        Ok(reloadt.toString).as("application/text")
      }
      case "buildTimestamp" => {
        Ok(reloadt.toString).as("application/text")
      }
      case "timeout.please" => {
        Thread.sleep(60)
        RequestTimeout("as asked")
      }
      case x:String if x.forall(_.isDigit) => {
        Status(x.toInt).apply("as asked")
      }
      case _ => Ok(osusage).as("application/json")
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
}

/** Diff and sync remote wiki copies */
@Singleton
class AdminTest extends AdminBase {
  def test() = FADR { implicit stok=>
      ROK.k admin { implicit stok => views.html.admin.adminTest() }
    }

  def testEmail() = FAD { implicit au => implicit errCollector => implicit request =>
    SendEmail.withSession(Website.realm(request)) { implicit ms =>
      val html1 = ms.text("testBody")
      ms.notif("razie@razie.com", ms.SUPPORT, "TEST email Notify It's " + System.currentTimeMillis(), html1)
      ms.send ("razie@razie.com", ms.SUPPORT, "TEST email Send It's " + System.currentTimeMillis(), html1)
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
}

// TODO should I backup old removed entries ?
case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
                    _id: ObjectId = new ObjectId()) {
  def create = {
    RazMongo("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
