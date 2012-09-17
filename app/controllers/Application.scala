package controllers

import admin.Audit
import admin.VError
import model.DoSec
import play.api.mvc.Action
import model.Users
import java.io.File
import model.Enc
import play.api.mvc.Request
import model.Wikis

/** main entry points */
object Application extends RazController {

  def root = Action { implicit request =>
    request.headers.get("X-FORWARDED-HOST").flatMap(Wikis.urlfwd(_)).map {host=>
      Redirect (host)
      } getOrElse idoeIndexItem(1)
  }

  def index = doeIndexItem(1)

  def idoeIndexItem (i: Int) (implicit request:Request[_]) = 
    Ok(views.html.index("", auth, i, session.get("mobile").isDefined))
    
  def doeIndexItem(i: Int) = Action { implicit request =>
    idoeIndexItem(i)
  }

  // login as harry p.
  def doeHarry = Action { implicit request =>
    (for (u <- Users.findUserById("4fdb5d410cf247dd26c2a784")) yield {
      Redirect("/").withSession("connected" -> Enc.toSession(u.email))
    }) getOrElse Msg2("Can't find Harry Potter - sorry!")
  }

  def doeSpin = Action { implicit request =>
    Msg2("You can take me for a spin, pretending you are Harry Potter :) \n\n When you're done, if you want to create an account, just sign out first. \n\n Ready?", Some("/doe/harry"))
  }

  def mobile(m: Boolean) = Action { implicit request =>
    Redirect("/").withSession(
      if (m) session + ("mobile" -> "yes")
      else session - "mobile")
  }

  def show(page: String) = {
    page match {
      case "index" => index
      case "profile" => Action { implicit request => Redirect("/doe/profile") }
      case "terms" => Action { implicit request => Redirect("/page/Terms_of_Service") }
      case "join" => Action { implicit request => Redirect("/doe/join") }
      case "logout" | "signout" => Action { implicit request =>
        auth map (_.auditLogout)
        Redirect("/").withNewSession
      }
      case _ => { Audit.missingPage(page); TODO }
    }
  }

  def sec(whats: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    println(whats)
    (for (
      ds <- DoSec.find(whats) orErr "cantfindit";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("expired")
    ) yield Redirect(ds.link)) getOrElse Msg("Link is invalid/expired... " + errCollector.mkString, model.WID("Admin", "home"))
  }

  // TODO audit visits to site
  def hosted(site: String, path: String) = controllers.Assets.at("/public/hosted", site + "/" + path)

  // list all the hosted sites
  def hostedAll = Action { implicit request =>
    def link(s: String) = """<a href="/hosted/%s/index.html">%s</a>""".format(s, s)
    Msg2("Assets are: \n" + new File("public/hosted/").list().map(link(_)).mkString("<br>"))
  }
}
