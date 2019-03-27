package mod.snow

import admin.Config
import com.mongodb.casbah.Imports._
import com.novus.salat._
import controllers.Club
import org.joda.time.DateTime
import razie.db._
import razie.wiki.Sec._
import razie.wiki.model._
import model.{TPersonInfo, User, Users}
import razie.audit.Audit
import razie.diesel.dom.WikiDomain

/** a history element
  *
  * roles are:
  * - post is notification: if links to a post of some kind,
  * - bcast is a simple broadcast message
  */
case class RkHistory (
  rkId: ObjectId, // to: the kid this belongs to
  authorId: Option[ObjectId], // from: who created this
  eId: Option[ObjectId],   // the object this points to
  eKind:String,    // the type of object this points to
  role:String,    // the role this element plays
  ctx:String, // the context at the time
  important: Option[String] = None, // important ones shold float at top, like announcements
  replyTo:Option[ObjectId]=None, // reply to eId
  content:Option[String]=None, // reply to eId
  tags:Option[String]=None, // reply to eId
  crDtm: DateTime = DateTime.now,
  toRkId: Option[ObjectId] = None, // If this is a not/message TO someone, then this is to whom
  _id: ObjectId = new ObjectId()) extends REntity[RkHistory] {
}

/** the news is cached in User - make sure to cleanAuth */
case class RkHistoryFeed (
  rkId:ObjectId, // user or RK or parentWID
  news:Int, // count new items since last seen
  firstTime:Option[Boolean]=None, // this is set in rk.history
  _id: ObjectId = new ObjectId()) extends REntity[RkHistoryFeed] {

  def items (howMany:Int = -1) :List[RkHistory] =
    RMany.sortLimit[RkHistory](Map("rkId" -> rkId), Map("crDtm" -> -1), howMany)

//        .toList.sortWith((a,b)=>a.crDtm.compareTo(b.crDtm) >= 0).toList.take(howMany)

  def post(we:WikiEntry, au:User, text:Option[String]=None) = {
    // don't bother posting if user not reading...
    if(news < 10) {
      val h = RkHistory(
        rkId,
        Some(au._id),
        Some(we._id),
        we.category,
        "post",
        ""
      ).copy(content = text)
      h.createNoAudit(tx.auto)
      inc
    }
  }

  private def inc = this.copy(news=news+1).updateNoAudit(tx.auto)
  def reset = this.copy(news=0, firstTime=None).updateNoAudit(tx.auto)

  def add(h:RkHistory, shouldInc:Boolean=true) = {
    h.create(tx.auto)
    if(shouldInc) inc
  }

  def findByElement(eid:String) = {
    RMany[RkHistory]("rkId" -> rkId, "eId" -> new ObjectId(eid)).toList
  }

  def findByRole(role:String) = {
    RMany[RkHistory]("rkId" -> rkId, "role" -> role).toList
  }

  def delete(h:RkHistory) = {
    h.deleteNoAudit(tx.auto)
  }
}

