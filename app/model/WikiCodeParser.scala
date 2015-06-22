/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import org.bson.types.ObjectId
import razie.{cdebug, cout, clog}
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.matching.Regex.Match
import scala.util.parsing.combinator.RegexParsers
import scala.Option.option2Iterable
import scala.collection.mutable
import razie.wiki.parser.WAST
import razie.wiki.parser.WikiParserBase

/** parse dsl, fiddles and code specific fragments */
trait WikiCodeParser extends WikiParserBase {
  import WAST._
  
  def codeWikiProps = wikiPropScript | wikiPropCall | wikiPropExpr

  def wikiPropScript: PS = "{{" ~> """def|lambda""".r ~ "[: ]".r ~ """[^:}]*""".r ~ ":" ~ """[^}]*""".r ~ "}}" ~ lines <~ ("{{/def}}" | "{{/lambda}}" | "{{/}}") ^^ {
    case stype ~ _ ~ name ~ _ ~ sign ~ _ ~ lines => {
      if ("lambda" == stype)
        SState("`{{call:#" + name + "}}`") // lambdas are executed right there...
      else
        SState("`{{" + stype + ":" + name + "}}`") // defs are expanded in pre-processing and executed in display
    }
  }

  def wikiPropCall: PS = "{{" ~> """call""".r ~ "[: ]".r ~ opt("""[^#}]*""".r) ~ "#" ~ """[^}]*""".r <~ "}}" ^^ {
    case stype ~ _ ~ page ~ _ ~ name => {
      SState("`{{" + stype + ":" + (page getOrElse "") + "#" + name + "}}`")
      // calls are executed in display
    }
  }

  def wikiPropExpr: PS = "{{" ~> ( "e" | "e.js" | "e.scala") ~ "[: ]".r ~ """[^}]*""".r <~ "}}" ^^ {
    case stype ~ _ ~ body => {
      SState("`{{" + stype + ":" + body + "}}`") // are evaluated on display - just check syntax here
      // calls are executed in display
    }
  }

}


