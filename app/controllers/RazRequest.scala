package controllers

import model._
import play.api.mvc._
import razie.hosting.Website
import razie.wiki.Services
import razie.wiki.admin.SecLink

/** trying some type foolery - pass this off as a Request[_] as well and proxy to original */
class RazRequest (irealm:String, iau:Option[User], val ireq:Request[_], operation:String="") extends StateOk (
  irealm,
  iau orElse Services.auth.authUser(ireq).asInstanceOf[Option[User]],
  Some(ireq))
//  with play.api.mvc.RequestHeader
{

  def this (ireq:Request[_]) = this(
    Website.getRealm (ireq),
    None,
    ireq
  )

  /** check if an api key is needed in this realm and one is passed in and it's matthing OR also true if none is needed */
  // todo should be per user and user assigned to reactor I think... but we can also do per reactor, what the heck
  // todo see the needsApiKey logic - if not assigned per realm, we'll need a diesel.needsApikey setting then...
  def validateXApiKey = {
    val reactor = stok.website.dieselReactor
    val website = Website.forRealm(reactor).getOrElse(stok.website)
    val xapikey = website.prop("diesel.xapikey")

    def needsApiKey = xapikey.isDefined // just for historical reasons - this meant "needsApiKey"
    val xx = this.qhParm("X-Api-Key").mkString

    val isApiKeyGood = xapikey.isDefined && xapikey.exists { x =>
      x.length > 0 && x == xx
    }

    isApiKeyGood
  }

  def req = ireq.asInstanceOf[Request[AnyContent]]
  def oreq = Some(ireq)
  //  lazy val au =

  lazy val stok = this //new controllers.StateOk(Seq(), realm, au, Some(request))

  def session = ireq.session
  def flash = ireq.flash

  def id : scala.Long = ireq.id
  //def tags : scala.Predef.Map[scala.Predef.String, scala.Predef.String] = ireq.tags
  def headers : play.api.mvc.Headers = ireq.headers
  def queryString : scala.Predef.Map[scala.Predef.String, scala.Seq[scala.Predef.String]] = ireq.queryString
  def path : scala.Predef.String = ireq.path
  def uri : scala.Predef.String = ireq.uri
  def method : scala.Predef.String = ireq.method
  def version : scala.Predef.String = ireq.version
  def remoteAddress : scala.Predef.String = ireq.remoteAddress
  def secure : scala.Boolean = ireq.secure

  def verifySecLink : Boolean = req.flash.get(SecLink.HEADER).flatMap(SecLink.find).exists(_.verify)

  /** default transaction per request */
  var isTxnSet = false
  lazy val txn = {
    isTxnSet = true
    razie.db.tx.t(
      (if(operation.length > 0) operation else req.path),
      au.map(_.userName).getOrElse("?"))
  }

//  override def id : scala.Long = ireq.id
//  override def tags : scala.Predef.Map[scala.Predef.String, scala.Predef.String] = ireq.tags
//  override def headers : play.api.mvc.Headers = ireq.headers
//  override def queryString : scala.Predef.Map[scala.Predef.String, scala.Seq[scala.Predef.String]] = ireq.queryString
//  override def path : scala.Predef.String = ireq.path
//  override def uri : scala.Predef.String = ireq.uri
//  override def method : scala.Predef.String = ireq.method
//  override def version : scala.Predef.String = ireq.version
//  override def remoteAddress : scala.Predef.String = ireq.remoteAddress
//  override def secure : scala.Boolean = ireq.secure
}

// todo I can't use this - when using it, too many errors from implicits and stuff
class BetterRazRequest[A] (val request:Request[A]) extends WrappedRequest[A] (request) {
  def oreq = Some(request)
  lazy val au = Services.auth.authUser(request).asInstanceOf[Option[User]]
  lazy val realm = Website.getRealm (request)
  lazy val stok = new controllers.StateOk(realm, au, Some(request))
  lazy val errCollector = new VErrors()
}


