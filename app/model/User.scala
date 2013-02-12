package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log
import controllers.UserStuff
import model.Sec._
import controllers.Maps
import controllers.RazController
import play.api.cache.Cache
import admin.MailSession
import controllers.Emailer

object UserType {
  val Organization = "Organization"
}

/** temporary registrtion/login form */
case class Registration(email: String, password: String, repassword: String = "") {
  def ename = email.replaceAll("@.*", "")
}

/** register an email for news */
case class RegdEmail(email: String, when: DateTime = DateTime.now) {
  def create = Mongo("RegdEmail") += grater[RegdEmail].asDBObject(Audit.create(this))
  def delete = Mongo("RegdEmail").m.remove(Map("email" -> email))
}

/** permissions for a user group */
case class UserGroup(
  name: String,
  can: Set[String] = Set(Perm.uProfile.plus))

case class Location(city: String, state: String, country: String) {}

case class Perm(s: String) {
  def plus = "+" + this.s
  def minus = "-" + this.s
}

object Perm {
  val adminDb = Perm("adminDb")         // god - can fix users etc
  val adminWiki = Perm("adminWiki")     // can administer wiki - edit categories/reserved pages etc
  val uWiki = Perm("uWiki")             // can update wiki
  val uProfile = Perm("uProfile")       
  val eVerified = Perm("eVerified")
  val apiCall = Perm("apiCall")         // special users that can make api calls

  implicit def tos(p: Perm): String = p.s

  // TODO - how to do this better with enum support
  // TODO - remove the old perms from this list at some point
  val all: Seq[String] = Seq(adminDb, adminWiki, uWiki, uProfile, eVerified, apiCall, "cCategory", "uCategory", "uReserved")
}

/** Minimal user info - loaded all the time for a user */
case class User(
  userName: String,
  firstName: String,
  lastName: String,
  yob: Int,
  email: String,
  pwd: String,
  status: Char = 'a', // a-active, s-suspended, d-deleted
  roles: Set[String], // = Set("Racer"),
  addr: Option[String] = None, // address as typed in
  prefs: Map[String, String] = Map(), // = Set("Racer"),
  _id: ObjectId = new ObjectId()) {

  // TODO change id = it shows like everywhere
  def id = _id.toString

  def ename = if (firstName != null && firstName.size > 0) firstName else email.dec.replaceAll("@.*", "")

  def tasks = Users.findTasks(_id)

  def isActive = status == 'a'
  def isSuspended = status == 's'
  def isClub = roles contains UserType.Organization.toString
  def isAdmin = hasPerm (Perm.adminDb) || hasPerm (Perm.adminWiki)

  // TODO optimize
  def perms: Set[String] = profile.map(_.perms).getOrElse(Set()) ++ groups.flatMap(_.can).toSet
  def hasPerm(p: Perm) = perms.contains("+" + p.s) && !perms.contains("-" + p.s)

  def under12 = DateTime.now.year.get - yob <= 12

  // centered on Toronto by default
  lazy val ll = addr.flatMap(Maps.latlong _).getOrElse(("43.664395", "-79.376907"))

  lazy val canHasProfile = (!under12) || Users.findParentOf(_id).exists(_.trust == "Public")

  // TODO cache groups
  lazy val groups = roles flatMap { role => Mongo("UserGroup").findOne(Map("name" -> role)) map (grater[UserGroup].asObject(_)) }

  /** make a default profile */
  def mkProfile = Profile(this._id, None, Set())

  def rel(r: String): List[User] = profile.map(p =>
    (for (t <- p.relationships if (t._2 == r))
      yield t._1).flatMap(Users.findUserById(_)).toList) getOrElse List()

  /** load my profile */
  lazy val profile = Mongo("Profile").findOne(Map("userId" -> _id)) map (grater[Profile].asObject(_))

  /** the wikis I linked to */
  lazy val wikis =
    Mongo("UserWiki").find(Map("userId" -> _id)).map(grater[UserWiki].asObject(_)).toList

  /** pages of category that I linked to */
  def pages(cat: String) = wikis.filter(_.wid.cat == cat)

  def auditCreated { Log.audit(AUDT_USER_CREATED + email) }
  def auditLogout { Log.audit(AUDT_USER_LOGOUT + email) }
  def auditLogin { Log.audit(AUDT_USER_LOGIN + email) }
  def auditLoginFailed { Log.audit(AUDT_USER_LOGIN_FAILED + email) }

  final val AUDT_USER_CREATED = "USER_CREATED "
  final val AUDT_USER_LOGIN = "USER_LOGIN "
  final val AUDT_USER_LOGIN_FAILED = "USER_LOGIN_FAILED "
  final val AUDT_USER_LOGOUT = "USER_LOGOUT "

  lazy val key = Map("email" -> email)

  def create(p: Profile) {
    var res = Mongo("User") += grater[User].asDBObject(Audit.create(this))

    p.createdDtm = DateTime.now()
    p.lastUpdatedDtm = DateTime.now()
    res = Mongo("Profile") += grater[Profile].asDBObject(Audit.create(p))

    UserEvent(_id, UserEvent.CREATE).create
  }

  def update(u: User) = {
    Mongo("UserOld") += grater[User].asDBObject(Audit.create(this))
    Mongo("User").m.update(key, grater[User].asDBObject(Audit.update(u)))
    UserEvent(_id, UserEvent.UPDATE).create
  }

  def usedSlot(u: User) = {
    Mongo("UserOld") += grater[User].asDBObject(Audit.create(this))
    Mongo("User").m.update(key, grater[User].asDBObject(Audit.update(u)))
    UserEvent(_id, UserEvent.UPDATE).create
  }

  //race, desc, date, venue
  lazy val events: List[(ILink, String, DateTime, ILink)] = UserStuff.events(this)

  def toJson = grater[User].asDBObject(this).toString

  def shouldEmailParent(what: String) = {
    if (under12) {
      for (
        pc <- Users.findParentOf(this._id) if (pc.notifys == what);
        parent <- Users.findUserById(pc.parentId)
      ) yield parent
    } else None
  }

  def pref (name:String) (default: => String) = prefs.get(name).getOrElse (default)
  
  // TODO optimize
  def quota = {
    (Mongo("UserQuota").findOne(Map("userId" -> _id)) map (grater[UserQuota].asObject(_)) ) getOrElse UserQuota (_id)
  }
}

