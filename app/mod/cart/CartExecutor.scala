package mod.cart

import razie.clog
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EMsg, MatchCollector}
import razie.diesel.expr.ECtx

object EEModCartExecutor extends EExecutor("diesel.cart") {

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "diesel.mod.cart"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModCartExec", "?") { implicit txn =>
      clog << "diesel.mod.cart.addItem"

      in.met match {
        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::diesel.cart"

  override val messages: List[EMsg] = List(
    "canstatus",
    "updstatus",
    "createuser"
  ).map (EMsg("diesel.mod.cart", _))
}

