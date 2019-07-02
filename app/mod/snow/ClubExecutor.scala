package mod.snow

import mod.cart.Price
import org.bson.types.ObjectId
import razie.audit
import razie.clog
import razie.db._
import razie.diesel.dom.ECtx
import razie.diesel.ext.{EExecutor, EMsg, EVal, MatchCollector}
import razie.wiki.model._

object EEModSnowExecutor extends EExecutor("modsnow") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "modsnow"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModSnowExec", "?") { implicit txn =>
      in.met match {

        case "updregstatus" => {
          //todo auth
          var msg = "ok"
          clog << "modsnow.updregstatus"
          val regid = ctx("regid")
          val status = ctx("status")
          val paid = ctx("paid")

          Regs.findId(regid).map {reg=>
            if(paid.length > 0) {
              val amt = reg.amountPaid + Price(paid.toFloat)
              reg.copy(regStatus = status, paid = amt.amount.toString, amountPaid = amt).update
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

  override def toString = "$executor::modsnow "
}

