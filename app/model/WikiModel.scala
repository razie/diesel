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

  lazy val key = Map("category" -> category, "name" -> name)

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

    Audit.logdb(AUDT_WIKI_CREATED, category + ":" + name, " BY " + this.by)
    Wikis.table += grater[WikiEntry].asDBObject(Audit.create(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    Wikis.index.put(name, category, _id)
  }

  def update(newVer: WikiEntry) = {
    Audit.logdb(AUDT_WIKI_UPDATED, category + ":" + name, " BY " + this.by)
    WikiEntryOld(this).create
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.update(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  lazy val tags = Wikis.preprocess(this.markup, this.content).tags

  final val AUDT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDT_WIKI_UPDATED = "WIKI_UPDATED "
}

case class WikiEntryOld (
    entry:WikiEntry,
    _id:ObjectId = new ObjectId()) {
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

  def find(category: String, name: String) =
    table.findOne(Map("category" -> category, "name" -> name)) map (grater[WikiEntry].asObject(_))

  def findAny(name: String) =
    table.find(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

  def linkFromName(s: String) = {
    val a = s.split(":")
    WikiLink(WID(a(0), a(1)), WID(a(2), a(3)), "?")
  }

  lazy val index = {
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

  def formatName(name: String) = name.replaceAll("""[ /:{}\[\]]""", "_")

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
        WikiParser.State(a0, tags) <- Some(WikiParser (content))
//        s1 <- SedWiki("""\[\[\[([^]]*)\]\]\]""", SedWiki.expand3, formatName, a0);
//        s2 <- SedWiki("""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?\]\]""", SedWiki.expand2, formatName, a0)
      ) yield WikiParser.State(a0, tags)) getOrElse WikiParser.State("")
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

  def formatOld(markup: String, icontent: String) = {
    val content = preprocess(markup, noporn(icontent)).s
    markup match {
      case MD   => toXHTML(knockoff(content)).toString
      case TEXT => content
      case _    => "UNKNOWN MARKUP " + markup + " - " + content
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
}

/** sed like filter using Java regexp
 *
 *  example: from US to normal: Sed ("""(\d\d)/(\d\d)/(\d\d)""", """\2/\1/\3""", "01/31/12")
 *
 *  Essentially useless since plain "sss".replaceAll(..., "$1 $2...") works almost the same way..
 */
object SedWiki {
  def apply(pat: String, rep: Match => String, input: String): Option[String] = apply(pat, rep, identity, input)

  def apply(pat: String, rep: Match => String, repf: (String => String), input: String): Option[String] = {
    Some(pat.r replaceAllIn (input, (m: Match) =>
      patRep replaceAllIn (rep(m), (m1: Match) =>
        repf(m group (m1 group 1).toInt))))
  }

  /** processing special categories */
  def expand2(m: Match): String = {
      def hackmd(s1: String, s2: String, s3: String) = {
        val ss2 = s2.replace('/', ':')
        if (Wikis.index.idx.contains(s3))
          """<a href="/wiki/%s%s%s">%s</a>""".format(ss2, (if (ss2.isEmpty || ss2.endsWith(":")) "" else ":"), s3, s1)
        else
          // hide it from google
          """<a href="/wikie/%s%s%s" title="Page missing">%s <sup><b style="color:red">++</b></sup></a>""".format(ss2, (if (ss2.isEmpty || ss2.endsWith(":")) "" else ":"), s3, s1)
      }

    var cat = if (m.group(2) == null) "any:" else m.group(2)
    val nm = if (m.group(3) != null) m.group(3).trim else "?"
    var label = if (m.group(5) != null) m.group(5) else nm
    if (label.length <= 0) label = nm
      def res = if (m.group(3) != null) hackmd(label, cat, Wikis.formatName(nm)) else ERR

    if (m.group(1) != null) m.group(1) match {
      case "alias:" => "Alias for " + res
      case "list:" => {
        val c = if (m.group(3) != null) nm else "?"

        if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(c).map { p =>
            hackmd(p, c, p)
          }.mkString(" ")
        else "TOO MANY to list"
      }
      case _ => if (m.group(2) == null) { cat = m.group(1); res } else ERR + res
    }
    else res

  }
//  def expand3(m: Match): String = """<a href="http://en.wikipedia.org/wiki/\1"><i>$1</i></a>"""

  val ERR = "[ERROR SYNTAX]"

  val patRep = """\\([0-9])""".r
}

object WikiParserTest extends RegexParsers {
  //  def numexpr : Parser[AExpr] = wholeNumber ^^ { case i => new AExpr(i) }
  //  def moreexpr : Parser[AExpr] = """[^+"()=<>|&]+""".r ^^ { e => new AExpr (e) }

  //  def pexpr: Parser[AExpr] = term~rep("+"~term | "-"~term) ^^ { case t~l => t+l.first }// TODO fix this
  //  def term: Parser[AExpr] = factor~rep("*"~factor | "/"~factor) ^^ { case f~l => t*l.first } //TODO fix this
}

/** wiki parser */
object WikiParser extends RegexParsers {
  override def skipWhitespace = false

  case class State(s: String, tags: Map[String, String] = Map())
  implicit def toState(s: String) = State(s)

  type PS = Parser[State]
  def apply(input: String) = parseAll(wiki, input) getOrElse (State("[[CANNOT PARSE]]"))
  def applys(input: String) = apply(input).s

  def wiki: PS = lines | line | CRLF | NADA

  def line: PS = rep(badHtml | badHtml2 | wiki3 | wiki2 | wikiProps | static) ^^ { case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap) }

  def lines: PS = rep(optline ~ (CRLF)) ~ opt(line) ^^ {
    case l ~ c =>
      State(
        l.map(t => t._1.s + t._2.s).mkString + c.map(_.s).getOrElse(""),
        l.flatMap(_._1.tags).toMap ++ c.map(_.tags).getOrElse(Map()))
  }

  def optline: PS = opt(line) ^^ { case o => o.map (identity).getOrElse (State("")) }

  def CRLF: PS = ("\r\n" | "\n") ^^ { State(_) }
  def NADA: PS = "" ^^ { x => State("ASDF") }

  def static: PS = (not("{{") ~> not("[[") ~> """.""".r+) ^^ { case l => l.map(_.s).mkString }

      def wiki3 : PS = "[[["~"""[^]]*""".r~"]]]" ^^ { 
        case "[[["~name~"]]]" => """<a href="http://en.wikipedia.org/wiki/%s"><i>%s</i></a>""".format(name,name) 
      }
//        s2 <- SedWiki("""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?\]\]""", SedWiki.expand2, formatName, a0)

      def wiki2 : PS = "[["~"""[^]]*""".r~"]]" ^^ { 
        case "[["~name~"]]" => SedWiki(wikip2a, expand2 _, Wikis.formatName _, name).get
      }
      
  val wikip2  ="""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?\]\]"""
  val wikip2a =    """([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?"""
  
      /** processing special categories */
  def expand2(m: Match): String = {
      def hackmd(s1: String, s2: String, s3: String) = {
        val ss2 = s2.replace('/', ':')
        if (Wikis.index.idx.contains(s3))
          """<a href="/wiki/%s%s%s">%s</a>""".format(ss2, (if (ss2.isEmpty || ss2.endsWith(":")) "" else ":"), s3, s1)
        else
          // hide it from google
          """<a href="/wikie/%s%s%s" title="Page missing">%s <sup><b style="color:red">++</b></sup></a>""".format(ss2, (if (ss2.isEmpty || ss2.endsWith(":")) "" else ":"), s3, s1)
      }

    var cat = if (m.group(2) == null) "any:" else m.group(2)
    val nm = if (m.group(3) != null) m.group(3).trim else "?"
    var label = if (m.group(5) != null) m.group(5) else nm
    if (label.length <= 0) label = nm
      def res = if (m.group(3) != null) hackmd(label, cat, Wikis.formatName(nm)) else SedWiki.ERR

    if (m.group(1) != null) m.group(1) match {
      case "alias:" => "Alias for " + res
      case "list:" => {
        val c = if (m.group(3) != null) nm else "?"

        if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(c).map { p =>
            hackmd(p, c, p)
          }.mkString(" ")
        else "TOO MANY to list"
      }
      case _ => if (m.group(2) == null) { cat = m.group(1); res } else SedWiki.ERR + res
    }
    else res

  }

  // forbidden html tags
  //  val hok = "abbr|acronym|address|b|base|basefont|bdo|big|blockquote|body|br|button|caption|center|cite|code|col|colgroup|dd|del|dfn|dir|div|dl|dt|em|fieldset|font|form|frame|frameset|h1|h2|h3|h4|h5|h6|head|hr|html|i|iframe|img|input|ins|isindex|kbd|label|legend|li|link|map|menu|meta|noframes|noscript|object|ol|optgroup|option|p|param|pre|q|s|samp|script|select|small|span|strike|strong|style|sub|sup|table|tbody|td|textarea|tfoot|th|thead|title|tr|tt|u|ul|var|"
  val hnok = "applet|area|a|base|basefont|bdo|big|body|button|caption|center|cite|code|colgroup|col|dd|del|dfn|dir|div|dl|dt|fieldset|font|form|frame|frameset|head|html|iframe|img|input|ins|isindex|kbd|label|legend|link|map|menu|meta|noframes|noscript|object|ol|optgroup|option|param|samp|script|select|span|style|textarea|title|tt|var"

  def badHtml: PS = "<" ~> hnok.r ~ opt(" " ~ """[^>]*""".r) <~ ">" ^^ {
    case b ~ Some(c ~ d) => "&lt;" + b + c + d + "&gt;"
    case b ~ None        => "&lt;" + b + "&gt;"
  }

  def badHtml2: PS = "</" ~> hnok.r <~ ">" ^^ {
    case b => "&lt;/" + b + "&gt;"
  }

  // ============== {{name:value}}
  def wikiProps: PS = wikiPropWhen | wikiPropRoles | wikiProp

  def wikiProp: PS = "{{" ~> """[^:}]+""".r ~ ":" ~ """[^}]*""".r <~ "}}" ^^ {
    case name ~ _ ~ value => State("""[Unknonw prop %s=%s]""".format(name, value), Map(name -> value))
  }

  def wikiPropWhen: PS = "{{when:" ~> """[^}]*""".r <~ "}}" ^^ { case date => State("""{{Date: %s}}""".format(date), Map("when" -> date)) }

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
}

object Test extends App {
  import com.tristanhunt.knockoff.DefaultDiscounter._
  
  implicit val eqpsCollector = new collection.mutable.ListBuffer[Any]()
  
  //  println(toXHTML(knockoff("[wiki link](http://en.wikipedia.org/wiki/Bracket_(disambiguation))")).toString)
  //  println(toXHTML(knockoff("""     """)).toString)
  case class EQPSS(s: WikiParser.State) {
    def eqps(os: WikiParser.State) = { println(s); println(os); println(s == os); val res = (s == os); if (!res) eqpsCollector += (this -> os); res}
  }
  case class EQPS(s: String) {
    def eqps(os: String) = { println(s); println(os); println(s == os);  val res = (s == os); if (!res) eqpsCollector += (this -> os); res}
    def eqps(os: WikiParser.State) = { println(s); println(os.s); println(s == os.s);  val res = (s == os.s); if (!res) eqpsCollector += (this -> os); res }
  }
  implicit def toeqps(s: String) = EQPS(s)
  implicit def toeqpss(s: WikiParser.State) = EQPSS(s)

  "&lt;applet&gt;" eqps (WikiParser apply "<applet>")
  "&lt;iframe&gt;" eqps (WikiParser apply "<iframe>")
  "&lt;iframe gg=\"1\"&gt;" eqps (WikiParser apply "<iframe gg=\"1\">")
  "&lt;/iframe&gt;" eqps (WikiParser apply "</iframe>")
  "<small>" eqps (WikiParser apply "<small>")
  "</small>" eqps (WikiParser apply "</small>")

    "<a href=\"/wiki/any:Sport\">Sport</a>" eqps (Wikis.format(Wikis.MD, "[[Sport]]"))
    "<a href=\"/wiki/any:Sport\">Curu</a>" eqps (Wikis.format(Wikis.MD, "[[Sport|Curu]]"))
    "<a href=\"/wiki/any:Sport\">Curu</a>" eqps (Wikis.format(Wikis.MD, "[[Sport | Curu]]"))
    "<a href=\"/wiki/Club:Offroad_Ontario\">Curu</a>" eqps (Wikis.format(Wikis.MD, "[[Club:Offroad Ontario | Curu]]"))
    "<a href=\"/wiki/any:Sport\">Sport</a> <a href=\"/wiki/any:Club\">Club</a>" eqps (Wikis.format(Wikis.MD, "[[Sport]] [[Club]]"))
    "<a href=\"/wiki/any:Sport\">Sport</a> haha: <a href=\"/wiki/any:Privacy_Policy\">Privacy Policy" eqps (Wikis.format(Wikis.MD, "[[Sport]] haha: [[Privacy Policy]]"))

    "" eqps (WikiParser.applys(""))
    "some sentence" eqps (WikiParser.applys("some sentence"))
    "\n" eqps (WikiParser.applys("\n"))
    "\na\nb\n" eqps (WikiParser.applys("\na\nb\n"))
    "a\nb\n\n" eqps (WikiParser.applys("a\nb\n\n"))
    "aa\nbb" eqps (WikiParser.applys("aa\nbb"))
    "Date: 2011-07-24" eqps (WikiParser.applys("{{when:2011-07-24}}"))
    "haha Date: 2011-07-24\nhehe" eqps (WikiParser.applys("haha {{when:2011-07-24}}\nhehe"))
    WikiParser.State("haha Date: 2011-07-24\nhehe", Map("when" -> "2011-07-24")) eqps (WikiParser.apply("haha {{when:2011-07-24}}\nhehe"))

  //  """
  //Sorry, you don't have the permission to do this! 
  //
  //You can describe the issue in a support request and we'll take care of it! Thanks!
  // 
  //[not logged in -> Sorry - need to log in to edit a page]
  //""" eqps (WikiParser.applys(
  //    """
  //Sorry, you don't have the permission to do this! 
  //
  //You can describe the issue in a support request and we'll take care of it! Thanks!
  // 
  //[not logged in -> Sorry - need to log in to edit a page]
  //"""))

  //---------------1111111111-22222222222222-33333333-4444444444444-5555555
  val pat = """\[\[([^:\]]*:)?([^/:\]]*[/:])?([^|\]]+)([ ]*[|][ ]*)?([^]]*)?\]\]"""

  //  println("[[OO XC 2011-12]]".replaceAll("""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^]]+)\]\]""", "KK-$1-KK-$2-KK-$3-KK"))
  //  println("[[OO]] haha: [[OO XC 2011-12]]".replaceAll("""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^]]+)\]\]""", "KK-$1-KK-$2-KK-$3-KK"))
  //  println("[[Series:OO XC 2011-12]]".replaceAll("""\[\[([^:\]]*:)?([^/:\]]*[/:])?([^]]+)\]\]""", "KK-$1-KK-$2-KK-$3-KK"))

  //    println("[[Series:OO XC 2011 | 2011]]".replaceAll(pat, "KK-$1-KK-$2-KK-$3-KK-$4-KK-$5-KK"))
  //    println("[[OO]]".replaceAll(pat, "KK-$1-KK-$2-KK-$3-KK-$4-KK-$5-KK"))
    
  "_____habibi__" eqps Wikis.formatName("[] /:habibi{}")
  
  println ("ERRORS: "+eqpsCollector.size)
  println (eqpsCollector.mkString("\n"))
  
}
