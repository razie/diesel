/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.matching.Regex.Match
import scala.util.parsing.combinator.RegexParsers
import scala.Option.option2Iterable
import admin.Services
import scala.collection.mutable
import model.dom.WikiDomainParser

//import db.RTable
//import scala.Array.canBuildFrom

/**
 * sed like filter using Java regexp
 *
 *  example: from US to normal: Sed ("""(\d\d)/(\d\d)/(\d\d)""", """\2/\1/\3""", "01/31/12")
 *
 *  Essentially useless since plain "sss".replaceAll(..., "$1 $2...") works almost the same way..
 */
object SedWiki {
  val SEARCH = """search:?([^]]*)""".r
  val SEARCH2 = """q:?([^]]*)""".r
  val LIST = """list:?([^]]*)""".r
  val USERLIST = """userlist:?([^]]*)""".r
  val ALIAS = """alias:([^\]]*)""".r
  val NOALIAS = """(rk:)?([^|\]]*)([ ]*[|][ ]*)?([^]]*)?""".r

  def apply(repf: (String => String), input: String): Option[(String, Option[ILink])] = {
    var i: Option[ILink] = None

    input match {
      case SEARCH(nm) =>
        Some("""<a href="http://google.com/search?q=""" + Enc.toUrl(nm) + "\">" + nm + "</a>", None)
      case SEARCH2(nm) =>
        Some("""<a href="http://google.com/search?q=""" + Enc.toUrl(nm) + "\">" + nm + "</a>", None)

      case LIST(cat) => {
        Some(((if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(cat).toList.sortWith(_ < _).map { p =>
          Wikis.formatWikiLink(WID(cat, p), p, p)
        }.map(_._1).mkString(" ")
        else "TOO MANY to list"), None))
      }

      case USERLIST(cat) => {
        // TODO can't see more than 20-
        Some((
          try {
            val up = razie.NoStaticS.get[model.WikiUser]
            val upp = up.toList.flatMap(_.myPages(cat)).map(_.asInstanceOf[{ def wid: WID }])
            "<ul>" + upp.sortWith(_.wid.name < _.wid.name).take(20).map(_.wid).map { wid =>
              Wikis.formatWikiLink(wid, Wikis.label(wid).toString, Wikis.label(wid).toString)
            }.map(_._1).map(x => "<li>" + x + "</li>").mkString(" ") + "</ul>"
          } catch {
            case e @ (_: Throwable) => {
              println(e.toString);
              "ERR Can't list userlist"
            }
          }, None))
      }

      case ALIAS(wpath) => {
        val wid = WID.fromPath(wpath)
        wid.map { w =>
          val f = Wikis.formatWikiLink(w, w.name, w.name)
          ("Alias for " + f._1, f._2)
        }
      }

      case NOALIAS(rk, wpath, _, label) => {
        val wid = WID.fromPath(wpath)
        wid map (w => Wikis.formatWikiLink(w, w.name, (if (label != null && label.length > 1) label else w.name), None, rk != null && rk.length > 0))
      }

      case _ => Some(ERR, i)
    }
  }

  /** processing special categories */
  def expand2(m: Match): (String, Option[ILink]) = {
    def hackmd(s1: String, s2: String, s3: String) = {
      val ss2 = s2.replace('/', ':')
      val cat = ss2
      val catnocolon = if (ss2.isEmpty || !ss2.endsWith(":")) ss2 else ss2.substring(0, ss2.length - 1)
      val nicename = s3
      val label = s1
      Wikis.formatWikiLink(WID(catnocolon, nicename), nicename, label)
    }

    var cat = if (m.group(2) == null) "any:" else m.group(2)
    val nm = if (m.group(3) != null) m.group(3).trim else "?"
    var label = if (m.group(5) != null) m.group(5) else nm
    if (label.length <= 0) label = nm

    def res = if (m.group(3) != null) hackmd(label, cat, nm) else (SedWiki.ERR, None)

    if (m.group(1) != null) m.group(1) match {
      case _ => if (m.group(2) == null) { cat = m.group(1); res } else (SedWiki.ERR + res, None)
    }
    else res
  }

  val ERR = "[ERROR SYNTAX]"

  val patRep = """\\([0-9])""".r
}

/** simple parsers */
trait ParserCommons extends RegexParsers {
  override def skipWhitespace = false

  type P = Parser[String]

  type LS2 = List[List[String]]
  type LS1 = List[String]
  type PS2 = Parser[List[List[String]]]
  type PS1 = Parser[List[String]]

  def CRLF1: P = CRLF2 <~ "RKHABIBIKU" <~ not("""[^a-zA-Z0-9-]""".r) ^^ { case ho => ho + "<br>" } // hack: eol followed by a line - DISABLED
  def CRLF2: P = ("\r\n" | "\n") // normal eol
  //  def CRLF2: P = ("\r\n" | "\n") ^^ {case a => "FIFI" } // normal eol
  def CRLF3: P = CRLF2 ~ """[ \t]*""".r ~ CRLF2 ^^ { case a ~ _ ~ b => a + b } // an empty line = two eol
  //  def CRLF3: P = CRLF2 ~ CRLF2 ~ rep(CRLF2) ^^ { case a ~ b ~ c => "\n<p></p>\n" } // an empty line = two eol
  def NADA: P = ""

  // static must be stopped to not include too much - that's why the last expr
  def static: P = not("{{") ~> not("[[") ~> not("}}") ~> not("[http") ~> (""".""".r) ~ ("""[^{}\[\]`\r\n]""".r*) ^^ { case a ~ b => a + b.mkString }
}

/** wiki parser base definitions shared by different wiki parser */
trait WikiParserBase extends ParserCommons {

