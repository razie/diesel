/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import razie.wiki.Services
import razie.wiki.parser.{WAST, WikiParserBase}

/** elemnts for dark/light backgrounds */
trait WikiDarkParser extends WikiParserBase {
  import WAST._
  
  def darkHtml = wikiPropImgDark | htmlDark

  // todo remove this - relies on statics
  private def isDark = !Services.config.oldisLight

  def wikiPropImgDark: PS = "{{img" ~> """\.light|\.dark""".r ~ """[: ]""".r ~ """[^} ]*""".r ~ optargs <~ "}}" ^^ {
    case stype ~ _ ~ name ~ args => {
      LazyState {(current, ctx) =>
        if(isDark && stype.contains("dark") || !isDark && stype.contains("light")) {
          val sargs = args.foldLeft(""){(c, a) => s""" $c ${a._1}="${a._2}" """}
          SState(s"""<img src="$name" $sargs />""")
        } else {
          SState("")
        }
      }
    }
  }

  // to not parse the content, use slines instead of lines
  def htmlDark: PS = "{{" ~> opt(".") ~ """html.dark|html.light""".r ~ "}}" ~ lines <~ ("{{/" ~ """html""".r ~ "}}") ^^ {
    case hidden ~ stype ~ _ ~ lines => {
      LazyState {(current, ctx) =>
        if(isDark && stype.contains("dark") || !isDark && stype.contains("light")) {
          lines.fold(ctx)
        } else {
          SState.EMPTY
        }
      }
    }
  }
}


