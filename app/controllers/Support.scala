package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import model.Api
import model.Enc
import model.EncUrl
import model.RegdEmail
import model.Registration
import model.User
import model.UserTask
import model.Users
import model.Wikis
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import razie.Logging
import model.ParentChild
import admin.SendEmail
import model.DoSec

/** support features */
object Support extends RazController with Logging {

  def supportForm1 = {
    Form {
      tuple (
        "email" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
        "desc" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying("Invalid characters", vldSpec(_)),
        "details" -> text)
    }
  }

  def support(desc:String,details:String) = Action { implicit request =>
    import model.Sec._
    Ok(views.html.admin.support(supportForm1.fill((
        auth.map(_.email.dec).getOrElse(""), 
        if (desc.length <=0) "Oops!" else desc, 
        details
        )), auth))
  }

  def supportu = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.admin.support(formWithErrors, auth)),
      {
        case (e, desc, details) => {
          Emailer.sendSupport(e, desc, details)
          Msg ("Ok - support request sent. We will look into it asap.", HOME)
        }
      })
  }

}
