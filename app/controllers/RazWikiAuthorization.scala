package controllers

import javax.management.relation.RoleStatus

import razie.wiki.Sec.EncryptedS
import model._
import razie.Logging
import razie.diesel.dom.WikiDomain
import razie.wiki.model._

/** wiki controller base stuff - file too large */
object RazWikiAuthorization extends RazController with Logging with WikiAuthorization {

  implicit def toU (wu:WikiUser) : User = wu.asInstanceOf[User]

  // allow fans or not
  def NFAN (role:String) = role != "Former" // && role != "Fan"

  def isInSameClub(member: WikiUser, owner: WikiUser, role:String="") = { //}(implicit errCollector: VError = IgnoreErrors) = {
    // member's clubs
    val m1 = member.asInstanceOf[User].wikis.filter(x => x.uwid.cat == "Club" && NFAN(x.role)).toList

    (
      // owner is same as member
      (owner.roles.contains(UserType.Organization) && (member.userName == owner.userName)) ||
      // owner is the club
      (owner.roles.contains(UserType.Organization) && m1.exists(_.uwid.nameOrId == owner.userName)) ||
      // owner is someone else => club lists intersect?
      (!owner.roles.contains(UserType.Organization) && {
        val m2 = owner.wikis.filter(x => x.uwid.cat == "Club" && NFAN(x.role)) // owner's clubs
        m1.exists(x1 => m2.exists(x2=> x2.uwid.id == x1.uwid.id && (role.isEmpty || role == x1.role))) // do they intersect?
      }))
  }

  /** if user is admin of club where owner member */
  def isClubAdmin(admin: WikiUser, owner: WikiUser) = { //}(implicit errCollector: VError = IgnoreErrors) = {
    // all clubs where member
    val clubs = owner.wikis.filter(x => x.uwid.cat == "Club" && NFAN(x.role))

    (
      // owner is same as member
      (admin.isClub && (admin.userName == owner.userName)) ||
      // admin is the club
      (admin.isClub && clubs.exists(_.uwid.nameOrId == admin.userName)) ||
      // admin is god
      (!admin.isClub && admin.hasPerm(Perm.adminDb)) ||
      // admin is club admin
      (!admin.isClub && {
        val aemail = admin.emailDec
        clubs.exists(x1 => Users.findUserByUsername(x1.uwid.nameOrId).exists(
          u => u.prefs.get("regAdmin").exists(_ == aemail) ||
          Club(u).props.filter(_._1 startsWith "admin").exists(_._2 == aemail)
          ))
        }) // TODO this is expensive - at least optimize as a Mongo query?
      )
  }

