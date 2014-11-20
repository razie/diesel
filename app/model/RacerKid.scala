package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log
import controllers.UserStuff
import model.Sec._
import controllers.Maps
import controllers.RazController
import admin.MailSession
import controllers.Emailer
import db.RTable
import scala.annotation.StaticAnnotation
import db.ROne
import db.RMany
import db.RCreate
import db.RDelete
import db.RUpdate
import razie.|>._
import db.REntity
import controllers.Club
import admin.Config

/** a user that may or may not have an account - or user group */
trait TRacerKidInfo {
  def firstName: String
  def lastName: String
  def email: String
  def yob: Int
  def gender: String // M/F/?
  def roles: Set[String]
  def status: Char
  def parents: Set[ObjectId]
  def notifyParent: Boolean
  def _id: ObjectId

  def ename = if (firstName != null && firstName.size > 0) firstName else email.dec.replaceAll("@.*", "")
  def role = roles.head
}

/** a user that may or may not have an account - or user group */
@db.RTable
case class RacerKidInfo(
  firstName: String,
  lastName: String,
  email: String, // uniquely identifies the kid
  dob: DateTime,
  gender: String, // M/F/?
  roles: Set[String], // Racer, Parent, Coach, Spouse
  status: Char,
  notifyParent: Boolean, // notify parent instead of kid
  rkId: ObjectId, // who is my resolving rkId - these may change when conflicts are resolved
  ownerId: ObjectId, // owner user id - user that created/owns this info: parent or club or ... even club admin?
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RacerKidInfo] with TRacerKidInfo {

  def yob: Int = dob.getYear()

  def parents: Set[ObjectId] = RMany[RacerKidAssoc]("to" -> _id, "what" -> RK.ASSOC_PARENT).map(_.from).toSet
}

/**
 * a user that may or may not have an account - or user group
 *
 *  references to this are named rkId
 *
 *  User -> Assoc(Myself) -> RK(Myself) -> User(?)
 *  User -> Assoc(Parent) -> RK(Normal) -> RKI (Kid)
 *  User -> Assoc(Parent) -> RK(Normal) -> RKI (Spouse)
 *
 */
@db.RTable
case class RacerKid(
  ownerId: ObjectId, // owner user id - user that created/owns this info: parent or club or ... even club admin?
  userId: Option[ObjectId] = None, // populated if this represents a site user
  rkiId: Option[ObjectId] = None, // populated if there is a record manually entered for this
  newRkId: Option[ObjectId] = None, // TODO use this - idea is reconciliation
  oldRkId: Seq[ObjectId] = Seq.empty, // these are the ones I override
  kind: String = RK.KIND_NORMAL,
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RacerKid] {

  lazy val info: TRacerKidInfo = rki orElse user getOrElse RacerKidz.JoeDoe

  lazy val rki = rkiId flatMap RacerKidz.findByRkiId
  lazy val user = userId flatMap model.Users.findUserById

  /** the wikis I linked to */
  lazy val wikis = RMany[RacerWiki]("rkId" -> _id).toList

  /** pages of category that I linked to */
  def pages(cat: String) = wikis.filter(_.wid.cat == cat)
  def myPages(cat: String) = pages(cat)

  // for email, user account overrides record
  def email = user orElse rki map (_.email)

  //TODO unique
  lazy val all:List[RacerKid] = (this :: RMany[RacerKid]("newRkId"->_id).toList ::: oldRkId.toList.flatMap(_.as[RacerKid].toList))
}

/**
 * a link between a racer and a wiki (tribe/team etc)
 *
 *  @param regStatus is one of RegStatus or None, which means not required or n/a (a fan or something)
 */
case class RacerWiki(
  rkId: ObjectId,
  wid: WID,
  role: String,
  _id: ObjectId = new ObjectId()) extends REntity[RacerWiki] {

  def rk = rkId.as[RacerKid]
}

/** relationships between RK from -Parent-> to */
@db.RTable
case class RacerKidAssoc(
  from: ObjectId, // User: parent, owner, club...
  to: ObjectId, // RacerKid: kid
  assoc: String, // why am i associated... the source
  role: String, // role of "to" in the association: Racer, Coach, Parent, Spouse
  owner: ObjectId, // who owns this assoc
  hours: Int = 0, // volunteer hours
  year: String = Config.curYear, // relevant for club memberships only
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RacerKidAssoc] {

  def user = from.as[User]
  def rk = to.as[RacerKid]
}

/** volunteer hours */
@db.RTable
case class VolunteerH(
  rkaId: ObjectId, // association for which I track volunteer events
  hours: Int, // hours in this entry
  desc: String, // description of work done
  by: ObjectId, // user that created the entry
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId) extends REntity[VolunteerH] {
}

