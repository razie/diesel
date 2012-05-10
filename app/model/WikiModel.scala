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

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  ver: Int = 1,
  props: Map[String, String] = Map(),
  createdDtm: DateTime = DateTime.now,
  lastUpdatedDtm: DateTime = DateTime.now) {

  lazy val key = Map("category" -> category, "name" -> name)

  def newVer(label: String, markup: String, content: String, props: Map[String, String] = this.props) =
    WikiEntry (category, name, label, markup, content, ver + 1, props, createdDtm, DateTime.now)

  def props(m: Map[String, String]) =
    WikiEntry (category, name, label, markup, content, ver, m)

  def isReserved = props.get("reserved").map(_ == "yes").getOrElse(false)

  def isPrivate = "User" == category || (props.exists(e => "owner" == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => "owner" == e._1 && id == e._2))

  def create = {
    // TODO optimize exists
    if (Wikis.find(category, name).isDefined) {
      Log.error("ERR_WIKI page exists " + category + ":" + name)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    auditCreated
    Wikis.table += grater[WikiEntry].asDBObject(Audit.create(this))
  }

  def update(newVer: WikiEntry) = {
    auditUpdated
    Mongo ("WikiEntryOld") += grater[WikiEntry].asDBObject(Audit.create(this))
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.update(newVer)))
  }

  def auditCreated { Log.audit(AUDT_WIKI_CREATED + category + "/" + name) }
  def auditUpdated { Log.audit(AUDT_WIKI_UPDATED + category + "/" + name) }

  final val AUDT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDT_WIKI_UPDATED = "WIKI_UPDAED "
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

  val markups = Array("markdown", "rk", "text", "html")

  import com.tristanhunt.knockoff.DefaultDiscounter._

  def formatName(name: String) = name.replaceAll("[ /]", "_")

  // TODO better escaping of all url chars in wiki name
  def preprocess(markup: String, content: String) = markup match {
    case "markdown" =>
      (for (
        a1 <- SedWiki("""<""", x => "&lt;", formatName, content);
        a2 <- SedWiki(""">""", x => "&gt;", formatName, a1);
        s1 <- SedWiki("""\[\[\[([^]]*)\]\]\]""", SedWiki.expand3, formatName, a2);
        s2 <- SedWiki("""\[\[([^:]*:)?([^/]*/)?([^]]+)\]\]""", SedWiki.expand2, formatName, s1)
      ) yield s2) getOrElse ""
    case "rk"   => content.replaceAll("\\[\\[([^]]*)\\]\\]", "wiki")
    case "html" => content.replaceAll("\\[\\[([^]]*)\\]\\]", "wiki")
    case "text" => content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]""")
    case _      => "UNKNOWN MARKUP " + markup + " - " + content
  }

  def format(markup: String, icontent: String) = {
    val content = preprocess(markup, noporn(icontent))
    markup match {
      case "markdown" => toXHTML(knockoff(content)).toString
      case "rk"       => toXHTML(knockoff(content)).toString
      case "html"     => content
      case "text"     => content
      case _          => "UNKNOWN MARKUP " + markup + " - " + content
    }
  }

  def noporn(s: String) = porn.foldLeft(s)((x, y) => x.replaceAll("""\b%s\b""".format(y), "BLIP"))

  def hasporn(s: String, what: Array[String] = porn): Boolean = s.toLowerCase.split("""\w""").exists(what.contains(_))

  def flag(we: WikiEntry) { flag (we.category, we.name) }

  def flag(c: String, n: String, reason: String = "?") {
    Audit.logdb("WIKI_FLAGGED", reason, c, n)
  }

  final val porn = Array("porn", "fuck", "sex", "dick")

  final val meh = Array("tit", "breast")
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
      def hackmd(s1: String, s2: String, s3: String) = """<a href="/wiki/%s%s%s">%s</a>""".format(s2, (if (s2.isEmpty || s2.endsWith(":")) "" else ":"), s3, s1)
    // should be: "[%s](/wiki/%s/%s)".format(s1,s2,s3)

    val cat = if (m.group(2) == null) "any:" else m.group(2)
    val res = if (m.group(3) != null) hackmd(m.group(3), cat, Wikis.formatName(m.group(3))) else ERR

    if (m.group(1) != null) m.group(1) match {
      case "alias:" => "Alias for " + res
      case "list:" => {
        val c = if (m.group(3) != null) m.group(3) else "?"

        if (Wikis.pageNames(cat).size < 100)
          Wikis.pageNames(c).map { p =>
            hackmd(p, c, p)
          }.mkString(" ")
        else "TOO MANY to list"
      }
      case _ => ERR + res
    }
    else res

  }
  def expand3(m: Match): String = """[$1](http://en.wikipedia.org/wiki/\1)"""

  val ERR = "[ERROR SYNTAX]"

  val patRep = """\\([0-9])""".r
}

object Test extends App {
  import com.tristanhunt.knockoff.DefaultDiscounter._
//  println(toXHTML(knockoff("[wiki link](http://en.wikipedia.org/wiki/Bracket_(disambiguation))")).toString)
  println(toXHTML(knockoff("""     """)).toString)
}
