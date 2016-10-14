/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import mod.snow.{RoleWid, RacerKidz, RacerKid}
import razie.wiki.model.{UWID, WID}

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.Sec.EncryptedS
import model.User
import play.api.data.Form
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import razie.cout
import admin.Config
import razie.db.RMongo
import play.api.mvc.AnyContent
import play.api.mvc.Result
import razie.db.RDelete

/** per topic reg */
case class ModRkReg(
  wid: WID,
  curYear: String = Config.curYear) {
//  lazy val kids = RMany[ModRkEntry]("curYear" -> curYear, "wpath" -> wid.wpath).toList
  lazy val kids =
    RMany[ModRkEntry]("wpath" -> wid.wpath).toList ++ // old recs
    wid.uwid.toList.flatMap {uwid=>
      RMany[ModRkEntry]("uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).toList
    }
}

/** per topic reg */
@RTable
case class ModRkEntry(
  rkId: ObjectId,
  wpath: Option[String],      // todo for old records - purge them and code at some point
  uwid : Option[UWID] = None, // for new records
  role: String,
  note: Option[String] = None,
  attended: Option[String] = None, // any value means yes basically
  curYear: String = Config.curYear,   // just a notional value, UWID matters
  forms: Seq[RoleWid] = Seq.empty,    // future possiblity to have reg forms per event
  _id: ObjectId = new ObjectId) extends REntity[ModRkEntry] {

  // optimize access to User object
  lazy val rk = rkId.as[RacerKid]
}

/** controller for club management */
object ModRk extends RazController with Logging {

  import razie.db.RMongo.as

  def regd (au:User, wid:WID) = ModRkReg(wid).kids.map(x => (x, x.rkId.as[RacerKid].get)).toList
  def rks (au:User, wid:WID) = RacerKidz.rka(au).map(x => (x, x.rk.get)).toList

  def doeModRkRegs(wid: WID) = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      page <- wid.page
    ) yield {
      val regd = ModRkReg(page.wid).kids.map(x => (x, x.rkId.as[RacerKid].get)).toList
      val rks = RacerKidz.rka(au).map(x => (x, x.rk.get)).toList

      Ok(views.html.modules.doeModRkRegs(au, page, ModRkReg(page.wid), regd, rks))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeModRkAdd(wid: WID, rkid: String, role: String) = FAUR { implicit stok =>
    ModRkEntry(new ObjectId(rkid), None, wid.uwid, role).create
    Redirect(Wiki.w(wid))
  }

  def doeModRkRemove(wid: WID, rkid: String) = FAUR { implicit stok =>
    wid.uwid.map{uwid=>
      ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).foreach(_.delete)
      //todo delete forms to, if any
    }

    // try also old records with wpath
    ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "wpath" -> wid.wpath).foreach(_.delete)

    Redirect(Wiki.w(wid))
  }
}

