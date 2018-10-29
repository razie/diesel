package controllers

import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier

import com.google.inject.Singleton
import com.mongodb.casbah.Imports.DBObject
import com.novus.salat.grater
import mod.notes.controllers.NotesLocker
import model.WikiScripster
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.Action
import razie.audit.Audit
import razie.db.RazMongo
import razie.db.RazSalatContext.ctx
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.{GlobalData, SendEmail}
import razie.wiki.model._

object Admin extends AdminBase {
  // routes do/:page
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
          } else Msg2("Nope - hehe")
        }

        case _ => {
          Audit.missingPage(page);
          Redirect("/")
        }
      }
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
\n
allReactors=${WikiReactors.allReactors.keys.mkString(",")}\n
loadedReactors=${WikiReactors.reactors.keys.mkString(",")}\n
\n
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