/** a user that may or may not have an account - or user group */
@RTable
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
  _id: ObjectId = new ObjectId()) extends REntity[RacerKidInfo] with TPersonInfo {

  def organization : Option[String] = None

  def emailDec : String = email.dec

  def yob: Int = dob.getYear()
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
@RTable
case class RacerKid(
  ownerId: ObjectId, // owner user id - user that created/owns this info: parent or club or ... even club admin?
  userId: Option[ObjectId] = None, // populated if this represents a site user
  rkiId: Option[ObjectId] = None, // populated if there is a record manually entered for this
  newRkId: Option[ObjectId] = None, // TODO use this - idea is reconciliation
  oldRkId: Seq[ObjectId] = Seq.empty, // these are the ones I override
  kind: String = RK.KIND_NORMAL,
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RacerKid] {

  lazy val info: TPersonInfo = rki orElse user getOrElse RacerKidz.JoeDoe

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

  def history = {
    ROne[RkHistoryFeed]("rkId"-> _id).getOrElse {
      var h = new RkHistoryFeed(_id, 0, Some(true))
      h = h.copy(news=h.items(-1).size)
      h.create(tx.auto)
      h
    }
  }

  // all teams in Club, that I'm in role
  def teams (club:Club, role:String) =
    RacerKidz.findWikiAssocById(_id.toString, club.curYear, "Program").filter(t=>
      role.isEmpty || t.role == role
    ).filter(t=>
      t.uwid.wid.exists(_.parentOf(WikiDomain(club.wid.getRealm).isA("Club", _)).exists(_.name == club.userName))
    )

  def rka = RMany[RacerKidAssoc]("to" -> _id)
  def rkwa = RMany[RacerKidWikiAssoc]("rkId" -> _id)

  def clubs =
   rka.map(_.from).toList.distinct.flatMap(Club.findForUserId).toList//.filter(_.curYear == rka.year)
//    for(
//      assoc <- rka;
//  .map(_.from).toList.distinct.flatMap(Club.findForUserId).toList.filter(_.curYear == rka.year)

  def parents: Set[ObjectId] = RMany[RacerKidAssoc]("to" -> _id, "assoc" -> RK.ASSOC_PARENT).map(_.from).toSet
  def parentUsers: Seq[User] = parents.flatMap(_.as[User].toList).toSeq
  def usersToNotify: List[ObjectId] = userId.toList ++ parents.toList
  // list of all persons of interest with non-empty emails
  def personsToNotify: List[TPersonInfo] = {
//    (userId.toList ++ parents.toList).flatMap(_.as[User]) ++ List(info) filter(_.email.dec.length > 0)
    var x : List[TPersonInfo] = parents.toList.flatMap(_.as[User])
    val u = userId.toList.flatMap(_.as[User])
    x = x ++ u.filter(u=> x.find(_.emailDec == u.emailDec).isEmpty)
    if(x.find(_.emailDec == info.emailDec).isEmpty)
      x = info :: x
    x.filter(_.emailDec.length > 0)
  }
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
@RTable
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

  override def delete(implicit txn: Txn) = {
    RacerKidz.findVolByRkaId(_id.toString).foreach(_.delete)
    RacerKidz.findWikiAssocByRk(to).foreach(_.delete)
    RDelete[RacerKidAssoc](this)
  }
  override def deleteNoAudit(implicit txn: Txn) = {
    RacerKidz.findVolByRkaId(_id.toString).foreach(_.deleteNoAudit)
    RacerKidz.findWikiAssocByRk(to).foreach(_.deleteNoAudit)
    RDelete.noAudit[RacerKidAssoc](this)
  }

  /** move everything off to another assoc - used when merging */
  def moveTo(lives:RacerKidAssoc)(implicit txn:Txn) = {
    RacerKidz.findVolByRkaId(_id.toString).foreach(_.copy(rkaId = lives._id).update)
    RDelete[RacerKidAssoc](this)
  }

  def user = from.as[User]
  def rk = to.as[RacerKid]
  def club = from.as[User] map Club.apply // for now using
}

/** relationships between RK from -Parent-> to
  *
  * team membership and such
  */
@RTable
case class RacerKidWikiAssoc(
  rkId: ObjectId,
  uwid: UWID, // to
  year: String, // relevant for club memberships only
  role: String, // role of "to" in the association: Racer, Coach, Parent, Spouse
  assoc:String = mod.snow.RK.ASSOC_LINK, // why am i associated
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RacerKidWikiAssoc] {

  def rk = rkId.as[RacerKid]
}

/** volunteer hours */
@RTable
case class VolunteerH(
  rkaId: ObjectId, // association for which I track volunteer events
  hours: Int, // hours in this entry
  desc: String, // description of work done
  comment: String, // details/comments
  by: ObjectId, // user that created the entry
  approver: Option[String]=None, // user that will approve: email or userName or userId
  status: String=VH.ST_WAITING, // user that will approve: email or userName or userId
  approvedBy: Option[ObjectId]=None, // user that approved the entry
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId) extends REntity[VolunteerH] {
}

object VH {
  final val ST_OK = "Ok"
  final val ST_WAITING = "Waiting"
  final val ST_REJECTED = "Rejected"
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
  final val ROLE_GUEST = "Guest"
  final val ROLE_OTHER = "Other"
  final val ROLE_FAN = "Fan" // for former members and others

  def RELATIONSHIPS = WikiDomain(Wikis.RK).roles("User", "Person").toArray
//    Array(ROLE_KID, ROLE_PARENT, ROLE_SPOUSE, ROLE_MEMBER, ROLE_GUEST, ROLE_OTHER)
  def ROLES(wid:WID) = WikiDomain(wid.getRealm).roles(wid.cat, "Person").toArray

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
  final val ASSOC_INVITED = "Invited" // user no longer club member

  lazy val noid = new ObjectId()


}

/** racer kid info utilities */
object RacerKidz {
  def findById(rkId: ObjectId) = ROne[RacerKid]("_id" -> rkId)
  def findByIdAndRole(rkId: ObjectId, role:String) = ROne[RacerKid]("_id" -> rkId)
  def findByIds(rkId: String) = ROne[RacerKid]("_id" -> new ObjectId(rkId))

  def findAssocById(rkaId: String) = ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId))

  def findWikiAssocs(year:String, uwid:UWID) =
    RMany[RacerKidWikiAssoc]("year"->year, "uwid.cat"->uwid.cat, "uwid.id"->uwid.id)

  def findWikiAssocs(year:String, cat:String) =
    RMany[RacerKidWikiAssoc]("year"->year, "uwid.cat"->cat)

  def findWikiAssocById(rkId: String, year:String, cat:String) =
    RMany[RacerKidWikiAssoc]("rkId" -> new ObjectId(rkId), "year"->year, "uwid.cat"->cat)

  def findWikiAssocByRk(rkId: ObjectId) =
    RMany[RacerKidWikiAssoc]("rkId" -> rkId)

  def findByParentUser(id: ObjectId) = {
    val mine = RMany[RacerKidAssoc]("from" -> id, "assoc" -> RK.ASSOC_PARENT) map (_.to) flatMap (findById)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }
  def findByParentRK(id: ObjectId) = RMany[RacerKidAssoc]("from" -> id, "assoc" -> RK.ASSOC_PARENT) map (_.to) flatMap (findById)

  /** find all RK associated to user */
  def findAssocForUser(id: ObjectId) = {
    val mine = RMany[RacerKidAssoc]("from" -> id)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }

  def findByUserId (userId:ObjectId) = ROne[RacerKid]("userId" -> Some(userId)).get

  def findAllForUser(id: ObjectId) =
    findAssocForUser(id) map (_.to) flatMap (findById)

  def myself(userId: ObjectId) : RacerKid = {
    // TODO creating the RK for myself
    var rk = ROne[RacerKid]("userId" -> Some(userId))
    if (rk.isEmpty) {
      val nrk = RacerKid(userId, Some(userId), None, None, Seq.empty, RK.KIND_MYSELF)
      nrk.create(tx.auto)
      RacerKidAssoc(userId, nrk._id, RK.ASSOC_MYSELF, RK.ASSOC_MYSELF, userId).create(tx.auto)
      // sometimes I can't find the record right away...
//      rk = ROne[RacerKid]("userId" -> Some(userId))
      rk = Some(nrk)
    }
    rk.get
  }

  def checkMyself(userId: ObjectId) { myself(userId) }

  def findByRkiId(id: ObjectId) = ROne[RacerKidInfo]("_id" -> id)

  def findVolByRkaId(rkaid: String) = RMany[VolunteerH]("rkaId" -> new ObjectId(rkaid))

  def findByParentReg(reg: Reg) = {
    val kidz = findByParentUser(reg.userId)
  }

  def rkwa(
            rkId: ObjectId,
            uwid: UWID, // to
            year: String, // relevant for club memberships only
            role: String, // role of "to" in the association: Racer, Coach, Parent, Spouse
            assoc:String = mod.snow.RK.ASSOC_LINK
            )(implicit txn: Txn = tx.auto) = {
    //    RacerKidWikiAssoc(rkId, uwid, year, role, assoc).create
    // enable when moving from RKA to RKA for club membership
  }

  /** find all RKA associated to user */
  def rka (u:User) = {
    val mine = RMany[RacerKidAssoc]("from" -> u._id)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }
  /** find all RK associated to user */
  def rk (u:User) = rka(u) flatMap (_.rk)

  def rmHistory(id:ObjectId, role:String="post")(implicit txn:Txn) : Unit ={
    val c = RMany[RkHistory]("eId"->id, "role"->role).size
    RDelete[RkHistory]("eId"->id, "role"->role)
    if(c > 0) Audit.logdb("ENTITY_DELETE", s"$c RkHistory DELETED")
  }

  final val JoeDoe = RacerKidInfo("Joe", "Doe", "", DateTime.parse("2000-01-15"), "M", Set.empty, RK.STATUS_ACTIVE, false, RK.noid, RK.noid)
  final val empty = RacerKidInfo("", "", "", DateTime.parse("2000-01-15"), "M", Set.empty, RK.STATUS_ACTIVE, true, RK.noid, RK.noid)

//  CQRS.
}
