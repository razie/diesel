/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package mod.diesel.guard

import razie.cdebug
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EError, EMsg, EVal, _}
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.{Diesel, DieselRateLimiter, RateLimitGroup}
import razie.wiki.Config

/** properties - from system or file
  */
class EEDieselApiGw extends EExecutor(DieselMsg.APIGW.ENTITY) {
  val DT = DieselMsg.APIGW.ENTITY

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DieselMsg.APIGW.ENTITY
  }

  private def OK = List(
    EVal(P.fromTypedValue(Diesel.PAYLOAD, "ok"))
  )

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val realm = ctx.root.settings.realm.mkString

    cdebug << "EEDieselApiGW: apply " + in

    in.ea match {

      case "diesel.apigw.limit.static" => {
        if (Config.isLocalhost) {
          DieselRateLimiter.LIMIT_API = ctx.getRequired("limit").toInt

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case "diesel.apigw.limit.rate" => {
        if (Config.isLocalhost) {
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

        val m = if (Config.isLocalhost) {
          val group = DieselRateLimiter.rateLimits.get(groupName)
          val newg = group
              .map(g => g.copy(
                regex = (if (in.ea.endsWith("path")) regex else None).toList ::: g.regex,
                headers = header.map(x => (x, regex.get)).toList ::: g.headers
              )
              )
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
        if (Config.isLocalhost) {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, DieselRateLimiter.rateLimits.map(t => {
              (t._1, {
                t._2.toString
              })
            }))
            ))
        } else {
          throw new IllegalArgumentException("Error: No permission")
        }
      }

      case "diesel.apigw.limit.stats" => {
        // todo should use permissions?
        List(
          EVal(P.fromTypedValue(Diesel.PAYLOAD, DieselRateLimiter.rateStats.map(t => {
            (t._1, {
              t._2.toString
            })
          }))
          ))
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
        EMsg(DT, "stats") ::
        EMsg(DT, "groups") :: Nil
}
