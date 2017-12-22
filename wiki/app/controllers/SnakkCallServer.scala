/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * ( __ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   LICENSE.txt
  **/
package controllers

import java.util
import javax.inject.{Inject, Singleton}

import play.api.mvc.{Action, Controller}
import razie.{Logging, Snakk, SnakkResponse, js}
import razie.diesel.exec.{AsyncSnakkCallList, SnakkCall}
import razie.tconf.DTemplate

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import akka.pattern.after
import akka.actor.ActorSystem

/** a single snakk call to make
  *
  * @param protocol http, telnet
  * */
@Singleton
class SnakkCallServer @Inject() (actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends RazController with Logging {

  lazy val TOUT = 20 second

  /** proxy checking if there are any requests */
  def check(host: String) = RAction { implicit request =>
    AsyncSnakkCallList.calls.synchronized {
      AsyncSnakkCallList.next(host).map { t =>
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

      log("RECEIVED: " + body)

      val resp = Snakk.responseFromJson(body)
      AsyncSnakkCallList.complete(id, resp)
    } catch {
      case t : Throwable => log(t.toString)
    }

    Ok("")
  }

  /** client creating a request to proxy something */
  def proxy(p: String, host:String, path:String) = RAction.async { implicit request =>
    val sc = SnakkCall(p, "GET", host + "/" + path, Map.empty, "")

    log("Proxying - " + sc.toJson)

    val f = sc.future
    lazy val timeout = after(duration = TOUT, using = actorSystem.scheduler)(Future.successful(RequestTimeout("Request timeout !!")))

    val result = f.map {response=>
      log("returning - " + response)
      Ok(response.content).withHeaders(response.headers.toSeq:_*)
    }

    Future.firstCompletedOf(Seq(result, timeout))
  }

}

