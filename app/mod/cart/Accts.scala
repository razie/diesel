package mod.cart

import controllers._
import mod.snow.Regs
import org.bson.types.ObjectId
import razie.Logging
import razie.audit.Audit
import razie.wiki.model._
import views.html.modules.cart.{doeAcct, doeClubBilling}
import com.google.inject.Singleton

import scala.util.Try

/** controller for billing management */
@Singleton
class Accts extends RazController with Logging {

  /** list accounts and manage billing */
  def doeClubBillingView(cwid: WID) = FAUR { implicit request =>
    (for (
      club <- Club.findForAdmin(cwid, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      val regs = Regs.findClubYear(cwid,
        club.curYear).toList //.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)
      val users = club.activeMembers
      ROK.r apply {
        doeClubBilling(club, users, regs)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  /** list accounts and manage billing */
  def doeClubBillingReport(cwid:WID) = FAUR { implicit request=>
    (for (
      club <- Club.findForAdmin(cwid, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      val regs = Regs.findClubYear(cwid, club.curYear).toList//.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)
      val users = club.activeMembers
      ROK.r apply {
        views.html.modules.cart.doeClubBillingReport(club, users, regs)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

  /** list accounts and manage billing */
  def doeClubBillingPurge(cwid:WID) = FAUR { implicit request=>
    (for (
      club <- Club.findForAdmin(cwid, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      val regs = Regs.findClubYear(cwid, club.curYear).toList//.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)
      val users = club.activeMembers

      var counter = 0

      users.map { uw =>
        Acct.findCurrent(uw.userId, club.wid).map { acct =>
          acct.txns.map { t =>
            t.deleteNoAudit
            counter = counter+1
          }
        }
      }

      Audit.logdb("removed acct transactions", counter + " records")

      ROK.r apply {
        views.html.modules.cart.doeClubBillingReport(club, users, regs)
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

  def updateDiscount(id:String) = FAUR { implicit request=>
    (for(
      acct <- Acct.findById(new ObjectId(id));
      club <- Club.findForAdmin(acct.clubWid.wid.get, request.au.get) orErr s"club not found ${acct.clubWid.nameOrId}"
      ) yield {
        val x = Try {
          request.formParm("discount").toInt
        }.toOption

      acct.copy(discount= (if(x.exists(_ == 0)) None else x)).update

      Audit.logdb("BILLING", "DISCOUNT updated", x, id )

      ROK.r.msg("ok" -> ("Discount updated to "+x.toString)) apply {
        doeAcct(acct,club)
      }
    }) getOrElse {
      unauthorized("ACCT_ERR can't find account")
    }
  }

}

