package controllers

import com.google.inject._
import com.mongodb.casbah.Imports.ObjectId
import mod.snow.RacerKidz
import model._
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, _}
import play.api.mvc._
import razie.audit.Audit
import razie.db.Txn
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.hosting.Website
import razie.wiki.Sec._
import razie.wiki.admin.{SecLink, SendEmail}
import razie.wiki.model._
import razie.wiki.{Config, Enc, Services}
import razie.{Logging, Snakk, cout}
import scala.collection.mutable.HashMap

/** User Profile utilities - this is NOT the controller */
object Profile extends WikieBase {

  val trusts = Array("Public", "Club", "Friends", "Private")
  val notifiers = Array("Everything", "FriendsOnly", "None")

  // TODO this shoud be private
  def updateUser(old: User, newU: User)(implicit request: Request[_]) = {
    old.update(newU)
    cleanAuth(Some(newU))
    newU
  }

  // TODO should be private
  def createUser(u: User, about:String = "", realm:String, host:Option[String])(implicit txn:Txn) = {
    val website = Website.forRealm(realm).get
    val pro = u.mkProfile

    val created = {u.create(pro); Some(u)}

    // todo decouple with the event below
    created.foreach { x => RacerKidz.myself(x._id) }

    created.foreach {x=>
      Services ! UserCreatedEvent(x._id.toString, Services.config.node)
    }

    // when testing, skip email verification - unless the site needs it
    if(! Services.config.isDevMode || website.bprop("requireEmailVerification").exists(_ == true)) {
      UserTasks.verifyEmail(u).create
    } else {
      // localhost
      var pu = u.addPerm(realm, Perm.eVerified.s).addPerm(realm, Perm.uWiki.s)
      pu = pu.addModNote(realm, "Localhost - no email verif")
      u.update(if (u.isUnder13) pu else pu.addPerm(realm, Perm.uProfile.s))
    }

    if (u.isClub) {
      UserTasks.setupCalendars(u).create
      UserTasks.setupRegistration(u).create
    }

    SendEmail.withSession(realm) { implicit mailSession =>
      UserTasksCtl.sendEmailVerif(u, host.orElse(Some(website.domain)))
      // to user - why notify of default username?
      val uname = (u.firstName + (if (u.lastName.length > 0) ("." + u.lastName) else "")).replaceAll("[^a-zA-Z0-9\\.]", ".").replaceAll("[\\.\\.]", ".")

      Emailer.sendEmailUname(uname, u, false)
//      Emailer.tellAdmin("New user", u.userName, u.emailDec, "realm: "+u.realms.mkString, "ABOUT: "+about)
    }

    //realm new user flow
    Services ! DieselMsgString(
      DieselMsg.USER_JOINED + s"""(userName="${u.userName}", realm="${realm}")""",
      DieselTarget.REALMDIESEL(realm)
    )

    created
  }

}

case class UserCreatedEvent (uid:String, node:String) extends WikiEventBase

/** temporary registration/login form */
case class Registration(email: String, password: String, reemail:String="", repassword: String = "") {
  def ename = email.replaceAll("@.*", "")
}

// create profile
case class CrProfile(firstName: String, lastName: String, company:String, yob: Int, address: String, userType: String, accept: Boolean, g_recaptcha_response: String="", about:String="")

object AttemptCounter {
  // keep last attempts, 3 seconds, 10 seconds (email - (count,last systime))
  val lastAttempts = new HashMap[String, (Int, Long)]()

  /** check for repeated attempts */
  def tooManyAttempts(email: String): Boolean = synchronized {
    var count = 0
    val now = System.currentTimeMillis()

    val v = lastAttempts.get(email)
    v.foreach(v => count = v._1)
    lastAttempts.put(email, (count + 1, now))

    // 2 seconds per count - exponential waiting
    val res = v.filter(v => v._1 >= 5 && (now - v._2 < 2000 * count)).isDefined

    if (res) {
      cout << s"TOO MANY ATTEMPTS ($count) for $email"
      Audit.logdb("TOO_MANY_ATTEMPTS", email, lastAttempts.get(email).map(_._1).mkString)
    }

    false || res
  }

  /** check for repeated attempts */
  def success(email: String) = synchronized {
    lastAttempts.remove(email)
  }

  /** check for repeated attempts */
  def countAttempts(email: String): Int = synchronized {
    var count = 0
    val v = lastAttempts.get(email)
    v.foreach(v => count = v._1)
    count
  }
}

/** user profile controller */
@Singleton
class Profile @Inject()(config: Configuration, adminImport: AdminImport) extends WikieBase with Logging {

