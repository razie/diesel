package mod.snow

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import razie.db.RazSalatContext._
import com.mongodb.util.JSON
import razie.db.RTable
import scala.annotation.StaticAnnotation
import razie.db.ROne
import razie.db.RMany
import razie.db.REntity
import razie.wiki.model.WID

/** registration set for a family */
@RTable
case class Reg(
  userId: ObjectId,
  clubName: String,
  year: String,
  role: String,
  wids: Seq[WID],
  regStatus: String,
  paid: String = "", // amount paid
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Reg] {

  def updateRegStatus(newStatus: String) = this.copy(regStatus = newStatus).update
  def updateRole(newRole: String) = this.copy(role = newRole).update

  def kids = RMany[RegKid]("regId" -> _id)
}

/**
 * RegKidz also have an association...
 *
 *  part of a big set, just for one racer
 */
@RTable
case class RegKid(
  regId: ObjectId,
  rkId: ObjectId,
  wids: Seq[WID],
  role: String, // one form per role
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RegKid] {

  def reg = Regs.findId(regId.toString)
  def rk = ROne[RacerKid]("_id" -> rkId)
}

/**
 * user - club registration status
 *
 * legend is in memgers1.scala.html - keep to date
 */
object RegStatus {
  final val CURRENT = "current" // reg is current, approved and up to date for season
  final val ACCEPTED = "accepted" // forms accepted, pending payment
  final val EXPIRED = "expired" // reg is expired - accompanied by a task to update it / create new one
  final val PENDING = "pending" // forms have been submitted - waiting
  final val FAMILY = "family" // reg is filed by another member (parent or spouse)
  final val DELETE = "delete" // transient request - it will remove the record
}

/** user factory and utils */
object Regs {

  def fromJson(j: String) = Option(grater[Reg].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  def findClubUser(clubName: String, userId: ObjectId) = RMany[Reg]("clubName" -> clubName, "userId" -> userId)
  def findClubYear(clubName: String, year: String) = RMany[Reg]("clubName" -> clubName, "year" -> year)
  def findClubUserYear(clubName: String, userId: ObjectId, year: String) = ROne[Reg]("clubName" -> clubName, "userId" -> userId, "year" -> year)
  def findId(id: String) = ROne[Reg]("_id" -> new ObjectId(id))
  def findClub(clubName: String) = RMany[Reg]("clubName" -> clubName)
  def findWid(wid: WID) = RMany[Reg]().filter(_.wids.contains(wid)) // TODO optimize - really bad

  def findRkClub(userId: ObjectId, clubName:String) = RMany[Reg]("clubName" -> clubName, "userId" -> userId) // TODO add curyear
}
