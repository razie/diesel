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

  def wid = new WID(category,name)
  
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
    Audit.logdb(AUDT_WIKI_UPDATED, category + ":" + name, " BY " + this.by + "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name)
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.updatenoaudit(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    if(category != newVer.category || name != newVer.name) {
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
        ("""<a href="/wikie/%s:%s" title="%s">%s<sup><b style="color:red">++</b></sup></a>""".format(cat, nicename, hover.getOrElse("Page missing"), label),
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
    try {
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

/** most information about a page */
case class ILink(cat: String, name: String, label: String, tags: Map[String, String] = Map()) {
  def href = "/wiki/%s:%s".format(cat, name)
}

/** wiki parser */
object WikiParser extends RegexParsers {
  override def skipWhitespace = false

  case class State(s: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = List())
  implicit def toState(s: String) = State(s)

  type PS = Parser[State]
  def apply(input: String) = parseAll(wiki, input) getOrElse (State("[[CANNOT PARSE]]"))
  def applys(input: String) = apply(input).s
  
  def parseW2(input: String) = parseAll(wiki2, input) getOrElse (State("[[CANNOT PARSE]]"))

  def wiki: PS = lines | line | CRLF2 | NADA

  def line: PS = opt(lists) ~ rep(badHtml | badHtml2 | wiki3 | wiki2 | link1 | wikiProps | static) ^^ { 
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

  def CRLF1: PS = CRLF2 <~ not ("""[^a-zA-Z]""".r) ^^ { case ho => State(ho.s + "<br>") }
  def CRLF2: PS = ("\r\n" | "\n") ^^ { State(_) }
  def CRLF3: PS = CRLF2 ~ CRLF2 ^^ { case a ~ b => State(a.s + b.s) }
  def NADA: PS = "" ^^ { x => State("ASDF") }

  def static: PS = (not("{{") ~> not("[[") ~> not("[http:") ~> """.""".r+) ^^ { case l => l.map(_.s).mkString }

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

  // forbidden html tags TODO it's easier to allow instead?
  val hok = "abbr|acronym|address|b|blockquote|br|h1|h2|h3|h4|h5|h6|hr|i|li|p|pre|q|s|small|strike|strong|sub|sup|" +
    "table|tbody|td|tfoot|th|thead|tr|ul|u"
  val hnok = "applet|area|a|base|basefont|bdo|big|body|button|caption|center|cite|code|colgroup|col|" +
    "dd|del|dfn|dir|div|dl|dt|fieldset|font|form|frame|frameset|head|html|iframe|img|input|ins|isindex|kbd|" +
    "label|legend|link|map|menu|meta|noframes|noscript|object|ol|" +
    "optgroup|option|param|samp|script|select|span|style|textarea|title|tt|var"

  def badHtml: PS = "<" ~> hnok.r ~ opt(" " ~ """[^>]*""".r) <~ ">" ^^ {
    case b ~ Some(c ~ d) => "&lt;" + b + c + d + "&gt;"
    case b ~ None        => "&lt;" + b + "&gt;"
  }

  def badHtml2: PS = "</" ~> hnok.r <~ ">" ^^ {
    case b => "&lt;/" + b + "&gt;"
  }

  // ============== {{name:value}}
  
  // this is used when matching a link/name
  def wikiPropsRep: PS = rep(wikiPropMagicName | wikiPropByName | wikiPropWhenName | wikiPropWhereName | wikiPropLocName | wikiPropRoles | wikiProp | static) ^^ { case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap) }

  // this is used for contents of a topic
  def wikiProps: PS = wikiPropMagic | wikiPropBy | wikiPropWhen | wikiPropWhere | wikiPropLoc | wikiPropRoles | wikiProp

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
    case place => State("{{by "+parseW2("""[[Club:%s]]""".format(place)).s+"}}", Map("club" -> place))
  }
  
  def wikiPropWhere: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{at "+parseW2("""[[Place:%s]]""".format(place)).s+"}}", Map("place" -> place))
  }

  def wikiPropWhereName: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{at %s}}""".format(place), Map("place" -> place))
  }

  def wikiPropLoc: PS = "{{loc:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case what ~ _ ~ loc => {
      if ("ll" == what)
        State("""{{[Location](http://maps.google.com/maps?ll=%s&z=15)}}""".format(loc), Map("loc:" + what -> loc))
      else if ("s" == what)
        State("""{{[Location](http://www.google.com/maps?hl=en&q=%s)}}""".format(loc.replaceAll(" ","+")), Map("loc:" + what -> loc))
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
        State("""{{Has [[%s]](s)}}""".format(cat), Map("roles:" + cat -> how))
      else if ("Parent" == how)
        State("""{{Owned by [[%s]](s)}}""".format(cat), Map("roles:" + cat -> how))
      else
        State("""{{Can link from %s(s) as %s}}""".format(cat, how), Map("roles:" + cat -> how))
    }
  }

  val mth1 = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
  val mth2 = "January|February|March|April|May|June|July|August|September|October|November|December"

  def dates = date1 | date2
  def date1 = """\d\d\d\d""".r ~ "-" ~ """\d\d""".r ~ "-" ~ """\d\d""".r ^^ { case y ~ _ ~ m ~ _ ~ d => "%s-%s-%s".format(y, m, d) }
  def date2 = (mth2 + "|" + mth1).r ~ " *".r ~ """\d[\d]?""".r ~ "[ ,-]*".r ~ """\d\d\d\d""".r ^^ { case m ~ _ ~ d ~ _ ~ y => "%s-%s-%s".format(y, m, d) }
}

object Test extends App {
  import com.tristanhunt.knockoff.DefaultDiscounter._

}
