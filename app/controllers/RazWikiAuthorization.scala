package controllers

import scala.Array.canBuildFrom
import org.joda.time.DateTime
import com.mongodb.DBObject
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin.Audit
import admin.Config
import admin.Corr
import admin.IgnoreErrors
import admin.MailSession
import admin.Notif
import admin.SendEmail
import admin.VErrors
import model.Enc
import model.Perm
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import model.Stage
import model.User
import model.UserType
import model.UserWiki
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import razie.cout
import db.ROne
import model.WikiCount
import model.WikiIndex
import model.Wikis
import model.CMDWID
import model.WikiAudit
import model.WikiEntry
import model.WikiEntryOld
import model.WID
import model.WikiLink
import model.WikiDomain
import model.WikiWrapper
import model.WikiXpSolver
import model.WikiUser

/** wiki controller base stuff - file too large */
object RazWikiAuthorization extends RazController with Logging with WikiAuthorization {
  import Visibility._

  implicit def toU (wu:WikiUser) : User = wu.asInstanceOf[User]

  def isInSameClub(member: WikiUser, owner: WikiUser) = { //}(implicit errCollector: VError = IgnoreErrors) = {
    // all clubs where member
    val m1 = member.asInstanceOf[User].wikis.filter(x => x.uwid.cat == "Club" && x.role != "Fan").toList

    (
      // owner is same as member
      (owner.roles.contains(UserType.Organization) && (member.userName == owner.userName)) ||
      // owner is the club 
      (owner.roles.contains(UserType.Organization) && m1.exists(_.uwid.nameOrId == owner.userName)) ||
      // owner is someone else => club lists intersect?
      (!owner.roles.contains(UserType.Organization) && {
        val m2 = owner.wikis.filter(x => x.uwid.cat == "Club" && x.role != "Fan").toList
        m1.exists(x1 => m2.exists(_.uwid.id == x1.uwid.id))
      }))
  }

  /** if user is admin of club where owner member */
  def isClubAdmin(admin: WikiUser, owner: WikiUser) = { //}(implicit errCollector: VError = IgnoreErrors) = {
    // all clubs where member
    val clubs = owner.wikis.filter(x => x.uwid.cat == "Club" && x.role != "Fan").toList

    (
      // owner is same as member
      (admin.isClub && (admin.userName == owner.userName)) ||
      // admin is the club
      (admin.isClub && clubs.exists(_.uwid.nameOrId == admin.userName)) ||
      // admin is god
      (!admin.isClub && admin.hasPerm(Perm.adminDb)) ||
      // admin is club admin
      (!admin.isClub && { 
        val aemail = admin.email.dec
        clubs.exists(x1 => Users.findUserByUsername(x1.uwid.nameOrId).exists(
          u => u.prefs.get("regAdmin").exists(_ == aemail) ||
          Club(u).props.filter(_._1 startsWith "admin").exists(_._2 == aemail)
          )) 
        }) // TODO this is expensive - at least optimize as a Mongo query?
      )
  }

