package controllers

import admin.Audit
import admin.VError
import model.DoSec
import play.api.mvc.Action

/** main entry points */
object Application extends RazController {

  def index = indexItem(1)

  def indexItem (i:Int) = Action { implicit request =>
    Ok(views.html.index("", auth, i, session.get("mobile").isDefined))
  }

  def mobile(m:Boolean) = Action { implicit request =>
    Redirect ("/").withSession (
        if(m) session + ("mobile" -> "yes")
        else session - "mobile"
        )
  }

  def show(page: String) = {
    page match {
      case "index"        => index
      case "profile"      => Profile.profile
      case "logout" | "signout" => Action { implicit request =>
        auth map (_.auditLogout)
        Redirect ("/").withNewSession
      }
      case _         => { Audit.missingPage(page); TODO }
    }
  }

  def sec(whats: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    println(whats)
    (for (
      ds <- DoSec.find(whats) orErr "cantfindit";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("expired")
    ) yield Redirect(ds.link)) getOrElse Msg("Link is invalid/expired... " + errCollector.mkString, "Page", "home")
  }

}
