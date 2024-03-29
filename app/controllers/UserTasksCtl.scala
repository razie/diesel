package controllers

import com.google.inject.{Inject, Singleton}
import controllers.Emailer.expand
import model._
import org.joda.time.DateTime
import play.api.Configuration
import play.api.mvc.{Action, DiscardingCookie, Request}
import razie.Logging
import razie.hosting.Website
import razie.wiki.admin.{MailSession, SecLink}
import razie.wiki.model.{Perm, WID}
import razie.wiki.{Config, Enc, EncUrl, Services, WikiConfig}

// NOTE this is not actually a controller - leave it object, the one below is...
object UserTasksCtl extends RazController with Logging {
  import razie.wiki.Sec._

  def msgVerif(c: User, extra: String = "", next: Option[String] = None)(implicit request: Request[_]) = {
    val MSG_EMAIL_VERIF = s"""
Please check your email <font style="color:red">${c.emailDec}</font> for an activation email and follow the instructions to validate your email address.
Please do that soon: it will expire in a few hours, for security reasons.
<p>Please check your spam/junk folders as well in the next few minutes - make sure you mark ${Services.config.SUPPORT} as a safe sender!
<p>Especially if you have an @hotmail.com/.ca or @live.com/.ca or @outlook.com/.ca email address - their spam filter is very aggressive!
""" + extra

    if(c.emailDec.contains("@k.com"))
      Msg2(MSG_EMAIL_VERIF, next, Some(c)) // for testing, don't logout user
    else
      Msg2(MSG_EMAIL_VERIF, next, Some(c)).withSession(Services.config.CONNECTED -> Enc.toSession(c.email))
  }

  def sendEmailVerif(c: User, host:Option[String])(implicit mailSession: MailSession) = {
    val from = mailSession.SUPPORT
    val dt = DateTime.now().plusHours(1).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))

    // overwrite host if local url is specified
    var nhost = if (WikiConfig.getInstance.get.isOverriden("local.url")) None else host

    val hc1 = """/user/task/verifyEmail2?expiry=%s&email=%s&id=%s""".format(EncUrl(dt), Enc.toUrl(c.email), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = SecLink(hc1, nhost)
    log("ENC_LINK2=" + ds.secUrl)

    val h = host.getOrElse("www.dieselapps.com")
    sendToVerif1(c.emailDec, from, c.ename, h, ds.secUrl)
  }

  /** reset pwd */
  def sendEmailReset(c: User)(implicit request: Request[_], mailSession: MailSession) = {
    val from = mailSession.SUPPORT
    // can't use 1 hour - wtf
    val dt = DateTime.now().plusHours(8).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))
    val header = request.headers.get("X-Forwarded-Host")
    val hc1 = """/doe/profile/forgot3?expiry=%s&id=%s""".format(EncUrl(dt), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = SecLink(hc1, header, 1, DateTime.now.plusHours(1))
    log("ENC_LINK2=" + ds.secUrl)

    val h = header.getOrElse ("www.dieselapps.com")
    sendToReset1(c.emailDec, from, c.ename, h, ds.secUrl)
  }

  def sendToVerif1(email: String, from: String, ename: String, header:String, link: String)(implicit mailSession: MailSession) = {
    val html = expand("emailverif", List(
      "ename" -> ename,
      "email" -> email,
      "header" -> header,
      "header" -> header,
      "url" -> link
    ))

    mailSession.send(email, from, "Please verify your email", html)
  }

  def sendToReset1(email: String, from: String, ename: String, h:String, link: String)(implicit mailSession: MailSession) = {
    val html = expand("emailreset", List(
      "ename" -> ename,
      "url" -> link
    ))

    mailSession.send(email, from, "Please reset your password", html)
  }

}

@Singleton
class UserTasksCtl @Inject()(realmCtl:Realm) extends RazController with Logging {
  import play.api.data.Forms._
  import play.api.data._
  import razie.wiki.Sec._

