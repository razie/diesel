package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import admin.SendEmail
import model.Api
import model.DoSec
import model.Sec._
import model.Enc
import model.EncUrl
import model.ParentChild
import model.RegdEmail
import model.Registration
import model.User
import model.UserTask
import model.Users
import model.Wikis
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.data.Form
import play.api.mvc.Request
import play.api.mvc.Action
import razie.Logging
import razie.Snakk
import model.Base64
import model.Perm
import admin._
import model.WID
import model.UserTasks
import model.RegStatus
import model.UserWiki
import org.bson.types.ObjectId
import model.Reg
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
import model.Sec._
import play.api.cache.Cache
import admin.MailSession
import db.RTable
import scala.annotation.StaticAnnotation
import db.ROne
import db.RMany
import db.RCreate
import db.RDelete
import db.Mongo
import model.Tribes
import model.Stage
import model.WikiLink
import razie.clog

/** controller for tribes management */
object Tribe extends RazController with Logging {

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  def doeClubTribes = Action { implicit request =>
    Ok(views.html.club.doeClubTribes(auth.get, Club(auth.get)))
  }

  ////////////////////// manage tribe

  def tribeForm(implicit request: Request[_]) = Form {
    tuple(
      "name" -> nonEmptyText.verifying(vPorn, vSpec),
      "label" -> nonEmptyText.verifying(vPorn, vSpec),
      "year" -> nonEmptyText.verifying(vPorn, vSpec), //      "clubId" -> nonEmptyText.verifying(vPorn, vSpec),
      "role" -> nonEmptyText.verifying(vPorn, vSpec), //      "clubId" -> nonEmptyText.verifying(vPorn, vSpec),
      "desc" -> text.verifying(vPorn, vSpec) //      "clubId" -> nonEmptyText.verifying(vPorn, vSpec),
      //    clubId:ObjectId, 
      //      "year" -> nonEmptyText.verifying(vPorn, vSpec),
      //      "role" -> nonEmptyText.verifying(vPorn, vSpec),
      //      "wid" -> nonEmptyText.verifying(vPorn, vSpec)
      //    wid:Option[WID]
      )
  }

  def addForm(implicit request: Request[_]) = Form {
    tuple(
      "name" -> nonEmptyText.verifying(vPorn, vSpec),
      "label" -> text.verifying(vPorn, vSpec),
      "desc" -> text.verifying(vPorn, vSpec),
      "role" -> text.verifying(vPorn, vSpec))
  }

