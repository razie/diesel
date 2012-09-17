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

object Tasks extends RazController with Logging {
  import Profile.Email
  import Profile.parentForm
  import model.Sec._

  lazy val cNotParent = new Corr("you're not the parent", "login with the parent account and try again")

  val parentForm3 = Form {
    tuple (
      "parentEmail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "childEmail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "childId" -> text,
      "expiry" -> nonEmptyText)
  }

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // step 1 - show the form for user already created (from task)
  def addParent = Action { implicit request =>
    (for (
      u <- auth
    ) yield Ok(views.html.tasks.addParent(parentForm.fill(Email("")), u.ename))) getOrElse
      Unauthorized("Oops - how did you get here?")
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
      Unauthorized("Oops - how did you get here?")
  }

  def userNameChgDenied = Action { implicit request =>
    this dbop UserTasks.userNameChgDenied(auth.get).delete
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
      parentForm.bindFromRequest.fold(
        formWithErrors => {
          error("FORM ERR " + formWithErrors)
          BadRequest(views.html.tasks.addParent(formWithErrors, "")).withSession("ujson" -> session.get("ujson").get)
        },
        {
          case Email(pe) => {
            for (
              uj <- session.get("ujson") orErr ("missing ujson - bad request");
              c <- Users.fromJson(uj) orErr ("cannot parse ujson - bad request");
              res <- Api.createUser(c) orErr ("cannot create in db")
            ) yield {
              UserTasks.addParent(c).create
              UserTasks.verifyEmail(c).create

              sendEmail (pe, c)
            }
          } getOrElse {
            ERR
          }
        })
    } else if (auth.isDefined) {
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
              sendEmail (pe, c)
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

  def sendEmail(pe: String, c: User)(implicit request: Request[_]) = {
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

  def sendToParentAdd(to: String, from: String, childEmail: String, childName: String, link: String) {
    val html = """
Your child %s (%s) would like to use <a href="http://www.racerkidz.com">RacerKidz.com</a> but he/she is 12 years or younger.

Please follow these steps:
<ul>
<li>Register a parent account at <a href="http://%s/doe/profile/joinWith?e=%s">racerkidz.com</a>
<li>Login with <em>your account</em>
<li>Use <a href="%s">this link</a> to add the child account
</ul>
          
Thank you,
The RacerKidz
""".format(childName, childEmail.dec, Config.hostport, EncUrl(to), link);

    SendEmail.send (to, from, "Racer Kid parent - please activate your account", html)
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
          this dbop pro.update (pro.addRel(cid -> "child"))
          this dbop cpro.update(cpro.addRel(p.id -> "parent"))
          this dbop UserTask(child._id, "addParent").delete
          this dbop ParentChild (p._id, child._id).create

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
    ) yield sendEmailVerif (c)) getOrElse {
      ERR
    }
  }

  def sendEmailVerif(c: User)(implicit request: Request[_], mailSession:Option[javax.mail.Session] = None) = {
    val from = "support@racerkidz.com"
    val MSG_EMAIL_VERIF = """
Ok - we sent an email to your registered email address - please follow the instructions in that email to validate your email address and begin using the site.<br>
Please check your spam/junk folders as well in the next few minutes - make sure you mark support@racerkidz.com as a safe sender!"""

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
    Msg(MSG_EMAIL_VERIF, HOME, Some(c)).withSession("connected" -> Enc.toSession(c.email))
  }

  def sendToVerif1(email: String, from: String, name: String, link: String) (implicit mailSession:Option[javax.mail.Session] = None) = {
    val html = """
Hey, %s,
<p>You registered this email address (%s) at <a href="http://www.racerkidz.com">RacerKidz.com</a> and you need to verify it.

Please follow these steps:
<ul>
<li>Login with <em>your account</em>
<li>Use <a href="%s">this link</a> to validate this email
</ul>

<em>The most common problems:</em>
<br> - trying to verify an email is using a different browser to open this link, one where you didn't log in first!
<br> - closing the browser window and then clicking on this email      

<p>
Remember, you have to login first and then click the link - we will improve the process, but for now that's it :(

Cheers,
The RacerKidz
""".format(name, email, link);

    SendEmail.send (email, from, "Racer Kid - please activate your account", html)
  }

  /** step 2 - user clicked on email link to verify email */
  def verifyEmail2(expiry1: String, email: String, id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (expiry1, email, id) match {
      case (Enc(expiry), ce, cid) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- auth orCorr cNoAuth;
          a <- (if (p.email == ce) Some(true) else None) logging ("ERR neq",p.email,ce) orErr "Not same user";
          pro <- p.profile orCorr cNoProfile;
          already <- !(p.hasPerm(Perm.eVerified)) orErr "Already verified"
        ) yield {
          // TODO transaction
          this dbop pro.update (pro.addPerm("+"+Perm.eVerified.s).addPerm("+"+Perm.uWiki.s))
          this dbop UserTasks.verifyEmail(p).delete
          
          // replace in cache
          Users.findUserById(p._id).map { u =>
            import play.api.Play.current
            RazController.cleanAuth(Some(u))
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

}
