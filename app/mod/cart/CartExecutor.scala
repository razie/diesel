package mod.cart

import controllers.Emailer
import model.Users
import org.joda.time.DateTime
import razie.clog
import razie.diesel.dom.ECtx
import razie.diesel.ext.{EExecutor, EMsg, EVal, MatchCollector}
import razie.wiki.Services
import razie.wiki.model._

object ModCartExecutor extends EExecutor("diesel.cart") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "mod.user"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModCartExec", "?") { implicit txn =>
      clog << "mod.cart.addItem"

      in.met match {

/*
        case "addItem" => { //todo auth

          (for (
            wid <- WID.fromPath(clubWpath) orErr "no wid";
            uwid <- wid.uwid orErr "no uwid";
            pCategory <- stok.fqhParm("category") orErr "api: no category";
            pDesc <- stok.fqhParm("desc") orErr "api: no desc";
            pLink <- stok.fqhParm("link") orErr "api: no link";
            pOk <- stok.fqhParm("ok") orErr "api: no ok";
            pCancel <- stok.fqhParm("cancel") orErr "api: no cancel";
            pAmount <- stok.fqhParm("amount") orErr "api: no amount";
            pCurrency <- stok.fqhParm("currency") orErr "api: no currency";
            pId <- stok.fqhParm("id") orErr "api: no id"
          ) yield razie.db.tx("addToCart", stok.userName) { implicit txn =>
          }) getOrElse Unauthorized("haha")

          val realm = ctx("realm")
          val level = ctx("level")
          val amount = ctx("paymentAmount")
          val paymentId = ctx("paymentId")
          val userId = ctx("userId")

          Emailer.withSession(realm) { implicit mailSession =>
            if (paymentId.startsWith("PAY-") && paymentId.length > 20) {
              // ok, update
              Users.findUserById(userId).map {u=>
                val perm = level match {
                  case "blue" | Perm.Basic.s => Some(Perm.Basic)
                  case "black" | Perm.Gold.s => Some(Perm.Gold)
                  case "racing" | Perm.Platinum.s => Some(Perm.Platinum)
                  case _ => None
                }

                if(perm.isDefined && !u.hasMembershipLevel(perm.get)) u.profile.map { p =>
                  p.update(p.addPerm(perm.get))
                }

                val newu = u.copy(modNotes = u.modNotes ++ Seq(s"${DateTime.now().toString} - membership upgraded to $level with payment $paymentId amount $amount"))
                u.update(newu)
                Services.auth.cleanAuth2(u)

                Emailer.tellSiteAdmin(s"User ${u.userName} paid membership upgraded to: $level")
              }.getOrElse {
                Emailer.tellSiteAdmin(s"Some problem with user payment uid ${userId} to: $level")
              }
            } else {
              // oops? what payment?
              val uname = Users.findUserById(userId).map (_.userName).mkString
              Emailer.tellSiteAdmin(s"Payment ID invalid uid ${userId} uname $uname to: $level paymentId $paymentId amount $amount")
            }
          }

          List(new EVal("payload", msg))
        }
*/
        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::diesel.cart"
}

