/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import db.RazSalatContext._
import razie.Logging
import admin.VErrors
import admin.Validation
import db.RMany
import admin.Audit
import admin.Services
import db.RazMongo

/** the basic element of reaction: an app or module */
class Reactor (val realm:String) {
  val wiki:WikiInst = new WikiInst(realm)
  val domain : WikiDomain = new WikiDomain (realm, wiki)
}

/** a wiki */
class WikiInst (val realm:String) {
  val index : WikiIndex = new WikiIndex (realm)

  /** this is the actual parser to use - combine your own and set it here in Global */
  var wparserFactory : WikiParserFactory = TheWikiParserFactory

  val REALM = "realm" -> realm

  /** cache of categories - updated by the WikiIndex */
  lazy val cats = new collection.mutable.HashMap[String,WikiEntry]() ++
    (RMany[WikiEntry](REALM, "category" -> "Category") map (w=>(w.name,w)))

  def weTable(cat: String) = Wikis.TABLE_NAMES.get(cat).map(x=>RazMongo(x)).getOrElse(if (Wikis.PERSISTED contains cat) RazMongo("we"+cat) else table)
  def weTables(cat: String) = Wikis.TABLE_NAMES.getOrElse(cat, if (Wikis.PERSISTED contains cat) ("we"+cat) else Wikis.TABLE_NAME)
  def table (realm:String) = RazMongo(Wikis.TABLE_NAME)
  def table = RazMongo(Wikis.TABLE_NAME)

  def foreach (f:DBObject => Unit) {table find(Map(REALM)) foreach f} // todo test

  // ================== methods from Wikis

  def pages(category: String) =
    weTable(category).m.find(Map(REALM, "category" -> category)) map (grater[WikiEntry].asObject(_))

  def pageNames(category: String) =
    weTable(category).find(Map(REALM, "category" -> category)) map (_.apply("name").toString)

  def pageLabels(category: String) =
    table.m.find(Map(REALM, "category" -> category)) map (_.apply("label").toString)

  // TODO optimize - cache labels...
  def label(wid: WID):String = /*wid.page map (_.label) orElse*/
    index.label(wid.name) orElse (ifind(wid) flatMap (_.getAs[String]("label"))) getOrElse wid.name

  def label(wid: UWID):String = /*wid.page map (_.label) orElse*/
    wid.wid.map(x=>label(x)).getOrElse(wid.nameOrId)

  private def ifind(wid: WID) = {
    wid.parent.map {p=>
      weTable(wid.cat).findOne(Map(REALM, "category" -> wid.cat, "name" -> wid.name, "parent" -> p))
    } getOrElse {
      if("Reactor" == wid.cat) // reactors can be accessed both from their realm and main
	weTable(wid.cat).findOne(Map("realm"->"rk", "category" -> wid.cat, "name" -> Wikis.formatName(wid.name)))
     else
	weTable(wid.cat).findOne(Map(REALM, "category" -> wid.cat, "name" -> Wikis.formatName(wid.name)))
    }
  }

  // TODO find by ID is bad, no - how to make it work across wikis ?
  def findById(id: String) = find(new ObjectId(id))
  // TODO optimize
  def find(id: ObjectId) =
    (table.findOne(Map("_id" -> id)) orElse (Wikis.PERSISTED.find {cat=>
      weTable(cat).findOne(Map("_id" -> id)).isDefined
    } flatMap {s:String=>weTable(s).findOne(Map("_id" -> id))})) map (grater[WikiEntry].asObject(_))

  def findById(cat:String, id: String):Option[WikiEntry] = findById(cat, new ObjectId(id))

