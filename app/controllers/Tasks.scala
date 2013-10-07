package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import admin.SendEmail
import model.Api
import model.DoSec
import model.Enc
import model.EncUrl
import model.ParentChild
import model.RegdEmail
import model.Registration
import model.User
import model.UserTask
import model.Users
import model.Wikis
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.data.Form
import play.api.mvc.Request
import play.api.mvc.Action
import razie.Logging
import razie.Snakk
import model.Base64
import model.Perm
import admin._
import model.WID
import model.UserTasks
import play.api.cache.Cache
import model.RacerKidAssoc
import model.RacerKidz
import model.RK

object Tasks extends RazController with Logging {
  import Profile.Email
  import Profile.parentForm
  import model.Sec._

  lazy val cNotParent = new Corr("you're not the parent", "login with the parent account and try again")

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // step 1 - show the form for user already created (from task)
  def addParent = Action { implicit request =>
    (for (
      u <- auth
    ) yield Ok(views.html.tasks.addParent(parentForm.fill(Email("")), u.ename))) getOrElse
      Unauthorized("Oops - how did you get here? [addParent]")
  }

  // step 1b - when creating user
  // ujson is used during creation of the child account - not yet in db
  def addParent1 = Action { implicit request =>
    (for (
      uj <- session.get("ujson") orErr ("missing ujson");
      u <- Users.fromJson(uj) orErr ("cannot parse ujson")
    ) yield {
      debug("ujson=" + uj)
      Ok(views.html.tasks.addParent(parentForm.fill(Email("")), u.ename)).withSession("ujson" -> uj)
    }) getOrElse
      Unauthorized("Oops - how did you get here? [addParent1]")
  }

  def userNameChgDenied = Action { implicit request =>
    UserTasks.userNameChgDenied(auth.get).delete
    Msg2("Your request to change username has been denied!")
  }

