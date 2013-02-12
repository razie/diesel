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
import model.UserType
import model.WikiIndex

object Profile extends RazController with Logging {
  case class Email(s: String)

  val parentForm = Form {
    mapping(
      "parentEmail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)))(Email.apply)(Email.unapply)
  }

  val prefsForm = Form {
    tuple(
      "css" -> nonEmptyText.verifying("Wrong value!", Array("dark", "light").contains(_)).verifying("Invalid characters", vldSpec(_)),
      "favQuote" -> text.verifying("Invalid characters", vldSpec(_))) verifying
      ("Password mismatch - please type again", { t: (String, String) =>
        val (css, favQuote) = t
        true
      })
  }

  val registerForm = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> nonEmptyText.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repassword" -> text)(model.Registration.apply)(model.Registration.unapply) verifying
      ("Password mismatch - please type again", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
        else true
      }) verifying
      ("Wrong username or password - please type again. (To register a new account, enter the password twice...)", { reg: Registration =>
        //          println ("======="+reg.email.enc+"======="+reg.password.enc)
        if (reg.password.length > 0 && reg.repassword.length <= 0)
          // TODO optimize - we lookup users twice on loing
          Users.findUser(reg.email.enc).orElse(Users.findUserNoCase(reg.email)).map { u =>
            //          println ("======="+u.email+"======="+u.pwd)
            if (reg.password.enc == u.pwd) true
            else {
              u.auditLoginFailed
              false
            }
          } getOrElse {
            Audit.wrongLogin(reg.email, reg.password)
            false
          }
        else true
      }) verifying
      ("Email already registered - if you are logging in, type the password once!", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0)
          Api.findUser(reg.email.enc).map(u => false) getOrElse { true }
        else true
      })
  }

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // create profile
  case class CrProfile(firstName: String, lastName: String, yob: Int, address: String, userType: String, accept: Boolean, recaptcha_challenge_field: String, recaptcha_response_field: String)

  def crProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Config.userTypes.contains(ut)),
      "accept" -> checked("").verifying("You must accept the Terms of Service to use this site", { x: Boolean => x }),
      "recaptcha_challenge_field" -> text,
      "recaptcha_response_field" -> text)(CrProfile.apply)(CrProfile.unapply) verifying
      ("CAPTCHA failed!", { cr: CrProfile =>
        capthca(cr.recaptcha_challenge_field, cr.recaptcha_response_field, clientIp)
      }) verifying
      ("Can't use last name for organizations!", { cr: CrProfile =>
        cr.userType != UserType.Organization.toString || cr.lastName.length <= 0
      })
  }

  def capthca(challenge: String, response: String, clientIp: String) =
    Config.isLocalhost || {
      val resp = Snakk.body(
        Snakk.url(
          "http://www.google.com/recaptcha/api/verify",
          razie.AA("privatekey", "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip", "kk", "challenge", challenge, "response", response),
          "POST"))

      debug("CAPTCHCA RESP=" + resp)

      resp.startsWith("true")
    }

  // profile
  def edProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Config.userTypes.contains(ut)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)))(
        (f, l, t, y, a) => User("kuku", f, l, y, "noemail", "nopwd", 'a', Set(t), (if (a != null && a.length > 0) Some(a) else None)))(
          (u: User) => Some(u.firstName, u.lastName, u.roles.head, u.yob, u.addr.map(identity).getOrElse(""))) verifying
          ("Can't use last name for organizations!", { u: User =>
            (!(auth.get.isClub)) || u.lastName.length <= 0
          })
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
  def doeJoin(club: String, role: String, next: String) = Action {
    Ok(views.html.join(registerForm)).withSession(
      "gaga" -> System.currentTimeMillis.toString,
      "extra" -> "%s,%s,%s".format(club, role, next))
  } // continue with register()

  def doeJoinWith(email: String) = Action {
    log("joinWith email=" + email)
    Ok(views.html.join(registerForm.fill(Registration(email.dec, "", "")))).withSession("gaga" -> System.currentTimeMillis.toString)
  } // continue with register()

  // join step 2 - submited email/pass form
  def doeJoin2 = Action { implicit request =>
    registerForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.join(formWithErrors)).withSession("gaga" -> System.currentTimeMillis.toString,
        "extra" -> session.apply("extra")),
      {
        case reg @ model.Registration(e, p, r) => {
          val g = try {
            (session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _ => 1
          }

          if (System.currentTimeMillis - g <= 120000) {
            if (p == null || p.size <= 0)
              // this is de-activated, password is now required
              registerEmailOnly(reg)
            else
              login(reg, session.apply("extra"))
          } else {
            Msg2("Session expired - You could try again", Some("/doe/join")).withNewSession
          }
        }
      })
  }

  def updateUser(old: User, newU: User)(implicit request: Request[_]) = {
    old.update(newU)
    RazController.cleanAuth(Some(newU))
    newU
  }

  def registerEmailOnly(reg: Registration) = {
    Audit.regdemail(reg.email)
    RegdEmail(reg.email).create
    Ok(views.html.thankyou(reg.ename)).withNewSession
  }

  /** login or start registration */
  def login(reg: model.Registration, extra: String) = {
    // TODO optimize - we lookup users twice on login
    Users.findUser(Enc(reg.email)) orElse (Users.findUserNoCase(reg.email)) match {
      case Some(u) =>
        if (Enc(reg.password) == u.pwd) {
          u.auditLogin
          debug("SEss.conn=" + ("connected" -> Enc.toSession(u.email)))
          Redirect("/").withSession("connected" -> Enc.toSession(u.email))
        } else {
          u.auditLoginFailed
          Redirect(routes.Profile.doeJoin()).withNewSession
        }
      case None => // capture basic profile and create profile
        Redirect(routes.Profile.doeJoin3).flashing("email" -> reg.email, "pwd" -> reg.password, "extra" -> extra)
      //getOrElse InternalServerError("Oops - cannot create this user...")
    }
  }

  /** start registration long form - submit is doeCreateProfile */
  def doeJoin3 = Action { implicit request =>
    {
      for (
        e <- flash.get("email");
        p <- flash.get("pwd")
      ) yield Ok(views.html.user.join3(crProfileForm.fill(
        CrProfile("", "", 13, "", "racer", false, "", "")), auth)).withSession("pwd" -> p, "email" -> e, "extra" -> flash.apply("extra"))
    } getOrElse
      unauthorized("Oops - how did you get here?").withNewSession
  }

  /** join step 4 - after captcha */
  def doeCreateProfile = Action { implicit request =>
    implicit val errCollector = new VError()
    val resp = crProfileForm.bindFromRequest
    resp.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.join3(formWithErrors, auth)).withSession("pwd" -> session.apply("pwd"), "email" -> session.apply("email"), "extra" -> session.apply("extra"))
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, y, addr, ut, accept, challenge, response) =>
          (for (
            p <- session.get("pwd") orErr ("psession corrupted");
            e <- session.get("email") orErr ("esession corrupted");
            already <- (!Users.findUser(Enc(e)).isDefined) orCorr ("User already created" -> "patience, patience...");
            u <- Some(User(System.currentTimeMillis.toString, f.trim, l.trim, y, Enc(e), Enc(p), 'a', Set(ut), (if (addr != null && addr.length > 0) Some(addr) else None)))
          ) yield {
            // finally created a new account/profile
            if (u.under12) {
              Redirect(routes.Tasks.addParent1).withSession("ujson" -> u.toJson, "extra" -> session.apply("extra"))
            } else {
              Api.createUser(u)
              val extra = session.get("extra")
              UserTasks.verifyEmail(u).create
              RegdEmail(u.email.dec).delete
              // TODO why the heck am i sleeping?
              Thread.sleep(5000)
              SendEmail.withSession { implicit mailSession =>
                Tasks.sendEmailVerif(u)
                val uname = (u.firstName + (if (u.lastName.length > 0) ("." + u.lastName) else "")).replaceAll("[^a-zA-Z0-9\\.]", ".").replaceAll("[\\.\\.]", ".")
                Emailer.sendEmailUname(uname, u)
              }

              extra.map { x =>
                val s = x split ","
                if (s.size > 0 && s(0).length > 0) {
                  Tasks.msgVerif(u, Some("/wikie/linkuser/Club:" + s(0) + "?wc=0"))
                } else
                  Tasks.msgVerif(u, Some("/"))
              } getOrElse Tasks.msgVerif(u, Some("/"))
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            unauthorized("Oops - cannot update this user - Please try again or send a suport request!").withNewSession
          }
      }) //fold
  }

  /** show profile **/
  def profile = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth;
      isA <- checkActive(au)
    ) yield {
      Ok(views.html.user.edBasic(edProfileForm.fill(au), au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** show children in profile **/
  def profile2(child: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth;
      isA <- checkActive(au);
      c <- Users.findUserById(child)
    ) yield {
      log("PC " + au._id + "        " + c._id)
      log("PC " + Users.findPC(au._id, c._id))
      val ParentChild(_, _, t, n, _) = Users.findPC(au._id, c._id).getOrElse(ParentChild(null, null, "Private", "Everything"))
      Ok(views.html.user.edChildren(edprofileForm2.fill((t, n)), child, au))
    }) getOrElse unauthorized("Oops - how did you get here? ")
  }

  /** edited children in profile **/
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
            au <- auth orErr ("not authenticated");
            isA <- checkActive(au);
            c <- Users.findUserById(child)
          ) yield {
            Users.findPC(au._id, c._id) match {
              case Some(pc) => this dbop pc.update(ParentChild(au._id, c._id, t, n, pc._id))
              case None => this dbop ParentChild(au._id, c._id, t, n).create
            }
            Redirect("/")
          }
        } getOrElse
          {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            unauthorized("Oops - cannot update this user... ")
          }
      })
  }

  def doeUpdPrefs = Action { implicit request =>
    prefsForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.userPreferences(formWithErrors, auth.get)),
      {
        case (css, favQuote) => forActiveUser { au =>
          val u = updateUser(au, User(au.userName, au.firstName, au.lastName, au.yob, au.email, au.pwd, au.status, au.roles, au.addr, au.prefs ++
            Seq("css" -> css, "favQuote" -> favQuote),
            au._id))
          Emailer.withSession { implicit mailSession =>
            au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
          }
          Ok(views.html.user.userPreferences(prefsForm.fill((u.pref("css")("dark"), u.pref("favQuote")(""))), u))
        }
      })
  }

  def doePreferences = Action { implicit request =>
    forActiveUser { au =>
      Ok(views.html.user.userPreferences(prefsForm.fill((au.pref("css")("dark"), au.pref("favQuote")(""))), au))
    }
  }

  // TODO
  def doeUpdPref(name: String) = Action { implicit request =>
    forActiveUser { au =>
      Ok(views.html.user.profileHelp(au))
    }
  }

  def doeHelp = Action { implicit request =>
    forActiveUser { au =>
      Ok(views.html.user.profileHelp(au))
    }
  }

  def doeUpdateProfile = Action { implicit request =>
    edProfileForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edBasic(formWithErrors, auth.get)),
      {
        case u: User =>
          forActiveUser { au =>
            updateUser(au, User(au.userName, u.firstName, u.lastName, au.yob, au.email, au.pwd, au.status, au.roles, u.addr, au.prefs, au._id))
            Emailer.withSession { implicit mailSession =>
              au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
            }
            RazController.cleanAuth(Some(au))
            Redirect("/")
          }
      })
  }

  // authenticated means doing a task later
  def doeChgPass = Action { implicit request =>
    forActiveUser { au =>
    Ok(views.html.user.edPassword(chgpassform, au))
  }
  }

  def doeChgPass2 = Action { implicit request =>
    implicit val errCollector = new VError()
    chgpassform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edPassword(formWithErrors, auth.get)),
      {
        case (o, n, _) =>
          (for (
            au <- auth orErr ("not authenticated");
            isA <- checkActive(au);
            pwdCorrect <- {
              (if (Enc(o) == au.pwd || ("ADMIN" + au.pwd == o)) Some(true) else None) orErr ("Password incorrect!")
              // the second form is hack to allow me to reset it
            }
          ) yield {
            updateUser(au, User(au.userName, au.firstName, au.lastName, au.yob, au.email, Enc(n), au.status, au.roles, au.addr, au.prefs, au._id))
            Msg2("Ok, password changed!")
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            unauthorized("Oops - cannot update this user... ")
          }
      })
  }

  def publicProfile = Action { implicit request =>
    (for (au <- auth) yield {
      if (WikiIndex.withIndex(_.get2(au.userName, WID("User", au.userName)).isDefined))
        Redirect(controllers.Wiki.w(WID("User", au.userName)))
      else
        Redirect(routes.Wiki.wikieEdit(WID("User", au.userName)))
    }) getOrElse
      unauthorized("Oops - how did you get here?")
  }

}