  /** specific to clubs - can comment out whenever */
  private def isClubVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry]=None)(implicit errCollector: VErrors = IgnoreErrors) = {
    val pvis = props.getOrElse(visibility, "")

    props
      .get("owner")
      .flatMap(Users.findUserById(_))
      .exists(owner =>
         // hoping it's more likely members read blogs than register...
         pvis == Visibility.CLUB && isInSameClub(u.get, owner) ||
         pvis == Visibility.CLUB_COACH && isInSameClub(u.get, owner, "Coach") ||
         pvis == Visibility.CLUB_ADMIN && isClubAdmin(u.get, owner) ||
         // maybe the club created the parent topic (like forum/blog etc)?
         pvis == Visibility.CLUB &&
         props.get("parentOwner").flatMap(Users.findUserById(_)).exists(parentOwner => isInSameClub(u.get, parentOwner)) ||
         pvis == Visibility.CLUB &&
         we.flatMap(_.wid.findParent.flatMap(_.props.get("owner"))).flatMap(Users.findUserById(_)).exists(parentOwner => isInSameClub(u.get, parentOwner))
      )
  }

  /** can user see a topic with the given properties? */
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry]=None)(implicit errCollector: VErrors = IgnoreErrors): Boolean = {
    // TODO optimize
    def uname(id: Option[String]) = id.flatMap(Users.findUserById(_)).map(_.userName).getOrElse(id.getOrElse(""))

    val pvis = props.getOrElse(visibility, "")

    val res = (!props.get(visibility).isDefined) || u.exists(_.hasPerm(Perm.adminDb)) ||
      (pvis == Visibility.PUBLIC) || // if changing while edit, it will have a value even when public
      (u.isDefined orCorr cNoAuth).exists(_ == true) && // anything other than public needs logged in
      (
        props.get("owner") == Some(u.get.id) || // can see anything I am owner of - no need to check Visibility.PRIVATE
        u.get.hasMembershipLevel(pvis) ||
        (
          pvis.startsWith(Visibility.CLUB) && isClubVisible(u, props, visibility, we)
          orCorr cNotMember(uname(props.get("owner")))
        ).getOrElse(
            false
      )
    )

    res
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
      pubProfile <- (
        "User" != cat ||
        WikiIndex.withIndex(wid.getRealm)(_.get1k(name).exists(_.cat == cat)) ||
        au.map(name == _.userName).getOrElse(isAdmin)
        ) orErr (
        "Sorry - profile not found or is private! %s : %s".format(cat, name)
        );
      mine2 <- (
        !we.isDefined ||
          isVisible(au, we.get.props, "visibility", we)
        ) orErr ("Sorry - topic is not visible!"); // TODO report
      t <- true orErr ("just can't, eh")
    ) yield true)
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
    lazy val we = w orElse wid.page
    val wprops = if (we.isDefined) we.map(_.props) else props

    if (
      u.exists(_.hasPerm(Perm.adminDb)) ||
      u.exists(Club.canAdmin(wid, _)))
      Some(true)
    else (for (
      cansee <- canSee(wid, u, w);
      au <- u orCorr cNoAuth;
      isA <- checkActive(au);
      r1 <- ("Category" != cat || au.hasPerm(Perm.adminWiki)) orErr ("no permission to edit a Category");
      r2 <- ("Admin" != cat || au.hasPerm(Perm.adminWiki) || we.exists(_.isOwner(au.id))) orErr ("no permission to edit an Admin entry");
      mine <- ("User" != cat || name == au.userName) orErr ("Can only edit your own public profile!");
      mine1 <- ("User" != cat || au.canHasProfile) orErr ("Sorry - you cannot have a public profile - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=parent+should+allow\">support request</a> below.");
      mine2 <- ("WikiLink" == cat || au.canHasProfile) orErr ("Sorry - you cannot create or edit public topics - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=cannot+have+public+profile\">support request</a> below.");
      pro <- au.profile orCorr cNoProfile;
      verif <- ("WikiLink" == cat || "User" == cat || au.hasPerm(Perm.eVerified)) orCorr corrVerified;
      res <- (!w.exists(_.isReserved) || au.hasPerm(Perm.adminWiki) || "User" == wid.cat) orErr ("Category is reserved");
      owner <- !(WikiDomain(wid.getRealm).needsOwner(cat)) ||
        we.exists(_.isOwner(au.id)) ||
        (wprops.flatMap(_.get("wvis")).isDefined && isVisible(u, wprops.get, "wvis")) ||
        wprops.flatMap(_.get("visibility")).exists(_.startsWith(Visibility.CLUB) && isVisible(u, wprops.get, "visibility")) ||
        !wvis(wprops).isDefined orErr ("Sorry - you are not the owner of this topic");
      memod <- (
        we
          .flatMap(_.contentProps.get("moderator"))
          .map(_ == au.emailDec)
          .getOrElse(true)) orErr ("Sorry - this is moderated and you are not the moderator, are you?");
      noLevel <- (
        wprops.isEmpty ||
          wprops
            .flatMap(_.get("wvis"))
            .map(x=> isVisible(u, wprops.get, "wvis"))
            .exists(_ == true)
        ) orErr "Not enough Karma";
      t <- true orErr ("can't")
    ) yield true)
  }
}
