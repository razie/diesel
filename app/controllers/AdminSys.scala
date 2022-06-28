/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  * */
package controllers

import com.google.inject.Singleton
import mod.notes.controllers.NotesLocker
import play.api.mvc.Action
import razie.db.RazMongo
import razie.diesel.engine.exec.EEDieselExecutors
import razie.hosting.WikiReactors
import razie.wiki.admin.SendEmail

/** some system level admin ops: monitoring, config, pings etc */
@Singleton
class AdminSys extends AdminBase {

  def osusage = {
    import play.api.libs.concurrent.Execution.Implicits.defaultContext

    razie.js.tojsons(
      EEDieselExecutors.dieselPing() ++
          Map(
            "extra" -> Map(
              "notesLockerAutosaved" -> NotesLocker.autosaved,
              "threads" -> defaultContext.toString
            )
          ))
  }

  def system(what: String) = Action { implicit request =>
    forAdmin {
      Ok(osusage).as("application/json")
    }
  }

  val reloadt = System.currentTimeMillis(); // reset when classloader reloads

  def ping2(what: String) = ping(what)

  /** stupid protection against */
  var timingOut = false

  // unsecured ping for
  def ping(what: String) = Action { implicit request =>
    what match {
//      case "script1" => Ok(WikiScripster.impl.runScript("1+2", "js", None, None, Map()))
      case "shouldReload" => {
        Ok(reloadt.toString).as("application/text")
      }
      case "buildTimestamp" => {
        Ok(reloadt.toString).as("application/text")
      }
      case "timeout.please" => {
        if(!timingOut) {
          timingOut = true
          Thread.sleep(60)
          timingOut = false
          RequestTimeout("as asked")
        } else {
          Unauthorized("already timing out")
        }
      }
      case x: String if x.forall(_.isDigit) => {
        Status(x.toInt).apply("as asked")
      }
      case _ => Ok(osusage).as("application/json")
    }
  }


  // TODO turn off emails during remote test
  // todo only for admin?
  def config(what: String) = Action { implicit request =>
    forAdmin {
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