  def userNameChgDenied = Action { implicit request =>
    razie.db.tx("usernamechgDenied", auth.get.userName) { implicit txn =>
      UserTasks.userNameChgDenied(auth.get).delete
    }
    Msg("Your request to change username has been denied!")
  }

  /** step 1 - send verification email */
  def verifyEmail1 = RAction { implicit request =>
    def ERR = {
      verror("ERR_CANT_UPDATE_USER.verifyEmail1 " + request.session.get("email") + " REQUEST: "+request.req.toString)
      Unauthorized("Oops - cannot update this user....verifyEmail1 " + request.errCollector.mkString)
    }

    (for (
      c <- auth orCorr cNoAuth
    ) yield Emailer.withSession(request.realm) { implicit mailSession =>
      UserTasksCtl.sendEmailVerif(c, request.headers.get("X-Forwarded-Host").orElse(Some(request.website.domain)))
      UserTasksCtl.msgVerif(c)
    }) getOrElse {
      ERR
    }
  }

  val reloginForm = Form {
    tuple(
      "email" -> text.verifying("Wrong email format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> text)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifyEmail2(expiry1: String, email: String, id: String) = Action { implicit request =>
    val odate = (try { Option(DateTime.parse(expiry1.dec)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired")

    val pe = email.dec
    // if date is ok and not logged in, but ID matches email
    if (
      odate.isDefined
          && odate.get.isAfterNow
          && !auth.isDefined
          && Users.findUserByEmailDec(pe).orElse(Users.findUserNoCase(pe)).exists(_.id == id)
    )
      verifyEmail3a(
        expiry1,
        email,
        id,
        Users.findUserByEmailDec(pe).orElse(Users.findUserNoCase(pe))
      )
//      (ROK.r noLayout {implicit stok=>
//        views.html.tasks.verifEmail3(reloginForm.fill(("", "")), expiry1, email, id)
//      }).withNewSession
    else if (
      odate.isDefined
          && odate.get.isAfterNow
          && !auth.isDefined
      // emails don't match id ... why?
    )
      (ROK.r noLayout {implicit stok=>
        views.html.tasks.verifEmail3(reloginForm.fill(("", "")), expiry1, email, id)
      }).withNewSession
    else
      verifyEmail3a(expiry1, email, id)
  }

  /** step 2A - user clicked on email link to verify email but it's not logged in, then he provided email again */
  def verifyEmail3(expiry1: String, email: String, id: String) = Action { implicit request =>
    reloginForm.bindFromRequest.fold(
      formWithErrors => {
        error("FORM ERR " + formWithErrors)
        BadRequest(views.html.tasks.verifEmail3(formWithErrors, expiry1, email, id)(ROK.r))
      },
      {
        case (pe, pwd) => {
          val u = Users.findUserByEmailDec(pe).orElse(Users.findUserNoCase(pe))
          if (pe.toLowerCase() == email.dec.toLowerCase() && u.exists(_.pwd == pwd.enc))
            verifiedEmail(expiry1, email, id, Users.findUserByEmailEnc(email))
          else {
            u.foreach(_.auditLoginFailed(Website.getRealm))
            Msg("Email doesn't match - could not verify email!")
          }
        }
      })
  }

  /** done - verify it */
  private def verifyEmail3a(expiry1: String, email: String, id: String, user:Option[User] = None)(implicit request: Request[_]) = {
    verifiedEmail(expiry1, email, id, user.orElse(auth))
  }

  /** done - verify it */
  def verifiedEmail(expiry1: String, email: String, id: String, user: Option[User])(implicit request: Request[_]) = {
    val realm = Website.getRealm
    implicit val errCollector = new VErrors()
    (expiry1, email, id) match {
      case (Enc(expiry), ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          op <- user orCorr cNoAuth;
          a <- (if (op.email == ce) Some(true) else None) logging ("ERR neq", op.email, ce) orErr "Validation link for a different user than the one logged in!";
          pro <- op.profile orCorr cNoProfile
        ) yield {
          val p = op.forRealm(realm)
          razie.db.tx("verifiedEmail", p.userName) { implicit txn =>
            if (!p.hasPerm(Perm.eVerified)) {
              // need to create first this realm - sometimes it's not created, if user doesn't try to login
              var pu = p.addPerm(realm, Perm.eVerified.s).addPerm(realm, Perm.uWiki.s)
              pu = pu.addPerm("*", Perm.eVerified.s).addPerm("*", Perm.uWiki.s)
              pu = if (p.isUnder13) pu else pu.addPerm(realm, Perm.uProfile.s)
              pu = if (p.isUnder13) pu else pu.addPerm("*", Perm.uProfile.s)
              p.update(pu)

              // replace in cache
              Users.findUserById(p._id).map { u =>
                cleanAuth(Some(u))
              }
            }
            UserTasks.verifyEmail(p).delete
          }

//          (if(op.hasConsent(realm)) {
          Msg2("""
Email verified. Your account is now active!
""", Some("/")
//            )
//          } else
//            (ROK.r apply { implicit stok =>
//              views.html.user.doeConsent(next.getOrElse("/"))
//            })
          )//DO NOT LOGG HIM IN... .withSession(Services.config.CONNECTED -> Enc.toSession(u.email))
              .discardingCookies(DiscardingCookie("error"))
        }
      } getOrElse
          {
            verror("ERR_CANT_UPDATE_USER.verifiedEmail " + Enc.unapply(email))
            Unauthorized("Oops - cannot update this user....verifiedEmail " + errCollector.mkString)
          }
    }
  }

  /** force verify email */
  def forceVerified(id: String) = FAUR("force verif") { implicit request =>
      val realm = request.realm

      for (
        au <- request.au;
        can <- au.isMod orErr "No permission";
        r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
        u <- Users.findUserById(id).map(_.forRealm(request.realm)) orCorr cNoAuth;
        p <- u.profile orCorr cNoProfile
      ) yield {
        razie.db.tx("verifiedEmail", u.userName) { implicit txn =>
          if (!u.hasPerm(Perm.eVerified)) {
            // need to create first this realm - sometimes it's not created, if user doesn't try to login
            var pu = u.addPerm(realm, Perm.eVerified.s).addPerm(realm, Perm.uWiki.s)
            pu = pu.addPerm("*", Perm.eVerified.s).addPerm("*", Perm.uWiki.s)
            pu = if (u.isUnder13) pu else pu.addPerm(realm, Perm.uProfile.s)
            pu = if (u.isUnder13) pu else pu.addPerm("*", Perm.uProfile.s)
            pu = pu.addModNote(realm, "Verified by mod")
            u.update(pu)

            // replace in cache
            Users.findUserById(p._id).map { u =>
              cleanAuth(Some(u))
            }
            Msg2("""Email verified by admin. The account is now active!""", None)
          } else {
            Msg2("""Accoutn was already active!""", None)
          }
        }
//          UserTasks.verifyEmail(p).delete
      }
  }

  def some(what: String) = Action { implicit request =>
    forUser { au =>
      what match {
        case "setupRegistration" => {
          Msg2("Setting up registration is a bit complicated right now, but we'll do it for you. <p>Please create a support request from the link at the bottom and tell us if you'd like registration and if you want to see the default forms or with your own forms.", Some("/"))
        }
        case "setupCalendars" => {
          Msg2("Setting up calendars is a bit complicated right now, but we'll do it for you. <p>Please create a support request from the link at the bottom and describe what calendar this is (club calendar probably) and the events in it: what, when, where.", Some("/"))
        }
        case UserTasks.START_REGISTRATION => {
          val ut = au.tasks.find(_.name == UserTasks.START_REGISTRATION)
          Redirect(routes.ClubCtl.doeStartRegSimple(WID.fromPath(ut.map(_.args("club")).mkString).get))
        }
        case _ => {
          Msg("?")
        }
      }
    }
  }

  def someok(what: String) = Action { implicit request =>
    forUser { au =>
      val t = UserTasks.some(au, what)
      razie.db.tx("someok", au.userName) { implicit txn =>
        t.delete
      }
      Msg(t.desc + " Completed!")
    }
  }

}
