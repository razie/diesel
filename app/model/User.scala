package model

import mod.snow._
import razie.wiki.Sec._
import com.mongodb.casbah.Imports._
import com.mongodb.util.JSON
import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import razie.db.RazSalatContext._
import admin.{Config, CipherCrypt}
import java.net.URLEncoder
import razie.Log
import controllers.UserStuff
import controllers.Maps
import controllers.RazController
import controllers.Emailer
import scala.annotation.StaticAnnotation
import model.proto.UserId
import razie.wiki.model._
import razie.wiki.admin.MailSession
import razie.wiki.admin.Audit
import com.mongodb.DBObject
import razie.db._
import razie.Snakk
import razie.db.RMongo.as
import razie.db.tx.txn

object UserType {
  val Organization = "Organization"
}

/** temporary user for following stuff */
@RTable
case class Follower(
  email: String,
  name: String,
  when: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {
  def delete = RDelete[Follower]("email" -> email)
}

/** temporary user for following stuff */
@RTable
case class FollowerWiki(
  followerId: ObjectId,
  comment: String,
  uwid: UWID,
  _id: ObjectId = new ObjectId()) {
  def delete = { Audit.delete(this); RDelete[FollowerWiki]("_id" -> _id) }

  def follower = ROne[Follower](followerId)
}

/** permissions for a user group */
@RTable
case class UserGroup(
  name: String,
  can: Set[String] = Set(Perm.uProfile.plus))

case class Location(city: String, state: String, country: String) {}

case class Perm(s: String) {
  def plus = "+" + this.s
  def minus = "-" + this.s
}

// ACLs
object Perm {
  val adminDb = Perm("adminDb") // god - can fix users etc
  val adminWiki = Perm("adminWiki") // can administer wiki - edit categories/reserved pages etc
  val uWiki = Perm("uWiki") // can update wiki
  val uProfile = Perm("uProfile")
  val eVerified = Perm("eVerified")
  val apiCall = Perm("apiCall") // special users that can make api calls
  val domFiddle = Perm("domFiddle") // can create services in eithe scala or JS
  val codeMaster = Perm("codeMaster") // can create services in eithe scala or JS

  val Member = Perm("Member") // not paid account - this is not actually needed in the profile - if au then member
  val Basic = Perm("Basic") // paid account
  val Gold = Perm("Gold") // paid account
  val Platinum = Perm("Platinum") // paid account
  val Moderator = Perm("Moderator") // paid account

  implicit def tos(p: Perm): String = p.s

  // TODO - how to do this better with enum support
  // TODO - remove the old perms from this list at some point
  val all: Seq[String] = Seq(adminDb, adminWiki, uWiki, uProfile, eVerified, apiCall, codeMaster,
    "cCategory", "uCategory", "uReserved",
    Basic, Gold, Platinum, Moderator, domFiddle
  )
}

/** Minimal user info - loaded all the time for a user */
@RTable
case class User(
  userName: String,
  firstName: String,
  lastName: String,
  yob: Int,
  email: String,
  pwd: String,
  status: Char = 'a', // a-active, s-suspended, d-deleted
  roles: Set[String], // = Set("Racer"),
  realms: Set[String]=Set(), // = RK modules (notes, rk, ski etc)
  addr: Option[String] = None, // address as typed in
  prefs: Map[String, String] = Map(), // = Set("Racer"),
  gid: Option[String] = None, // google id
  _id: ObjectId = new ObjectId()) extends WikiUser with TRacerKidInfo {

  // TODO change id = it shows like everywhere
  def id = _id.toString
  def parents:Set[ObjectId] = Set.empty // TODO implement from trait RacerKid
  def gender = "?"
  def notifyParent = false

  override def ename = if (firstName != null && firstName.size > 0) firstName else email.dec.replaceAll("@.*", "")

  def tasks = Users.findTasks(_id)

  def isActive = status == 'a'
  def isSuspended = status == 's'
  def isAdmin = hasPerm(Perm.adminDb) || hasPerm(Perm.adminWiki)
  def isClub = roles contains UserType.Organization.toString
  def isUnder13 = DateTime.now.year.get - yob <= 12
  def isHarry = id == "4fdb5d410cf247dd26c2a784"

  // TODO optimize
  def perms: Set[String] = profile.map(_.perms).getOrElse(Set()) ++ groups.flatMap(_.can).toSet
  def hasPerm(p: Perm) = perms.contains("+" + p.s) && !perms.contains("-" + p.s)

  override def hasMembershipLevel(s:String) =
    (s == Perm.Member.s) ||
    (s == Perm.Moderator.s && (this.hasPerm(Perm.Moderator) || this.isAdmin)) ||
    (s == Perm.Platinum.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Platinum))) ||
    (s == Perm.Gold.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Platinum) || this.hasPerm(Perm.Gold))) ||
    (s == Perm.Basic.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Platinum) || this.hasPerm(Perm.Gold) || this.hasPerm(Perm.Basic)))

  // centered on Toronto by default
  lazy val ll = addr.flatMap(Maps.latlong _).getOrElse(("43.664395", "-79.376907"))

  lazy val canHasProfile = (!isUnder13) || Users.findParentOf(_id).exists(_.trust == "Public")

  // TODO cache groups
  lazy val groups = roles flatMap {
    role => ROne[UserGroup]("name" -> role)
    }

  /** make a default profile */
  def mkProfile = Profile(this._id, None, Set())

  def rel(r: String): List[User] = profile.map(p =>
    (for (t <- p.relationships if (t._2 == r))
      yield t._1).flatMap(Users.findUserById(_)).toList) getOrElse List()

  /** load my profile */
  lazy val profile = ROne[Profile]("userId" -> _id)

  /** the wikis I linked to */
  lazy val wikis = RMany[UserWiki]("userId" -> _id).toList

  def isLinkedTo(uwid:UWID) = ROne[UserWiki]("userId" -> _id, "uwid" -> uwid.grated).toList

  /** pages of category that I linked to */
  def pages(realm:String, cat: String*) = wikis.filter{w=>
    (cat=="*"   || cat.contains(w.uwid.cat)) &&
    (realm=="*" || Reactors(realm).supers.contains(w.uwid.getRealm))
  }
  def myPages(realm:String, cat: String) = pages (realm, cat)

  def ownedPages(realm:String, cat: String) =
    Wikis(realm).weTable(cat).find(Map("props.owner" -> id, "category" -> cat)) map {o=>
      WID(cat, o.getAs[String]("name").get).r(o.getAs[String]("realm").get)
    }

  def auditCreated(realm:String) { Log.audit(AUDT_USER_CREATED + email + " realm: " + realm) }
  def auditLogout(realm:String) { Log.audit(AUDT_USER_LOGOUT + email+" realm: "+realm) }
  def auditLogin(realm:String) { Log.audit(AUDT_USER_LOGIN + email + " realm: " + realm) }
  def auditLoginFailed(realm:String) { Log.audit(AUDT_USER_LOGIN_FAILED + email + " realm: " + realm) }

  final val AUDT_USER_CREATED = "USER_CREATED "
  final val AUDT_USER_LOGIN = "USER_LOGIN "
  final val AUDT_USER_LOGIN_FAILED = "USER_LOGIN_FAILED "
  final val AUDT_USER_LOGOUT = "USER_LOGOUT "

  lazy val key = Map("email" -> email)

  def create(p: Profile) {
    var res = RCreate(this)

    p.createdDtm = DateTime.now()
    p.lastUpdatedDtm = DateTime.now()
    res = RCreate(p)

    UserEvent(_id, UserEvent.CREATE).create
  }

  def update(u: User) = {
    RazMongo("UserOld") += grater[User].asDBObject(Audit.create(this))
    RazMongo("User").update(key, grater[User].asDBObject(Audit.update(u)))
    UserEvent(_id, UserEvent.UPDATE).create
  }

  def usedSlot(u: User) = {
    RazMongo("UserOld") += grater[User].asDBObject(Audit.create(this))
    RazMongo("User").update(key, grater[User].asDBObject(Audit.update(u)))
    UserEvent(_id, UserEvent.UPDATE).create
  }

  //race, desc, date, venue
  lazy val events: List[(ILink, String, DateTime, ILink, Snakk.Wrapper[WWrapper])] =
    UserStuff.events(Wikis.RK, this)

  def toJson = grater[User].asDBObject(this).toString

  def shouldEmailParent(what: String) = {
    if (isUnder13) {
      for (
        pc <- Users.findParentOf(this._id) if (pc.notifys == what);
        parent <- ROne[User](pc.parentId)
      ) yield parent
    } else None
  }

  def getPrefs(name: String, default:String="") = prefs.get(name).getOrElse(default)
  def setPrefs(name: String, value : => String) = this.update(this.copy(prefs = this.prefs + (name -> value)))

  def hasRealm(m:String) = realms.contains(m) || (realms.isEmpty && Wikis.RK == m)

  def quota = ROne[UserQuota]("userId" -> _id) getOrElse UserQuota(_id)

  var css = prefs.get("css")

  /** find all RKA associated to user */
  def rka = {
    val mine = RMany[RacerKidAssoc]("from" -> _id)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }
  /** find all RK associated to user */
  def rk = rka map (_.to) flatMap (_.as[RacerKid])
  def myself=RacerKidz.myself(_id)
}

