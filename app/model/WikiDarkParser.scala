/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import admin.Config
import razie.wiki.parser.{WAST, WikiParserBase}

/** parse dsl, fiddles and code specific fragments */
trait WikiDarkParser extends WikiParserBase {
  import WAST._
  
  def darkHtml = wikiPropImgDark | htmlDark

  private def isDark = Config.isDark

  def wikiPropImgDark: PS = "{{img" ~> """\.light|\.dark""".r ~ """[: ]""".r ~ """[^} ]*""".r ~ optargs <~ "}}" ^^ {
    case stype ~ _ ~ name ~ args => {
      if(isDark && stype.contains("dark") || !isDark && stype.contains("light")) {
        val sargs = args.foldLeft(""){(c, a) => s""" $c ${a._1}="${a._2}" """}
        SState(s"""<img src="$name" $sargs />""")
      } else {
        SState("")
      }
    }
  }

  // to not parse the content, use slines instead of lines
  /** {{section:name}}...{{/section}} */
  def htmlDark: PS = "{{" ~> opt(".") ~ """html.dark|html.light""".r ~ "}}" ~ lines <~ ("{{/" ~ """html""".r ~ "}}") ^^ {
    case hidden ~ stype ~ _ ~ lines => {
      if(isDark && stype.contains("dark") || !isDark && stype.contains("light")) {
        lines
      } else {
        SState.EMPTY
      }
    }
  }
}


