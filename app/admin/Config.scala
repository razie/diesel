package admin

import java.util.Properties
import scala.Option.option2Iterable
import model.WID
import model.Wikis

/** extended config */
object Config extends WikiConfig {
  // ------------- meta tags to include in html - useful for analytics/webmaster stuff

  final val METAS = "sitemetas"
  lazy val metas = Wikis.find(WID("Admin", METAS)) map (_.content) getOrElse ""
  def currUser = { razie.NoStaticS.get[model.WikiUser].map(_.asInstanceOf[model.User]) }

  final val mongodb = props.getProperty("rk.mongodb")
  final val mongohost = props.getProperty("rk.mongohost")
  final val mongouser = props.getProperty("rk.mongouser")
  final val mongopass = props.getProperty("rk.mongopass")

  final val curYear = "2013"
  
  def darkLight = { razie.NoStaticS.get[controllers.DarkLight] }

  def theme = {
    darkLight.map(_.css).orElse(currUser.flatMap(_.css).orElse(sitecfg("css"))) getOrElse ("dark")
  }

  def reloadUrlMap {
    println("========================== RELOADING URL MAP ==============================")
    for (c <- Array(URLMAP, URLFWD, SITECFG, TOPICRED, SAFESITES, USERTYPES)) {
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

