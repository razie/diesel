/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.hosting

import play.api.mvc.Request
import razie.wiki.{Config, Services}
import razie.wiki.util.PlayTools

/** multi-tenancy - identify the reactor from the current domain
  *
  * todo inject and remove hardcodings
  */
object RkReactors {
  val hostedDomains = Services.config.prop("wiki.hostedDomains", "dieselapps.com").split(",").map("." + _)

  /** find realm for the host REACTOR.dslapps.com - see if there is a known reactor for that */
  def forHost (h:String) : Option[String] = {
    // auto-websites of type REACTOR.coolscala.com
    if (
      hostedDomains.exists(d=> h.endsWith(d))
    ) {
      // extract reactor
      val r = h.substring(0, h.indexOf('.')).toLowerCase
      WikiReactors.lowerCase.get(WikiReactors.ALIASES.getOrElse(r, r)) orElse {
        // maybe it was not loaded yet
        WikiReactors.allReactors.find(_._1.toLowerCase == r).map(_._1)
      }
    } else {
      // try to find one that has a website section with this url
      // todo perf keep parsing the webiste section all the time
      WikiReactors
        .reactors
        .values
        .find(_.props.prop("domain").exists(_ == h))
        .map(_.realm)
    }
  }

  def apply (request:Request[_]) = PlayTools.getHost(request).flatMap(forHost)
}
