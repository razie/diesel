/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * ( __ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   LICENSE.txt
  **/
package controllers

import akka.pattern.after
import akka.util.ByteString
import javax.inject.Singleton
import play.api.http.{HttpEntity, Writeable}
import play.api.mvc
import play.api.mvc.{Action, RawBuffer}
import razie.diesel.engine.exec.{SnakkCall, SnakkCallAsyncList}
import razie.snakked.SnakkProxyRemote
import razie.wiki.Services
import razie.{Logging, Snakk, SnakkRequest}
import scala.concurrent._
import scala.concurrent.duration._

/** a proxy server, based on polling
  *
  * example using the proxy:
  *
  * curl -X GET -H "$ELASTIC_AUTH" -H 'Content-Type: application/json' -H "kbn-xsrf: true" 'http://elastic.107.9200.snakkproxy.dieselapps.com/diagresult/_search'
*/
@Singleton
class SnakkProxyControl extends RazController with Logging {

  lazy val TOUT = 20.second

  /** proxy checking if there are any requests */
  def check (env:String, host: String) = RAction { implicit request =>
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
      val body = raw.map {a => new String(a.asByteBuffer.array(), "UTF-8")}.getOrElse("")

      log("SNAKKPROXY RECEIVED: " + body.replaceAllLiterally("\n", ""))

      val resp = Snakk.responseFromJson(body)
      SnakkCallAsyncList.complete(id, resp)
    } catch {
      case t : Throwable => log(t.toString)
    }

    Ok("")
  }

  /** client creating a request to proxy something */
  def proxy (env:String, p: String, host:String, port:String, path:String) = RAction.async { implicit request =>
    val sport = if(p == "https" && port == "443" || p == "http" && port == "80") "" else s":$port"
    val spath = if(path.startsWith("/")) path else "/"+path
    val sc = SnakkCall(p, request.method, s"$host$sport$spath", request.headers.toSimpleMap, "")
        .withEnv(env)
        .withCookies(request.ireq.cookies)
        .withSession(request.ireq.session)

    log("SNAKKPROXY Proxying - " + sc.toJson.replaceAllLiterally("\n", " "))

    val f = sc.future
    implicit val ec: ExecutionContext = Services.system.getDispatcher
    lazy val timeout = after(duration = TOUT, using = Services.system.scheduler)(Future.successful(RequestTimeout("Request timeout !!")))(ec)

    val result = f.map {response=>
      log("SNAKKPROXY returning - " + response)
      new Status(response.resCode )(response.content).withHeaders(response.headers.toSeq:_*)
    }(ec)

    Future.firstCompletedOf(Seq(result, timeout))(ec)
  }

  def proxyToNode (newUrl:String, path:String) : Action[RawBuffer] = Action(parse.raw) { implicit request =>
    val q = if (request.rawQueryString.trim.length > 0) {
      if (request.rawQueryString startsWith "?") request.rawQueryString else "?" + request.rawQueryString
    } else ""

    val raw = request.body.asBytes()

    // RAZ play 2.6 val body = raw.map(a => new String(a.asByteBuffer.array())).getOrElse("")
    val body = raw.map(a => new String(a.toArray)).getOrElse("")

    val sc = SnakkRequest("http", request.method, s"$newUrl${request.path}${q}", request.headers.toSimpleMap, body)
        .withCookies(request.cookies)

    razie.Log.info("--------PROXY------- SnakkRequest" + sc.toString())

    val response = SnakkProxyRemote.doProxy(sc, encode64 = false)

    razie.Log.info("--------PROXY------- SnakkResponse" + response.toString())

    implicit val identityWriteable: Writeable[HttpEntity.Strict] = new Writeable[HttpEntity.Strict](
      transform = _.data, contentType = Some(response.ctype)) {
      override def toEntity(a: HttpEntity.Strict): HttpEntity = HttpEntity.Strict(transform(a),
        contentType.filter(_.nonEmpty))
    }

    val xx = new mvc.Results.Status(response.resCode)(
      HttpEntity.Strict(
        ByteString.fromArray(response.decodeContent),
        Some(response.ctype).filter(_.nonEmpty)//.orElse(Some("text/plain"))
      )
    )(identityWriteable)
        .withHeaders(response.headers.toSeq: _*)


    xx //res
  }


}