  def findById(cat:String, id: ObjectId): Option[WikiEntry] =
    weTable(cat).findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))

  def find(wid: WID): Option[WikiEntry] =
    if(wid.cat.isEmpty && wid.parent.isEmpty) {
      // some categories are allowed without cat if there's just one of them by name
      val wl = findAny(wid.name).filter(we=>Array("Blog", "Post").contains(we.wid.cat)).toList
      if(wl.size == 1) Some(wl.head)
      else {
	val wll = wl.filter(_.wid.getRealm == "rk")
	if(wll.size == 1) Some(wll.head)
	else None // don't want to randomly find what others define with same name...
	//todo if someone else defines a blog/forum with same name, it will not find mine anymore - so MUST use REALMS
      }
    } else
      ifind(wid) map (grater[WikiEntry].asObject(_))

  def find(uwid: UWID): Option[WikiEntry] = findById(uwid.cat, uwid.id)

  def find(category: String, name: String): Option[WikiEntry] = find(WID(category, name))

  /** find any topic with name - will look in PERSISTED tables as well until at least one found */
  def findAny(name: String) = {
    val w1 = table.find(Map(REALM, "name" -> name)) map (grater[WikiEntry].asObject(_))
    if(w1.hasNext) w1
    else {
      var found:Option[DBObject]=None
      Wikis.PERSISTED.find {cat=>
	if(found.isEmpty)
	  found= weTable(cat).findOne(Map(REALM, "name" -> name))
	found.isDefined
      }
      found map (grater[WikiEntry].asObject(_)) toIterator
    }
  }

  def findAnyOne(name: String) =
    table.findOne(Map(REALM, "name" -> name)) map (grater[WikiEntry].asObject(_))

  def categories = cats.values
  def category(cat: String) = cats.get(cat)
  def visibilityFor(cat: String): Seq[String] =
    cats.get(cat).flatMap(_.contentTags.get("visibility")).map(_.split(",").toSeq).getOrElse(Seq("Public"))
}

/** the default reactor, the main wiki */
object RkReactor extends model.Reactor (Wikis.RK) {
  override val wiki : WikiInst = RkWikiInst
  override val domain : WikiDomain = new WikiDomain(Wikis.RK, RkWikiInst)
}

/** a wiki */
object RkWikiInst extends model.WikiInst(Wikis.RK) {
}

object Reactors {
  final val RK = WikiConfig.RK
  final val NOTES = WikiConfig.NOTES

  //todo - scale... now all realms currently loaded in this node
  lazy val reactors = {
    val res = new collection.mutable.HashMap[String,Reactor]()
    res.put (Wikis.RK, RkReactor)
    res.put (NOTES, new Reactor(NOTES))
    RkReactor.wiki.pages("Reactor").filter(x=> !(Array(RK, NOTES) contains x.name)).foreach {w=>
      res.put (w.name, new Reactor(w.name))
    }

    res
  }

  def add (realm:String): Unit = {
    assert(! reactors.contains(realm))
    reactors.put(realm, new Reactor(realm))
  }

  def apply (realm:String = Wikis.RK) = reactors.getOrElse(realm, RkReactor) // using RK as a fallback
}

/** wiki factory and utils */
object Wikis extends Logging with Validation {
  final val PERSISTED = Array("Item", "Event", "Training", "Note", "Entry", "Form",
    "DRReactor", "DRElement", "DRDomain")

  final val TABLE_NAME = "WikiEntry"
  final val TABLE_NAMES = Map("DRReactor" -> "weDR", "DRElement" -> "weDR", "DRDomain" -> "weDR")

  final val RK = "rk"
  final val DFLT = RK // todo replace with RK

  def apply (realm:String = RK) = Reactors(realm).wiki
  def rk = Reactors(RK).wiki
//  def apply (wid:WID) = Reactors(wid.getRealm).wiki

  def weTable(cat: String) = TABLE_NAMES.get(cat).map(x=>RazMongo(x)).getOrElse(if (PERSISTED contains cat) RazMongo("we"+cat) else table)
  def weTables(cat: String) = TABLE_NAMES.getOrElse(cat, if (PERSISTED contains cat) ("we"+cat) else TABLE_NAME)
  def table (realm:String) = RazMongo(TABLE_NAME)
  def table = RazMongo(TABLE_NAME)

  def fromGrated[T <: AnyRef](o: DBObject)(implicit m: Manifest[T]) = grater[T](ctx, m).asObject(o)

//  def linkFromName(s: String) = {
//    val a = s.split(":")
//    WikiLink(WID(a(0), a(1)), WID(a(2), a(3)), "?")
//  }

  // TODO refactor convenience
  def find(wid: WID): Option[WikiEntry] = apply(wid.getRealm).find(wid)

  // TODO find by ID is bad, no - how to make it work across wikis ?
  /** @deprecated optimize with realm */
  def findById(id: String) = find(new ObjectId(id))
  /** @deprecated optimize with realm */
  def find(id: ObjectId) =
    Reactors.reactors.foldLeft(None.asInstanceOf[Option[WikiEntry]])((a,b) => a orElse b._2.wiki.find(id))
  /** @deprecated optimize with realm */
  def findById(cat:String, id: String):Option[WikiEntry] = findById(cat, new ObjectId(id))
  /** @deprecated optimize with realm */
  def findById(cat:String, id: ObjectId): Option[WikiEntry] =
    Reactors.reactors.foldLeft(None.asInstanceOf[Option[WikiEntry]])((a,b) => a orElse b._2.wiki.findById(cat, id))

