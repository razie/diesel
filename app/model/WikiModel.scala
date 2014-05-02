/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.novus.salat._
import com.novus.salat.annotations._
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import scala.util.parsing.combinator.RegexParsers
import admin.Audit
import scala.util.matching.Regex.Match
import scala.util.matching.Regex
import razie.Log
import razie.base.data.TripleIdx
import admin.Notif
import admin.WikiConfig
import play.mvc.Http.Request
import razie.base.ActionContext
import db.RazSalatContext._
import db.{ RTable, RCreate, RDelete }
import admin.Services
import admin.WikiConfig

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
@RTable
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  by: ObjectId,
  tags: Seq[String] = Seq(),
  realm:String = "rk",
  ver: Int = 1,
  parent: Option[ObjectId] = None,
  props: Map[String, String] = Map.empty,
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

  def cloneContent(newcontent: String) = copy(content = newcontent)

  def cloneNewVer(label: String, markup: String, content: String, by: ObjectId, props: Map[String, String] = this.props) =
    WikiEntry(category, name, label, markup, content, by, tags, realm, ver + 1, parent, props, crDtm, DateTime.now, _id)

  def cloneParent(p: Option[ObjectId]) = copy(parent = p, updDtm = DateTime.now)

  def cloneProps(m: Map[String, String], sby: ObjectId) =
    WikiEntry(category, name, label, markup, content, sby, tags, realm, ver, parent, this.props ++ m, crDtm, DateTime.now, _id)

  def withTags(s: Seq[String], sby: ObjectId) =
    copy(tags=s)
