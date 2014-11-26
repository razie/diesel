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
import razie.wiki.model.WikiInst
import razie.wiki.dom.WikiDomain
import razie.wiki.model.Reactor

/** the default reactor, the main wiki */
object RkReactor extends Reactor (Wikis.RK) {
  override val wiki : WikiInst = RkWikiInst
  override val domain : WikiDomain = new WikiDomain(Wikis.RK, RkWikiInst)
}

/** a wiki */
class RkWikiInst(realm:String) extends WikiInst(realm) {
  class WikiParserCls(val realm:String) extends WikiParserT with DslParser with AdParser with WikiDomainParser with WikiParserNotes {
    withWikiProp(dslWikiProps)
    withWikiProp(adWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks)
  }

  override def mkParser = new WikiParserCls(realm)
}

/** a wiki */
object RkWikiInst extends RkWikiInst(Wikis.RK) {
}