case class Contact(info: Map[String, String])

/**
 * detailed user profile
 *
 *  perms are permissions
 */
@RTable
case class Profile(
  userId: ObjectId,
  loc: Option[Location] = None, // address as refined with Google
  tags: Set[String] = Set(),
  perms: Set[String] = Set(),
  aboutMe: Option[WikiEntry] = None,
  relationships: Map[String, String] = Map(), // (who -> what)
  contact: Option[Contact] = None,
  consent:Option[String] = None,
  realmInfo: Map[String, String] = Map(), // (who -> what)
  _id: ObjectId = new ObjectId()) {

  def update(p: Profile) =  RUpdate(Map("userId" -> userId), p)

  def addRel(t: (String, String)) = this.copy(relationships = relationships ++ Map(t))
  def addPerm(t: String) = this.copy(perms = perms + t)
  def removePerm(t: String) = this.copy(perms = perms - t)
  def addTag(t: String) = this.copy(tags = tags + t)
  def setContact(c: Contact) = this.copy(contact = Some(c))

  def consented(ver: String) = this.copy(consent = Some(ver + " on " +DateTime.now().toString()))

  def setRealmProp(realm: String, prop:String, value:String) = {
    this.update(this.copy(realmInfo = this.realmInfo + ((realm+"."+prop) -> value)))
  }

  def getRealmProp(realm: String, prop:String, dfltValue:String="") = {
    realmInfo.getOrElse(realm+"."+prop, dfltValue)
  }

  var createdDtm: DateTime = DateTime.now
  var lastUpdatedDtm: DateTime = DateTime.now
}