  // manage tribe screen
  def doeClubTribe(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("tribes only for a club");
      tribe <- model.Tribes.findById(new ObjectId(id))
    ) yield {
      Ok(views.html.club.doeClubTribe(tribeForm.fill(
        (tribe.name, tribe.label, tribe.year, tribe.role, tribe.desc)), tribe, au))
    }) getOrElse Msg2("CAN'T " + errCollector.mkString)
  }

  // manage tribe screen
  def doeClubAddKid(tid: String, kid:String, x:String, role:String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("tribes only for a club");
      tribe <- model.Tribes.findById(new ObjectId(tid)) orErr ("tribe not found");
      rk <- model.RacerKidz.findById(new ObjectId(kid)) orErr ("RacerKid not found");
      wid <- model.Wikis.findById(tid).map(_.wid) orErr ("wiki not found")
    ) yield {
      model.RacerWiki(rk._id, wid, role).create
      Redirect(routes.Tribe.doeClubTribe(tid))
    }) getOrElse Msg2("CAN'T " + errCollector.mkString)
  }

  // manage tribe screen
  def doeTribeAddHow(tid: String, role:String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("tribes only for a club");
      tribe <- model.Tribes.findById(new ObjectId(tid)) orErr ("tribe not found")
    ) yield {
       Ok(views.html.club.doeTribeAddHow(role, tribe, au))
    }) getOrElse Msg2("CAN'T " + errCollector.mkString)
  }

  // manage tribe screen
  def doeClubRemoveKid(tid: String, kid:String, x:String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("tribes only for a club");
      tribe <- model.Tribes.findById(new ObjectId(tid))
    ) yield {
      Ok(views.html.club.doeClubTribe(tribeForm.fill(
        (tribe.name, tribe.label, tribe.year, tribe.role, tribe.desc)), tribe, au))
    }) getOrElse Msg2("CAN'T " + errCollector.mkString)
  }

  // manage tribe screen
  def doeClubTribeAdd(cid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    addForm.bindFromRequest.fold(
      formWithErrors => {clog << formWithErrors.errors.toString; Msg2("Oops, can't add that name!")},
      {
        case (name, label, desc, role) => {
          (for (
            au <- activeUser;
            isClub <- au.isClub orErr ("tribes only for a club");
            club <- Club.findForUser(au)
          ) yield {
            val cat = "Tribe"
          val n = Wikis.formatName(WID(cat, name))
          Stage("WikiLink", WikiLink(WID(cat, n, club.wid.findId), club.wid, role).grated, auth.get.userName).create
          controllers.Wiki.wikieEdit(WID(cat, name, club.wid.findId), 
              s"{{label:$label}}\n{{desc:$desc}}\n{{role:$role}}\n{{year:${club.curYear}}}\n"
            ).apply(request)
          }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
        }
      })
  }

  def doeClubTribeUpdate(tid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("tribe management only for a club")
    ) yield {
//      val oldt = if (tid.length > 2) model.Tribes.findById(new ObjectId(tid)) else None
//      tribeForm.bindFromRequest.fold(
//        formWithErrors => BadRequest(views.html.club.doeClubTribe(formWithErrors, oldt, au)),
//        {
//          case (n, l, y, r, d) =>
//            val newt = if (oldt.isDefined)
//              model.Tribe(n, l, d, au._id, y, r, oldt.get.wid, oldt.get.crDtm, oldt.get._id)
//            else
//              model.Tribe(n, l, d, au._id, y, r, WID("Tribe", n))
//
//             if (oldt.isDefined) newt.update
//            else newt.create
              
    Ok(views.html.club.doeClubTribes(auth.get, Club(auth.get)))
//            Ok(views.html.club.doeClubTribe(tribeForm.fill(
//              (n, l, y, r, d)), None, au))
//        })
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** add a kid to current registration */
  //  def xdoeClubUwAddForm(uwid: String, role: String) = Action { implicit request =>
  //    implicit val errCollector = new VError()
  //    (for (
  //      au <- activeUser;
  //      isClub <- au.isClub orErr ("registration only for a club");
  //      c <- Club.findForUser(au);
  //      kwid <- c.regForm(role) orErr ("no reg form for role " + role);
  //      regAdmin <- c.uregAdmin orErr ("no regadmin");
  //      uw <- model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid) orErr ("no uw");
  //      u <- uw.user orErr ("oops - missing user?");
  //      reg <- Club(au).reg(u) orCorr ("no registration record for year... ?" -> "did you expire it first?")
  //    ) yield {
  //      val newfwid = WID("Form", kwid.name + "-" + role + "-" + uw.userId + "-" + reg.year + "-" + reg.wids.size)
  //      var label = s"${kwid.name.replaceAll("_", " ")}"
  //      if (!label.contains(reg.year)) label = label + s" for season ${reg.year}"
  //      reg.copy(wids = reg.wids ++ Seq(newfwid)).update
  //
  //      // have to create form ?
  //      if (!Wikis.find(newfwid).isDefined) {
  //        controllers.Forms.crForm(u, kwid, newfwid, label, regAdmin, Some(role))
  //      }
  //
  //      Ok(views.html.club.doeClubMember(tribeForm.fill(
  //        (uw.role, reg.regStatus, "")), uw, au))
  //    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  //  }

  /** list members */
  def doeUserTribes = Action { implicit request =>
    Ok(views.html.club.doeUserTribes(auth.get))
  }

  /** */
  def doeUserTribe(id: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      tribe <- Tribes.findById(new ObjectId(id)) orErr ("no tribe found")
    ) yield {
      Ok(views.html.club.doeUserTribe(au, tribe))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }
}
