/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.model

import com.mongodb.casbah.Imports._
import com.novus.salat._
import org.joda.time.DateTime
import razie.audit.Audit
import razie.wiki.parser.WAST.SState
import razie.{AA, Log, cdebug}
import razie.db.RazSalatContext._
import razie.db._
import razie.wiki.Services
import razie.wiki.parser.WAST

import scala.collection.mutable.ListBuffer

/**
  * simple trait for a wiki
  */
trait WikiPage {
  def category: String
  def name: String
  def label: String
  def markup: String
  def content: String
  def by: ObjectId
  def tags: Seq[String]
  def realm:String
  def ver: Int
  def parent: Option[ObjectId]
  def props: Map[String, String]
  def crDtm: DateTime
  def updDtm: DateTime
  def _id: ObjectId

  def wid : WID
  def uwid : UWID
  def section (stype: String, name: String) : Option[WikiSection]
  def contentProps : Map[String,String]
}

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
  props: Map[String, String] = Map.empty, // properties - can be supplemented in the content
  likes: List[String]=List.empty,         // list of usernames that liked it
  dislikes: List[String]=List.empty,         // list of usernames that liked it
  likeCount: Int=0,      // list of usernames that liked it
  dislikeCount: Int=0,      // list of usernames that liked it
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends WikiPage {

  import WikiEntry._

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

  /** is this just a redirect?
    */
  def redirect: Option[String] = {
    val wikip2 = """(?s)\{\{redirect[ :]([^\]]*)\}\}.*"""
    val wikip2r = wikip2.r
    if (content.matches(wikip2)) {
      val wikip2r(url) = content
      Some(url)
    } else None
  }

  // what other pages I depend on
  var depys: List[UWID] = Nil

  // set during parsing and folding - false if page has any user-specific elements
  // any scripts or such will make this false
  // this is very pessimistic right now for safety issues: even a whiff of non-static content will turn this off
  var cacheable: Boolean = true

  /** todo should use this version instead of content - this resolves includes */
  def included : String = {
    val x = Wikis.preprocessIncludes(wid, markup, content, Some(this))
    x
  }

  def wid  = WID(category, name, parent, None, if(realm == Wikis.RK) None else Some(realm))
  def uwid = UWID(category, _id, if(realm == Wikis.RK) None else Some(realm))

  /** i should be conservative and default to rk. Note this doesn't check Config.urlcanon */
  def canonicalUrl =
    if (realm != Wikis.RK) {
      Wikis(realm).navTagFor(tags).map(x => s"http://www.racerkidz.com/wiki/${wid.wpath}") getOrElse s"http://www.racerkidz.com/wiki/${wid.wpath}"
    } else s"http://www.racerkidz.com/wiki/${wid.wpath}"

  def cloneContent(newcontent: String) = copy(content = newcontent)

  def cloneNewVer(label: String, markup: String, content: String, by: ObjectId, props: Map[String, String] = this.props) =
    copy(label=label, markup=markup, content=content, by=by, ver=ver + 1, props=props, updDtm=DateTime.now)

  def cloneProps(m: Map[String, String], sby: ObjectId) = copy(props = this.props ++ m)

  def withTags(s: Seq[String], sby: ObjectId) = copy(tags=s)

  def findParent = parent flatMap (p => Wikis(realm).find(p))

  def isReserved = props.get(PROP_RESERVED).exists(_ == "yes")
  def isDraft = props.contains("draft")

  def isPrivate = "User" == category || (props.exists(e => PROP_OWNER == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => PROP_OWNER == e._1 && id == e._2))
  def owner = props.get(PROP_OWNER).flatMap(s => WikiUsers.impl.findUserById(new ObjectId(s)))
  def ownerId = props.get(PROP_OWNER).map(s=> new ObjectId(s))

  // todo trying to avoid parsing it just to get the label
  def getLabel = if(content contains "label") contentProps.getOrElse("label", label) else label
  def getDescription = contentProps.getOrElse("meta.description", getFirstParagraph.mkString)
  def getFirstParagraph = content.lines.find(s => !s.trim.isEmpty && !".{".contains(s.trim.charAt(0)))
  def wordCount = content.count(_ == ' ')

  def visibility = props.get(PROP_VISIBILITY).getOrElse(Visibility.PUBLIC)
  def wvis = props.get(PROP_WVIS).getOrElse(visibility)

  def create = {
    // TODO optimize exists
    if (Wikis.find(wid).exists(_.realm == this.realm)) {
      Log.error("ERR_WIKI page exists " + wid)
      throw new IllegalStateException(s"page already exists: $category/$name")
    }

    Audit.logdbWithLink(
      if(wid.cat=="Note") AUDIT_NOTE_CREATED else AUDIT_WIKI_CREATED,
      wid.urlRelative,
      "BY " + (WikiUsers.impl.findUserById(this.by).map(_.userName).getOrElse(this.by.toString)) +
        " " + category + ":" + name)
    Wikis(realm).weTable(wid.cat) += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    Wikis(realm).index.create(this)
  }

  /** backup old version and update entry, update index */
  def update(newVer: WikiEntry, reason:Option[String] = None)(implicit txn:Txn=tx.auto) = {
    val uname = WikiUsers.impl.findUserById(newVer.by).map(_.userName).getOrElse(newVer.by.toString)
    if(uname != "Razie")
      Audit.logdbWithLink(
        if(wid.cat=="Note") AUDIT_NOTE_UPDATED else AUDIT_WIKI_UPDATED,
        newVer.wid.urlRelative,
        s"""BY $uname - $category : $name ver ${newVer.ver}""")
    if(!isDraft || !newVer.isDraft) WikiEntryOld(this, reason).create
    RUpdate.noAudit[WikiEntry](Wikis(realm).weTables(wid.cat), Map("_id" -> newVer._id), newVer)
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))

    // this is done async from WikiEvent. if sync here it will cause problems