  /** collects the result of a parser rule
    *
    * @param s the string representation of this section
    * @param tags the tags collected from this section, will be added to the page's tags
    * @param ilinks the links to other pages collected in this rule
    * @param decs other modifiers - allow you to add custom logic
    */
  case class State(s: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = List(), decs: List[WikiEntry => WikiEntry] = List()) {
    def this(s: String, ilinks: List[ILink]) = this(s, Map(), ilinks)

    // todo make the string lazy to allow composition
    def +(other: State) = State(this.s + other.s, this.tags ++ other.tags, this.ilinks ++ other.ilinks, this.decs ++ other.decs)
  }

  implicit def toState(s: String) = State(s)

  type PS = Parser[State]

  val moreDotProps = new ListBuffer[PS]()
  val moreWikiProps = new ListBuffer[PS]()

  // todo half combinators to allow user modules to define new rules
  def withDotProp  (p:PS) : WikiParserBase = {moreDotProps append p; this }
  def withWikiProp (p:PS) : WikiParserBase = {moreWikiProps append p; this}
}

/** the big parser aggregates all little section parsers
  *
  * the major patterns recognized are:
  *
  * - markdown extensions
  * - escape most html tags
  * \[\[link to other wiki page\]\]
  * \[\[\[link to wikipedia page\]\]\]
  * {{markup and properties}}
  * {{{magic markup and properties}}}
  * .keyword markup   // dot first char
  * {{xxx multiline markup}}
  * ...
  * {{/xxx}}
  * ::beg multiline markup
  * ...
  * ::end
  *
  *
  * Each parsing rule creates a State, which are flattened at the end to both update the WikiEntry as well as create the
  * String representation of it - so you can be lazy in the State.
  *
  * */
object WikiParser extends WikiParserCls {
}

class WikiParserCls extends WikiParserBase with CsvParser with WikiDomainParser {
  def apply(input: String) = parseAll(wiki, input) match {
    case Success(value, _) => value
      // don't change this format
    case NoSuccess(msg, next) => (State(s"[[CANNOT PARSE]] [${next.pos.toString}] : ${msg}"))
  }
//  def applys(input: String) = apply(input).s

  /** use this to expand [[xxx]] on the spot */
  def parseW2(input: String) = parseAll(wiki2, input) getOrElse (State("[[CANNOT PARSE]]"))

  /** use this to parse wiki markdown on the spot - it is meant for short strings within like a cell or something */
  def parseLine(input: String) = parseAll(line, input) getOrElse (State("[[CANNOT PARSE]]"))

  def xCRLF1: PS = CRLF1 ^^ { case x => x }
  def xCRLF2: PS = CRLF2 ^^ { case x => x }
  def xCRLF3: PS = CRLF3 ^^ { case x => x }
  def xNADA: PS = NADA ^^ { case x => x }
  def xstatic: PS = static ^^ { case x => x }
  def escaped: PS = "`" ~ opt(""".[^`]*""".r) ~ "`" ^^ { case a ~ b ~ c => a + b.mkString + c }
  def escaped1: PS = "``" ~ opt(""".*""".r) ~ "``" ^^ { case a ~ b ~ c => a + b.mkString + c }


  //============================== wiki parsing

  def wiki: PS = lines | line | xCRLF2 | xNADA

