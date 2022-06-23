/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import razie.tconf.parser.StrAstNode
import razie.wiki.parser.ParserBase

/** parse dsl, fiddles and code specific fragments */
trait WikiAdParser extends ParserBase {
  
  def adWikiProps = wikiPropAds

  private def wikiPropAds: PS = "{{" ~> "ad" ~> opt("[: ]".r ~> """[^}]*""".r) <~ "}}" ^^ {
    case what => {
      what match {
        case _ => StrAstNode("")
      }
    }
  }
}
