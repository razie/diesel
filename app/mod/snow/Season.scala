package mod.snow

import admin.Config
import akka.actor.{Actor, Props}
import controllers.{VErrors, Club, RazController}
import controllers.Wiki._
import play.api.libs.concurrent.Akka
import play.mvc.Result
import razie.wiki.admin.SendEmail
import controllers.Profile._
import controllers.Tasks._
import razie.db.RMongo.as
import razie.db.{RTable, REntity, RMany, ROne}
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}
import razie.wiki.{Dec, WikiConfig, EncUrl, Enc}
import razie.wiki.model.{WikiReactors, Wikis, WID, FormStatus}
import razie.{clog, Logging, cout}
import views.html.club.doeClubRegsRepHtml
import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import razie.wiki.admin.SendEmail
import razie.db.Txn

import scala.concurrent.Future

/** an element is an object and a role. Kind maps to the mongo table of it */
case class Element(id: ObjectId, kind:String, role: String)

@RTable
case class Season (
  clubName: ObjectId, // wikid
  year: String = Config.curYear, // year or season like "2016-summer"
  label:String,
  elements : Seq[Element] = Nil,
  _id: ObjectId = new ObjectId) extends REntity[Season] {

  def name = if(label.isEmpty) year else label
}

/** controller for club management */
object Season extends RazController with Logging {

  import play.api.data.Forms._
  import play.api.data._

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def seasonForm(implicit request: Request[_]) = Form {
    tuple(
      "year" -> nonEmptyText,
      "label" -> text
//    ) verifying
//      ("Can't use last name for organizations!", { t: (String, Int, String, String) =>
//        true
//      }
        )
  }

  def create(parentWid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      parent <- WID.fromPath(parentWid);
      c <- Club.findForAdmin(parent.name, au) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.s apply { implicit stok=>
          views.html.modules.snow.doeCreateSeason(parent.name, seasonForm.fill((c.curYear, c.curYear)))
        }
      }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeCreateSeason(clubName:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      c <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.r apply { implicit stok=>
          views.html.modules.snow.doeCreateSeason(clubName, seasonForm.fill((c.curYear, c.curYear)))
        }
      }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeEditSeason(id:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      s <- ROne[Season](new ObjectId(id)) orErr ("Not a valid season");
      c <- Club.findForAdmin("", au) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.r apply { implicit stok=>
          views.html.modules.snow.doeCreateSeason("", seasonForm.fill((s.year, s.label)))
        }
      }) getOrElse Msg2("OOPS " + errCollector.mkString)
  }

  def doeUpdateSeason(clubName:String) = FAU { implicit au => implicit errCollector => implicit request =>
    seasonForm.bindFromRequest.fold(
    formWithErrors => ROK.s badRequest { implicit stok=> views.html.modules.snow.doeCreateSeason(clubName, formWithErrors) },
    {
      case (y, l) => forActiveUser { au =>
        //        val c1 = ROne[Season]()
        //        c1.update
        Redirect(routes.Season.viewSeason(""))
      }
    })
  }

  def viewSeason(id:String) = RAction { implicit request =>
    ROK.k apply { implicit stok=>
      views.html.modules.snow.viewSeason(ROne[Season](new ObjectId(id)).get)
    }
  }

}


