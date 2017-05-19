/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import mod.diesel.model.parser.WikiDomParser
import razie.diesel.dom.{RDomain, WikiDomain, WikiDomainImpl}
import razie.wiki.model._
import razie.wiki.parser.{WikiParserNotes, WikiParserT}

/** use custom rk parser for wikis */
class RkReactor (realm:String, fallBacks:List[Reactor], we:Option[WikiEntry]) extends ReactorImpl (realm, Nil, we) {
  override val wiki : WikiInst = new RkWikiInst(realm, fallBacks.map(_.wiki))
  override val domain : WikiDomain = new WikiDomainImpl(realm, wiki)
}

/** use custom rk parser for wikis */
class RkWikiInst(realm:String, fallBacks:List[WikiInst]) extends WikiInstImpl (realm, fallBacks) {
  class WikiParserCls(val realm:String) extends WikiParserT
  with WikiDslParser with WikiCodeParser with WikiAdParser
  with WikiDomParser with WikiParserNotes with WikiDarkParser{
    withWikiProp(adWikiProps)
    withWikiProp(codeWikiProps)
    withWikiProp(dslWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks)
    withBlocks(darkHtml)
  }

  override def mkParser = new WikiParserCls(realm)
}


