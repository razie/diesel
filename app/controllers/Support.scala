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
import razie.cout
import org.joda.time.DateTime
import com.novus.salat.grater
import admin.Audit
import db.RazSalatContext.ctx
import db.Mongo

/** support features */
object Support extends RazController with Logging {

  case class Support(
    email: String,
    desc: String,
    closed: Boolean,
    resolution: String,
    createdDtm: DateTime = DateTime.now,
    solvedDtm: Option[DateTime] = None) {

    def create = Mongo("Support") += grater[Support].asDBObject(Audit.create(this))

    //  def close (resolution:String) = {
    //val newOne = Support(
    //  email, desc, closed=true,
    //  resolution,
    //  createdDtm=createdDtm,
    //  solvedDtm=Some(DateTime.now))
    //
    //    Mongo("Support").m.update(key, grater[Support].asDBObject(Audit.update(this)))
    //  }
  }

  def supportForm1 = Form {
    tuple(
      "email" -> nonEmptyText.verifying(vEmail, vSpec),
      "desc" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying(vPorn, vSpec),
      "v1" -> number,
      "v2" -> number,
      "details" -> text)
  }

  def support(desc: String, details: String) = Action { implicit request =>
    import model.Sec._
    val v2 = (10 + math.random * 11).toInt
    Ok(views.html.admin.support(supportForm1.fill((
      auth.map(_.email.dec).getOrElse(""),
      if (desc.length <= 0) "Oops!" else desc,
      0,
      v2,
      details)), v2, auth))
  }

  def supportu = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.admin.support(formWithErrors, 21, auth)),
      {
        case t @ (e, desc, v1, v2, details) => {
          cout << t
          if (v1 == v2 && v2 > 1 || v1 == 21 || auth.exists(_.isActive)) {
            Emailer.withSession { implicit mailSession =>
              Emailer.sendSupport(e, (auth.map("Username: "+_.userName+" ").mkString)+desc, details)
            }
            Msg("Ok - support request sent. We will look into it asap.", HOME)
          } else
            Msg("Either your math is bad or you're a robot...", HOME)
        }
      })
  }

}
