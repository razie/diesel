package controllers

import javax.inject.Inject
import play.api.http.HttpConfiguration
import play.api.i18n.{DefaultLangs, DefaultMessagesApi, Lang, Langs, Messages, MessagesProvider}
import play.api.mvc._
import play.twirl.api.Html
import razie.diesel.engine.EContent
import razie.hosting.Website
import razie.wiki.model._
import scala.collection.mutable

// todo refactor
object Res extends Results

object StateOk {
  final val lang = Lang("en")
  final val candidates = Seq(Lang("en"))
  final val dma = new DefaultMessagesApi ()
}

/** captures the current state of what to display - passed to all views */
class StateOk (val realm:String, val au: Option[model.User], val request: Option[Request[_]]) extends MessagesProvider {
  var msg: Seq[(String, String)] = Seq.empty
  var _title : String = "" // this is set by the body as it builds itself and used by the header, heh
  val _metas = new mutable.HashMap[String,String]() // moremetas
  var _css : Option[String] = None // if you determine you want a different CSS
  var stuff : List[String] = List() // keep temp status for this request
  val bottomAd: Boolean = false
  var canonicalLink : Option[String] = None
  var _showSocial = true


  // messages provider play 2.6
  override def messages: Messages = StateOk.dma.preferred(StateOk.candidates)

  var _requireJs : Boolean = true
  def requireJs(x : Boolean): Unit = { _requireJs = x;}
  def requireJs = _requireJs

  def withCss (x:String) = {
    _css = Option(x)
    this
  }

  def withError (x:Any) = {
    msg = msg ++ Seq("err" -> x.toString)
    this
  }

  def withErrors (x:List[Any]) = {
    msg = msg ++ x.map(x => ("err" -> x.toString)).toSeq
    this
  }

  def withMessages (x:List[Any]) = {
    msg = msg ++ x.map(x => ("msg" -> x.toString)).toSeq
    this
  }

  lazy val errCollector = new VErrors()

  def showBottomAd(yes: Boolean): Unit = {}
  def showSocial(yes: Boolean): Unit = this._showSocial = yes

  def isLocalhost = request.exists(_.host.startsWith("localhost:"))

  /** current host and port */
  def hostPort:String = request.map(_.host).mkString

  /** current complete url base like https://haha.com:56 */
  val hostUrlBase = "http" + (if(this.request.get.secure) "s" else "") + "://" + this.hostPort

  lazy val form = request.flatMap(_.asInstanceOf[Request[AnyContent]].body.asFormUrlEncoded)

  lazy val query = request.map(_.queryString.map(t=>(t._1, t._2.mkString))).getOrElse(Map.empty)

  def formParms = form.map(_.collect { case (k, v) => (k, v.head) }).getOrElse (Map.empty)

  /** find current value for a form parm or EMPTY string */
  def formParm(name:String) = form.flatMap(_.get(name)).map(_.mkString).mkString

  def fParm(name:String) : Option[String] =
    form.flatMap(_.getOrElse(name, Seq.empty).headOption)

  // from query or body
  def fqParm(name:String, dflt:String) : String =
    query.get(name).orElse(fParm(name)).getOrElse(dflt)

  def fqhParm(name:String) : Option[String] =
    query.get(name).orElse(fParm(name)).orElse(request.flatMap(_.headers.get(name)))

  // useful so you don't have to typecast to AnyContent
  def qhParm(name:String) : Option[String] =
    query.get(name).orElse(request.flatMap(_.headers.get(name)))

  def fqhoParm(name:String, dflt:String) : String =
    query.get(name).orElse(fParm(name)).orElse(request.flatMap(_.headers.get(name))).getOrElse(dflt)

  /** set the title of this page */
  def title(s:String) = {
    this._title = s;
  }

  /** messages are "msg" -> "blah" or "ok" -> "blah" or "err" -> "blah" */
  def msg (imsg: Seq[(String, String)]) : StateOk = {
    msg = imsg
    this
  }

  /** messages are "msg" -> "blah" or "ok" -> "blah" or "err" -> "blah" */
  def msg (imsg: (String, String)) : StateOk = {
    msg = Seq(imsg)
    this
  }

  /** set the title of this page */
  def css = {
    val ret = _css.getOrElse {
      // session settings override everything
      request.flatMap(_.session.get("css")) orElse (
        // then user
        au.flatMap(_.css)
        ) orElse (
        // or website settings
        request.flatMap(r=> Website(r)).flatMap(_.css)
        ) getOrElse ("light")
    }

    if(ret == null || ret.length <=0) "light" else ret
  }
  def isLight = css contains "light"

  /** add a meta to this page's header */
  def meta(name:String, content:String) = {this._metas.put(name, content); ""}
  def metas = _metas.toMap

  def reactorLayout12FullPage (content: => Html) =
    Res.Ok (views.html.util.reactorLayout12FullPage(content, msg)(this))

  def reactorLayout12 (content: => Html) =
    Res.Ok (views.html.util.reactorLayout12(content, msg)(this))

  def reactorLayout12 (content: StateOk => Html) =
    Res.Ok (views.html.util.reactorLayout12(content(this), msg)(this))

  def apply (content: => Html) =
    Res.Ok (views.html.util.reactorLayout(content, msg)(this))

  def apply (content: StateOk => Html) =
    Res.Ok (views.html.util.reactorLayout(content(this), msg)(this))

  def justLayout (content: StateOk => Html) =
    views.html.util.reactorLayout(content(this), msg)(this)

  def notFound (content: StateOk => Html) =
    Res.NotFound (views.html.util.reactorLayout(content(this), msg)(this))

  def notFound12 (content: StateOk => Html) =
    Res.NotFound (views.html.util.reactorLayout12(content(this), msg)(this))

  /** use when handling forms */

  def badRequest (content: StateOk => Html) = {
    msg("err" -> "[Form has errors]")
    Res.BadRequest (views.html.util.reactorLayout12(content(this), msg)(this))
  }

  def badRequest (content: => Html) = {
    msg("err" -> "[Form has errors]")
    Res.BadRequest (views.html.util.reactorLayout12(content, msg)(this))
  }

  /** use for old templates with embedded layout OR plaint text */
  def noLayout (content: => Html) =
    Res.Ok (content)

  def noLayout (content: StateOk => Html) =
    Res.Ok (content(this))

  val website = Website.get(this.request.get)

  /** should show bottom ads */
  def showBottomAds(page: Option[WikiEntry]) = false

  /** should show side ads - either adsOnSide is explicitely defined or else inherit adsAtBottom */
  def showSideAds(page: Option[WikiEntry]) = false

  /** prepare a WID - add current realm if missing */
  def prepWid (wid:WID) =
    if(wid.realm.isDefined) wid else wid.r(realm)

  /** username, if any */
  def userName = au.fold("")(_.userName)

  def postedContent: Option[EContent] = {
    request.flatMap { request =>
      if (request.body.isInstanceOf[AnyContentAsRaw]) {
        val raw = request.body.asInstanceOf[AnyContentAsRaw].asRaw.flatMap(_.asBytes())
        Option(new EContent(
          raw.fold("")(a => new String(a.asByteBuffer.array())),
          request.contentType.mkString,
          200,
          request.headers.toSimpleMap,
          None,
          raw.map(_.asByteBuffer.array())))
      } else if (request.contentType.contains("application/json")) {
        Option(new EContent(
          request.asInstanceOf[Request[AnyContent]].body.asJson.mkString,
          request.contentType.mkString))
      } else None
    }
  }
}


