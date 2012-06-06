package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import admin.SendEmail
import model.Api
import model.DoSec
import model.Sec._
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

object Profile extends RazController with Logging {
  case class Email(s: String)

  val parentForm = Form {
    mapping (
      "parentEmail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)))(Email.apply)(Email.unapply)
  }

  val registerForm = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repassword" -> text) (model.Registration.apply)(model.Registration.unapply) verifying
      ("Password mismatch - please type again", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
        else true
      }) verifying
      ("Wrong username & password - please type again. (To register a new account, enter the password twice...)", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length <= 0)
          Api.findUser(reg.email.enc).map (u =>
            if (reg.password.enc == u.pwd) true
            else {
              u.auditLoginFailed
              false
            }) getOrElse {
            Audit.wrongLogin(reg.email, reg.password)
            false
          }
        else true
      }) verifying
      ("Email already registered - if you are logging in, type the password once!", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0)
          Api.findUser(reg.email.enc).map (u => false) getOrElse { true }
        else true
      })
  }

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // create profile
  case class CrProfile(firstName: String, lastName: String, yob: Int, address: String, userType: String, accept: Boolean, recaptcha_challenge_field: String, recaptcha_response_field: String)

  def crprofileForm(implicit request: Request[_]) = Form {
    mapping (
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)),
      "accept" -> checked("").verifying("You must accept the Terms of Service to use this site", { x: Boolean => x }),
      "recaptcha_challenge_field" -> text,
      "recaptcha_response_field" -> text) (CrProfile.apply)(CrProfile.unapply) verifying
      ("CAPTCHA failed!", { cr: CrProfile =>
        capthca(cr.recaptcha_challenge_field, cr.recaptcha_response_field, clientIp)
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
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)))(
        //      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)))(
        (f, l, y, a) => User("kuku", f, l, y, "noemail", "nopwd", 'a', Set(), (if (a != null && a.length > 0) Some(a) else None)))(
          (u: User) => Some(u.firstName, u.lastName, u.yob, u.addr.map(identity).getOrElse("")))
  }

  val trusts = Array("Public", "Club", "Friends", "Private")
  val notifiers = Array("Everything", "FriendsOnly", "None")

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

  // join step 1
  def doeJoin = Action { Ok(views.html.join(registerForm)).withSession("gaga" -> System.currentTimeMillis.toString) } // continue with register()

  def doeJoinWith(email: String) = Action {
    log("joinWith email=" + email)
    Ok(views.html.join(registerForm.fill(Registration(email.dec, "", "")))).withSession("gaga" -> System.currentTimeMillis.toString)
  } // continue with register()

  // join step 2
  def doeJoin2 = Action { implicit request =>
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
            Msg2("Session expired - You could try again", Some("/doe/join")).withNewSession
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
          Redirect (routes.Profile.doeJoin).withNewSession
        }
      case None => // capture basic profile and create profile
        Redirect(routes.Profile.doeJoin3).flashing("email" -> reg.email, "pwd" -> reg.password)
      //getOrElse InternalServerError("Oops - cannot create this user...")
    }
  }

  def doeJoin3 = Action { implicit request =>
    {
      for (
        e <- flash.get("email");
        p <- flash.get("pwd")
      ) yield Ok(views.html.user.join3(crprofileForm.fill(
        CrProfile("", "", 13, "", "racer", false, "", "")), auth)).withSession("pwd" -> p, "email" -> e)
    } getOrElse
      Unauthorized("Oops - how did you get here?").withNewSession
  }

  // join step 4
  def doeCreateProfile = Action { implicit request =>
    implicit val errCollector = new VError()
    val resp = crprofileForm.bindFromRequest
    resp.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.join3(formWithErrors, auth))
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, y, addr, ut, accept, challenge, response) =>
          (for (
            p <- session.get("pwd") orErr ("psession corrupted");
            e <- session.get("email") orErr ("esession corrupted");
            u <- Some(User(System.currentTimeMillis.toString, f, l, y, Enc(e), Enc(p), 'a', Set(ut)));
            res <- // finally created a new account/profile
            if (u.under12) {
              Some(Redirect(routes.Tasks.addParent1).withSession("ujson" -> u.toJson))
            } else Api.createUser(u) map { x =>
              UserTasks.verifyEmail(u).create
              RegdEmail(u.email.dec).delete
              Redirect("/").withSession("connected" -> u.email)
            }
          ) yield res) getOrElse
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
    implicit val errCollector = new VError()
    (for (
      u <- auth;
      c <- Users.findUserById(child)
    ) yield {
      log ("PC " + u._id + "        " + c._id)
      log ("PC " + Users.findPC(u._id, c._id))
      val ParentChild(_, _, t, n, _) = Users.findPC(u._id, c._id).getOrElse(ParentChild(null, null, "Private", "Everything"))
      Ok(views.html.user.edChildren(edprofileForm2.fill((t, n)), child, u))
    }) getOrElse Unauthorized("Oops - how did you get here? " + errCollector.mkString)
  }

  def profile2u(child: String) = Action { implicit request =>
    implicit val errCollector = new VError()
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

  def doeUpdateProfile = Action { implicit request =>
    edprofileForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edBasic(formWithErrors, auth.get)),
      {
        case u: User =>
          (for (
            au <- auth orErr ("not authenticated")
          ) yield {
            au.update(User(au.userName, u.firstName, u.lastName, au.yob, au.email, au.pwd, au.status, au.roles, u.addr, au._id))
            au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
            Redirect("/") //.withSession("connected" -> u.email)
          }) getOrElse
            Unauthorized("Oops - how did you get here?")
      })
  }

  // authenticated means doing a task later
  def doeChgPass = Action { implicit request =>
    (for (u <- auth)
      yield Ok(views.html.user.edPassword(chgpassform, auth.get))) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

  def doeChgPass2 = Action { implicit request =>
    implicit val errCollector = new VError()
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
            au.update(User(au.userName, au.firstName, au.lastName, au.yob, au.email, Enc(n), au.status, au.roles, au.addr, au._id))
            Msg2("Ok, password changed!")
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  def publicProfile = Action { implicit request =>
    (for (au <- auth) yield {
      if (Wikis.withIndex(_.get2("User", au.userName).isDefined))
        Redirect (routes.Wiki.show1("User", au.userName))
      else
        Redirect (routes.Wiki.edit("User", au.userName))
    }) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

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
    implicit val errCollector = new VError()
    chgusernameform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edUsername(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            u <- auth orErr ("not authenticated");
            ok <- (if (o == u.userName) Some(true) else None) orCorr Corr("Not correct old username")
          ) yield {
            Emailer.sendEmailUname(n, u)
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
  }

  // logged in as ADMIN
  def accept(expiry1: String, userId: String, newusername: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (expiry1, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
          // play 2.0 workaround - remove in play 2.1
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => (try { Option(DateTime.parse(expiry1.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired");
          notExpired <- date.isAfterNow orCorr cExpired;
          admin <- auth orCorr cNoAuth;
          a <- (if (hasPerm(Perm.adminDb)) Some(true) else None) orCorr Corr("Not authorized");
          u <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(u.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          u.update(User(newusername, u.firstName, u.lastName, u.yob, u.email, u.pwd, u.status, u.roles, u.addr, u._id))
          this dbop UserTasks.userNameChgDenied(u).create
          Wikis.updateUserName (u.userName, newusername)
          Emailer.sendEmailUnameOk(newusername, u)

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
    implicit val errCollector = new VError()
    (expiry, userId, newusername) match {
      case (Enc(expiry), _, _) => {
        for (
          date <- (try { Option(DateTime.parse(expiry)) } catch { case _ => None }) orErr ("token faked: " + expiry);
          notExpired <- date.isAfterNow orCorr cExpired;
          u <- auth orCorr cNoAuth;
          a <- (if (hasPerm(Perm.adminDb)) Some(true) else None) orCorr Corr("Not authorized");
          user <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(user.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          this dbop UserTasks.userNameChgDenied(u).create
          Emailer.sendEmailUnameDenied(newusername, u)
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

}

object EdEmail extends RazController {
  // profile
  val chgemailform = Form {
    tuple(
      "curemail" -> text,
      "newemail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Sorry - already in use", { t: (String, String) => !Api.findUser(t._2.enc).isDefined })
  }

  // authenticated means doing a task later
  def step1(userId: String) = Action { implicit request =>
    (for (
      au <- auth orCorr cNoAuth;
      u <- Users.findUserById(userId) orErr ("user account not found");
      ok <- (if (au._id == u._id) Some(true) else None) orCorr Corr("Not correct user")
    ) yield Ok(views.html.user.edEmail(chgemailform.fill(u.email.dec, ""), auth.get))) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

  def step2(userId: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    chgemailform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edEmail(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            au <- auth orCorr cNoAuth;
            u <- Users.findUserById(userId) orErr ("user account not found");
            ok <- (if (au._id == u._id) Some(true) else None) orCorr Corr("Not correct user")
          ) yield {
            val newu = User(u.userName, u.firstName, u.lastName, u.yob, n.enc, u.pwd, u.status, u.roles, u.addr, u._id)
            u.update(newu)
            val pro = newu.profile.getOrElse(newu.mkProfile)
            this dbop pro.update (pro.removePerm("+"+Perm.eVerified.s))
            this dbop UserTasks.verifyEmail(newu).create
            Tasks.sendEmailVerif(newu)
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_EMAIL ")
            Unauthorized("Oops - cannot update this user... " + errCollector.mkString)
          }
      })
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