//    Wikis(realm).index.update(this, newVer)
  }

  /** backup old version and update entry, update index */
  def delete(sby: String) (implicit txn:Txn) = {
    Audit.logdb(AUDIT_WIKI_DELETED, "BY " + sby + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this, Some ("deleted")).create
    WikiTrash("WikiEntry", this.grated, sby, txn.id).create
    val key = Map("realm" -> realm, "category" -> category, "name" -> name, "parent" -> parent)
    RDelete.apply (Wikis(realm).weTables(wid.cat), key)
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  /** reparsing the content - wiki sections are delimited by {{section:name}} */

  /** these are normal - all sections after include */
  lazy val sections = findSections(included, PATT_SEC) ::: findSections(included, PATT_TEM)

  /** these are when used as a template - template sections do not resolve include */
  lazy val templateSections = findSections(included, PATT_TEM) ::: findSections(content, PATT_TEM)

  // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
  //?s means DOTALL - multiline
  // format: {{stype[ :]name:signature}}content
  private final val PATT_SEC =
    """(?s)\{\{\.*(section|def|lambda|inline|dfiddle|dsl\.\w*)([: ])?([^:}]*)?(:)?([^}]*)?\}\}((?>.*?(?=\{\{/[^`])))\{\{/\.*(section|def|lambda|inline|dfiddle|dsl\.\w*)?\}\}""".r
  private final val PATT_TEM =
    """(?s)\{\{\.*(template)([: ])?([^ :}]*)?([: ])?([^}]*)?\}\}((?>.*?(?=\{\{/[^`])))\{\{/\.*(template)?\}\}""".r

  /** find the sections - used because for templating I don't want to reolves the includes */
  private def findSections (c:String, pat:scala.util.matching.Regex) = {
    // todo use the wiki parser later modifiers to load the sections, not a separate parser here
    // todo IMPORTANT - can't quite do that: these are used WHILE parsing other elements... see WikiDomParser.pmsg
    val PATT2 = pat

    val x = pat replaceSomeIn (c, { m =>
      None
    })

    (for (m <- pat.findAllIn(c).matchData) yield {
      val mm = PATT2.findFirstMatchIn(m.matched).get
      val signargs = mm.group(5).split("[: ]")
      val args = if(signargs.length>1) AA(signargs(1)).toMap else Map.empty[String,String]
      val sign = signargs(0)
      val ws = WikiSection(mm.source.toString, this, mm.group(1), mm.group(3), sign, mm.group(6), args)
      val ss = c.substring(0, m.start)
      val t = ss.lines.toList
      if(t.size > 0) {
        ws.line = t.size+1
        ws.col  = t.apply(t.size-1).length
      }
      ws
    }).toList
  }

  /** pattern for all sections requiring signing - (?s) means multi-line */
  val PATTSIGN = """(?s)\{\{(\.?)(template|def|lambda|inline):([^:}]*)(:REVIEW[^}]*)\}\}((?>.*?(?=\{\{/)))\{\{/(template|def|lambda|inline)?\}\}""".r //?s means DOTALL - multiline

  /** find a section */
  def section (stype: String, name: String) = sections.find(x => x.stype == stype && x.name == name)

  /** scripts are just a special section */
  lazy val scripts = sections.filter(x => "def" == x.stype || "lambda" == x.stype || "inline" == x.stype)

  /** pre processed form - parsed and graphed. No context is used when parsing - only when folding this AST, so you can reuse the AST */
  lazy val ast = Wikis.preprocess(this.wid, this.markup, Wikis.noBadWords(this.content), Some(this))

  /** AST folded with a context */
  var ipreprocessed : Option[(SState, Option[WikiUser])] = None;
  //todo don't hold the actual user, but someone that can get the user... prevents caching?

  // smart preprocess with user and stuff
  def preprocess(au:Option[WikiUser]) = {
    val t1 = System.currentTimeMillis
    val s = ast.fold(WAST.context(Some(this), au)) // fold the AST
    // add hardcoded attribute - these can be overriden by tags in content
    val res = WAST.SState(s.s,
      Map("category" -> category, "name" -> name, "label" -> label, "url" -> (wid.urlRelative),
        "id" -> _id.toString, "tags" -> tags.mkString(",")) ++ s.props,
      s.ilinks)
    ipreprocessed = Some(res, au)
    val t2 = System.currentTimeMillis
    cdebug << s"wikis.folded ${t2 - t1} millis for ${wid.name}"
    res
  }

  def preprocessed = ipreprocessed.map(_._1).getOrElse(preprocess(None))

  def grated = grater[WikiEntry].asDBObject(this)

  override def toString: String =
    grater[WikiEntry].asDBObject(this).toString

  /** tags collected during parsing of the content, with some static tags like url,label etc */
  def contentProps = preprocessed.props

  /** attributes are props perhaps overriden in content */
  def attr(name:String) : Option[String] =
    // optimized to not parse if it's not in content
    if(ipreprocessed.isDefined) contentProps.get(name).orElse(props.get(name))
    else if(content contains name) contentProps.get(name).orElse(props.get(name))
    else props.get(name)

  /** all the links from this page to others, based on parsed content */
  def ilinks = preprocessed.ilinks

  final val AUDIT_WIKI_CREATED = "WIKI_CREATED"
  final val AUDIT_WIKI_UPDATED = "WIKI_UPDATED"
  final val AUDIT_WIKI_DELETED = "WIKI_DELETED"
  final val AUDIT_NOTE_CREATED = "NOTE_CREATED"
  final val AUDIT_NOTE_UPDATED = "NOTE_UPDATED"

  /** field definitions as parsed
    * fields are rendered in WForm
    */
  var fields = new scala.collection.mutable.HashMap[String, FieldDef]()
  lazy val form = new WikiForm(this)
  def formRole = this.props.get(FormStatus.FORM_ROLE)
  def formState = this.props.get(FormStatus.FORM_STATE).orElse(form.formState)

  /** other parsing artifacts to be used by knowledgeable modules.
    * Parsers can put stuff in here. */
  //todo move the fields and form stuff here
  val cache = new scala.collection.mutable.HashMap[String, Any]()

  def linksFrom = RMany[WikiLink] ("from.id" -> this.uwid.id)
  def linksTo = RMany[WikiLink] ("to.id" -> this.uwid.id)
}

