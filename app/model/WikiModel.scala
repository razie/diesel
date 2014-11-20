/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.novus.salat._
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import razie.Log
import db.RazSalatContext._
import db._
import admin.Services

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source
  *
  * There is an "owner" property - owner is supposed to have special privileges
  */
@RTable
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  by: ObjectId,
  tags: Seq[String] = Seq(),
  realm:String = Wikis.RK,
  ver: Int = 1,
  parent: Option[ObjectId] = None,
  props: Map[String, String] = Map.empty,
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  /** is this just an alias?
    *
    * an alias is a topic that only contains the alias markup: [[alias:xxx]]
    */
  def alias: Option[WID] = {
    val wikip2 = """\[\[alias:([^\]]*)\]\]"""
    val wikip2r = wikip2.r
    if (content.matches(wikip2)) {
      val wikip2r(wpath) = content
      WID.fromPath(wpath)
    } else None
  }

  def wid  = WID(category, name, parent, None, if(realm == Wikis.RK) None else Some(realm))
  def uwid = UWID(category, _id, if(realm == Wikis.RK) None else Some(realm))

  def cloneRenamed(newlabel: String) = copy(name = Wikis.formatName(newlabel), label = newlabel, ver = ver + 1, updDtm = DateTime.now)

  def cloneContent(newcontent: String) = copy(content = newcontent)

  def cloneNewVer(label: String, markup: String, content: String, by: ObjectId, props: Map[String, String] = this.props) =
    copy(label=label, markup=markup, content=content, by=by, ver=ver + 1, props=props, updDtm=DateTime.now)

  def cloneParent(p: Option[ObjectId]) = copy(parent = p, updDtm = DateTime.now)

  def cloneProps(m: Map[String, String], sby: ObjectId) = copy(props = this.props ++ m)

  def withTags(s: Seq[String], sby: ObjectId) = copy(tags=s)

  def findParent = parent flatMap (p => Wikis(realm).find(p))

  def isReserved = props.get("reserved").exists(_ == "yes")

  val PROP_OWNER: String = "owner"

  def isPrivate = "User" == category || (props.exists(e => PROP_OWNER == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => PROP_OWNER == e._1 && id == e._2))
  def owner = props.get(PROP_OWNER).flatMap(s => WikiUsers.impl.findUserById(new ObjectId(s)))

  def create = {
    // TODO optimize exists
    if (Wikis.find(wid).isDefined) {
      Log.error("ERR_WIKI page exists " + wid)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    Audit.logdbWithLink(
      if(wid.cat=="Note") AUDIT_NOTE_CREATED else AUDIT_WIKI_CREATED,
      s"/wiki/${wid.wpath}",
      "BY " + (WikiUsers.impl.findUserById(this.by).map(_.userName).getOrElse(this.by.toString)) +
	" " + category + ":" + name)
    Wikis.weTable(wid.cat) += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    Wikis(realm).index.create(this)
  }

  def update(newVer: WikiEntry)(implicit txn:db.Txn) = {
    Audit.logdbWithLink(
      if(wid.cat=="Note") AUDIT_NOTE_UPDATED else AUDIT_WIKI_UPDATED,
      s"/wiki/${newVer.wid.wpath}",
      s"""BY ${(WikiUsers.impl.findUserById(newVer.by).map(_.userName).getOrElse(newVer.by.toString))} - $category : $name ver ${newVer.ver}""")
    WikiEntryOld(this).create
    db.RUpdate.noAudit[WikiEntry](Wikis.weTables(wid.cat), Map("_id" -> newVer._id), newVer)
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))

    if (shouldIndex) Wikis(realm).index.update(this, newVer)
  }

  /** should this entry be indexed in memory */
  def shouldIndex = !(Wikis.PERSISTED contains wid.cat)

  def delete(sby: String) (implicit txn:db.Txn) = {
    Audit.logdb(AUDIT_WIKI_DELETED, "BY " + sby + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    db.RDelete.apply (Wikis(realm).weTables(wid.cat), key)
    if (shouldIndex) Wikis(realm).index.delete(this)
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  /** reparsing the content - wiki sections are delimited by {{section:name}} */
  lazy val sections = {
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    //todo use the wiki parser later modifiers to load the sections, not a separate parser here
    val PATT1 = """(?s)\{\{\.*(section|template|def|lambda|dsl\.\w*)(:)?([^:}]*)?(:)?([^}]*)?\}\}((?>.*?(?=\{\{/)))\{\{/\.*(section|template|def|lambda|dsl\.\w*)\}\}""".r //?s means DOTALL - multiline
    val PATT2 = PATT1

    (for (m <- PATT1.findAllIn(content)) yield {
      val mm = PATT2.findFirstMatchIn(m).get
      WikiSection(this, mm.group(1), mm.group(3), mm.group(5), mm.group(6))
    }).toList
  }

  /** pattern for all sections requiring signing - (?s) means multi-line */
  val PATTSIGN = """(?s)\{\{(template|def|lambda):([^:}]*)(:REVIEW[^}]*)\}\}((?>.*?(?=\{\{/)))\{\{/(template|def|lambda)\}\}""".r //?s means DOTALL - multiline

  /** find a section */
  def section(stype: String, name: String) = sections.find(x => x.stype == stype && x.name == name)

  /** scripts are just a special section */
  lazy val scripts = sections.filter(x => "def" == x.stype || "lambda" == x.stype)

  /** pre processed form - parsed and graphed */
  lazy val preprocessed = {
    val ss = Wikis.preprocess(this.wid, this.markup, Wikis.noporn(this.content))
    val s = ss.fold(Some(this)) // fold the AST
    // apply transformations
    s.decs.map(x => x(this))
    // add hardcoded attribute - these can be overriden by tags in content
    WikiParser.SState(s.s,
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

  final val AUDIT_WIKI_CREATED = "WIKI_CREATED"
  final val AUDIT_WIKI_UPDATED = "WIKI_UPDATED"
  final val AUDIT_WIKI_DELETED = "WIKI_DELETED"
  final val AUDIT_NOTE_CREATED = "NOTE_CREATED"
  final val AUDIT_NOTE_UPDATED = "NOTE_UPDATED"

  /** field definitions contained - added to during parsing */
  var fields = new scala.collection.mutable.HashMap[String, FieldDef]()
  lazy val form = new WikiForm(this)
  def formRole = this.props.get(FormStatus.FORM_ROLE)

  /** other parsing artifacts to be used by knowledgeable modules.
    * Parsers can put stuff in here. */
  //todo move the fields and form stuff here
  val cache = new scala.collection.mutable.HashMap[String, Any]()
}

/** a form field definition */
case class FieldDef(name: String, value: String, attributes: Map[String, String]) {
  def withValue(x: String) = FieldDef(name, x, attributes)
}

/** a section inside a wiki page */
case class WikiSection(parent: WikiEntry, stype: String, name: String, signature: String, content: String) {
  def sign = Services.auth.sign(content)

  def checkSignature = Services.auth.checkSignature(sign, signature)

  def wid = parent.wid.copy(section=Some(name))

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

/** a unique ID - it is less verbose than the WID - used in data modelling.
  *
  * also, having a wid means a page exists or existed
*/
case class UWID(cat: String, id:ObjectId, realm:Option[String]=None) {
  def findWid = {
    WikiIndex.withIndex(getRealm) { idx =>
      idx.find((_,_,x)=>x == id).map(_._2)
    } orElse Wikis(getRealm).findById(cat, id).map(_.wid)
  }
  def wid = findWid orElse Some(WID(cat, id.toString)) // used in too many places to refactor properly
  def nameOrId = wid.map(_.name).getOrElse(id.toString)
  lazy val grated     = grater[UWID].asDBObject(this)
  lazy val page = Wikis(getRealm).find(this)

  /** get the realm or the default */
  def getRealm = realm.getOrElse(Wikis.DFLT)

  /** withRealm - convienience builder */
  def r(r:String) = if(Wikis.RK == r) this else this.copy(realm=Some(r))
}

/** a wrapper for categories, since they can now have a realm */
case class CAT(cat: String, realm:Option[String]) { // don't give realm a None defaut, eh? see object.apply
  /** get the realm or the default */
  def getRealm = realm.getOrElse(Wikis.DFLT)
}

object CAT {
  def unapply (cat:String) : Option[CAT] = Some(apply(cat))
  def apply (cat:String) : CAT =
    if(cat.contains(".")) {
      val cs = cat.split("\\.")
      CAT(cs(1), Some(cs(0)))
    }
    else CAT(cat, None)
}

/** a wiki id, a pair of cat and name - can reference a wiki entry or a section of an entry
  *
  * format is parent/cat:name#section
  *
  * assumption is that when we link between wikis, we'll have wiki/parent/cat:name#section -
  */
case class WID(cat: String, name: String, parent: Option[ObjectId] = None, section: Option[String] = None, realm:Option[String]=None) {
  override def toString = "[[" + wpath + "]]" //cat + ":" + name + (section.map("#"+_).getOrElse("")) + parent.map(" of " + _.toString).getOrElse("")

  lazy val grated     = grater[WID].asDBObject(this)
  lazy val findParent = parent flatMap (p => Wikis(getRealm).find(p))
  lazy val parentWid  = parent flatMap (p => WikiIndex.withIndex(getRealm) { index => index.find { case (a, b, c) => c == p }.map(_._2) }) orElse (findParent map(_.wid))

  /** find the page for this, if any - respects the NOCATS */
  lazy val page = {
    if(cat.isEmpty) findId flatMap Wikis(getRealm).find // special for NOCATS
    else Wikis(getRealm).find(this)
   }

  /** get textual content, unprocessed, of this object, if found */
  def content = section.map{s=> page.flatMap(_.sections.find(_.name == s)).map(_.content) getOrElse s"[Section $s not found!]"} orElse page.map(_.content)

  /** withRealm - convienience builder */
  def r(r:String) = if(Wikis.RK == r) this else this.copy(realm=Some(r))

  /** find the ID for this page, if any - respects the NOCATS */
  def findId = {
    WikiIndex.withIndex(getRealm) { idx =>
      if(! cat.isEmpty)
	idx.get2(name, this)
      else {
	// try the nocats
	idx.get1k(name).filter(x=>WID.NOCATS.contains(x.cat)).headOption.flatMap(x=>idx.get2(name, x))
	//todo maybe forget this branch and enhance equals to look at nocats ?
      }
    } orElse Wikis.find(this).map(_._id)
  }

  def uwid = findId map {x=>UWID(cat, x, realm)}

  def cats = if(realm.exists(_ != Wikis.RK)) (realm.get + "." + cat) else cat

  /** format into nice url */
  def wpath: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 && !WID.NOCATS.contains(cat)) (cats + ":") else "") + name + (section.map("#" + _).getOrElse(""))
  //this one used for simple cats with /w/:realm
  def wpathnocats: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 && !WID.NOCATS.contains(cat)) (cat + ":") else "") + name + (section.map("#" + _).getOrElse(""))

  /** full categories allways */
  def wpathFull: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 ) (cats + ":") else "") + name + (section.map("#" + _).getOrElse(""))
  def formatted = this.copy(name=Wikis.formatName(this))
  def url: String = "http://" + Services.config.hostport + (realm.filter(_ != "rk").map(r=>s"/w/$r").getOrElse("")) + "/wiki/" + wpathnocats
  def urlRelative: String = (realm.filter(_ != "rk").map(r=>s"/w/$r").getOrElse("")) + "/wiki/" + wpathnocats
  def ahref: String = "<a href=\"" + url + "\">" + toString + "</a>"

  /** helper to get a label, if defined or the default provided */
  def label(id: String, alt: String) = WikiDomain(getRealm).labelFor(this, id).getOrElse(alt)
  def label(id: String) = WikiDomain(getRealm).labelFor(this, id).getOrElse(id)

  /** get the realm or the default */
  def getRealm = realm.getOrElse(Wikis.DFLT)
}

