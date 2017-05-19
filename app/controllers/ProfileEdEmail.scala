package controllers

import model._
import play.api.data.Form
import play.api.data.Forms.{tuple, _}
import razie.wiki.Sec._
import razie.wiki.model._
import razie.hosting.Website

//@Singleton
//class Profile @Inject() (config:Configuration) extends RazController with Logging {
object EdEmail extends RazController {
  val emailForm = Form {
    tuple(
      "curemail" -> text,
      "newemail" -> text.verifying(vSpec, vEmail)) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByEmail(t._2.enc).isDefined })
  }

  // change email
  def doeProfileEmail() = RAction { implicit request =>
    (for (
      au <- auth orCorr cNoAuth
    ) yield ROK.k noLayout {
        views.html.user.doeProfileEmail(emailForm.fill(au.email.dec, ""), auth.get)
      }
      ) getOrElse
      unauthorized("Oops - how did you get here? [step1]")
  }

  // change email
  def doeProfileEmail2() = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    emailForm.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {views.html.user.doeProfileEmail(formWithErrors, auth.get)},
      {
        case (o, n) =>
          (for (
            au <- activeUser
          ) yield {
            razie.db.tx("change.email", au.userName) { implicit txn =>
              val newu = au.copy(email = n.enc)
              //            val newu = User(au.userName, au.firstName, au.lastName, au.yob, n.enc, au.pwd, au.status, u.roles, u.addr, u.prefs, u._id)
              Profile.updateUser(au, newu)
              val pro = newu.profile.getOrElse(newu.mkProfile)
              pro.update(pro.removePerm("+" + Perm.eVerified.s))
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

