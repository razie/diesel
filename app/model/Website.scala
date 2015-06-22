package model

import admin.Config
import com.novus.salat._
import controllers.Application._
import controllers.{Application, Wiki}
import razie.db.RazSalatContext._
import play.api.mvc.{Request, Action}
import razie.OR._
import razie.wiki.util.{DslProps, PlayTools}
import razie.wiki.model._

/**
 * website settings - will collect website properties from the topic if it has a 'website' section
 */
class Website (we:WikiEntry, extra:Seq[(String,String)] = Seq()) extends DslProps(Some(we), "website", extra) {
  def name:String = this prop "name" OR "?"
  def url:String = this prop "url" OR "?"
  def css:Option[String] = this prop "css" // dark vs light

  def homePage:Option[WID] = this wprop "home"
  def userHomePage:Option[WID] = this wprop "userHome"

  /** blog URL - can be http://xxx or CAT:NAME */
  def blog:String = {
    this prop "blog" flatMap {b=>
      if(b startsWith "http") Some(b)
      else this wprop "blog" map (_.url)
    } getOrElse WID("Blog", "RacerKidz_Site_News").url
  }

  def twitter:String = this prop "twitter" OR "racerkid"
  def gplus:Option[String] = this prop "gplus"
  def tos:String = this prop "tos" OR "/page/Terms_of_Service"
  def privacy:String = this prop "privacy" OR "/page/Privacy_Policy"
  def support:String = this prop "support" OR "/doe/support"

  def join:String = this prop "join" OR "/doe/join"
  def parent:Option[WID] = this wprop "parent"
  def skipParent:Option[WID] = this wprop "skipParent"

  def divMain:String = this prop "divMain" OR "9"
  def showAds:String = this prop "showAds" OR "yes"
  def copyright:Option[String] = this prop "copyright"

  def rightTop:Option[WID] = this wprop "rightTop"
  def rightBottom:Option[WID] = this wprop "rightBottom"
  def about:Option[WID] = this wprop "about"

  def layout:String = this prop "layout" OR "Play:classicLayout"

  def reactor:String = this prop "reactor" OR (this prop "realm" OR "rk")

  def useWikiPrefix:Boolean = this bprop "useWikiPrefix" OR true

  //sections should be "More" "Support" "Social"
  def bottomMenu (section:String) = {
    propSeq.filter(_._1 startsWith (s"bottom.$section")).map(t=>(t._1.replaceFirst(s"bottom.$section.", ""), t._2))
  }

    //nav.TopLevel
    def navrMenu () = {
      propSeq.filter(_._1 startsWith (s"navr.")).map(t=>(t._1.replaceFirst(s"navr.", ""), t._2))
    }
    //nav.TopLevel
    def navMenu () = {
      propSeq.filter(_._1 startsWith (s"nav.")).filter(! _._1.startsWith(s"nav.Browse")).map(t=>(t._1.replaceFirst(s"nav.", ""), t._2))
    }
    def navBrowse(realm:String):String = this prop "nav.Browse" OR (
      //    if(Wikis.RK == realm) s"http://${Config.hostport}/wiki"
      //  else if(!Config.isLocalhost) s"/wiki" else s"http://${Config.hostport}/w/$realm/wiki"
      if(!Config.isLocalhost) s"/wiki" else s"http://${Config.hostport}/w/$realm/wiki"
      )

    def navNotes:String = this prop "nav.Notes" OR s"http://${Config.hostport}/notes"
    def navTheme:String = this prop "nav.Theme" OR "/doe/selecttheme"
    def navBrand = this prop "nav-brand"
}

object Website {
  private case class CacheEntry (w:Website, millis:Long)
  private val cache = new collection.mutable.HashMap[String,CacheEntry]()
  val EXP = 100000

  def apply (s:String):Option[Website] = {
    val ce = cache.get(s)
    if (ce.isEmpty) {// || System.currentTimeMillis > ce.get.millis) {
      var w = Wikis.rk.find("Site", s) map (new Website(_))
      if (w.isDefined)
        cache.put(s, CacheEntry(w.get, System.currentTimeMillis()+EXP))
      else RkReactors.forHost(s).map { r=>
        // auto-websites of type REACTOR.coolscala.com
        Reactors.findWikiEntry(r).map { rpage=> // todo no need to reload, the reactor now has the page
          // create an entry even if no website section present
          w = Some(new Website(rpage, Seq("reactor" -> r)))
          cache.put(s, CacheEntry(w.get, System.currentTimeMillis()+EXP))
        }
      }
      w
    } else
      ce.map(_.w)
  }

  def all = cache.values.map(_.w).toList

  def realm (implicit request:Request[_]) = apply(request).map(_.reactor).getOrElse(dflt.reactor)

  def apply (implicit request: Request[_]):Option[Website] = getHost flatMap Website.apply

  /** find or default */
  def get (implicit request: Request[_]) : Website = apply getOrElse dflt

  def clean (host:String):Unit = { cache.remove(host) }

  def dflt = new Website(Wikis.rk.categories.head) //todo this is stupid - find better default

  /** @deprecated use PlayTools.getHost */
  def getHost (implicit request: Request[_]) = PlayTools.getHost
}