/**
 * detailed user profile
 *
 *  perms are permissions
 */
case class Profile(
  userId: ObjectId,
  loc: Option[Location] = None, // address as refined with Google
  tags: Set[String] = Set(),
  perms: Set[String] = Set(),
  aboutMe: Option[WikiEntry] = None,
  relationships: Map[String, String] = Map(), // (who -> what)
  _id: ObjectId = new ObjectId()) {

  //  def create = Mongo ("Profile") += grater[Profile].asDBObject(Audit.create(this))
  def update(p: Profile) = {
    Mongo("Profile").m.update(Map("userId" -> userId), grater[Profile].asDBObject(Audit.update(p)))
  }

  def addRel(t: (String, String)) = model.Profile(userId, loc, tags, perms, aboutMe, relationships ++ Map(t), _id)
  def addPerm(t: String) = model.Profile(userId, loc, tags, perms + t, aboutMe, relationships, _id)
  def removePerm(t: String) = model.Profile(userId, loc, tags, perms - t, aboutMe, relationships, _id)
  def addTag(t: String) = model.Profile(userId, loc, tags + t, perms, aboutMe, relationships, _id)

  var createdDtm: DateTime = DateTime.now
  var lastUpdatedDtm: DateTime = DateTime.now
}

/**
 * high churn table - user edit slots
 */
case class UserQuota (
  userId: ObjectId,
  updates: Option[Int] = Some(5), // number of wikis updated
  _id: ObjectId = new ObjectId()) {

  //  def create = Mongo ("Profile") += grater[Profile].asDBObject(Audit.create(this))
  def update (q:UserQuota) = 
    Mongo("UserQuota").findOne(Map("userId" -> userId)) map {p=>
      Mongo("UserQuota").m.update(Map("userId" -> userId), grater[UserQuota].asDBObject(q))
      q
    } getOrElse {
      Mongo("UserQuota") += grater[UserQuota].asDBObject(q)
      q
    }
    
  def canUpdate = {
    this.updates.exists (_ > 0) || this.updates.isEmpty
  }
  
  def incUpdates (implicit mailSession: MailSession) = {
    val q = UserQuota (userId, updates.map (_ - 1) orElse Some(5), _id)
    update (q)

    if (! q.canUpdate) Emailer.sendEmailNeedQuota(userId.toString)
  }
 
}

/**
 * a parent/child relationship with additional permissions
 */
