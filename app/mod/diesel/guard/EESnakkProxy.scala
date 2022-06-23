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
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.{Diesel, DieselRateLimiter, RateLimitGroup}
import razie.snakked.SnakkProxyRemote
import razie.wiki.{Config, Services}

/** control the built-in snakk proxy */
class EESnakkProxy extends EExecutor("diesel.proxy") {
  val DT = "diesel.proxy"

  var clientThread: Option[Thread] = None

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DT
  }

  private def OK = List(
    EVal(P.fromTypedValue(Diesel.PAYLOAD, "ok"))
  )

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    cdebug << "EEDieselProxy: apply " + in

    in.ea match {

/*
$send diesel.proxy.start(
  name="107",
  dests="elastic:9200,kibana:5601",
  sources="www.dieselapps.com"
  )
*/

      case "diesel.proxy.start" => {
        if (Services.config.isLocalhost) this.synchronized {

          clientThread.foreach{
            try {
              t=>t.stop()
            } finally {}
          }

          ctx.get("name").orElse(ctx.get("env")).foreach { SnakkProxyRemote.name = _ }

          SnakkProxyRemote.dests = ctx.getRequired("dests").split(",")
          SnakkProxyRemote.sources = ctx.getRequired("sources").split(",")
          SnakkProxyRemote.SLEEP1 = ctx.get("sleep1").getOrElse("1000").toInt
          SnakkProxyRemote.SLEEP2 = ctx.get("sleep2").getOrElse("5000").toInt
          SnakkProxyRemote.DELAY = ctx.get("delay").getOrElse("10000").toInt
          SnakkProxyRemote.RESTART = ctx.get("restart").getOrElse("120000").toInt

          clientThread = Some(razie.Threads.fork{
            try {
              while(true) {
                SnakkProxyRemote.mainLoop()
                Thread.sleep(2000)
              }
            } finally {}
          })

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case "diesel.proxy.stop" => {
        if (Services.config.isLocalhost) this.synchronized {

          clientThread.foreach{
            try {
              t=>t.stop()
            } finally {}
          }

          clientThread = None

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case "diesel.proxy.restart" => {
        if (Services.config.isLocalhost) this.synchronized {

          clientThread.foreach {
            try {
              t => t.stop()
            } finally {}
          }

          clientThread = Some(razie.Threads.fork {
            try {
              while (true) {
                SnakkProxyRemote.mainLoop()
                Thread.sleep(2000)
              }
            } finally {}
          })

          OK
        } else {
          List(
            EVal(P.fromTypedValue(Diesel.PAYLOAD, "ERR - not isLocalhost"))
          )
        }
      }

      case s@_ => {
        new EError(s"$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.proxy "

  override val messages: List[EMsg] =
    EMsg(DT, "start") ::
        EMsg(DT, "stop") ::
        EMsg(DT, "restart") ::
        EMsg(DT, "stats") :: Nil
}
