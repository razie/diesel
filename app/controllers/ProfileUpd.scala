package controllers

import com.google.inject._
import controllers.Emailer.expand
import model._
import org.joda.time.DateTime
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{nonEmptyText, tuple, _}
import play.api.mvc.{Action, DiscardingCookie, Request}
import razie.Logging
import razie.audit.Audit
import razie.hosting.Website
import razie.wiki.Sec._
import razie.wiki.admin.SecLink
import razie.wiki.model._
import razie.wiki.{Config, Enc, Services}

@Singleton
class ProfileUpd @Inject() (config:Configuration) extends RazController with Logging {

  private def dfltCss = Services.config.sitecfg("dflt.css") getOrElse "light"

  val prefsForm = Form {
    tuple(
      "css" -> nonEmptyText.verifying("Wrong value!", Array("dark", "light").contains(_)).verifying("Invalid characters", vldSpec(_)),
      "favQuote" -> text.verifying("Invalid characters", vldSpec(_)),
      "apiKey" -> text,
      "weatherCode" -> text.verifying("Invalid characters", vldSpec(_))) verifying
      ("Password mismatch - please type again", { t: (String, String, String, String) =>
        val (css, favQuote, apiKey, weatherCode) = t
        true
      })
  }

  import play.api.data.Forms._
  import play.api.data._

  // profile
  def edProfileForm(implicit request: Request[_]) = Form {
    mapping(
      "firstName" -> nonEmptyText
        .verifying("Obscenity filter", !Wikis.hasBadWords(_))
        .verifying("Invalid characters", vldSpec(_)),
      "lastName" -> text
        .verifying("Obscenity filter", !Wikis.hasBadWords(_))
        .verifying("Invalid characters", vldSpec(_)),
      "userType" -> nonEmptyText
        .verifying("Please select one", ut => Website.userTypes.contains(ut)),
      "yob" -> number(min = 1900, max = 2012),
      "about" -> text
        .verifying("Obscenity filter", !Wikis.hasBadWords(_))
        .verifying("Invalid characters", vldSpec(_)),
      "company" -> text
        .verifying("Obscenity filter", !Wikis.hasBadWords(_))
        .verifying("Invalid characters", vldSpec(_)),
      "address" -> text
        .verifying("Invalid characters", vldSpec(_)))(
        (f, l, t, y, c, about, a) =>
          User("kuku", f, l, y, "noemail", "nopwd", 'a', Set(t), Set(), (if (a != null && a.length > 0) Some(a) else None), Map("about"-> about))
          .copy(organization = (if (c != null && c.length > 0) Some(c) else None))
    )(
          (u: User) => Some(u.firstName, u.lastName, u.roles.head, u.yob, u.getPrefs("about", ""),
            u.organization.map(identity).getOrElse(""),
            u.addr.map(identity).getOrElse(""))) verifying
          ("Can't use last name for organizations!", { u: User =>
            (!(auth.get.isClub)) || u.lastName.length <= 0
          })
  }

  // profile
  val edprofileForm2 = Form {
    tuple(
      "trust" -> nonEmptyText.verifying("Please select one", ut => Profile.trusts.contains(ut)),
      "notify" -> nonEmptyText.verifying("Please select one", ut => Profile.notifiers.contains(ut)))
  }

  /** show profile **/
  def doeProfile = FAUR { implicit request =>
    Ok(views.html.user.doeProfile(edProfileForm.fill(request.au.get), request.au.get))
  }

