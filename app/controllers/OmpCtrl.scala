package controllers

import admin.Config
import model.User
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.mvc.Action
import play.api.mvc.Request
import razie.cout
import play.api.mvc.AnyContent
import play.api.mvc.Result
import java.lang.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier

import model.WikiScripster
import omp._
import razie.audit.MdbAuditService
import razie.wiki.model.Perm

object OmpCtrl extends RazController {

  // routes do/:page
  def show(entity: String, step: Int, id: String) = Action { implicit request =>
    entity match {
      case "step" => Ok(views.html.ompv.ompShow(step, id, Omp.context))
      case _ => unauthorized(s"Unknown page for $entity and $id")
    }
  }
}
