package admin

import model.Website
import play.api.Play
import play.api.mvc.Request
import razie.wiki.model.WikiObservers
import razie.wiki.{Services, WikiConfig}
import razie.wiki.model._

import scala.Option.option2Iterable
import scala.collection.mutable.HashMap

/** extended config */
object Config extends WikiConfig {
  final val curYear = "2017" // just FYI basicaly, each club has its own year

  // todo this is not working. Need to inject configuration here
//  def appConf (s:String) = Play.current.configuration.getString(s).mkString

  import WikiConfig.parsep

  override def simulateHost = isimulateHost
  var isimulateHost = {
    //      "www.snowproapp.com"
//      "www.racerkidz.com"    // for testing locally
//      "www.effectiveskiing.com"    // for testing locally
    "specs.dieselapps.com"    // for testing locally
//        "www.dieselapps.com"    // for testing locally
//    "wiki.dieselapps.com"    // for testing locally
//    "ebaysim.dieselapps.com"    // for testing locally
//    "catsim.dieselapps.com"    // for testing locally
//    "www.coolscala.com"    // for testing locally
//    "www.enduroschool.com"    // for testing locally
//    "www.askicoach.com"    // for testing locally
//    "www.glacierskiclub.com"    // for testing locally
//    "notes.razie.com"    // for testing locally
//    "www.dieselreactor.net"    // for testing locally
//    "gsc.dieselapps.com"    // for testing locally
//    "c52.dieselapps.com"    // for testing locally
  }

  private var ibadIps = Array("178.175.146.90")
  def badIps = ibadIps

  override def getTheme (user:Option[WikiUser], request:Option[Request[_]]) = {
    // session settings override everything
    request.flatMap(_.session.get("css")) orElse (
      // then user
      user.flatMap(_.css)
      ) orElse (
      // or website settings
      request.flatMap(r=> Website(r)).flatMap(_.css)
      ) getOrElse ("light")
  }

  final val CFG_PAGES = Array(SITECFG, TOPICRED, USERTYPES, BANURLS, URLCFG)

  WikiObservers mini {
    case WikiEvent(_, "WikiEntry", _, Some(x), _, _, _)
      if "Admin" == x.asInstanceOf[WikiEntry].category && CFG_PAGES.contains(x.asInstanceOf[WikiEntry].name)  => {
        reloadUrlMap
    }
  }

  // using sync here, although access is not... sometimes weird conflict
  // todo remove the object and reload atomically the Services.config
  def reloadUrlMap = synchronized {
    println("========================== RELOADING URL MAP ==============================")
    for (c <- Array(SITECFG, TOPICRED, USERTYPES, BANURLS)) {
      val urlmaps = Some(Wikis.find(WID("Admin", c)).toSeq map (_.content) flatMap parsep)
      val xurlmap = (urlmaps.map(se => HashMap[String, String](se: _*)))
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
//      println("============= config topic: " + x + " size " + xconfig.get(x).size)
//      xconfig.get(x).foreach(y => println(y.mkString("\n  ")))
    })

    Services ! new WikiConfigChanged

    irobotUserAgents = sitecfg("robots.useragents").toList.flatMap(s=>s.split("[;,]"))
    ireservedNames = sitecfg("reserved.names").toList.flatMap(s=>s.split("[;,]"))
//     todo settle this - there are two places for configuring trusted sites
    itrustedSites = sitecfg("trusted.sites").toList.flatMap(s=>s.split("[;,]"))
    ibadIps = sitecfg("badips").toList.flatMap(s=>s.split("[;,]")).toArray
  }

}