  def line: PS = opt(lists) ~ rep(escaped1 | escaped | badHtml | badHtml2 | wiki3 | wiki2 | link1 | wikiProps | lastLine | xstatic) ^^ {
    case ol ~ l => State(ol.getOrElse(State("")).s + l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks), l.flatMap(_.decs))
  }

  def optline: PS = opt(dotProps | line) ^^ { case o => o.map(identity).getOrElse(State("")) }

  def lines: PS = rep((domainBlock ~ CRLF2) | (optline ~ (CRLF1 | CRLF3 | CRLF2))) ~ opt(dotProps | line) ^^ {
    case l ~ c =>
      State(
        l.map(t => t._1.s + t._2.s).mkString + c.map(_.s).getOrElse(""),
        l.flatMap(_._1.tags).toMap ++ c.map(_.tags).getOrElse(Map()),
        l.flatMap(_._1.ilinks).toList ++ c.map(_.ilinks).getOrElse(Nil),
        l.flatMap(_._1.decs).toList ++ c.map(_.decs).getOrElse(Nil))
  }

  // ======================== static lines - not parsed

  def escbq: PS = "``" ^^ { case a => State("`") }

  def sstatic: PS = not("{{/code}}") ~> (""".""".r) ~ ("""[^{}\[\]`\r\n]""".r*) ^^ { case a ~ b => State(a + b.mkString) }
  def sline: PS = rep(lastLine | sstatic) ^^ {
    case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks), l.flatMap(_.decs))
  }

  def soptline: PS = opt(sline) ^^ { case o => o.map(identity).getOrElse(State("")) }

  def slines: PS = rep(soptline ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(sline) ^^ {
    case l ~ c =>
      State(
        l.map(t => t._1.s + t._2.s).mkString + c.map(_.s).getOrElse(""),
        l.flatMap(_._1.tags).toMap ++ c.map(_.tags).getOrElse(Map()),
        l.flatMap(_._1.ilinks).toList ++ c.map(_.ilinks).getOrElse(Nil),
        l.flatMap(_._1.decs).toList ++ c.map(_.decs).getOrElse(Nil))
  }

  def wiki3: PS = "[[[" ~ """[^]]*""".r ~ "]]]" ^^ {
    case "[[[" ~ name ~ "]]]" => """<a href="http://en.wikipedia.org/wiki/%s"><i>%s</i></a>""".format(name, name)
  }

  def wiki2: PS = "[[" ~ """[^]]*""".r ~ "]]" ^^ {
    case "[[" ~ name ~ "]]" => {
      val p = parseAll(wikiPropsRep, name)
      if (p.successful) {
        // this is an ilink with auto-props in the name/label
        // for now, reformat the link to allow props and collect them in the ILInk
        //        SedWiki(wikip2a, expand2 _, identity, p.get.s).map(x => State(x._1, Map(), x._2.map(x => ILink(x.cat, x.name, x.label, p.get.tags, p.get.ilinks)).toList)).get // TODO something with the props
        SedWiki(identity, p.get.s).map(x => State(x._1, Map(), x._2.map(x => ILink(x.wid, x.label, p.get.tags, p.get.ilinks)).toList)).get // TODO something with the props
      } else {
        // this is a normal ilink
        //        SedWiki(wikip2a, expand2 _, Wikis.formatName _, name).map(x => State(x._1, Map(), x._2.toList)).get
        SedWiki(Wikis.formatName _, name).map(x => State(x._1, Map(), x._2.toList)).get
      }
    }
  }

  val wikip2 = """\[\[([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?\]\]"""
  val wikip2a = """([^:|\]]*:)?([^/:|\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?"""

  /** processing special categories */
  def expand2(m: Match): (String, Option[ILink]) = {
    def hackmd(s1: String, s2: String, s3: String) = {
      val ss2 = s2.replace('/', ':')
      val cat = ss2
      val catnocolon = if (ss2.isEmpty || !ss2.endsWith(":")) ss2 else ss2.substring(0, ss2.length - 1)
      val nicename = s3
      val label = s1
      Wikis.formatWikiLink(WID(catnocolon, nicename), nicename, label)
    }

    var cat = if (m.group(2) == null) "any:" else m.group(2)
    val nm = if (m.group(3) != null) m.group(3).trim else "?"
    var label = if (m.group(5) != null) m.group(5) else nm
    if (label.length <= 0) label = nm

    def res = if (m.group(3) != null) hackmd(label, cat, nm) else (SedWiki.ERR, None)

    if (m.group(1) != null) m.group(1) match {
      case "alias:" => ("Alias for " + res._1, res._2)
      case "search:" => ("""<a href="http://google.com/search?q=""" + Enc.toUrl(nm) + "\">" + nm + "</a>", None)
      case "list:" => {
        val c = if (m.group(3) != null) nm else "?"

        ((if (Wikis.pageNames(c).size < 100)
          Wikis.pageNames(c).map { p =>
          hackmd(p, c, p)
        }.map(_._1).mkString(" ")
        else "TOO MANY to list"), None)
      }
      case _ => if (m.group(2) == null) { cat = m.group(1); res } else (SedWiki.ERR + res, None)
    }
    else res
  }

  def link1: PS = "[" ~> """http[s]?://[^] ]*""".r ~ opt("[ ]+".r ~ """[^]]*""".r) <~ "]" ^^ {
    case url ~ Some(sp ~ text) => """[%s](%s)""".format(text, url)
    case url ~ None => """[%s](%s)""".format(url, url)
  }

  /** quick list element */
  def lists = li1 | li2 | li3
  def li1: PS = """^ \* """.r ^^ { case x => "    * " }
  def li2: PS = """^  \* """.r ^^ { case x => "        * " }
  def li3: PS = """^   \* """.r ^^ { case x => "            * " }

  //======================= forbidden html tags TODO it's easier to allow instead?

  val hok = "abbr|acronym|address|a|b|blockquote|br|div|dd|dl|dt|font|h1|h2|h3|h4|h5|h6|hr|i|img|li|p|pre|q|s|small|strike|strong|span|sub|sup|" +
    "table|tbody|td|tfoot|th|thead|tr|ul|u"
  val hnok = "applet|area|base|basefont|bdo|big|body|button|caption|center|cite|code|colgroup|col|" +
    "del|dfn|dir|fieldset|form|frame|frameset|head|html|iframe|input|ins|isindex|kbd|" +
    "label|legend|link|map|menu|meta|noframes|noscript|object|ol|" +
    "optgroup|option|param|samp|script|select|style|textarea|title|tt|var"

  //  def iframe: PS = "<iframe" ~> """[^>]*""".r ~ """src="""".r ~ """[^"]*""".r ~ """[^>]*""".r <~ ">" ^^ {
  def iframe: PS = "<iframe" ~> """[^>]*""".r <~ ">" ^^ {
    case a => {
      val url = a.replaceAll(""".*src="(.*)".*""", "$1")
      val x = Services.config.config(Services.config.SAFESITES).flatMap(_.keys.find(x => url.startsWith(x)).map(x => "<iframe" + a + "></iframe>")) getOrElse ("&lt;iframe" + a + "&gt;")
      x
    }
  }

  private def badHtml: PS = iframe | badTags

  private def badTags: PS = "<" ~> hnok.r ~ opt(" " ~ """[^>]*""".r) <~ ">" ^^ {
    case b ~ Some(c ~ d) => "&lt;" + b + c + d + "&gt;"
    case b ~ None => "&lt;" + b + "&gt;"
  }

  private def badHtml2: PS = "</" ~> hnok.r <~ ">" ^^ {
    case b => "&lt;/" + b + "&gt;"
  }

  //======================= {{name:value}}

  // this is used when matching a link/name
  private def wikiPropsRep: PS = rep(wikiPropMagicName | wikiPropByName | wikiPropWhenName |
    wikiPropWhereName | wikiPropLocName | wikiPropRoles | wikiProp |
    xstatic) ^^ {
    case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks))
  }

  // this is used for contents of a topic
  private def wikiProps: PS =
    moreWikiProps.foldLeft(
    wikiPropMagic | wikiPropBy | wikiPropWhen | wikiPropXp | wikiPropWhere |
    wikiPropLoc | wikiPropRoles | wikiPropAds | wikiPropWidgets | wikiPropCsv | wikiPropCsv2 |
    wikiPropTable | wikiPropSection | wikiPropImg | wikiPropVideo | wikiPropScript | wikiPropCall |
    wikiPropFiddle | wikiPropCode | wikiPropField | wikiPropRk | wikiPropLinkImg | wikiPropFeedRss |
    wikiPropRed
    )((x,y) => x | y) | wikiProp

  private def wikiPropMagic: PS = "{{{" ~> """[^}]*""".r <~ "}}}" ^^ {
    case value => {
      val p = parseAll(dates, value)
      if (p.successful) {
        State("""{{Date %s}}""".format(value), Map("date" -> value))
      } else {
        State("""{{??? %s}}""".format(value), Map("magic" -> value))
      }
    }
  }
  def wikiPropMagicName: PS = """{{{""" ~> """[^}]*""".r <~ """}}}""" ^^ {
    case value => {
      val p = parseAll(dates, value)
      if (p.successful) {
        State("""{{date %s}}""".format(value), Map("date" -> value))
      } else {
        State("""{{??? %s}}""".format(value), Map("magic" -> value))
      }
    }
  }

  // TODO this opt() not working - could remove the propS
  //  def wikiProp: PS = "{{" ~> """[^}:]+""".r ~ opt("""[: ]""".r ~> """[^}:]*""".r) <~ "}}" ^^ {
  //      case name ~ value =>
  //      if (name startsWith ".")
  //        State("", Map(name.substring(1) -> value.getOrElse(""))) // hidden
  //      else
  //        State("""{{Property %s=%s}}""".format(name, value), Map(name -> value.getOrElse("")))
  //  }
  def wikiProp: PS = "{{" ~> """[^}: ]+""".r ~ """[: ]""".r ~ """[^}]*""".r <~ "}}" ^^ {
    case name ~ _ ~ value =>
      if (name startsWith ".")
        State("", Map(name.substring(1) -> value)) // hidden
      else
        State("""{{Property %s=%s}}""".format(name, value), Map(name -> value))
  }

  private def dotProps: PS =
    moreDotProps.foldLeft(dotPropShare | dotPropAct | dotPropTags | dotPropEmail | dotPropName)((x,y) => x | y) | dotProp

  def dotProp: PS = """^\.""".r ~> """[.]?[^.: ][^: ]+""".r ~ """[: ]""".r ~ """[^\r\n]*""".r ^^ {
    case name ~ _ ~ value =>
      if (name startsWith ".")
        State("", Map(name.substring(1) -> value)) // hidden
      else
        State(s"""<font style="font-weight:bold;">{{.Property $name=$value}}</font><br>""", Map(name -> value))
  }

  def dotPropTags: PS = """^\.t """.r ~> """[^\n\r]*""".r  ^^ {
    case value => State("", Map("inlinetags" -> value)) // hidden
  }

  def dotPropShare: PS = """^\.shared """.r ~> """[^\n\r]*""".r  ^^ {
    case value => State(s"""<small><font style="color:red;font-weight:bold;">{{Share $value}}</font></small><br>""", Map("share" -> value))
  }

  def dotPropAct: PS = """^\.a """.r ~> """[^\n\r]*""".r  ^^ {
    case value => State(s"""<small><font style="color:red;font-weight:bold;">{{Action $value}}</font></small><br>""", Map("action" -> value))
  }

  def dotPropEmail: PS = """^\.email """.r ~> """[^ \n\r]*""".r  ^^ {
    case value => State(s"""<small><font style="font-weight:bold;">{{email $value}}</font></small><br>""", Map("email" -> value))
  }

  def dotPropName: PS = """^\.n """.r ~> """[^\n\r]*""".r  ^^ {
    case value => State(s"""<small><font style="font-weight:bold;">$value</font></small><br>""", Map("name" -> value))
  }

  // TODO this opt() not working - could remove the propS
  //  def wikiPropNV: PS = "{{" ~> """[^}:]+""".r <~ "}}" ^^ {
  //      case name =>
  //      if (name startsWith ".")
  //        State("", Map(name.substring(1) -> "")) // hidden
  //      else
  //        State("""{{Property %s=%s}}""".format(name, ""), Map(name -> ""))
  //  }

  private def wikiPropByName: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{by %s}}""".format(place), Map("by" -> place))
  }

  private def wikiPropBy: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{by " + parseW2("""[[Club:%s]]""".format(place)).s + "}}", Map("club" -> place), ILink(WID("Club", place), place) :: Nil)
  }

  private def wikiPropWhere: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{at " + parseW2("""[[Venue:%s]]""".format(place)).s + "}}", Map("venue" -> place), ILink(WID("Venue", place), place) :: Nil)
  }

  private def wikiPropWhereName: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{at %s}}""".format(place), Map("venue" -> place), ILink(WID("Venue", place), place) :: Nil)
  }

  private def wikiPropLoc: PS = "{{" ~> "loc" ~> """[: ]""".r ~> """[^}:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ loc => {
      if ("ll" == what)
        State("""{{[Location](http://maps.google.com/maps?ll=%s&z=15)}}""".format(loc), Map("loc" -> (what + ":" + loc)))
      else if ("s" == what)
        State("""{{[Location](http://www.google.com/maps?hl=en&q=%s)}}""".format(loc.replaceAll(" ", "+")), Map("loc" -> (what + ":" + loc)))
      else if ("url" == what)
        State("""{{[Location](%s)}}""".format(loc), Map("loc" -> (what + ":" + loc)))
      else
        State("""{{Unknown location spec: %s value %s}}""".format(what, loc), Map("loc" -> (what + ":" + loc)))
    }
  }

  private def wikiPropLocName: PS = "{{" ~> "loc" ~> """[: ]""".r ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ loc => {
      State("""{{at:%s:%s)}}""".format(what, loc), Map("loc:" + what -> loc))
    }
  }

  private def wikiPropWhen: PS = ("\\{\\{when[: ]".r | "\\{\\{[Oo]n[: ]+".r | "\\{\\{[Dd]ate[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case date => {
      val p = parseAll(dates, date)
      if (p.successful) {
        State("""{{Date %s}}""".format(date), Map("date" -> date))
      } else {
        State("""{{Date ???}}""".format(date), Map())
      }
    }
  }

  private def wikiPropWhenName: PS = ("\\{\\{when[: ]".r | "\\{\\{[Oo]n[: ]+".r | "\\{\\{[Dd]ate[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case date => {
      val p = parseAll(dates, date)
      if (p.successful) {
        State("""{{date %s}}""".format(date), Map("date" -> date))
      } else {
        State("""%s??""".format(date), Map())
      }
    }
  }

  private def wikiPropXp: PS = "{{" ~> """xpl?""".r ~ """[: ]""".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ path => {
      State("""`{{{""" + what + """:%s}}}`""".format(path), Map())
    }
  }

  private def wikiPropRoles: PS = "{{" ~> "roles" ~> """[: ]""".r ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case cat ~ coloAdoAdsn ~ how => {
      if ("Child" == how)
        State("{{Has " + parseW2("[[%s]]".format(cat)).s + "(s)}}", Map("roles:" + cat -> how))
      else if ("Parent" == how)
        State("{{Owned by " + parseW2("[[%s]]".format(cat)).s + "(s)}}", Map("roles:" + cat -> how))
      else
        State("{{Can link from " + parseW2("[[%s]]".format(cat)).s + "(s) as %s}}".format(how), Map("roles:" + cat -> how))
    }
  }

  private def wikiPropAds: PS = "{{" ~> "ad" ~> opt("[: ]".r ~> """[^}]*""".r) <~ "}}" ^^ {
    case what => {
      what match {
        case Some("lederboard") => State(Ads.lederboard)
        case Some("square") | Some("squaretop") => State(Ads.squaretop)
        case Some("squareright") => State(Ads.squareright)
        case Some("squarenofloat") | Some("squareinline") => State(Ads.squareinline)
        case _ => State(Ads.lederboard)
      }
    }
  }

  private def wikiPropRk: PS = "{{" ~> "rk" ~> opt("[: ]".r ~> """[^}]*""".r) <~ "}}" ^^ {
    case what => {
      what match {
        case Some("member") =>
          State("""<a class="badge badge-warning" href="http://www.racerkidz.com/wiki/Admin:Member_Benefits">RacerKidz</a>""")
        case Some("club") =>
          State("""<a class="badge badge-warning" href="http://www.racerkidz.com/wiki/Admin:Club_Hosting">RacerKidz</a>""")
        case _ =>
          State("""<a class="badge badge-warning" href="http://www.racerkidz.com">RacerKidz</a>""")
      }
    }
  }

  private def wikiPropRed: PS = "{{" ~> "red" ~> opt("[: ]".r ~> """[^}]*""".r) <~ "}}" ^^ {
    case what => State(s"""<span style="color:red;font-weight:bold;">${what.mkString}</span>""")
  }

  // simple x=y
  private def arg = "[^:=,}]*".r ~ "=" ~ "[^},]*".r ^^ { case n ~ _ ~ v => (n, v) }
  // if contains comma, use ""
  private def arg2 = "[^:=,}]*".r ~ "=\"" ~ "[^\"]*".r <~ "\"" ^^ { case n ~ _ ~ v => (n, v) }

  private def optargs : Parser[List[(String,String)]] = opt("[: ]".r ~ rep((arg2 | arg) <~ opt(","))) ^^ {
    case Some(_ ~ l) => l
    case None => List()
  }

  private def wikiPropWidgets: PS = "{{" ~> "widget:" ~> "[^:]+".r ~ optargs <~ "}}" ^^ {
    case name ~ args => {
      State(
        Wikis.find(WID("Admin", "widget_" + name)).map(_.content).map { c =>
          args.foldLeft(c)((c, a) => c.replaceAll(a._1, a._2))
        } getOrElse "")
    }
  }

  //======================= forms

  private def wikiPropField: PS = "{{" ~> "f:" ~> "[^:]+".r ~ optargs <~ "}}" ^^ {
    case name ~ args => {
      State(
        "`{{{f:%s}}}`".format(name), Map(), List(), List({ w =>
          w.fields = w.fields ++ Map(name -> FieldDef(name, "", args.map(t => t).toMap))
          w
        }))
    }
  }

  private def wikiPropFieldVal: PS = "{{" ~> "fval:" ~> "[^:]+".r ~ optargs <~ "}}" ^^ {
    case name ~ args => {
      State(
        Wikis.find(WID("Admin", "widget_" + name)).map(_.content).map { c =>
          args.foldLeft(c)((c, a) => c.replaceAll(a._1, a._2))
        } getOrElse "")
    }
  }

  //======================= delimited imports and tables

  def wikiPropCsv: PS = "{{" ~> "r1.delimited:" ~> (wikiPropCsvStart >> { h: CsvHeading => csv(h.delim) ^^ { x => (h, x) } }) <~ "{{/r1.delimited}}" ^^ {
    case (a, body) => {
      val c = body
      State(a.s) + c.filter(_.size > 0).map(l =>
        State("\n* ") + parseW2("[[" + a.what + ":" + l.zip(a.h).filter(c => c._1.length > 0).map(c =>
          if ("_" == c._2) c._1 else ("{{" + c._2 + " " + c._1 + "}}")).mkString(" ") + "]]")).reduce(_ + _) + "\n"
    }
  }

  def wikiPropCsv2: PS = "{{" ~> "r1.delimited2:" ~> """[^:]*""".r ~ ":" ~ """[^:]*""".r ~ ":" ~ (wikiPropTableStart >> { h: CsvHeading => csv(h.delim) ^^ { x => (h, x) } }) <~ "{{/r1.delimited}}" ^^ {
    case prefix ~ _ ~ cat ~ _ ~ Tuple2(a, body) => {

      def ecell(cat: String, p: String, a: String, b: String) =
        parseW2("[[" + cat + ":" + p + " " + a + " " + b + "]]").s

      a.s + body.map(l =>
        if (l.size > 0) ("\n<tr>" + l.map(c =>
          "<td>" + c + "</td>" + a.h.tail.map(b =>
            "<td>" + ecell(cat, prefix, c, b) + "</td>").mkString).mkString + "</tr>")
        else "").mkString + "\n</table>"
    }
  }

  def wikiPropTable: PS = "{{" ~> "r1.table:" ~> (wikiPropTableStart >> { h: CsvHeading => csv(h.delim) ^^ { x => (h, x) } }) <~ "{{/r1.table}}" ^^ {
    case (a, body) => {
      a.s + body.map(l =>
        if (l.size > 0) ("\n<tr>" + l.map(c =>
          "<td>" + parseLine(c).s + "</td>").mkString + "</tr>")
        else "").mkString + "\n</table>"
    }
  }

  // to not parse the content, use slines instead of lines
  def wikiPropSection: PS = "{{" ~> opt(".") ~ """section|template|properties""".r ~ ":" ~ """[^}]*""".r ~ "}}" ~ lines <~ ("{{/section}}" | "{{/template}}" | "{{/properties}}") ^^ {
    case hidden ~ stype ~ _ ~ name ~ _ ~ lines => {
      hidden.map(x => State("")) getOrElse State("`SECTION START {{" + stype + ":" + name + "}}`") + lines + State("`SECTION END`")
    }
  }

  def wikiPropImg: PS = "{{img" ~> opt("""\.icon|\.small|\.medium""".r) ~ """[: ]""".r ~ """[^} ]*""".r ~ optargs <~ "}}" ^^ {
    case stype ~ _ ~ name ~ args => {
      val sargs = args.foldLeft(""){(c, a) => s""" $c ${a._1}="${a._2}" """}
      State(s"""<img src="$name" $sargs />""")
    }
  }

  private def wikiPropVideo: PS = "{{" ~> ("video" | "photo" | "slideshow") ~ """[: ]""".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ url => {
      what match {
        case "video" => {
          val yt1 = """http://youtu.be/(.*)""".r
          val yt2 = """http[s]?://www.youtube.com/watch?.*v=([^?&]+).*""".r
          val vm3 = """http[s]?://vimeo.com/([^?&]+)""".r
          def xt(id: String) = """<iframe width="560" height="315" src="http://www.youtube.com/embed/%s" frameborder="0" allowfullscreen></iframe>""".format(id)
          def vm(id: String) = """<iframe src="//player.vimeo.com/video/%s" width="500" height="281" frameborder="0" webkitallowfullscreen mozallowfullscreen allowfullscreen></iframe>""".format(id)
          url match {
            case yt1(a) => State(xt(a))
            case yt2(a) => State(xt(a))
            case vm3(a) => State(vm(a))
            case _ => State("""{{Unsupported video source - please report to support: <a href="%s">url</a>}}""".format(url))
          }
        }
        case "photo" => {
          val yt2 = """(.*)""".r
          def xt2(id: String) = """<a href="%s"><img src="%s"></a>""".format(id, id)
          url match {
            case yt2(a) => State(xt2(a))
            case _ => State("""{{Unsupported photo source - please report to support: [%s]}}""".format(url))
          }
        }
        case "slideshow" => {
          val yt1 = """(.*)""".r
          def xt(id: String) = """<a href="%s">Slideshow</a>""".format(id)
          url match {
            case yt1(a) => State(xt(a))
            case _ => State("""{{Unsupported slideshow source - please report to support: %s}}""".format(url))
          }
        }
      }
    }
  }

  def wikiPropFiddle: PS = "{{" ~> """fiddle""".r ~ "[: ]".r ~ """[^:}]*""".r ~ opt(":" ~ rep(arg <~ opt(","))) ~ "}}" ~ lines <~ "{{/fiddle}}" ^^ {
    case stype ~ _ ~ lang ~ xargs ~ _ ~ lines => {
      var args = (if(xargs.isDefined) xargs.get._2 else List()).toMap
      val name = args.get("name").getOrElse("")
      def trim (s:String) = s.replaceAll("\r", "").replaceAll("^\n|\n$","")//.replaceAll("\n", "\\\\n'\n+'")
      lazy val ss = lines.s.replaceAll("&lt;", "<").replaceAll("&gt;", ">") // bad html was escaped while parsing

      try {
        lang match {
          case "js" | "html" | "css" => {
            // TODO can't get this oneliner to work
            //          val re = """(?s)(<html>.*</html>).*(<style>.*</style>).*(<script>.*</script>).*""".r
            //          val  re(h, c, j) = lines.s
            val rehh = """(?s).*<head>(.*)</head>.*""".r
            val reh = """(?s).*<html>(.*)</html>.*""".r
            val rec = """(?s).*<style>(.*)</style>.*""".r
            val rej = """(?s).*<script>(.*)</script>.*""".r

            val hh = (if (ss contains "<head>") rehh.findFirstMatchIn(ss).get.group(1) else "").replaceAll("<script", "<scrRAZipt").replaceAll("</script", "</scrRAZipt")
            val h = (if (ss contains "<html>") reh.findFirstMatchIn(ss).get.group(1) else if (lang == "html") ss else "")
            val c = (if (ss contains "<style>") rec.findFirstMatchIn(ss).get.group(1) else if (lang == "css") ss else "")
            val j = (if (ss contains "<script>") rej.findFirstMatchIn(ss).get.group(1) else if (lang == "js") ss else "")

            if (!(args contains "tab"))
              args = args + ("tab" -> lang)

            // remove empty lines from parsing

            State(views.html.fiddle.jsfiddle(name, args, (trim(hh), trim(h), trim(c), trim(j)), None).body)
          }
          case "javascript" => {
            State(views.html.fiddle.jsfiddle(name, args, ("", "", "", trim("document.write(function(){ return " + ss.replaceFirst("\n", "") + "}())")), None).body)
          }
          case "scala" => {
            State(views.html.fiddle.scalafiddle(name, args, lines.s, None).body)
          }
          case _ => {
            State(views.html.fiddle.scalafiddle(name, args, lines.s, None).body)
          }
        }
      }
      catch  {
        case _ : Throwable =>
          State("""<font style="color:red">[[BAD FIDDLE - check syntax]]</font>""")
      }
    }
  }

  def wikiPropCode: PS = "{{" ~> """code""".r ~ "[: ]".r ~ """[^:}]*""".r ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ "{{/code}}" ^^ {
    case stype ~ _ ~ name ~ _ ~ crlf ~ lines => {
      //      lines.copy(s = ((lines.s split "\n") map ("    " + _)).mkString("\n"))
      lines.copy(s = "<pre><code>" + lines.s + "</code></pre>")
    }
  }

  def wikiPropScript: PS = "{{" ~> """def|lambda""".r ~ "[: ]".r ~ """[^:}]*""".r ~ ":" ~ """[^}]*""".r ~ "}}" ~ lines <~ ("{{/def}}" | "{{/lambda}}") ^^ {
    case stype ~ _ ~ name ~ _ ~ sign ~ _ ~ lines => {
      if ("lambda" == stype)
        State("`{{call:#" + name + "}}`") // lambdas are executed right there...
      else
        State("`{{" + stype + ":" + name + "}}`")
    }
  }

  def wikiPropCall: PS = "{{" ~> """call""".r ~ "[: ]".r ~ opt("""[^#}]*""".r) ~ "#" ~ """[^}]*""".r <~ "}}" ^^ {
    case stype ~ _ ~ page ~ _ ~ name => {
      State("`{{" + stype + ":" + (page getOrElse "") + "#" + name + "}}`")
    }
  }

  case class CsvHeading(what: String, s: String, delim: String = ";", h: List[String] = Nil)

  def heading: P = (not("}}") ~> not(",") ~> """.""".r+) ^^ { case l => l.mkString }

  def csvHeadings: Parser[CsvHeading] = heading ~ rep("," ~> heading) ^^ {
    case ol ~ l => CsvHeading("", "", "", List(ol) ::: l)
  }

  def wikiPropCsvStart: Parser[CsvHeading] = """.""".r ~ ":".r ~ """[^:]*""".r ~ opt(":".r ~ csvHeadings) <~ "}}" ^^ {
    case delim ~ _ ~ what ~ head => {
      var s = what + "(s):" + "\n"

      CsvHeading(what, s, delim, head.map(_._2.h).getOrElse(List()))
    }
  }

  /** delim and optional column headings */
  def wikiPropTableStart: Parser[CsvHeading] = """.""".r ~ ":".r ~ opt(csvHeadings) <~ "}}" ^^ {
    case delim ~ _ ~ head => {
      var s = """<table class="table table-striped">"""

      if (head.isDefined)
        s += "\n<thead><tr>" + head.get.h.map(e => "<th>" + e + "</th>").mkString + "</tr></thead>"

      CsvHeading("", s, delim, head.map(_.h).getOrElse(List()))
    }
  }

  // todo more humane name
  def wikiPropLinkImg: PS = "{{link.img" ~ "[: ;]".r ~> """[^ ;}]*""".r ~ "[: ;]".r ~ """[^ ;}]*""".r <~ "}}" ^^ {
    case url ~ _ ~ link => {
      s"""<a href="$link"><img src="$url" /></a>"""
    }
  }

  // todo more humane name
  def wikiPropFeedRss: PS = "{{feed.rss" ~ "[: ;]".r ~> """[^ ;}]*""".r <~ "}}" ^^ {
    case xurl => {
      import razie.Snakk._
      val rss  = url (xurl)
      Try {
        (for (xn <- xml(scala.xml.XML.loadString(body(rss))) \ "channel" \ "item") yield {
          val n = xml(xn)
          // insulate external strings - sometimes the handling of underscore is bad
          // replace urls with MD markup:
          def ext(s: String) = s //.replaceAll("""(\s|^)(https?://[^\s]+)""", "$1[$2]")

          val link = ext(n \@ "link")
          val title = ext(n \@ "title")
          val desc = ext(n \@ "description")
          s"""<h3><a href="$link">$title</a></h3><pre>$desc</pre> """
        }).mkString
      }
    }.recover{
      case e:Throwable => s"Error reading RSS: ${e.getClass.getSimpleName} : ${e.getLocalizedMessage}"
    }.get
  }


  //======================= dates

  val mth1 = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
  val mth2 = "January|February|March|April|May|June|July|August|September|October|November|December"

  def dates = date1 | date2
  def date1 = """\d\d\d\d""".r ~ "-" ~ """\d\d""".r ~ "-" ~ """\d\d""".r ^^ { case y ~ _ ~ m ~ _ ~ d => "%s-%s-%s".format(y, m, d) }
  def date2 = (mth2 + "|" + mth1).r ~ " *".r ~ """\d[\d]?""".r ~ "[ ,-]*".r ~ """\d\d\d\d""".r ^^ { case m ~ _ ~ d ~ _ ~ y => "%s-%s-%s".format(y, m, d) }
}

