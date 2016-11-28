package controllers

import mod.snow.{RacerKidz, RK, RacerKidAssoc}
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}
import razie.Logging
import model._
import razie.wiki.admin.{SecLink, SendEmail, MailSession}
import razie.wiki.model.WID
import razie.wiki.{Services, EncUrl, Enc}
import admin.Config

object Tasks extends RazController with Logging {
  import controllers.Profile.parentForm
  import razie.wiki.Sec._

  lazy val cNotParent = new Corr("you're not the parent", "login with the parent account and try again")

  import play.api.data.Forms._
  import play.api.data._

  // step 1 - show the form for user already created (from task)
  def addParent = RAction { implicit request =>
    (for (
      u <- request.au
    ) yield Ok(views.html.tasks.addParent(parentForm.fill(""), u.ename))) getOrElse
      Unauthorized("Oops - how did you get here? [addParent]")
  }

  // step 1b - when creating user
  // ujson is used during creation of the child account - not yet in db
  def addParent1 = RAction { implicit request =>
    (for (
      uj <- request.ireq.session.get("ujson") orErr ("missing ujson");
      u <- Users.fromJson(uj) orErr ("cannot parse ujson")
    ) yield {
      debug("ujson=" + uj)
      Ok(views.html.tasks.addParent(parentForm.fill(""), u.ename)).withSession("ujson" -> uj)
    }) getOrElse
      Unauthorized("Oops - how did you get here? [addParent1]")
  }

  def userNameChgDenied = Action { implicit request =>
    razie.db.tx("usernamechgDenied") { implicit txn =>
      UserTasks.userNameChgDenied(auth.get).delete
    }
    Msg2("Your request to change username has been denied!")
  }

  // step 2 - filled parent email, now creating child user and send email to parent
  def addParent2 = RAction { implicit request =>
    def ERR = {
      verror("ERR_CANT_UPDATE_USER.addParent2 " + request.session.get("email"))
      Unauthorized("Oops - cannot update this user [addParent2]... " + errCollector.mkString)
    }

    if (request.session.get("ujson").isDefined) {
      // during createing new user - user not created yet
      parentForm.bindFromRequest()(request.ireq).fold(
        formWithErrors => {
          error("FORM ERR " + formWithErrors)
          BadRequest(views.html.tasks.addParent(formWithErrors, "")).withSession("ujson" -> request.session.get("ujson").get)
        },
        {
          case pe: String => {
            for (
              uj <- request.session.get("ujson") orErr ("missing ujson - bad request");
              c <- Users.fromJson(uj) orErr ("cannot parse ujson - bad request")
            ) yield {
              // TODO bad code - reconcile and reuse createion sequence from Profile.doCreateProfiles
              razie.db.tx("addParent2") { implicit txn =>
                val created = Profile.createUser(c)(request.ireq, txn)

                UserTasks.addParent(c).create

                Emailer.withSession(request.realm) { implicit mailSession =>
                  sendEmail(pe, c)(request.ireq, mailSession)
                }
              }
            }
          } getOrElse {
            ERR
          }
        })
    } else if (request.au.isDefined) {
      // done later, user already created
      parentForm.bindFromRequest()(request.ireq).fold(
        formWithErrors => {
          error("FORM ERR " + formWithErrors)
          BadRequest(views.html.tasks.addParent(formWithErrors, request.au.get.ename))
        },
        {
          case pe: String => {
            for (
              c <- request.au orErr ("not authenticated")
            ) yield {
              Emailer.withSession(request.realm) { implicit mailSession =>
                sendEmail(pe, c)(request.ireq, mailSession)
              }
            }
          } getOrElse {
            ERR
          }
        })
    } else {
      error("ERR_ no ujson and no auth!!")
      ERR
    }
  }

