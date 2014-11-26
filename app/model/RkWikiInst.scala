/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import model.dom.WikiDomainParser
import razie.wiki.parser.WikiParserNotes
import razie.wiki.parser.WikiParserT
import razie.wiki.model.{Wikis, WikiInst, Reactor}
import razie.wiki.dom.WikiDomain

/** the default reactor, the main wiki */
class RkReactor (realm:String) extends Reactor (realm) {
  override val wiki : WikiInst = new RkWikiInst(realm)
  override val domain : WikiDomain = new WikiDomain(realm, wiki)
}

/** a wiki, used for all RK realms */
class RkWikiInst(realm:String) extends WikiInst(realm) {
  class WikiParserCls(val realm:String) extends WikiParserT with DslParser with AdParser with WikiDomainParser with WikiParserNotes {
    withWikiProp(adWikiProps)
    withWikiProp(dslWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks)
  }

  override def mkParser = new WikiParserCls(realm)
}

