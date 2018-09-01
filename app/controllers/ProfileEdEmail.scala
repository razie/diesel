package controllers

import com.google.inject._
import model._
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{tuple, _}
import razie.hosting.Website
import razie.wiki.Sec._
import razie.wiki.model._

/** edit email in profile */
@Singleton
class EdEmail @Inject() (config:Configuration) extends RazController {

  val emailForm = Form {
    tuple(
      "curemail" -> text,
      "newemail" -> text.verifying(vSpec, vEmail)) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByEmailDec(t._2).isDefined })
  }

  // change email
  def doeProfileEmail() = FAUR { implicit request =>
    ROK.k noLayout {
        views.html.user.doeProfileEmail(emailForm.fill(request.au.get.emailDec, ""), auth.get)
      }
  }

  // change email
  def doeProfileEmail2() = FAUR { implicit request =>
    implicit val errCollector = new VErrors()
    emailForm.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {views.html.user.doeProfileEmail(formWithErrors, auth.get)},
      {
        case (o, n) =>
          (for (
            au <- activeUser
          ) yield {
            razie.db.tx("change.email", au.userName) { implicit txn =>
              val realm = Website.getRealm
              var newu = au.copy(email = n.enc).removePerm(realm, "+" + Perm.eVerified.s)
              Profile.updateUser(au, newu)
              val pro = newu.profile.getOrElse(newu.mkProfile)
              pro.update(pro)
              UserTasks.verifyEmail(newu).create

              Emailer.withSession(request.realm) { implicit mailSession =>
                Tasks.sendEmailVerif(newu)
                Tasks.msgVerif(newu)
              }
            }
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER_EMAIL.doeProfileEmail2 ")
            unauthorized("Oops - cannot update this user [doeProfileEmail2]... ")
          }
      })
  }
}

