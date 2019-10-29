package controllers

import omp._
import play.api.mvc.Action

class OmpCtrl extends RazController {

  // routes do/:page
  def show(entity: String, step: Int, id: String) = Action { implicit request =>
    entity match {
      case "step" => Ok(views.html.ompv.ompShow(step, id, Omp.context))
      case _ => unauthorized(s"Unknown page for $entity and $id")
    }
  }
}