  /** send email to parrent to accep tkid */
  def sendEmail(pe: String, c: User)(implicit request: Request[_], mailSession: MailSession) = {
    val from = "admin@razie.com"

    val dt = DateTime.now().plusHours(1).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))
    val hc1 = """/doe/tasks/addParent3?expiry=%s&parentEmail=%s&childEmail=%s&childId=%s""".format(EncUrl(dt), EncUrl(pe), Enc.toUrl(c.email), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = SecLink(hc1)
    log("ENC_LINK2=" + ds.secUrl)

    sendToParentAdd(pe, from, c.email, c.ename, ds.secUrl)
    Msg("Ok - we sent an email - please ask your parent to follow the instructions in that email. " +
      "" +
      "They have to first register and then follow this [link](" +
      ds.secUrl + ")", HOME, Some(c)).withSession(Services.config.CONNECTED -> Enc.toSession(c.email))
  }

  def sendToParentAdd(to: String, from: String, childEmail: String, childName: String, link: String)(implicit mailSession: MailSession) {
    val html = Emailer.text("parentadd").format(childName, childEmail.dec, EncUrl(to), EncUrl(to), link);

    SendEmail.send(to, from, "Racer Kid parent - please activate your account", html.replaceAll("www.racerkidz.com", Services.config.hostport))
  }

  /** step 3 - parent clicked on email link to add child */
  def addParent3(expiry1: String, parentEmail: String, childEmail: String, childId: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (expiry1, parentEmail, childEmail, childId) match {
      case (Enc(expiry), pe, ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- auth orCorr cNoAuth;
          a <- (if (p.email == pe) Some(true) else None) orCorr cNotParent;
          pro <- p.profile orCorr cNoProfile;
          child <- Users.findUserById(cid) orErr ("child account not found");
          cpro <- child.profile orCorr cNoProfile;
          already <- !(Users.findPC(p._id, child._id).isDefined) orErr "Already defined"
        ) yield {
          razie.db.tx("addParent3") { implicit txn =>
            pro.update(pro.addRel(cid -> "child"))
            cpro.update(cpro.addRel(p.id -> "parent"))
            UserTask(child._id, "addParent").delete
            ParentChild(p._id, child._id).create

            // TODO manual reconcile
            RacerKidAssoc(
              p._id, RacerKidz.myself(child._id)._id, RK.ASSOC_CHILD,
              RK.ROLE_KID,
              p._id).create
          }

          Msg("""
Ok child added. You can edit the privacy settings from your [profile page](/doe/profile).

Please read our [[Terms of Service]] as well as our [[Privacy Policy]]
""", HOME)
        }
      } getOrElse
        {
          verror("ERR_CANT_UPDATE_USER.addParent3 " + request.session.get("email"))
          Unauthorized("Oops - cannot update this user [addParent3]... " + errCollector.mkString)
        }
    }
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
      sendEmailVerif(c)
      msgVerif(c)
    }) getOrElse {
      ERR
    }
  }

  def msgVerif(c: User, extra: String = "", next: Option[String] = None)(implicit request: Request[_]) = {
    val MSG_EMAIL_VERIF = s"""
Please check your email <font style="color:red">${c.email.dec}</font> for an activation email and follow the instructions to validate your email address.
Please do that soon: it will expire in a few hours, for security reasons.
<p>Please check your spam/junk folders as well in the next few minutes - make sure you mark ${Config.SUPPORT} as a safe sender!
<p>Especially if you have an @hotmail.com/.ca or @live.com/.ca or @outlook.com/.ca email address - their spam filter is very aggressive!
""" + extra

    if(c.email.dec.contains("@k.com"))
      Msg2(MSG_EMAIL_VERIF, next, Some(c)) // for testing, don't logout user
    else
      Msg2(MSG_EMAIL_VERIF, next, Some(c)).withSession(Services.config.CONNECTED -> Enc.toSession(c.email))
  }

  def sendEmailVerif(c: User)(implicit request: Request[_], mailSession: MailSession) = {
    val from = Config.SUPPORT
    val dt = DateTime.now().plusHours(1).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))
    val header = request.headers.get("X-Forwarded-Host")
    val hc1 = """/user/task/verifyEmail2?expiry=%s&email=%s&id=%s""".format(EncUrl(dt), Enc.toUrl(c.email), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = SecLink(hc1, header)
    log("ENC_LINK2=" + ds.secUrl)

    val h = header.getOrElse ("www.racerkidz.com")
    sendToVerif1(c.email.dec, from, c.ename, h, ds.secUrl)
  }

  /** reset pwd */
  def sendEmailReset(c: User)(implicit request: Request[_], mailSession: MailSession) = {
    val from = Config.SUPPORT
    val dt = DateTime.now().plusHours(1).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))
    val header = request.headers.get("X-Forwarded-Host")
    val hc1 = """/doe/profile/forgot3?expiry=%s&id=%s""".format(EncUrl(dt), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = SecLink(hc1, header, 1, DateTime.now.plusHours(1))
    log("ENC_LINK2=" + ds.secUrl)

    val h = header.getOrElse ("www.racerkidz.com")
    sendToReset1(c.email.dec, from, c.ename, h, ds.secUrl)
  }

  def sendToVerif1(email: String, from: String, name: String, h:String, link: String)(implicit mailSession: MailSession) = {
    val html = Emailer.text("emailverif").format(name, email, h, h, link);

    SendEmail.send(email, from, "Racer Kid - please verify your email", html)
  }

  def sendToReset1(email: String, from: String, name: String, h:String, link: String)(implicit mailSession: MailSession) = {
    val html = Emailer.text("emailreset").format(name, link);

    SendEmail.send(email, from, "Racer Kid - please reset your password", html)
  }

  val reloginForm = Form {
    tuple(
      "email" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> text)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifyEmail2(expiry1: String, email: String, id: String) = Action { implicit request =>
    val odate = (try { Option(DateTime.parse(expiry1.dec)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired")
    if (odate.isDefined && odate.get.isAfterNow && !auth.isDefined)
      ROK.r noLayout {implicit stok=>
        views.html.tasks.verifEmail3(reloginForm.fill(("", "")), expiry1, email, id)
      }
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
          val u = Users.findUserByEmail(pe.enc).orElse(Users.findUserNoCase(pe))
          if (pe.toLowerCase() == email.dec.toLowerCase() && u.exists(_.pwd == pwd.enc))
            verifiedEmail(expiry1, email, id, Users.findUserByEmail(email))
          else {
            u.foreach(_.auditLoginFailed(Website.getRealm))
            Msg2("Email doesn't match - could not verify email!")
          }
        }
      })
  }

  /** doing it */
  private def verifyEmail3a(expiry1: String, email: String, id: String)(implicit request: Request[_]) = {
    verifiedEmail(expiry1, email, id, auth)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifiedEmail(expiry1: String, email: String, id: String, user: Option[User])(implicit request: Request[_]) = {
    implicit val errCollector = new VErrors()
    (expiry1, email, id) match {
      case (Enc(expiry), ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- user orCorr cNoAuth;
          a <- (if (p.email == ce) Some(true) else None) logging ("ERR neq", p.email, ce) orErr "Not same user";
          pro <- p.profile orCorr cNoProfile
        ) yield {
          razie.db.tx("verifiedEmail") { implicit txn =>
            if (!p.hasPerm(Perm.eVerified)) {
              // TODO transaction
              val ppp = pro.addPerm("+" + Perm.eVerified.s).addPerm("+" + Perm.uWiki.s)
              pro.update(if (p.isUnder13) ppp else ppp.addPerm("+" + Perm.uProfile.s))

              // replace in cache
              Users.findUserById(p._id).map { u =>
                cleanAuth(Some(u))
              }
            }
            UserTasks.verifyEmail(p).delete
          }

          Msg2("""
Ok, email verified. You can now edit topics.

Please read our [[Terms of Service]] as well as our [[Privacy Policy]]
""", Some("/")).withSession(Services.config.CONNECTED -> Enc.toSession(email))
        }
      } getOrElse
        {
          verror("ERR_CANT_UPDATE_USER.verifiedEmail " + Enc.unapply(email))
          Unauthorized("Oops - cannot update this user....verifiedEmail " + errCollector.mkString)
        }
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
          Redirect(routes.Club.doeStartRegSimple(WID.fromPath(ut.map(_.args("club")).mkString).get))
        }
        case UserTasks.APPROVE_VOL => {
          val ut = au.tasks.find(_.name == UserTasks.APPROVE_VOL)
                    Vol.doeVolApprover(auth.get)
//          Redirect(routes.Club.doeVolApprover(auth.get))
        }
        case _ => {
          Msg2("?")
        }
      }
    }
  }

  def someok(what: String) = Action { implicit request =>
    forUser { au =>
      val t = UserTasks.some(au, what)
      razie.db.tx("someok") { implicit txn =>
        t.delete
      }
      Msg2(t.desc + " Completed!")
    }
  }

}
