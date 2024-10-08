/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.mongodb.util.JSON
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.db.RazSalatContext._
import razie.db._
import razie.db.tx.txn
import razie.wiki.{Enc, Services}
import razie.wiki.model._
import scala.concurrent.duration.DurationInt
import salat._

/** permissions for a user group */
@RTable
case class UserGroup(
  name: String,
  can: Set[String] = Set(Perm.uProfile.plus),
  _id : ObjectId = new ObjectId()
  ) extends REntity[UserGroup]


/**
 * high churn table - user edit slots
 *
 * I use these mainly to limit new users to just a few changes,
 * in case some paparazzi gets through...
 */
@RTable
case class UserQuota(
  userId: ObjectId,
  updates: Option[Int] = Some(5), // number of wikis updated
  _id: ObjectId = new ObjectId()) {

  //  def create = Mongo ("Profile") += Select remote host to compare againstgrater[Profile].asDBObject(Audit.create
  //  (this))
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

  def incUpdates = {
    val q = this.copy(updates = updates.map(_ - 1) orElse Some(5))
    update(q)

    if (q.updates.exists(_ < 20))
      Services ! EventNeedsQuota (
        Users.findUserById (userId).map (u=> s"$u.userName - $u.firstName $u.lastName").toString,
        userId.toString
      )
  }

  // admin op
  def reset(i: Int) = {
    val q = this.copy(updates = Some(i))
    update(q)
  }
}

/** cqrs decouplig */
case class EventNeedsQuota(s1:String, s2:String, override val node:String="") extends WikiEventBase

/**
 * a parent/child relationship with additional permissions.
 *
 * used to supervise users under 13
 */
@RTable
case class ParentChild(
  parentId: ObjectId,
  childId: ObjectId,
  trust: String = "Private",
  notifys: String = "Everything",
  _id: ObjectId = new ObjectId()) extends REntity[ParentChild] {

  def update(p: ParentChild): Unit = {
    RUpdate(
      Map("parentId" -> parentId, "childId" -> childId),
      p)
  }
  def delete: Unit = RDelete[ParentChild]("parentId" -> parentId, "childId" -> childId)
}

object UW {
  final val NOEMAIL = "n"
  final val EMAIL_EACH = "e"
  final val EMAIL_DAILY = "d" // TODO
}

/** a link between a user and a wiki.
  *
  * indicates owner, follower, member etc
  */
@RTable
case class UserWiki(
    userId: ObjectId,
    uwid: UWID,
    role: String,
    notif:String = UW.EMAIL_EACH,
    _id: ObjectId = new ObjectId()) extends REntity[UserWiki] {

  def updateRole(newRole: String): Unit = {
      this.copy(role=newRole).update
  }

  def wlink = WikiLink(UWID("User", userId), uwid, "")
  def wname = wlink.wname

  lazy val user = Users.findUserById(userId)
  def wid = uwid.wid.get
}

/** the presence of this indicates the user is not verified */
case class UserVerifReq(id: UserId, verifType: String)

/** some user audit info */
@RTable
case class UserEvent(
  userId: ObjectId,
  what: String,
  when: DateTime = DateTime.now()) {

  def create: Unit = RCreate(this)
}

import play.api.cache._
import razie.wiki.Services

/** user factory and utils */
object Users {

  final val SUSPENDED = 's'
  final val ACTIVE = 'a'
  final val DELETED = 'd'

  final val ROLE_MEMBER = "Member"

  var persist: UsersPersist = {
    val cls =
      if(Services.config.isLocalhost)
        Services.config.prop("users.persistance", "model.UsersPersistMongo")
      else
        "model.UsersPersistMongo"
    val r = Services.config.prop("default.realm", "specs")
    val c = Class.forName(cls).newInstance().asInstanceOf[UsersPersist]
    c.setDefaultRealm(r)
    c
  }

  def uniqueUsername(initial: String) = {
    var cur = initial
    var n = 0

    while (n < 20 && Users.findUserByUsername(cur).isDefined) {
      n = n + 1
      cur = initial + "." + n
    }

    if (n >= 20) cur = initial + "." + System.currentTimeMillis()
    cur
  }

