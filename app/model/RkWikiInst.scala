/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import mod.diesel.model.parser.WikiDomainParser
import play.api.mvc.Request
import razie.wiki.WikiConfig
import razie.wiki.parser.WikiParserNotes
import razie.wiki.parser.WikiParserT
import razie.wiki.model._
import razie.wiki.dom.WikiDomain
import razie.wiki.util.PlayTools

/** the default reactor, the main wiki */
class RkReactor (realm:String, fallBacks:List[Reactor], we:Option[WikiEntry]) extends Reactor (realm, Nil, we) {
  override val wiki : WikiInst = new RkWikiInst(realm, fallBacks.map(_.wiki))
  override val domain : WikiDomain = new WikiDomain(realm, wiki)
}

/** a wiki, used for all RK realms */
class RkWikiInst(realm:String, fallBacks:List[WikiInst]) extends WikiInst(realm, fallBacks) {
  class WikiParserCls(val realm:String) extends WikiParserT
  with WikiDslParser with WikiCodeParser with WikiAdParser
  with WikiDomainParser with WikiParserNotes with WikiDarkParser{
    withWikiProp(adWikiProps)
    withWikiProp(codeWikiProps)
    withWikiProp(dslWikiProps)
    withDotProp(notesDotProps)
    withBlocks(domainBlocks)
    withBlocks(darkHtml)
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
    } else {
      // try to find one that has a website section with this url
      // todo keep parsing the webiste section all the time
      Reactors.reactors.values.find(_.we.flatMap(_.section("section", "website")).exists {s=>
        val propSeq = WikiConfig.parsep(s.content)

        propSeq.exists(t=>t._1 == "domain" && t._2 == h)
      }).map(_.realm)
    }
  }

  def apply (request:Request[_]) = PlayTools.getHost(request).flatMap(forHost)
}
