/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import com.google.inject._
import model._
import org.joda.time.DateTime
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{tuple, _}
import play.api.mvc.Action
import razie.hosting.Website
import razie.wiki.Sec._
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.{Enc, Services}

@Singleton
class EdUsername @Inject() (config:Configuration) extends RazController {
  // profile
  val chgusernameform = Form {
    tuple(
      "currusername" -> text,
      "newusername" -> text.verifying(
        "Too short!", p => p.length > 3).verifying(
        "Too long!", p => p.length <= 14).verifying(
        "That name is reserved!", p => !Services.config.reservedNames.contains(p)).verifying(
        "No spaces please", p => !p.contains(" "))) verifying
      ("Can't use the same name", { t: (String, String) => t._1 != t._2 }) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByUsername(t._2).isDefined })
  }

  // authenticated means doing a task later
  def doeProfileUname = FAUR { implicit request =>
    Ok(views.html.user.doeProfileUname(chgusernameform.fill(request.au.get.userName, "")))
  }

  def doeProfileUname2 = FAUR { implicit request =>
    chgusernameform.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(views.html.user.doeProfileUname(formWithErrors)),
      {
        case (o, n) =>
          (for (
            au <- activeUser;
            ok <- (o == au.userName) orErr ("Not correct old username");
            isc <- (!au.isClub) orErr ("Cannot change names for clubs, sorry - too many at stake")
          ) yield {
            SendEmail.withSession(request.realm) { implicit mailSession =>
              Emailer.sendEmailUname(n, au)
            }
            Msg("Ok - we sent a request - we'll review it asap and let you know.",
              HOME, Some(au))
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USERNAME ")
            unauthorized("Oops - cannot update this user [ERR_CANT_UPDATE_USERNAME]... ")
          }
      })
  }

  // logged in as ADMIN
  def accept(expiry1: String, userId: String, newusername: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (expiry1, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
        // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          admin <- auth orCorr cNoAuth;
          a <- admin.hasPerm(Perm.adminDb) orCorr cNoPermission;
          u <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(u.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          razie.db.tx("accept.user", admin.userName) { implicit txn =>
            Profile.updateUser(u, u.copy(userName=newusername))
            UserTasks.userNameChgDenied(u).delete
            Wikis.updateUserName(u.userName, newusername)
            Emailer.withSession(Website.getRealm(request)) { implicit mailSession =>
              Emailer.sendEmailUnameOk(newusername, u)
            }
          }

          cleanAuth(Some(u))

          Msg("""Ok, username changed.""", HOME)
        }
      } getOrElse
        {
          verror("ERR_CANT_UPDATE_USER.accept " + request.session.get("email"))
          unauthorized("Oops - cannot update this user [accept]... ")
        }
    }
  }

  // logged in as ADMIN
  def deny(expiry: String, userId: String, newusername: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (expiry, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => None }) orErr ("token faked: " + expiry);
          notExpired <- date.isAfterNow orCorr cExpired;
          u <- activeUser;
          a <- u.hasPerm(Perm.adminDb) orCorr Corr("Not authorized");
          user <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(user.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          razie.db.tx("deny.user", u.userName) { implicit txn =>
            UserTasks.userNameChgDenied(u).create
            Emailer.withSession(Website.getRealm(request)) { implicit mailSession =>
              Emailer.sendEmailUnameDenied(newusername, u)
            }
          }
          Msg("""
Ok, username notified
""", HOME)
        }
      } getOrElse
        {
          verror("ERR_CANT_UPDATE_USER.deny " + request.session.get("email"))
          unauthorized("Oops - cannot update this user [deny]... ")
        }
    }
  }
}


