

import model.WikiParser
import model.Wikis
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class TestWiki extends FlatSpec with ShouldMatchers {

  "WikiParser" should "escape bad html" in {
    "&lt;applet&gt;" === (WikiParser apply "<applet>").s
    "&lt;iframe&gt;" === (WikiParser apply "<iframe>").s
    "&lt;iframe gg=\"1\"&gt;" === (WikiParser apply "<iframe gg=\"1\">").s
    "&lt;/iframe&gt;" === (WikiParser apply "</iframe>").s
    "<small>" === (WikiParser apply "<small>").s
    "</small>" === (WikiParser apply "</small>").s
  }

  "WikiParser" should "recognize [[]] and [[[]]]" in {
    "<a href=\"/wiki/any:Sport\">Sport</a>" === (Wikis.format(Wikis.MD, "[[Sport]]"))
    "<a href=\"/wiki/any:Sport\">Curu</a>" === (Wikis.format(Wikis.MD, "[[Sport|Curu]]"))
    "<a href=\"/wiki/any:Sport\">Curu</a>" === (Wikis.format(Wikis.MD, "[[Sport | Curu]]"))
    "<a href=\"/wiki/Club:Offroad_Ontario\">Curu</a>" === (Wikis.format(Wikis.MD, "[[Club:Offroad Ontario | Curu]]"))
    "<a href=\"/wiki/any:Sport\">Sport</a> <a href=\"/wiki/any:Club\">Club</a>" === (Wikis.format(Wikis.MD, "[[Sport]] [[Club]]"))
    "<a href=\"/wiki/any:Sport\">Sport</a> haha: <a href=\"/wiki/any:Privacy_Policy\">Privacy Policy" === (Wikis.format(Wikis.MD, "[[Sport]] haha: [[Privacy Policy]]"))
  }

  "WikiParser" should "recognize these " in {
    "" === (WikiParser.applys(""))
    "some sentence" === (WikiParser.applys("some sentence"))
    "\n" === (WikiParser.applys("\n"))
    "\na\nb\n" === (WikiParser.applys("\na\nb\n"))
    "a\nb\n\n" === (WikiParser.applys("a\nb\n\n"))
    "aa\nbb" === (WikiParser.applys("aa\nbb"))
    "{{Date 2011-07-24}}" === (WikiParser.applys("{{when:2011-07-24}}"))
    "haha {{Date 2011-07-24}}\nhehe" === (WikiParser.applys("haha {{when:2011-07-24}}\nhehe"))
    WikiParser.State("haha Date: 2011-07-24\nhehe", Map("when" -> "2011-07-24")) === (WikiParser.apply("haha {{when:2011-07-24}}\nhehe"))

    "_____habibi__" === Wikis.formatName("[] /:habibi{}")
    //
  }

  "WikiParser" should "like dates" in {
    "2012-01-21" === WikiParser.parseAll(WikiParser.date1, "2012-01-21").get
    "2012-October-14" === WikiParser.parseAll(WikiParser.date2, "October 14, 2012").get
  }

}
