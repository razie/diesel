package controllers

import mod.diesel.controllers.{DieselMsgString, DieselMsg}
import mod.snow._
import razie.base.Audit
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
import play.api.mvc.{Action, Request}
import razie.OR._
import razie.wiki.admin.SendEmail
import razie.wiki.model.{WikiEvent, WikiIndex, Wikis, WID}
import razie.wiki.{Services, Enc}

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
  case class Registration(email: String, password: String, reemail:String="", repassword: String = "") {
    def ename = email.replaceAll("@.*", "")
  }

  def registerForm (implicit request : Request[_]) = Form {
    mapping(
      "email" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "password" -> nonEmptyText.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "reemail" -> text,
      "repassword" -> text)(Registration.apply)(Registration.unapply) verifying
      ("Email mismatch - please type again", { reg: Registration =>
        if (reg.reemail.length > 0 && reg.email.length > 0 && reg.email != reg.reemail) false
        else true
      }) verifying
      ("Password mismatch - please type again", { reg: Registration =>
        if (reg.password.length > 0 && reg.repassword.length > 0 && reg.password != reg.repassword) false
        else true
      }) verifying
      ("Wrong email or password - please type again. (To register a new account, enter the password twice...)", { reg: Registration =>
        //          println ("======="+reg.email.enc+"======="+reg.password.enc)
        if (reg.password.length > 0 && reg.repassword.length <= 0)
          // TODO optimize - we lookup users twice on loing
          Users.findUserByEmail(reg.email.enc).orElse(Users.findUserNoCase(reg.email)).map { u =>
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
          Users.findUserByEmail(reg.email.enc).map(u => false) getOrElse { true }
        else true
      })
  }

  import play.api.data.Forms._
  import play.api.data._

  // create profile
  case class CrProfile(firstName: String, lastName: String, yob: Int, address: String, userType: String, accept: Boolean, g_recaptcha_response: String="", about:String="")

  def crProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "yob" -> number(min = 1900, max = 2012),
      "address" -> text.verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText.verifying("Please select one", ut => Website.userTypes.contains(ut)),
      "accept" -> checked("").verifying("You must accept the Terms of Service to use this site", { x: Boolean => x }),
      "g-recaptcha-response" -> text,
      "about" -> text.verifying("Invalid characters", vldSpec(_))
      )(CrProfile.apply)(CrProfile.unapply) verifying
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
      "userType" -> nonEmptyText.verifying("Please select one", ut => Website.userTypes.contains(ut)),
      "yob" -> number(min = 1900, max = 2012),
      "about" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "address" -> text.verifying("Invalid characters", vldSpec(_)))(
        (f, l, t, y, about, a) =>
          User("kuku", f, l, y, "noemail", "nopwd", 'a', Set(t), Set(), (if (a != null && a.length > 0) Some(a) else None), Map("about"-> about))
    )(
          (u: User) => Some(u.firstName, u.lastName, u.roles.head, u.yob, u.getPrefs("about", ""), u.addr.map(identity).getOrElse(""))) verifying
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

  // todo stop messing with the routes again
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
    (ROK.r noLayout {implicit stok=> views.html.doeJoin(registerForm.fill(Registration(email.dec, "", "", "")))}).withSession(
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
      case reg @ Registration(e, p, _, _) => {
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
        case reg @ Registration(e, p, _, _) => {
          val g = try {
            (request.session.get("gaga").map(identity).getOrElse("1")).toLong
          } catch {
            case _: Throwable => 1
          }

          // allow this only for some minutes
          if (System.currentTimeMillis - g <= 120000 && request.session.get("gid").isDefined) {
            // associate user to google id and login
            Users.findUserByEmail(Enc(reg.email)) orElse (Users.findUserNoCase(reg.email)) foreach {u =>
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
    Users.findUserByEmail(Enc(email)) orElse (Users.findUserNoCase(email)) match {
      case Some(u) =>
        if (Enc(pass) == u.pwd || u.gid.exists(_ == gid)) {
          Audit.logdb("USER_LOGIN", u.userName, u.firstName + " " + u.lastName + " realm: " + realm)
          u.auditLogin(realm)
          debug("SEss.conn=" + (Services.config.CONNECTED -> Enc.toSession(u.email)))

          // process extra parms to determine what next
          var next : Option[String] = None
          var club : String = ""
          request.session.get("extra") map { x =>
            val s = x split ","
            if (s.size > 0 && s(0).length > 0) {
              val prefix = if (s(0).indexOf(":") > 0) "" else "Club:" // for old links with name instead of WID
              next = Some("/wikie/linkuser/" + prefix + s(0) + "?wc=0")
              club=WID.fromPath(s(0)).map(_.wpathnocats).mkString
            }
          }

          (if(u.profile.flatMap(_.consent).isDefined) {
            if(next.isDefined)
              Msg2( s"""Click below to continue joining the $club.""", next)
            else
              Redirect("/")
          } else
            (ROK.r apply {implicit stok=>
              views.html.user.doeConsent(next.getOrElse("/"))
            })
          ).withSession(Services.config.CONNECTED -> Enc.toSession(u.email))
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

  // display consent
  def doeConsent (next:String) = FAUR { implicit request =>
    ROK.k apply {implicit stok=>
      views.html.user.doeConsent(next)
    }
  }

  // accepted consent, record it
  def doeConsent2 (ver:String, next:String) = FAUR { implicit request =>
      request.au.get.profile.map(p => p.update(p.consented(ver)))
      UserEvent(request.au.get._id, "CONSENTED").create
      cleanAuth()
      Msg2("Thank you!", Some(next))
  }

  /** start registration long form - submit is doeCreateProfile */
  def doeJoin3 = Action { implicit request =>
    auth // clean theme
    (for (
      e <- request.flash.get("email");
      p <- request.flash.get("pwd") orElse request.flash.get("gid")
    ) yield
      (ROK.r reactorLayout12  {implicit stok=>
        views.html.user.doeJoin3(crProfileForm.fill(
          CrProfile("", "", 13, "", "racer", false)))}).withSession("pwd" -> p, "email" -> e, "extra" -> request.flash.get("extra").mkString, "gid" -> request.flash.get("gid").mkString)
    ) getOrElse
      unauthorized(
        """Session expired [join3] - please <a href="/doe/join">start again</a>."""
        ).withNewSession
  }

  private def dfltCss = Services.config.sitecfg("dflt.css") getOrElse "light"

  /** join step 4 - after captcha: create profile and send emails */
  def doeCreateProfile(testcode: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    def getFromSession(s: String, d: String) =
      if (testcode != T.TESTCODE) request.session.get(s)
      else Some(d)

    def unameauto (yob:Int) = Services.config.sitecfg("userName.auto").exists(_ startsWith "ye") && DateTime.now.year.get - yob > 12
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
        case CrProfile(f, l, y, addr, ut, accept, _, about) =>

          (for (
            p <- getFromSession("pwd", T.TESTCODE) orErr ("psession corrupted");
            e <- getFromSession("email", f + l + "@k.com") orErr ("esession corrupted");
            already <- (!Users.findUserByEmail(Enc(e)).isDefined) orCorr ("User already created" -> "patience, patience...");
            iu <- Some(User(
              uname(f, l, y), f.trim, l.trim, y, Enc(e),
              Enc(p), 'a', Set(ut),
              Set(Website.realm),
              (if (addr != null && addr.length > 0) Some(addr) else None),
              Map("css" -> dfltCss, "favQuote" -> "Do one thing every day that scares you - Eleanor Roosevelt", "weatherCode" -> "caon0696"),
              request.flash.get("gid") orElse request.session.get("gid")))
          ) yield {
            // finally created a new account/profile
            var u = iu
            if (u.isUnder13 && !u.isClub) {
              Redirect(routes.Tasks.addParent1).withSession("ujson" -> u.toJson, "extra" -> request.session.get("extra").mkString, "gid" -> request.flash.get("gid").mkString)
            } else {
              if(about.length > 0) u = u.copy(prefs = u.prefs + ("about" -> about))

              razie.db.tx("doeCreateProfile") { implicit txn =>
                // TODO bad code - update and reuse account creation code in Tasks.addParent
                val pro = u.mkProfile
                val created = { u.create(pro); Some(u)}
                created.foreach { x => RacerKidz.myself(x._id) }

                // when testing, skip email verification
                if(! Services.config.isLocalhost) {
                  UserTasks.verifyEmail(u).create
                } else {
                  val ppp = pro.addPerm("+" + Perm.eVerified.s).addPerm("+" + Perm.uWiki.s)
                  pro.update(if (u.isUnder13) ppp else ppp.addPerm("+" + Perm.uProfile.s))
                }

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
                  Emailer.tellRaz("New user", u.userName, u.email.dec, "realm: "+u.realms.mkString, "ABOUT: "+about)
                }
              }

              val stok = ROK.r

              //realm new user flow
              Services ! DieselMsgString(
                s"""$$msg user.joined(userName="${u.userName}", realm="${stok.realm}")""",
                WID.fromPath(s"${stok.realm}.Reactor:${stok.realm}#diesel").toList,
                Nil)

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
  def doeProfile = FAUR { implicit request =>
    Ok(views.html.user.doeProfile(edProfileForm.fill(request.au.get), request.au.get))
  }

  /** show children in profile **/
  def profile2(child: String) = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Users.findUserById(child)
    ) yield {
      log("PC " + au._id + "        " + c._id)
      log("PC " + Users.findPC(au._id, c._id))
      val ParentChild(_, _, t, n, _) = Users.findPC(au._id, c._id).getOrElse(ParentChild(null, null, "Private", "Everything"))
      ROK.k noLayout {views.html.user.edChildren(edprofileForm2.fill((t, n)), child, au)}
    }) getOrElse unauthorized("Oops - how did you get here? [p2]")
  }

  /** edited children in profile **/
  def profile2u(child: String) = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    edprofileForm2.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        ROK.k badRequest{views.html.user.edChildren(formWithErrors, child, auth.get)}
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

  def doeUpdPrefs = RAction { implicit request =>
    prefsForm.bindFromRequest.fold(
    formWithErrors => ROK.k badRequest {views.html.user.doeProfilePreferences(formWithErrors, auth.get)},
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
        Users.findUserByEmail(Enc(email)) orElse (Users.findUserNoCase(email)) match {
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

    def doeProfilePreferences = RAction { implicit request =>
    forUser { au =>
      ROK.k noLayout {
        views.html.user.doeProfilePreferences(prefsForm.fill((
        au.getPrefs("css",dfltCss),
        au.getPrefs("favQuote",""),
        au.getPrefs("weatherCode",""))),
        au)
      }
    }
  }

  def doeHelp = FAUR { implicit request =>
    Ok(views.html.user.doeProfileHelp())
  }

  def doeProfileUpdate = RAction { implicit request =>
    edProfileForm.bindFromRequest.fold(
      formWithErrors =>
        ROK.k badRequest{views.html.user.doeProfile(formWithErrors, auth.get)},
      {
        case u: User =>
          forActiveUser { au =>
            val newu = au.copy(firstName=u.firstName, lastName=u.lastName, yob=u.yob,
              roles= {
                if(au.roles.mkString == u.roles.mkString) au.roles
                else (au.roles ++ u.roles)
              },
              addr=u.addr, prefs=au.prefs ++ u.prefs)

            updateUser(au, newu)
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
        if (t._2.length > 0 && t._3.length > 0 && t._3 != t._2) false
        else true
      })
  }

  def doeProfilePass = FAUR { implicit request =>
    Ok(views.html.user.doeProfilePass(chgpassform, request.au.get))
  }

  def doeProfilePass2 = FAUR { implicit request =>
    val au = request.au.get
    chgpassform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfilePass(formWithErrors, auth.get)),
      {
        case (o, n, _) =>
          (for (
            pwdCorrect <- {
              // sometimes the password plus ADMIN doesn't work...
              (if (Enc(o) == au.pwd || ("ADMIN" + au._id.toString.reverse == o)) Some(true) else None) orErr ("Password incorrect!")
              // the second form is hack to allow me to reset it
            }
          ) yield {
            updateUser(au, au.copy(pwd=Enc(n)))
            Msg2("Ok, password changed!")
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER_PASSWORD ")
            unauthorized("Oops - cannot update this user [ERR_CANT_UPDATE_USER_PASSWORD]... ")
          }
      })
  }

  val forgot = Form(
    "email" -> text.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_))
  )

  def doeForgotPass = RAction { implicit stok =>
    ROK.k apply {
      views.html.user.doeProfilePassForgot1(forgot)
    }
  }

  def doeForgotPass2 = RAction { implicit stok =>
    forgot.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {
        views.html.user.doeProfilePassForgot1(formWithErrors)
      },
    {
      case (e) =>
        Users.findUserByEmail(e.enc).map {au=>
          Emailer.withSession { implicit mailSession =>
            Audit.logdb("RESET_PWD", "request for "+e)
            Tasks.sendEmailReset(au)
          }
        } getOrElse {
          Audit.logdb("ERR_RESET_PWD", "email not found "+e)
        }
      Msg2("Please check your email!")
    })
  }

  /** step 2 - user clicked on email link to verify email */
  def doeForgotPass3(expiry:String, id:String) = RAction { implicit stok =>
    (for (
      date <- (try { Option(DateTime.parse(expiry)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
      notExpired <- date.isAfterNow orCorr cExpired;
      p <- Users.findUserById(id) orCorr cNoAuth
    ) yield
      ROK.r apply {
        views.html.user.doeProfilePassForgot2(chgpassform, id)
      }) getOrElse {
      Audit.logdb("ERR_USER_RESET_PWD", stok.errCollector.mkString)
      Msg2("Link expired!")
    }
  }

  def doeForgotPass4(id:String) = RAction { implicit stok =>
      chgpassform.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {
        views.html.user.doeProfilePassForgot2(formWithErrors, id)
      },
      {
        case (_, n, _) =>
          (for (
            au <- Users.findUserById(id)
          ) yield {
              updateUser(au, au.copy(pwd=Enc(n)))
              Msg2("Ok, password changed! Please login.")
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

  /** show profile - if from reg screen, regid is not empty **/
  def doeContact(regid:String) = FAUR { implicit request =>
      Ok(views.html.user.doeContact(regid,
        edContactForm.fill(
          request.au.get.profile.flatMap(_.contact).getOrElse(Contact(Map.empty))), request.au.get))
  }

  def doeContactUpdate(regid:String) = FAUR { implicit request =>
    edContactForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeContact(regid, formWithErrors, auth.get)),
      {
        case c => {
          val au = request.au.get
          au.profile.map(p => p.update(p.setContact(c)))
          au.profile.map(_.setContact(c))

          Emailer.withSession { implicit mailSession =>
            au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedProfile(parent, au))
          }
          cleanAuth()
          if(regid.length > 1) Redirect(routes.Club.doeClubUserReg(regid))
          else Redirect(routes.Profile.doeContact("-"))
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
        "That name is reserved!", p => !Services.config.reservedNames.contains(p)).verifying(
        "No spaces please", p => !p.contains(" "))) verifying
      ("Can't use the same name", { t: (String, String) => t._1 != t._2 }) verifying
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByUsername(t._2).isDefined })
  }

  // authenticated means doing a task later
  def doeProfileUname = FAUR { implicit request =>
      Ok(views.html.user.doeProfileUname(chgusernameform.fill(request.au.get.userName, "")))
  }

  def doeProfileUname2 = FAUR { implicit request =>
    chgusernameform.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(views.html.user.doeProfileUname(formWithErrors)),
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
      ("Sorry - already in use", { t: (String, String) => !Users.findUserByEmail(t._2.enc).isDefined })
  }

  // change email
  def doeProfileEmail() = RAction { implicit request =>
    (for (
      au <- auth orCorr cNoAuth
    ) yield ROK.k noLayout {
        views.html.user.doeProfileEmail(emailForm.fill(au.email.dec, ""), auth.get)
      }
      ) getOrElse
      unauthorized("Oops - how did you get here? [step1]")
  }

  // change email
  def doeProfileEmail2() = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    emailForm.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {views.html.user.doeProfileEmail(formWithErrors, auth.get)},
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

object T {
  final val TESTCODE = "RazTesting"
}

