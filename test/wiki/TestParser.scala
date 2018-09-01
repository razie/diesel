
package wiki

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import razie.tconf.parser.SState
import razie.wiki.model.{WID, Wikis}
import razie.wiki.parser.{WAST, WikiParserT}

object nWikiParser extends WikiParserT {
  def realm =  Wikis.RK
}

class TestParser extends FlatSpec with ShouldMatchers {
  TestInit.init

  def applys (s:String) = nWikiParser.apply(s).fold(WAST.context(None))

  "WikiParser" should "escape bad html" in {
    "&lt;applet&gt;" === (this applys "<applet>").s
    "&lt;iframe&gt;" === (this applys "<iframe>").s
    "&lt;iframe gg=\"1\"&gt;" === (this applys "<iframe gg=\"1\">").s
    "&lt;/iframe&gt;" === (this applys "</iframe>").s
    "<small>" === (this applys "<small>").s
    "</small>" === (this applys "</small>").s
  }

  val wid = WID("?", "?")
  "WikiParser" should "recognize [[]] and [[[]]]" in {
    "<a href=\"/wiki/any:Sport\">Sport</a>" === (Wikis.format(wid, Wikis.MD, "[[Sport]]", None, None))
    "<a href=\"/wiki/any:Sport\">Curu</a>" === (Wikis.format(wid, Wikis.MD, "[[Sport|Curu]]", None, None))
    "<a href=\"/wiki/any:Sport\">Curu</a>" === (Wikis.format(wid, Wikis.MD, "[[Sport | Curu]]", None, None))
    "<a href=\"/wiki/Club:Offroad_Ontario\">Curu</a>" === (Wikis.format(wid, Wikis.MD, "[[Club:Offroad Ontario | Curu]]", None, None))
    "<a href=\"/wiki/any:Sport\">Sport</a> <a href=\"/wiki/any:Club\">Club</a>" === (Wikis.format(wid, Wikis.MD, "[[Sport]] [[Club]]", None, None))
    "<a href=\"/wiki/any:Sport\">Sport</a> haha: <a href=\"/wiki/any:Privacy_Policy\">Privacy Policy" === (Wikis.format(wid, Wikis.MD, "[[Sport]] haha: [[Privacy Policy]]", None, None))
  }

  "WikiParser" should "recognize roles in [[[]]]" in {
    "<a href=\"/wiki/Club:Offroad_Ontario\">Curu</a>" === (Wikis.format(wid, Wikis.MD, "[[friend::Club:Offroad Ontario | Curu]]", None, None))
//    (Wikis.format(wid, Wikis.MD, "[[friend::Category:Sport]]"))
  }

  "WikiParser" should "recognize these " in {
    "" === (applys(""))
    "some sentence" === (applys("some sentence"))
    "\n" === (applys("\n"))
    "\na\nb\n" === (applys("\na\nb\n"))
    "a\nb\n\n" === (applys("a\nb\n\n"))
    "aa\nbb" === (applys("aa\nbb"))
    "{{Date 2011-07-24}}" === (applys("{{when:2011-07-24}}"))
    "haha {{Date 2011-07-24}}\nhehe" === (applys("haha {{when:2011-07-24}}\nhehe"))
    SState("haha Date: 2011-07-24\nhehe", Map("when" -> "2011-07-24")) === (nWikiParser.apply("haha {{when:2011-07-24}}\nhehe"))

    "_____habibi__" === Wikis.formatName("[] /:habibi{}")
    //
  }

  "WikiParser" should "like dates" in {
    "2012-01-21" === nWikiParser.parseAll(nWikiParser.date1, "2012-01-21").get
    "2012-October-14" === nWikiParser.parseAll(nWikiParser.date2, "October 14, 2012").get
  }

  scala.math.min(3,5)
}