  /** can user see a topic with the given properties? */
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry]=None)(implicit errCollector: VErrors = IgnoreErrors): Boolean = {
    // TODO optimize
    def uname(id: Option[String]) = id.flatMap(Users.findUserById(_)).map(_.userName).getOrElse(id.getOrElse(""))

    (!props.get(visibility).isDefined) || u.exists(_.hasPerm(Perm.adminDb)) ||
      (props(visibility) == Visibility.PUBLIC) || // if changing while edit, it will have a value even when public
      (u.isDefined orCorr cNoAuth).exists(_ == true) && // anything other than public needs logged in
      (
        props.get("owner") == Some(u.get.id) || // can see anything I am owner of - no need to check Visibility.PRIVATE
        (
          props(visibility).startsWith(Visibility.CLUB) &&
            props.get("owner").flatMap(Users.findUserById(_)).exists(owner =>
              // hoping it's more likely members read blogs than register...
              props(visibility) == Visibility.CLUB && isInSameClub(u.get, owner) || 
                props(visibility) == Visibility.CLUB_ADMIN && isClubAdmin(u.get, owner) ||
                // maybe the club created the parent topic (like forum/blog etc)?
                props(visibility) == Visibility.CLUB &&
                  props.get("parentOwner").flatMap(Users.findUserById(_)).exists(parentOwner => isInSameClub(u.get, parentOwner)) ||
                props(visibility) == Visibility.CLUB &&
                  we.flatMap(_.wid.findParent.flatMap(_.props.get("owner"))).flatMap(Users.findUserById(_)).exists(parentOwner => isInSameClub(u.get, parentOwner))
            ) orCorr
            cNotMember(uname(props.get("owner")))).getOrElse(
            false))
  }

  /**
   * can the user see the topic - a little more checks than isVisibile - this is the one to use
   *
   * can pass admin.IgnoreErrors as an errCollector
   */
  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] = {
    lazy val isAdmin = au.exists(_.hasPerm(Perm.adminDb))
    lazy val we = if (w.isDefined) w else Wikis.find(wid)
    val cat = wid.cat
    val name = wid.name
    (for (
      pubProfile <- ("User" != cat || WikiIndex.withIndex(_.get1k(name).exists(_.cat == cat)) || au.map(name == _.userName).getOrElse(isAdmin)) orErr ("Sorry - profile not found or is private! %s : %s".format(cat, name));
      mine2 <- (!we.isDefined || isVisible(au, we.get.props, "visibility", we)) orErr ("Sorry - topic is not visible!"); // TODO report
      t <- true orErr ("just can't, eh")
    ) yield true)
    // TODO parent can see child's profile
  }

  final val corrVerified = new Corr("not verified", """Sorry - you need to <a href="/user/task/verifyEmail">verify your email address</a>, to create or edit public topics.\n If you already did, please describe the issue in a  <a href="/doe/support?desc=email+already+verified">support request</a> below.""");

  /**
   * can the user edit the topic
   *
   *  can pass admin.IgnoreErrors as an errCollector
   */
  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VErrors): Option[Boolean] = {
    val cat = wid.cat
    val name = wid.name
    lazy val we = if (w.isDefined) w else Wikis.find(cat, name)
    lazy val wprops = if (we.isDefined) we.map(_.props) else props
    if (u.isDefined && u.exists(_.hasPerm(Perm.adminDb)))
      Some(true)
    else (for (
      cansee <- canSee(wid, u, w);
      au <- u orCorr cNoAuth;
      isA <- checkActive(au);
      r1 <- ("Category" != cat || au.hasPerm(Perm.adminWiki)) orErr ("no permission to edit a Category");
      r2 <- ("Admin" != cat || au.hasPerm(Perm.adminWiki)) orErr ("no permission to edit an Admin entry");
      mine <- ("User" != cat || name == au.userName) orErr ("Can only edit your own public profile!");
      mine1 <- ("User" != cat || au.canHasProfile) orErr ("Sorry - you cannot have a public profile - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=parent+should+allow\">support request</a> below.");
      mine2 <- ("WikiLink" == cat || au.canHasProfile) orErr ("Sorry - you cannot create or edit public topics - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=cannot+have+public+profile\">support request</a> below.");
      pro <- au.profile orCorr cNoProfile;
      verif <- ("WikiLink" == cat || "User" == cat || au.hasPerm(Perm.eVerified)) orCorr corrVerified;
      res <- (!w.exists(_.isReserved) || au.hasPerm(Perm.adminWiki) || "User" == wid.cat) orErr ("Category is reserved");
      owner <- !(WikiDomain.needsOwner(cat)) ||
        we.exists(_.isOwner(au.id)) ||
        (wprops.flatMap(_.get("wvis")).isDefined && isVisible(u, wprops.get, "wvis")) ||
        wprops.flatMap(_.get("visibility")).exists(_.startsWith(Visibility.CLUB) && isVisible(u, wprops.get, "visibility")) ||
        !wvis(wprops).isDefined orErr ("Sorry - you are not the owner of this topic");
      memod <- (w.flatMap(_.contentTags.get("moderator")).map(_ == au.userName).getOrElse(true)) orErr ("Sorry - this is moderated and you are not the moderator, are you?");
      t <- true orErr ("can't")
    ) yield true)
  }
}
