/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.google.inject._
import mod.snow._
import model.{FollowerWiki, User, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, text}
import play.api.mvc.{Action, Request}
import razie.audit.Audit
import razie.db._
import razie.diesel.dom.WikiDomain
import razie.wiki.Sec.EncryptedS
import razie.wiki.Services
import razie.wiki.model._

/** controller for link ops */
@Singleton
class Wikil @Inject() (config:Configuration) extends WikieBase {

  // import from companion object
  import Wikil.{FollowerLinkWiki, hows, ilinkAccept}

  def followerLinkForm(implicit request: Request[_]) = Form {
    mapping(
      "email1" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "email2" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "comment" -> play.api.data.Forms.text,
      "g-recaptcha-response" -> text
    )(FollowerLinkWiki.apply)(FollowerLinkWiki.unapply) verifying
      ("CAPTCHA failed!", { cr: FollowerLinkWiki =>
        new Recaptcha(config).verify2(cr.g_recaptcha_response, clientIp)
      }) verifying
      ("Email mismatch - please type again", { reg: FollowerLinkWiki =>
        if (reg.email1.length > 0 && reg.email2.length > 0 && reg.email1 != reg.email2) false
        else true
      })
  }

  def link(fromCat: String, fromName: String, toCat: String, toName: String) = {
    if ("User" == fromCat) linkUser(WID(toCat, toName))
    else
      TODO
  }

  /** user unlikes page */
  def unlinkUser(wid: WID, really: String = "n", uid:String = "") = FAUR { implicit stok =>
    (for (
      au <- {
        // if uid provided, then it's done by admin
        if(uid.length > 0) Users.findUserById(uid).filter(x=> stok.au.exists(_.isAdmin))
        else stok.au
      };
      uwid <- wid.uwid orErr ("can't find uwid");
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission("uProfile - probably need to validate email")
    ) yield {
      if (WikiDomain(wid.getRealm).isA("Club", wid.cat) && really != "y") {
        def hasRegs =
          if(Regs.findClubUser(wid, au._id).nonEmpty)
            """<span style="color:red">You have registrations for this club - they will not be deleted !</span>"""
        else "No registrations for this club."

        Msg3(
          s"""Are you certain you want to leave club?
            |You will not be able to follow calendars, register or see any of the forums etc...
            |<p>$hasRegs
            |<p>Choose Leave only if certain.""".stripMargin,
          Some(Wiki.w(wid)),
          Some("Leave" -> routes.Wikil.unlinkUser(wid, "y", uid).toString))
      } else {
        au.pages(wid.getRealm, wid.cat).find(_.uwid == uwid).toList.headOption.map { wl =>
          // two links: UserWiki and RacerKidAssoc
          wl.delete
          val rk = RacerKidz.myself(au._id)
          rk.rka.filter(_.assoc == mod.snow.RK.ASSOC_LINK).filter (rka=>
            Club.findForUserId(rka.from).exists(_.name == wid.name)
          ).toList.map(_.delete)

          if (WikiDomain(wid.getRealm).isA("Club", wid.cat)) {
            Club.userLeft(au, wid.name)(razie.db.tx.auto)
          }

          if (WikiDomain(wid.getRealm).isA("Club", wid.cat) && Regs.findClubUser(wid, au._id).nonEmpty) {
            // notify club admin
            Wikil.moderatorOf(wid).map {mod=>
              Emailer.withSession(stok.realm) { implicit mailSession =>
                Emailer.tell(mod, "User left the page", " Page: "+wid.wpath, " User:"+au.userName)
              }
            }
          }

          cleanAuth()
          Msg2("OK, removed link!", Some("/"))
        } getOrElse {
          // need to link now
          Msg2("OOPS - you don't like this, nothing to unlike!", Some("/"))
        }
      }
    }) getOrElse
      noPerm(wid, "UNLINKUSER")
  }