case class ParentChild(
  parentId: ObjectId,
  childId: ObjectId,
  trust: String = "Private",
  notifys: String = "Everything",
  _id: ObjectId = new ObjectId()) {

  def create = Mongo("ParentChild") += grater[ParentChild].asDBObject(Audit.create(this))
  def update(p: ParentChild) = {
    Mongo("ParentChild").m.update(
      Map("parentId" -> parentId, "childId" -> childId),
      grater[ParentChild].asDBObject(Audit.update(p)))
  }
  def delete = Mongo("ParentChild").m.remove(Map("parentId" -> parentId, "childId" -> childId))
}

/** a link between a user and a wiki */
case class UserWiki(userId: ObjectId, wid: WID, role: String, _id: ObjectId = new ObjectId()) {
  def create = Mongo("UserWiki") += grater[UserWiki].asDBObject(Audit.create(this))
  def delete = { Audit.delete(this); Mongo("UserWiki").m.remove(Map("_id" -> _id)) }

  def wlink = WikiLink(WID("User", userId.toString), wid, "")
  def wname = wlink.wname

  def user = model.Users.findUserById(userId)

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
case class UserEvent(
  userId: ObjectId,
  what: String,
  when: DateTime = DateTime.now()) {

  def create = Mongo("UserEvent") += grater[UserEvent].asDBObject(Audit.create(this))

}

object UserEvent {
  final val CREATE = "CREATE"
  final val UPDATE = "UPD_PROFILE"
  final val UPD_PROFILE = "UPD_PROFILE"
}

/** user factory and utils */
object Users {
  lazy val reservedUsers = Array("assets", "do", "wiki", "admin")

  def fromJson(j: String) = Option(grater[User].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  // TOD optimize somwhow
  def findUserNoCase(uncEmail: String) = {
    val tl = uncEmail.toLowerCase()
    Mongo("User") find (Map()) find (_.as[String]("email").dec.toLowerCase == tl) map (grater[User].asObject(_))
  }

  def findUser(email: String) = Mongo("User").findOne(Map("email" -> email)) map (grater[User].asObject(_))
  def findUserById(id: String) = Mongo("User").findOne(Map("_id" -> new ObjectId(id))) map (grater[User].asObject(_))
  def findUserById(id: ObjectId) = Mongo("User").findOne(Map("_id" -> id)) map (grater[User].asObject(_))
  def findUserByUsername(uname: String) = Mongo("User").findOne(Map("userName" -> uname)) map (grater[User].asObject(_))

  def nameOf(id: ObjectId): String = Mongo("User").findOne(Map("_id" -> id)).get("userName").toString

  def findTasks(id: ObjectId) = Mongo("UserTask").find(Map("userId" -> id)) map (grater[UserTask].asObject(_))

  def findPC(pid: ObjectId, cid: ObjectId) = Mongo("ParentChild").findOne(Map("parentId" -> pid, "childId" -> cid)) map (grater[ParentChild].asObject(_))
  def findParentOf(cid: ObjectId) = Mongo("ParentChild").findOne(Map("childId" -> cid)) map (grater[ParentChild].asObject(_))
  def findChildOf(pid: ObjectId) = Mongo("ParentChild").findOne(Map("parentId" -> pid)) map (grater[ParentChild].asObject(_))

  def findUserLinksTo(wid: WID) = Mongo("UserWiki").find(Map("wid.name" -> wid.name, "wid.cat" -> wid.cat)).map(grater[UserWiki].asObject(_))
  def findUserLinksToCat(cat: String) = Mongo("UserWiki").find(Map("wid.cat" -> cat)).map(grater[UserWiki].asObject(_))

  def create(ug: UserGroup) = Mongo("UserGroup") += grater[UserGroup].asDBObject(Audit.create(ug))

  def group(name: String) = Mongo("UserGroup").findOne(Map("name" -> name)) map (grater[UserGroup].asObject(_))

  def create(r: Task) = (Mongo("Task") += grater[Task].asDBObject(Audit.create(r)))
}

case class Task(name: String, desc: String)

case class UserTask(userId: ObjectId, name: String) {
  def desc = Mongo("Task").findOne(Map("name" -> name)) map (grater[Task].asObject(_)) map (_.desc) getOrElse "?"
  def create = Mongo("UserTask") += grater[UserTask].asDBObject(Audit.create(this))

  def delete = {
    Audit.delete(this);
    Mongo("UserTask").m.remove(Map("userId" -> userId, "name" -> name))
  }
}

object UserTasks {
  def userNameChgDenied(u: User) = UserTask(u._id, "userNameChgDenied")
  def verifyEmail(u: User) = UserTask(u._id, "verifyEmail")
  def addParent(u: User) = UserTask(u._id, "addParent")
}

