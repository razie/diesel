package admin

import java.util.Properties

import scala.Option.option2Iterable
import model.{WikiUser, WikiConfig, WID, Wikis}
import scala.collection.mutable.HashMap
import play.api.mvc.Request

/** extended config */
object Config extends WikiConfig {
  // ------------- meta tags to include in html - useful for analytics/webmaster stuff

  final val METAS = "sitemetas"
  lazy val metas = Wikis.find(WID("Admin", METAS)) map (_.content) getOrElse ""
  def currUser = { razie.NoStaticS.get[WikiUser].map(_.asInstanceOf[model.User]) }

  final val mongodb = props.getProperty("rk.mongodb")
  final val mongohost = props.getProperty("rk.mongohost")
  final val mongouser = props.getProperty("rk.mongouser")
  final val mongopass = props.getProperty("rk.mongopass")

  final val CONNECTED = props.getProperty("rk.connected", "connected")

  final val curYear = "2015"

  def darkLight = { razie.NoStaticS.get[controllers.DarkLight] }

  def theme = {
    darkLight.map(_.css).orElse(currUser.flatMap(_.css).orElse(
      sitecfg("css"))) getOrElse ("dark")
  }

  def isLight = theme contains "light"
  def isDark = ! isLight

  // called when configuration is reloaded - use them to refresh your caches
  val cbacks = new collection.mutable.ListBuffer[() => Unit]()

  /** add a callback to be called when the configuration is refreshed - use it to regresh your own configuration and/or caches */
  def callback (f:() => Unit) = {
    cbacks append f
  }

  // parse a properties looking thing
  def parsep(content: String) = (content.split("\r\n")) filter (!_.startsWith("#")) map (_.split("=", 2)) filter (_.size == 2) map (x => (x(0), x(1)))

  var robotUserAgents = List[String]()

  def reloadUrlMap {
    println("========================== RELOADING URL MAP ==============================")
    for (c <- Array(SITECFG, TOPICRED, SAFESITES, USERTYPES, BANURLS)) {
      val urlmaps = Some(Wikis.find(WID("Admin", c)).toSeq map (_.content) flatMap parsep)
      val xurlmap = (urlmaps.map(se => HashMap[String, String](se: _*)))
      println("========================== RELOADING URL MAP ==============================")
      xurlmap.map(xconfig.put(c, _))
    }

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

    cbacks foreach (_())

    robotUserAgents = sitecfg("robots.useragents").toList.flatMap(s=>s.split("[;,]"))
  }

}

