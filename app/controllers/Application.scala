package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import model.Api
import model.User
import admin.Audit

object Application extends Controller {

  val regForm = Form {
    mapping(
      "email" -> text,
      "password" -> text)(model.Registration.apply)(model.Registration.unapply)
  }

  def auth(implicit request: Request[_]): Option[User] = request.session.get("connected").flatMap (Api.findUser(_))

  def index = Action { implicit request =>
    Ok(views.html.index("", auth))
  }

  def show(page: String) = { //}Action { request =>
    page match {
      case "index" => index
      case "logout" => Action {
        Ok(views.html.join(regForm)).withNewSession
      }
      case "join" => Action {
        Ok(views.html.join(regForm)).withNewSession
      }
      case _ => { Audit.missingPage(page); TODO }
    }
  }

  def register = Action { implicit request =>
    regForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.join(formWithErrors)),
      {
        case reg @ model.Registration(e, p) =>
          Api.createOrFindUser(reg.email) map (u =>
            Ok(views.html.index("", Some(u))).withSession("connected" -> u.email)) getOrElse Unauthorized("WWWWWWWWWW")
      })
  }

}