  def unlinkAll(wid: WID, really: String = "n") = FAUR("unlink.user") { implicit request =>
    val au = request.au.get
    for (
      uwid <- wid.uwid orErr ("can't find uwid");
      page <- wid.page orErr ("Cannot link to " + wid.name);
      owner <- page.owner orErr ("Cannot link to " + wid.name);
      isClub <- owner.asInstanceOf[User].isClub orErr ("possible only for a club");
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission("uProfile");
      c <- Club.findForName(owner.userName)
    ) yield {
      if (really != "y") {
        Msg3(really + "Are you certain you want to unlink all members?<p>Choose Unlink only if certain.",
          Some(Wiki.w(wid)),
          Some("Unlink" -> s"/wikie/unlinkAll/${wid.wpath}?really=y"))
      } else {
        val followers = Users.findUserLinksTo(page.uwid).toList
        var count = 0
        followers.filter(_.user.exists(c.isMember)).foreach { ul =>
          ul.delete
          count += 1
          // TODO remove the comments page as well if any
        }
        cleanAuth()
        Msg2(s"UNlinked $count users", Some(page.wid.urlRelative))
      }
    }
  }

  /** user 'likes' page - link the current user to the page */
  def linkAll(wid: WID, really: String = "n") = FAUR { implicit request =>
    val au = request.au.get
    (for (
      hasuwid <- wid.uwid.isDefined orErr ("can't find uwid");
      uwid <- wid.uwid;
      page <- wid.page orErr ("Cannot link to " + wid.name);
      owner <- page.owner orErr ("Cannot link to " + wid.name);
      isClub <- owner.asInstanceOf[User].isClub orErr ("possible only for a club");
      r1 <- (au.hasPerm(Perm.uProfile) || "Club" == wid.cat) orCorr cNoPermission("uProfile");
      c <- Club.findForName(owner.userName)
    ) yield {
      if (really != "y") {
        Msg3(really + "Are you certain you want to link all members?<p>Choose LINK only if certain.",
          Some(Wiki.w(wid)),
          Some("LINK" -> s"/wikie/linkAll/${wid.wpath}?really=y"))
      } else {
        def content = """[[User:%s | You]] -> [[%s:%s]]""".format(au.id, wid.cat, wid.name)
        var count = 0
        for (m <- c.userLinks if m.role != "Fan") {
          // if he was already, just say it
          if (m.user.isDefined && !m.user.get.pages(wid.getRealm, wid.cat).exists(_.uwid.id == uwid.id)) {
            model.UserWiki(m.user.get.asInstanceOf[User]._id, uwid, "Contributor").create
            count += 1
          }
        }
        cleanAuth()
        Msg2(s"Linked $count users", Some(page.wid.urlRelative))
      }
    }) getOrElse
      noPerm(wid, "LINKUSER")
  }