//    WikiEntry(category, name, label, markup, content, sby, ver, parent, this.props + ("tags" -> s.mkString(",")), crDtm, DateTime.now, _id)

  def findParent = parent flatMap (p => Wikis.find(p))

  def isReserved = props.get("reserved").exists(_ == "yes")

  def isPrivate = "User" == category || (props.exists(e => "owner" == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => "owner" == e._1 && id == e._2))
  def owner = props.get("owner").flatMap(s => WikiUsers.impl.findUserById(new ObjectId(s)))

  def create = {
    // TODO optimize exists
    if (Wikis.find(wid).isDefined) {
      Log.error("ERR_WIKI page exists " + wid)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    Audit.logdbWithLink(
      AUDIT_WIKI_CREATED,
      s"/wiki/${wid.wpath}",
      "BY " + (WikiUsers.impl.findUserById(this.by).map(_.userName).getOrElse(this.by.toString)) +
        " " + category + ":" + name)
    Wikis.weTable(wid.cat) += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    WikiIndex.create(this)
  }

  def update(newVer: WikiEntry)(implicit txn:db.Txn) = {
    Audit.logdbWithLink(
      AUDIT_WIKI_UPDATED,
      s"/wiki/${newVer.wid.wpath}",
      s"""BY ${(WikiUsers.impl.findUserById(newVer.by).map(_.userName).getOrElse(newVer.by.toString))} - $category : $name ver ${newVer.ver}""")
    WikiEntryOld(this).create
    // TODO why am I using these instead of the _id ?
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    db.RUpdate.noAudit[WikiEntry](Wikis.weTables(wid.cat), key, newVer)
//    Wikis.weTable(wid.cat).m.update(key, grater[WikiEntry].asDBObject(Audit.updatenoaudit(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))

    if (shouldIndex) WikiIndex.update(this, newVer)
  }

  def shouldIndex = !(Wikis.PERSISTED contains wid.cat)

  def delete(sby: String) (implicit txn:db.Txn) = {
    Audit.logdb(AUDIT_WIKI_DELETED, "BY " + sby + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    db.RDelete.apply (Wikis.weTables(wid.cat), key)
    if (shouldIndex) WikiIndex.delete(this)
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  /** wiki sections are delimited by {{section:name}} */
  lazy val sections = {
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    val PATT1 = """(?s)\{\{\.*(section|template|def|lambda):([^:}]*)(:)?([^}]*)?\}\}((?>.*?(?=\{\{/)))\{\{/\.*(section|template|def|lambda)\}\}""".r //?s means DOTALL - multiline
    val PATT2 = PATT1

    (for (m <- PATT1.findAllIn(content)) yield {
      val mm = PATT2.findFirstMatchIn(m).get
      WikiSection(this, mm.group(1), mm.group(2), mm.group(4), mm.group(5))
    }).toList
  }

  /** pattern for all sections requiring signing - (?s) means multi-line */
  val PATTSIGN = """(?s)\{\{(template|def|lambda):([^:}]*)(:REVIEW[^}]*)\}\}((?>.*?(?=\{\{/)))\{\{/(section|template|def|lambda)\}\}""".r //?s means DOTALL - multiline

  /** find a section */
  def section(stype: String, name: String) = sections.find(x => x.stype == stype && x.name == name)

  /** scripts are just a special section */
  lazy val scripts = sections.filter(x => Array("def", "lambda").contains(x.stype))

  /** pre processed form - parsed and graphed */
  lazy val preprocessed = {
    val s = Wikis.preprocess(this.wid, this.markup, Wikis.noporn(this.content))
    // apply transformations
    s.decs.map(x => x(this))
    // add hardcoded attributes
    WikiParser.State(s.s,
      Map("category" -> category, "name" -> name, "label" -> label, "url" -> (category + ":" + name),
        "tags" -> tags.mkString(",")) ++ s.tags,
      s.ilinks, s.decs)
  }

  def grated = grater[WikiEntry].asDBObject(this)

  override def toString: String =
    grater[WikiEntry].asDBObject(this).toString

  /** tags collected during parsing of the content, with some static tags like url,label etc */
  def contentTags = preprocessed.tags

  /** all the links from this page to others, based on parsed content */
  def ilinks = preprocessed.ilinks

  /** tags of the page, nothing to do with parsing */
//  def tags = props.get("tags").map(_.split(",").toSeq).getOrElse(Seq())

  final val AUDIT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDIT_WIKI_UPDATED = "WIKI_UPDATED "
  final val AUDIT_WIKI_DELETED = "WIKI_DELETED "

  /** field definitions contained - added to during parsing */
  var fields = new scala.collection.mutable.HashMap[String, FieldDef]()
  lazy val form = new WikiForm(this)
  def formRole = this.props.get(FormStatus.FORM_ROLE)
}

/** a form field definition */
case class FieldDef(name: String, value: String, attributes: Map[String, String]) {
  def withValue(x: String) = FieldDef(name, x, attributes)
}

/** a section inside a wiki page */
case class WikiSection(parent: WikiEntry, stype: String, name: String, signature: String, content: String) {
  def sign = Services.auth.sign(content)

  def checkSignature = Services.auth.checkSignature(sign, signature)

  override def toString = s"WikiSection(stype=$stype,name=$name,signature=$signature,content=$content)"
}

object WikiEntry {
  final val UPD_CONTENT = "UPD_CONTENT"
  final val UPD_TOGGLE_RESERVED = "UPD_TOGGLE_RESERVED"
  final val UPD_UOWNER = "UPD_UOWNER"

  def grated(o: DBObject) = grater[WikiEntry].asObject(o)
}

/** old wiki entries - a copy of each older version when udpated or deleted */
@RTable
case class WikiEntryOld(entry: WikiEntry, _id: ObjectId = new ObjectId()) {
  def create (implicit txn:db.Txn) = RCreate.noAudit[WikiEntryOld](this)
}

/** a wiki id, a pair of cat and name - can reference a wiki entry or a section of an entry */
case class WID(cat: String, name: String, parent: Option[ObjectId] = None, section: Option[String] = None) {
  override def toString = "[[" + wpath + "]]" //cat + ":" + name + (section.map("#"+_).getOrElse("")) + parent.map(" of " + _.toString).getOrElse("")

  lazy val grated     = grater[WID].asDBObject(this)
  lazy val findParent = parent flatMap (p => Wikis.find(p))
  lazy val parentWid  = parent flatMap (p => WikiIndex.withIndex { index => index.find { case (a, b, c) => c == p }.map(_._2) }) orElse (findParent map(_.wid))

  lazy val page = Wikis.find(this)
  def findId = {
    WikiIndex.withIndex { idx =>
      idx.get2(name, this)
    } orElse Wikis.find(this).map(_._id) 
  }

  /** format into nice url */
//  def wpath: String = findParent.map(_.wid.wpath + "/").getOrElse("") + (if (cat != null && cat.length > 0) (cat + ":") else "") + name + (section.map("#" + _).getOrElse(""))
  def wpath: String = parentWid.map(_.wpath + "/").getOrElse("") + (if (cat != null && cat.length > 0) (cat + ":") else "") + name + (section.map("#" + _).getOrElse(""))
  def formatted = WID(cat, Wikis.formatName(this), parent, section)
  def url: String = "http://" + Services.config.hostport + "/wiki/" + wpath
  def urlRelative: String = "/wiki/" + wpath
  def ahref: String = "<a href=\"" + url + "\">" + toString + "</a>"

  /** helper to get a label, if defined or the default provided */
  def label(id: String, alt: String) = WikiDomain.labelFor(this, id).getOrElse(alt)
  def label(id: String) = WikiDomain.labelFor(this, id).getOrElse(id)
}

/** a special command wid, contains a command, what and wid - used in routes */
case class CMDWID(wpath: Option[String], wid: Option[WID], cmd: String, rest: String)

object WID {
  private val REGEX = """([^/:\]]*[:])?([^#|\]]+)(#[^|\]]+)?""".r

  private def widFromSeg(a: Array[String]) = {
    val w = a.map { x =>
      x match {
        case REGEX(c, n, s) => WID((if (c == null) "" else c).replaceFirst(":", ""), n, None, Option(s).filter(_.length > 1).map(_.substring(1)))
        case _ => UNKNOWN
      }
    }
    val res = w.foldLeft[Option[WID]](None)((x, y) => Some(WID(y.cat, y.name, x.flatMap(_.findId), y.section)))
    res
  }

  def fromPath(path: String): Option[WID] = {
    if (path == null || path.length() == 0)
      None
    else {
      val a = path.split("/")
      widFromSeg(a)
    }
  }

  def cmdfromPath(path: String): Option[CMDWID] = {
    if (path == null || path.length() == 0)
      None
    else {
      // TODO optimize this copy/paste later
      if (path contains "/xp/") {
        val b = path split "/xp/"
        val a = b.head split "/"
        Some(CMDWID(b.headOption, widFromSeg(a), "xp", b.tail.headOption.getOrElse("")))
      } else if (path contains "/xpl/") {
        val b = path split "/xpl/"
        val a = b.head split "/"
        Some(CMDWID(b.headOption, widFromSeg(a), "xpl", b.tail.headOption.getOrElse("")))
      } else if (path contains "/tag/") {
        val b = path split "/tag/"
        val a = b.head split "/"
        Some(CMDWID(b.headOption, widFromSeg(a), "tag", b.tail.headOption.getOrElse("")))
      } else {
        val a = path split "/"
        Some(CMDWID(Some(path), widFromSeg(a), "", ""))
      }
    }
  }

  val NONE = WID("?", "?")
  val UNKNOWN = WID("?", "?")
}

/** a link between two wikis */
@RTable
case class WikiLink(
  from: WID,
  to: WID,
  how: String,
  crDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) {
  import admin.M._

  def create (implicit txn:db.Txn) = RCreate[WikiLink](this)

  val wname = Array(from.cat, from.name, to.cat, to.name).mkString(":")

  def page = Wikis.find("WikiLink", wname)
  def pageFrom = Wikis.find(from)
  def pageTo = Wikis.find(to)

  def isPrivate = List(pageFrom, page).flatMap(_ map (_.isPrivate)).exists(identity)

  def grated = grater[WikiLink].asDBObject(this)

  def delete = RDelete[WikiLink](this)
}

object ILink {
  def apply(wid: WID) = new ILink(wid, wid.name)
}

/** most information about a page */
case class ILink(wid: WID, label: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = Nil) {
  def href = Services.config.urlmap("/wiki/%s".format(wid.wpath))
  def format = Wikis.formatWikiLink(wid, wid.name, label, None)
}
