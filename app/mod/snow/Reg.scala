package mod.snow

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import razie.clog
import razie.db.RazSalatContext._
import com.mongodb.util.JSON
import razie.db._
import scala.annotation.StaticAnnotation
import razie.wiki.model._

/** registration set for a family */
@RTable
case class Reg(
  userId: ObjectId,
  clubName: String,
  club: WID=WID.empty,
  year: String,
  role: String,
  wids: Seq[WID],         // todo for old regs - remove eventually
  roleWids: Seq[RoleWid] = Seq(), // all forms in this reg
  regStatus: String,
  paid: String = "", // amount paid
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Reg] {

  def deprecatedWids: Seq[WID] = roleWids.map(_.wid)

  def updateRegStatus(newStatus: String)(implicit txn:Txn) = this.copy(regStatus = newStatus).update
  def updateRole(newRole: String)(implicit txn:Txn) = this.copy(role = newRole).update

  def kids = RMany[RegKid]("regId" -> _id)
  def roleOf (rkId : ObjectId) = kids.find(_.rkId == rkId).map(_.role)

  /** sum up all "fee" fields across all forms */
  def fee() = {
    try {
      val num = deprecatedWids.flatMap(_.page.toList).flatMap(_.form.fields.get("fee").map(_.value)).filter(_.matches("\\d+")).toList.map(_.toInt).sum
      f"$$ $num%,.2f"
    } catch {
      case _:Throwable => "ERR"
    }
  }
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
  wids: Seq[WID],                // deprecated
  roleWids: Seq[RoleWid]=Seq(),  
  role: String,    
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[RegKid] {

  def deprecatedwids: Seq[WID] = roleWids.map(_.wid)

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

  def findClubUser(clubwid: WID, userId: ObjectId) = RMany[Reg]("clubName" -> clubwid.name, "userId" -> userId)
  def findClubYear(clubwid: WID, year: String) = RMany[Reg]("clubName" -> clubwid.name, "year" -> year)
  def findClubUserYear(clubwid: WID, userId: ObjectId, year: String) = ROne[Reg]("clubName" -> clubwid.name, "userId" -> userId, "year" -> year)
  def findByUserId(uid: ObjectId) = RMany[Reg]("userId" -> uid)
  def findId(id: String) = ROne[Reg]("_id" -> new ObjectId(id))
  def findClub(clubwid: WID) = RMany[Reg]("clubName" -> clubwid.name)
  def findWid(wid: WID) = RMany[Reg]().filter(_.deprecatedWids.contains(wid)) // TODO optimize - really bad

  def findRkClub(userId: ObjectId, clubwid:WID) = RMany[Reg]("clubName" -> clubwid.name, "userId" -> userId) // TODO add curyear

  def upgradeAllRegs1 = {

    razie.db.tx("upgrades1", "?") { implicit txn =>
      RazMongo.upgradeMaybe("upgradeAllRegs1", Array.empty) {
        RMany[Reg]().foreach { r =>
          if (r.roleWids.isEmpty) {
            val rw = r.wids.map(x => RoleWid(
              x.page.flatMap(_.formRole).get,
              x))
            if (rw.size > 0) {
              r.copy(roleWids = rw).update
            }
          }
        }
      }

      // formState from content to prop
      RazMongo.upgradeMaybe("upgradeAllForms2", Array.empty) {
        Wikis("rk").pages("Form").foreach { p =>
          if (p.form.formState.nonEmpty) {
            val n = p.cloneProps(p.props ++ Map(FormStatus.FORM_STATE -> p.form.formState.get), p.by)
            p.update(n)
          }
        }
      }
    }
  }

  razie.db.tx("upgrades", "?") { implicit txn =>

    RazMongo.upgradeMaybe("upgradeAllRegs3", Array.empty) {
      RMany[RegKid]().foreach { r =>
        if (r.roleWids.isEmpty) {
          val rw = r.deprecatedwids.map(x => RoleWid(
            x.page.flatMap(_.formRole).get,
            x))
          if (rw.size > 0) {
            r.copy(roleWids = rw).updateNoAudit
          }
        }
      }
    }

    RazMongo.upgradeMaybe("upgradeAllRegs4", Array("upgradeAllRegs3")) {
      // remove deprecated wids
      RMany[Reg]().foreach { r =>
        if (!r.wids.isEmpty && r.roleWids.size == r.wids.size) {
          r.copy(wids = Seq.empty).updateNoAudit
        }
      }
      RMany[RegKid]().foreach { r =>
        if (!r.wids.isEmpty && r.roleWids.size == r.wids.size) {
          r.copy(wids = Seq.empty).updateNoAudit
        }
      }
    }

    RazMongo.upgradeMaybe("upgradeAllRegs7", Array("upgradeAllRegs4")) {
      RMany[Reg]().foreach { r =>
        clog << "Upgrading Reg " + r.toString
        r.copy(club = WID("Club", r.clubName).r("rk")).updateNoAudit
      }
    }

    RazMongo.upgradeMaybe("teamToProgram", Array("upgradeAllRegs7")) {
      RMany[RacerKidWikiAssoc]().foreach { r =>
        if (r.uwid.cat == "Program") {
          clog << "Upgrading RKWA " + r.toString
          r.copy(uwid = r.uwid.copy(cat = "Program")).updateNoAudit
        }
      }
      RMany[WikiEntry]().foreach { r =>
        if (r.category == "Team") {
          clog << "Upgrading RKWA " + r.toString
          RUpdate(r.copy(category = "Program"))
        }
      }
      RMany[WikiLink]().foreach { r =>
        if (r.from.cat == "Team") {
          clog << "Upgrading RKWA " + r.toString
          r.copy(from = r.from.copy(cat = "Program")).updateNoAudit
        }
      }
    }

    RazMongo.upgradeMaybe("teamToProgram2", Array("teamToProgram")) {
      RMany[RacerKidWikiAssoc]().foreach { r =>
        if (r.uwid.cat == "Program") {
          clog << "Upgrading RKWA " + r.toString
          r.copy(uwid = r.uwid.copy(cat = "Program")).updateNoAudit
        }
      }
    }
  }
}