  /** user 'likes' page - link the current user to the page */
  def linkUser(wid: WID, withComment: Boolean = false) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      hasuwid <- wid.uwid.isDefined orErr ("can't find uwid");
      uwid <- wid.uwid;
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      // even new users that didn't verify their email can register for club
      //      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr Profile.cNoConsent;
      r1 <- (au.hasPerm(Perm.uProfile) || WikiDomain(wid.getRealm).isA("Club", wid.cat)) orCorr cNotVerified
    ) yield {
      def content = """[[User:%s | You]] -> [[%s:%s]]""".format(au.id, wid.cat, wid.name)

      // if he was already, just say it
      au.pages(wid.getRealm, wid.cat).find(_.uwid.id == uwid.id).map { wl =>
        wl.wlink.page.map { wlp =>
          Redirect(routes.Wikie.wikieEdit(WID("WikiLink", wl.wname)))
        } getOrElse {
          Msg2("Already added!", Some("/"))
        }
      } getOrElse {
        ROK.r apply {implicit stok =>
          views.html.wiki.wikiLink(
            WID("User", au.id),
            wid,
            linkForm.fill(Wikil.LinkWiki("Enjoy", model.UW.EMAIL_EACH, Wikis.MD, content)), withComment)
        }
      }
    }) getOrElse
      noPerm(wid, "LINKUSER")
  }

  /** NEW user 'likes' page - link the current user to the page */
  def linkFollower1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    if (auth.isDefined) Redirect(routes.Wikil.linkUser(wid, false))
    else {
      (for (
        exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
        r1 <- canSee(wid, None, wid.page)
      ) yield {
          ROK.r apply {implicit stok =>
            views.html.wiki.wikiFollowerLink1(wid, followerLinkForm.fill(FollowerLinkWiki("", "", "")))
          }
      }) getOrElse
        noPerm(wid, "LINKUSER")
    }
  }

  /** send email to confirm following */
  def linkFollower2(wid: WID) = Action { implicit request =>

    implicit val errCollector = new VErrors()

    followerLinkForm.bindFromRequest.fold(
    formWithErrors => BadRequest(
      ROK.r justLayout { implicit stok =>
        views.html.wiki.wikiFollowerLink1(wid, formWithErrors)
      }
    ),
    {
      case we @ FollowerLinkWiki(email1, email2, comment, _) =>
        (for (
          exists <- wid.page.isDefined orErr ("Cannot link to non-existent page: " + wid.name);
          uwid <- wid.page map (_.uwid);
          r1 <- canSee(wid, None, wid.page)
        ) yield {
          val es = email1.enc
          if (model.Users.findFollowerLinksTo(uwid).toList.flatMap(_.follower).exists(_.email == es)) {
            Msg2("You already subscribed with that email... Enjoy!", Some(wid.urlRelative))
          } else {
            Emailer.withSession(wid.getRealm) { implicit mailSession =>
              Emailer.sendEmailFollowerLink(email1, wid, comment)
              Emailer.tellAdmin("Subscribed", email1 + " ip=" + request.headers.get("X-Forwarded-For"), wid.ahref, comment)
            }
            Msg2("You got an email with a link, to activate your subscription. Enjoy!", Some(wid.urlRelative))
          }
        }) getOrElse {
          verror("ERR_CANT_UPDATE_USER.linkFollower2" + wid + " : " + request.session.get("email"))
          unauthorized("Oops - cannot create this link... ")
        }
    })
  }

  /** clicked confirm in the email -> so follow */
  def linkFollower3(expiry: String, email: String, comment: String, wid: WID) = RAction { implicit request =>
    (for (
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      uwid <- wid.page.map(_.uwid);
      r1 <- canSee(wid, None, wid.page)
    ) yield {

      // TODO should I notify or ask the moderator?
      //            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })

      if (model.Users.findFollowerLinksTo(uwid).toList.flatMap(_.follower).exists(_.email == email)) {
        Msg2("You already subscribed with that email... Enjoy!", Some(wid.urlRelative))
      } else {
        val f = model.Users.findFollowerByEmail(email).getOrElse {
          val newf = model.Follower(email, "")
          RCreate(newf)
          newf
        }
        RCreate(model.FollowerWiki(f._id, comment.decUrl, uwid))
cleanAuth(auth)
        Emailer.withSession(wid.getRealm) { implicit mailSession =>
          Emailer.tellAdmin("Subscription confirmed", email.dec, wid.ahref, comment.decUrl)
        }

        Msg2("Ok - you are subscribed to %s via email!".format(wid.page.map(_.label).getOrElse(wid.name)), Some(wid.urlRelative))
      }
    }) getOrElse {
      verror("ERR_CANT_UPDATE_USER.linkFollower3 " + wid + " : " + request.session.get("email").mkString)
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def unlinkFollower4(expiry: String, email: String, wid: WID) = RAction { implicit request =>
    (for (
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      r1 <- canSee(wid, None, wid.page)
    ) yield {
      //            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })
      //
      //            val f = model.Follower(email1, "??")
      //            this dbop f.create
      //            this dbop model.FollowerWiki(f._id, wid).create
      //
      // TODO remove follower, possibly notify owner of topic or moderator
      Msg2("Sorry - please submit a support request...", Some(wid.urlRelative))
    }) getOrElse {
      verror(s"""ERR_CANT_UPDATE_USER.unlinkFollower4 : $wid : ${request.session.get("email")}""")
      unauthorized("Oops - cannot remove this subscription... ")
    }
  }

  def unlinkExtFollower(id: String) = RAction { implicit request =>
    (for (
      _ <- ObjectId.isValid(id) orErr "id not valid";
      fw <- ROne[FollowerWiki](new ObjectId(id)) orErr "no fwiki";
      _ <- request.au.exists(_.isAdmin) orErr "not admin"
    ) yield {
      fw.delete(txn)
      //            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })
      //
      //            val f = model.Follower(email1, "??")
      //            this dbop f.create
      //            this dbop model.FollowerWiki(f._id, wid).create
      //
      // TODO remove follower, possibly notify owner of topic or moderator
      Msg2("Ok - done")
    }) getOrElse {
      verror(s"""ERR_CANT_UPDATE_USER.unlinkExtFollower : $id """)
      unauthorized("Oops - cannot remove this subscription... ")
    }
  }


  def linked(from:WID, to:WID, withComment: Boolean) = {
    if ("User" == from.cat) linkedUser(from.name, to, withComment)
    else
      TODO
  }

  // link a user for moderated club was approved by moderator
  def linkAccept (expiry: String, userId: String, club: WID, how: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    import razie.wiki.Sec._
    val wid = club

    (for (
    // play 2.0 workaround - remove in play 2.1
      date <- (try { Option(DateTime.parse(expiry.dec)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
      notExpired <- date.isAfterNow orCorr cExpired;
      user <- Users.findUserById(userId);
      isA <- checkActive(user);
      admin <- auth orCorr cNoAuth;
      c <- Club(club.name);
      modEmail <- Wikil.moderatorOf(wid);
      isMod <- c.isClubAdmin(admin) orErr ("You do not have permission!!!");
      ok <- hows(club, "User").contains(how) orErr ("invalid role");
      uwid <- wid.uwid orErr ("can't find uwid");
      again <- (!user.wikis.exists(_.uwid == uwid)) orErr ("Aldready associated to club")
    ) yield {
      razie.db.tx("linkUser.toWiki", admin.userName) { implicit txn =>
        ilinkAccept(user, c, uwid, how, true)
      }
      Msg2("OK, added!", Some("/"))
    }) getOrElse {
      error("ERR_CANT_LINK_USER " + request.session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def linkDeny(userId: String, club: WID, how: String) = Action { implicit request =>
    Emailer.withSession(club.getRealm) { implicit mailSession =>
      Emailer.sendEmailLinkDenied(Users.findUserById(userId).get, club)
    }
    Msg2("OK, denied!", Some("/"))
  }

  /** a user linked to a WID - submitted form */
  def linkedUser(userId: String, wid: WID, withComment: Boolean) = FAU {
    implicit au => implicit errCollector => implicit request =>

    clog << s"METHOD linkedUser($userId, $wid, $withComment)"

    implicit val errCollector = new VErrors()

    linkForm.bindFromRequest.fold(
    formWithErrors => BadRequest(
      ROK.s justLayout { implicit stok =>
        views.html.wiki.wikiLink(WID("User", au.id), wid, formWithErrors, withComment)
      }),
    {
      case we @ Wikil.LinkWiki(how, notif, mark, comment) =>
        (for (
          hasuwid <- wid.uwid.isDefined orErr "cannot find a uwid";
          uwid <- wid.uwid;
          isMe <- (au.id equals userId) orErr {
            Audit.security("Another user tried to link...", userId, au.id)
            "invalid user"
          };
          page <- wid.page orErr s"Page $wid not found";
          ok <- hows(wid, "User").contains(how) orErr "invalid role";
          xxx <- Some("")
        ) yield {
          razie.db.tx("wiki.linkeduser", au.userName) { implicit txn =>
            val mod = Wikil.moderatorOf(wid).flatMap(mid => { Users.findUserByEmailDec((mid)) })

            if (WikiDomain(wid.getRealm).isA("Club", wid.cat)) {
              if (mod.isEmpty ||
                Club(wid).exists(_.props.get("link.auto").mkString == "yes")) {
                ilinkAccept(au, Club(wid).get, uwid, how, true)
                cleanAuth(Some(au))
                Msg2("OK, added!", Some("/"))
              } else {
                Emailer.withSession(wid.getRealm) { implicit mailSession =>
                  Emailer.sendEmailLink(mod.get, au, wid, how)
                }
                Msg2(s"An email has been sent to the moderator of <strong>${page.label}</strong>, you will receive an email when they're done!",
                  Some("/"))
              }
            } else {
              model.UserWiki(au._id, uwid, how).create
              if (withComment) {
                val wl = WikiLink(UWID("User", au._id), uwid, how)
                wl.create
                WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
              }
              cleanAuth(Some(au))
              Msg2("OK, added!", Some("/"))
            }
          }
        }) getOrElse {
          verror(s"""ERR_CANT_UPDATE_USER.linkedUser $userId : $wid : ${request.session.get("email")}""")
          unauthorized("Oops - cannot create this link... ")
        }
    })
  }
}

/** utilities from refactoring, not the actual controller - that one is below */
object Wikil extends WikieBase {
  def moderatorOf(wid: WID) = wid.page.flatMap(_.contentProps.get("moderator"))

  /** a user linked to a WID - submitted form */
  def wikiFollow(uname:String, wpath: String, how:String) = {
    //todo auth
    clog << s"METHOD wiki.follow($how, $wpath, $uname)"

    implicit val errCollector = new VErrors()

    (for (
      au <- Users.findUserByUsername(uname) orErr "user not found: "+uname;
      wid <- WID.fromPath(wpath) orErr "wpath not found: "+wpath;
      uwid <- wid.uwid orErr "wpath does not exist: "+wpath;
      page <- wid.page orErr s"Page $wid not found";
      ok <- hows(wid, "User").contains(how) orErr "invalid role";
      xxx <- Some("")
    ) yield {
      razie.db.tx("wiki.follow", au.userName) { implicit txn =>
        val mod = moderatorOf(wid).flatMap(mid => { Users.findUserByEmailDec((mid)) })

        if (WikiDomain(wid.getRealm).isA("Club", wid.cat)) {
          if (mod.isEmpty ||
            Club(wid).exists(_.props.get("link.auto").mkString == "yes")) {
            ilinkAccept(au, Club(wid).get, uwid, how, false) // no quota
            "OK, added!"
          } else {
            Emailer.withSession(wid.getRealm) { implicit mailSession =>
              Emailer.sendEmailLink(mod.get, au, wid, how)
            }
            "Email sent to mod"
          }
        } else {
          model.UserWiki(au._id, uwid, how).create
          Services ! WikiEvent("AUTH_CLEAN", "User", au._id.toString)
          "OK, added!"
        }
      }
    }) getOrElse ("ERROR: "+errCollector.mkString)
  }

  // roles
  def hows(to:WID, from:String) = {
    to.page.flatMap(_.contentProps.get("roles:"+from)) match {
      case Some(s) => {
        s.split(",").toList
      }
      case None => {
        WikiDomain(to.getRealm).roles(to.cat, from)
      }
    }
  }

  // link a user for moderated club was approved
  private def ilinkAccept(user: User, club: Club, pageUwid:UWID, how: String, giveQuota:Boolean)(implicit txn: Txn) {
    // only if there is a club/user entry for that Club page
    val rk = RacerKidz.myself(user._id)
    if(!rk.rka.exists(_.from == club.userId))
      RacerKidAssoc(club.userId, rk._id, mod.snow.RK.ASSOC_LINK, how, club.userId).create
    if(!rk.rkwa.exists(_.uwid.id == club.uwid.id))
      RacerKidz.rkwa(rk._id, club.uwid, club.curYear, how, mod.snow.RK.ASSOC_LINK)

    createLinkedUser(user, club.wid, pageUwid, false, how, "", "")

    if (giveQuota && !user.quota.updates.exists(_ > 10))
      user.quota.reset(50)

    Emailer.withSession(club.wid.getRealm) { implicit mailSession =>
      Emailer.sendEmailLinkOk(user, club.userName, club.msgWelcome)
      Emailer.tellAdmin("User joined club", "Club: " + club.wid.wpath, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.emailDec}")
      (Wikil.moderatorOf(club.wid).toList :::
        club.props.filter(_._1.startsWith("link.notify.")).toList.map(_._2)
        ).distinct.foreach { email =>
        Emailer.tell(email, "User connected to page", "Page: " + club.wid.wpath, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.emailDec}")
      }
    }
  }

  // link a user for moderated club was approved
  private def createLinkedUser(au: User, wid:WID, uwid: UWID, withComment: Boolean, how: String, mark: String, comment: String)(implicit txn: Txn) = {
    model.UserWiki(au._id, uwid, how).create
    if (withComment) {
      val wl = WikiLink(UWID("User", au._id), uwid, how)
      wl.create
      WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
    }

    if (WikiDomain(wid.getRealm).isA("Club", wid.cat))
      Club.linkedUser(au, wid, how)

    Services ! WikiEvent("AUTH_CLEAN", "User", au._id.toString)
  }

  case class FollowerLinkWiki(email1: String, email2: String, comment: String, g_recaptcha_response:String="")
}