/**
 * high churn table - user edit slots
 */
@RTable
case class UserQuota(
  userId: ObjectId,
  updates: Option[Int] = Some(5), // number of wikis updated
  _id: ObjectId = new ObjectId()) {

  //  def create = Mongo ("Profile") += grater[Profile].asDBObject(Audit.create(this))
  def update(q: UserQuota) =
    ROne[UserQuota]("userId" -> userId) map { p =>
      RUpdate.noAudit[UserQuota](Map("userId" -> userId), q)
      q
    } getOrElse {
      RCreate.noAudit[UserQuota](q)
      q
    }

  def canUpdate = {
    this.updates.exists(_ > 0) || this.updates.isEmpty
  }

  def incUpdates(implicit mailSession: MailSession) = {
    val q = this.copy(updates = updates.map(_ - 1) orElse Some(5))
    update(q)

    if (q.updates.exists(_ < 20))
      Emailer.sendEmailNeedQuota(Users.findUserById(userId).map(u=> s"$u.userName - $u.firstName $u.lastName").toString, userId.toString)
  }

  // admin op
  def reset(i: Int) = {
    val q = this.copy(updates = Some(i))
    update(q)
  }

}

/**
 * a parent/child relationship with additional permissions
 */
@RTable
case class ParentChild(
  parentId: ObjectId,
  childId: ObjectId,
  trust: String = "Private",
  notifys: String = "Everything",
  _id: ObjectId = new ObjectId()) extends REntity[ParentChild] {

  def update(p: ParentChild) = {
    RUpdate(
      Map("parentId" -> parentId, "childId" -> childId),
      p)
  }
  def delete = RDelete[ParentChild]("parentId" -> parentId, "childId" -> childId)
}

object UW {
  final val NOEMAIL = "n"
  final val EMAIL_EACH = "e"
  final val EMAIL_DAILY = "d" // TODO
}

/** a link between a user and a wiki */
@RTable
case class UserWiki(
    userId: ObjectId,
    uwid: UWID,
    role: String,
    notif:String = UW.EMAIL_EACH,
    _id: ObjectId = new ObjectId()) extends REntity[UserWiki] {

  def updateRole(newRole: String) = {
      this.copy(role=newRole).update
  }

  def wlink = WikiLink(UWID("User", userId), uwid, "")
  def wname = wlink.wname

  def user = ROne[User](userId)
  def wid = uwid.wid.get
}

/**
 * detailed user profile
 *
 *  perms are permissions
 */
case class UserPerms(
  user: User,
  group: UserGroup,
  can: Option[Seq[String]] = None,
  cant: Seq[String] = Seq()) {

  var _id: ObjectId = new ObjectId
  var createdDtm: DateTime = DateTime.now
  var lastUpdatedDtm: DateTime = DateTime.now
}

/** the presence of this indicates the user is not verified */
case class UserVerifReq(id: UserId, verifType: String)

/** some user audit info */
@RTable
case class UserEvent(
  userId: ObjectId,
  what: String,
  when: DateTime = DateTime.now()) {

  def create = RCreate(this)
}

