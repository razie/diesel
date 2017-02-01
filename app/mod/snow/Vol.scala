package controllers

import mod.snow._
import mod.snow.RK
import play.api.i18n.Messages
import play.api.i18n.MessagesApi
import play.api.i18n.Messages.Implicits._
import play.api.Play.current

import admin.Config
import akka.actor.{Actor, Props}
import controllers.Wiki._
import play.api.libs.concurrent.Akka
import play.mvc.Result
import play.twirl.api.Html
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
import razie.wiki.model._
import razie.{clog, Logging, cout}
import views.html.club.doeClubRegsRepHtml
import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import razie.wiki.admin.SendEmail
import razie.db.Txn

import scala.concurrent.Future
import play.api.data.Forms._
import play.api.data._

/** controller for club management */
object Vol extends RazController with Logging {

  // rollup volunteer hours per registration
  def volPerReg(reg:Reg) = {
    val x = for(club <- Club(reg.clubName)) yield {
      val rkasx = reg.kids.flatMap(rk=> RMany[RacerKidAssoc]("from" -> club.userId, "year" -> reg.year, "to" -> rk.rkId).filter(_.role != RK.ROLE_FAN))
      rkasx.map(_.hours)
    }
    if (x.isEmpty || x.get.isEmpty) "~" else x.get.sum.toString
  }

  /** for user per club au is the user */
  def doeUserVolAdd (regId:String) = FAUR("add volunteer hours") { implicit stok =>
    for (
      reg <- Regs.findId(regId) orErr "no registration found... ?";
      club <- Club(reg.clubName)
    ) yield {
      reg.kids.map(_.rkId);
      val rkas = club.rka("", reg.year).filter(_.role != RK.ROLE_FAN).filter(a=> reg.kids.exists(_.rkId == a.to))
        ROK.k apply {
          views.html.club.doeClubUserVolAdd(reg, rkas.toList, addvol.fill("", 0, "?", "", ""), club)
        }
    }
  }

  /** for club per member */
  def doeVol(wid:WID) = FAUR("see volunteer hours") { implicit stok =>
    for (
      club <- Club.findForAdmin(wid, stok.au.get) orErr ("Not a club or you're not admin")
    ) yield {

      val rks =
        (for (
          a <-  club.rka if a.role != RK.ROLE_FAN;
          k <-  mod.snow.RacerKidz.findById(a.to)        if k.info.status != RK.STATUS_FORMER
        ) yield (k, a)).toList.sortBy(x => x._1.info.lastName + x._1.info.firstName)

      val regs = Regs.findClubYear(club.wid, club.curYear).toList

        ROK.k apply { views.html.club.doeClubVol(club, rks, regs) }
      }
  }

  def addvol = Form {
    tuple(
      "who" -> text,
      "hours" -> number(min = 0, max = 100),
      "desc" -> text.verifying(vBadWords, vSpec),
      "comment" -> text.verifying(vBadWords, vSpec),
      "approver" -> text.verifying(vBadWords, vSpec)
    )
  }

  /** club admin: capture volunteer hour record */
  def doeVolAdd(wid:WID, rkaId: String) = FAUR("adding volunteer hours") { implicit stok =>
    for (
      club <- Club.findForAdmin(wid, stok.au.get) orErr ("Not a club or you're not admin");
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId)) orErr ("No RKA id "+rkaId)
    ) yield {
        ROK.k apply { views.html.club.doeClubVolAdd(club, rkaId, rka, addvol.fill(rkaId, 0, "?", "", "?"), stok.au.get) }
    }
  }

  /** create volunteer hour record
    *
    * rid is rkaid when clubName (from club admin) and
    * rid is regid when clubName empty (from user)
    */
  def doeVolAdded(wid:WID, rid:String) = FAUR { implicit stok =>
      addvol.bindFromRequest.fold(formWithErrors => {
        val id = formWithErrors("who").value.mkString
        val rka = ROne[RacerKidAssoc]("_id" -> new ObjectId(id)).get
         ROK.k badRequest { implicit stok =>
           if(!wid.isEmpty) {
             // from admin
             views.html.club.doeClubVolAdd(Club(wid).get, id, rka, formWithErrors, stok.au.get)
           } else {
             // from user
             val reg = Regs.findId(rid).get
             val club = Club(reg.clubName).get
             reg.kids.map(_.rkId);
             val rkas = club.rka("", reg.year).filter(_.role != RK.ROLE_FAN).filter(a=> reg.kids.exists(_.rkId == a.to))
             views.html.club.doeClubUserVolAdd(reg, rkas.toList, formWithErrors, club)
           }
         }
        },
        {
          case (w, h, d, c, a) =>
            val id = w

            ROne[RacerKidAssoc]("_id" -> new ObjectId(id)).map{rka=>
              rka.copy(hours = rka.hours + h).update
            }

            if(!wid.isEmpty) {
              VolunteerH(new ObjectId(id), h, d, c, stok.au.get._id, Some(a), VH.ST_OK).create
              Redirect(routes.Vol.doeVolAdd(wid, rid))
            } else {
              mod.snow.VolunteerH(new ObjectId(id), h, d, c, stok.au.get._id, Some(a), VH.ST_WAITING).create
              Redirect(routes.Vol.doeUserVolAdd(rid))
            }
        })
  }

  /** delete volunteer hour record */
  def doeVolDelete(wid:WID, rkaId: String, vhid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      vh <- ROne[VolunteerH]("_id" -> new ObjectId(vhid))
    ) yield {
      vh.delete

      if(!wid.isEmpty) {

        ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId)).map { rka =>
          if (vh.status == VH.ST_OK)
            rka.copy(hours = rka.hours - vh.hours).update
        }

        Redirect(routes.Vol.doeVolAdd(wid, rkaId))
      } else
        Redirect(routes.Vol.doeUserVolAdd(rkaId))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  import razie.wiki.Sec._

  /** capture volunteer hour record */
  def doeVolApprover(user:User) (implicit request:Request[_]) = {
    implicit val errCollector = new VErrors()
    val vhs =
      if (user.isClub)
        RMany[VolunteerH]("approvedBy" -> None)
      else
        RMany[VolunteerH]("approver" -> Some(user.email.dec), "approvedBy" -> None)
    ROK s (user, request) apply { implicit stok =>(views.html.club.doeClubUserVolApprove(vhs.toList, user))}
  }

  /** approve volunteer hour record */
  def doeVolApprove(vhid: String, approved:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      vh <- ROne[VolunteerH]("_id" -> new ObjectId(vhid));
      rka <- ROne[RacerKidAssoc]("_id" -> vh.rkaId)
    ) yield {
      if(approved.startsWith("to:")) {
        vh.copy(approver = Some(approved.replaceFirst("to:", ""))).update
      } else if("y" == approved) {
        vh.copy(approvedBy = Some(au._id), status=VH.ST_OK).update
        rka.copy(hours = rka.hours + vh.hours).update
      } else {
        vh.copy(approvedBy = Some(au._id), status=VH.ST_REJECTED).update
      }
      Redirect ("/user/task/approveVolunteerHours")
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // rollup per family/reg
  def volFamily(rk: RacerKid, rks: List[(RacerKid, RacerKidAssoc)], regs: List[Reg]) = {
    (for (
      uid <- rk.userId;
      reg <- regs.find(_.userId == uid)
    ) yield {
      val rkids = reg.kids.toList.flatMap(_.rkId.as[RacerKid].toList).flatMap(_.all)
      val x = rks.filter(x => rkids exists (_._id == x._1._id)).map(_._2.hours)
      if (x.isEmpty) "" else x.sum.toString
    }) getOrElse ""
  }
}