object EdUsername extends RazController {
  // profile
  val chgusernameform = Form {
    tuple(
      "currusername" -> text,
      "newusername" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4)).verifying("No spaces please", p => !p.contains(" "))) verifying
      ("Can't use the same name", { t: (String, String) => t._1 != t._2 }) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByUsername(t._2).isDefined })
  }

  // authenticated means doing a task later
  def step1 = Action { implicit request =>
    forActiveUser { au =>
    Ok(views.html.user.edUsername(chgusernameform.fill(au.userName, ""), au))
    }
  }

  def step2 = Action { implicit request =>
    implicit val errCollector = new VError()
    chgusernameform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edUsername(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            au <- auth orErr ("not authenticated");
            isA <- checkActive(au);
            ok <- (o == au.userName) orErr ("Not correct old username")
          ) yield {
            SendEmail.withSession { implicit mailSession =>
              Emailer.sendEmailUname(n, au)
            }
            Msg("Ok - we sent a request - we'll review it asap and let you know.",
              HOME, Some(au))
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_PASSWORD ")
            unauthorized("Oops - cannot update this user... ")
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
          a <- admin.hasPerm(Perm.adminDb) orCorr cNoPermission;
          u <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(u.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          Profile.updateUser(u, User(newusername, u.firstName, u.lastName, u.yob, u.email, u.pwd, u.status, u.roles, u.addr, u.prefs, u._id))
          this dbop UserTasks.userNameChgDenied(u).delete
          Wikis.updateUserName(u.userName, newusername)
          Emailer.withSession { implicit mailSession =>
          }
          Emailer.withSession { implicit mailSession =>
            Emailer.sendEmailUnameOk(newusername, u)
          }
          RazController.cleanAuth(Some(u))

          Msg("""
Ok, username changed.
""", HOME)
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          unauthorized("Oops - cannot update this user... ")
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
          a <- u.hasPerm(Perm.adminDb) orCorr Corr("Not authorized");
          user <- Users.findUserById(userId) orErr ("user account not found");
          already <- !(user.userName == newusername) orErr "Already updated"
        ) yield {
          // TODO transaction
          this dbop UserTasks.userNameChgDenied(u).create
          Emailer.withSession { implicit mailSession =>
            Emailer.sendEmailUnameDenied(newusername, u)
          }
          Msg("""
Ok, username notified
""", HOME)
        }
      } getOrElse
        {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          unauthorized("Oops - cannot update this user... ")
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
      isA <- checkActive(au);
      ok <- (au._id == u._id) orErr ("Not correct user")
    ) yield Ok(views.html.user.edEmail(chgemailform.fill(u.email.dec, ""), auth.get))) getOrElse
      unauthorized("Oops - how did you get here?")
  }

  def step2(userId: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    chgemailform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.edEmail(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            au <- auth orCorr cNoAuth;
            isA <- checkActive(au);
            u <- Users.findUserById(userId) orErr ("user account not found");
            ok <- (au._id == u._id) orErr ("Not correct user")
          ) yield {
            val newu = User(u.userName, u.firstName, u.lastName, u.yob, n.enc, u.pwd, u.status, u.roles, u.addr, u.prefs, u._id)
            Profile.updateUser(u, newu)
            val pro = newu.profile.getOrElse(newu.mkProfile)
            this dbop pro.update(pro.removePerm("+" + Perm.eVerified.s))
            this dbop UserTasks.verifyEmail(newu).create
            Emailer.withSession { implicit mailSession =>
              Tasks.sendEmailVerif(newu)
              Tasks.msgVerif(newu, None)
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER_EMAIL ")
            unauthorized("Oops - cannot update this user... ")
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
  println(Enc.fromUrl(s1))
  println(Enc.unapply(s2))
  //  println (Enc.fromUrl(s2))
  //  println (Base64 --> s3)
  //  println (DateTime.parse(s4) + "")

}