object UserEvent {
  final val CREATE = "CREATE"
  final val UPDATE = "UPD_PROFILE"
  final val UPD_PROFILE = "UPD_PROFILE"
}

/** user factory and utils */
object Users {
  lazy val reservedUsers = Array("assets", "do", "wiki", "admin") ++ Config.reservedNames

  def fromJson(j: String) = Option(grater[User].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  // TOD optimize somwhow
  def findUserNoCase(uncEmail: String) = {
    val tl = uncEmail.toLowerCase()
    RazMongo("User") findAll () find (_.as[String]("email").dec.toLowerCase == tl) map (grater[User].asObject(_))
  }

  def findUser(email: String) = ROne[User]("email" -> email)
  def findUserById(id: String) = ROne[User](new ObjectId(id))
  def findUserById(id: ObjectId) = ROne[User](id)
  def findUserByUsername(uname: String) = ROne[User]("userName" -> uname)

  //todo optimize this - cache some users?
  def nameOf(id: ObjectId): String = /* leave it */
    ROne.raw[User]("_id" -> id).fold("???")(_.apply("userName").toString)

  /** find tasks for user */
  def findTasks(uid: ObjectId) = {
    val x = RMany[UserTask]("userId" -> uid).toList
    if(ROne[VolunteerH]("approver" -> findUserById(uid).map(_.email.dec), "approvedBy" -> None).isDefined)
      UserTasks.approveVolunteerHours(uid) :: x
    else
      x
  }

  def findPC(pid: ObjectId, cid: ObjectId) = ROne[ParentChild]("parentId" -> pid, "childId" -> cid)
  def findParentOf(cid: ObjectId) = ROne[ParentChild]("childId" -> cid)
  def findChildOf(pid: ObjectId) = ROne[ParentChild]("parentId" -> pid)

  def findUserLinksTo(u: UWID) = RMany[UserWiki]("uwid" -> u.grated)
//  def findUserLinksTo(u: UWID) = RMany[UserWiki]("uwid.cat" -> u.cat, "uwid.id" -> u.id)
  def findUserLinksToCat(cat: String) = RMany[UserWiki]("wid.cat" -> cat)

  def create(ug: UserGroup) = RCreate(ug)

  def group(name: String) = ROne[UserGroup]("name" -> name)

  def create(r: Task) = RCreate(r)

  def findFollowerByEmail(email: String) = ROne[Follower]("email" -> email)
  def findFollowerLinksTo(u: UWID) = RMany[FollowerWiki]("uwid" -> u.grated)
//  def findFollowerLinksTo(u: UWID) = RMany[FollowerWiki]("uwid.cat" -> u.cat, "uwid.id" -> u.id)
}

/** user factory and utils */
object WikiUsersImpl extends WikiUsers {
  def findUserById(id: String) = Users.findUserById (id)
  def findUserById(id: ObjectId) = Users.findUserById (id)
  def findUserByUsername(uname: String) = Users.findUserByUsername(uname)
}

/** the static tasks - reference table */
@RTable
case class Task(name: String, desc: String)

@RTable
case class UserTask(
    userId: ObjectId,
    name: String,
    args:Map[String,String] = Map(),
    crDtm:DateTime = DateTime.now) {

  def desc = ROne[Task]("name" -> name) map (_.desc) getOrElse UserTasks.labelFor(this)
  def create (implicit txn:Txn) = RCreate(this)

  def delete (implicit txn:Txn) = {
    Audit.delete(this); // should delete more than one
    RDelete[UserTask]("userId" -> userId, "name" -> name)
  }
}

object UserTasks {
  final val START_REGISTRATION = "startRegistration"
  final val APPROVE_VOL = "approveVolunteerHours"

  def userNameChgDenied(u: User) = UserTask(u._id, "userNameChgDenied")
  def verifyEmail(u: User) = UserTask(u._id, "verifyEmail")
  def addParent(u: User) = UserTask(u._id, "addParent")
  def chooseTheme(u: User) = UserTask(u._id, "chooseTheme")
  def setupRegistration(u: User) = UserTask(u._id, "setupRegistration")
  def setupCalendars(u: User) = UserTask(u._id, "setupCalendars")
  def approveVolunteerHours(uid: ObjectId) = UserTask(uid, APPROVE_VOL)

  def some(u: User, what:String) = UserTask(u._id, what)

  def labelFor (ut:UserTask) = {
    ut.name match {
      case START_REGISTRATION => "Start registration for "+ut.args.get("club").mkString
      case "setupRegistration" => "Setup registration and forms"
      case "setupCalendars" => "Setup club calendars"
      case APPROVE_VOL => "Approve volunteer hours"
      case _ => "?"
    }
  }

}

