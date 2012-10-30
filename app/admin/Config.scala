package admin

import model.Mongo
import model.Wikis
import model.WID

/** configuration settings */
object Config {
  lazy val rk = System.getProperty("rk.home", "www.racerkidz.com")
  lazy val hostport = System.getProperty("rk.hostport", "localhost:9000")
  lazy val safeMode = System.getProperty("safemode", "none")
  final val mongodb = System.getProperty("mongodb", "rk")
  final val mongohost = System.getProperty("mongohost", "localhost")
  final val mongouser = System.getProperty("mongouser", "r")
  final val mongopass = System.getProperty("mongopass", "r")
  final val analytics = System.getProperty("analytics", "false").toBoolean
  final val noads = System.getProperty("noads", "false").toBoolean
  
  def SUPPORT = admin.Config.sitecfg("support").getOrElse("support@racerkidz.com")
  
  final val forcePhone = System.getProperty("forcePhone", "false").toBoolean

  def isLocalhost = "localhost:9000" == hostport
  
  //-------------- special pages
    // modify external sites mapped to external URLs
  def urlmap(u: String) = {
    var res = u

    for (has <- config(URLMAP); site <- has) {
      res = res.replaceFirst("^%s".format(site._1), site._2)
    }
    res
  }

  // modify external sites mapped to external URLs
  def urlfwd(u: String) = {
    for (has <- config(URLFWD); site <- has.get(u))
      yield site
  }

  // site cfg parms
  def sitecfg(parm: String) = {
    config(SITECFG).flatMap(_.get(parm))
  }

  // site cfg parms
  def userTypes() = {
    config(USERTYPES).toList.flatMap(_.keys.toList)
  }

  def config(s: String) = {
    if (xconfig.isEmpty) reloadUrlMap
    xconfig.get(s)
  }

  private val xconfig = scala.collection.mutable.Map[String, Map[String, String]]()

  final val URLMAP = "urlmap"
  final val URLFWD = "urlfwd"
  final val SITECFG = "sitecfg"
  final val TOPICRED = "topicred"
  final val SAFESITES = "safesites"
  final val USERTYPES = "usertypes"

  def reloadUrlMap {
    for (c <- Array(URLMAP, URLFWD, SITECFG, TOPICRED, SAFESITES, USERTYPES)) {
//      val urlmap = Some(Mongo("WikiEntry").find(Map("category" -> "Admin", "name" -> c)) map (grater[WikiEntry].asObject(_)) map (_.content) flatMap (_.split("\r\n")) filter (!_.startsWith("#")) map (_.split("=")) filter (_.size == 2) map (x => (x(0), x(1))))
      val urlmaps = Some(Wikis.find(WID("Admin", c)).toSeq map (_.content) flatMap (_.split("\r\n")) filter (!_.startsWith("#")) map (_.split("=")) filter (_.size == 2) map (x => (x(0), x(1))))
      val xurlmap = urlmaps.map(_.toMap)
      println("========================== RELOADING URL MAP ==============================")
      xurlmap.map(xconfig.put(c, _))
    }
    xconfig.keys.foreach(x => {
      println("============= config topic: " + x)
      xconfig.get(x).foreach(y => println(y.mkString("\n  ")))
    })
  }
}

