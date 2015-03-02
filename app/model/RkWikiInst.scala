/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import model.dom.WikiDomainParser
import play.api.mvc.Request
import razie.wiki.parser.WikiParserNotes
import razie.wiki.parser.WikiParserT
import razie.wiki.model.{Reactors, Wikis, WikiInst, Reactor}
import razie.wiki.dom.WikiDomain
import razie.wiki.util.PlayTools

/** the default reactor, the main wiki */
class RkReactor (realm:String, fallBack:Option[Reactor]) extends Reactor (realm) {
  override val wiki : WikiInst = new RkWikiInst(realm, fallBack.map(_.wiki))
  override val domain : WikiDomain = new WikiDomain(realm, wiki)
}

/** a wiki, used for all RK realms */
class RkWikiInst(realm:String, fallBack:Option[WikiInst]) extends WikiInst(realm, fallBack) {
  class WikiParserCls(val realm:String) extends WikiParserT with WikiDslParser with WikiCodeParser with AdParser with WikiDomainParser with WikiParserNotes {
    withWikiProp(adWikiProps)
    withWikiProp(codeWikiProps)
    withWikiProp(dslWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks)
  }

  override def mkParser = new WikiParserCls(realm)
}

object RkReactors {
  /** for the host REACTOR.wikireactor.com - see if there is a known reactor for that */
  def forHost (h:String) : Option[String] = {
    // auto-websites of type REACTOR.coolscala.com
    if (h.endsWith(".coolscala.com") ||
      h.endsWith(".wikireactor.com") ||
      h.endsWith(".dslcloud.com") ||    /* the testing website */
      h.endsWith(".dieselapps.com")
    ) {
      // extract reactor
      val r = h.substring(0, h.indexOf('.')).toLowerCase
      Reactors.lowerCase.get(Reactors.ALIASES.getOrElse(r, r))
    } else None
  }

  def apply (request:Request[_]) = PlayTools.getHost(request).flatMap(forHost)
}
