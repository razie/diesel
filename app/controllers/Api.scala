/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import com.google.inject._
import play.api.mvc._
import model._
import play.api.{Configuration, Play}
import razie.hosting.Website

@Singleton
class Api @Inject() (val config:Configuration) extends AdminBase {

  class WeRequest[A] (val au:Option[User], val errCollector:VErrors, val request:Request[A])
    extends WrappedRequest[A] (request) {
    def realm = Website.getRealm(request)
  }

  implicit def errCollector (r:WeRequest[_]) = r.errCollector

  def NFAU(f: WeRequest[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
        f(new WeRequest[AnyContent](Some(au), errCollector, request))
      }) getOrElse unauthorized("CAN'T")
  }

  def wix = NFAU {implicit request =>
    Ok(api.wix(None, request.au, Map.empty, request.realm).jsonBrowser).as("text/json")
  }

  def ownedPages(role: String, cat:String) = NFAU {implicit request =>
    Ok(api.wix(None, request.au, Map.empty, request.realm).jsonBrowser).as("text/json")
  }

  def user(id: String) = NFAU { implicit request =>
    ROK.r admin {implicit stok=>
      views.html.admin.adminUser(model.Users.findUserById(id))}
  }
}