/** a form field definition */
case class FieldDef(name: String, value: String, attributes: Map[String, String]) {
  def withValue(x: String) = FieldDef(name, x, attributes)
}

/** a section inside a wiki page */
case class WikiSection(original:String, parent: WikiEntry, stype: String, name: String, signature: String, content: String, args:Map[String,String] = Map.empty) {
  var line : Int = -1
  var col  : Int = -1

  def sign = Services.auth.sign(content)

  def checkSignature(au:Option[WikiUser]) = Services.auth.checkSignature(sign, signature, au)

  def wid = parent.wid.copy(section=Some(name))

  override def toString = s"WikiSection(stype=$stype, name=$name, signature=$signature, args=$args, content=$content)"
}

object WikiEntry {
  final val PROP_VISIBILITY = "visibility"
  final val PROP_WVIS = "wvis"
  final val PROP_RESERVED = "reserved"
  final val PROP_OWNER: String = "owner"

  def grated(o: DBObject) = grater[WikiEntry].asObject(o)

}

/** old wiki entries - a copy of each older version is archived when udpated or deleted */
@RTable
case class WikiEntryOld(entry: WikiEntry, reason:Option[String], _id: ObjectId = new ObjectId()) {
  def create (implicit txn:Txn) = RCreate.noAudit[WikiEntryOld](this)
}


