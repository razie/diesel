package model

import admin.Config
import com.novus.salat._
import controllers.Application._
import controllers.{Application, Wiki}
import razie.db.RazSalatContext._
import play.api.mvc.{Request, Action}
import razie.OR._
import razie.wiki.util.PlayTools

/**
 * dsl processing - entries with a props section
 */
class DslProps (val we:WikiEntry, section:String) {
  lazy val propSeq = (we.section("section", section).toArray flatMap (ws=>admin.Config.parsep(ws.content)))
  lazy val props = propSeq.toMap[String,String]

  def prop (s:String) = props get s
  def wprop (s:String) = (this prop s).flatMap(x=>WID.fromPath(x))
  def bprop (s:String) = (this prop s).map(_.toBoolean)

  override def toString = propSeq.mkString
}

/**
 * website settings
 */
class Website (we:WikiEntry) extends DslProps(we, "website") {
  def name:String = this prop "name" OR "?"
  def url:String = this prop "url" OR "?"
  def css:Option[String] = this prop "css" // dark vs light
  def ttl:Int = (this prop "ttl" OR "60000").toInt

  def homePage:Option[WID] = this wprop "home"
  def blog:WID = this wprop "blog" OR WID("Blog", "RacerKidz_Site_News")
  def twitter:String = this prop "twitter" OR "racerkid"
  def gplus:Option[String] = this prop "gplus"
  def tos:String = this prop "tos" OR "/page/Terms_of_Service"
  def privacy:String = this prop "privacy" OR "/page/Privacy_Policy"

  def join:String = this prop "join" OR "/doe/join"
  def navBrowse(realm:String):String = this prop "nav.browse" OR (if(Wikis.RK == realm) s"http://${Config.hostport}/wiki" else s"http://${Config.hostport}/w/$realm/wiki")

  def parent:Option[WID] = this wprop "parent"
  def skipParent:Option[WID] = this wprop "skipParent"

  def divMain:String = this prop "divMain" OR "9"
  def showAds:String = this prop "showAds" OR "yes"
  def rightTop:Option[WID] = this wprop "rightTop"
  def rightBottom:Option[WID] = this wprop "rightBottom"
  def about:Option[WID] = this wprop "about"

  def reactor:String = this prop "reactor" OR "rk"

  def useWikiPrefix:Boolean = this bprop "useWikiPrefix" OR true

  //sections should be "More" "Support" "Social"
  def bottomMenu (section:String) = {
    propSeq.filter(_._1 startsWith (s"bottom.$section")).map(t=>(t._1.replaceFirst(s"bottom.$section.", ""), t._2))
  }
}

object Website {
  case class CacheEntry (w:Website, millis:Long)
  val cache = new collection.mutable.HashMap[String,CacheEntry]()

  def apply (s:String):Option[Website] = {
    val ce = cache.get(s)
    if (ce.isEmpty || System.currentTimeMillis > ce.get.millis) {
      val w = Wikis.rk.find("Site", s) map (new Website(_))
      if (w.isDefined)
        cache.put(s, CacheEntry(w.get, System.currentTimeMillis()+w.get.ttl))
      w
    } else
      ce.map(_.w)
  }

  def apply (implicit request: Request[_]):Option[Website] = getHost flatMap Website.apply

  def dflt = new Website(Wikis.rk.categories.head) //todo this is stupid - find better default

  /** @deprecated use PlayTools.getHost */
  def getHost (implicit request: Request[_]) = PlayTools.getHost
}

/**
 * website settings
 */
class Realm (we:WikiEntry) extends DslProps(we, "realm") {
  def name:String = this prop "name" OR "?"
  def domain:String = this prop "domain" OR "?"
//  def css:Option[String] = this prop "css" // dark vs light
  def ttl:Int = (this prop "ttl" OR "60000").toInt

  //sections should be "More" "Support" "Social"
  def bottomMenu (section:String) = {
    propSeq.filter(_._1 startsWith (s"bottom.$section")).map(t=>(t._1.replaceFirst(s"bottom.$section.", ""), t._2))
  }
}

object Realms {
  case class CacheEntry (w:Realm, millis:Long)
  val cache = new collection.mutable.HashMap[String,CacheEntry]()

  //todo as we scale - load/unload reactors from memory
  def apply (s:String):Option[Realm] = {
    val ce = cache.get(s)
    if (ce.isEmpty || System.currentTimeMillis > ce.get.millis) {
      val w = Wikis.rk.find("Reactor", s) map (new Realm(_))
      if (w.isDefined)
        cache.put(s, CacheEntry(w.get, System.currentTimeMillis()+w.get.ttl))
      w
    } else
      ce.map(_.w)
  }

  def apply (implicit request: Request[_]):Option[Realm] = getRealm flatMap Realms.apply

  def dflt = new Realm(Wikis.rk.categories.head) //todo this is stupid - find better default

  def getRealm (implicit request: Request[_]) = {
    val h = Website.getHost(request)
    //todo optimize
    cache.values.find(_.w.domain.split(",") contains h).map(_.w.name) orElse Some(Wikis.RK)
  }

}

