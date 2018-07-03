package mod.cart

import controllers.Emailer
import model.Users
import org.joda.time.DateTime
import razie.clog
import razie.diesel.dom.ECtx
import razie.diesel.ext.{EExecutor, EMsg, EVal, MatchCollector}
import razie.wiki.Services
import razie.wiki.model._

object ModUserExecutor extends EExecutor("mod.user") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "mod.user"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModUserExec", "?") { implicit txn =>
      in.met match {

        case "canstatus" => { // can user upgrade to level?
          //todo auth
          var msg = "false"

          val realm = ctx("realm")
          val level = ctx("level")
          val userId = ctx("userId")

          Users.findUserById(userId).map {u=>
            val perm = level match {
              case "renew:blue" => u.hasPerm(Perm.Basic)
              case "renew:black" => u.hasPerm(Perm.Basic) || u.hasPerm(Perm.Gold)     // can upgrage this way
              case "renew:racing" => u.hasPerm(Perm.Gold) || u.hasPerm(Perm.Platinum) || u.hasPerm(Perm.Unobtanium) // can upgrage this way
              case "blue" | "black" | "racing" => u.hasMembershipLevel(Perm.Member.s)
              case _ => false
            }

            msg = perm.toString
          }

          List(new EVal("payload", msg))
        }

        case "updstatus" => { //  user paid, udpate membership
          //todo auth
          var msg = "ok"
          clog << "mod.user.updstatus"
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
                  case "blue" | "renew:blue" | Perm.Basic.s => Some(Perm.Basic)
                  case "black" | "renew:black" | Perm.Gold.s => Some(Perm.Gold)
                  case "racing" | "renew:racing" | Perm.Platinum.s => Some(Perm.Platinum)
                  case _ => None
                }

                if(perm.isDefined && !u.hasMembershipLevel(perm.get)) u.profile.map { p =>
                  p.update(p.addPerm("+"+perm.get.s))
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

        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::mod.user "
}