  /** show children in profile **/
  def profile2(child: String) = FAUR { implicit request =>
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
  def profile2u(child: String) = FAUR { implicit request =>
    implicit val errCollector = new VErrors()
    edprofileForm2.bindFromRequest.fold(
      formWithErrors => {
        warn("FORM ERR " + formWithErrors)
        ROK.k badRequest{views.html.user.edChildren(formWithErrors, child, request.au.get)}
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

  def doeUpdPrefs = FAUR { implicit request =>
    val au = request.au.get

    prefsForm.bindFromRequest.fold(
    formWithErrors => ROK.k badRequest {views.html.user.doeProfilePreferences(formWithErrors, request.au.get)},
    {
      case (css, favQuote, apiKey, weatherCode) => {
        val u = Profile.updateUser(au, au.setPrefs(request.realm,
          Map("css" -> css, "favQuote" -> favQuote, "weatherCode" -> weatherCode)))

        Redirect(routes.ProfileUpd.doeProfilePreferences)
      }
    })
  }

    def doeProfilePreferences = RAction { implicit request =>
    forUser { au =>
      ROK.k noLayout {
        views.html.user.doeProfilePreferences(prefsForm.fill((
        au.getPrefs("css",dfltCss),
        au.getPrefs("favQuote",""),
        au.apiKey.mkString,
        au.getPrefs("weatherCode",""))),
        au)
      }
    }
  }

  def doeHelp = FAUR { implicit request =>
    Ok(views.html.user.doeProfileHelp())
  }

  def doeProfileUpdate = FAUR { implicit request =>
    edProfileForm.bindFromRequest.fold(
      formWithErrors =>
        ROK.k badRequest{views.html.user.doeProfile(formWithErrors, auth.get)},
      {
        case u: User =>
          forActiveUser { au =>
            val newu = au.copy(
              firstName=u.firstName,
              lastName=u.lastName,
              yob=u.yob,
              addr=u.addr
            ).setRoles(
              request.realm,
              (
                if(au.roles.mkString == u.roles.mkString) au.roles
                else (au.roles ++ u.roles)
              )
            ).setPrefs(
              request.realm,
              au.prefs ++ u.prefs
            )

            Profile.updateUser(au, newu)
            cleanAuth()
            Redirect(routes.ProfileUpd.doeProfile)
          }
      })
  }

  //////////////////// passwords

  val chgpassform = Form {
    tuple(
      "currpass" -> text,
      "newpass" -> text.verifying("Too short!", p => (p.length == 0 || p.length >= 4)),
      "repass" -> text,
      "token" -> text
    ) verifying
      ("Password mismatch - please type again", { t: (String, String, String, String) =>
        if (t._2.length > 0 && t._3.length > 0 && t._3 != t._2) false
        else true
      })
  }

  def doeProfilePass = FAUR { implicit request =>
    Ok(views.html.user.doeProfilePass(chgpassform.fill("", "", "", SecLink("chgpassform").token), request.au.get))
  }

  def doeProfilePass2 = FAUR { implicit request =>
    val au = request.au.get
    chgpassform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.user.doeProfilePass(formWithErrors, auth.get)),
      {
        case (o, n, _, t) =>
          (for (
            t <- SecLink.findAndDelete(t) orErr "security error" ;
            pwdCorrect <- {
              // sometimes the password plus ADMIN doesn't work...
              (if (Enc(o) == au.pwd || ("ADMIN" + au._id.toString == o)) Some(true) else None) orErr ("Password incorrect!")
              // the second form is hack to allow me to reset it
            }
          ) yield {
            Profile.updateUser(au, au.copy(pwd=Enc(n)))
            Emailer.withSession(request.realm) { implicit mailSession =>
              // todo use email template
              mailSession.send(au.emailDec, Config.SUPPORT, "Password was changed", "Your password was changed!")
            }
            Msg("Your password has successfully changed.")
          }) getOrElse {
            verror("ERR_CANT_UPDATE_USER_PASSWORD ")
            unauthorized("Oops - cannot update this user [ERR_CANT_UPDATE_USER_PASSWORD]... ")
          }
      })
  }

  def forgotForm(implicit request: Request[_]) = Form {
    tuple(
      "email" -> text.verifying("Wrong email format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "token" -> text,
      "g-recaptcha-response" -> text
    ) verifying
      ("reCAPTCHA failed!", { cr: (String, String, String) =>
        new Recaptcha(config).verify2(cr._3, clientIp)
      })
  }

  def doeForgotPass = RAction { implicit stok =>
    (ROK.k apply {
      views.html.user.doeProfilePassForgot1(forgotForm.fill("", SecLink("forgotpass").token, ""))
    })
        .withNewSession
        .discardingCookies(DiscardingCookie("error"))
  }

  def doeForgotPass2 = RAction { implicit stok =>
    forgotForm.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {
        views.html.user.doeProfilePassForgot1(formWithErrors)
      },
    {
      case (e, t, _) =>
        SecLink.findAndDelete(t).map { sl =>
          Users.findUserByEmailDec(e.trim).map { au =>
            Emailer.withSession(stok.realm) { implicit mailSession =>
              Audit.logdb("RESET_PWD_SENT", "request for " + e)
              Tasks.sendEmailReset(au)
            }
            Msg(MSG_EMAIL)
          } getOrElse {
            Audit.logdb("ERR_RESET_PWD", "email not found " + e)
            Msg(MSG_EMAIL)
          }
        } getOrElse {
          Audit.logdb("ERR_RESET_PWD", "token not found " + e)
          // todo ban the IP
          Msg(MSG_EMAIL)
        }
    })
  }

  val MSG_EMAIL= s"""
Please check your email and follow the instructions.
<p>
<small>
<br>If you don't see the email, please check your spam/junk folders.
</small>
"""

  /** step 2 - user clicked on email link to verify email */
  def doeForgotPass3(expiry:String, id:String) = RAction { implicit stok =>
    (for (
      date <- (
        try {
          Option(DateTime.parse(expiry))
        } catch {
          case _: Throwable => (
            try {
              Option(DateTime.parse(expiry.replaceAll(" ", "+").dec))
            } catch {
              case _: Throwable => None
            })
        }) orErr ("token faked or expired");
      _ <- date.isAfterNow orCorr cExpired;
      _ <- stok.verifySecLink orCorr SecLink.EXPIRED;
      p <- Users.findUserById(id) orCorr cNoAuth
    ) yield {
      stok.req.flash.get(SecLink.HEADER).flatMap(SecLink.find).foreach(_.done)
      ROK.r apply {
        views.html.user.doeProfilePassForgot2(chgpassform.fill("", "", "", SecLink("chgpassform").token), id)
      }}) getOrElse {
      Audit.logdb("ERR_USER_RESET_PWD", stok.errCollector.mkString)
      Msg("Link expired!")
    }
  }

  /** submitted form with new password */
  def doeForgotPass4(id:String) = RAction { implicit stok =>
      chgpassform.bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {
        views.html.user.doeProfilePassForgot2(formWithErrors, id)
      },
      {
        case (_, n, _, t) =>
          (for (
            t <- SecLink.findAndDelete(t);
            au <- Users.findUserById(id)
          ) yield {
              Profile.updateUser(au, au.copy(pwd=Enc(n)))
            Audit.logdb("RESET_PWD_DONE", "request for " + au.emailDec + " - " + au.userName)
            Emailer.withSession(stok.realm) { implicit mailSession =>
              // todo use email template
//              val html = Emailer.text("passwordchanged").format(au.ename);
              val html = Emailer.expand("passwordchanged", List(
                "ename" -> au.ename
              ))
              mailSession.send(au.emailDec, mailSession.SUPPORT,
                "Password was changed",
                html)
            }
              Msg("Your password was successfully changed.")
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

          cleanAuth()
          if(regid.length > 1) Redirect(routes.Club.doeClubUserReg(regid))
          else Redirect(routes.ProfileUpd.doeContact("-"))
        }
      })
  }
}