  import AttemptCounter._

  final val INVALID_LOGIN = "Invalid username and/or password"

  def registerForm(implicit request: Request[_]) = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong email format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> nonEmptyText.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "reemail" -> text,
      "repassword" -> text
    )(Registration.apply)(Registration.unapply) verifying
        ("Email mismatch - please type again", { reg: Registration =>
          if (reg.reemail.length > 0 && reg.email.length > 0 && reg.email != reg.reemail) false
          else true
        }) verifying
        ("Password not matched - please type again", { reg: Registration =>
          if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
          else true
        }) verifying
      ("Bad email or password - please type again! To register a new account, use the Create button and if you forgot your email, use the Forgot button", { reg: Registration =>
        //          println ("======="+reg.email.enc+"======="+reg.password.enc)
        clog << "login test: " + reg.email
        if (tooManyAttempts(reg.email)) {
          false
        } else if (reg.password.length > 0 && reg.repassword.length <= 0)
          // TODO optimize - we lookup users twice on loing
          Users.findUserByEmailDec(reg.email).orElse(Users.findUserNoCase(reg.email)).map { u =>
            if (reg.password.enc == u.pwd) true
            else {
              u.auditLoginFailed (Website.getRealm, countAttempts(reg.email))
              false
            }
          } getOrElse {
            Audit.wrongLogin(reg.email, reg.password, countAttempts(reg.email))

            clog << s"should I download remote user? isLocalhost: ${Services.config.isLocalhost}"

            val res = if (
              Services.config.isLocalhost &&
                adminImport.isRemoteUser(reg.email, reg.password)) {

              adminImport.importRemoteUser(reg.email, reg.password)
              AttemptCounter.success(reg.email)

              Services ! WikiEvent("AUTH_REMOTE", "User", reg.email, Some(Enc(reg.password)))

              // give the other nodes some time to receive and update their db... only when we imported remotely...
              Thread.sleep(2000)

              true
            } else false

            res
        }
        else {
          true
        }
      }) verifying
      ("Email already registered - if you are logging in, type the password once!", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0)
          Users.findUserByEmailDec(reg.email).map(u => false) getOrElse { true }
        else true
      })
  }

  def crProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "company" -> text
        .verifying("Obscenity filter", !Wikis.hasBadWords(_))
        .verifying("Invalid characters", vldSpec(_))
        .verifying("Too short (should be more then 3)", (x=> x.length == 0 || x.length >= 4)),
