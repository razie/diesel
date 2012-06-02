package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import scala.util.matching.Regex.Match
import scala.util.matching.Regex
import razie.Log
import scala.util.parsing.combinator.RegexParsers
import razie.base.data.TripleIdx
import admin.Notif

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  by: String,
  ver: Int = 1,
  props: Map[String, String] = Map(),
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  /** is this just an alias? */
  def alias: Option[WID] = {
    val wikip2 = """\[\[alias:([^/:\]]*)[/:]([^|\]]+)\]\]"""
    val wikip2r = wikip2.r
    if (content.matches(wikip2)) {
      val wikip2r(c, n) = content
      Some(new WID(c, n))
    } else None
  }

  def wid = new WID(category, name)

  def renamed(newlabel: String) =
    WikiEntry (category, Wikis.formatName(newlabel), newlabel, markup, content, by, ver + 1, props, crDtm, DateTime.now, _id)

  def newVer(label: String, markup: String, content: String, by: String, props: Map[String, String] = this.props) =
    WikiEntry (category, name, label, markup, content, by, ver + 1, props, crDtm, DateTime.now, _id)

  def props(m: Map[String, String]) =
    WikiEntry (category, name, label, markup, content, by, ver, m, crDtm, DateTime.now, _id)

  def isReserved = props.get("reserved").map(_ == "yes").getOrElse(false)

  def isPrivate = "User" == category || (props.exists(e => "owner" == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => "owner" == e._1 && id == e._2))

  def create = {
    // TODO optimize exists
    if (Wikis.find(category, name).isDefined) {
      Log.error("ERR_WIKI page exists " + category + ":" + name)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    Audit.logdb(AUDT_WIKI_CREATED, category + ":" + name, " BY " + this.by + "\nCONTENT:\n" + this)
    Wikis.table += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    Wikis.withIndex { _.put(name, category, _id) }
  }

  def update(newVer: WikiEntry) = {
    Audit.logdb(AUDT_WIKI_UPDATED, "BY " + newVer.by + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name)
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.updatenoaudit(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    if (category != newVer.category || name != newVer.name) {
      Wikis.withIndex { idx =>
        idx.put(newVer.name, newVer.category, _id)
        idx.remove2(name, category)
      }
    }
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  lazy val preprocessed = Wikis.preprocess(this.markup, this.content)
  lazy val tags = preprocessed.tags
  lazy val ilinks = preprocessed.ilinks

  final val AUDT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDT_WIKI_UPDATED = "WIKI_UPDATED "
}

object WikiEntry {
  final val UPD_CONTENT = "UPD_CONTENT"
  final val UPD_TOGGLE_RESERVED = "UPD_TOGGLE_RESERVED"
}

case class WikiEntryOld(
  entry: WikiEntry,
  _id: ObjectId = new ObjectId()) {
  def create = {
    Mongo ("WikiEntryOld") += grater[WikiEntryOld].asDBObject(Audit.createnoaudit(this))
  }
}

/** a wiki id, a pair of cat and name */
case class WID(cat: String, name: String) {
  override def toString = cat + "/" + name
}

/** a link between two wikis */
case class WikiLink(from: WID, to: WID, how: String) {
  def create = Mongo ("WikiLink") += grater[WikiLink].asDBObject(Audit.create(this))

  val wname = Array(from.cat, from.name, to.cat, to.name).mkString(":")

  def page = Wikis.find("WikiLink", wname)
  def pageFrom = Wikis.find(from.cat, from.name)
  def pageTo = Wikis.find(to.cat, to.name)

  def isPrivate = List(pageFrom, page).flatMap(_ map (_.isPrivate)).exists(identity)
}

//  lazy val stuff = Mongo("UserStuff").findOne(Map("email" -> email)) map (grater[UserStuff].asObject(_))

/** wiki factory and utils */
object Wikis {
  def table = Mongo("WikiEntry")

  def pageNames(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("name").toString)

  def pageLabels(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("label").toString)

  def label(category: String, name: String) =
    table.findOne(Map("category" -> category, "name" -> name)) map (_.apply("label")) getOrElse name

  def findById(id: String) =
    table.findOne(Map("_id" -> new ObjectId(id))) map (grater[WikiEntry].asObject(_))

  def find(category: String, name: String) =
    table.findOne(Map("category" -> category, "name" -> name)) map (grater[WikiEntry].asObject(_))

  def findAny(name: String) =
    table.find(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

  def findAnyOne(name: String) =
    table.findOne(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

  def linkFromName(s: String) = {
    val a = s.split(":")
    WikiLink(WID(a(0), a(1)), WID(a(2), a(3)), "?")
  }

  /** the index is (name, category, ID) */
  def withIndex[A](f: TripleIdx[String, String, ObjectId] => A) = {
    synchronized {
      f(index)
    }
  }
  // TODO sync reader/writer for udpates
  private lazy val index = {
    val t = new TripleIdx[String, String, ObjectId]()
    table.find(Map()).foreach { db =>
      t.put(db.as[String]("name"), db.as[String]("category"), db.as[ObjectId]("_id"))
    }
    t
  }

  val MD = "md"
  val TEXT = "text"
  val markups = Array(MD, TEXT)

  import com.tristanhunt.knockoff.DefaultDiscounter._

  private def iformatName(name: String, pat: String) = name.replaceAll(pat, "_").replaceAll("_+", "_").replaceFirst("_$", "")

  /** format a simple name - try NOT to use this */
  def formatName(name: String): String = iformatName(name, """[ &?,;/:{}\[\]]""")

  /** format a complex name cat:name */
  def formatName(wid: WID): String = if ("WikiLink" == wid.cat) iformatName (wid.name, """[ /{}\[\]]""") else formatName(wid.name)

  /** format an even more complex name */
  def formatWikiLink(cat: String, nicename: String, label: String, hover: Option[String] = None) = {
    val name = Wikis.formatName(nicename)
    val title = hover.map("title=\"" + _ + "\"") getOrElse ("")

    Wikis.withIndex{ index =>
      if (index.idx.contains(name) || cat.matches("User"))
        ("""<a href="/wiki/%s:%s" %s>%s</a>""".format(cat, name, title, label),
          Some(ILink(cat, nicename, label)))
      else
        // hide it from google
        ("""<a href="/wikie/%s:%s" title="%s">%s<sup><b style="color:red">++</b></sup></a>""".format(cat, nicename, hover.getOrElse("Missing page"), label),
          Some(ILink(cat, nicename, label)))
    }
  }

  def shouldFlag(name: String, label: String, content: String): Option[String] = {
    val a = Array(name, label, content)

    if (a.exists(_.matches("(?i)^.*<(" + WikiParser.hnok + ")([^>]*)>"))) Some("WIKI_FORBIDDEN_HTML")
    else if (hasporn(content, softporn)) Some("WIKI_HAS_SOFTPO")
    else None
  }

  // TODO better escaping of all url chars in wiki name
  def preprocess(markup: String, content: String) = markup match {
    case MD =>
      (for (
        s @ WikiParser.State(a0, tags, ilinks) <- Some(WikiParser (content))
      ) yield s) getOrElse WikiParser.State("")
    case TEXT => WikiParser.State(content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]"""))
    case _    => WikiParser.State("UNKNOWN MARKUP " + markup + " - " + content)
  }

  def format(markup: String, icontent: String) = {
    val res = try {
      val content = preprocess(markup, noporn(icontent)).s
      markup match {
        case MD   => toXHTML(knockoff(content)).toString
        case TEXT => content
        case _    => "UNKNOWN MARKUP " + markup + " - " + content
      }
    } catch {
      case e @ _ => {
        log("ERROR formatting wiki: " + e)
        "ERROR formatting wiki..."
      }
    }
    
    // mark the external links
    res.replaceAll ("""(<a +href="http:)([^>]*)>([^<]*)(</a>)""", """$1$2 title="External site"><i>$3</i><sup>&nbsp;<b style="color:darkred">●●</b></sup>$4""")
  }

  def noporn(s: String) = porn.foldLeft(s)((x, y) => x.replaceAll("""\b%s\b""".format(y), "BLIP"))

  def hasporn(s: String, what: Array[String] = porn): Boolean = s.toLowerCase.split("""\w""").exists(what.contains(_))

  def flag(we: WikiEntry) { flag (we.category, we.name) }

  def flag(c: String, n: String, reason: String = "?") {
    Audit.logdb("WIKI_FLAGGED", reason, c, n)
  }

  final val porn = Array("porn", "fuck", "sex")

  final val softporn = Array("tit", "breast", "ass", "dick")

  def updateUserName(uold: String, unew: String) = {
    // TODO1 optimize with find()
    // tODO2 rename references
    Mongo.withDb(Mongo.db("WikiEntry")) { t =>
      for (u <- t if ("User" == u.get("category") && uold == u.get("name"))) {
        u.put("name", unew)
        t.save(u)
      }
    }
    Mongo.withDb(Mongo.db("WikiEntryOld")) { t =>
      for (u <- t if ("User" == u.get("category") && uold == u.get("name"))) {
        u.put("name", unew)
        t.save(u)
      }
    }
  }

}

/** sed like filter using Java regexp
 *
 *  example: from US to normal: Sed ("""(\d\d)/(\d\d)/(\d\d)""", """\2/\1/\3""", "01/31/12")
 *
 *  Essentially useless since plain "sss".replaceAll(..., "$1 $2...") works almost the same way..
 */
object SedWiki {
  def apply(pat: String, rep: Match => (String, Option[ILink]), input: String): Option[(String, Option[ILink])] = apply(pat, rep, identity, input)

  def apply(pat: String, rep: Match => (String, Option[ILink]), repf: (String => String), input: String): Option[(String, Option[ILink])] = {
    var i: Option[ILink] = None
    Some(
      (pat.r replaceAllIn (input, (m: Match) => {
        val x = rep(m)
        i = x._2
        patRep replaceAllIn (x._1, (m1: Match) =>
          repf(m group (m1 group 1).toInt))
      })), i)
  }

  val ERR = "[ERROR SYNTAX]"

  val patRep = """\\([0-9])""".r
}

object ILink {
  def apply(cat: String, name: String) = new ILink(cat, name, name)
}

/** most information about a page */
case class ILink(cat: String, name: String, label: String, tags: Map[String, String] = Map()) {
  def href = "/wiki/%s:%s".format(cat, name)
  def format = Wikis.formatWikiLink(cat, name, label, None)
}

/** simple parsers */
trait ParserCommons extends RegexParsers {
  override def skipWhitespace = false

  type P = Parser[String]

  type LS2 = List[List[String]]
  type LS1 = List[String]
  type PS2 = Parser[List[List[String]]]
  type PS1 = Parser[List[String]]
  
  def CRLF1: P = CRLF2 <~ not ("""[^a-zA-Z]""".r) ^^ { case ho => ho + "<br>" }
  def CRLF2: P = ("\r\n" | "\n") 
  def CRLF3: P = CRLF2 ~ CRLF2 ^^ { case a ~ b => a + b }
  def NADA:  P = "" 

  def static: P = (not("{{") ~> not("[[") ~> not("}}") ~> not("[http:") ~> """.""".r+) ^^ { case l => l.mkString }
}

/** wiki parser */
object WikiParser extends ParserCommons with CsvParser {
  case class State(s: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = List())
  implicit def toState(s: String) = State(s)

  type PS = Parser[State]
  def apply(input: String) = parseAll(wiki, input) getOrElse (State("[[CANNOT PARSE]]"))
  def applys(input: String) = apply(input).s

  /** use this to expand [[xxx]] on the spot */
  def parseW2(input: String) = parseAll(wiki2, input) getOrElse (State("[[CANNOT PARSE]]"))

  def xCRLF1: PS = CRLF1 ^^ {case x=>x}
  def xCRLF2: PS = CRLF2 ^^ {case x=>x}
  def xCRLF3: PS = CRLF3 ^^ {case x=>x}
  def xNADA: PS = NADA ^^ {case x=>x}
  def xstatic: PS = static ^^ {case x=>x}

  //============================== wiki parsing
  
  def wiki: PS = lines | line | xCRLF2 | xNADA

  def line: PS = opt(lists) ~ rep(badHtml | badHtml2 | wiki3 | wiki2 | link1 | wikiProps | xstatic) ^^ {
    case ol ~ l => State(ol.getOrElse(State("")).s + l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks))
  }

  def lines: PS = rep(optline ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(line) ^^ {
    case l ~ c =>
      State(
        l.map(t => t._1.s + t._2.s).mkString + c.map(_.s).getOrElse(""),
        l.flatMap(_._1.tags).toMap ++ c.map(_.tags).getOrElse(Map()),
        l.flatMap(_._1.ilinks).toList ++ c.map(_.ilinks).getOrElse(Nil))
  }

  def optline: PS = opt(line) ^^ { case o => o.map (identity).getOrElse (State("")) }

  def wiki3: PS = "[[[" ~ """[^]]*""".r ~ "]]]" ^^ {
    case "[[[" ~ name ~ "]]]" => """<a href="http://en.wikipedia.org/wiki/%s"><i>%s</i></a>""".format(name, name)
  }

  def wiki2: PS = "[[" ~ """[^]]*""".r ~ "]]" ^^ {
    case "[[" ~ name ~ "]]" => {
      val p = parseAll(wikiPropsRep, name)
      if (p.successful) {
        // this is an ilink with auto-props in the name/label
        // for now, reformat the link to allow props and collect them in the ILInk
        SedWiki(wikip2a, expand2 _, identity, p.get.s).map(x => State(x._1, Map(), x._2.map(x => ILink(x.cat, x.name, x.label, p.get.tags)).toList)).get // TODO something with the props
      } else {
        // this is a normal ilink
        SedWiki(wikip2a, expand2 _, Wikis.formatName _, name).map(x => State(x._1, Map(), x._2.toList)).get
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
        Wikis.formatWikiLink(catnocolon, nicename, label)
      }

    var cat = if (m.group(2) == null) "any:" else m.group(2)
    val nm = if (m.group(3) != null) m.group(3).trim else "?"
    var label = if (m.group(5) != null) m.group(5) else nm
    if (label.length <= 0) label = nm

      def res = if (m.group(3) != null) hackmd(label, cat, nm) else (SedWiki.ERR, None)

    if (m.group(1) != null) m.group(1) match {
      case "alias:" => ("Alias for " + res._1, res._2)
      case "list:" => {
        val c = if (m.group(3) != null) nm else "?"

        ((if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(c).map { p =>
          hackmd(p, c, p)
        }.map(_._1).mkString(" ")
        else "TOO MANY to list"), None)
      }
      case _ => if (m.group(2) == null) { cat = m.group(1); res } else (SedWiki.ERR + res, None)
    }
    else res
  }

  def link1: PS = "[" ~> """http://[^] ]*""".r ~ opt("[ ]+".r ~ """[^]]*""".r) <~ "]" ^^ {
    case url ~ Some(sp ~ text) => """[%s](%s)""".format(text, url)
    case url ~ None            => """[%s](%s)""".format(url, url)
  }

  def lists = li1 | li2 | li3
  def li1: PS = """^ \* """.r ^^ { case x => "    * " }
  def li2: PS = """^  \* """.r ^^ { case x => "        * " }
  def li3: PS = """^   \* """.r ^^ { case x => "            * " }

  //======================= forbidden html tags TODO it's easier to allow instead?
  
  val hok = "abbr|acronym|address|b|blockquote|br|h1|h2|h3|h4|h5|h6|hr|i|li|p|pre|q|s|small|strike|strong|sub|sup|" +
    "table|tbody|td|tfoot|th|thead|tr|ul|u"
  val hnok = "applet|area|a|base|basefont|bdo|big|body|button|caption|center|cite|code|colgroup|col|" +
    "dd|del|dfn|dir|div|dl|dt|fieldset|font|form|frame|frameset|head|html|iframe|img|input|ins|isindex|kbd|" +
    "label|legend|link|map|menu|meta|noframes|noscript|object|ol|" +
    "optgroup|option|param|samp|script|select|span|style|textarea|title|tt|var"

  val safeSites = Set("http://maps.google.ca", "http://maps.google.com", "http://www.everytrail.com")
  //  def iframe: PS = "<iframe" ~> """[^>]*""".r ~ """src="""".r ~ """[^"]*""".r ~ """[^>]*""".r <~ ">" ^^ {
  def iframe: PS = "<iframe" ~> """[^>]*""".r <~ ">" ^^ {
    case a => {
      val url = a.replaceAll (""".*src="(.*)".*""", "$1")
      if (safeSites.exists (x => url.startsWith(x))) "<iframe" + a + "></iframe>"
      else "&lt;iframe" + a + "&gt;"
    }
  }

  def badHtml: PS = iframe | badTags

  def badTags: PS = "<" ~> hnok.r ~ opt(" " ~ """[^>]*""".r) <~ ">" ^^ {
    case b ~ Some(c ~ d) => "&lt;" + b + c + d + "&gt;"
    case b ~ None        => "&lt;" + b + "&gt;"
  }

  def badHtml2: PS = "</" ~> hnok.r <~ ">" ^^ {
    case b => "&lt;/" + b + "&gt;"
  }

  //======================= {{name:value}}

  // this is used when matching a link/name
  def wikiPropsRep: PS = rep(wikiPropMagicName | wikiPropByName | wikiPropWhenName | wikiPropWhereName | wikiPropLocName | wikiPropRoles | wikiProp | xstatic) ^^ { case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap) }

  // this is used for contents of a topic
  def wikiProps: PS = wikiPropMagic | wikiPropBy | wikiPropWhen | wikiPropWhere | wikiPropLoc | wikiPropRoles | wikiPropCsv | wikiPropTable | wikiProp

  def wikiPropMagic: PS = "{{{" ~> """[^}]*""".r <~ "}}}" ^^ {
    case value => {
      val p = parseAll(dates, value)
      if (p.successful) {
        State("""{{Date %s}}""".format(value), Map("date" -> value))
      } else {
        State("""{{??? %s}}""".format(value), Map("magic" -> value))
      }
    }
  }
  def wikiPropMagicName: PS = "{{{" ~> """[^}]*""".r <~ "}}}" ^^ {
    case value => {
      val p = parseAll(dates, value)
      if (p.successful) {
        State("""{{date %s}}""".format(value), Map("date" -> value))
      } else {
        State("""{{??? %s}}""".format(value), Map("magic" -> value))
      }
    }
  }

  def wikiProp: PS = "{{" ~> """[^:}]+""".r ~ ":" ~ """[^}]*""".r <~ "}}" ^^ {
    case name ~ _ ~ value => State("""[Unknonw prop %s=%s]""".format(name, value), Map(name -> value))
  }

  def wikiPropByName: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{by %s}}""".format(place), Map("by" -> place))
  }

  def wikiPropBy: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{by " + parseW2("""[[Club:%s]]""".format(place)).s + "}}", Map("club" -> place))
  }

  def wikiPropWhere: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{at " + parseW2("""[[Venue:%s]]""".format(place)).s + "}}", Map("venue" -> place))
  }

  def wikiPropWhereName: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{at %s}}""".format(place), Map("venue" -> place))
  }

  def wikiPropLoc: PS = "{{loc:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ loc => {
      if ("ll" == what)
        State("""{{[Location](http://maps.google.com/maps?ll=%s&z=15)}}""".format(loc), Map("loc:" + what -> loc))
      else if ("s" == what)
        State("""{{[Location](http://www.google.com/maps?hl=en&q=%s)}}""".format(loc.replaceAll(" ", "+")), Map("loc:" + what -> loc))
      else if ("url" == what)
        State("""{{[Location](%s)}}""".format(loc), Map("loc:" + what -> loc))
      else
        State("""{{Unknown location spec: %s value %s}}""".format(what, loc), Map("loc:" + what -> loc))
    }
  }

  def wikiPropLocName: PS = "{{loc:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ loc => {
      State("""{{at:%s:%s)}}""".format(what, loc), Map("loc:" + what -> loc))
    }
  }

  def wikiPropWhen: PS = ("\\{\\{when[: ]".r | "\\{\\{[Oo]n[: ]+".r | "\\{\\{[Dd]ate[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case date => {
      val p = parseAll(dates, date)
      if (p.successful) {
        State("""{{Date %s}}""".format(date), Map("date" -> date))
      } else {
        State("""{{Date ???}}""".format(date), Map())
      }
    }
  }

  def wikiPropWhenName: PS = ("\\{\\{when[: ]".r | "\\{\\{[Oo]n[: ]+".r | "\\{\\{[Dd]ate[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case date => {
      val p = parseAll(dates, date)
      if (p.successful) {
        State("""{{date %s}}""".format(date), Map("date" -> date))
      } else {
        State("""%s??""".format(date), Map())
      }
    }
  }

  def wikiPropRoles: PS = "{{roles:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case cat ~ colon ~ how => {
      if ("Child" == how)
        State("{{Has " + parseW2("[[%s]]".format(cat)).s+"(s)}}", Map("roles:" + cat -> how))
      else if ("Parent" == how)
        State("{{Owned by " + parseW2("[[%s]]".format(cat)).s+"(s)}}", Map("roles:" + cat -> how))
      else
        State("{{Can link from " + parseW2("[[%s]]".format(cat)).s + "(s) as %s}}".format(how), Map("roles:" + cat -> how))
    }
  }

  //======================= delimited imports and tables
  
  def wikiPropCsv: PS = "{{delimited:" ~> (wikiPropCsvStart >> {h:CsvHeading => csv(h.delim) ^^ {x => (h,x)}}) <~ "{{/delimited}}" ^^ {
    case (a, body) => {
      val c = body
      a.s + c.map( l=>
        if(l.size>0)
          ("\n* "+parseW2("[["+a.what+":"+l.zip(a.h).map( c=>
            "{{"+c._2+" "+c._1+"}}"
            ).mkString(", ")+"]]").s
          ) else "").mkString + "\n"
    }
  }

  def wikiPropTable: PS = "{{table:" ~> (wikiPropTableStart >> {h:CsvHeading => csv(h.delim) ^^ {x => (h,x)}}) <~ "{{/table}}" ^^ {
    case (a, body) => {
      val c = body
      a.s + c.map( l =>
        if(l.size>0)("\n<tr>"+l.map( c =>
          "<td>"+c+"</td>"
          ).mkString+"</tr>"
          ) else "").mkString + "\n</table>"
    }
  }

  case class CsvHeading (what:String,s:String,delim:String=";",h:List[String]=Nil)
  
  def heading: P = (not("}}") ~> not(",") ~> """.""".r+) ^^ { case l => l.mkString }
  
  def csvHeadings: Parser[CsvHeading] = heading ~ rep("," ~> heading) ^^ {
    case ol ~ l => CsvHeading("","","",List(ol) ::: l)
  }

  def wikiPropCsvStart: Parser[CsvHeading] = """.""".r ~ ":".r ~ """[^:]*""".r ~ opt(":".r ~ csvHeadings) <~ "}}" ^^ {
    case delim ~ _ ~ what ~ head => {
      var s = what + "(s):"+"\n"
        
    CsvHeading(what, s, delim, head.map(_._2.h).getOrElse(List()))
    }
  }

  def wikiPropTableStart: Parser[CsvHeading] = """.""".r ~ ":".r ~ opt(csvHeadings) <~ "}}" ^^ {
    case delim ~ _ ~ head => {
      var s = """<table class="table table-striped">"""
        
      if(head.isDefined)
        s += "\n<thead><tr>"+ head.get.h.map(e=>"<th>"+e+"</th>").mkString + "</tr></thead>"
        
    CsvHeading("", s, delim, head.map(_.h).getOrElse(List()))
    }
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
  
  def csv(implicit xdelim:String): Parser[List[List[String]]] = csvLines

  def csvCRLF2: PS2 = CRLF2 ^^ { case x => Nil }
  def csvNADA: PS2 = NADA ^^ { case x => Nil }
  
  def csvLine(implicit xdelim:String): PS1 = cell ~ rep(xdelim.r ~ (cell | NADA)) ^^ {
    case ol ~ l => {
    List(ol) ::: l.map(_._2)
    }
  }

  def csvLines(implicit xdelim:String): PS2 = rep(csvOptline ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(csvLine) ^^ {
    case l ~ c => (l.map(_._1) ::: c.toList)
  }
  
  def plainLines (implicit end:String): P = rep(opt(plainLine) ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(plainLine) ^^ {
    case l ~ c => l.flatMap(_._1.map(_+"\n")).mkString + c.getOrElse("")
  }
  
  def plainLine(implicit end:String): P = (not(end) ~> """.""".r+) ^^ { case l => l.mkString }
  
  def csvOptline(implicit xdelim:String): PS1 = opt(csvLine) ^^ { case o => o.map (identity).getOrElse (Nil) }

  def cell(implicit xdelim:String): P = (not(xdelim) ~> not("{{") ~> not("[[") ~> not("}}") ~> """.""".r+) ^^ { case l => l.mkString }
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
    
    println (WikiParser.applys(csv1))
    println (WikiParser.applys(csv2))
    println (WikiParser.applys(csv3))
    println (WikiParser.applys(csv4))
}
