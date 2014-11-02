/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import java.io.FileInputStream
import java.util.Properties

//import admin.Config
import play.api.mvc.Request
import razie.clog

import scala.collection.mutable

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
  def urlcanon(wpath: String, page:Option[WikiEntry]) = {
    var res: Option[String] = None

    //todo make unit test for urlcfg based canon for enduroschool
    // todo optimize this somehow wiht just some lookups based on parent
    // look for configured entry points (parents) and topics
    config(URLCANON).flatMap(_.filterKeys(k=>wpath.startsWith(k)).headOption).map { site=>
      res = Some(wpath.replaceFirst("^%s".format(site._1), site._2))
    }

    //todo make unit test for tag based canon for enduroschool
    // todo optimize this somehow wiht just some lookups based on tags
    if(res.isEmpty) {
      // look for configured tags
     page.flatMap(p=>config(URLCANON).flatMap(_.filterKeys(k=>p.tags.contains(k)).headOption)).map { site=>
        res = Some(site._2 + "/" + wpath)
      }
    }

    res
  }

  /** modify external sites mapped to external URLs - NOT when on localhost:9000 though */
  def urlmap(u: String) = {
    var res = u

    //todo this looks stupid - use startsWith like in canon above
    for (has <- config(URLMAP) if(! isLocalhost); site <- has) {
      res = res.replaceFirst("^%s".format(site._1), site._2)
    }
    res
  }

  /** modify external sites mapped to external URLs */
  def urlfwd(u: String) = config(URLFWD) flatMap (_.get(u))

  /** generic site configuration */
  def sitecfg(parm: String) = config(SITECFG) flatMap (_.get(parm))

  /** find the realm from the request parameters - hostport or forwarded-for or something */
  def realm(implicit request: Request[_]) = {
    if(request.host contains "localhost") "rk" else {
      config("realm").map { m =>
        Website.getHost match {
          case Some(x) if m contains x => m(x)
          case _ => "rk"
        }
      } getOrElse "rk"
    }
  }

  /** pre-configured user types */
  def userTypes(implicit request: Request[_])  = {
    if(realm == "notes")
      // TODO configure these
      List("Individual", "Organization")
    else
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

  /** override to implement the actual configuration loading */
  def reloadUrlMap: Unit
}
