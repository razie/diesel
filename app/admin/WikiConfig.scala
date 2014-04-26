/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import java.util.Properties
import collection.mutable
import scala.Option.option2Iterable
import java.io.FileInputStream
import db.RTable
import razie.cout
import razie.clog
import play.api.mvc.Request

/**
 * configuration for the core wiki engine - some come from a property file and some from special admin pages
 *
 * create a property file rk.properties and include in classpath,
 * containing the properties below
 */
abstract class WikiConfig {
  protected lazy val props = {
    val p = new Properties();
    p.load(new FileInputStream(System.getProperty("rk.properties")))
    clog << "================ rk.properties ==================\n" + p.toString
    p
  }

  final val rk = System.getProperty("rk.home", props.getProperty("rk.home"))
  final val hostport = props.getProperty("rk.hostport")
  final val safeMode = props.getProperty("rk.safemode")
  final val analytics = true; //props.getProperty("rk.analytics").toBoolean
  final val noads = props.getProperty("rk.noads").toBoolean
  final val forcephone = props.getProperty("rk.forcephone").toBoolean

  def SUPPORT = sitecfg("support").getOrElse("support@racerkidz.com")

  def isLocalhost = "localhost:9000" == hostport

  //-------------- special admin/configuration pages

  /** if there is an external favorite canonical URL for this WPATH */
  def urlcanon(wpath: String) = {
    var res: Option[String] = None

    for (has <- config(URLCANON); site <- has if (wpath.startsWith(site._1))) {
      res = Some(wpath.replaceFirst("^%s".format(site._1), site._2))
    }
    res
  }

  /** modify external sites mapped to external URLs */
  def urlmap(u: String) = {
    var res = u

    for (has <- config(URLMAP); site <- has) {
      res = res.replaceFirst("^%s".format(site._1), site._2)
    }
    res
  }

  /** modify external sites mapped to external URLs */
  def urlfwd(u: String) = {
    for (has <- config(URLFWD); site <- has.get(u))
      yield site
  }

  /** generic site configuration */
  def sitecfg(parm: String) = {
    config(SITECFG).flatMap(_.get(parm))
  }

  /** pre-configured user types */
  def userTypes() = {
    config(USERTYPES).toList.flatMap(_.keys.toList)
  }

  def config(s: String) = {
    if (xconfig.isEmpty) reloadUrlMap
    xconfig.get(s)
  }

  /** holds the entire wiki-based configuration, can reset to reload */
  protected val xconfig = mutable.Map[String, mutable.Map[String, String]]()

  final val URLCFG = "urlcfg"
  final val URLCANON = "urlcanon"
  final val URLMAP = "urlmap"
  final val URLFWD = "urlfwd"
  final val SITECFG = "sitecfg"
  final val TOPICRED = "topicred"
  final val SAFESITES = "safesites"
  final val USERTYPES = "usertypes"
  final val BANURLS = "banurls"

  def reloadUrlMap: Unit

}
