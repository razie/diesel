/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package razie.diesel.engine.exec

import java.util.regex.Pattern
import razie.cdebug
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.utils.DomCollector
import razie.diesel.{Diesel, DieselRateLimiter, RateLimitGroup}
import razie.wiki.Services
import scala.collection.mutable.{HashMap, ListBuffer}

/** properties - from system or file
  */
class EEDieselApiGw extends EExecutor(DieselMsg.APIGW.ENTITY) {
  val DT = DieselMsg.APIGW.ENTITY

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity startsWith DieselMsg.APIGW.ENTITY
  }

  private def OK = List(
    EVal(P.fromTypedValue(Diesel.PAYLOAD, "ok"))
  )

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val realm = ctx.root.settings.realm.mkString

    cdebug << "EEDieselApiGW: apply " + in

    in.ea match {

      case "diesel.apigw.limit.static" => {
        if (Services.config.isLocalhost) DieselRateLimiter.synchronized {
          DieselRateLimiter.LIMIT_API = ctx.getRequired("limit").toInt
          // todo replace the globalGroup there

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case "diesel.apigw.limit.rate" => {
        if (Services.config.isLocalhost) DieselRateLimiter.synchronized {
          DieselRateLimiter.RATELIMIT = ctx.getRequired("limit").toBoolean

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case "diesel.apigw.limit.path" | "diesel.apigw.limit.header" => {
        val groupName = ctx.getRequired("group")
        val regex = ctx.get("regex")
        val header = ctx.get("header")

        if (Services.config.isLocalhost) DieselRateLimiter.synchronized {
          val group = DieselRateLimiter.rateLimits.get(groupName)
          val newg = group
              .map { g =>
                g.regex = ((if (in.ea.endsWith("path")) regex else None).toList ::: g.regex).distinct
                g.headers = (header.map(x => (x, regex.get)).toList ::: g.headers).distinct
                g
              }
              .getOrElse(
                new RateLimitGroup(
                  groupName,
                  ctx.getRequired("limit").toInt,
                  regex = (if (in.ea.endsWith("path")) regex else None).toList,
                  header.map(x => (x, regex.get)).toList
                )
              )
          DieselRateLimiter.rateLimits.put(groupName, newg)
        } else {
          throw new IllegalArgumentException("Error: No permission")
        }

        OK
      }

      case "diesel.apigw.limit.groups" => {
        // todo should use permissions?
        if (! Services.config.isLocalhost)
          throw new IllegalArgumentException("Error: No permission")

        List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, DieselRateLimiter.rateLimits.map(t => {
              (t._1, {
                t._2.toString
              })
            }))
            ))
      }

      case "diesel.apigw.limit.stats" => {
        // todo should use permissions?
        List(
          EVal(
            P.fromSmartTypedValue(Diesel.PAYLOAD, DieselRateLimiter.toj)))
      }

      case "diesel.apigw.collect.config" => {
        val p = in.attrs.head.calculatedTypedValue.asJson.get("patterns")
        val config = p.map(_.asInstanceOf[ListBuffer[HashMap[String,Any]]]).get
        val x = config.map (m => DomCollector.ConfigEntry(
          Pattern.compile(m("description").toString),
          m("collectCount").toString.toInt,
          m("collectGroup").toString
        ))
        DomCollector.configPerRealm.put (ctx.root.settings.realm.mkString, x)

        OK
      }

      case s@_ => {
        new EError(s"$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.apigw "

  override val messages: List[EMsg] =
    EMsg(DT, "limit.static") ::
        EMsg(DT, "limit.rate") ::
        EMsg(DT, "limit.path") ::
        EMsg(DT, "limit.header") ::
        EMsg(DT, "collect.config") ::
        EMsg(DT, "stats") ::
        EMsg(DT, "groups") :: Nil
}
