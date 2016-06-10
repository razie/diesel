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
trait WikiAdParser extends WikiParserBase {
  import WAST._
  
  def adWikiProps = wikiPropAds

  private def wikiPropAds: PS = "{{" ~> "ad" ~> opt("[: ]".r ~> """[^}]*""".r) <~ "}}" ^^ {
    case what => {
      what match {
        case Some("lederboard") => SState(Ads.lederboard)
        case Some("square") | Some("squaretop") => SState(Ads.squaretop)
        case Some("squareright") => SState(Ads.squareright)
        case Some("squarenofloat") | Some("squareinline") => SState(Ads.squareinline)
        case _ => SState(Ads.lederboard)
      }
    }
  }
}

object Ads {
  class Type
  case object Lederboad extends Type
  case object Square extends Type
  case object None extends Type

  val lederboard = """
<script async src="//pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
<!-- 728x90all -->
<ins class="adsbygoogle"
style="display:inline-block;width:728px;height:90px"
data-ad-client="ca-pub-5622141672958561"
data-ad-slot="3920300830"></ins>
<script>
(adsbygoogle = window.adsbygoogle || []).push({});
</script>
<p>
                   """

  private val squarebase = """
<script async src="//pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
<!-- square -->
<ins class="adsbygoogle"
style="display:inline-block;width:336px;height:280px"
data-ad-client="ca-pub-5622141672958561"
data-ad-slot="4940326420"></ins>
<script>
(adsbygoogle = window.adsbygoogle || []).push({});
</script>
</div>
                   """
  val squareinline = """<div style="margin: 10px 5px 0px 5px">""" + squarebase
  val squaretopx = """ <div style="float:right;margin: -25px 5px 0px 5px"> """ + squarebase
  val squareright = """<div style="float:right">""" + squareinline + """</div>"""
  val squaretop = """<div style="float:right">""" + squaretopx + """</div>"""
}

