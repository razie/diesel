/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  **/
package mod.diesel.guard

import java.io.FileInputStream
import java.util.Properties
import razie.cdebug
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EError, EMsg, EVal, _}
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.hosting.Website
import razie.wiki.Config
import scala.collection.JavaConverters._
import scala.io.Source

/** properties - from system or file
  */
class EEDieselProps extends EExecutor("diesel.props") {
  val DT = DieselMsg.PROPS.ENTITY

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DieselMsg.PROPS.ENTITY
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] =  {
    val realm = ctx.root.settings.realm.mkString

    cdebug << "EEDiselProps: apply " + in

    in.ea match {

      case "diesel.props.realm" => {
        val result = ctx.get("result").getOrElse("payload")

        val m = Website.getRealmProps(ctx.root.settings.realm.mkString)

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.system" => {
        val result = ctx.get("result").getOrElse("payload")

        val m = if(Config.isLocalhost) {
          System.getProperties.asScala
        } else {
          Map("error" -> "No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.file" => {
        val result = ctx.get("result").getOrElse("payload")
        val name = ctx.getRequired("path")

        val m = if(Config.isLocalhost) {
          val p = new Properties()
          p.load(new FileInputStream(name))

          p.asScala
        } else {
          Map("error" -> "No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.jsonFile" => {
        val result = ctx.get("result").getOrElse("payload")
        val name = ctx.getRequired("path")

        val m = if(Config.isLocalhost) {
          val s = Source.fromInputStream(new FileInputStream(name)).mkString
          razie.js.parse(s)
        } else {
          Map("error" -> "No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case s@_ => {
        new EError(s"$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.props "

  override val messages: List[EMsg] =
    EMsg(DT, "system") ::
    EMsg(DT, "jsonFile") ::
    EMsg(DT, "file") :: Nil
}

