package controllers

import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import razie.{Logging, cdebug, cout}
import admin.Audit
import com.novus.salat.grater
import db.RazMongo
import db.RazSalatContext.ctx
import org.joda.time.DateTime

/** support features */
object Support extends RazController with Logging {

  def divi(v2: Int) = {
    val x = (math.random * (v2 - 3)).toInt
    s"$x + ${v2 - x}"
  }

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
      "v1" -> number,
      "v2" -> number,
      "details" -> text)
  }

  // display the form
  def support(page: String, desc: String, details: String) = Action { implicit request =>
    import model.Sec._
    val v2 = (10 + math.random * 11).toInt
    Ok(views.html.admin.support(supportForm1.fill((
      auth.map(_.email.dec).getOrElse(""),
      auth.map(_.ename).getOrElse(""),
      if (desc.length <= 0) "Oops!" else desc,
      0,
      v2,
      details)), v2, page, auth))
  }

  // user submitted form
  def supportu(page: String) = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.admin.support(formWithErrors, 21, page, auth)),
      {
        case t @ (e, n, desc, v1, v2, details) => {
          cdebug << t
          if (v1 == v2 && v2 > 1 || v1 == 21 || auth.exists(_.isActive)) {
            Emailer.withSession { implicit mailSession =>
              Emailer.sendSupport("Support request", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - support request sent. We will look into it asap.", HOME)
          } else {
            Audit.logdb("BAD_MATH", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
            Msg("Either your math is bad or you're a robot...", HOME)
          }
        }
      })
  }

  def suggest(page: String, desc: String, details: String) = Action { implicit request =>
    import model.Sec._
    val v2 = (10 + math.random * 11).toInt
    Ok(views.html.wiki.suggest(supportForm1.fill((
      auth.map(_.email.dec).getOrElse(""),
      auth.map(_.ename).getOrElse(""),
      if (desc.length <= 0) "Oops!" else desc,
      0,
      v2,
      details)), v2, page, auth))
  }

  def suggested(page: String) = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.suggest(formWithErrors, 21, page, auth)),
      {
        case t @ (e, n, desc, v1, v2, details) => {
          cout << t
          if (v1 == v2 && v2 > 1 || v1 == 21 || auth.exists(_.isActive)) {
            Emailer.withSession { implicit mailSession =>
              Emailer.sendSupport("Suggestion", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - question/suggestion sent. We will try to answer it asap.", HOME)
          } else
            Msg("Either your math is bad or you're a robot...", HOME)
        }
      })
  }

}
