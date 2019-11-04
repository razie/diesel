/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model.exec

import controllers.Emailer
import razie.diesel.dom.RDOM._
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import scala.collection.mutable


class EEMail extends EExecutor("diesel.mail") {

  final val DM = "diesel.mail"

  /** map of active contexts per transaction */
  val contexts = new mutable.HashMap[String, ECtx]()

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DM
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    def parm(s: String): Option[P] = in.attrs.find(_.name == s).orElse(ctx.getp(s))

    in.met match {

      case "new" => {
        val host = ctx.getRequired("host")
        val port = ctx.getRequired("port")
        val name = ctx.getRequired("name")
        val user = ctx.get("user")
        val pass = ctx.get("pass")

        EVal(P(name, "")) :: Nil
      }

      case "send" => {
        val to = ctx.getRequired("to").split(",").toList
        val subject = ctx.getRequired("subject")
        val body = ctx.getRequired("body")
          Emailer.withSession(ctx.root.settings.realm.get) { mailSession =>
            to.filter(nzlen).map { addr =>
              mailSession.send(addr, mailSession.SUPPORT, subject, body)
            }
          }
          EInfo("Sent...") :: Nil
      }

      case s@_ => {
        new EError(s"$DM.$s - unknown activity ") :: Nil
      }
    }
  }

  private def nzlen(s:String) = s != null && s.length > 0

  override def toString = "$executor::mail "

  override val messages: List[EMsg] =
    EMsg(DM, "send") ::
      Nil
}
