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
import play.mvc.Http.Request
import razie.base.ActionContext

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
case class WikiEntry(
  category: String,
  name: String,
  label: String,
  markup: String,
  content: String,
  by: ObjectId,
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

  def cloneContent(newcontent: String) = copy(content = newcontent)

  def cloneNewVer(label: String, markup: String, content: String, by: ObjectId, props: Map[String, String] = this.props) =
    WikiEntry(category, name, label, markup, content, by, ver + 1, parent, props, crDtm, DateTime.now, _id)

  def cloneParent(p: Option[ObjectId]) = copy(parent = p, updDtm = DateTime.now)

  def cloneProps(m: Map[String, String], sby: ObjectId) =
    WikiEntry(category, name, label, markup, content, sby, ver, parent, this.props ++ m, crDtm, DateTime.now, _id)

  def withTags(s: Seq[String], sby: ObjectId) =
    WikiEntry(category, name, label, markup, content, sby, ver, parent, this.props + ("tags" -> s.mkString(",")), crDtm, DateTime.now, _id)

  def findParent = parent flatMap (p => Wikis.find(p))

  def isReserved = props.get("reserved").exists(_ == "yes")

  def isPrivate = "User" == category || (props.exists(e => "owner" == e._1))
  def isOwner(id: String) = ("User" == category && name == id) || (props.exists(e => "owner" == e._1 && id == e._2))

  def create = {
    // TODO optimize exists
    if (Wikis.find(wid).isDefined) {
      Log.error("ERR_WIKI page exists " + wid)
      throw new IllegalStateException("page already exists: " + category + "/" + name)
    }

    Audit.logdb(AUDIT_WIKI_CREATED, "BY " + (Users.findUserById(this.by).map(_.userName).getOrElse(this.by.toString)) + " " + category + ":" + name, "\nCONTENT:\n" + this)
    Wikis.table += grater[WikiEntry].asDBObject(Audit.createnoaudit(this))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))
    WikiIndex.create(this)
  }

  def update(newVer: WikiEntry) = {
    Audit.logdb(AUDIT_WIKI_UPDATED, "BY " + (Users.findUserById(newVer.by).map(_.userName).getOrElse(newVer.by.toString)) + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    Wikis.table.m.update(key, grater[WikiEntry].asDBObject(Audit.updatenoaudit(newVer)))
    Wikis.shouldFlag(name, label, content).map(auditFlagged(_))

    WikiIndex.update(this, newVer)
  }

  def delete(sby: String) = {
    Audit.logdb(AUDIT_WIKI_DELETED, "BY " + sby + " " + category + ":" + name, "\nCONTENT:\n" + this)
    WikiEntryOld(this).create
    val key = Map("category" -> category, "name" -> name, "parent" -> parent)
    Wikis.table.m.remove(key)
    WikiIndex.delete(this)
  }

  def auditFlagged(f: String) { Log.audit(Audit.logdb(f, category + ":" + name)) }

  /** wiki sections are delimited by {{section:name}} */
  lazy val sections = {
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    val PATT1 = """(?s)\{\{(section|template|def|lambda):([^:}]*)(:)?([^}]*)?\}\}((?>.*?(?=\{\{/)))\{\{/(section|template|def|lambda)\}\}""".r //?s means DOTALL - multiline
    val PATT2 = PATT1

    (for (m <- PATT1.findAllIn(content)) yield {
      val mm = PATT2.findFirstMatchIn(m).get
      WikiSection(this, mm.group(1), mm.group(2), mm.group(4), mm.group(5))
    }).toList
  }

  val PATTSIGN = """(?s)\{\{(template|def|lambda):([^:}]*)(:REVIEW)\}\}((?>.*?(?=\{\{/)))\{\{/(section|template|def|lambda)\}\}""".r //?s means DOTALL - multiline

  def section(stype: String, name: String) = sections.find(x => x.stype == stype && x.name == name)

  /** scripts are just a special section */
  lazy val scripts = sections.filter(x => Array("def", "lambda").contains(x.stype))

  /** pre processed form - parsed and graphed */
  lazy val preprocessed = {
    val s = Wikis.preprocess(this.wid, this.markup, this.content)
    // add hardcoded attributes
    WikiParser.State(s.s, s.tags ++ Map("category" -> category, "name" -> name, "label" -> label, "url" -> (category + ":" + name)), s.ilinks)
  }

  def grated = grater[WikiEntry].asDBObject(this)

  override def toString: String =
    grater[WikiEntry].asDBObject(this).toString

  def contentTags = preprocessed.tags
  def ilinks = preprocessed.ilinks

  def tags = props.get("tags").map(_.split(",").toSeq).getOrElse(Seq())

  final val AUDIT_WIKI_CREATED = "WIKI_CREATED "
  final val AUDIT_WIKI_UPDATED = "WIKI_UPDATED "
  final val AUDIT_WIKI_DELETED = "WIKI_DELETED "

  def userWikis = model.Users.findUserLinksTo(wid).toList

}

