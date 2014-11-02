/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import scala.Array.canBuildFrom
import org.joda.time.DateTime
import com.mongodb.DBObject
import admin._
import model._
import db.RazMongo
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{AnyContent, Action, Request}
import razie.{cout, Logging, clog}
import db.ROne
import db.RMany
import model.WikiAudit
import model.WikiEntryOld
import model.WikiLink
import model.CMDWID

object Wikil extends WikieBase {

  import Visibility._

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  def link(fromCat: String, fromName: String, toCat: String, toName: String) = {
    if ("User" == fromCat) linkUser(WID(toCat, toName))
    else
      TODO
  }

  /** user unlikes page */
  def unlinkUser(wid: WID, really: String = "n") = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      uwid <- wid.uwid orErr ("can't find uwid");
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission("uProfile")
    ) yield {
      if (wid.cat == "Club" && really != "y") {
        Msg3(really + "Are you certain you want to leave club? You will not be able to follow calendars, register or see any of the forums etc...<p>Choose Leave only if certain.",
          Some(Wiki.w(wid)),
          Some("Leave" -> s"/wikie/unlinkuser/${wid.wpath}?really=y"))
      } else {
        // if he was already, just say it
        au.pages(wid.cat).find(_.uwid == uwid).map { wl =>
          // TODO remove the comments page as well if any
          //        wl.wlink.page.map { wlp =>
          //          Redirect(routes.Wiki.wikieEdit(WID("WikiLink", wl.wname)))
          //        }
          wl.delete
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

  /** user 'likes' page - link the current user to the page */
  def linkUser(wid: WID, withComment: Boolean = false) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      hasuwid <- wid.uwid.isDefined orErr ("can't find uwid");
      uwid <- wid.uwid;
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      // even new users that didn't verify their email can register for club
      //      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr Profile.cNoConsent;
      r1 <- (au.hasPerm(Perm.uProfile) || "Club" == wid.cat) orCorr cNoPermission("uProfile")
    ) yield {
      def content = """[[User:%s | You]] -> [[%s:%s]]""".format(au.id, wid.cat, wid.name)

      // if he was already, just say it
      au.pages(wid.cat).find(_.uwid.id == uwid.id).map { wl =>
        wl.wlink.page.map { wlp =>
          Redirect(routes.Wikie.wikieEdit(WID("WikiLink", wl.wname)))
        } getOrElse {
          Msg2("Already added!", Some("/"))
        }
      } getOrElse {
        // need to link now
        Ok(views.html.wiki.wikiLink(WID("User", au.id), wid,
          linkForm.fill(LinkWiki("Enjoy", model.UW.EMAIL_EACH, Wikis.MD, content)), withComment, auth))
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
        val v2 = (10 + math.random * 11).toInt
        Ok(views.html.wiki.wikiFollowerLink1(wid, v2, followerLinkForm.fill(FollowerLinkWiki("", "", 0, v2, ""))))
      }) getOrElse
        noPerm(wid, "LINKUSER")
    }
  }

  /** send email to confirm following */
  def linkFollower2(wid: WID) = Action { implicit request =>

    implicit val errCollector = new VErrors()

    followerLinkForm.bindFromRequest.fold(
    formWithErrors => BadRequest(views.html.wiki.wikiFollowerLink1(wid, 21, formWithErrors)),
    {
      case we @ FollowerLinkWiki(email1, email2, v1, v2, comment) =>
        (for (
          exists <- wid.page.isDefined orErr ("Cannot link to non-existent page: " + wid.name);
          uwid <- wid.page map (_.uwid);
          goodMath <- (v1 == v2 && v2 > 1 || v1 == 21) orErr {
            Audit.logdb("BAD_MATH", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
            ("Bad math")
          };
          r1 <- canSee(wid, None, wid.page)
        ) yield {
          val es = email1.enc
          if (model.Users.findFollowerLinksTo(uwid).toList.flatMap(_.follower).exists(_.email == es)) {
            Msg2("You already subscribed with that email... Enjoy!", Some(wid.urlRelative))
          } else {
            Emailer.laterSession { implicit mailSession =>
              Emailer.sendEmailFollowerLink(email1, wid, comment)
              Emailer.tellRaz("Subscribed", email1 + " ip=" + request.headers.get("X-Forwarded-For"), wid.ahref, comment)
            }
            Msg2("You got an email with a link, to activate your subscription. Enjoy!", Some(wid.urlRelative))
          }
        }) getOrElse {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          unauthorized("Oops - cannot create this link... ")
        }
    })
  }

  /** clicked confirm in the email -> so follow */
  def linkFollower3(expiry: String, email: String, comment: String, wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

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
          this dbop db.RCreate(newf)
          newf
        }
        this dbop db.RCreate(model.FollowerWiki(f._id, comment.decUrl, uwid))

        Emailer.withSession { implicit mailSession =>
          Emailer.tellRaz("Subscription confirmed", email.dec, wid.ahref, comment.decUrl)
        }

        Msg2("Ok - you are subscribed to %s via email!".format(wid.page.map(_.label).getOrElse(wid.name)), Some(wid.urlRelative))
      }
    }) getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def unlinkFollower4(expiry: String, email: String, wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

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
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def moderatorOf(wid: WID) = Wikis.find(wid).flatMap(_.contentTags.get("moderator"))

  def linked(fromCat: String, fromName: String, toCat: String, toName: String, withComment: Boolean) = {
    if ("User" == fromCat) linkedUser(fromName, WID(toCat, toName), withComment)
    else
      TODO
  }

  // link a user for moderated club was approved
  private def createLinkedUser(au: User, wid:WID, uwid: UWID, withComment: Boolean, how: String, mark: String, comment: String)(implicit request: Request[_], txn: db.Txn) = {
    this dbop model.UserWiki(au._id, uwid, how).create
    if (withComment) {
      val wl = model.WikiLink(UWID("User", au._id), uwid, how)
      wl.create
      model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
    }

    if (wid.cat == "Club")
      Club.linkUser(au, wid.name, how)

    cleanAuth(Some(au))
  }

  // link a user for moderated club was approved by moderator
  def linkAccept (expiry: String, userId: String, club: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    def hows = {
      Wikis.category("Club").flatMap(_.contentTags.get("roles:" + "User")) match {
        case Some(s) => s.split(",").toList
        case None => Wikis.pageNames("Link").toList
      }
    }

    import model.Sec._
    val wid = WID("Club", club)

    (for (
    // play 2.0 workaround - remove in play 2.1
      date <- (try { Option(DateTime.parse(expiry.dec)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
      notExpired <- date.isAfterNow orCorr cExpired;
      user <- Users.findUserById(userId);
      isA <- checkActive(user);
      admin <- auth orCorr cNoAuth;
      modUname <- moderatorOf(WID("Club", club));
      isMod <- (admin.hasPerm(Perm.adminDb) || admin.userName == modUname) orErr ("You do not have permission!!!");
      ok <- hows.contains(how) orErr ("invalid role");
      uwid <- wid.uwid orErr ("can't find uwid");
      again <- (!user.wikis.exists(_.uwid == uwid)) orErr ("Aldready associated to club");
      c <- Club(club)
    ) yield {
      db.tx("linkUser.toWiki") { implicit txn =>
        ilinkAccept(user, c, uwid, how)
      }
      Msg2("OK, added!", Some("/"))
    }) getOrElse {
      error("ERR_CANT_LINK_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  // link a user for moderated club was approved
  private def ilinkAccept(user: User, club: Club, pageUwid:UWID, how: String)(implicit request: Request[_], txn: db.Txn) {
    // only if there is a club/user entry for that Club page
    val rk = model.RacerKidz.myself(user._id)
    model.RacerKidAssoc(club.userId, rk._id, model.RK.ASSOC_LINK, user.role, club.userId).create

    //    createLinkedUser(user, WID("Club", club.userName), UWID("Club", club._id), false, how, "", "")
    createLinkedUser(user, WID("Club", club.userName), pageUwid, false, how, "", "")

    if (!user.quota.updates.exists(_ > 10))
      user.quota.reset(50)

    Emailer.withSession { implicit mailSession =>
      Emailer.sendEmailLinkOk(user, club.userName)
      Emailer.tellRaz("User joined club", "Club: " + club.userName, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.email.dec}")
      club.props.filter(_._1.startsWith("link.notify.")).foreach { t =>
        Emailer.tell(t._2, "User joined club", "Club: " + club.userName, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.email.dec}")
      }
    }
  }

  def linkDeny(userId: String, club: String, how: String) = Action { implicit request =>
    Emailer.withSession { implicit mailSession =>
      Emailer.sendEmailLinkDenied(Users.findUserById(userId).get, club)
    }
    Msg2("OK, denied!", Some("/"))
  }

  /** a user linked to a WID */
  def linkedUser(userId: String, wid: WID, withComment: Boolean) = Action { implicit request =>

    razie.clog << s"METHOD linkedUser($userId, $wid, $withComment)"

    def hows = {
      Wikis.category(wid.cat).flatMap(_.contentTags.get("roles:" + "User")) match {
        case Some(s) => s.split(",").toList
        case None => Wikis.pageNames("Link").toList
      }
    }

    implicit val errCollector = new VErrors()

    linkForm.bindFromRequest.fold(
    formWithErrors => BadRequest(views.html.wiki.wikiLink(WID("User", auth.get.id), wid, formWithErrors, withComment, auth)),
    {
      case we @ LinkWiki(how, notif, mark, comment) =>
        (for (
          au <- activeUser;
          hasuwid <- wid.uwid.isDefined orErr "cannot find a uwid";
          uwid <- wid.uwid;
          isMe <- (au.id equals userId) orErr {
            Audit.security("Another user tried to link...", userId, au.id)
            "invalid user"
          };
          page <- wid.page orErr s"Page $wid not found";
          ok <- hows.contains(how) orErr "invalid role";
          xxx <- Some("")
        ) yield {
          db.tx("wiki.linkeduser") { implicit txn =>
            val mod = moderatorOf(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })

            if ("Club" == wid.cat && mod.isDefined) {
              if (Club(wid.name).exists(_.props.get("link.auto").mkString == "yes")) {
                ilinkAccept(au, Club(wid.name).get, uwid, how)
                Msg2("OK, added!", Some("/"))
              } else {
                Emailer.withSession { implicit mailSession =>
                  Emailer.sendEmailLink(mod.get, au, wid.name, how)
                }
                Msg2(s"An email has been sent to the moderator of <strong>${page.label}</strong>, you will receive an email when they're done!",
                  Some("/"))
              }
            } else {
              model.UserWiki(au._id, uwid, how).create
              if (withComment) {
                val wl = model.WikiLink(UWID("User", au._id), uwid, how)
                wl.create
                model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
              }
              cleanAuth()
              Msg2("OK, added!", Some("/"))
            }
          }
        }) getOrElse {
          error("ERR_CANT_UPDATE_USER " + session.get("email"))
          unauthorized("Oops - cannot create this link... ")
        }
    })
  }

}

