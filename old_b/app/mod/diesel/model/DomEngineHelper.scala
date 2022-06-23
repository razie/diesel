/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import controllers.RazRequest
import play.api.mvc._
import razie.diesel.engine.DomEngineSettings.{DIESEL_CONFIG_TAG, DIESEL_HOST, DIESEL_NODE_ID, DIESEL_USER_ID}
import razie.diesel.engine.{DomEngineSettings, _}
import scala.Option.option2Iterable

object DomEngineHelper {

  /** */
  def settingsFrom(stok: RazRequest) = {
    val q = settingsFromRequest(stok.req)

    // find the config tag (which configuration to use - default to userId
    if (q.configTag.isEmpty && stok.au.isDefined)
      q.configTag = Some(stok.au.get._id.toString)

    // todo should keep the original user or switch?
    if (q.userId.isEmpty && stok.au.isDefined)
      q.userId = Some(stok.au.get._id.toString)

    if (q.draftMode)
      q.configTag = Some(q.configTag.mkString + ",draft")

    q.realm = Some(stok.realm)
    q
  }

  /** take the settings from either URL or body form or default */
  private def settingsFromRequest(request:Request[AnyContent]) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    // form query
    def fParm(name:String)=
      if(request.body.isInstanceOf[AnyContentAsFormUrlEncoded])
        request.body.asFormUrlEncoded.flatMap(_.getOrElse(name, Seq.empty).headOption)
      else None

    // from query or body
    def fqParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).getOrElse(dflt)

    def fqhParm(name: String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).filter(_.trim.length > 0)

    def fqhoParm(name: String, dflt: String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).getOrElse(dflt)

    import DomEngineSettings._

    val hp = (if (request.secure) "https" else "http") + "://" + request.host

    new DomEngineSettings(
      mockMode = fqhoParm(MOCK_MODE, "true").toBoolean,
      blenderMode = fqhoParm(BLENDER_MODE, "true").toBoolean,
      draftMode = fqhoParm(DRAFT_MODE, "true").toBoolean,
      sketchMode = fqhoParm(SKETCH_MODE, "false").toBoolean,
      execMode = fqhoParm(EXEC_MODE, "sync"),
      resultMode = fqhoParm(RESULT_MODE, ""),
      parentNodeId = fqhParm(DIESEL_NODE_ID),
      configTag = fqhParm(DIESEL_CONFIG_TAG),
      userId = fqhParm(DIESEL_USER_ID).filter(_.trim.length > 5),
      postedContent = {
        if (request.body.isInstanceOf[AnyContentAsRaw]) {
          val raw = request.body.asRaw.flatMap(_.asBytes())
          Some(new EContent(
            raw.map(a => new String(a)).getOrElse(""),
            request.contentType.mkString,
            200,
            request.headers.toSimpleMap,
            None,
            raw))
        } else if (request.contentType.exists(c => c == "application/json")) {
          Some(new EContent(
            request.body.asJson.mkString,
            request.contentType.mkString))
        } else None
      },
      tagQuery = fqhParm(TAG_QUERY),
      simMode = fqhoParm(SIM_MODE, "false").toBoolean,
      dieselHost = Some(hp),
      sla = if(fqhoParm(SLA, "").length > 0) Some(fqhoParm(SLA, "")) else None
    )
  }

  /** take the settings from request header */
  def settingsFromRequestHeader(request:RequestHeader, cont:Option[EContent]) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String) : Option[String] = None

    // from query or body
    def fqParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).getOrElse(dflt)

    def fqhParm(name:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).filter(_.trim.length > 0)

    def fqhoParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).getOrElse(dflt)

    import DomEngineSettings._

    new DomEngineSettings(
      mockMode = fqhoParm(MOCK_MODE, "true").toBoolean,
      blenderMode = fqhoParm(BLENDER_MODE, "true").toBoolean,
      draftMode = fqhoParm(DRAFT_MODE, "true").toBoolean,
      sketchMode = fqhoParm(SKETCH_MODE, "false").toBoolean,
      execMode = fqhoParm(EXEC_MODE, "sync"),
      resultMode = fqhoParm(RESULT_MODE, ""),
      parentNodeId = fqhParm(DIESEL_NODE_ID),
      configTag = fqhParm(DIESEL_CONFIG_TAG),
      userId = fqhParm(DIESEL_USER_ID),
      cont,
      tagQuery = fqhParm(TAG_QUERY),
      simMode = fqhoParm(SIM_MODE, "false").toBoolean,
      dieselHost = Some(request.host)
    )
  }

  /** other attributes in the request not related to settings - use these as input
    *
    * combine the queryParms with the posted form parms
    */
  def parmsFromRequestHeader(request:RequestHeader, cont:Option[EContent] = None) : Map[String,String] = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    q.filter(t => !(HEADERS_FILTER contains t._1))
  }

  /** other attributes in the request not related to settings - use these as input
    *
    * combine the queryParms with the posted form parms
    */
  def headers(request: RequestHeader, cont: Option[EContent] = None): Map[String, String] = {
    val q = request.headers.toSimpleMap

    q
        .filter(t => !(HEADERS_FILTER contains t._1))
        .map(t => if (HEADERS_SCRAMBLE contains t._1) (t._1, "*******") else t)
  }

  // these headers are filtered
  val HEADERS_FILTER = DomEngineSettings.FILTER ++
      Array(DIESEL_NODE_ID, DIESEL_CONFIG_TAG, DIESEL_USER_ID, DIESEL_HOST, dieselSourceHost)

  // these headers are scrambled
  val HEADERS_SCRAMBLE = Array("X-Api-Key", "Authorization")

  final val dieselFlowId = "dieselFlowId"
  final val dieselSourceHost = "dieselSourceHost"
}


