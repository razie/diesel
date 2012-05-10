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

/** temporary registrtion/login form */
case class Registration(email: String, password: String, repassword: String = "") {
  def ename = email.replaceAll("@.*", "")
}

/** register an email for news */
case class RegdEmail(email: String, when: DateTime = DateTime.now) {
  def create = Mongo ("RegdEmail") += grater[RegdEmail].asDBObject(Audit.create(this))
  def delete = Mongo ("RegdEmail").m.remove(Map("email" -> email))
}

/** permissions for a user group */
case class UserGroup(
  name: String,
  can: Seq[String] = Seq("+uProfile"))

/** Minimal user info - loaded all the time for a user */
case class User(
  userName: String,
  firstName: String,
  lastName: String,
  yob: Int,
  email: String,
  pwd: String,
  userType: String = "racer",
  _id: ObjectId = new ObjectId()) {

  // TODO change id = it shows like everywhere
  def id = _id.toString

  def ename = if (firstName != null && firstName.size > 0) firstName else Users.dec(email).replaceAll("@.*", "")

  def tasks = Users.findTasks(_id)

  // TODO optimize
  def perms: Seq[String] = profile.map(_.perms).getOrElse(Seq()) ++ group.map(_.can).getOrElse(Seq())
  def hasPerm(p: String) = perms.contains("+"+p) && !perms.contains("-"+p)

  def under12 = DateTime.now.year.get - yob <= 12

  // TODO cache groups
  lazy val group = Mongo("UserGroup").findOne(Map("name" -> userType)) map (grater[UserGroup].asObject(_))

  /** make a default profile */
  def mkProfile = Profile(this._id, Seq())

  def rel(r: String): List[User] = profile.map(p =>
    (for (t <- p.relationships if (t._2 == r))
      yield t._1).flatMap(Users.findUserById(_)).toList) getOrElse List()

  /** load my profile */
  lazy val profile = Mongo("Profile").findOne(Map("userId" -> _id)) map (grater[Profile].asObject(_))

  lazy val wikis =
    Mongo("UserWiki").find(Map("userId" -> _id)).map(grater[UserWiki].asObject(_)).toList

  def pages(cat: String) = wikis.filter(_.cat == cat)

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
    var res = Mongo ("User") += grater[User].asDBObject(Audit.create(this))

    p.createdDtm = DateTime.now()
    p.lastUpdatedDtm = DateTime.now()
    res = Mongo ("Profile") += grater[Profile].asDBObject(Audit.create(p))

    UserEvent(_id, UserEvent.CREATE).create
  }

  def update(u: User) = {
    Mongo ("UserOld") += grater[User].asDBObject(Audit.create(this))
    Mongo("User").m.update(key, grater[User].asDBObject(Audit.update(u)))
    UserEvent(_id, UserEvent.UPDATE).create
  }

  def toJson = grater[User].asDBObject(this).toString
}

/** detailed user profile
 *
 *  perms are permissions
 */
case class Profile(
  userId: ObjectId,
  tags: Seq[String] = Seq(),
  perms: Seq[String] = Seq(),
  aboutMe: Option[WikiEntry] = None,
  relationships: Map[String, String] = Map(), // (who -> what)
  _id: ObjectId = new ObjectId()) {

//  def create = Mongo ("Profile") += grater[Profile].asDBObject(Audit.create(this))
  def update(p: Profile) = Mongo("Profile").m.update(Map("userId" -> userId), grater[Profile].asDBObject(Audit.update(p)))

  def addRel(t: (String, String)) = model.Profile(userId, tags, perms, aboutMe, relationships ++ Map(t), _id)

  var createdDtm: DateTime = DateTime.now
  var lastUpdatedDtm: DateTime = DateTime.now
}

/** a parent/child relationship with additional permissions
 */
case class ParentChild(
  parentId: ObjectId,
  childId: ObjectId,
  trust: String = "Moderated",
  notifys: String = "Content",
  _id: ObjectId = new ObjectId()) {

  def create = Mongo ("ParentChild") += grater[ParentChild].asDBObject(Audit.create(this))
  def update(p: ParentChild) = {
    Mongo("ParentChild").m.update(
        Map("parentId" -> parentId, "childId" -> childId), 
        grater[ParentChild].asDBObject(Audit.update(p)))
  }
  def delete = Mongo("ParentChild").m.remove(Map("parentId" -> parentId, "childId" -> childId))
}

/** a link between a user and a wiki */
case class UserWiki(userId: ObjectId, cat: String, name: String) {
  def create = Mongo ("UserWiki") += grater[UserWiki].asDBObject(Audit.create(this))

  def wname = WikiLink(WID("User", userId.toString), WID(cat, name), "").wname
}

/** detailed user profile
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

  def create = Mongo ("UserEvent") += grater[UserEvent].asDBObject(Audit.create(this))

}

object UserEvent {
  final val CREATE = "CREATE"
  final val UPDATE = "UPD_PROFILE"
  final val UPD_PROFILE = "UPD_PROFILE"
}

/** user factory and utils */
object Users {
  lazy val userTypes = Array("Racer", "Parent", "Organization")
  lazy val reservedUsers = Array("assets", "do", "wiki", "admin")

  def fromJson(j: String) = Option(grater[User].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  def findUser(email: String) = Mongo("User").findOne(Map("email" -> email)) map (grater[User].asObject(_))
  def findUserById(id: String) = Mongo("User").findOne(Map("_id" -> new ObjectId(id))) map (grater[User].asObject(_))
  def findUserById(id: ObjectId) = Mongo("User").findOne(Map("_id" -> id)) map (grater[User].asObject(_))

  def findTasks(id: ObjectId) = Mongo("UserTask").find(Map("userId" -> id)) map (grater[UserTask].asObject(_))

  def findPC(pid: ObjectId, cid: ObjectId) = Mongo("ParentChild").findOne(Map("parentId" -> pid, "childId" -> cid)) map (grater[ParentChild].asObject(_))

  def create(ug: UserGroup) = Mongo ("UserGroup") += grater[UserGroup].asDBObject(Audit.create(ug))

  def group(name: String) = Mongo("UserGroup").findOne(Map("name" -> name)) map (grater[UserGroup].asObject(_))

  def create(r: Task) = (Mongo ("Task") += grater[Task].asDBObject(Audit.create(r)))

  def enc(e: String) = (new admin.CipherCrypt).encrypt(e)
  def dec(e: String) = (new admin.CipherCrypt).decrypt(e)

}

case class Task(name: String, desc: String)

case class UserTask(userId: ObjectId, name: String) {
  def desc = Mongo("Task").findOne(Map("name" -> name)) map (grater[Task].asObject(_)) map (_.desc) getOrElse "?"
  def create = Mongo ("UserTask") += grater[UserTask].asDBObject(Audit.create(this))

  def delete = {
    Audit.delete(this);
    Mongo ("UserTask").m.remove(Map("userId" -> userId, "name" -> name))
  }
}
