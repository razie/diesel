package mod.diesel.controllers

import controllers._
import model.Website
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.Logging
import razie.wiki.Enc
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin.SecLink

/** manage invitations to realms */
class DomInvites extends mod.diesel.controllers.SFiddleBase with Logging {

  /** */
  def invited = RAction { implicit request =>

    val email = request.fqParm("email", "").trim
    val invite = request.fqParm("invitation", "").trim
    val realm = request.fqParm("realm", "").trim

    SecLink.find(invite).map { secLink =>
      auth.map { au =>
        //        if (au.hasPerm(Perm.domFiddle))
        if (au.realms contains realm)
          Redirect("/wiki/Admin:UserHome")
        else {
          if (secLink.link contains Enc.toUrl(au.email.dec)) {
            //            au.profile.map { p =>
            //              p.update(p.addPerm('+' + Perm.domFiddle))
            au.update(au.copy(realms = au.realms + realm))
            cleanAuth(Some(au))
            Emailer.withSession(request.realm) { implicit mailSession =>
              Emailer.tellRaz("realm invitation used", s"email: $email   invite: $invite    realm: $realm")
            }
            Redirect("/wiki/Admin:UserHome")
            //            } getOrElse Msg("No profile !!!???")
          } else Msg("Not your invite...")
        }
      } getOrElse {
        if (secLink.link contains Enc.toUrl(email))
          Msg("Ok - please proceed to create an account", "...and then click this link again to activate it")
        else
          Msg("Not your invite...")
      }
    } getOrElse
      Msg("No invitation found...")
  }

  /** request an invite for a realm */
  def rqinvite(realm: String) = RAction { implicit request =>

    val email = request.fqParm("email", "").trim
    val why = request.fqParm("why", "").trim

    Emailer.withSession(request.realm) { implicit mailSession =>
      // todo tell the owner instead
      Emailer.tellRaz("Specs invite request for realm", "email:", email, "why:", why, "realm:", realm)
    }

    Msg("Ok... queued up - watch your inbox! Thank you for your interest!")
  }

  /** */
  def createInvite(email: String, realm: String) = FAUR { implicit request =>
    // todo not just admin, but realm owner too
    if (request.au.exists(_.isAdmin) && email != "-") {
      val id = new ObjectId()
      val link = "/diesel/invited?email=" + Enc.toUrl(email) + "&invitation=" + id.toString + "&realm=" + realm
      val sec = SecLink(link, Some(Website.forRealm(realm).getOrElse(request.website).domain),
        10, DateTime.now.plusDays(5), 0, DateTime.now, id)
      Msg("Invite link: " + sec.secUrl, "   code: " + id.toString)
    } else
      Msg("Ask an admin for an invite, please.")
  }
}