/** delimited and table parser */
trait CsvParser extends ParserCommons {

  def csv(implicit xdelim: String): Parser[List[List[String]]] = csvLines

  def csvCRLF2: PS2 = CRLF2 ^^ { case x => Nil }
  def csvNADA: PS2 = NADA ^^ { case x => Nil }

  def csvLine(implicit xdelim: String): PS1 = (cell | xdelim) ~ rep(xdelim ~ (cell | NADA)) ^^ {
    case ol ~ l => {
      if (ol == xdelim) List("") ::: l.map(_._2)
      else List(ol) ::: l.map(_._2)
    }
  }

  def csvLines(implicit xdelim: String): PS2 = rep(csvOptline ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(csvLine) ^^ {
    case l ~ c => (l.map(_._1) ::: c.toList)
  }

  def plainLines(implicit end: String): P = rep(opt(plainLine) ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(plainLine) ^^ {
    case l ~ c => l.flatMap(_._1.map(_ + "\n")).mkString + c.getOrElse("")
  }

  def plainLine(implicit end: String): P = (not(end) ~> """.""".r+) ^^ { case l => l.mkString }

  def csvOptline(implicit xdelim: String): PS1 = opt(csvLine) ^^ { case o => o.map(identity).getOrElse(Nil) }

  def cell(implicit xdelim: String): P = (not(xdelim) ~> not("{{") ~> not("}}") ~> """.""".r+) ^^ { case l => l.mkString }
  //  def cell(implicit xdelim: String): P = (not(xdelim) ~> not("{{") ~> not("[[") ~> not("}}") ~> """.""".r+) ^^ { case l => l.mkString }
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

  val squarebase = """
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
  val squaretopx = """ <div style="float:right;margin: -40px 5px 0px 5px"> """ + squarebase
  val squareright = """<div style="float:right">""" + squareinline + """</div>"""
  val squaretop = """<div style="float:right">""" + squaretopx + """</div>"""
}

object Widgets {
  def weather(s: String) = """
<iframe marginheight="0" marginwidth="0" name="wxButtonFrame" id="wxButtonFrame" height="168" 
    src="http://btn.weather.ca/weatherbuttons/template7.php?placeCode=%s&category0=Cities&containerWidth=180&btnNo=&backgroundColor=blue&multipleCity=0&citySearch=1&celsiusF=C" 
    align="top" frameborder="0" width="180" scrolling="no"></iframe><br>
""".format(s)
}

object Test extends App {
  import com.tristanhunt.knockoff.DefaultDiscounter._

  val csv1 =
    """
{{delimited:,:Race}}
a,b,c,d
{{/delimited}}
"""

  val csv2 =
    """
{{delimited:,:Race:}}

{{/delimited}}
"""

  val csv3 =
    """
{{delimited:;:Race:name,where,by,when}}
a;b;c;d
{{/delimited}}
"""

  val csv4 =
    """
{{delimited:,:Race:name,where,by,when}}
a,b,c,d
a,,c,d
{{/delimited}}
"""

  //  println(WikiParser.applys(csv1))
  //  println(WikiParser.applys(csv2))
  //  println(WikiParser.applys(csv3))
  //  println(WikiParser.applys(csv4))

  val tabsp = """<table><tr><td>
1



1
</td><td></td></tr></table>"""

  val tabsp1 = """
Ontario (slalom and GS), within the following four age groups:

- Nancy Greene League Racing (ages 7 to 10)
- K1 League Racing (ages 11 and 12), 
- K2 League Racing (ages 13 and 14) and 
- J Alpine League Racing (ages 15 to 18)
"""
  //  println (WikiParser.applys(tabsp))
  //  println (Wikis.format(WID.NONE, "md", tabsp))
  //  val content = Wikis.preprocess(WID.NONE, "md", tabsp1).s
  val content = """fufu

 """
  //  println(toXHTML(knockoff(content)).toString)
  //  println(Wikis.format (WID("1","2"), "md", content))
  //  println(Wikis.format(WID("1", "2"), "md", content))

  //---------------------

  //  println(f())

  def f() = {
    val ccc = """
{{section:supportreq}}
Support reuested: <p>
<table>
<tr><td>email:</td><td>%s</td></tr>
<tr><td>desc:</td><td>%s</td></tr>
<tr><td>details:</td><td>%s</td></tr>
</table>        
<p>        
Thank you,<br>The RacerKidz
{{/section}}

"""

    //    val PATT1s = """.*\{\{section:.*\}\}.*\{\{/section\}\}.*""".r
    val PATT1s = """(?s)\{\{section:.*""".r
    val PATT1 = """(?s)\{\{(section):(.*)\}\}(.*)\{\{/(section)\}\}""".r
    val PATT2 = PATT1
    //    val PATT2 = """{{(section|template):([^}]*)}}([^{]*){{/(section|template)}}""".r

    //    PATT1.findAllIn(ccc).toList

  }

  //  Wikis.find(WID("Note", "adfasdf")).foreach { we =>
  //    println(Wikis.format(we.wid, we.markup, we.content, Some(we)))
  //  }

  val r1tabbug = """
{{r1.table:|:POSITION,VOLUNTEER HOURS,FILLED BY}}
1|2|3
a|b|c
{{/r1.table}}
"""
  val xr1tabbug = """
President|10|Karen Whitney
Vice President - Operations|10|Karen Lawson
Past President|10|Position Vacant
Vice-President in Training|10|Mark Booth
Secretary|10|Position Vacant
Treasurer|10|Martin Hambrock
Registrar|10|Ileana Cojocaru
Coaching Administrator|10|John Johnston
Race Administrator|10|Noelle Wood
Hosting Co-ordinator|6 hours per race|Position Vacant
Webmaster|10|Razie Cojocaru
Clothing Co-ordinator|10|Debbie Holmes
Volunteer Co-ordinator|10|Position Vacant
Coaches - paid|10|Varia
Managers|10|Varia
.|[4 hours per week if race/ 1 hr./wk otherwise]|.
Board of Directors|2 hours per meeting - +20|Positions Available
.|.|Secretary for this season and Treasurer for next season (can transition with existing Treasurer this year)
"""
  println(WikiParser.apply(r1tabbug).s)

}
