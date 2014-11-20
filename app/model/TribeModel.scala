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
import db.REntity
import controllers.Club
import razie.cout
import razie.cdebug

/** a tribe - or user group */
@db.RTable
case class Tribe (
    uwid:UWID,
    wl:WikiLink,
    clubId:ObjectId) {
  cdebug << "TRIBE "+this.toString

//  lazy val users = RMany[TribeUser]("tribeId" -> _id)
    def name:String = page.contentTags.get("name").getOrElse(page.wid.name)
    def label:String = page.contentTags.get("label").getOrElse(page.label)
    def desc:String = page.contentTags.get("desc").getOrElse("?")
    def year:String = page.contentTags.get("year").getOrElse ("?")
    def role = page.contentTags.get("role").getOrElse ("?")

    lazy val page = wid.page.get

    def kidz = RMany[RacerWiki]("wid" -> wid.grated)
    def has (rkId:ObjectId) = kidz.exists(_.rkId == rkId)

  def wid = uwid.wid.get
}

/** tribes */
object Tribes {
  final val KROLE_RACER = "Racer"
  final val KROLE_COACH = "Coach"
  final val KROLE_MANAGER = "Manager"

  final val TROLE_TEAM = "Team"
  final val TROLE_ADMIN = "Admin"
  final val TROLE_EXEC = "Exec"

  def findByName(name:String) = RMany[Tribe]("name" -> name)
  def findByClub(club:Club) = Wikis.linksTo(club.uwid,"Child").filter(_.from.cat == "Tribe").map(wl=> new Tribe(wl.from, wl, club.userId))
  def findById(id:ObjectId) = Wikis.findById(id.toString).map(w=> Tribe(w.uwid, Wikis.linksFrom(w.uwid, "Child").next, id))
}
