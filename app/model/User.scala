/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import razie.wiki.Sec._
import com.mongodb.casbah.Imports._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import salat._
import salat.annotations._
import razie.db.RazSalatContext._
import razie.Log
import razie.wiki.model._
import razie.db._
import razie.db.tx.txn
import razie.audit.Audit
import razie.diesel.dom.WikiDomain
import razie.hosting.{Website, WikiReactors}
import razie.wiki.util.Maps
import scala.collection.mutable

object UserType {
  val Organization = "Organization"
}

case class Location(city: String, state: String, country: String) {}

/** a person that may or may not have an account - or user group */
trait TPersonInfo {
  def firstName: String
  def lastName: String
  def email: String         // email encoded
  def emailDec: String      // email decoded
  def yob: Int
  def gender: String // M/F/?
  def roles: Set[String]
  def status: Char
  def notifyParent: Boolean
  def _id: ObjectId

  def ename = if (firstName != null && firstName.size > 0) firstName else emailDec.replaceAll("@.*", "")
  def fullName = firstName + " " + lastName
  def role = roles.head

  def organization:Option[String]
  def authClient:Option[String]
  def authRealm:Option[String]
  def authMethod:Option[String]

 /** which authorization realm sourced this user */
//  def authRealm:Option[String]
}

/** account - Minimal user info - loaded all the time for a user */
@RTable
case class User(
  userName: String,
  firstName: String,
  lastName: String,
  yob: Int,
  email: String,   // encrypted
  emailLower:Option[String] = None, //encrypted
  pwd: String,     // encrypted

  // this is per realm
  status: Char = Users.ACTIVE, // a-active, s-suspended, d-deleted
  roles: Set[String], // = Set("Racer"),
//  authRealm : Option[String] = None, // None - diesel, oauth - configured external oauth service

  realms: Set[String]=Set(), // = RK modules (notes, rk, ski etc)
  addr: Option[String] = None, // address as typed in
  prefs: Map[String, String] = Map(), // user preferences
  gid: Option[String] = None, // google id
  modNotes: Seq[String] = Seq.empty, // google id
  clubSettings : Option[String] = None, // if it's a club - settings

  organization : Option[String] = None, // possibly name of org
  authClient : Option[String] = None, // the client that authorized this user (oauth)
  authRealm : Option[String] = None, // the realm that authorized this user (oauth)
  authMethod : Option[String] = None, // how was it authorized 'password'/'X-Api-Key'/oauth etc

  perms: Set[String] = Set(),

  realmSet: Map[String, UserRealm] = Map(), // per realm
  consent:Option[String] = None,
  apiKey:Option[String] = Some(new ObjectId().toString),

  crDtm:  Option[DateTime] = None,
  updDtm: Option[DateTime] = None,

  _id: ObjectId = new ObjectId()) extends WikiUser with TPersonInfo {

  var token : Option[Map[String,Any]] = None

  def withToken (t:Map[String,Any]) = {
    this.token = Option(t)
    this
  }

  def emailDec = email.dec

  def gender = "?"
  def notifyParent = false

  def isInRealm(realm:String) : Boolean = isAdmin || realms.contains(realm)

  override def ename = if (firstName != null && firstName.size > 0) firstName else emailDec.replaceAll("@.*", "")

  def tasks = Users.findTasks(_id)

  // OTHER isXXX inherited from WikiUser

  override def isActive = status == 'a'
  override def isSuspended = status == 's'
  def isClub = roles contains UserType.Organization.toString
  def isUnder13 = DateTime.now.year.get - yob <= 12

  /** is this a group admin for a group the page belongs to ? */
  def canAdmin (we:WikiEntry) : Boolean = isAdmin //||
//    we.wid.parentOf(WikiDomain(we.realm).isA("Club", _)).flatMap(Club.apply).exists(_.isClubAdmin(this))

  /** is this a group admin for a group the page belongs to ? */
  def canAdmin (wid:WID) : Boolean = isAdmin || wid.page.exists(canAdmin)

  /** Harry is a default suspended account you can use for demos */
  def isHarry = id == "4fdb5d410cf247dd26c2a784"

  // TODO optimize - cache conditions, they are used a lot

  def allPerms: Set[String] = perms ++ groups.flatMap(_.can)
  def hasPerm(p: Perm) =
    allPerms.contains("+" + p.s) &&
        !allPerms.contains("-" + p.s) &&
        !allPerms.contains("+" + Perm.Expired.s) ||
  {
    // if not found, see about special permission hierarchies
    if(p.equals(Perm.uWiki)) {
      hasPerm(Perm.adminWiki) || hasPerm(Perm.adminDb)
    }
    else false
  }

  def isExpired = allPerms.contains("+" + Perm.Expired.s)

  override def membershipLevel : String =
    if   (this.hasPerm(Perm.Expired)) Perm.Expired.s
    else nonExpiredMembershipLevel

  /** last membership before expired */
  def nonExpiredMembershipLevel : String =
      if (this.hasPerm(Perm.Moderator) || this.isAdmin) Perm.Moderator.s
      else if (this.hasPerm(Perm.Unobtanium)) Perm.Unobtanium.s
      else if (this.hasPerm(Perm.Platinum)) Perm.Platinum.s
      else if (this.hasPerm(Perm.Gold)) Perm.Gold.s
      else if (this.hasPerm(Perm.Basic)) Perm.Basic.s
      else Perm.Member.s

  override def hasMembershipLevel(s:String) =
    (s == Perm.Member.s) ||
    (this.isExpired && s == Perm.Expired.s) ||
    (!this.isExpired && s == Perm.Moderator.s && (this.hasPerm(Perm.Moderator) || this.isAdmin)) ||
    (!this.isExpired && s == Perm.Unobtanium.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Unobtanium))) ||
    (!this.isExpired && s == Perm.Platinum.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Unobtanium) || this.hasPerm(Perm.Platinum))) ||
    (!this.isExpired && s == Perm.Gold.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Unobtanium) || this.hasPerm(Perm.Platinum) || this.hasPerm(Perm.Gold))) ||
    (!this.isExpired && s == Perm.Basic.s && (this.hasPerm(Perm.Moderator) || this.hasPerm(Perm.Unobtanium) || this.hasPerm(Perm.Platinum) || this.hasPerm(Perm.Gold) || this.hasPerm(Perm.Basic)))

  // centered on Toronto by default
  lazy val ll = addr.flatMap(Maps.latlong _).getOrElse(("43.664395", "-79.376907"))

  /** can move around without restrictions */
  lazy val canHasProfile = (!isUnder13) || Users.findParentOf(_id).exists(_.trust == "Public")

  // TODO cache groups
  lazy val groups = roles flatMap(role=> ROne[UserGroup]("name" -> role))

  /** make a default profile */
  def mkProfile = Profile(this._id, None, Set())

  /** relationships */
  def rel(r: String): List[User] = profile.map(p =>
    (for (t <- p.relationships if (t._2 == r))
      yield t._1).flatMap(Users.findUserById(_)).toList) getOrElse List()

  /** load my profile */
  lazy val profile = Users.findProfileByUserId(_id.toString)

  /** the wikis I linked to */
  lazy val wikis = RMany[UserWiki]("userId" -> _id).toList
  lazy val clubs =
    wikis.filter(uw=>WikiDomain(uw.uwid.getRealm).isA("Club", uw.uwid.cat)).toList

  // perf issue - checked for each user logged in
  def isLinkedTo(uwid:UWID) =
    wikis.filter(uw=>uw.uwid.cat==uwid.cat && uw.uwid.id == uwid.id)

  /** pages of category that I linked to */
  def pages(realm: String, cat: String*) = wikis.filter { w =>
    (cat.contains("*") || cat.contains(w.uwid.cat)) &&
        (realm == "*" || realm == w.uwid.getRealm || WikiReactors(realm).supers.contains(w.uwid.getRealm))
  }

  override def myPages(realm: String, cat: String) = pages(realm, cat)

  // add owned pages for backwards issue with Razie's old reactors
  lazy val memberReactors = (realms.toList ::: ownedPages("rk", "Reactor").map(_.name).toList).distinct

  def memberReactorsSameOrg(realm: String) = {
    memberReactors
  }

  lazy val memberReactorsWithOrg :List[(String,String)] = getmemberReactorsWithOrg()

  def getmemberReactorsWithOrg():List[(String,String)]  = {
    // todo complete this
//        val org = Website
//        .forRealm(realm)
//        .flatMap(_.prop("org"))
//        .toList
//        .flatMap(org =>
    memberReactors.map {r=>
      val org = Website
          .forRealm(r)
          .flatMap(_.prop("org"))
          .mkString
      (org, r)
    }
  }

  def ownedPages(realm: String, cat: String) =
    Wikis(realm).weTable(cat).find(
      Map(
        "props.owner" -> id,
        "category" -> cat)
    ) map { o =>
      WID(cat, o.getAs[String]("name").get).r(o.getAs[String]("realm").get)
    }

  def auditCreated(realm: String): Unit = {
    Log.audit("USER_CREATED " + email + " realm: " + realm)
  }

  def auditLogout(realm: String): Unit = {
    Log.audit("USER_LOGOUT " + email + " realm: " + realm)
  }

  def auditLogin(realm: String): Unit = {
    Log.audit("USER_LOGIN " + email + " realm: " + realm)
  }

  def auditLoginFailed(realm: String, count: Int = -1): Unit = {
    Log.audit(s"USER_LOGIN_FAILED $email - realm: $realm - count: $count")
  }

  lazy val key = Map("email" -> email)

  def create(p: Profile): Unit = {
    var res = Users.createUser(this.copy(crDtm = Some(DateTime.now())))

    razie.Log.info(s"Created user, res=$res")

    p.createdDtm = DateTime.now()
    p.lastUpdatedDtm = DateTime.now()
    res = Users.createProfile(p)
    razie.Log.info(s"Created user profile, res=$res")

    UserEvent(_id, "CREATE").create
  }

  def update(newu: User): Unit = {
    RazMongo("UserOld") += grater[User].asDBObject(Audit.create(this))
    Users.updateUser(this, newu)
    UserEvent(_id, "UPDATE").create
  }

  def toJson = {
    grater[User].asDBObject(this).toString
  }

  def toJsonSafe = {
    val o = grater[User].asDBObject(this)
//    o.put("mongoId", o.get("_id"))
//    o.put("_id", this._id.toString)
    o.toString
  }

  def shouldEmailParent(what: String) = {
    if (isUnder13) {
      for (
        pc <- Users.findParentOf(this._id) if (pc.notifys == what);
        parent <- Users.findUserById(pc.parentId)
      ) yield parent
    } else None
  }

  def hasRealm(m:String) = realms.contains(m) || (realms.isEmpty && Wikis.RK == m)

  def quota = ROne[UserQuota]("userId" -> _id) getOrElse UserQuota(_id)

  var css = prefs.get("css")

  def mapRS(realm:String)(f: UserRealm => UserRealm) = {
    // if not there, create record for this realm
    val x = if(realmSet.contains(realm) || "*" == realm)
      this
    else {
      this.copy(
        realmSet =
          this.realmSet ++
          Seq((realm -> UserRealm( status, roles, perms, prefs - "dieselEnv", modNotes, consent, Some(DateTime.now) )))
        // don't copy the dieselEnv
      )
    }

    x.copy(
        realmSet = x.realmSet.map { rs =>
        if (rs._1 == realm || "*" == realm) (rs._1, f(rs._2))
        else rs
      })
  }

  def consented(realm:String, ver: String) = mapRS(realm) {rs=>
    rs.copy(consent = Some(ver + " on " +DateTime.now().toString()))
  }

  def hasConsent(realm:String) =
    this.consent.isDefined ||
      this.realmSet.get(realm).orElse(this.realmSet.get("rk")).exists(_.consent.isDefined)

  def clearConsent(realm:String) = mapRS(realm) {rs=>
    rs.copy(consent = None)
  }

  def addPerm(realm:String, t: String) = mapRS(realm) {rs=>
    rs.copy(perms = rs.perms + (if(t.startsWith("+")) t else "+"+t))
  }

  def removePerm(realm:String, t: String) = mapRS(realm) {rs=>
    rs.copy(perms = rs.perms - (if(t.startsWith("+")) t else "+"+t))
//    rs.copy(perms = rs.perms - t)
  }

  def forRealm(realm:String) = {
    // default to rk for backwards comp for old users wihtout realms
    this.realmSet.get(realm).orElse(this.realmSet.get("rk")).map{rs=>
      this.copy(
        status = rs.status,
        roles = rs.roles,
        prefs = rs.prefs,
        perms = rs.perms,
        modNotes = rs.modNotes,
        consent = rs.consent
      )
    }.getOrElse(this)
  }

  def getPrefs(name: String, default:String="") = prefs.get(name).getOrElse(default)
  def setPrefs(realm:String, p:Map[String,String]) = mapRS(realm) {rs=>
    rs.copy(prefs = rs.prefs ++ p)
  }

  override def realmPrefs(realm:String) =
    realmSet.get(realm).map(_.prefs).getOrElse(Map.empty)

  def setRoles(realm:String, s: String) = mapRS(realm) {rs=>
    rs.copy(roles = s.split("[, ]").toSet)
  }

  def setRoles(realm:String, s: Set[String]) = mapRS(realm) {rs=>
    rs.copy(roles = s)
  }

  def setRealms(realm:String, s: String) = {
    this.copy(realms = s.split("[, ]").toSet)
  }

  def setStatus(realm:String, s: String) = mapRS(realm) {rs=>
    rs.copy(status = s(0))
  }

  def addModNote(realm:String, note: String) = mapRS(realm) {rs=>
    rs.copy(modNotes = rs.modNotes ++ Seq(note))
  }

  def removeModNote(realm:String, note: String) = mapRS(realm) {rs=>
    rs.copy(modNotes = rs.modNotes.filter(_ != note))
  }

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
  aboutMe: Option[WikiEntry] = None,
  relationships: Map[String, String] = Map(), // (who -> what)
  contact: Option[Contact] = None,

  newExtLinks : Seq[ExtSystemUserLink]= Seq(),

  _id: ObjectId = new ObjectId()) {

  def update(p: Profile): Unit =  Users.updateProfile(p)

  def addRel(t: (String, String)) = this.copy(relationships = relationships ++ Map(t))
  def addTag(t: String) = this.copy(tags = tags + t)
  def setContact(c: Contact) = this.copy(contact = Some(c))

  def toJson = {
    grater[Profile].asDBObject(this).toString
  }

  def toJsonSafe = {
    val o = grater[Profile].asDBObject(this)
//    o.put("mongoUserId", this._id.toString)
//    o.put("mongoId", o.get("_id"))
//    o.put("key", this._id.toString)
    o.toString
  }

  var createdDtm: DateTime = DateTime.now
  var lastUpdatedDtm: DateTime = DateTime.now

  def getExtLink (realm:String, systemId:String, instanceId:String) =
    newExtLinks.find(x=> x.realm == realm && x.extSystemId == systemId && x.extInstanceId == instanceId)

  /** add or update existing link */
  def upsertExtLink (realm:String, link:ExtSystemUserLink) = {
    val e1 = newExtLinks.filter(x=> !( x.realm == realm && x.extSystemId == link.extSystemId && x.extInstanceId == link.extInstanceId))
    val e2 = e1 ++ Seq(link)
    this.copy(newExtLinks = e2)
  }
}

/** link to an external system account */
case class ExtSystemUserLink (
  realm:String,
  extSystemId : String,    // external system id
  extInstanceId : String,  // external system id
  extAccountId : String    // external account id
  )

/** settings per realm */
case class UserRealm (
  status: Char = Users.ACTIVE, // a-active, s-suspended, d-deleted
  roles: Set[String], // like groups perms etc

  perms: Set[String] = Set(),

  prefs: Map[String, String] = Map(), // user preferences
  modNotes: Seq[String] = Seq.empty,
  consent:Option[String] = None,
  crDtm: Option[DateTime] = None
  ) {
  }
