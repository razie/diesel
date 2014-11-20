package controllers

import admin.Audit
import admin.Config
import admin.VErrors
import model.Enc
import model.Perm
import model.User
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.mvc.Action
import play.api.mvc.Request
import razie.cout
import admin.RazAuditService
import admin.SendEmail
import model.Wikis
import play.api.mvc.AnyContent
import play.api.mvc.Result
import java.lang.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import model.WikiScripster
import admin.GlobalData

import omp._

object OmpCtrl extends RazController {

  // routes do/:page
  def show(entity: String, step: Int, id: String) = Action { implicit request =>
    entity match {
      case "step" => Ok(views.html.ompv.ompShow(step, id, Omp.context))
      //	    Redirect("/")
      case _ => unauthorized(s"Unknown page for $entity and $id")
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
	Msg2(formWithErrors.toString + "Oops, can't add that perm!"),
      {
	case we @ AddPerm(perm) =>
	  (for (
	    goodS <- ("+-" contains perm(0)) && Perm.all.contains(perm.substring(1)) orErr ("bad perm")
	  ) yield {
	    Redirect("/razadmin/user/" + id)
	  }) getOrElse {
	    error("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
	    Unauthorized("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
	  }
      })
  }
}