  // step 2 - filled parent email, now creating child user and send email to parent
  def addParent2 = Action { implicit request =>
    implicit val errCollector = new VError()
    def ERR = {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
    }

    if (session.get("ujson").isDefined) {
      // during createing new user - user not created yet
      parentForm.bindFromRequest.fold(
        formWithErrors => {
          error("FORM ERR " + formWithErrors)
          BadRequest(views.html.tasks.addParent(formWithErrors, "")).withSession("ujson" -> session.get("ujson").get)
        },
        {
          case Email(pe) => {
            for (
              uj <- session.get("ujson") orErr ("missing ujson - bad request");
              c <- Users.fromJson(uj) orErr ("cannot parse ujson - bad request")
            ) yield {
              // TODO bad code - reconcile and reuse createion sequence from Profile.doCreateProfiles
              val created = Profile.createUser(c)

              UserTasks.addParent(c).create

              Emailer.withSession { implicit mailSession =>
                sendEmail(pe, c)
              }
            }
          } getOrElse {
            ERR
          }
        })
    } else if (auth.isDefined) {
      // done later, user already created
      parentForm.bindFromRequest.fold(
        formWithErrors => {
          error("FORM ERR " + formWithErrors)
          BadRequest(views.html.tasks.addParent(formWithErrors, auth.get.ename))
        },
        {
          case Email(pe) => {
            for (
              c <- auth orErr ("not authenticated")
            ) yield {
              Emailer.withSession { implicit mailSession =>
                sendEmail(pe, c)
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
    val ds = DoSec(hc1)
    log("ENC_LINK2=" + ds.secUrl)

    sendToParentAdd(pe, from, c.email, c.ename, ds.secUrl)
    Msg("Ok - we sent an email - please ask your parent to follow the instructions in that email. " +
      "" +
      "They have to first register and then follow this [link](" +
      ds.secUrl + ")", HOME, Some(c)).withSession("connected" -> Enc.toSession(c.email))
  }

  def sendToParentAdd(to: String, from: String, childEmail: String, childName: String, link: String)(implicit mailSession: MailSession) {
    val html = Emailer.text("parentadd").format(childName, childEmail.dec, EncUrl(to), EncUrl(to), link);

    SendEmail.send(to, from, "Racer Kid parent - please activate your account", html)
  }

  /** step 3 - parent clicked on email link to add child */
  def addParent3(expiry1: String, parentEmail: String, childEmail: String, childId: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (expiry1, parentEmail, childEmail, childId) match {
      case (Enc(expiry), pe, ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- auth orCorr cNoAuth;
          a <- (if (p.email == pe) Some(true) else None) orCorr cNotParent;
          pro <- p.profile orCorr cNoProfile;
          child <- Users.findUserById(cid) orErr ("child account not found");
          cpro <- child.profile orCorr cNoProfile;
          already <- !(Users.findPC(p._id, child._id).isDefined) orErr "Already defined"
        ) yield {
          // TODO transaction
          this dbop pro.update(pro.addRel(cid -> "child"))
          this dbop cpro.update(cpro.addRel(p.id -> "parent"))
          this dbop UserTask(child._id, "addParent").delete
          this dbop ParentChild(p._id, child._id).create

          // TODO manual reconcile
          RacerKidAssoc(
            p._id, RacerKidz.myself(child._id)._id, RK.ASSOC_CHILD,
            RK.ROLE_KID,
            p._id).create

          Msg("""
Ok child added. You can edit the privacy settings from your [profile page](/doe/profile).

Please read our [[Terms of Service]] as well as our [[Privacy Policy]]
""", HOME)
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
        }
    }
  }

  /** step 1 - send verification email */
  def verifyEmail1 = Action { implicit request =>
    implicit val errCollector = new VError()
    def ERR = {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
    }

    (for (
      c <- auth orCorr cNoAuth
    ) yield Emailer.withSession { implicit mailSession =>
      sendEmailVerif(c)
      msgVerif(c)
    }) getOrElse {
      ERR
    }
  }

  def msgVerif(c: User, extra: String = "", next: Option[String] = None)(implicit request: Request[_]) = {
    val MSG_EMAIL_VERIF = s"""
Ok - we sent an email to your registered email address <font style="color:red">${c.email.dec}</font> - please follow the instructions in that email to validate your email address.
<p>You can't really <font style="color:red">begin using the site</font> until you verify your email, sorry...
<p>Please check your spam/junk folders as well in the next few minutes - make sure you mark """ + Config.SUPPORT + """ as a safe sender!""" + extra

    Msg2(MSG_EMAIL_VERIF, next, Some(c)).withSession("connected" -> Enc.toSession(c.email))
  }

  def sendEmailVerif(c: User)(implicit request: Request[_], mailSession: MailSession) = {
    val from = Config.SUPPORT
    val dt = DateTime.now().plusHours(1).toString()
    log("ENC_DT=" + dt)
    log("ENC_DT=" + dt.enc)
    log("ENC_DT=" + dt.enc.dec)
    log("ENC_DT=" + EncUrl(dt))
    val hc1 = """/user/task/verifyEmail2?expiry=%s&email=%s&id=%s""".format(EncUrl(dt), Enc.toUrl(c.email), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = DoSec(hc1)
    log("ENC_LINK2=" + ds.secUrl)

    sendToVerif1(c.email.dec, from, c.ename, ds.secUrl)
  }

  def sendToVerif1(email: String, from: String, name: String, link: String)(implicit mailSession: MailSession) = {
    val html = Emailer.text("emailverif").format(name, email, link);

    SendEmail.send(email, from, "Racer Kid - please activate your account", html)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifyEmail2(expiry1: String, email: String, id: String) = Action { implicit request =>
    val odate = (try { Option(DateTime.parse(expiry1.dec)) } catch { case _ => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired")
    if (odate.isDefined && odate.get.isAfterNow && !auth.isDefined)
      Ok(views.html.tasks.verifEmail3(parentForm.fill(Email("")), expiry1, email, id))
    else
      verifyEmail3a(expiry1, email, id)
  }

  /** step 2A - user clicked on email link to verify email but it's not logged in, then he provided email again */
  def verifyEmail3(expiry1: String, email: String, id: String) = Action { implicit request =>
    parentForm.bindFromRequest.fold(
      formWithErrors => {
        error("FORM ERR " + formWithErrors)
        BadRequest(views.html.tasks.verifEmail3(formWithErrors, expiry1, email, id))
      },
      {
        case Email(pe) => {
          if (pe.toLowerCase() == email.dec.toLowerCase())
            verifiedEmail(expiry1, email, id, Users.findUser(email))
          else
            Msg2("Email doesn't match - could not verify email!")
        }
      })
  }

  /** doing it */
  private def verifyEmail3a(expiry1: String, email: String, id: String)(implicit request: Request[_]) = {
    verifiedEmail(expiry1, email, id, auth)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifiedEmail(expiry1: String, email: String, id: String, user: Option[User])(implicit request: Request[_]) = {
    implicit val errCollector = new VError()
    (expiry1, email, id) match {
      case (Enc(expiry), ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- user orCorr cNoAuth;
          a <- (if (p.email == ce) Some(true) else None) logging ("ERR neq", p.email, ce) orErr "Not same user";
          pro <- p.profile orCorr cNoProfile;
          already <- !(p.hasPerm(Perm.eVerified)) orErr "Already verified"
        ) yield {
          // TODO transaction
          val ppp = pro.addPerm("+" + Perm.eVerified.s).addPerm("+" + Perm.uWiki.s)
          this dbop pro.update(if (p.isUnder13) ppp else ppp.addPerm("+" + Perm.uProfile.s))
          this dbop UserTasks.verifyEmail(p).delete

          // replace in cache
          Users.findUserById(p._id).map { u =>
            import play.api.Play.current
            cleanAuth(Some(u))
          }

          Msg("""
Ok, email verified. You can now edit topics.

Please read our [[Terms of Service]] as well as our [[Privacy Policy]]
""", HOME)
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
        }
    }
  }

  def some(what: String) = Action { implicit request =>
    val t = UserTasks.some(auth.get, what)

    what match {
      case "setupRegistration" => {
        Msg2("Setting up registration is a bit complicated right now, but we'll do it for you. <p>Please create a support request from the link at the bottom and tell us if you'd like registration and if you want to see the default forms or with your own forms.", Some("/"))
      }
      case "setupCalendars" => {
        Msg2("Setting up calendars is a bit complicated right now, but we'll do it for you. <p>Please create a support request from the link at the bottom and describe what calendar this is (club calendar probably) and the events in it: what, when, where.", Some("/"))
      }
      case _ => {
        Msg2("?")
      }
    }
  }

  def someok(what: String) = Action { implicit request =>
    val t = UserTasks.some(auth.get, what)
    t.delete
    Msg2(t.desc + " Completed!")
  }

}
