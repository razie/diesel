package mod.cart

import admin.Config
import akka.actor.{Actor, Props}
import controllers._
import mod.cart
import mod.diesel.controllers.DieselMsgString
import mod.snow.Regs
import model._
import org.bson.types.ObjectId
import org.scalatest.path
import play.api.mvc.{Action, Result}
import razie.base.Audit
import razie.db.{REntity, ROne, tx}
import razie.wiki.dom.WikiDomain
import razie.wiki._
import razie.wiki.model._
import razie.{Logging, clog, cout}
import views.html.modules.cart.{doeAcct, doeCart, doeClubBilling}

import scala.Option.option2Iterable
import scala.concurrent.Future
import scala.util.Try

/** controller for club management */
object Accts extends RazController with Logging {

  /** list accounts and manage billing */
  def clubBilling(cwid:WID) = FAUR { implicit request=>
    (for (
      club <- Club.findForAdmin(cwid, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      val regs = Regs.findClubYear(cwid, club.curYear).toList//.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)
      val users = club.activeMembers
      ROK.r apply {
        doeClubBilling(club, users, regs)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  /** create acct */
  def create(regId:String) = FAUR { implicit request=>
    (for (
      reg <- Regs.findId(regId) orErr "reg not found";
      club <- Club.findForAdmin(reg.club, request.au.get) orErr s"club not found ${reg.club}";
      acct <- Acct.createOrFind(reg.userId, reg.club) orErr "Need registration up to date!"
    ) yield {

      ROK.r.msg("ok" -> "Account created") apply {
        doeAcct(acct,club)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  /** create acct */
  def create2(userId:String, cwid:WID) = FAUR { implicit request=>
    (for (
      club <- Club.findForAdmin(cwid, request.au.get) orErr s"club not found ${cwid}";
      acct <- Acct.createOrFind(new ObjectId(userId), club.wid) orErr "Need registration up to date!"
    ) yield {

      ROK.r.msg("ok" -> "Account created") apply {
        doeAcct(acct,club)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  /** manage acct */
  def acct(acctId:String) = FAUR { implicit request=>
    (for (
      acct <- Acct.findById(new ObjectId(acctId)) orErr "id not found";
      cwid <- acct.clubWid.wid orErr s"club wid not found ${acct.clubWid}";
      club <- Club(cwid) orErr "Club not found"
    ) yield {
      ROK.r apply {
        doeAcct(acct,club)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  def applyCredit(id:String) = FAUR { implicit request=>
    (for(
      acct <- Acct.findById(new ObjectId(id));
      club <- Club.findForAdmin(acct.clubWid.wid.get, request.au.get) orErr s"club not found ${acct.clubWid.nameOrId}"
      ) yield {
      try {

      val paid = Price(
        Try {request.formParm("amount").toFloat} getOrElse 0,
        request.formParm("currency")
      )

      acct.add (AcctTxn(
        request.au.get.userName,
        request.au.get._id,
        acct._id,
        paid,
        request.formParm("what"),
        request.formParm("desc"),
        Acct.STATE_CREDIT
      ))

      } catch {
        case t : Throwable =>
          Audit.logdb("ERR_BILLING", "ERR_CREDIT", t)
      }

      ROK.r.msg("ok" -> "Credit applied") apply {
        doeAcct(acct,club)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

}

