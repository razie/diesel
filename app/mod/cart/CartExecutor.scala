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
        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::diesel.cart"
}

