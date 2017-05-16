package mod.snow

import admin.Config
import controllers.{Club, RazController}
import org.bson.types.ObjectId
import play.api.data.Forms._
import play.api.data._
import play.api.mvc.Request
import razie.Logging
import razie.db.{REntity, ROne, RTable}
import razie.wiki.model.WID
import com.google.inject._


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
@Singleton
class SeasonCtl extends RazController with Logging {

  def seasonForm(implicit request: Request[_]) = Form {
    tuple(
      "year" -> nonEmptyText,
      "label" -> text
        )
  }

  def create(parentWid:String) = FAUR("create") { implicit stok =>
    implicit val request = stok.req
    for (
      parent <- WID.fromPath(parentWid);
      c <- Club.findForAdmin(parent.name, stok.au.get) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.k apply { implicit stok=>
          views.html.modules.snow.doeCreateSeason(parent.name, seasonForm.fill((c.curYear, c.curYear)))
        }
      }
  }

  def doeCreateSeason(clubName:String) = FAUR("create season") { implicit stok =>
    implicit val request = stok.req
    for (
      c <- Club.findForAdmin(clubName, stok.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      ROK.k apply { implicit stok=>
        views.html.modules.snow.doeCreateSeason(clubName, seasonForm.fill((c.curYear, c.curYear)))
      }
    }
  }

  def doeEditSeason(id:String) = FAUR ("edit season") { implicit stok=>
    implicit val request = stok.req
    for (
      s <- ROne[Season](new ObjectId(id)) orErr ("Not a valid season");
      c <- Club.findForAdmin("", stok.au.get) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.k apply { implicit stok=>
          views.html.modules.snow.doeCreateSeason("", seasonForm.fill((s.year, s.label)))
        }
      }
  }

  def doeUpdateSeason(clubName:String) = FAUR { implicit stok =>
    implicit val request = stok.req

    seasonForm.bindFromRequest.fold(
    formWithErrors => ROK.k badRequest { implicit stok=> views.html.modules.snow.doeCreateSeason(clubName, formWithErrors) },
    {
      case (y, l) => forActiveUser { au =>
        //        val c1 = ROne[Season]()
        //        c1.update
        Redirect(routes.SeasonCtl.viewSeason(""))
      }
    })
  }

  def viewSeason(id:String) = RAction { implicit request =>
    ROK.k apply { implicit stok=>
      views.html.modules.snow.viewSeason(ROne[Season](new ObjectId(id)).get)
    }
  }

}