/** racer kid info utilities */
object RK {
  // relationships
  final val ROLE_KID = "Kid"
  final val ROLE_SPOUSE = "Spouse"
  final val ROLE_PARENT = "Parent"

  // roles in assoc
  final val ROLE_RACER = "Racer"
  final val ROLE_ME = "Me"
  final val ROLE_COACH = "Coach"
  final val ROLE_VOLUNTEER = "Volunteer"
  final val ROLE_MEMBER = "Member"
  final val ROLE_FAN = "Fan" // for former members and others

  final val RELATIONSHIPS = Array(ROLE_KID, ROLE_SPOUSE, ROLE_PARENT, ROLE_MEMBER)
  final val ROLES = Array(ROLE_RACER, ROLE_COACH, ROLE_MEMBER, ROLE_FAN)

  final val KIND_NORMAL = "Norm"
  final val KIND_MYSELF = "Myself"
  final val KIND_CORRELATION = "Corr"

  // these are not actually used...
  final val STATUS_ACTIVE = 'a'
  final val STATUS_FORMER = 'f'
  final val STATUS_SUSPENDED = 's'

  final val ASSOC_PARENT = "Parent" // defined by me (looking down) should die when reconciled with CHILD
  final val ASSOC_CHILD = "Child" // when user kids were added to me as kids - should survive when reconciled with PARENT (looking up)
  final val ASSOC_REGD = "Registered" // kid racer to club
  final val ASSOC_SPOUSE = "Spouse"  // defined by me
  final val ASSOC_MYSELF = "Myself" // defined automatically
  final val ASSOC_LINK = "Linked" // accepted as member to club on "like/follow"
  final val ASSOC_ARCHIVE = "Archived" // user no longer club member

  lazy val noid = new ObjectId()
}

/** racer kid info utilities */
object RacerKidz {
  def findById(rkId: ObjectId) = ROne[RacerKid]("_id" -> rkId)
  def findByIds(rkId: String) = ROne[RacerKid]("_id" -> new ObjectId(rkId))

  def findAssocById(rkaId: String) = ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId))

  def findByParentUser(id: ObjectId) = {
    val mine = RMany[RacerKidAssoc]("from" -> id, "assoc" -> RK.ASSOC_PARENT) map (_.to) flatMap (findById)
    //	  val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }
  def findByParentRK(id: ObjectId) = RMany[RacerKidAssoc]("from" -> id, "assoc" -> RK.ASSOC_PARENT) map (_.to) flatMap (findById)

  /** find all RK associated to user */
  def findAssocForUser(id: ObjectId) = {
    val mine = RMany[RacerKidAssoc]("from" -> id)
    //	  val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }

  /** find all RK associated to user */
  def findForUser(id: ObjectId) =
    findAssocForUser(id) map (_.to) flatMap (findById)

  def findAssocByClub(club: Club) = {
    RMany[RacerKidAssoc]("from" -> club.userId, "year" -> club.curYear) //, "what" -> RK.ASSOC_REGD)
  }

  def findByClub(club: Club) = {
    val mine = findAssocByClub(club) map (_.to) flatMap (findById)
    //	  val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }

  def myself(userId: ObjectId) = {
    // TODO creating the RK for myself
    if (!ROne[RacerKid]("userId" -> Some(userId)).isDefined) {
      val rk = RacerKid(userId, Some(userId), None, None, Seq.empty, RK.KIND_MYSELF)
      rk.create
      RacerKidAssoc(userId, rk._id, RK.ASSOC_MYSELF, RK.ASSOC_MYSELF, userId).create
    }
    val rk = ROne[RacerKid]("userId" -> Some(userId)).get
    rk
  }

  def checkMyself(userId: ObjectId) { myself(userId) }

  def findByRkiId(id: ObjectId) = ROne[RacerKidInfo]("_id" -> id)

  def findVolByRkaId(rkaid: String) = RMany[VolunteerH]("rkaId" -> new ObjectId(rkaid))

  def findByParentReg(reg: Reg) = {
    val kidz = findByParentUser(reg.userId)
  }

  final val JoeDoe = RacerKidInfo("Joe", "Doe", "", DateTime.parse("2000-01-15"), "M", Set.empty, RK.STATUS_ACTIVE, false, RK.noid, RK.noid)
  final val empty = RacerKidInfo("", "", "", DateTime.parse("2000-01-15"), "M", Set.empty, RK.STATUS_ACTIVE, true, RK.noid, RK.noid)
}
