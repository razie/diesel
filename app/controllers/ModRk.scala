/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import admin.SendEmail
import admin.VError
import db.REntity
import db.RMany
import db.ROne
import db.RTable
import model.FormStatus
import model.RK
import model.RacerKid
import model.RacerKidAssoc
import model.RacerKidz
import model.Reg
import model.RegKid
import model.RegStatus
import model.Regs
import model.Sec.EncryptedS
import model.User
import model.Users
import model.VolunteerH
import model.WID
import model.Wikis
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
import db.RMongo
import play.api.mvc.AnyContent
import play.api.mvc.Result
import db.RDelete

/** per topic reg */
case class ModRkReg(
  wid: WID,
  curYear: String = Config.curYear) {
  lazy val kids = RMany[ModRkEntry]("curYear" -> curYear, "wpath" -> wid.wpath).toList
}

/** per topic reg */
@db.RTable
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

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  def FAU(f: User=>VError=>Request[AnyContent]=> Result) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T SEE PROFILE ")
  }

  def t1 = FAU { implicit au => implicit errCollector => implicit request =>
    val members =
      model.Users.findUserLinksTo(model.WID("Club", au.userName)).map(uw =>
        (model.Users.findUserById(uw.userId),
          uw,
          model.Regs.findClubUserYear(au, uw.userId, controllers.Club(au).curYear))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)

    Ok(views.html.club.doeClubRegs(au, members))
  }
  import db.RMongo.as

  def regd (au:User, wid:WID) = ModRkReg(wid).kids.map(x => (x, x.rkId.as[model.RacerKid].get)).toList
  def rks (au:User, wid:WID) = model.RacerKidz.findAssocForUser(au._id).map(x => (x, x.rk.get)).toList

  def doeModRkRegs(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      page <- wid.page
    ) yield {
      val regd = ModRkReg(page.wid).kids.map(x => (x, x.rkId.as[model.RacerKid].get)).toList
      val rks = model.RacerKidz.findAssocForUser(au._id).map(x => (x, x.rk.get)).toList

      Ok(views.html.modules.doeModRkRegs(au, page, ModRkReg(page.wid), regd, rks))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeModRkAdd(wid: WID, rkid: String, role: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser
    ) yield {
      ModRkEntry(new ObjectId(rkid), wid.wpath, role).create

      Redirect(Wiki.w(wid))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeModRkRemove(wid: WID, rkid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser
    ) yield {
      ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "wpath" -> wid.wpath).foreach(_.delete)
      Redirect(Wiki.w(wid))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }
}

