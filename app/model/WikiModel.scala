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
import admin.Config

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  by: String,
  ver: Int = 1,
  parent: Option[ObjectId] = None,
  props: Map[String, String] = Map(),
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  /** is this just an alias? */
  def alias: Option[WID] = {
    val wikip2 = """\[\[alias:([^\]]*)\]\]"""
    val wikip2r = wikip2.r
    if (content.matches(wikip2)) {
      val wikip2r(wpath) = content
      WID.fromPath(wpath)
    } else None
  }

  def wid = new WID(category, name, parent)

  def cloneRenamed(newlabel: String) = copy(name = Wikis.formatName(newlabel), label = newlabel, ver = ver + 1, updDtm = DateTime.now)
  //    WikiEntry (category, Wikis.formatName(newlabel), newlabel, markup, content, by, ver + 1, parent, props, crDtm, DateTime.now, _id)

  def cloneNewVer(label: String, markup: String, content: String, by: String, props: Map[String, String] = this.props) =
    WikiEntry(category, name, label, markup, content, by, ver + 1, parent, props, crDtm, DateTime.now, _id)

  def cloneParent(p: Option[ObjectId]) = copy(parent = p, updDtm = DateTime.now)
  //    WikiEntry (category, name, label, markup, content, by, ver, p, props, crDtm, DateTime.now, _id)

  def cloneProps(m: Map[String, String], sby: String) =
    WikiEntry(category, name, label, markup, content, sby, ver, parent, m, crDtm, DateTime.now, _id)

  def findParent = parent flatMap (p => Wikis.find(p))

  def isReserved = props.get("reserved").map(_ == "yes").getOrElse(false)

  def isPrivate = "User" == category || (props.exists(e => "owner" == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => "owner" == e._1 && id == e._2))

  def create = {
    // TODO optimize exists
    if (Wikis.find(wid).isDefined) {
      Log.error("ERR_WIKI page exists " + wid)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    Audit.logdb(AUDIT_WIKI_CREATED, "BY " + this.by + " " + category + ":" + name, "\nCONTENT:\n" + this)
    Wikis.table += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    Wikis.withIndex { _.put(name, wid, _id) }
  }

  def update(newVer: WikiEntry) = {
    Audit.logdb(AUDIT_WIKI_UPDATED, "BY " + newVer.by + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.updatenoaudit(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    if (category != newVer.category || name != newVer.name) {
      Wikis.withIndex { idx =>
        idx.put(newVer.name, newVer.wid, _id)
        idx.remove2(name, wid)
      }
    }
  }

  def delete(sby: String) = {
    Audit.logdb(AUDIT_WIKI_DELETED, "BY " + sby + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    Wikis.table.m.remove(key)
    Wikis.withIndex { idx =>
      idx.remove2(name, wid)
    }
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  /** pre processed form - parsed and graphed */
  lazy val preprocessed = {
    val s = Wikis.preprocess(this.wid, this.markup, this.content)
    // add hardcoded attributes
    WikiParser.State(s.s, s.tags ++ Map("category" -> category, "name" -> name, "label" -> label, "url" -> (category + ":" + name)), s.ilinks)
  }

  def grated = grater[WikiEntry].asDBObject(this)

  override def toString: String =
    grater[WikiEntry].asDBObject(this).toString

  def tags = preprocessed.tags
  def ilinks = preprocessed.ilinks

  final val AUDIT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDIT_WIKI_UPDATED = "WIKI_UPDATED "
  final val AUDIT_WIKI_DELETED = "WIKI_DELETED "
}

object WikiEntry {
  final val UPD_CONTENT = "UPD_CONTENT"
  final val UPD_TOGGLE_RESERVED = "UPD_TOGGLE_RESERVED"

  def grated(o: DBObject) = grater[WikiEntry].asObject(o)

}

case class WikiEntryOld(
  entry: WikiEntry,
  _id: ObjectId = new ObjectId()) {
  def create = {
    Mongo("WikiEntryOld") += grater[WikiEntryOld].asDBObject(Audit.createnoaudit(this))
  }
}

/** a wiki id, a pair of cat and name */
case class WID(cat: String, name: String, parent: Option[ObjectId] = None) {
  override def toString = cat + ":" + name + parent.map(" of " + _.toString).getOrElse("")
  def grated = grater[WID].asDBObject(this)
  def findParent = parent flatMap (p => Wikis.find(p))
  lazy val parentWid = parent flatMap (p => Wikis.withIndex { index => index.find { case (a, b, c) => c == p }.map(_._2) })
  def findId = Wikis.find(this).map(_._id) // TODO optimize with cache lookup
  def wpath: String = findParent.map(_.wid.wpath + "/").getOrElse("") + (if (cat != null && cat.length > 0) (cat + ":") else "") + name
  def formatted = WID(cat, Wikis.formatName(this), parent)
}

object WID {
  def fromPath(path: String): Option[WID] = {
    val a = path.split("/")
    val w = a.map { x =>
      val regex = """([^/:\]]*[:])?([^|\]]+)""".r
      val regex(c, n) = x
      WID((if (c == null) "" else c).replaceFirst(":", ""), n)
    }
    //    debug ("wikieEdit "+w.mkString)
    val res = w.foldLeft[Option[WID]](None)((x, y) => Some(WID(y.cat, y.name, x.flatMap(_.findId))))
    //    debug ("wikieEdit "+res)
    res
  }

  val NONE = WID("?", "?")
  val UNKNOWN = WID("?", "?")
}

/** a link between two wikis */
case class WikiLink(from: WID, to: WID, how: String) {
  def create = Mongo("WikiLink") += grater[WikiLink].asDBObject(Audit.create(this))

  val wname = Array(from.cat, from.name, to.cat, to.name).mkString(":")

  def page = Wikis.find("WikiLink", wname)
  def pageFrom = Wikis.find(from.cat, from.name)
  def pageTo = Wikis.find(to.cat, to.name)

  def isPrivate = List(pageFrom, page).flatMap(_ map (_.isPrivate)).exists(identity)

  def grated = grater[WikiLink].asDBObject(this)
}

//  lazy val stuff = Mongo("UserStuff").findOne(Map("email" -> email)) map (grater[UserStuff].asObject(_))

class NewTripleIdx[A, B, C] extends TripleIdx[A, B, C] {
  def find(f: (A, B, C) => Boolean): Option[(A, B, C)] = {
    for (a <- idx; x <- a._2)
      if (f(a._1, x._1, x._2))
        return Some((a._1, x._1, x._2))
    None
  }

  def foreach(f: (A, B, C) => Unit): Unit = {
    for (a <- idx; x <- a._2)
      f(a._1, x._1, x._2)
  }

  def map[R](f: (A, B, C) => R): Seq[R] = {
    (for (a <- idx; x <- a._2)
      yield f(a._1, x._1, x._2)).toList
  }
}

/** wiki factory and utils */
object Wikis {
  def table = Mongo("WikiEntry")

  def fromGrated[T <: AnyRef](o: DBObject)(implicit m: Manifest[T]) = grater[T](ctx, m).asObject(o)

  def pages(category: String) =
    table.m.find(Map("category" -> category)) map (grater[WikiEntry].asObject(_))

  def pageNames(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("name").toString)

  def pageLabels(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("label").toString)

  def label(wid: WID) = ifind(wid) map (_.apply("label")) getOrElse wid.name

  def findById(id: String) = find(new ObjectId(id))

  def find(id: ObjectId) =
    table.findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))

  private def ifind(wid: WID) =
    if (wid.parent.isDefined)
      table.findOne(Map("category" -> wid.cat, "name" -> wid.name, "parent" -> wid.parent.get))
    else
      table.findOne(Map("category" -> wid.cat, "name" -> Wikis.formatName(wid.name)))

  def find(wid: WID): Option[WikiEntry] = ifind(wid) map (grater[WikiEntry].asObject(_))

  def find(category: String, name: String): Option[WikiEntry] = find(WID(category, name))

  def findAny(name: String) =
    table.find(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

  def findAnyOne(name: String) =
    table.findOne(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

  def linkFromName(s: String) = {
    val a = s.split(":")
    WikiLink(WID(a(0), a(1)), WID(a(2), a(3)), "?")
  }

  // modify external sites mapped to external URLs
  def urlmap(u: String) = {
    var res = u

    for (has <- config(URLMAP); site <- has) {
      res = res.replaceFirst("^%s".format(site._1), site._2)
    }
    res
  }

  // modify external sites mapped to external URLs
  def urlfwd(u: String) = {

    for (has <- config(URLFWD); site <- has.get(u))
      yield site
  }

  // site cfg parms
  def sitecfg(parm: String) = {
    config(SITECFG).flatMap(_.get(parm))
  }

  def config(s: String) = {
    if (xconfig.isEmpty) reloadUrlMap
    xconfig.get(s)
  }

  private val xconfig = scala.collection.mutable.Map[String, Map[String, String]]()

  final val URLMAP = "urlmap"
  final val URLFWD = "urlfwd"
  final val SITECFG = "sitecfg"
  final val TOPICRED = "topicred"

  def reloadUrlMap {
    for (c <- Array(URLMAP, URLFWD, SITECFG, TOPICRED)) {
      val urlmaps = Some(table.find(Map("category" -> "Admin", "name" -> c)) map (grater[WikiEntry].asObject(_)) map (_.content) flatMap (_.split("\r\n")) filter (!_.startsWith("#")) map (_.split("=")) filter (_.size == 2) map (x => (x(0), x(1))))
      val xurlmap = urlmaps.map(_.toMap)
      println("RELOADING URL MAP ==========================================" + c)
      println(xurlmap)
      xurlmap.map(xconfig.put(c, _))
      println("-----------")
      println(xconfig.get(c))
    }
    println("-----------")
    println(xconfig)
  }

  // TODO cache
  def categories =
    table.find(Map("category" -> "Category")) map (grater[WikiEntry].asObject(_))

  // TODO cache
  def category(cat: String) =
    table.findOne(Map("category" -> "Category", "name" -> cat)) map (grater[WikiEntry].asObject(_))

  // TODO cache
  def visibilityFor(cat: String): Seq[String] =
    //    category(cat).map(_.props).get//.get("visibility")).map(_.split(",")).getOrElse (Array("Public"))
    category(cat).flatMap(_.tags.get("visibility")).map(_.split(",").toSeq).getOrElse(Seq("Public"))

  def linksFrom(from: WID, role: String) =
    Mongo("WikiLink").find(Map("from" -> from.grated, "how" -> role)) map (grater[WikiLink].asObject(_))

  def linksTo(to: WID, role: String) =
    Mongo("WikiLink").find(Map("to" -> to.grated, "how" -> role)) map (grater[WikiLink].asObject(_))

  /** the index is (name, category, ID) */
  def withIndex[A](f: NewTripleIdx[String, WID, ObjectId] => A) = {
    synchronized {
      f(index)
    }
  }
  // TODO sync reader/writer for udpates
  private lazy val index = {
    val t = new NewTripleIdx[String, WID, ObjectId]()
    table.find(Map()).foreach { db =>
      val w = WID(db.as[String]("category"), db.as[String]("name"), if (db.containsField("parent")) Some(db.as[ObjectId]("parent")) else None)
      t.put(w.name, w, db.as[ObjectId]("_id"))
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
  def formatName(wid: WID): String = if ("WikiLink" == wid.cat) iformatName(wid.name, """[ /{}\[\]]""") else formatName(wid.name)

  /** format an even more complex name */
  def formatWikiLink(wid: WID, nicename: String, label: String, hover: Option[String] = None, rk:Boolean=false) = {
    val name = Wikis.formatName(wid.name)
    val title = hover.map("title=\"" + _ + "\"") getOrElse ("")

    Wikis.withIndex { index =>
      if (index.idx.contains(name) || wid.cat.matches("User")) {
        var u = Wikis.urlmap("/wiki/%s".format(wid.formatted.wpath))

        if (rk && (u startsWith "/")) u = "http://"+Config.hostport+u

        ("""<a href="%s" title="%s">%s</a>""".format(u, title, label),
          Some(ILink(wid, label)))
      } else
        // hide it from google
        ("""<a href="/wikie/%s" title="%s">%s<sup><b style="color:red">++</b></sup></a>""".format(wid.wpath, hover.getOrElse("Missing page"), label),
          Some(ILink(wid, label)))
    }
  }

  def shouldFlag(name: String, label: String, content: String): Option[String] = {
    val a = Array(name, label, content)

    if (a.exists(_.matches("(?i)^.*<(" + WikiParser.hnok + ")([^>]*)>"))) Some("WIKI_FORBIDDEN_HTML")
    else if (hasporn(content, softporn)) Some("WIKI_HAS_SOFTPO")
    else None
  }

  // TODO better escaping of all url chars in wiki name
  def preprocess(wid: WID, markup: String, content: String) = markup match {
    case MD =>
      var c2 = content
      if (c2 contains "[[./")
        c2 = content.replaceAll("""\[\[\./""", """[[%s/""".format(wid.cat + ":" + wid.name)) // child topics
      if (c2 contains "[[../")
        c2 = c2.replaceAll("""\[\[\../""", """[[%s/""".format(wid.parentWid.map(wp => wp.cat + ":" + wp.name).getOrElse("?"))) // siblings topics
      (for (
        s @ WikiParser.State(a0, tags, ilinks) <- Some(WikiParser(c2))
      ) yield s) getOrElse WikiParser.State("")
    case TEXT => WikiParser.State(content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]"""))
    case _ => WikiParser.State("UNKNOWN MARKUP " + markup + " - " + content)
  }

  /** main formatting function */
  def format(wid: WID, markup: String, icontent: String) = {
    var res = try {
      val content = preprocess(wid, markup, noporn(icontent)).s
      markup match {
        case MD => toXHTML(knockoff(content)).toString
        case TEXT => content
        case _ => "UNKNOWN MARKUP " + markup + " - " + content
      }
    } catch {
      case e @ _ => {
        log("ERROR formatting wiki: " + e)
        "ERROR formatting wiki..."
      }
    }

    // mark the external links
    res = res.replaceAll("""(<a +href="http:)([^>]*)>([^<]*)(</a>)""", """$1$2 title="External site"><i>$3</i><sup>&nbsp;<b style="color:darkred">^^</b></sup>$4""")
    //    // modify external sites mapped to external URLs
    //    // TODO optimize - either this logic or a parent-based approach
    //    for (site <- Wikis.urlmap)
    //      res = res.replaceAll ("""<a +href="%s""".format(site._1), """<a href="%s""".format(site._2))

    res
  }

  def noporn(s: String) = porn.foldLeft(s)((x, y) => x.replaceAll("""\b%s\b""".format(y), "BLIP"))

  def hasporn(s: String, what: Array[String] = porn): Boolean = s.toLowerCase.split("""\w""").exists(what.contains(_))

  def flag(we: WikiEntry) { flag(we.wid) }

  def flag(wid: WID, reason: String = "?") {
    Audit.logdb("WIKI_FLAGGED", reason, wid.toString)
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

/**
 * sed like filter using Java regexp
 *
 *  example: from US to normal: Sed ("""(\d\d)/(\d\d)/(\d\d)""", """\2/\1/\3""", "01/31/12")
 *
 *  Essentially useless since plain "sss".replaceAll(..., "$1 $2...") works almost the same way..
 */
object SedWiki {
  val SEARCH = """search:?([^]]*)""".r
  val LIST = """list:?([^]]*)""".r
  val ALIAS = """alias:([^\]]*)""".r
  val NOALIAS = """(rk:)?([^|\]]*)([ ]*[|][ ]*)?([^]]*)?""".r

  def apply(repf: (String => String), input: String): Option[(String, Option[ILink])] = {
    var i: Option[ILink] = None

    input match {
      case SEARCH(nm) =>
        Some("""<a href="http://google.com/search?q=""" + Enc.toUrl(nm) + "\">" + nm + "</a>", None)

      case LIST(cat) => {
        Some(((if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(cat).toList.sortWith(_ < _).map { p =>
          Wikis.formatWikiLink(WID(cat, p), p, p)
        }.map(_._1).mkString(" ")
        else "TOO MANY to list"), None))
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
        wid map (w => Wikis.formatWikiLink(w, w.name, (if (label != null && label.length > 1) label else w.name), None, rk != null && rk.length>0))
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

object ILink {
  def apply(wid: WID) = new ILink(wid, wid.name)
}

/** most information about a page */
case class ILink(wid: WID, label: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = Nil) {
  def href = Wikis.urlmap("/wiki/%s".format(wid.wpath))
  def format = Wikis.formatWikiLink(wid, wid.name, label, None)
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
  def CRLF3: P = CRLF2 ~ CRLF2 ^^ { case a ~ b => a + b } // an empty line = two eol
  //  def CRLF3: P = CRLF2 ~ CRLF2 ~ rep(CRLF2) ^^ { case a ~ b ~ c => "\n<p></p>\n" } // an empty line = two eol
  def NADA: P = ""

  // static must be stopped to not include too much - that's why the last expr
  def static: P = not("{{") ~> not("[[") ~> not("}}") ~> not("[http:") ~> (""".""".r) ~ ("""[^{}\[\]`\r\n]""".r*) ^^ { case a ~ b => a + b.mkString }
}

/** wiki parser */
object WikiParser extends ParserCommons with CsvParser {
  case class State(s: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = List())
  implicit def toState(s: String) = State(s)

  type PS = Parser[State]
  def apply(input: String) = parseAll(wiki, input) getOrElse (State("[[CANNOT PARSE]] - sorry, dumb program here! The content is not lost: try editing this topic... also, please open a support issue and copy/paste there the content."))
  def applys(input: String) = apply(input).s

  /** use this to expand [[xxx]] on the spot */
  def parseW2(input: String) = parseAll(wiki2, input) getOrElse (State("[[CANNOT PARSE]]"))

  def xCRLF1: PS = CRLF1 ^^ { case x => x }
  def xCRLF2: PS = CRLF2 ^^ { case x => x }
  def xCRLF3: PS = CRLF3 ^^ { case x => x }
  def xNADA: PS = NADA ^^ { case x => x }
  def xstatic: PS = static ^^ { case x => x }
  def escaped: PS = "`" ~ opt(""".[^`]*""".r) ~ "`" ^^ { case a ~ b ~ c => a + b.getOrElse("") + c }

  //============================== wiki parsing

  def wiki: PS = lines | line | xCRLF2 | xNADA

  def line: PS = opt(lists) ~ rep(escaped | badHtml | badHtml2 | wiki3 | wiki2 | link1 | wikiProps | xstatic) ^^ {
    case ol ~ l => State(ol.getOrElse(State("")).s + l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks))
  }

  def lines: PS = rep(optline ~ (CRLF1 | CRLF3 | CRLF2)) ~ opt(line) ^^ {
    case l ~ c =>
      State(
        l.map(t => t._1.s + t._2.s).mkString + c.map(_.s).getOrElse(""),
        l.flatMap(_._1.tags).toMap ++ c.map(_.tags).getOrElse(Map()),
        l.flatMap(_._1.ilinks).toList ++ c.map(_.ilinks).getOrElse(Nil))
  }

  def optline: PS = opt(line) ^^ { case o => o.map(identity).getOrElse(State("")) }

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

  def link1: PS = "[" ~> """http://[^] ]*""".r ~ opt("[ ]+".r ~ """[^]]*""".r) <~ "]" ^^ {
    case url ~ Some(sp ~ text) => """[%s](%s)""".format(text, url)
    case url ~ None => """[%s](%s)""".format(url, url)
  }

  def lists = li1 | li2 | li3
  def li1: PS = """^ \* """.r ^^ { case x => "    * " }
  def li2: PS = """^  \* """.r ^^ { case x => "        * " }
  def li3: PS = """^   \* """.r ^^ { case x => "            * " }

  //======================= forbidden html tags TODO it's easier to allow instead?

  val hok = "abbr|acronym|address|b|blockquote|br|div|dd|dl|dt|font|h1|h2|h3|h4|h5|h6|hr|i|img|li|p|pre|q|s|small|strike|strong|span|sub|sup|" +
    "table|tbody|td|tfoot|th|thead|tr|ul|u"
  val hnok = "applet|area|a|base|basefont|bdo|big|body|button|caption|center|cite|code|colgroup|col|" +
    "del|dfn|dir|fieldset|form|frame|frameset|head|html|iframe|input|ins|isindex|kbd|" +
    "label|legend|link|map|menu|meta|noframes|noscript|object|ol|" +
    "optgroup|option|param|samp|script|select|style|textarea|title|tt|var"

  val safeSites = Set("http://maps.google.ca", "http://maps.google.com", "http://www.everytrail.com", "http://www.youtube.com")
  //  def iframe: PS = "<iframe" ~> """[^>]*""".r ~ """src="""".r ~ """[^"]*""".r ~ """[^>]*""".r <~ ">" ^^ {
  def iframe: PS = "<iframe" ~> """[^>]*""".r <~ ">" ^^ {
    case a => {
      val url = a.replaceAll(""".*src="(.*)".*""", "$1")
      if (safeSites.exists(x => url.startsWith(x))) "<iframe" + a + "></iframe>"
      else "&lt;iframe" + a + "&gt;"
    }
  }

  def badHtml: PS = iframe | badTags

  def badTags: PS = "<" ~> hnok.r ~ opt(" " ~ """[^>]*""".r) <~ ">" ^^ {
    case b ~ Some(c ~ d) => "&lt;" + b + c + d + "&gt;"
    case b ~ None => "&lt;" + b + "&gt;"
  }

  def badHtml2: PS = "</" ~> hnok.r <~ ">" ^^ {
    case b => "&lt;/" + b + "&gt;"
  }

  //======================= {{name:value}}

  // this is used when matching a link/name
  def wikiPropsRep: PS = rep(wikiPropMagicName | wikiPropByName | wikiPropWhenName | wikiPropWhereName | wikiPropLocName | wikiPropRoles | wikiProp | xstatic) ^^ { case l => State(l.map(_.s).mkString, l.flatMap(_.tags).toMap, l.flatMap(_.ilinks)) }

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

  def wikiProp: PS = "{{" ~> """[^}:]+""".r ~ ":" ~ """[^}]*""".r <~ "}}" ^^ {
    case name ~ _ ~ value =>
      if (name startsWith ".")
        State("", Map(name.substring(1) -> value)) // hidden
      else
        State("""{{Property %s=%s}}""".format(name, value), Map(name -> value))
  }

  def wikiPropByName: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{by %s}}""".format(place), Map("by" -> place))
  }

  def wikiPropBy: PS = ("\\{\\{[Bb]y[: ]+".r | "\\{\\{[Cc]lub[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{by " + parseW2("""[[Club:%s]]""".format(place)).s + "}}", Map("club" -> place), ILink(WID("Club", place), place) :: Nil)
  }

  def wikiPropWhere: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("{{at " + parseW2("""[[Venue:%s]]""".format(place)).s + "}}", Map("venue" -> place), ILink(WID("Venue", place), place) :: Nil)
  }

  def wikiPropWhereName: PS = ("\\{\\{where[: ]".r | "\\{\\{[Aa]t[: ]+".r | "\\{\\{[Pp]lace[: ]+".r | "\\{\\{[Vv]enue[: ]+".r) ~> """[^}]*""".r <~ "}}" ^^ {
    case place => State("""{{at %s}}""".format(place), Map("venue" -> place), ILink(WID("Venue", place), place) :: Nil)
  }

  def wikiPropLoc: PS = "{{" ~> "loc:" ~> """[^}:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
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

  def wikiPropLocName: PS = "{{" ~> "loc:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
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

  def wikiPropRoles: PS = "{{" ~> "roles:" ~> """[^:]*""".r ~ ":".r ~ """[^}]*""".r <~ "}}" ^^ {
    case cat ~ colon ~ how => {
      if ("Child" == how)
        State("{{Has " + parseW2("[[%s]]".format(cat)).s + "(s)}}", Map("roles:" + cat -> how))
      else if ("Parent" == how)
        State("{{Owned by " + parseW2("[[%s]]".format(cat)).s + "(s)}}", Map("roles:" + cat -> how))
      else
        State("{{Can link from " + parseW2("[[%s]]".format(cat)).s + "(s) as %s}}".format(how), Map("roles:" + cat -> how))
    }
  }

  //======================= delimited imports and tables

  def wikiPropCsv: PS = "{{" ~> "delimited:" ~> (wikiPropCsvStart >> { h: CsvHeading => csv(h.delim) ^^ { x => (h, x) } }) <~ "{{/delimited}}" ^^ {
    case (a, body) => {
      val c = body
      a.s + c.map(l =>
        if (l.size > 0)
          ("\n* " + parseW2("[[" + a.what + ":" + l.zip(a.h).map(c =>
          "{{" + c._2 + " " + c._1 + "}}").mkString(", ") + "]]").s)
        else "").mkString + "\n"
    }
  }

  def wikiPropTable: PS = "{{" ~> "table:" ~> (wikiPropTableStart >> { h: CsvHeading => csv(h.delim) ^^ { x => (h, x) } }) <~ "{{/table}}" ^^ {
    case (a, body) => {
      val c = body
      a.s + c.map(l =>
        if (l.size > 0) ("\n<tr>" + l.map(c =>
          "<td>" + c + "</td>").mkString + "</tr>")
        else "").mkString + "\n</table>"
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

  def wikiPropTableStart: Parser[CsvHeading] = """.""".r ~ ":".r ~ opt(csvHeadings) <~ "}}" ^^ {
    case delim ~ _ ~ head => {
      var s = """<table class="table table-striped">"""

      if (head.isDefined)
        s += "\n<thead><tr>" + head.get.h.map(e => "<th>" + e + "</th>").mkString + "</tr></thead>"

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

  def csv(implicit xdelim: String): Parser[List[List[String]]] = csvLines

  def csvCRLF2: PS2 = CRLF2 ^^ { case x => Nil }
  def csvNADA: PS2 = NADA ^^ { case x => Nil }

  def csvLine(implicit xdelim: String): PS1 = cell ~ rep(xdelim.r ~ (cell | NADA)) ^^ {
    case ol ~ l => {
      List(ol) ::: l.map(_._2)
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

  def cell(implicit xdelim: String): P = (not(xdelim) ~> not("{{") ~> not("[[") ~> not("}}") ~> """.""".r+) ^^ { case l => l.mkString }
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

  println(WikiParser.applys(csv1))
  println(WikiParser.applys(csv2))
  println(WikiParser.applys(csv3))
  println(WikiParser.applys(csv4))

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
  val content = Wikis.preprocess(WID.NONE, "md", tabsp1).s
  println(content)
  println(toXHTML(knockoff(content)).toString)
  //  println(knockoff(tabsp))
}

object WikiDomain {

  def zEnds(aEnd: String, role: String) =
    Wikis.categories.filter(_.tags.get("roles:" + aEnd).map(_.split(",")).map(_.contains(role)).getOrElse(false)).toList

  def aEnds(zEnd: String, role: String) =
    for (
      c <- Wikis.category(zEnd).toList;
      t <- c.tags if (t._2.split(",").contains(role))
    ) yield t._1.split(":")(1)

  def needsOwner(cat: String) =
    model.Wikis.category(cat).flatMap(_.tags.get("roles:" + "User")).map(_.split(",").contains("Owner")).getOrElse(false)

}