/** a special command wid, contains a command, what and wid - used in routes */
case class CMDWID(wpath: Option[String], wid: Option[WID], cmd: String, rest: String) {
  def hasGoodWid = wid.exists(w=>WID.UNKNOWN.cat != w.cat && WID.UNKNOWN.name != w.name )
}

object WID {
  /** do not require the category */
  private final val NOCATS = Array("Blog", "Post", "xSite")

  //cat:name#section$realm
  private val REGEX = """([^/:\]]*[:])?([^#|\]]+)(#[^|\]]+)?""".r

  /** @param a the list of wids from a path, parent to child */
  private def widFromSeg(a: Array[String]) = {
    val w = a.map { x =>
      x match {
	case REGEX(c, n, s) => WID(
	  (if (c == null) "" else c.replaceFirst("[^.]+\\.", "")).replaceFirst(":", ""),
	  n,
	  None,
	  Option(s).filter(_.length > 1).map(_.substring(1)),
	  if(c != null && c.contains(".")) Some(c.replaceFirst("\\..*", "")) else None)
	case _ => UNKNOWN
      }
    }
    val res = w.foldLeft[Option[WID]](None)((x, y) => Some(WID(y.cat, y.name, x.flatMap(_.findId), y.section, y.realm)))
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
      //todo if the name contains the sequence /debug this won't work - should check i.e. /debug$
      Array("/xp/", "/xpl/", "/tag/").collectFirst {
        case tag if path contains tag => splitIt (tag, path)
      } orElse
        Array("/rss.xml", "/debug").collectFirst {
        case tag if path endsWith tag => splitIt (tag, path)
      } orElse
        Some(CMDWID(Some(path), widFromSeg(path split "/"), "", ""))

      def splitIt (tag:String, path:String) = {
        val b = path split tag
        val a = b.head split "/"
        CMDWID(b.headOption, widFromSeg(a), tag.replaceAllLiterally("/", ""), b.tail.headOption.getOrElse(""))
      }

//	if (path contains "/xp/") {
//	  val b = path split "/xp/"
//	  val a = b.head split "/"
//	  Some(CMDWID(b.headOption, widFromSeg(a), "xp", b.tail.headOption.getOrElse("")))
//	} else if (path contains "/xpl/") {
//	  val b = path split "/xpl/"
//	  val a = b.head split "/"
//	  Some(CMDWID(b.headOption, widFromSeg(a), "xpl", b.tail.headOption.getOrElse("")))
//	} else if (path contains "/tag/") {
//	  val b = path split "/tag/"
//	  val a = b.head split "/"
//	  Some(CMDWID(b.headOption, widFromSeg(a), "tag", b.tail.headOption.getOrElse("")))
//	} else {
//	  val a = path split "/"
//	  Some(CMDWID(Some(path), widFromSeg(a), "", ""))
//	}
    }
  }

  val NONE = WID("?", "?")
  val UNKNOWN = WID("?", "?")
}

/** a link between two wikis */
@RTable
case class WikiLinkStaged(
  from: WID,
  to: WID,
  how: String,
  crDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) extends REntity[WikiLinkStaged] {
}

/** a link between two wikis */
@RTable
case class WikiLink(
  from: UWID,
  to: UWID,
  how: String,
  crDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) extends REntity[WikiLink] {

  /** the name of the link page, if there will be any */
  val wname = Array(from.cat, from.nameOrId, to.cat, to.nameOrId).mkString(":")

  def page = Wikis(from.getRealm).find("WikiLink", wname)
  def pageFrom = Wikis(from.getRealm).find(from)
  def pageTo = Wikis(to.getRealm).find(to)

  def isPrivate = List(pageFrom, page).flatMap(_ map (_.isPrivate)).exists(identity)
}

object ILink {
  def apply(wid: WID) = new ILink(wid, wid.name)
}

/** most information about a page */
case class ILink(wid: WID, label: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = Nil) {
  def href = Services.config.urlmap("/wiki/%s".format(wid.wpath))
  def format = Wikis.formatWikiLink(wid, wid.name, label, None)
}
