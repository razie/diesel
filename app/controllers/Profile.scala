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

object Profile extends RazController with Logging {
  case class Email(s: String)

  val parentForm = Form {
    mapping (
      "parentEmail" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)))(Email.apply)(Email.unapply)
  }

  val parentForm3 = Form {
    tuple (
      "parentEmail" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")),
      "childEmail" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")),
      "childId" -> text,
      "expiry" -> nonEmptyText)
  }

  val registerForm = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)),
      "password" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repassword" -> text) (model.Registration.apply)(model.Registration.unapply) verifying
      ("Password mismatch - please type again", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
        else true
      }) verifying
      ("Wrong username & password - please type again. (To register a new account, you have to verify the password)", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length <= 0 && (reg.password.length > 0))
          Api.findUser(Users.enc(reg.email)).map (u =>
            if (Users.enc(reg.password) == u.pwd) true
            else {
              u.auditLoginFailed
              false
            }) getOrElse {
            Audit.wrongLogin(reg.email, reg.password)
            false
          }
        else true
      })
  }

  // create profile
  case class CrProfile(firstName: String, lastName: String, yob: Int, email: String, userType: String, recaptcha_challenge_field: String, recaptcha_response_field: String)
  val crprofileForm = Form {
    mapping (
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "email" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)),
      "recaptcha_challenge_field" -> text,
      "recaptcha_response_field" -> text) (CrProfile.apply)(CrProfile.unapply) verifying
      ("Oops - this email is already registered...", { cru =>
        Api.findUser(Enc(cru.email)).map { u =>
          audit("ERR tried to create a profile again " + u)
          false
        } getOrElse true
      })
  }

  def capthca(challenge: String, response: String, clientIp: String) = {
    val resp = Snakk.body(
      Snakk.url(
        "http://www.google.com/recaptcha/api/verify",
        razie.AA("privatekey", "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip", "kk", "challenge", challenge, "response", response),
        "POST"))

    println("RESP=" + resp)

    resp.startsWith("true")
  }

  // profile
  val edprofileForm = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "email" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)))(
        //      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)))(
        (f, l, y, e) => User("kuku", f, l, y, Users.enc(e), "kuku", 'a'))(
          (u: User) => Some(u.firstName, u.lastName, u.yob, u.email.dec))
  }

  val trusts = Array("Public", "Moderated", "ParentModerated", "Private")
  val notifiers = Array("Everything", "Content", "None")

  // profile
  val edprofileForm2 = Form {
    tuple(
      "trust" -> nonEmptyText.verifying("Please select one", ut => trusts.contains(ut)),
      "notify" -> nonEmptyText.verifying("Please select one", ut => notifiers.contains(ut)))
  }

  // profile
  val chgpassform = Form {
    tuple(
      "currpass" -> text,
      "newpass" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repass" -> text) verifying
      ("Password mismatch - please type again", { t: (String, String, String) =>
        if (t._3.length > 0 && t._3.length > 0 && t._3 != t._2) false
        else true
      })
  }

  def join = Action { Ok(views.html.join(registerForm)).withSession("gaga" -> System.currentTimeMillis.toString) } // continue with register()

  def joinWith(email: String) = Action {
    log("joinWith email=" + email)
    Ok(views.html.join(registerForm.fill(Registration(email.dec, "", "")))).withSession("gaga" -> System.currentTimeMillis.toString)
  } // continue with register()

  def register = Action { implicit request =>
    registerForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.join(formWithErrors)).withSession("gaga" -> System.currentTimeMillis.toString),
      {
        case reg @ model.Registration(e, p, r) => {
          val g = try {
            (session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _ => 1
          }

          if (System.currentTimeMillis - g <= 120000) {
            if (p == null || p.size <= 0)
              registerEmailOnly(reg)
            else
              login(reg)
          } else {
            Msg2("Session expired - You could try again", Some("/do/join")).withNewSession
          }
        }
      })
  }

  def registerEmailOnly(reg: Registration) = {
    Audit.regdemail (reg.email)
    RegdEmail(reg.email).create
    Ok(views.html.thankyou(reg.ename)).withNewSession
  }

  def login(reg: model.Registration) = {
    Api.findUser(Enc(reg.email)) match {
      case Some(u) =>
        if (Enc(reg.password) == u.pwd) {
          u.auditLogin
          Redirect("/").withSession("connected" -> u.email)
        } else {
          u.auditLoginFailed
          Redirect (routes.Application.show("join")).withNewSession
        }
      case None => // capture basic profile and create profile
        Redirect(routes.Application.show("basicprofile")).flashing("email" -> reg.email, "pwd" -> reg.password)
      //getOrElse InternalServerError("Oops - cannot create this user...")
    }
  }

  def basicprofile = Action { implicit request =>
    {
      for (
        e <- flash.get("email");
        p <- flash.get("pwd")
      ) yield Ok(views.html.user.crprofile(crprofileForm.fill(
        CrProfile("", "", 13, e, "racer", "", "")), auth)).withSession("pwd" -> p)
    } getOrElse
      Unauthorized("Oops - how did you get here?").withNewSession
  }

  // probably forwarded by apache proxy
  def clientIp(implicit request: Request[_]) =
    request.headers.get("X-Forwarded-For").getOrElse(request.headers.get("RemoteIP").getOrElse("x.x.x.x"))

  def createProfile = Action { implicit request =>
    implicit val errCollector = new Error()
    val resp = crprofileForm.bindFromRequest
    resp.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.crprofile(formWithErrors, auth))
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, y, e, ut, challenge, response) =>
          if (!capthca(challenge, response, clientIp)) {
            warn("CAPTCHCA FAIL " + resp)
            BadRequest(views.html.user.crprofile(resp, auth))
          } else {
            for (
              p <- session.get("pwd") orErr ("no pwd in session");
              u <- Some(User(System.currentTimeMillis.toString, f, l, y, Enc(e), Enc(p), 'a', Seq(ut)));
              res <- // finally created a new account/profile
              if (u.under12) {
                Some(Redirect("/doe/profile/addParent1").withSession("ujson" -> u.toJson))
              } else Api.createUser(u) map { x =>
                UserTask(u._id, "verifyEmail").create
                RegdEmail(u.email.dec).delete
                Redirect("/").withSession("connected" -> u.email)
              }
            ) yield res
          } getOrElse
            {
              error("ERR_CANT_UPDATE_USER " + session.get("email"))
              Unauthorized("Oops - cannot update this user... " + errCollector.mkString).withNewSession
            }
      })
  }

  def profile = Action { implicit request =>
    auth map { u => Ok(views.html.user.edBasic(edprofileForm.fill(u), u)) } getOrElse Unauthorized("Oops - how did you get here?")
  }

  def profile2(child: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    (for (
      u <- auth;
      c <- Users.findUserById(child)
    ) yield {
      log ("PC " + u._id + "        " + c._id)
      log ("PC " + Users.findPC(u._id, c._id))
      val ParentChild(_, _, t, n, _) = Users.findPC(u._id, c._id).getOrElse(ParentChild(null, null, "Moderated", "Content"))
      Ok(views.html.user.edChildren(edprofileForm2.fill((t, n)), child, u))
    }) getOrElse Unauthorized("Oops - how did you get here? " + errCollector.mkString)
  }

  def profile2u(child: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    edprofileForm2.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.edChildren(formWithErrors, child, auth.get))
      },
      {
        case (t, n) => {
          for (
            u <- auth orErr ("not authenticated");
            c <- Users.findUserById(child)
          ) yield {
            Users.findPC(u._id, c._id) match {
              case Some(pc) => this dbop pc.update(ParentChild(u._id, c._id, t, n, pc._id))
              case None     => this dbop ParentChild(u._id, c._id, t, n).create
            }
            Redirect("/")
          }
        } getOrElse
          {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  def updateProfile = Action { implicit request =>
    edprofileForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edBasic(formWithErrors, auth.get)),
      { u: User =>
        (for (
          au <- auth orErr ("not authenticated")
        ) yield {
          au.update(User(au.userName, u.firstName, u.lastName, au.yob, au.email, au.pwd, u.status, au.roles, au._id))
          Redirect("/").withSession("connected" -> u.email)
        }) getOrElse
          Unauthorized("Oops - how did you get here?")
      })
  }

  // step 1 - show the form for user already created (from task)
  def addParent(implicit request: Request[_]) = {
    (for (u <- auth)
      yield Ok(views.html.tasks.addParent(parentForm.fill(Email("")), u.ename))) getOrElse
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

  // step 2 - 
  def addParent2 = Action { implicit request =>
    implicit val errCollector = new Error()
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
              UserTask(c._id, "addParent").create
              UserTask(c._id, "verifyEmail").create

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
    val hc1 = """/doe/profile/addParent3?expiry=%s&parentEmail=%s&childEmail=%s&childId=%s""".format(EncUrl(dt), EncUrl(pe), Enc.toUrl(c.email), c.id)
    log("ENC_LINK1=" + hc1)
    val ds = DoSec(hc1)
    log("ENC_LINK2=" + ds.secUrl)

    sendToParentAdd(pe, from, c.email, c.ename, ds.secUrl)
    Msg("Ok - we sent an email - please ask your parent to follow the instructions in that email. " +
      "" +
      "They have to first register and then follow this [link](" +
      ds.secUrl + ")", "Page", "home", Some(c)).withSession("connected" -> c.email)
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
""".format(childName, Users.dec(childEmail), Config.hostport, EncUrl(to), link);

    SendEmail.send (to, from, "Racer Kid parent - please activate your account", html)
  }

  def addParent3(expiry: String, parentEmail: String, childEmail: String, childId: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    (expiry, parentEmail, childEmail, childId) match {
      case (Enc(expiry), pe, ce, cid) => {
        for (
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => None }) orErr ("token faked: " + expiry);
          notExpired <- date.isAfterNow orCorr cExpired;
          p <- auth orCorr cLogin;
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

Please read our [[Terms and Conditions]] as well as our [[Privacy Policy]]
""", "Page", "home")
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
        }
    }
  }

  // authenticated means doing a task later
  def chgPass = Action { implicit request =>
    (for (u <- auth)
      yield Ok(views.html.user.edPassword(chgpassform, auth.get))) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

  def chgPass2 = Action { implicit request =>
    implicit val errCollector = new Error()
    chgpassform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edPassword(formWithErrors, auth.get)),
      {
        case (o, n, _) =>
          (for (
            au <- auth orErr ("not authenticated");
            pwdCorrect <- {
              (if (Enc(o) == au.pwd) Some(true) else None) orErr ("Password incorrect!")
            }
          ) yield {
            au.update(User(au.userName, au.firstName, au.lastName, au.yob, au.email, Enc(n), au.status, au.roles, au._id))
            Msg2("Ok, password changed!")
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  lazy val cNotParent = new Corr("you're not the parent", "login with the parent account and try again")
}

object EdUsername extends RazController {
  // profile
  val chgusernameform = Form {
    tuple(
      "currusername" -> text,
      "newusername" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4))) verifying
      ("Can't use the same name", { t: (String, String) => t._1 != t._2 }) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByUsername(t._2).isDefined })
  }

  // authenticated means doing a task later
  def step1 = Action { implicit request =>
    (for (u <- auth)
      yield Ok(views.html.user.edUsername(chgusernameform.fill(u.userName, ""), auth.get))) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

  def step2 = Action { implicit request =>
    implicit val errCollector = new Error()
    chgusernameform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edUsername(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            u <- auth orErr ("not authenticated");
            ok <- (if (o == u.userName) Some(true) else None) orCorr Corr("Not correct old username")
          ) yield {
            sendEmailUname(n, u)
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  // logged in as ADMIN
  def accept(expiry: String, userId: String, newusername: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    (expiry, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => None }) orErr ("token faked: " + expiry);
          notExpired <- date.isAfterNow orCorr cExpired;
          u <- auth orCorr cLogin;
          a <- (if (hasPerm("adminDb")) Some(true) else None) orCorr Corr("Not authorized");
          user <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(user.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          u.update(User(newusername, u.firstName, u.lastName, u.yob, u.email, u.pwd, u.status, u.roles, u._id))
          UserTask(u._id, "verifyEmail").create
          this dbop UserTask(u._id, "userNameChgDenied").delete
          sendEmailUnameOk(newusername, u)

          Msg("""
Ok, username changed.
""", "Page", "home")
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
        }
    }
  }

  // logged in as ADMIN
  def deny(expiry: String, userId: String, newusername: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    (expiry, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => None }) orErr ("token faked: " + expiry);
          notExpired <- date.isAfterNow orCorr cExpired;
          u <- auth orCorr cLogin;
          a <- (if (hasPerm("adminDb")) Some(true) else None) orCorr Corr("Not authorized");
          user <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(user.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          UserTask(u._id, "userNameChgDenied").create
          sendEmailUnameDenied(newusername, u)
          Msg("""
Ok, username notified
""", "Page", "home")
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
        }
    }
  }

  def sendEmailUname(newUsername: String, u: User)(implicit request: Request[_]) = {
    val from = "support@racerkidz.com"
    val to = "support@racerkidz.com"

    val dt = DateTime.now().plusDays(1)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val ds1 = DoSec(hc1, dt)
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val ds2 = DoSec(hc2, dt)

    val html1 = """
User requested username change.
<p>
old username %s
new username %s
<p>
Action
<ul>
<li><a href="%s">Accept</a>
<li><a href="%s">Deny</a>
</ul>
<p>
Thank you,
<br>The RacerKidz
""".format(u.userName, newUsername, ds1.secUrl, ds2.secUrl);

    SendEmail.send (to, from, "RacerKidz - username change request", html1)

    val html2 = """
Hello %s,
<p>
You requested to change your username. We'll review your request and let you know.
<p>
Changing username from %s -> %s
<p>
Thank you,
<br>The RacerKidz
""".format(u.ename, u.userName, newUsername)

    SendEmail.send (Users.dec(u.email), from, "RacerKidz - username change request", html2)

    Msg("Ok - we sent a reuquest - we'll review it asap and let you know.",
      "Page", "home", Some(u))
  }

  def sendEmailUnameOk(newUsername: String, u: User)(implicit request: Request[_]) = {
    val from = "support@racerkidz.com"

    val html1 = """
Hello %s,
<p>
Your username has been approved and changed to %s
<p>
Thank you,
<br>The RacerKidz
""".format(u.ename, u.userName);

    SendEmail.send (u.email.dec, from, "RacerKidz :) username change approved", html1)
  }

  def sendEmailUnameDenied(newUsername: String, u: User)(implicit request: Request[_]) = {
    val from = "support@racerkidz.com"

    val html1 = """
Hello %s,
<p>
Sorry - your new username request has been denied. Please try another username.
<p>
Note that you don't need a username to use the site.
<p>
Thank you,
<br>The RacerKidz
""".format(u.ename, u.userName);

    SendEmail.send (u.email.dec, from, "RacerKidz :( username change denied", html1)
  }

}

