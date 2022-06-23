/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * ( __ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   LICENSE.txt
  **/
package controllers

import akka.actor.ActorSystem
import akka.pattern.after
import javax.inject.{Inject, Singleton}
import play.api.mvc.Action
import razie.diesel.engine.exec.{SnakkCall, SnakkCallAsyncList}
import razie.{Logging, Snakk}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

/** a proxy server, based on polling
  *
  * example using the proxy:
  *
  * curl -X GET -H "$ELASTIC_AUTH" -H 'Content-Type: application/json' -H "kbn-xsrf: true" 'http://elastic.107.9200.snakkproxy.dieselapps.com/diagresult/_search'
*/
@Singleton
class SnakkCallServer @Inject() (actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends RazController with Logging {

  lazy val TOUT = 20 second

  /** proxy checking if there are any requests */
  def check(env:String, host: String) = RAction { implicit request =>
    SnakkCallAsyncList.calls.synchronized {
      SnakkCallAsyncList.next(env, host).map { t =>
        Ok(razie.js.tojsons(t._2.toSnakkRequest(t._1).toJson))
      } getOrElse Ok("")
    }
  }

  /** proxy sending results to one request */
  def complete(id: String) = Action(parse.raw(10000 * 1024)) { implicit request =>
    def stok = razRequest

    try {
      val raw = request.body.asBytes()
      val body = raw.map(a => new String(a, "UTF-8")).getOrElse("")

      log("SNAKKPROXY RECEIVED: " + body.replaceAllLiterally("\n", ""))

      val resp = Snakk.responseFromJson(body)
      SnakkCallAsyncList.complete(id, resp)
    } catch {
      case t : Throwable => log(t.toString)
    }

    Ok("")
  }

  /** client creating a request to proxy something */
  def proxy(env:String, p: String, host:String, port:String, path:String) = RAction.async { implicit request =>
    val sport = if(p == "https" && port == "443" || p == "http" && port == "80") "" else s":$port"
    val spath = if(path.startsWith("/")) path else "/"+path
    val sc = SnakkCall(p, "GET", s"$host$sport$spath", request.headers.toSimpleMap, "").withEnv(env)

    log("SNAKKPROXY Proxying - " + sc.toJson.replaceAllLiterally("\n", " "))

    val f = sc.future
    lazy val timeout = after(duration = TOUT, using = actorSystem.scheduler)(Future.successful(RequestTimeout("Request timeout !!")))

    val result = f.map {response=>
      log("SNAKKPROXY returning - " + response)
      new Status(response.resCode )(response.content).withHeaders(response.headers.toSeq:_*)
    }

    Future.firstCompletedOf(Seq(result, timeout))
  }

}