  /** @deprecated use realm */
  def category(cat: String) =
    if(cat.contains(".")) {
      val cs = cat.split("\\.")
      apply(cs(0)).category(cs(1))
    }
    else rk.category(cat)

  /** @deprecated use realm */
  def visibilityFor(cat: String): Seq[String] = rk.visibilityFor(cat)

  def linksFrom(from: UWID) = RMany[WikiLink]("from" -> from.grated)

  def linksTo(to: UWID) = RMany[WikiLink]("to" -> to.grated)

  def childrenOf(parent: UWID) =
    RMany[WikiLink]("to" -> parent.grated, "how" -> "Child").map(_.from)

  def linksFrom(from: UWID, role: String) =
    RMany[WikiLink]("from" -> from.grated, "how" -> role)

  def linksTo(to: UWID, role: String) =
    RMany[WikiLink]("to" -> to.grated, "how" -> role)


  // leave these vvvvvvvvvvvvvvvvvvvvvvvvvv

  def label(wid: WID):String = /*wid.page map (_.label) orElse*/
    apply(wid.getRealm).label(wid)

  def label(wid: UWID):String = /*wid.page map (_.label) orElse*/
    wid.wid.map(x=>label(x)).getOrElse(wid.nameOrId)

  // leave these ^^^^^^^^^^^^^^^^^^^^^^^^^^

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
  def formatName(wid: WID): String =
    if ("WikiLink" == wid.cat)
      iformatName(wid.name, """[ /{}\[\]]""")
    else
      formatName(wid.name)

  /** format an even more complex name
    * @param rk force links back to RK main or leave them
    */
  def formatWikiLink(wid: WID, nicename: String, label: String, hover: Option[String] = None, rk: Boolean = false) = {
    val name = Wikis.formatName(wid.name)
    val title = hover.map("title=\"" + _ + "\"") getOrElse ("")

    val bigName = apply(wid.getRealm).index.getForLower(name.toLowerCase())
    if (bigName.isDefined || wid.cat.matches("User")) {
      var newwid = wid.copy(name=bigName.get)
      var u = Services.config.urlmap(newwid.formatted.urlRelative)

      if (rk && (u startsWith "/")) u = "http://" + Services.config.rk + u

      ("""<a href="%s" title="%s">%s</a>""".format(u, title, label),
	Some(ILink(newwid, label)))
    } else if (rk)
      // hide it from google
      (s"""<a href="http://${Services.config.rk}${wid.formatted.urlRelative}" title="$title">$label<sup><b style="color:red">^</b></sup></a>""" ,
	Some(ILink(wid, label)))
    else
      (s"""<a href="/we/${wid.getRealm}/show/${wid.wpath}" title="%s">$label<sup><b style="color:red">++</b></sup></a>""".format
	(hover.getOrElse("Missing page")),
	Some(ILink(wid, label)))
  }

  def shouldFlag(name: String, label: String, content: String): Option[String] = {
    val a = Array(name, label, content)

    if (a.exists(_.matches("(?i)^.*<(" + ParserSettings.hnok + ")([^>]*)>"))) Some("WIKI_FORBIDDEN_HTML")
    else if (hasporn(content, softporn)) Some("WIKI_HAS_SOFTPO")
    else None
  }

  private def include(c2: String)(implicit errCollector: VErrors): Option[String] = {
    var done = false
    val res = try {
      val INCLUDE = """(?<!`)\[\[include:([^\]]*)\]\]""".r
      val res1 = INCLUDE.replaceAllIn(c2, { m =>
	val content = for (
	  wid <- WID.fromPath(m.group(1)) orErr ("bad format for page");
	  c <- wid.content orErr s"content for ${wid.wpath} not found"
	) yield c

	done = true
	//regexp uses $ as a substitution
	content.map(_.replaceAll("\\$", "\\\\\\$")).getOrElse("`[ERR Can't include $1 " + errCollector.mkString + "]`")
      })

      val TEMPLATE = """(?<!`)\[\[template:([^\]]*)\]\]""".r
      TEMPLATE.replaceAllIn(res1, { m =>
	done = true
	//todo this is parse-ahead, maybe i can make it lazy?
	val parms = WikiForm.parseFormData(c2)
	val content = template (m.group(1), Map()++parms)
	//regexp uses $ as a substitution
	content.replaceAll("\\$", "\\\\\\$")
      })
    } catch {
      case s: Throwable => log("Error: ", s); "`[ERR Can't process an include]`"
    }
    if (done) Some(res) else None
  }