  def asDBO(u: User) = grater[User].asDBObject(u)
  def fromJsonUser(j: String) = Option(grater[User].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  /** find user by lowercase email - at loging
    *
    * @param uncEmail unencoded email
    * @return user if found
    */
  def findUserNoCase(uncEmail: String) = persist.findUserNoCase(uncEmail)

  /** find user by lowercase email - at loging realm could be "*" if you're an admin... */
  def findUsersForRealm(realm: String) = persist.findUsersForRealm(realm)

  /** find by decrypted email - as entered by a user */
  def findUserByEmailDec(emailDec: String) = findUserByEmailEnc(Enc(emailDec))

  /** find by encrypted email */
  def findUserByEmailEnc(emailEnc: String) = persist.findUserByEmailEnc(emailEnc)

  /** find by encrypted email */
  def findUserByApiKey(key: String) = persist.findUserByApiKey(key)

  def findUserByFirstName(s: String) = persist.findUsersForRealm(s)

  def findUserById(id: String) = persist.findUserById(new ObjectId(id))

  def findUserById(id: ObjectId) = persist.findUserById(id)

  def findUserByUsername(uname: String) = persist.findUserByUsername(uname)

  def unameF(f: String, l: String, yob: Int = 0) =
    (f + (if (l.length > 0) ("." + l) else ""))
        .replaceAll("[^a-zA-Z0-9\\.]", ".")
        .replaceAll("[\\.\\.]", ".")

  def findProfileByUserId(userId: String): Option[Profile] = persist.findProfileByUserId(userId)

  def createProfile(p:Profile): Unit = persist.createProfile(p)
  def updateProfile(p:Profile): Unit = persist.updateProfile(p)
  def updateUser(oldu:User, newu:User): Unit = persist.updateUser(oldu, newu)
  def createUser(newu:User): Unit = persist.createUser(newu)

  //todo optimize this - cache some users?
  /** display name of user with id, for comments etc */
  def nameOf(uid: ObjectId): String = {
    Services.cache.get[String](uid.toString + ".username").getOrElse {
      val n = Services.auth.cachedUserById(uid.toString).map(_.userName).getOrElse {
        persist.nameOf(uid)
      }
      Services.cache.set(uid.toString + ".username", n, 600.seconds) // 10 miuntes
      n
    }
  }

  def nameOf(uid: String):String = nameOf(new ObjectId(uid))

  /** find tasks for user */
  def findTasks(uid: ObjectId) = {
    val x = RMany[UserTask]("userId" -> uid).toList
//    if(ROne[VolunteerH]("approver" -> findUserById(uid).map(_.email.dec), "approvedBy" -> None).isDefined)
//      UserTasks.approveVolunteerHours(uid) :: x
//    else
      x
  }

  def findPC(pid: ObjectId, cid: ObjectId) = ROne[ParentChild]("parentId" -> pid, "childId" -> cid)
  def findParentOf(cid: ObjectId) = ROne[ParentChild]("childId" -> cid)

  def findUserLinksTo(u: UWID) = RMany[UserWiki]("uwid.cat" -> u.cat, "uwid.id" -> u.id)

  def create(r: Task): Unit = RCreate(r)

  def findFollowerByEmail(email: String) = ROne[Follower]("email" -> email)
  def findFollowerLinksTo(u: UWID) = RMany[FollowerWiki]("uwid.cat" -> u.cat, "uwid.id" -> u.id)

  def updRealm(au:User, realm:String): User = {
    var u = au.copy(realms = (au.realms + realm))

    // copy standard perms to new realm
    if (u.realmSet.exists(_._2.perms.contains("+" + Perm.uProfile.s))) u = u.addPerm(realm, Perm.uProfile.s)
    if (u.realmSet.exists(_._2.perms.contains("+" + Perm.eVerified.s))) u = u.addPerm(realm, Perm.eVerified.s)
    if (u.realmSet.exists(_._2.perms.contains("+" + Perm.uWiki.s))) u = u.addPerm(realm, Perm.uWiki.s)
    if (u.realmSet.exists(_._2.perms.contains("+" + Perm.adminWiki.s))) u = u.addPerm(realm, Perm.adminWiki.s)
    u
  }

}

/** represents a unique user id */
case class UserId (id:String)