object TestCaphct extends App {
//  val resp = Snakk.body(
//    Snakk.url(
//      "http://www.google.com/recaptcha/api/verify",
//      razie.AA("privatekey", "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip", "kk", "challenge", "cc", "response", "ss"),
//      "POST"))
//
//  println("RESP=" + resp)
  
  val s = """http://localhost:9000/doe/profile/unameDeny?expiry=EinIQ3UuYjP%2Bl2EzvRJuuRjLyCAWOpqTcCQ9AbyDp8E%3D&userId=4fae5f2b0cf23a3faa46794f&newusername=fufu"""
  val s1 = """EinIQ3UuYjP%2Bl2EzvRJuuRjLyCAWOpqTcCQ9AbyDp8E%3D"""
  val s2 = """EinIQ3UuYjP+l2EzvRJuuRjLyCAWOpqTcCQ9AbyDp8E="""
  val s3 = """EinIQ3UuYjP l2EzvRJuuRjLyCAWOpqTcCQ9AbyDp8E="""
  val s4 = """RWluSVEzVXVZalAgbDJFenZSSnV1UmpMeUNBV09wcVRjQ1E5QWJ5RHA4RT0="""
  println (Enc.fromUrl(s1))
  println (Enc.unapply(s2))
//  println (Enc.fromUrl(s2))
//  println (Base64 --> s3)
//  println (DateTime.parse(s4) + "")
  
}
