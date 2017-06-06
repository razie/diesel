package controllers

import com.google.inject._
import razie.hosting.Website
import play.api.Configuration
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import razie.audit.Audit
import razie.{Logging, cout}

/** support features */
@Singleton
class Support @Inject() (config:Configuration) extends RazController with Logging {

  def supportForm1 = Form {
    tuple(
      "email" -> nonEmptyText.verifying(vEmail, vSpec),
      "name" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying(vBadWords, vSpec),
      "desc" -> nonEmptyText.verifying("Too Long!", _.length < 80).verifying(vBadWords, vSpec),
      "details" -> text,
      "g-recaptcha-response" -> text
    )
  }

  // display the form
  def doeSupport(page: String, desc: String, details: String) = Action { implicit request =>
    import razie.wiki.Sec._
    ROK.r apply {implicit stok=> views.html.admin.support(supportForm1.fill((
      auth.map(_.emailDec).getOrElse(""),
      auth.map(_.ename).getOrElse(""),
      if (desc.length <= 0) "" else desc,
      details, "")), page)}
  }

  // user submitted form
  def supportu(page: String) = RAction { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {implicit stok=> views.html.admin.support(formWithErrors, page)},
      {
        case t @ (e, n, desc, details, g_response) => {
          cdebug << t
          if (auth.exists(_.isActive) || new Recaptcha(config).verify2(g_response, clientIp)) {
            Emailer.withSession(request.realm) { implicit mailSession =>
              mailSession.sendSupport("Support request", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - support request sent. We will look into it asap.", HOME)
          } else {
            Audit.logdb("BAD_MATH", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.req.body).mkString("<br>"))
            Msg("Human verification fail...", HOME)
          }
        }
      })
  }

  def suggest(page: String, desc: String, details: String) = Action { implicit request =>
    import razie.wiki.Sec._
    ROK.r noLayout { implicit stok =>
      views.html.wiki.suggest(supportForm1.fill((
        auth.map(_.emailDec).getOrElse(""),
        auth.map(_.ename).getOrElse(""),
        if (desc.length <= 0) "Oops!" else desc,
        details, "")), page)
    }
  }

  def suggested(page: String) = Action { implicit request =>
    supportForm1.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.suggest(formWithErrors, page)(ROK.r)),
      {
        case t @ (e, n, desc, details, g_response) => {
          cout << t
            if (auth.exists(_.isActive) || new Recaptcha(config).verify2(g_response, clientIp)) {
            Emailer.withSession(Website.getRealm(request)) { implicit mailSession =>
              mailSession.sendSupport("Suggestion", n, e, (auth.map("Username: " + _.userName + " ").mkString) + desc, details, page)
            }
            Msg("Ok - question/suggestion sent. We will try to answer it asap.", HOME)
          } else
            Msg("Human verification fail...", HOME)
        }
      })
  }

}
