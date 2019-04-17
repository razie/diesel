/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import razie.wiki.parser.{DomFiddleParser, DomParser}
import razie.diesel.dom.{RDomain, WikiDomain, WikiDomainImpl}
import razie.wiki.model._
import razie.wiki.parser.{DomFiddleParser, WikiParserNotes, WikiParserT}

/** use custom rk parser for wikis */
class RkReactor (realm:String, fallBacks:List[Reactor], we:Option[WikiEntry]) extends ReactorImpl (realm, Nil, we) {
  override val wiki : WikiInst = new RkWikiInst(realm, fallBacks.map(_.wiki))
  override val domain : WikiDomain = wiki.domain //new WikiDomainImpl(realm, wiki)
}

/** use custom rk parser for wikis */
class RkWikiInst(realm:String, fallBacks:List[WikiInst])
  extends WikiInstImpl (realm, fallBacks, {wi=>new WikiDomainImpl(realm, wi)}) {

  class WikiParserCls(val realm:String) extends WikiParserT
  with WikiDslParser with WikiCodeParser with WikiAdParser
  with DomFiddleParser with WikiParserNotes with WikiDarkParser{
    withWikiProp(adWikiProps)
    withWikiProp(codeWikiProps)
    withWikiProp(dslWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks | dfiddleBlocks)
    withBlocks(darkHtml)
  }

  override def mkParser = new WikiParserCls(realm)

  /** make a js object accessible to the scripts */
  override def mkWixJson (owe: Option[WikiPage], ou:Option[WikiUser], q:Map[String,String], r:String) : String = {
    api.wix(owe, ou, q, r).jsonBrowser
  }
}


