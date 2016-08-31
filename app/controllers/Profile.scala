package controllers

import admin.Config
import mod.snow._
import razie.db.{ROne, Txn}
import razie.{Logging, cout, Snakk}
import razie.db.RMongo._
import razie.wiki.Sec._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, tuple, _}
import play.api.mvc.{RequestHeader, Action, Request}
import razie.OR._
import razie.wiki.util.{PlayTools, Corr, VErrors}
import razie.wiki.admin.{Audit, SendEmail}
import razie.wiki.model.{Reactors, WikiIndex, Wikis, WID}
import razie.wiki.Enc

object Profile extends RazController with Logging {

  final val cNoConsent = new Corr("Need consent!", """You need to <a href="/doe/consent">give your consent</a>!""");

  val parentForm = Form(
    "parentEmail" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)))

  val prefsForm = Form {
    tuple(
      "css" -> nonEmptyText.verifying("Wrong value!", Array("dark", "light").contains(_)).verifying("Invalid characters", vldSpec(_)),
      "favQuote" -> text.verifying("Invalid characters", vldSpec(_)),
      "weatherCode" -> text.verifying("Invalid characters", vldSpec(_))) verifying
      ("Password mismatch - please type again", { t: (String, String, String) =>
        val (css, favQuote, weatherCode) = t
        true
      })
  }

  /** temporary registration/login form */
  case class Registration(email: String, password: String, repassword: String = "") {
    def ename = email.replaceAll("@.*", "")
  }

  def registerForm (implicit request : Request[_]) = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> nonEmptyText.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repassword" -> text)(Registration.apply)(Registration.unapply) verifying
      ("Password mismatch - please type again", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
        else true
      }) verifying
      ("Wrong email or password - please type again. (To register a new account, enter the password twice...)", { reg: Registration =>
        //          println ("======="+reg.email.enc+"======="+reg.password.enc)
        if (reg.password.length > 0 && reg.repassword.length <= 0)
          // TODO optimize - we lookup users twice on loing
          Users.findUser(reg.email.enc).orElse(Users.findUserNoCase(reg.email)).map { u =>
            //          println ("======="+u.email+"======="+u.pwd)
            if (reg.password.enc == u.pwd) true
            else {
              u.auditLoginFailed (Website.getRealm)
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
          Users.findUser(reg.email.enc).map(u => false) getOrElse { true }
        else true
      })
  }

  import play.api.data.Forms._
  import play.api.data._

  // create profile
  case class CrProfile(firstName: String, lastName: String, yob: Int, address: String, userType: String, accept: Boolean, g_recaptcha_response: String="")

  def crProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Config.userTypes.contains(ut)),
      "accept" -> checked("").verifying("You must accept the Terms of Service to use this site", { x: Boolean => x }),
      "g-recaptcha-response" -> text)(CrProfile.apply)(CrProfile.unapply) verifying
      ("CAPTCHA failed!", { cr: CrProfile =>
        Recaptcha.verify2(cr.g_recaptcha_response, clientIp)
      }) verifying
      ("Can't use last name for organizations!", { cr: CrProfile =>
        cr.userType != UserType.Organization.toString || cr.lastName.length <= 0
      })
  }

  // profile
  def edProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Config.userTypes.contains(ut)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)))(
        (f, l, t, y, a) => User("kuku", f, l, y, "noemail", "nopwd", 'a', Set(t), Set(), (if (a != null && a.length > 0) Some(a) else None)))(
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

  // todo stop fucking with the routes again
  def doeJoinAlso = doeJoin("", "", "")

  // join step 1
  def doeJoin(club: String, role: String, next: String) = Action {implicit request=>
    auth // clean theme
    (ROK.r noLayout {implicit stok=> views.html.doeJoin(registerForm)}).withSession(
      "gaga" -> System.currentTimeMillis.toString,
      "extra" -> "%s,%s,%s".format(club, role, next))
  } // continue with register()

  // when joining with google first time and have account - enter password
  def doeJoin1Google = Action {implicit request=>
    auth // clean theme
    (ROK.r noLayout {implicit stok=> views.html.doeJoinGoogle(registerForm)}).withSession("gaga" -> request.session.get("gaga").mkString, "extra" -> request.session.get("extra").mkString,  "gid" -> request.session.get("gid").mkString)
  } // continue with register()

  /** join with email */
  def doeJoinWith(email: String) = Action {implicit request=>
    auth // clean theme
    log("joinWith email=" + email)
    (ROK.r noLayout {implicit stok=> views.html.doeJoin(registerForm.fill(Registration(email.dec, "", "")))}).withSession(
      "gaga" -> System.currentTimeMillis.toString
    )
  } // continue with register()

  // join step 2 - submited email/pass form
  def doeJoin2 = Action { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
    formWithErrors => (ROK.r badRequest {implicit stok=> views.html.doeJoin(formWithErrors)}).withSession("gaga" -> System.currentTimeMillis.toString,
      "extra" -> request.session.get("extra").mkString),
    {
      case reg @ Registration(e, p, r) => {
        val g = try {
          (request.session.get("gaga").map(identity).getOrElse("1")).toLong
        } catch {
          case _: Throwable => 1
        }

        // allow this only for some minutes
        if (System.currentTimeMillis - g <= 120000) {
          login(reg.email, reg.password, request.session.get("extra").mkString)
        } else {
          Msg2(
            """Session expired - please <a href="/doe/join">start again</a>.
              |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin, Some("/doe/join")).withNewSession
        }
      }
    })
  }

  // join step 2 with google - link to existing account
  def doeJoin2Google = Action { implicit request =>
    auth // clean theme
    registerForm.bindFromRequest.fold(
      formWithErrors => {
        cout << formWithErrors
        (ROK.r badRequest {implicit stok=> views.html.doeJoinGoogle(formWithErrors)}).withSession("gaga" -> request.session.get("gaga").mkString,
          "extra" -> request.session.get("extra").mkString, "gid" -> request.session.get("gid").mkString)
      },
      {
        case reg @ Registration(e, p, r) => {
          val g = try {
            (request.session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _: Throwable => 1
          }

          // allow this only for some minutes
          if (System.currentTimeMillis - g <= 120000 && request.session.get("gid").isDefined) {
            // associate user to google id and login
            Users.findUser(Enc(reg.email)) orElse (Users.findUserNoCase(reg.email)) foreach {u =>
              u.update(u.copy(gid = request.session.get("gid")))
            }
            login(reg.email, reg.password, request.session.get("extra").mkString, request.session.get("gid").mkString)
          } else {
            Msg2(
              """Session expired - please <a href="/doe/join">start again</a>.
                |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin, Some("/doe/join")).withNewSession
          }
        }
      })
  }

  // TODO this shoud be private
  def updateUser(old: User, newU: User)(implicit request: Request[_]) = {
    old.update(newU)
    cleanAuth(Some(newU))
    newU
  }

  /** login or start registration */
  def login(email: String, pass:String, extra: String, gid:String="") (implicit request:Request[_]) = {
    // TODO optimize - we lookup users twice on login
    val realm = Wikie.getRealm()
    Users.findUser(Enc(email)) orElse (Users.findUserNoCase(email)) match {
      case Some(u) =>
        if (Enc(pass) == u.pwd || u.gid.exists(_ == gid)) {
          Audit.logdb("USER_LOGIN", u.userName, u.firstName + " " + u.lastName + " realm: " + realm)
          u.auditLogin(realm)
          debug("SEss.conn=" + (Config.CONNECTED -> Enc.toSession(u.email)))
          if(u.profile.flatMap(_.consent).isDefined) {
            Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(u.email))
          } else
            (ROK.r noLayout {implicit stok=> views.html.user.doeConsent()}).withSession(Config.CONNECTED -> Enc.toSession(u.email))
        } else {
          u.auditLoginFailed(realm)
          Redirect(routes.Profile.doeJoin()).withNewSession
        }
      case None => // capture basic profile and create profile
        Redirect(routes.Profile.doeJoin3).flashing("email" -> email, "pwd" -> pass, "gid"->gid, "extra" -> extra)
    }
  }

  /** logout */
  def doeLogout () = Action { implicit request =>
    Redirect("/").withNewSession
  }

  def doeConsent (next:String) = Action { implicit request =>
    forActiveUser { implicit au =>
      ROK.s noLayout {implicit stok=> views.html.user.doeConsent()}
    }
  }

  def doeConsent2 (ver:String, next:String) = Action { implicit request =>
    forActiveUser { au =>
      au.profile.map(p => p.update(p.consented(ver)))
      UserEvent(au._id, "CONSENTED").create
      cleanAuth()
      Msg2("Thank you!", Some(next))
    }
  }

  /** start registration long form - submit is doeCreateProfile */
  def doeJoin3 = Action { implicit request =>
    auth // clean theme
    (for (
      e <- request.flash.get("email");
      p <- request.flash.get("pwd") orElse request.flash.get("gid")
    ) yield
      (ROK.r noLayout {implicit stok=>
        views.html.user.doeJoin3(crProfileForm.fill(
          CrProfile("", "", 13, "", "racer", false)))}).withSession("pwd" -> p, "email" -> e, "extra" -> request.flash.get("extra").mkString, "gid" -> request.flash.get("gid").mkString)
    ) getOrElse
      unauthorized(
        """Session expired [join3] - please <a href="/doe/join">start again</a>."""
        ).withNewSession
  }

  private def dfltCss = Config.sitecfg("dflt.css") getOrElse "light"

  /** join step 4 - after captcha: create profile and send emails */
  def doeCreateProfile(testcode: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    def getFromSession(s: String, d: String) =
      if (testcode != T.TESTCODE) request.session.get(s)
      else Some(d)

    def unameauto (yob:Int) = Config.sitecfg("userName.auto").exists(_ startsWith "ye") && DateTime.now.year.get - yob > 12
    def unameF (f:String,l:String) = (f + (if (l.length > 0) ("." + l) else "")).replaceAll("[^a-zA-Z0-9\\.]", ".").replaceAll("[\\.\\.]", ".")

    def uname (f:String,l:String,yob:Int) =
      if (! unameauto(yob))
        System.currentTimeMillis.toString
      else unameF(f.trim,l.trim)

    auth // clean theme

    val resp = crProfileForm.bindFromRequest

    resp.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        (ROK.r badRequest {implicit stok=> views.html.user.doeJoin3(formWithErrors)}).withSession(
          "pwd" -> getFromSession("pwd", T.TESTCODE).get,
          "email" -> getFromSession("email", "@k.com").get,
          "extra" -> getFromSession("extra", "extra").mkString,
          "gid" -> request.session.get("gid").mkString)
      },
      {
        //  case class CrProfile (firstName:String, lastName:String, yob:Int, email:String, userType:String)
        case CrProfile(f, l, y, addr, ut, accept, _) =>

          (for (
            p <- getFromSession("pwd", T.TESTCODE) orErr ("psession corrupted");
            e <- getFromSession("email", f + l + "@k.com") orErr ("esession corrupted");
            already <- (!Users.findUser(Enc(e)).isDefined) orCorr ("User already created" -> "patience, patience...");
            u <- Some(User(
              uname(f, l, y), f.trim, l.trim, y, Enc(e),
              Enc(p), 'a', Set(ut),
              Set(Website.realm),
              (if (addr != null && addr.length > 0) Some(addr) else None),
              Map("css" -> dfltCss, "favQuote" -> "Do one thing every day that scares you - Eleanor Roosevelt", "weatherCode" -> "caon0696"),
              request.flash.get("gid") orElse request.session.get("gid")))
          ) yield {
            // finally created a new account/profile
            if (u.isUnder13 && !u.isClub) {
              Redirect(routes.Tasks.addParent1).withSession("ujson" -> u.toJson, "extra" -> request.session.get("extra").mkString, "gid" -> request.flash.get("gid").mkString)
            } else {
              razie.db.tx("doeCreateProfile") { implicit txn =>
                // TODO bad code - update and reuse account creation code in Tasks.addParent
                val created = { u.create(u.mkProfile); Some(u)}
                created.foreach { x => RacerKidz.myself(x._id) }
                UserTasks.verifyEmail(u).create
                if (u.isClub) {
                  UserTasks.setupCalendars(u).create
                  UserTasks.setupRegistration(u).create
                }

                // TODO why the heck am i sleeping?
                //              Thread.sleep(1000)
                SendEmail.withSession { implicit mailSession =>
                  Tasks.sendEmailVerif(u)
                  if (!unameauto(u.yob))
                    Emailer.sendEmailUname(unameF(u.firstName, u.lastName), u)
                  Emailer.tellRaz("New user", u.userName, u.email.dec, "realm: "+u.realms.mkString)
                }
              }

              // process extra parms to determine what next
              request.session.get("extra") map { x =>
                val s = x split ","
                if (s.size > 0 && s(0).length > 0) {
                  Tasks.msgVerif(u, s"""\n<p><font style="color:red">Click below to continue joining the ${s(0)}.</font>""", Some("/wikie/linkuser/Club:" + s(0) + "?wc=0"))
                } else
                  Tasks.msgVerif(u, "", Some(routes.Profile.doeConsent().url))
              } getOrElse Tasks.msgVerif(u, "", Some(routes.Profile.doeConsent().url))
            }
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER.doeCreateProfile " + getFromSession("email", f + l + "@k.com"))
            unauthorized("Oops - cannot update this user [doeCreateProfile " + getFromSession("email", f + l + "@k.com") + "] - Please try again or send a suport request!").withNewSession
          }
      }) //fold
  }

  // TODO should be private
  def createUser(u: User)(implicit request: Request[_], txn:Txn) = {
    val created = {u.create(u.mkProfile); Some(u)}
    created.foreach { x => RacerKidz.myself(x._id) }
    UserTasks.verifyEmail(u).create
    // TODO why the heck am i sleeping?
    //              Thread.sleep(1000)
    SendEmail.withSession { implicit mailSession =>
      Tasks.sendEmailVerif(u)
      val uname = (u.firstName + (if (u.lastName.length > 0) ("." + u.lastName) else "")).replaceAll("[^a-zA-Z0-9\\.]", ".").replaceAll("[\\.\\.]", ".")
      Emailer.sendEmailUname(uname, u, false)
      Emailer.tellRaz("New user", u.userName, u.email.dec)
    }
    created
  }

  /** show profile **/
  def doeProfile = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- auth orCorr cNoAuth
    ) yield {
      Ok(views.html.user.doeProfile(edProfileForm.fill(au), au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** show children in profile **/
  def profile2(child: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Users.findUserById(child)
    ) yield {
      log("PC " + au._id + "        " + c._id)
      log("PC " + Users.findPC(au._id, c._id))
      val ParentChild(_, _, t, n, _) = Users.findPC(au._id, c._id).getOrElse(ParentChild(null, null, "Private", "Everything"))
      Ok(views.html.user.edChildren(edprofileForm2.fill((t, n)), child, au))
    }) getOrElse unauthorized("Oops - how did you get here? [p2]")
  }

  /** edited children in profile **/
  def profile2u(child: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    edprofileForm2.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        BadRequest(views.html.user.edChildren(formWithErrors, child, auth.get))
      },
      {
        case (t, n) => {
          for (
            au <- activeUser;
            c <- Users.findUserById(child)
          ) yield {
            Users.findPC(au._id, c._id) match {
              case Some(pc) => pc.update(ParentChild(au._id, c._id, t, n, pc._id))
              case None => ParentChild(au._id, c._id, t, n).create
            }
            Redirect("/")
          }
        } getOrElse
          {
            verror("ERR_CANT_UPDATE_USER.profile2u " + request.session.get("email"))
            unauthorized("Oops - cannot update this user [profile2u]... ")
          }
      })
  }

  def doeUpdPrefs = Action { implicit request =>
    prefsForm.bindFromRequest.fold(
    formWithErrors => BadRequest(views.html.user.doeProfilePreferences(formWithErrors, auth.get)),
    {
      case (css, favQuote, weatherCode) => forActiveUser { au =>
        val u = updateUser(au, au.copy(prefs=au.prefs ++
          Seq("css" -> css, "favQuote" -> favQuote, "weatherCode" -> weatherCode)))
        //          val u = updateUser(au, User(au.userName, au.firstName, au.lastName, au.yob, au.email, au.pwd, au.status, au.roles, au.addr, au.prefs ++
        //            Seq("css" -> css, "favQuote" -> favQuote, "weatherCode" -> weatherCode),
        //            au._id))
        Emailer.withSession { implicit mailSession =>
          au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
        }
        Redirect(routes.Profile.doeProfilePreferences)
      }
    })
  }

  // user chose to login with google
  def doeJoinGtoken = Action { implicit request =>
    val b = request.body.asFormUrlEncoded.get
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
        Users.findUser(Enc(email)) orElse (Users.findUserNoCase(email)) match {
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
          |<p>Please make sure your browser allows cookies for this website, thank you!""".stripMargin, Some("/doe/join")).withNewSession
    }
  }

    def doeProfilePreferences = Action { implicit request =>
    forUser { au =>
      Ok(views.html.user.doeProfilePreferences(prefsForm.fill((
        au.getPrefs("css",dfltCss),
        au.getPrefs("favQuote",""),
        au.getPrefs("weatherCode",""))),
        au))
    }
  }

  def doeHelp = Action { implicit request =>
    forActiveUser { au =>
      Ok(views.html.user.doeProfileHelp(au))
    }
  }

  def doeProfileUpdate = Action { implicit request =>
    edProfileForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfile(formWithErrors, auth.get)),
      {
        case u: User =>
          forActiveUser { au =>
            updateUser(au, au.copy(au.userName, u.firstName, u.lastName, u.yob, au.email, au.pwd, au.status, u.roles, au.realms, u.addr, au.prefs, au.gid, au._id))
            Emailer.withSession { implicit mailSession =>
              au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
            }
            cleanAuth()
            Redirect(routes.Profile.doeProfile)
          }
      })
  }

  //////////////////// passwords

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

  def doeProfilePass = Action { implicit request =>
    forUser { au =>
      Ok(views.html.user.doeProfilePass(chgpassform, au))
    }
  }

  def doeProfilePass2 = Action { implicit request =>
    implicit val errCollector = new VErrors()
    chgpassform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfilePass(formWithErrors, auth.get)),
      {
        case (o, n, _) =>
          (for (
            au <- activeUser;
            pwdCorrect <- {
              // sometimes the password plus ADMIN doesn't work...
              (if (Enc(o) == au.pwd || ("ADMIN" + au._id.toString.reverse == o)) Some(true) else None) orErr ("Password incorrect!")
              // the second form is hack to allow me to reset it
            }
          ) yield {
//            updateUser(au, User(au.userName, au.firstName, au.lastName, au.yob, au.email, Enc(n), au.status, au.roles, au.addr, au.prefs, au._id))
            updateUser(au, au.copy(pwd=Enc(n)))
            Msg2("Ok, password changed!")
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER_PASSWORD ")
            unauthorized("Oops - cannot update this user [ERR_CANT_UPDATE_USER_PASSWORD]... ")
          }
      })
  }

  def publicProfile = Action { implicit request =>
    forUser { au =>
      if (WikiIndex.withIndex(Wikis.RK)(_.get2(au.userName, WID("User", au.userName)).isDefined))
        Redirect(controllers.Wiki.w(WID("User", au.userName)))
      else
        Redirect(routes.Wikie.wikieEdit(WID("User", au.userName)))
    }
  }

  final val prov = Array("ON", "QC", "Other")
  final val countries = Array("Canada", "US", "Other")

  // Contact
  def edContactForm(implicit request: Request[_]) = Form {
    mapping(
      "streetAndNo" -> text.verifying(vSpec),
      "aptNo" -> text.verifying(vSpec),
      "city" -> nonEmptyText.verifying(vSpec),
      "postalCode" -> text.verifying(vSpec, vPostalCode),
      "state" -> text.verifying(vSpec),
      "country" -> text.verifying(vSpec),
//      "state" -> nonEmptyText.verifying("Please select one", ut => prov.contains(ut)),
//      "country" -> nonEmptyText.verifying("Please select one", ut => countries.contains(ut)),
      "cellPhone" -> text,
      "homePhone" -> text,
      "workPhone" -> text)(
        (st, ap, ci, po, pr, co, ce, ho, wo) => Contact(Map(
          "streetAndNo" -> st,
          "aptNo" -> ap,
          "city" -> ci,
          "postalCode" -> po,
          "provinceState" -> pr,
          "country" -> co,
          "cellPhone" -> ce,
          "homePhone" -> ho,
          "workPhone" -> wo)))(
          (c: Contact) => {
            def p(s: String) = c.info.getOrElse(s, "")
            Some(
              p("streetAndNo"),
              p("aptNo"),
              p("city"),
              p("postalCode"),
              p("provinceState"),
              p("country"),
              p("cellPhone"),
              p("homePhone"),
              p("workPhone"))
          })
  }

  /** show profile **/
  def doeContact = Action { implicit request =>
    forUser { au =>
      Ok(views.html.user.doeContact(
        edContactForm.fill(
          au.profile.flatMap(_.contact).getOrElse(Contact(Map.empty))), au))
    }
  }

  def doeContactUpdate = Action { implicit request =>
    edContactForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeContact(formWithErrors, auth.get)),
      {
        case c => forActiveUser { au =>
          au.profile.map(p => p.update(p.setContact(c)))
          au.profile.map(_.setContact(c))

          Emailer.withSession { implicit mailSession =>
            au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
          }
          cleanAuth()
          Redirect(routes.Profile.doeContact)
        }
      })
  }
}

object EdUsername extends RazController {
  // profile
  val chgusernameform = Form {
    tuple(
      "currusername" -> text,
      "newusername" -> text.verifying(
        "Too short!", p => p.length > 3).verifying(
        "Too long!", p => p.length <= 14).verifying(
        "That name is reserved!", p => !Config.reservedNames.contains(p)).verifying(
        "No spaces please", p => !p.contains(" "))) verifying
      ("Can't use the same name", { t: (String, String) => t._1 != t._2 }) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByUsername(t._2).isDefined })
  }

  // authenticated means doing a task later
  def doeProfileUname = Action { implicit request =>
    forUser { au =>
      Ok(views.html.user.doeProfileUname(chgusernameform.fill(au.userName, ""), au))
    }
  }

  def doeProfileUname2 = Action { implicit request =>
    implicit val errCollector = new VErrors()
    chgusernameform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfileUname(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            au <- activeUser;
            ok <- (o == au.userName) orErr ("Not correct old username");
            isc <- (!au.isClub) orErr ("Cannot change names for clubs, sorry - too many at stake")
          ) yield {
            SendEmail.withSession { implicit mailSession =>
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
          razie.db.tx("accept.user") { implicit txn =>
            Profile.updateUser(u, u.copy(userName=newusername))
            UserTasks.userNameChgDenied(u).delete
            Wikis.updateUserName(u.userName, newusername)
            Emailer.withSession { implicit mailSession =>
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
         razie.db.tx("deny.user") { implicit txn =>
            UserTasks.userNameChgDenied(u).create
            Emailer.withSession { implicit mailSession =>
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

object EdEmail extends RazController {
  val emailForm = Form {
    tuple(
      "curemail" -> text,
      "newemail" -> text.verifying(vSpec, vEmail)) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUser(t._2.enc).isDefined })
  }

  // authenticated means doing a task later
  def doeProfileEmail() = Action { implicit request =>
    (for (
      au <- auth orCorr cNoAuth
    ) yield Ok(views.html.user.doeProfileEmail(emailForm.fill(au.email.dec, ""), auth.get))) getOrElse
      unauthorized("Oops - how did you get here? [step1]")
  }

  def doeProfileEmail2() = Action { implicit request =>
    implicit val errCollector = new VErrors()
    emailForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfileEmail(formWithErrors, auth.get)),
      {
        case (o, n) =>
          (for (
            au <- activeUser
          ) yield {
            razie.db.tx("change.email") { implicit txn =>
              val newu = au.copy(email = n.enc)
              //            val newu = User(au.userName, au.firstName, au.lastName, au.yob, n.enc, au.pwd, au.status, u.roles, u.addr, u.prefs, u._id)
              Profile.updateUser(au, newu)
              val pro = newu.profile.getOrElse(newu.mkProfile)
              pro.update(pro.removePerm("+" + Perm.eVerified.s))
              UserTasks.verifyEmail(newu).create

              Emailer.withSession { implicit mailSession =>
                Tasks.sendEmailVerif(newu)
                Tasks.msgVerif(newu)
              }
            }
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER_EMAIL.doeProfileEmail2 ")
            unauthorized("Oops - cannot update this user [doeProfileEmail2]... ")
          }
      })
  }
}

object Kidz extends RazController {

  def doeUserKids = FAU { implicit au => implicit errCollector => implicit request =>
    ROK.s apply {implicit stok=> views.html.user.doeUserKidz() }
  }

  def form(au: User) = Form {
    tuple(
      "fname" -> nonEmptyText.verifying(vSpec, vPorn),
      "lname" -> nonEmptyText.verifying(vSpec, vPorn),
      "email" -> text.verifying(vSpec, vEmail),
      "dob" -> jodaDate,
      "gender" -> text.verifying(vSpec).verifying(x => x == "M" || x == "F"),
      "role" -> nonEmptyText.verifying(vSpec, vPorn),
      "status" -> text.verifying(vSpec),//.verifying("bad status", x => "asf" contains x),
      "assocRole" -> nonEmptyText.verifying(vSpec, vPorn),
      "invite" -> text.verifying(vSpec, vPorn),
      "notifyParent" -> text.verifying(vSpec).verifying(x => x == "y" || x == "n")) verifying (
        "Must notify parent if there's no email...", {
          t: (String, String, String, DateTime, String, String, String, String, String, String) =>
            val _@ (f, l, e, d, g, r, ar, s, i, n) = t
            !(e.isEmpty && n == "n")
        }) verifying (
          "Must have no more than one spouse...", {
            t: (String, String, String, DateTime, String, String, String, String, String, String) =>
              val _@ (f, l, e, d, g, r, ar, s, i, n) = t
              !(r == RK.ROLE_SPOUSE && RacerKidz.findForUser(au._id).exists(_.info.roles.toString == RK.ROLE_SPOUSE))
          }) verifying (
            "You are already defined...", {
              t: (String, String, String, DateTime, String, String, String, String, String, String) =>
                val _@ (f, l, e, d, g, r, ar, s, i, n) = t
                !(r == RK.ROLE_ME && RacerKidz.findForUser(au._id).exists(_.info.roles.toString == RK.ROLE_ME))
            })
  }

  def canSeeKid(rkid:String, au:User, next:String) = {
    if(au.isClub) true
    else if(next.startsWith("kidz")) {
      //user managing his kids
      au.rka.toList.find(_.to.toString == rkid).isDefined
    } else if(next.startsWith("clubkidz:")) {
      val cname = next.substring(next.indexOf(':'))
      Club(cname).exists(c=> c.isClubCoach(au) || c.isClubAdmin(au))
    } else false
  }

  def canEditKid(rkid:String, au:User, next:String) = {
    if(au.isClub) true
    else if(next.startsWith("kidz")) {
      //user managing his kids
      au.rka.toList.find(_.to.toString == rkid).isDefined
    } else if(next.startsWith("clubkidz:")) {
      val cname = next.substring(next.indexOf(':')+1)
      Club(cname).exists(c=> c.isClubAdmin(au))
    } else false
  }

  // authenticated means doing a task later
  def doeKid(pId: String, rkId: String, role: String, associd: String, next: String) = Action { implicit request =>
    (for (
      au <- auth orCorr cNoAuth
    ) yield {

      val rk = if (rkId.length > 3) RacerKidz.findById(new ObjectId(rkId)) else None

      val k =
        if (rkId.length > 3)
          rk map (_.info) getOrElse RacerKidz.empty
        else RacerKidz.empty

      val rki =
        if (rkId.length > 3)
          rk flatMap (_.rki) getOrElse RacerKidz.empty
        else RacerKidz.empty

      val arole = if (associd.length > 3) new ObjectId(associd).as[RacerKidAssoc].get.role else ""

      ROK.s(au,request) apply {implicit stok=> (views.html.user.doeUserKid(pId, rkId, role, associd, next, form(au).fill((
        k.firstName, k.lastName, k.email.dec,
        rk flatMap (_.rki) map (_.dob) getOrElse rk.map(rk => new DateTime(rk.info.yob, 1, 1, 1, 1)).getOrElse(RacerKidz.empty.dob),
        rki.gender,
        if (rkId.length > 3) k.roles.toString else role,
        arole,
        k.status.toString,
        "",
        if (k.notifyParent) "y" else "n"))))}
    }) getOrElse
      unauthorized("Oops - how did you get here? [step1]")
  }

  def doeKidUpdate(userId: String, rkId: String, role: String, associd: String, next: String) = FAU { implicit au => implicit errCollector => implicit request =>
    var goodRkid = rkId
    form(auth.get).bindFromRequest.fold(
      formWithErrors => ROK.s badRequest {implicit stok=>(views.html.user.doeUserKid(userId, rkId, role, associd, next, formWithErrors))},
      {
        case (xf, xl, xe, d, g, r, s, ar, i, n) =>
          val f = xf.trim
          val l = xl.trim
          val e = xe.trim
          val status=if(s.length > 1) s else "a"

          def res = if (next startsWith "Club:")
            Redirect(routes.Club.doeClubReg(next.substring(next.indexOf(':')+1), Club(au).userLinks.filter(_.userId.toString == userId).next._id.toString))
          else if (next startsWith "ClubMem:")
            Redirect(routes.Club.doeClubRegs(next.substring(next.indexOf(':')+1)))
          else if (next startsWith "clubkidz:")
            Redirect(routes.Club.doeClubKids(next.substring(next.indexOf(':')+1)))
          else if (next startsWith "invite:")
            Redirect(routes.Kidz.doeKidHistory(next.substring(next.indexOf(':')+1), goodRkid, ""))
          else if (next == "kidz")
            Redirect(routes.Kidz.doeUserKids)
          else
            Redirect(routes.Club.doeClubUserReg(next))

          // just update the association type - for former members, to make them fans
          var assocOnly = false
          if (rkId.length > 2 && associd.length > 2) {
            new ObjectId(associd).as[RacerKidAssoc].foreach { rka =>
              if (ar != rka.role) {
                rka.copy(role = ar).update
                //todo update the userlink as well
                assocOnly = true
              }
            }
          }

          if (assocOnly) {
            res
          } else if (rkId.length > 2) {
            // update
            val rk = RacerKidz.findById(new ObjectId(rkId)).get
            val rki = rk.rki.get
            rki.copy(firstName = f, lastName = l, email = e.enc, dob = d,
              gender = g, status = status charAt 0, roles = Set(r)).update
//              if (associd.length > 2) new ObjectId(associd).as[RacerKidAssoc].foreach { rka =>
//                if (ar != rka.role)
//                  rka.copy(role = ar).update
//              }
            res
          } else {
            // create
            if (RacerKidz.findByParentUser(new ObjectId(userId)).exists(x => x.info.firstName == f || (x.info.email.dec == e && e.length > 1)))
            // is there already one with same name or email?
              Msg2("Kid with same first name or email already added", Some("/doe/user/kidz"))
            else {
              val rk = new RacerKid(au._id)
              val rki = new RacerKidInfo(f, l, e.enc, d, g, Set(r), status charAt 0, n == "y", rk._id, au._id)
              rk.copy(rkiId = Some(rki._id)).create
              rki.create
              goodRkid = rk._id.toString
              RacerKidAssoc(
                new ObjectId(userId), rk._id, RK.ASSOC_PARENT,
                role OR r,
                au._id).create
              (rk, rki)

              if (next startsWith "invite:") {
                //todo email invite
              }

              res
            }
          }
      })
  }

  def doeKidOverride(userId: String, rkId: String, role: String, associd: String, next: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      rk <- RacerKidz.findById(new ObjectId(rkId));
      u <- rk.user
    ) yield {
        def res = if (next startsWith "Club:")
          Redirect(routes.Club.doeClubReg(next.substring(next.indexOf(':')), Club(au).userLinks.filter(_.userId.toString == userId).next._id.toString))
        else if (next startsWith "ClubMem:")
          Redirect(routes.Club.doeClubRegs(next.substring(next.indexOf(':'))))
        else if (next startsWith "clubkidz:")
          Redirect(routes.Club.doeClubKids())
        else if (next == "kidz")
          Redirect(routes.Kidz.doeUserKids)
        else
          Redirect(routes.Club.doeClubUserReg(next))

        val rki = new RacerKidInfo(
          u.firstName,
          u.lastName,
          u.email,
          DateTime.parse(u.yob.toString + "-01-01"),
          u.gender,
          u.roles,
          'a',
          true,
          rk._id,
          au._id)

        rki.create
        rk.copy(rkiId = Some(rki._id)).update

        Redirect(routes.Kidz.doeKid(userId, rkId, role, associd, next))
      }) getOrElse {
      error("ERR_CANT_CREATE_KID ")
      unauthorized("Oops - cannot create this entry... ")
    }
  }

  def doeKidHistory(club:String, rkId: String, settings:String) = FAU { implicit au=> implicit errCollector=> implicit request =>
    (for (
      rk <- RacerKidz.findById(new ObjectId(rkId));
      c <- Club(club);
      can <- (c.isClubAdmin(au) || c.isMemberRole(au._id, "Coach") || rk.userId.exists(_ == au._id)) orCorr cNotCoach(club)
    ) yield {
        ROK.s apply {implicit stok=> views.html.user.doeKidHistory(c, rk, settings) }
      }) getOrElse unauthorized(rkId + "      " + au._id.toString + "   "+RacerKidz.findById(new ObjectId(rkId)).flatMap(_.userId).mkString)
  }

  def doeHistory(settings:String) = FAU { implicit au=> implicit errCollector=> implicit request =>
    (for (
      rk <- ROne[RacerKid]("userId" -> Some(au._id));
      c <- rk.clubs.headOption;
      can <- (rk.userId.exists(_ == au._id)) orCorr cNotCoach(c.name)
    ) yield {
        ROK.s apply {implicit stok=> views.html.user.doeKidHistory(c, rk, settings) }
      }) getOrElse unauthorized()
  }

  def doeDismissHistory(eid:String) = FAU { implicit au=> implicit errCollector=> implicit request =>
    (for (
      rk <- ROne[RacerKid]("userId" -> Some(au._id));
      can <- (rk.userId.exists(_ == au._id)) orErr "not yours to dismiss";
      i <- rk.history.findByElement(eid).headOption orErr "can't find history element"
    ) yield {
        rk.history.delete(i)
        Ok("ok")
    }) getOrElse unauthorized()
  }
}

object T {
  final val TESTCODE = "RazTesting"
}

