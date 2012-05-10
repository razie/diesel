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

/** profile related control */
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
  //•recaptcha_challenge_field is a hidden field that describes the CAPTCHA which the user is solving. It corresponds to the "challenge" parameter required by the reCAPTCHA verification API.
  //•recaptcha_response_field is a text field where the user enters their solution. It corresponds to the "response" parameter required by the reCAPTCHA verification API.
  val crprofileForm = Form {
    mapping (
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "email" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)),
      "recaptcha_challenge_field" -> text,
      "recaptcha_response_field" -> text) (CrProfile.apply)(CrProfile.unapply) verifying ("failed capthca", { cru =>
        capthca(cru.recaptcha_challenge_field, cru.recaptcha_response_field)
      }) verifying
      ("Oops - this email is already registered...", { cru =>
        Api.findUser(Enc(cru.email)).map { u =>
          audit("ERR tried to create a profile again " + u)
          false
        } getOrElse true
      })
  }

  def capthca(challenge:String, response:String) = {
//   val resp = Snakk.body(
//       Snakk.url(
//           "http://www.google.com/recaptcha/api/verify", 
//           razie.AA("privatekey"->"6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip"->"?", "challenge"->challenge, "response"->response), 
//           "POST"))
//           
//   println("RESP="+resp)
//   
//   resp.startsWith("true")
  }

  // profile
  val edprofileForm = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "email" -> text.verifying("Wrong format!", _.matches("[^@]+@[^@]+\\.[^@]+")).verifying("Invalid characters", !spec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Users.userTypes.contains(ut)))(
        (f, l, y, e, u) => User("kuku", f, l, y, Users.enc(e), "kuku", u))(
          (u: User) => Some(u.firstName, u.lastName, u.yob, u.email.dec, u.userType))
  }

  val trusts = Array("Public", "Moderated", "ParentModerated", "Private")
  val notifiers = Array("Everything", "Content", "None")

  // profile
  val edprofileForm2 = Form {
    tuple(
      "trust" -> nonEmptyText.verifying("Please select one", ut => trusts.contains(ut)),
      "notify" -> nonEmptyText.verifying("Please select one", ut => notifiers.contains(ut)))
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

  def createProfile = Action { implicit request =>
    implicit val errCollector = new Error()
    crprofileForm.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.crprofile(formWithErrors, auth))
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, y, e, ut, _, _) => {
          for (
            p <- session.get("pwd") orErr ("no pwd in session");
            u <- Some(User(System.currentTimeMillis.toString, f, l, y, Enc(e), Enc(p), ut));
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
    auth map { u => Ok(views.html.user.edprofile1(edprofileForm.fill(u), u)) } getOrElse Unauthorized("Oops - how did you get here?")
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
      Ok(views.html.user.edprofile2(edprofileForm2.fill((t, n)), child, u))
    }) getOrElse Unauthorized("Oops - how did you get here? " + errCollector.mkString)
  }

  def profile2u(child: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    edprofileForm2.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.edprofile2(formWithErrors, child, auth.get))
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
      formWithErrors => BadRequest(views.html.user.edprofile1(formWithErrors, auth.get)),
      { u: User =>
        (for (
          au <- auth orErr ("not authenticated")
        ) yield {
          au.update(User(au.userName, u.firstName, u.lastName, au.yob, au.email, au.pwd, u.userType, au._id))
          Redirect("/").withSession("connected" -> u.email)
        }) getOrElse
          Unauthorized("Oops - how did you get here?")
      })
  }

  // authenticated means doing a task later
  def addParent(implicit request: Request[_]) = {
    (for (u <- auth)
      yield Ok(views.html.tasks.addParent(parentForm.fill(Email("")), u.ename))) getOrElse
      Unauthorized("Oops - how did you get here?")
  }

  // non authenticated means creating user with flash
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
    this dbop ds.create
    val link = "http://" + Config.hostport + "/doe/sec/" + ds.id
    log("ENC_LINK2=" + link)

    sendToParentAdd(pe, from, c.email, c.ename, link)
    Msg("Ok - we sent an email - please ask your parent to follow the instructions in that email. " +
      "" +
      "They have to first register and then follow this [link](" +
      link + ")", "Page", "home", Some(c)).withSession("connected" -> c.email)
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

  def dbop(r: WriteResult) = log("DB_RESULT: " + r.getError)

  lazy val cExpired = new Corr("token expired", "get another token")
  lazy val cNotParent = new Corr("you're not the parent", "login with the parent account and try again")
  lazy val cNoProfile = InternalErr("can't load the user profile")

  def addParent3(expiry: String, parentEmail: String, childEmail: String, childId: String) = Action { implicit request =>
    implicit val errCollector = new Error()
    println(Map("e" -> expiry))
    (expiry, parentEmail, childEmail, childId) match {
      case (Enc(expiry), pe, ce, cid) => {
        println(Map("e" -> expiry))
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
}

//object TestCaphct extends App {
//   val resp = Snakk.body(Snakk.url("http://www.google.com/recaptcha/api/verify", razie.AA.EMPTY, "POST"))
//   println("RESP="+resp)
//}