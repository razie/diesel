/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import controllers.RazRequest
import razie.diesel.engine.RDExt._
import play.api.mvc.{AnyContent, Request}
import razie.diesel.engine._

import scala.Option.option2Iterable

object DomEngineHelper {

  /** */
  def settingsFrom(stok:RazRequest) = {
    val q = settingsFromRequest(stok.req)

    // find the config tag (which configuration to use - default to userId
    if(q.configTag.isEmpty && stok.au.isDefined)
      q.configTag = Some(stok.au.get._id.toString)

    // todo should keep the original user or switch?
    if(q.userId.isEmpty && stok.au.isDefined)
      q.userId = Some(stok.au.get._id.toString)

    q
  }

  /** take the settings from either URL or body form or default */
  def settingsFromRequest(request:Request[AnyContent]) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    // form query
    def fParm(name:String)=
      request.body.asFormUrlEncoded.flatMap(_.getOrElse(name, Seq.empty).headOption)

    // from query or body
    def fqParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).getOrElse(dflt)

    def fqhParm(name:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name))

    def fqhoParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).getOrElse(dflt)

    import DomEngineSettings._

    new DomEngineSettings(
      mockMode = fqhoParm(MOCK_MODE, "true").toBoolean,
      blenderMode = fqhoParm(BLENDER_MODE, "true").toBoolean,
      draftMode = fqhoParm(DRAFT_MODE, "true").toBoolean,
      sketchMode = fqhoParm(SKETCH_MODE, "false").toBoolean,
      execMode = fqhoParm(EXEC_MODE, "sync"),
      resultMode = fqhoParm(RESULT_MODE, "json"),
      parentNodeId = fqhParm("dieselNodeId"),
      configTag = fqhParm("dieselConfigTag"),
      userId = fqhParm("dieselUserId"),
      {
        if(request.contentType.exists(c=> c == "application/json")) {
          Some(new EEContent(request.body.asJson.mkString, request.contentType.get))
        } else None
      }
    )
  }
}


