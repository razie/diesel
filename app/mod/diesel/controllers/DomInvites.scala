package mod.diesel.controllers

import controllers._
import model.{User, Users}
import razie.hosting.Website
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.Logging
import razie.wiki.Enc
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin.SecLink
import razie.wiki.model.Perm
import views.html.wiki.genericForm

/** manage invitations to realms */
class DomInvites extends mod.diesel.controllers.SFiddleBase with Logging {

  /** */
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
          Redirect(controllers.routes.Profile.doeJoin()).withNewSession.flashing(SecLink.HEADER -> secLink.id)
        }
      } else
          Msg(s"Invitation expired [$invite]...")
      } getOrElse
        Msg(s"No valid invitation found...")
  }

  /** request an invite for a realm */
  def rqinvite(realm: String) = RAction { implicit request =>

    val email = request.fqParm("email", "").trim
    val why = request.fqParm("why", "").trim

    Emailer.withSession(request.realm) { implicit mailSession =>
      // todo tell the owner instead
      Emailer.tellAdmin("Specs invite request for realm", "email:", email, "why:", why, "realm:", realm)
    }

    Msg("Ok... queued up - watch your inbox! Thank you for your interest!")
  }

  /** */
  def createInvite1 = FAUR { implicit request =>
    val email = request.fqParm("email", "").trim

    // todo not just admin, but realm owner too
    if (request.au.exists(_.isAdmin) && email != "-") {
      ROK.k apply {
        genericForm(
          routes.DomInvites.createInvite2().url,
          "Create an invite",
          "If user exists, this realm will be added - you'll need to update permissions<br>",
          List("email")
        )
      }
    } else
      Msg("Ask an admin for an invite, please.")
  }

  /** */
  def createInvite2 = FAUR { implicit request =>
    val email = request.fqParm("email", "").trim

    // todo not just admin, but realm owner too
    if (request.au.exists(_.isAdmin) && email != "-") {
      Users.findUserByEmailDec(email).map {user =>
        user.update(Users.updRealm(user, request.realm))
        Msg(s"User ${user.ename} added to realm...")
      } getOrElse {
        val link = "/diesel/invited"
        val sec = SecLink(link, Some(Website.forRealm(request.realm).getOrElse(request.website).domain),
          10, DateTime.now.plusDays(5))
          .withProp("email", email)
          .withProp("realm", request.realm)
        Msg("Invite link: " + sec.secUrl, "   code: " + sec.id)
      }
    } else
      Msg("Ask an admin for an invite, please.")
  }
}

