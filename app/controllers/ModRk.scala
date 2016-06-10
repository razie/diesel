/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import razie.wiki.model.WID
import razie.wiki.util.VErrors

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import model.RacerKid
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
  lazy val kids = RMany[ModRkEntry]("wpath" -> wid.wpath).toList
}

/** per topic reg */
@RTable
case class ModRkEntry(
  rkId: ObjectId,
  wpath: String, // WID doesn't work
  role: String,
  note: String = "",
  curYear: String = Config.curYear,
  forms: Seq[RoleWid] = Seq.empty,
  _id: ObjectId = new ObjectId) extends REntity[ModRkEntry] {

  // optimize access to User object
  lazy val rk = rkId.as[RacerKid]
  lazy val wid = WID.fromPath(wpath)
}

/** controller for club management */
object ModRk extends RazController with Logging {

  import razie.db.RMongo.as

  def regd (au:User, wid:WID) = ModRkReg(wid).kids.map(x => (x, x.rkId.as[model.RacerKid].get)).toList
  def rks (au:User, wid:WID) = au.rka.map(x => (x, x.rk.get)).toList

  def doeModRkRegs(wid: WID) = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      page <- wid.page
    ) yield {
      val regd = ModRkReg(page.wid).kids.map(x => (x, x.rkId.as[model.RacerKid].get)).toList
      val rks = au.rka.map(x => (x, x.rk.get)).toList

      Ok(views.html.modules.doeModRkRegs(au, page, ModRkReg(page.wid), regd, rks))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeModRkAdd(wid: WID, rkid: String, role: String) = FAU { implicit au => implicit errCollector => implicit request =>
    ModRkEntry(new ObjectId(rkid), wid.wpath, role).create
    Redirect(Wiki.w(wid))
  }

  def doeModRkRemove(wid: WID, rkid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "wpath" -> wid.wpath).foreach(_.delete)
    Redirect(Wiki.w(wid))
  }
}