//        .verifying("Too long (less than 10)", _.length <= 10),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Website.userTypes.contains(ut)),
      "accept" -> checked("").verifying("You must accept the Terms of Service to use this site", { x: Boolean => x }),
      "g-recaptcha-response" -> text,
      "about" -> text.verifying("Invalid characters", vldSpec(_))
      )(CrProfile.apply)(CrProfile.unapply) verifying
      ("reCAPTCHA failed!", { cr: CrProfile =>
        new Recaptcha(config).verify2(cr.g_recaptcha_response, clientIp)
      }) verifying
      ("Can't use last name for organizations!", { cr: CrProfile =>
        cr.userType != UserType.Organization.toString || cr.lastName.length <= 0
      })
  }

  // todo stop messing with the routes again
  def doeJoinAlso = doeJoin("", "", "")

  def doeJoin   (club: String, role: String, next: String) = doeJoinInt(club, role, next, false)
  def doeJoinNew(club: String, role: String, next: String) = doeJoinInt(club, role, next, true)

  // join step 1
  def doeJoinInt(club: String, role: String, next: String, crNew:Boolean=false) = RAction {implicit request=>
    auth // clean theme
    val reg = request.flash.get(SecLink.HEADER).flatMap(SecLink.find).map {sl=>
      Registration(sl.props("email"),"", "", "")
    }.getOrElse (Registration("",""))

    val res = (ROK.r noLayout {implicit stok=>
      views.html.doeJoin(registerForm.fill(
      reg
    ), crNew)
    }).withSession(
      "gaga" -> System.currentTimeMillis.toString,
      "extra" -> "%s,%s,%s".format(club, role, next))
        .discardingCookies(DiscardingCookie("dieselProxyNode"))

    // carry on the flash
    request.flash.get(SecLink.HEADER).map(x=> res.addingToSession(SecLink.HEADER -> x)).getOrElse (res)
  } // continue with register()

  // when joining with google first time and have account - enter password
  def doeJoin1Google = Action {implicit request=>
    auth // clean theme
    (ROK.r noLayout {implicit stok=> views.html.doeJoinGoogle(registerForm)})
        .withSession(
          "gaga" -> request.session.get("gaga").mkString,
          "extra" -> request.session.get("extra").mkString,
          "gid" -> request.session.get("gid").mkString)
        .discardingCookies(DiscardingCookie("dieselProxyNode"))
  } // continue with register()

  def doeJoin1Openid = Action {implicit request=>
    val server="https://dcagroup.okta.com"
    val me = "http://localhost:9000/doe/join-oid".encUrl
//    Redirect (
//      Map(
//        "client_id" -> "0oa279k9b2uNpsNCA356",
//        "response_type" -> "token",
//        "scope" -> "openid",
//        "redirect_uri" -> me,
//        "state" -> "state123",
//        "nonce" -> "foo"
//      )
//    )
    val r =
// okta?
s"$server/oauth2/v1/authorize?client_id=0oa279k9b2uNpsNCA356&response_type=token&"+
//custom server - see bottom ofhttps://developer.okta.com/authentication-guide/auth-overview/
//s"$server/api/v1/authorizationServers/default/authorize?client_id=0oa279k9b2uNpsNCA356&response_type=token&"+
         s"scope=openid&redirect_uri=$me&state=state123&nonce=foo"

    log("OID Redirect to: "+r)
    Redirect (r)
  }

  /** join with email */
  def doeJoinWith(email: String) = Action {implicit request=>
    auth // clean theme
    log("joinWith email=" + email)
    (ROK.r noLayout {implicit stok=>
      views.html.doeJoin(
        registerForm.fill(
          Registration(email.dec, "", "", ""))
      )}
      ).withSession(
        "gaga" -> System.currentTimeMillis.toString
    )
        .discardingCookies(DiscardingCookie("dieselProxyNode"))
  } // continue with register()

  // join step 2 - submited email/pass form
  def doeJoin2 = RAction { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
    formWithErrors => (ROK.r badRequest {implicit stok
      => views.html.doeJoin(formWithErrors)}).withSession(
        "gaga" -> System.currentTimeMillis.toString,
        "extra" -> request.session.get("extra").mkString
    ),
    {
      case reg @ Registration(e, p, _, _) if (e.trim.length > 3) => { // todo better email message
        val g = try {
          (request.session.get("gaga").map(identity).getOrElse("1")).toLong
        } catch {
          case _: Throwable => 1
        }

        // allow this only for some minutes
        val millis = System.currentTimeMillis - g
        if (millis <= 120000) {
          login(reg.email, reg.password, request.session.get("extra").mkString)
        } else {
          Msg2(
            """Session expired - please <a href="/doe/join">start again</a>.
              |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin, Some("/doe/join")
          )
              .withNewSession
              .discardingCookies(DiscardingCookie("dieselProxyNode"))
        }
      }
    })
  }

  /** login from other pages */
  def doeLoginWithPass = RAction { implicit request =>
    auth // clean theme
    val email = request.formParm("email")
    val pass = request.formParm("password")
    val g_recaptcha_response = request.formParm("g-recaptcha-response")
    cdebug << "g-recaptcha-response: " + g_recaptcha_response

    if(! tooManyAttempts(email)) {
      val u = Users.findUserByEmailDec((email)) orElse (Users.findUserNoCase(email))
      if(!u.isDefined) {
        cdebug << "should I download remote user? isLocalhost: " + Services.config.isLocalhost
        if(
          Services.config.isLocalhost &&
          adminImport.isRemoteUser(email, pass)) {
          adminImport.importRemoteUser(email, pass)
          Services ! WikiEvent("AUTH_REMOTE", "User", email, Some(Enc(pass)))
          AttemptCounter.success(email)

          // give the other nodes some time to receive and update their db... only when we imported remotely...
          Thread.sleep(2000)
        }
      }
      login (email, pass, "", "", u)
    } else {
      val loginUrl = request.website.prop("join").getOrElse(routes.Profile.doeJoin().url)
      Redirect(loginUrl)
          .withNewSession
          .discardingCookies(DiscardingCookie("dieselProxyNode"))
          .withCookies(
            Cookie("error", "too many attempts - try again later".encUrl).copy(httpOnly = false)
          )
    }

//    if(new Recaptcha(config).verify2(g_recaptcha_response, clientIp)) {
//      clog << "passed recaptch"
//      login(email, pass, "")
//    } else {
//      clog << "reCAPTCHA failed"
//      val loginUrl = request.website.prop("join").getOrElse(routes.Profile.doeJoin().url)
//      Redirect(loginUrl)
//          .withNewSession
//        .discardingCookies(DiscardingCookie("dieselProxyNode"))
//          .withCookies(
//            Cookie("error", "reCAPTCHA failed".encUrl).copy(httpOnly = false)
//          )
//    }
  }

  // join step 2 with google - link to existing account
  def doeJoin2Google = RAction { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
      formWithErrors => {
        cout << formWithErrors
        (ROK.r badRequest {implicit stok=> views.html.doeJoinGoogle(formWithErrors)}).withSession("gaga" -> request.session.get("gaga").mkString,
          "extra" -> request.session.get("extra").mkString, "gid" -> request.session.get("gid").mkString)
      },
      {
        case reg @ Registration(e, p, _, _) if (e.trim.length > 3) => { // todo better email message
          val g = try {
            (request.session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _: Throwable => 1
          }

          // allow this only for some minutes
          if (System.currentTimeMillis - g <= 120000 && request.session.get("gid").isDefined) {
            // associate user to google id and login
            Users.findUserByEmailDec((reg.email)) orElse (Users.findUserNoCase(reg.email)) foreach {u =>
              u.update(u.copy(gid = request.session.get("gid")))
            }
            login(reg.email, reg.password, request.session.get("extra").mkString, request.session.get("gid").mkString)
          } else {
            Msg2(
              """Session expired - please <a href="/doe/join">start again</a>.
                |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin,
              Some("/doe/join")
            )
                .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
          }
        }
      })
  }

  // join step 2 with google - link to existing account
  def doeJoin2Openid = RAction { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
      formWithErrors => {
        cout << formWithErrors
        (ROK.r badRequest {implicit stok=> views.html.doeJoinGoogle(formWithErrors)}).withSession("gaga" -> request.session.get("gaga").mkString,
          "extra" -> request.session.get("extra").mkString, "gid" -> request.session.get("gid").mkString)
      },
      {
        case reg @ Registration(e, p, _, _) => {
          val g = try {
            (request.session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _: Throwable => 1
          }

          // allow this only for some minutes
          if (System.currentTimeMillis - g <= 120000 && request.session.get("gid").isDefined) {
            // associate user to google id and login
            Users.findUserByEmailDec((reg.email)) orElse (Users.findUserNoCase(reg.email)) foreach {u =>
              u.update(u.copy(gid = request.session.get("gid")))
            }
            login(reg.email, reg.password, request.session.get("extra").mkString, request.session.get("gid").mkString)
          } else {
            Msg2(
              """Session expired - please <a href="/doe/join">start again</a>.
                |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin,
              Some("/doe/join")
            )
                .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
          }
        }
      })
  }

  // join step 2 with google - link to existing account
  def doeJoin2OpenidRedirect = RAction { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
      formWithErrors => {
        cout << formWithErrors
        (ROK.r badRequest {implicit stok=> views.html.doeJoinGoogle(formWithErrors)}).withSession("gaga" -> request.session.get("gaga").mkString,
          "extra" -> request.session.get("extra").mkString, "gid" -> request.session.get("gid").mkString)
      },
      {
        case reg @ Registration(e, p, _, _) => {
          val g = try {
            (request.session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _: Throwable => 1
          }

          // allow this only for some minutes
          if (System.currentTimeMillis - g <= 120000 && request.session.get("gid").isDefined) {
            // associate user to google id and login
            Users.findUserByEmailDec((reg.email)) orElse (Users.findUserNoCase(reg.email)) foreach {u =>
              u.update(u.copy(gid = request.session.get("gid")))
            }
            login(reg.email, reg.password, request.session.get("extra").mkString, request.session.get("gid").mkString)
          } else {
            Msg2(
              """Session expired - please <a href="/doe/join">start again</a>.
                |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin,
              Some("/doe/join")
            )
                .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
          }
        }
      })
  }

  /** login or start registration */
  def login (email: String, pass:String, extra: String, gid:String="", theUser:Option[User] = None) (implicit request:RazRequest) = {
    // TODO optimize - we lookup users twice on login
    val realm = getRealm()
    val website = request.website
    val secLink = request.session.get(SecLink.HEADER).flatMap(SecLink.find)

    val loginUrl = website.prop("join").getOrElse(routes.Profile.doeJoin().url)

    debug ("login.secLink="+secLink.mkString)

      theUser orElse Users.findUserByEmailDec((email)) orElse (Users.findUserNoCase(email)) match {
      case Some(u) =>
        if (
          (
            Enc(pass) == u.pwd ||
            u.gid.exists(_ == gid && gid.length > 2)
          ) &&
          (
            website.openMembership ||
            u.realms.contains(realm) ||
            realm=="rk" ||
            realm=="ski" ||
            realm=="wiki" ||
            u.isAdmin )
        ) {

          // clear counters
          Services ! WikiEvent("AUTH_REMOTE", "User", email, Some(Enc(pass)))
          AttemptCounter.success(email)

          Audit.logdb("USER_LOGIN", u.userName, u.firstName + " " + u.lastName + " realm: " + realm)
          u.auditLogin(realm)
          debug("SEss.conn=" + (Services.config.CONNECTED -> Enc.toSession(u.email)))

          // process extra parms to determine what next
          var next: Option[String] = None
          var club: String = ""
          request.session.get("extra") map { x =>
            val s = x split ","
            if (s.size > 0 && s(0).length > 0) {
              val prefix = if (s(0).indexOf(":") > 0) "" else "Club:" // for old links with name instead of WID
              next = Some("/wikie/linkuser/" + prefix + s(0) + "?wc=0")
              club=WID.fromPath(s(0)).map(_.wpathnocats).mkString
            }
          }

          if(website.bprop("requireEmailVerification").exists(_ == true) &&
            ! u.forRealm(realm).hasPerm(Perm.eVerified)
          ) {
            // realm wants him to verify email first

            // resend activation link
            Emailer.withSession(request.realm) { implicit mailSession =>
              UserTasksCtl.sendEmailVerif(u, request.headers.get("X-Forwarded-Host").orElse(Some(website.domain)))
            }

            val msg = "Please check your email for an activation link!"

            Msg2(MSG_REGD, Some(loginUrl))
//            Redirect(loginUrl)
              .withNewSession
              .discardingCookies(DiscardingCookie("dieselProxyNode"))
              .withCookies(
                Cookie("error", msg.encUrl).copy(httpOnly = false)
              )
          } else
          (
              if(u.hasConsent(realm) || !request.website.needsConsent) {
                if(next.isDefined)
                  Msg2( s"""Click below to continue joining the $club.""", next)
                else
                  Redirect("/")
              } else
                  ROK.r apply { implicit stok =>
                    views.html.user.doeConsent(next.getOrElse("/"))
                  }
          ).withSession (Services.config.CONNECTED -> Enc.toSession(u.email))
           .discardingCookies(DiscardingCookie("error"))
        } else {
          u.auditLoginFailed(realm)

          if(Enc(pass) == u.pwd || u.gid.exists(_ == gid && gid.length > 2) ) {
            // user is ok but not member
            Msg2C (
              s"""Oops... you are not a member of this site/project. To join, open a support request.<br>
                 |<br>
                 |<small>Translation: even though you do have an account here, you're not a member of this particular
                 | site! ($realm)</small>
               """.stripMargin, Some(Call("GET", loginUrl)))
              .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
              .withCookies(
                Cookie("error", INVALID_LOGIN.encUrl).copy(httpOnly = false)
              )
          } else {
            // user not ok, try again
            Redirect(loginUrl)
              .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
              .withCookies(
                Cookie("error", INVALID_LOGIN.encUrl).copy(httpOnly = false)
              )
          }
        }

      case None => // capture basic profile and create profile
        if(request.website.openMembership ||
          (secLink.exists(_.props("realm") == request.realm) &&
            secLink.exists(_.props("email") == email)
          ))
          Redirect(routes.Profile.doeJoin3)
              .flashing("email" -> email, "pwd" -> pass, "gid"->gid, "extra" -> extra)
              .discardingCookies(DiscardingCookie("dieselProxyNode"))
        else
        // user not ok, try again
          Redirect(loginUrl)
            .withNewSession
              .discardingCookies(DiscardingCookie("dieselProxyNode"))
              .withCookies(
              Cookie("error", INVALID_LOGIN.encUrl).copy(httpOnly = false)
            )
    }
  }

  /** logout */
  def doeLogout () = Action { implicit request =>
    Redirect("/")
        .withNewSession
        .discardingCookies (DiscardingCookie("dieselProxyNode"))
  }

  // display consent
  def doeConsent (next:String) = FAUR { implicit request =>
      if(request.au.exists(_.hasConsent(request.realm)) || !request.website.needsConsent) {
        Redirect(next).withNewSession
      } else {
        ROK.k apply { implicit stok =>
          views.html.user.doeConsent(next)
        }
      }
  }

  // accepted consent, record it
  def doeConsent2 (ver:String, next:String) = FAUR { implicit request =>
      request.au.get.update(request.au.get.consented(request.realm, ver))
      UserEvent(request.au.get._id, "CONSENTED " + ver).create
      cleanAuth()
    if(request.website.bprop("consent.thankyou").exists(x=> !x)) {
      Redirect(next)
    } else {
      Msg2("Thank you!", Some(next))
    }
  }

  // display consent
  def doeClearConsent = FAUR { implicit request =>
    if(request.au.exists(_.isMod))
    Msg2(
      "This will clear all consents from your users - are you sure?",
      Some(routes.Profile.doeClearConsent2(request.realm).url))
    else
      Msg(s"No permission to do that! (clearConsent on realm ${request.realm})")
  }

  // display consent
  def doeClearConsent2 (realm:String) = FAUR { implicit request =>
    var cnt = 0

    if(
      realm == "*" && request.au.exists(_.isAdmin) ||
      realm == request.realm && request.au.exists(_.isMod)
    ) {
      Users.findUsersForRealm(realm).foreach {u =>
        u.realmSet.get(request.realm).foreach {p=>
          UserEvent(u._id, "CONSENT CLEARED - old: " + p.consent.mkString).create
          u.update (u.clearConsent(request.realm))
          cnt += 1
        }
      }

      Msg(s"Updated $cnt profiles!")
    } else
      Msg(s"No permission to do that! (clearConsent2 on realm $realm)")
  }

  /** start registration long form - submit is doeCreateProfile */
  def doeJoin3 = Action { implicit request =>
    auth // clean theme
    (for (
      e <- request.flash.get("email");
      p <- request.flash.get("pwd") orElse request.flash.get("gid")
    ) yield {

      (ROK.r reactorLayout12 { implicit stok =>
        val join3 = getJoin3Page(stok)

        views.html.user.doeJoin3(
          crProfileForm.fill(
            CrProfile("", "", "", 13, "", "racer", false)
          ),
          join3
        )
      }).withSession(
        "pwd" -> p,
        "email" -> e,
        "extra" -> request.flash.get("extra").mkString,
        "gid" -> request.flash.get("gid").mkString
      )
    }
        ) getOrElse
        unauthorized(
          """Session expired [join3] - please <a href="/doe/join">start again</a>."""
        ).withNewSession
            .discardingCookies(DiscardingCookie("dieselProxyNode"))
  }

  private def dfltCss (website:Website) = website.css orElse Services.config.sitecfg("dflt.css") getOrElse "light"

  def getJoin3Page(stok: StateOk) = {
    val join3 =
      WID.fromPath("Admin:page-join3").map(_.r(stok.realm)).flatMap(_.page).orElse(
        WID.fromPath("Admin:page-join3-" + stok.realm).map(_.r(stok.realm)).flatMap(_.page)).orElse(
        WID.fromPath("Admin:page-join3-" + stok.realm).flatMap(_.page)).orElse(
        WID.fromPath("Admin:page-join3-rk").flatMap(_.page))

    join3
  }

  /** join step 4 - after captcha: create profile and send emails */
  def doeCreateProfile(testcode: String) = RAction { implicit request =>

    def getFromSession(s: String, d: String) =
      if (testcode != T.TESTCODE) request.session.get(s)
      else Some(d)

    def uname(f: String, l: String, yob: Int) = Users.unameF(f.trim, l.trim)

    auth // clean theme

    val resp = crProfileForm.bindFromRequest

    resp.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        (ROK.r badRequest { implicit stok =>

          val join3 = getJoin3Page(stok)

          views.html.user.doeJoin3(formWithErrors, join3)
        }).withSession(
          "pwd" -> getFromSession("pwd", T.TESTCODE).mkString,
          "email" -> getFromSession("email", "@k.com").mkString,
          "extra" -> getFromSession("extra", "extra").mkString,
          "gid" -> request.session.get("gid").mkString)
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, org, y, addr, ut, accept, _, about) =>

          (for (
            p <- getFromSession("pwd", T.TESTCODE) orErr ("psession corrupted");
            e <- getFromSession("email", f + l + "@k.com") orErr ("esession corrupted");
            already <- (!Users.findUserByEmailDec((e)).isDefined) orCorr ("User already created" -> "patience, patience...");
            isOk <- (DateTime.now.year.get - y > 15) orErr ("You can only create an account if you are 16 or older");
            iu <- Some(User(
              Users.uniqueUsername(uname(f, l, y)),
              f.trim,
              l.trim,
              y,
              Enc(e.trim),
              Some(Enc(e.trim.toLowerCase)),
              Enc(p),
              'a',
              Set(ut),
              Set(Website.realm),
              (if (addr != null && addr.length > 0) Some(addr) else None),
              Map("css" -> dfltCss(request.website), "favQuote" -> "Do one thing every day that scares you - Eleanor Roosevelt", "weatherCode" -> "caon0696"),
              request.flash.get("gid") orElse request.session.get("gid")
            ).copy(organization = Some(org))
            )
          ) yield {
            // finally created a new account/profile
            var u = iu
            if (u.isUnder13 && !u.isClub) {
              unauthorized("Oops - we do not accept this account!")
            } else {
              if(about.length > 0) u = u.setPrefs(request.realm, u.prefs + ("about" -> about))

              razie.db.tx("doeCreateProfile", u.userName) { implicit txn =>
                Profile.createUser(u, about, request.realm, request.headers.get("X-Forwarded-Host"))
              }

              // process extra parms to determine what next
              request.session.get("extra") map { x =>
                val s = x split ","
                if (s.size > 0 && s(0).length > 0) {
                  UserTasksCtl.msgVerif(u, s"""\n<p><font style="color:red">Click below to continue joining the ${s(0)}.</font>""", Some("/wikie/linkuser/Club:" + s(0) + "?wc=0"))
                } else
                  UserTasksCtl.msgVerif(u, "", Some(routes.Profile.doeConsent().url))
              } getOrElse UserTasksCtl.msgVerif(u, "", Some(routes.Profile.doeConsent().url))
            }
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER.doeCreateProfile " + getFromSession("email", f + l + "@k.com"))
            unauthorized("Oops - cannot update this user [doeCreateProfile " + getFromSession("email", f + l + "@k.com") + "] - Please try again or send a suport request!")
                .withNewSession
                .discardingCookies(DiscardingCookie("dieselProxyNode"))
          }
      }) //fold
  }

  /**
    *  create profile for an external user - simplified FOR PORTALS
    */
  def doeCreateExt() = RAction { implicit stok =>

    def uname (f:String,l:String,yob:Int) = Users.unameF(f.trim,l.trim)

    auth // clean theme

    val realmcd = stok.formParm("realmcd").trim

    val n = stok.formParm("name").trim
    val f = n.split(" ").head
    val l = n.split(" ").tail.mkString(" ")
    val e = stok.formParm("email")
    val p = stok.formParm("password").trim
    val r = stok.formParm("repassword").trim
    val y = 0

    val esid = stok.formParm("extSystemId").trim
    val eiid = stok.formParm("extInstanceId").trim
    val eaid = stok.formParm("extAccountId").trim

    val currUrl = stok.formParm("currUrl").trim

    logger.info (n, f, l, e, p, y, esid, eiid, eaid)

    logger.info (stok.formParms.mkString)

    val g_recaptcha_response = stok.formParm("g-recaptcha-response")
    cdebug << "g-recaptcha-response: " + g_recaptcha_response

    if(! new Recaptcha(config).verify2(g_recaptcha_response, clientIp)) {
      clog << "reCAPTCHA failed"
      Redirect(currUrl)
          .withNewSession
          .discardingCookies(DiscardingCookie("dieselProxyNode"))
          .withCookies(
            Cookie("error", "reCAPTCHA failed".encUrl).copy(httpOnly = false)
          )
    } else if(realmcd != "hcvalue") {
      Unauthorized("")
   } else if(
      f.isEmpty ||
      e.isEmpty ||
      p.isEmpty ||
      p.length < 6 ||
      p != r ||
      esid.isEmpty ||
      eiid.isEmpty ||
      eaid.isEmpty
    ) {
      // you must have validated this data previously
      Msg ("Data is invalid, please try again")
          .withNewSession
          .discardingCookies(DiscardingCookie("dieselProxyNode"))
          .discardingCookies(DiscardingCookie("error"))
    } else if(Users.findUserNoCase((e)).isDefined) {

      // user exists

      val u = Users.findUserNoCase((e)).get

      if(u.pwd != Enc(p)) {
        clog << "User createExt password does not match"
        Redirect(currUrl)
            .withNewSession
            .discardingCookies(DiscardingCookie("dieselProxyNode"))
            .withCookies(
              Cookie(
                "error",
                "This email is associated with a different password. Please use the same password!".encUrl
              ).copy(httpOnly = false)
            )
      } else {
        if (!u.realms.contains(stok.realm)) {
          clog << "user exists but has no realm, adding realm to user and updating password"
          var newu = u.copy(realms = u.realms + stok.realm)

          u.update(newu)
          cleanAuth(Some(u))
        }

        val pro = u.profile.get
        val newp = pro.upsertExtLink(stok.realm, ExtSystemUserLink(stok.realm, esid, eiid, eaid))
        pro.update(newp)

        Msg("User registered... please proceed to login.")
            .withNewSession
            .discardingCookies(DiscardingCookie("dieselProxyNode"))
            .discardingCookies(DiscardingCookie("error"))
      }
    } else {
      val u = User(
        Users.uniqueUsername(uname(f, l, y)), f.trim, l.trim,
        y,
        Enc(e.trim),
        Some(Enc(e.trim.toLowerCase)),
        Enc(p),
        'a',
        Set(Users.ROLE_MEMBER),
        Set(stok.realm),
        None,
        Map(
          "css" -> dfltCss(stok.website),
          "favQuote" -> "Do one thing every day that scares you - Eleanor Roosevelt",
          "weatherCode" -> "caon0696"
        )
      )

      razie.db.tx("doeCreateExt", u.userName) { implicit txn =>
        val newu = Profile.createUser(u, "", stok.realm, stok.headers.get("X-Forwarded-Host"))
        val p = newu.get.profile.get
        val newp = p.upsertExtLink(stok.realm, ExtSystemUserLink(stok.realm, esid, eiid, eaid))
        p.update(newp)
      }

      // todo consent
//      Tasks.msgVerif(u, "", Some(routes.Profile.doeConsent().url))
      Msg2(MSG_REGD, Some("/"))
          .withNewSession
          .discardingCookies(DiscardingCookie("dieselProxyNode"))
          .discardingCookies(DiscardingCookie("error"))
      }
  }

  val MSG_REGD= s"""
Your registration was successful. A verification email with a confirmation link has been sent to your email address.
<p>
<small>
Please allow a few minutes for this message to arrive and don't forget to check your junk or spam folder if you do not see the email.
<br>Please follow the instructions from the email.
</small>
"""

  // user chose to login with google
  def doeJoinGtoken = RAction { implicit request =>
    val b = request.req.body.asFormUrlEncoded.get
    cdebug << "Google login request : " << b

    //todo better security - use random security token
    val g = try {
      (request.session.get("gaga").map(identity).getOrElse("1")).toLong
    } catch {
      case _: Throwable => 1
    }

    // allow this only for some minutes
    if (System.currentTimeMillis - g <= 120000) {
      val code = b("code").head
      val email = b("email").head
      val name = b("name").head
      val access_token = b("access_token").head
      val id = b("id").head

      // verifying the token
      val bres = Snakk.body(Snakk.url("https://www.googleapis.com/oauth2/v1/tokeninfo").form(Map("access_token"->access_token)))
      cdebug << "Google api response: " << bres
      val res = new JSONObject(bres)

      val CLIENT_ID="980280495626-7llkvk4o02anpu6qv1sseucc07f8f3gs.apps.googleusercontent.com"  // mine

      Audit.logdb("LOGIN_WITH_GOOGLE", email, name)

      if(id == res.getString("user_id") &&
        CLIENT_ID == res.getString("audience") ) {
        Users.findUserByEmailDec((email)) orElse (Users.findUserNoCase(email)) match {
          case Some(u) if (u.gid.exists(_.length > 0)) =>
            login(email, "", "", id)
          case Some(u) if (!u.gid.isDefined) =>
            Redirect(routes.Profile.doeJoin1Google).withSession("gaga" -> request.session.get("gaga").mkString, "extra" -> request.session.get("extra").mkString,  "gid" -> id)
          case None =>
            login(email, "", "", id)
        }
      } else {
        Audit.logdb("ERR_LOGIN_WITH_GOOGLE", "gaga expired")
        Unauthorized("")
      }
    } else {
      Msg2(
        """Session expired - please <a href="/doe/join">start again</a>.
          |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin, Some("/doe/join")
      )
          .withNewSession
          .discardingCookies(DiscardingCookie("dieselProxyNode"))
    }
  }

  /** manage remote cluster user actions */

  WikiObservers mini {
    case WikiEvent("AUTH_CLEAN", "User", id, au, _, _, _) => {
      if(au.isDefined)
        Services.auth.cleanAuth2(au.get.asInstanceOf[WikiUser])
      else
        Services.auth.cleanAuth2(Users.findUserById(new ObjectId(id)).get)
    }
    case WikiEvent("AUTH_REMOTE", "User", id, pass, _, _, _) => {
      // user logged in with remote profile on other node, import here too if i can't reach same persistence
      if (Users.findUserByEmailDec(id).isEmpty) {
        adminImport.importRemoteUser(id, Enc.unapply(pass.mkString).mkString)
      }
    }
  }

}


object T {
  final val TESTCODE = "RazTesting"
}
