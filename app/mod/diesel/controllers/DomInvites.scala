package mod.diesel.controllers

import com.google.inject.{Inject, Singleton}
import controllers._
import model.Users
import org.joda.time.DateTime
import play.api.Configuration
import razie.Logging
import razie.hosting.Website
import razie.wiki.admin.SecLink
import views.html.wiki.genericForm

/** manage invitations to realms */
@Singleton
class DomInvites @Inject() (config:Configuration) extends mod.diesel.controllers.SFiddleBase with Logging {

  /** user was invited - clicks and ends here */
  def invited = RAction { implicit request =>
    request.flash.get(SecLink.HEADER).flatMap(SecLink.find).map { secLink =>
      val email = secLink.props("email")
      val invite = secLink.id
      val realm = secLink.props("realm")

      if (request.verifySecLink) {
        auth.map { au =>
          if (au.realms contains realm)
            Redirect("/wiki/Admin:UserHome")
          else {
            if (email == au.emailDec) {
              au.update(Users.updRealm(au, realm))
              cleanAuth(Some(au))
              Emailer.withSession(realm) { implicit mailSession =>
                Emailer.tellAdmin("realm invitation used", s"email: $email   invite: $invite    realm: $realm")
              }
              Redirect("/wiki/Admin:UserHome")
            } else Msg("Not your invite or invitation already used...")
          }
        } getOrElse {
          // send to join but flash secLink.id
          val call = if (Users.findUserByEmailDec(email).isDefined) controllers.routes.Profile.doeJoin()
          else controllers.routes.Profile.doeJoinNew()

          Redirect(call)
              .withNewSession
              .flashing(SecLink.HEADER -> secLink.id)
        }
      } else
          Msg(s"Invitation expired [$invite]...")
      } getOrElse
        Msg(s"No valid invitation found...")
  }

  /** request an invite for a realm */
  def rqinvite(realm: String) = RAction { implicit request =>
//    val email = request.fqParm("email", "").trim
//    val why = request.fqParm("why", "").trim
//
//    val recap = request.fqParm("g-recaptcha-response", "").trim
//
//    if(! new Recaptcha(config).verify2(recap, clientIp)) {
//      Unauthorized("Are you human? If so, please use the support link at the bottom of the previous page...")
//    } else {
//      Emailer.withSession(request.realm) { implicit mailSession =>
//         todo tell the owner instead
//        Emailer.tellAdmin("Specs invite request for realm", "email:", email, "why:", why, "realm:", realm)
//      }
//
//      Msg("Ok... queued up - watch your inbox! Thank you for your interest!")
//    }

      Unauthorized("Please use the support link at the bottom of the previous page...")
  }

  /** display invite form */
  def createInvite1 = FAUR { implicit request =>
    val email = request.fqParm("email", "").trim

    // todo not just admin, but realm owner too
    if (request.au.exists(_.isMod) && email != "-") {
      ROK.k apply {
        genericForm(
          routes.DomInvites.createInvite2().url,
          "Create an invite",
          "If user exists, this realm will be added - you'll need to update permissions",
          List("email")
        )
      }
    } else
      Msg("Ask an admin for an invite, please.")
  }

  /** process an invite form submit */
  def createInvite2 = FAUR { implicit request =>
    val email = request.fqParm("email", "").trim

    if (request.au.exists(_.isMod) && email != "-") {
      Users.findUserByEmailDec(email.trim).orElse(Users.findUserNoCase(email.trim)).map { user =>
        user.update(Users.updRealm(user, request.realm))
        Msg(s"User ${user.ename} exists, added to realm...")
      } getOrElse {
        val link = "/diesel/invited"

        // overwrite host if local url is specified
        val nhost = if (razie.wiki.WikiConfig.getInstance.get.isOverriden("local.url")) None else Some(
          Website.forRealm(request.realm).getOrElse(request.website).domain)
        val sec = SecLink(link, nhost,
          10, DateTime.now.plusDays(5))
            .withProp("email", email)
            .withProp("realm", request.realm)
        Msg("Invite link: " + sec.secUrl, "   code: " + sec.id)
      }
    } else
      Msg("Ask an admin for an invite, please.")
  }
}

