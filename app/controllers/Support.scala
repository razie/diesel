package controllers

import com.sun.xml.internal.ws.resources.AddressingMessages
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import razie.{Logging, cdebug, cout}
import com.novus.salat.grater
import razie.db.RazMongo
import razie.db.RazSalatContext.ctx
import org.joda.time.DateTime
import razie.wiki.admin.Audit

/** support features */
object Support extends RazController with Logging {

  case class Support(
    email: String,
    desc: String,
    closed: Boolean,
    resolution: String,
    createdDtm: DateTime = DateTime.now,
    solvedDtm: Option[DateTime] = None) {

    def create = RazMongo("Support") += grater[Support].asDBObject(Audit.create(this))

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
      "name" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying(vPorn, vSpec),
      "desc" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying(vPorn, vSpec),
      "details" -> text,
      "g-recaptcha-response" -> text
    )
  }

  // display the form
  def support(page: String, desc: String, details: String) = Action { implicit request =>
    import razie.wiki.Sec._
    ROK(auth, request) apply {implicit stok=> views.html.admin.support(supportForm1.fill((
      auth.map(_.email.dec).getOrElse(""),
      auth.map(_.ename).getOrElse(""),
      if (desc.length <= 0) "Oops!" else desc,
      details, "")), page)}
  }

  // user submitted form
  def supportu(page: String) = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => ROK(auth, request) badRequest {implicit stok=> views.html.admin.support(formWithErrors, page)},
      {
        case t @ (e, n, desc, details, g_response) => {
          cdebug << t
          if (auth.exists(_.isActive) || Recaptcha.verify2(g_response, clientIp)) {
            Emailer.withSession { implicit mailSession =>
              Emailer.sendSupport("Support request", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - support request sent. We will look into it asap.", HOME)
          } else {
            Audit.logdb("BAD_MATH", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
            Msg("Human verification fail...", HOME)
          }
        }
      })
  }

  def suggest(page: String, desc: String, details: String) = Action { implicit request =>
    import razie.wiki.Sec._
    Ok(views.html.wiki.suggest(supportForm1.fill((
      auth.map(_.email.dec).getOrElse(""),
      auth.map(_.ename).getOrElse(""),
      if (desc.length <= 0) "Oops!" else desc,
      details, "")), page, auth))
  }

  def suggested(page: String) = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.suggest(formWithErrors, page, auth)),
      {
        case t @ (e, n, desc, details, g_response) => {
          cout << t
            if (auth.exists(_.isActive) || Recaptcha.verify2(g_response, clientIp)) {
            Emailer.withSession { implicit mailSession =>
              Emailer.sendSupport("Suggestion", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - question/suggestion sent. We will try to answer it asap.", HOME)
          } else
            Msg("Human verification fail...", HOME)
        }
      })
  }

}