case class WikiSection(parent: WikiEntry, stype: String, name: String, signature: String, content: String) {
  def sign = Enc apply Enc.hash(content)

  def checkSignature = sign == signature || ("ADMIN" == signature && razie.NoStaticS.get[model.User].exists(_.hasPerm(Perm.adminDb)))

  override def toString = "WikiSection(stype=%s,name=%s,signature=%s,content=%s)".format(stype, name, signature, content)
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
case class WID(cat: String, name: String, parent: Option[ObjectId] = None, section: Option[String] = None) {
  override def toString = "[[" + wpath + "]]" //cat + ":" + name + (section.map("#"+_).getOrElse("")) + parent.map(" of " + _.toString).getOrElse("")
  def grated = grater[WID].asDBObject(this)
  def findParent = parent flatMap (p => Wikis.find(p))

  lazy val parentWid = parent flatMap (p => WikiIndex.withIndex { index => index.find { case (a, b, c) => c == p }.map(_._2) })

  def page = Wikis.find(this)
  def findId = Wikis.find(this).map(_._id) // TODO optimize with cache lookup

  /** format into nice url */
  def wpath: String = findParent.map(_.wid.wpath + "/").getOrElse("") + (if (cat != null && cat.length > 0) (cat + ":") else "") + name + (section.map("#" + _).getOrElse(""))
  def formatted = WID(cat, Wikis.formatName(this), parent, section)
  
  /** helper to get a label, if defined or the default provided */
  def label (id:String, alt:String) = WikiDomain.labelFor(this, id).getOrElse(alt)
  def label (id:String) = WikiDomain.labelFor(this, id).getOrElse(id)
}

/** a special command wid, contains a command, what and wid - used in routes */
case class CMDWID(wpath:Option[String], wid:Option[WID], cmd: String, rest:String)

object WID {
  private val REGEX = """([^/:\]]*[:])?([^#|\]]+)(#[^|\]]+)?""".r

//  def apply (cat: String, name: String, parent: Option[ObjectId] = None, section: Option[String] = None) = 
//    new WID(cat, name, parent, section)
    
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
      if (path contains "/xp/") {
        val b = path split "/xp/"
        val a = b.head split "/"
        Some(CMDWID(b.headOption, widFromSeg(a), "xp", b.tail.headOption.getOrElse ("")))
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
case class WikiLink(from: WID, to: WID, how: String, _id: ObjectId = new ObjectId()) {
  import admin.M._

  def create = Mongo("WikiLink") += grater[WikiLink].asDBObject(Audit.create(this))

  val wname = Array(from.cat, from.name, to.cat, to.name).mkString(":")

  def page = Wikis.find("WikiLink", wname)
  def pageFrom = Wikis.find(from.cat, from.name)
  def pageTo = Wikis.find(to.cat, to.name)

  def isPrivate = List(pageFrom, page).flatMap(_ map (_.isPrivate)).exists(identity)

  def grated = grater[WikiLink].asDBObject(this)

  def delete = { Audit.delete(this); Mongo("WikiLink").m.remove(Map("_id" -> _id)) }
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

  // TODO cache
  def categories =
    table.find(Map("category" -> "Category")) map (grater[WikiEntry].asObject(_))

  // TODO cache
  def category(cat: String) =
    table.findOne(Map("category" -> "Category", "name" -> cat)) map (grater[WikiEntry].asObject(_))

  // TODO cache
  def visibilityFor(cat: String): Seq[String] =
    //    category(cat).map(_.props).get//.get("visibility")).map(_.split(",")).getOrElse (Array("Public"))
    category(cat).flatMap(_.contentTags.get("visibility")).map(_.split(",").toSeq).getOrElse(Seq("Public"))

  def linksFrom(from: WID) =
    Mongo("WikiLink").find(Map("from" -> from.grated)) map (grater[WikiLink].asObject(_))

  def linksTo(to: WID) =
    Mongo("WikiLink").find(Map("to" -> to.grated)) map (grater[WikiLink].asObject(_))

  def linksFrom(from: WID, role: String) =
    Mongo("WikiLink").find(Map("from" -> from.grated, "how" -> role)) map (grater[WikiLink].asObject(_))

  def linksTo(to: WID, role: String) =
    Mongo("WikiLink").find(Map("to" -> to.grated, "how" -> role)) map (grater[WikiLink].asObject(_))

  val MD = "md"
  val TEXT = "text"
  val markups = Array(MD, TEXT)

  import com.tristanhunt.knockoff.DefaultDiscounter._

  private def iformatName(name: String, pat: String, pat2: String = "") = name.replaceAll(pat, "_").replaceAll(pat2, "").replaceAll("_+", "_").replaceFirst("_$", "")

  /** format a simple name - try NOT to use this */
  //  def formatName(name: String): String = iformatName(name, """[ &?,;/:{}\[\]]""")

  /** these are the safe url characters. I also included ',which are confusing many sites */
  val SAFECHARS = """[^0-9a-zA-Z\$\-_\.()',]""" // DO NOT TOUCH THIS PATTERN!
  def formatName(name: String): String = iformatName(name, SAFECHARS, "") // DO NOT TOUCH THIS PATTERN!

  /** format a complex name cat:name */
  def formatName(wid: WID): String = if ("WikiLink" == wid.cat) iformatName(wid.name, """[ /{}\[\]]""") else formatName(wid.name)

  /** format an even more complex name */
  def formatWikiLink(wid: WID, nicename: String, label: String, hover: Option[String] = None, rk: Boolean = false) = {
    val name = Wikis.formatName(wid.name)
    val title = hover.map("title=\"" + _ + "\"") getOrElse ("")

    WikiIndex.withIndex { index =>
      if (index.idx.contains(name) || wid.cat.matches("User")) {
        var u = Config.urlmap("/wiki/%s".format(wid.formatted.wpath))

        if (rk && (u startsWith "/")) u = "http://" + Config.rk + u

        ("""<a href="%s" title="%s">%s</a>""".format(u, title, label),
          Some(ILink(wid, label)))
      } else // hide it from google
      if (rk)
        ("""<a href="http://""" + Config.rk + """/wiki/%s" title="%s">%s<sup><b style="color:red">^</b></sup></a>""".format(wid.formatted.wpath, title, label),
          Some(ILink(wid, label)))
      else
        ("""<a href="/wikie/show/%s" title="%s">%s<sup><b style="color:red">++</b></sup></a>""".format(wid.wpath, hover.getOrElse("Missing page"), label),
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

      val INCLUDE = """(?<!`)\[\[include:([^\]]*)\]\]""".r
      c2 = INCLUDE.replaceAllIn(c2, { m =>
        (for (
            wid <- WID.fromPath(m.group(1));
            p <- wid.page) yield {
          if(wid.section.isDefined) {
            println(p.sections)
            wid.section.flatMap(p.section("section", _)).map(_.content).getOrElse("[ERR section %s not found]".format(wid.wpath))
          } else
            p.content
        }).getOrElse("[ERR Can't include $1]")
      })

      (for (
        s @ WikiParser.State(a0, tags, ilinks) <- Some(WikiParser(c2))
      ) yield s) getOrElse WikiParser.State("")
    case TEXT => WikiParser.State(content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]"""))
    case _ => WikiParser.State("UNKNOWN MARKUP " + markup + " - " + content)
  }

  /** main formatting function */
  def format1(wid: WID, markup: String, icontent: String, we: Option[WikiEntry] = None) = {
    var res = try {
      var content = preprocess(wid, markup, noporn(icontent)).s

      // run scripts
      //      val S_PAT = """<code>\{\{(call):([^#}]*)#([^}]*)\}\}</code>""".r
      val S_PAT = """`\{\{(call):([^#}]*)#([^}]*)\}\}`""".r

      content = S_PAT replaceSomeIn (content, { m =>
        try {
          val pageWithScripts = WID.fromPath(m group 2).flatMap(x => model.Wikis.find(x)).orElse(we)
          pageWithScripts.flatMap(_.scripts.find(_.name == (m group 3))).filter(_.checkSignature).map(s => runScript(s.content, we))
          //        Some("xx")
        } catch { case _ => Some("!?!") }
      })

      markup match {
        case MD => toXHTML(knockoff(content)).toString
        case TEXT => content
        case _ => "UNKNOWN MARKUP " + markup + " - " + content
      }
    } catch {
      case e @ _ => {
        log("[[ERROR FORMATTING]]: " + e)
        "[[ERROR FORMATTING]] - sorry, dumb program here! The content is not lost: try editing this topic... also, please report this topic with the error and we'll fix it for you!"
      }
    }
    res
  }

  /** main formatting function */
  def format(wid: WID, markup: String, icontent: String, we: Option[WikiEntry] = None) = {
    var res = format1(wid, markup, icontent, we)

    // postformatting

    // mark the external links

    val A_PAT = """(<a +href="http://)([^>]*)>([^<]*)(</a>)""".r
    res = A_PAT replaceSomeIn (res, { m =>
      if (Option(m group 2) exists (!_.startsWith(Config.hostport)))
        Some("""$1$2 title="External site"><i>$3</i><sup>&nbsp;<b style="color:darkred">^^</b></sup>$4""")
      else None
    })

    //    // modify external sites mapped to external URLs
    //    // TODO optimize - either this logic or a parent-based approach
    //    for (site <- Wikis.urlmap)
    //      res = res.replaceAll ("""<a +href="%s""".format(site._1), """<a href="%s""".format(site._2))

    // run scripts

    //    val S_PAT = """<code>\{\{(call):([^#}]*)#([^}]*)\}\}</code>""".r
    //
    //    res = S_PAT replaceSomeIn (res, { m =>
    //      try {
    //        val pageWithScripts = WID.fromPath(m group 2).flatMap(x => model.Wikis.find(x)).orElse(we)
    //        pageWithScripts.flatMap(_.scripts.find(_.name == (m group 3))).filter(_.checkSignature).map(s => runScript(s.content, we))
    //        //        Some("xx")
    //      } catch { case _ => Some("!?!") }
    //    })

    res
  }

  
  private def runScript(s: String, page: Option[WikiEntry]) = {
    val up = razie.NoStaticS.get[model.User]
    val q  = razie.NoStaticS.get[model.QueryParms]
    WikiScripster.runScript(s, page, up, q.map(_.q.map(t=>(t._1, t._2.mkString))).getOrElse(Map()))
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

object ILink {
  def apply(wid: WID) = new ILink(wid, wid.name)
}

/** most information about a page */
case class ILink(wid: WID, label: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = Nil) {
  def href = Config.urlmap("/wiki/%s".format(wid.wpath))
  def format = Wikis.formatWikiLink(wid, wid.name, label, None)
}

/** all index related matters */
/** the index is (name, category, ID) */
object WikiIndex {
  case class PEntry(ilinks: List[ILink])
  private val parsed = scala.collection.mutable.Map[ObjectId, PEntry]()

  /** the index is (name, category, ID) */
  def withIndex[A](f: NewTripleIdx[String, WID, ObjectId] => A) = {
    synchronized {
      f(WikiIndex.actualIndex)
    }
  }

  // TODO sync reader/writer for udpates
  private lazy val actualIndex = {
    val t = new NewTripleIdx[String, WID, ObjectId]()
    Wikis.table.find(Map()).foreach { db =>
      val w = WID(db.as[String]("category"), db.as[String]("name"), if (db.containsField("parent")) Some(db.as[ObjectId]("parent")) else None)
      t.put(w.name, w, db.as[ObjectId]("_id"))
    }
    t
  }

  def up(we: WikiEntry) {
    parsed.put(we._id, PEntry(we.ilinks))
  }

  def graph(oid: ObjectId) = synchronized {
    parsed.getOrElseUpdate(oid, PEntry(Wikis.find(oid).toList.flatMap(_.ilinks)))
  }

  def update(oldVer: WikiEntry, newVer: WikiEntry) = withIndex { idx =>
    if (oldVer.category != newVer.category || oldVer.name != newVer.name) {
      idx.put(newVer.name, newVer.wid, oldVer._id)
      idx.remove2(oldVer.name, oldVer.wid)
    }
    up(newVer)
  }

  def create(we: WikiEntry) = withIndex { idx =>
    idx.put(we.name, we.wid, we._id)
    up(we)
  }

  def delete(we: WikiEntry) = withIndex { idx =>
    idx.remove2(we.name, we.wid)
    parsed.remove(we._id)
  }
}

/** encapsulates the knowledge to use the wiki-defined domain model */
object WikiDomain {

  def zEnds(aEnd: String, role: String) =
    Wikis.categories.filter(_.contentTags.get("roles:" + aEnd).map(_.split(",")).exists(_.contains(role))).toList

  def aEnds(zEnd: String, role: String) =
    for (
      c <- Wikis.category(zEnd).toList;
      t <- c.contentTags if (t._2.split(",").contains(role))
    ) yield t._1.split(":")(1)

  def needsOwner(cat: String) =
    model.Wikis.category(cat).flatMap(_.contentTags.get("roles:" + "User")).exists(_.split(",").contains("Owner"))

  def needsParent(cat: String) =
    model.Wikis.category(cat).exists(_.contentTags.exists { t =>
      t._1.startsWith("roles:") && t._2.split(",").contains("Parent")
    })

  def labelFor(wid: WID, action: String) = Wikis.category(wid.cat) flatMap (_.contentTags.get("label." + action))

}

object WikiScripster {
  var wikiCtx: Option[razie.base.scriptingx.NoBindSbtScalaContext] = None

  private def ctx = {
    if (!wikiCtx.isDefined) {
      wikiCtx = Some(new razie.base.scriptingx.NoBindSbtScalaContext())
    }
    wikiCtx.get
  }

  def runScript(s: String, page: Option[WikiEntry], user: Option[User], query:Map[String,String]) = synchronized {
    import razie.base.scriptingx.ScalaScriptContext;
    import razie.base.scriptingx._

    api.wix.page  = page
    api.wix.user  = user
    api.wix.query = query

    try {
      val res = (ScalaScript(s).interactive(ctx) getOrElse "?").toString
      ctx.clear // make sure there's nothing for hackers
      res
    } catch {
      case _ => { // any exceptions, get a new parser
        wikiCtx = None
        "?"
      }
    }
  }
}

case class QueryParms (q:Map[String,Seq[String]])