  // TODO better escaping of all url chars in wiki name
  def preprocess(wid: WID, markup: String, content: String) = markup match {
    case MD =>
      implicit val errCollector = new VErrors()

      // replace urls with MD markup:
      var c2 = content.replaceAll("""(\s|^)(https?://[^\s]+)""", "$1[$2]")

      if (c2 contains "[[./")
	c2 = content.replaceAll("""\[\[\./""", """[[%s/""".format(wid.cat + ":" + wid.name)) // child topics
      if (c2 contains "[[../")
	c2 = c2.replaceAll("""\[\[\../""", """[[%s/""".format(wid.parentWid.map(wp => wp.cat + ":" + wp.name).getOrElse("?"))) // siblings topics

      // TODO stupid - 3 levels of include...
      include(c2).map { c2 = _ }.flatMap { x =>
	include(c2).map { c2 = _ }.flatMap { x =>
	  include(c2).map { c2 = _ }
	}
      }

      apply(wid.getRealm).wparserFactory.mk(wid.getRealm) apply c2
//	(for (
//	  s @ WikiParser.SState(a0, tags, ilinks, decs) <- Some(wparser(c2))
//	) yield s) getOrElse WikiParser.SState("")
    case TEXT => WikiParser.SState(content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]"""))
    case _ => WikiParser.SState("UNKNOWN MARKUP " + markup + " - " + content)
  }

  /** main formatting function
    *
    * @param icontent to override the contents of the page
    */
  private def format1(wid: WID, markup: String, icontent: String, we: Option[WikiEntry] = None) = {
    val res = try {
      var content =
	if(icontent == null || icontent.isEmpty)
	  we.map(_.preprocessed).getOrElse(preprocess(wid, markup, noporn(icontent))).fold(we).s
	else
	  preprocess(wid, markup, noporn(icontent)).fold(we).s

      // TODO index noporn when saving/loading page, in the WikiIndex
      // TODO have a pre-processed and formatted page index I can use - for non-scripted pages, refreshed on save
      // run scripts
      val S_PAT = """`\{\{(call):([^#}]*)#([^}]*)\}\}`""".r

      content = S_PAT replaceSomeIn (content, { m =>
	try {
	  val pageWithScripts = WID.fromPath(m group 2).flatMap(x => Wikis(wid.getRealm).find(x)).orElse(we)
	  pageWithScripts.flatMap(_.scripts.find(_.name == (m group 3))).filter(_.checkSignature).map(s => runScript(s.content, we))
	  //	    Some("xx")
	} catch { case _: Throwable => Some("!?!") }
      })

      // todo move to an AST approach of states that are folded here instead of sequential replaces
      val XP_PAT = """`\{\{\{(xp[l]*):([^}]*)\}\}\}`""".r

      content = XP_PAT replaceSomeIn (content, { m =>
	try {
	  we.map(x => runXp(m group 1, x, m group 2))
	} catch { case _: Throwable => Some("!?!") }
      })

      val TAG_PAT = """`\{\{(tag)[: ]([^}]*)\}\}`""".r

      content = TAG_PAT replaceSomeIn (content, { m =>
	try {
	  Some(controllers.Wiki.hrefTag(wid, m group 2, m group 2))
	} catch { case _: Throwable => Some("!?!") }
      })

      // for forms
      we.map { x => content = new WForm(x).formatFields(content) }

      markup match {
	case MD => toXHTML(knockoff(content)).toString
	case TEXT => content
	case _ => "UNKNOWN MARKUP " + markup + " - " + content
      }
    } catch {
      case e : Throwable => {
	Audit.logdbWithLink("ERR_FORMATTING", wid.ahref, "[[ERROR FORMATTING]]: " + e.toString)
	log("[[ERROR FORMATTING]]: " + icontent.length + e.toString + "\n"+e.getStackTraceString)
	if(admin.Config.isLocalhost) throw e
	"[[ERROR FORMATTING]] - sorry, dumb program here! The content is not lost: try editing this topic... also, please report this topic with the error and we'll fix it for you!"
      }
    }
    res
  }

  private def runXp(what: String, w: WikiEntry, path: String) = {
    var root = new razie.Snakk.Wrapper(new WikiWrapper(w.wid), WikiXpSolver)
    var xpath = "*/" + path // TODO why am I doing this?

    if (path startsWith "root(") {
      val parser = """root\(([^:]*):([^:)/]*)\)/(.*)""".r //\[[@]*(\w+)[ \t]*([=!~]+)[ \t]*[']*([^']*)[']*\]""".r
      val parser(cat, name, p) = path
      root = new razie.Snakk.Wrapper(new WikiWrapper(WID(cat, name)), WikiXpSolver)
      xpath = "*/" + p
    }

    val res: List[_] =
      if (razie.GPath(xpath).isAttr) (root xpla xpath).filter(_.length > 0) // sometimes attributes come as zero value?
      else {
	(root xpl xpath).collect {
	  case ww: WikiWrapper => Wikis.formatWikiLink(ww.wid, ww.wid.name, ww.page.map(_.label).getOrElse(ww.wid.name))._1
	}
      }

    println("XP:" + res.mkString)

    what match {
      case "xp" => res.headOption.getOrElse("?").toString
      case "xpl" => "<ul>" + res.map { x: Any => "<li>" + x.toString + "</li>" }.mkString + "</ul>"
    }
    //	      else "TOO MANY to list"), None))
  }

  // scaled down formatting of jsut some content
  def sformat(content: String, markup:String="md") =
    format (WID("1","2"), markup, content)

  /** main formatting function */
  def format(wid: WID, markup: String, icontent: String, we: Option[WikiEntry] = None) = {
    var res = format1(wid, markup, icontent, we)

    // mark the external links
    val A_PAT = """(<a +href="http://)([^>]*)>([^<]*)(</a>)""".r
    res = A_PAT replaceSomeIn (res, { m =>
      if (Option(m group 2) exists (s=> !s.startsWith(Services.config.hostport)  &&
	//todo make these configurable
	!s.startsWith("www.racerkidz.com") &&
	!s.startsWith("www.enduroschool.com") &&
	!s.startsWith("www.nofolders.net") &&
	!s.startsWith("www.askicoach.com") &&
	!s.startsWith("www.dieselreactor.net") &&
	!s.startsWith("www.coolscala.com")
	))
	Some("""$1$2 title="External site"><i>$3</i><sup>&nbsp;<b style="color:darkred">^^</b></sup>$4""")
      else None
    })

    //	  // modify external sites mapped to external URLs
    //	  // TODO optimize - either this logic or a parent-based approach
    //	  for (site <- Wikis.urlmap)
    //	    res = res.replaceAll ("""<a +href="%s""".format(site._1), """<a href="%s""".format(site._2))

    res
  }

  private def runScript(s: String, page: Option[WikiEntry]) = {
    val up = razie.NoStaticS.get[WikiUser]
    val q = razie.NoStaticS.get[QueryParms]
    WikiScripster.impl.runScript(s, page, up, q.map(_.q.map(t => (t._1, t._2.mkString))).getOrElse(Map()))
  }

  /** format content from a template, given some parms */
  def template(wpath: String, parms:Map[String,String]) = {
    (for (wid <- WID.fromPath(wpath);
	  c <- wid.content
    ) yield {
      parms.foldLeft(c)((a,b)=>a.replaceAll("\\$\\{"+b._1+"\\}", b._2))
    }) getOrElse (
      "No content template for: " + wpath + "\n\nAttributes:\n\n" + parms.map{t=>s"* ${t._1} = ${t._2}\n"}.mkString
      )
  }

  def noporn(s: String) = porn.foldLeft(s)((x, y) => x.replaceAll("""\b%s\b""".format(y), "BLIP"))

  def hasporn(s: String, what: Array[String] = porn): Boolean = s.toLowerCase.split("""\w""").exists(what.contains(_))

  def flag(we: WikiEntry) { flag(we.wid) }

  def flag(wid: WID, reason: String = "?") {
    Audit.logdb("WIKI_FLAGGED", reason, wid.toString)
  }

  final val porn = Array("porn", "fuck")

  final val softporn = Array("tit", "breast", "ass", "dick")

  def updateUserName(uold: String, unew: String) = {
    // TODO1 optimize with find()
    // tODO2 rename references
    RazMongo.withDb(RazMongo("WikiEntry").m) { t =>
      for (u <- t if ("User" == u.get("category") && uold == u.get("name"))) {
	u.put("name", unew)
	t.save(u)
      }
    }
    RazMongo.withDb(RazMongo("WikiEntryOld").m) { t =>
      for (u <- t if ("User" == u.get("category") && uold == u.get("name"))) {
	u.put("name", unew)
	t.save(u)
      }
    }
  }

}


