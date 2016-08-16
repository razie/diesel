package admin

import model.{User, Website}
import play.api.mvc.Request
import razie.wiki.admin.{WikiEvent, WikiObservers}
import razie.wiki.{Services, WikiConfig}
import razie.wiki.model._

import scala.Option.option2Iterable
import scala.collection.mutable.HashMap

/** extended config */
object Config extends WikiConfig {
  // ------------- meta tags to include in html - useful for analytics/webmaster stuff

  import WikiConfig.parsep

  override val noads = isLocalhost

  final val METAS = "sitemetas"
  lazy val metas = Wikis.find(WID("Admin", METAS)) map (_.content) getOrElse ""

  final val CONNECTED = props.getProperty("rk.connected", "connected")

  final val curYear = "2016" // current year for registrations

  override def simulateHost = isimulateHost
  var isimulateHost = {
//        "www.racerkidz.com"    // for testing locally
//    "www.wikireactor.com"    // for testing locally
    "www.effectiveskiing.com"    // for testing locally
//                "ski.wikireactor.com"    // for testing locally
//    "ebaysim.wikireactor.com"    // for testing locally
//            "catsim.wikireactor.com"    // for testing locally
//        "www.coolscala.com"    // for testing locally
//        "www.enduroschool.com"    // for testing locally
//            "www.askicoach.com"    // for testing locally
//        "www.glacierskiclub.com"    // for testing locally
//        "www.nofolders.net"    // for testing locally
    //        "www.dieselreactor.net"    // for testing locally
//            "gsc.wikireactor.com"    // for testing locally
//            "specs.wikireactor.com"    // for testing locally
//        "c52.wikireactor.com"    // for testing locally
  }

  final val CFG_PAGES = Array(SITECFG, TOPICRED, USERTYPES, BANURLS, URLCFG)

  WikiObservers mini {
    case WikiEvent(_, "WikiEntry", _, Some(x), _, _)
      if "Admin" == x.asInstanceOf[WikiEntry].category && CFG_PAGES.contains(x.asInstanceOf[WikiEntry].name)  => {
        reloadUrlMap
    }
  }

  def getTheme (user:Option[WikiUser], request:Option[Request[_]]) = {
    // session settings override everything
    request.flatMap(_.session.get("css")) orElse (
        // then user
        user.flatMap(_.css)
      ) orElse (
        // or website settings
        request.flatMap(r=> Website(r)).flatMap(_.css)
    ) getOrElse ("light")
  }

  // no request available
  def isLight(au:Option[User], request:Option[Request[_]]=None) = getTheme (au, request) contains "light"
  // todo remove this - relies on statics
  def oldisLight = isLight(None, None)

  def robotUserAgents = irobotUserAgents
  private var irobotUserAgents = List[String]()

  def trustedSites = itrustedSites
  private var itrustedSites = List[String]()

  def reservedNames = ireservedNames
  private var ireservedNames = List[String]()

  def reloadUrlMap {
    println("========================== RELOADING URL MAP ==============================")
    for (c <- Array(SITECFG, TOPICRED, USERTYPES, BANURLS)) {
      val urlmaps = Some(Wikis.find(WID("Admin", c)).toSeq map (_.content) flatMap parsep)
      val xurlmap = (urlmaps.map(se => HashMap[String, String](se: _*)))
      println("========================== RELOADING URL MAP ==============================")
      xurlmap.map(xconfig.put(c, _))
    }

    // concentrated all types in just one topic "urlcfg"
    for (u <- Wikis.find(WID("Admin", URLCFG)).toSeq map (_.content) flatMap parsep) {
      val RE = """([^.]+)\.(.*)""".r
      val RE(pre, prop) = u._1

      if (!xconfig.contains(pre))
        xconfig.put(pre, HashMap[String, String](prop -> u._2))
      else
        xconfig.get(pre).map(_.put(prop, u._2))
    }

    xconfig.keys.foreach(x => {
      println("============= config topic: " + x + " size " + xconfig.get(x).size)
      xconfig.get(x).foreach(y => println(y.mkString("\n  ")))
    })

    Services.configCallbacks foreach (_())

    irobotUserAgents = sitecfg("robots.useragents").toList.flatMap(s=>s.split("[;,]"))
    ireservedNames = sitecfg("reserved.names").toList.flatMap(s=>s.split("[;,]"))
    // todo settle this - there are two places for configuring trusted sites
    itrustedSites = sitecfg("trusted.sites").toList.flatMap(s=>s.split("[;,]"))
  }

}

