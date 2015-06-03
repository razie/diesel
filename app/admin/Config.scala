package admin

import razie.wiki.{Services, WikiConfig}
import razie.wiki.model._

import scala.Option.option2Iterable
import scala.collection.mutable.HashMap

/** extended config */
object Config extends WikiConfig {
  // ------------- meta tags to include in html - useful for analytics/webmaster stuff

  import WikiConfig.parsep

  final val METAS = "sitemetas"
  lazy val metas = Wikis.find(WID("Admin", METAS)) map (_.content) getOrElse ""
  def currUser = { razie.NoStaticS.get[WikiUser].map(_.asInstanceOf[model.User]) }

  final val CONNECTED = props.getProperty("rk.connected", "connected")

  final val curYear = "2015"

  override val simulateHost = {
//        "www.racerkidz.com"    // for testing locally
    "www.effectiveskiing.com"    // for testing locally
//                "re9.wikireactor.com"    // for testing locally
//            "www.wikireactor.com"    // for testing locally
//        "www.coolscala.com"    // for testing locally
//        "www.enduroschool.com"    // for testing locally
//            "www.askicoach.com"    // for testing locally
//        "www.glacierskiclub.com"    // for testing locally
//        "www.nofolders.net"    // for testing locally
//        "www.dieselreactor.net"    // for testing locally
  }

  def darkLight = { razie.NoStaticS.get[controllers.DarkLight] }

  def theme = {
    darkLight.map(_.css).orElse(currUser.flatMap(_.css).orElse(
      sitecfg("css"))) getOrElse ("dark")
  }

  def isLight = theme contains "light"
  def isDark = ! isLight

  def robotUserAgents = irobotUserAgents
  private var irobotUserAgents = List[String]()

  def trustedSites = itrustedSites
  private var itrustedSites = List[String]()

  def reservedNames = ireservedNames
  private var ireservedNames = List[String]()

  def reloadUrlMap {
    println("========================== RELOADING URL MAP ==============================")
    for (c <- Array(SITECFG, TOPICRED, SAFESITES, USERTYPES, BANURLS)) {
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
      println("============= config topic: " + x)
      xconfig.get(x).foreach(y => println(y.mkString("\n  ")))
    })

    Services.configCallbacks foreach (_())

    irobotUserAgents = sitecfg("robots.useragents").toList.flatMap(s=>s.split("[;,]"))
    ireservedNames = sitecfg("reserved.names").toList.flatMap(s=>s.split("[;,]"))
    itrustedSites = sitecfg("trusted.sites").toList.flatMap(s=>s.split("[;,]"))
  }

}

