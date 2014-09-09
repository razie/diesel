/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.casbah.Imports._
//import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
//import db.RTable
//import com.tristanhunt.knockoff.DefaultDiscounter.knockoff
//import com.tristanhunt.knockoff.DefaultDiscounter.toXHTML
//import razie.base.scriptingx.ScalaScript
//import razie.cout
import razie.Logging
import admin.VErrors
import admin.Validation
import db.RMany
import admin.Audit
import admin.Services
import db.RazMongo

/** a wiki */
class WikiInst (val realm:String) {
  val domain : WikiDomain = new WikiDomain (realm)
  val index : WikiIndex = new WikiIndex (realm)

  /** cache of categories - updated by the WikiIndex */
  lazy val cats = new collection.mutable.HashMap[String,WikiEntry]() ++
    (RMany[WikiEntry]("category" -> "Category") map (w=>(w.name,w)))

  def table = RazMongo(Wikis.TABLE_NAME)

  def foreach (f:DBObject => Unit) {table find(Map()) foreach f}
}

/** wiki factory and utils */
object Wikis extends Logging with Validation {
  final val PERSISTED = Array("Item", "Event", "Training", "Note", "Entry", "Form")

  lazy val TABLE_NAME = "WikiEntry"

  lazy val DFLT = "rk"

  // all wiki instances
  lazy val wikis = {
    val res = new collection.mutable.HashMap[String,WikiInst]()
    res.put (DFLT, new WikiInst(DFLT))
    res
  }

  def apply (realm:String = DFLT) = wikis(realm)
  def apply (wid:WID) = wikis(wid.getRealm)
  
  def weTable(cat: String) = if (PERSISTED contains cat) RazMongo("we"+cat) else table
  def weTables(cat: String) = if (PERSISTED contains cat) ("we"+cat) else TABLE_NAME
  def table (realm:String) = RazMongo(TABLE_NAME)
  def table = RazMongo(TABLE_NAME)

  def fromGrated[T <: AnyRef](o: DBObject)(implicit m: Manifest[T]) = grater[T](ctx, m).asObject(o)

  def pages(category: String) =
    table.m.find(Map("category" -> category)) map (grater[WikiEntry].asObject(_))

  def pageNames(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("name").toString)

  def pageLabels(category: String) =
    table.m.find(Map("category" -> category)) map (_.apply("label").toString)

  // TODO optimize - cache labels...
  def label(wid: WID):String = /*wid.page map (_.label) orElse*/
    apply(DFLT).index.label(wid.name) orElse (ifind(wid) flatMap (_.getAs[String]("label"))) getOrElse wid.name

  def label(wid: UWID):String = /*wid.page map (_.label) orElse*/
    wid.wid.map(x=>label(x)).getOrElse(wid.nameOrId)

  def findById(id: String) = find(new ObjectId(id))
  def findById(cat:String, id: String) =
    weTable(cat).findOne(Map("_id" -> new ObjectId(id))) map (grater[WikiEntry].asObject(_))

  def findById(cat:String, id: ObjectId) =
    weTable(cat).findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))

  // TODO optimize
  def find(id: ObjectId) =
    (table.findOne(Map("_id" -> id)) orElse (PERSISTED.find {cat=>
      weTable(cat).findOne(Map("_id" -> id)).isDefined
  } flatMap {s:String=>weTable(s).findOne(Map("_id" -> id))})) map (grater[WikiEntry].asObject(_))

  private def ifind(wid: WID) =
    if (wid.parent.isDefined)
      weTable(wid.cat).findOne(Map("category" -> wid.cat, "name" -> wid.name, "parent" -> wid.parent.get))
    else
      weTable(wid.cat).findOne(Map("category" -> wid.cat, "name" -> Wikis.formatName(wid.name)))

  def find(wid: WID): Option[WikiEntry] = ifind(wid) map (grater[WikiEntry].asObject(_))
  def find(uwid: UWID): Option[WikiEntry] = findById(uwid.cat, uwid.id)

  def find(category: String, name: String): Option[WikiEntry] = find(WID(category, name))

  /** find any topic with name - will look in PERSISTED tables as well until at least one found */
  def findAny(name: String) = {
    val w1 = table.find(Map("name" -> name)) map (grater[WikiEntry].asObject(_))
    if(w1.hasNext) w1
    else {
      var found:Option[DBObject]=None
      PERSISTED.find {cat=>
        if(found.isEmpty)
          found= weTable(cat).findOne(Map("name" -> name))
        found.isDefined
      }
      found map (grater[WikiEntry].asObject(_)) toIterator
    }
  }

  def findAnyOne(name: String) =
    table.findOne(Map("name" -> name)) map (grater[WikiEntry].asObject(_))

//  def linkFromName(s: String) = {
//    val a = s.split(":")
//    WikiLink(WID(a(0), a(1)), WID(a(2), a(3)), "?")
//  }

  /** cache of categories - updated by the WikiIndex */
  lazy val cats = new collection.mutable.HashMap[String,WikiEntry]() ++
    (RMany[WikiEntry]("category" -> "Category") map (w=>(w.name,w)))
    
  def categories = cats.values

  def category(cat: String) = cats.get(cat)

  def visibilityFor(cat: String): Seq[String] =
    cats.get(cat).flatMap(_.contentTags.get("visibility")).map(_.split(",").toSeq).getOrElse(Seq("Public"))

  def linksFrom(from: UWID) = RMany[WikiLink]("from" -> from.grated)

  def linksTo(to: UWID) = RMany[WikiLink]("to" -> to.grated)

  def linksFrom(from: UWID, role: String) =
    RMany[WikiLink]("from" -> from.grated, "how" -> role)

  def linksTo(to: UWID, role: String) =
    RMany[WikiLink]("to" -> to.grated, "how" -> role)

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

  /** format an even more complex name */
  def formatWikiLink(wid: WID, nicename: String, label: String, hover: Option[String] = None, rk: Boolean = false) = {
    val name = Wikis.formatName(wid.name)
    val title = hover.map("title=\"" + _ + "\"") getOrElse ("")

    val bigName = apply(wid.getRealm).index.getForLower(name.toLowerCase())
    if (bigName.isDefined || wid.cat.matches("User")) {
      var newwid = wid.copy(name=bigName.get)
      var u = Services.config.urlmap("/wiki/%s".format(newwid.formatted.wpath))

      if (rk && (u startsWith "/")) u = "http://" + Services.config.rk + u

      ("""<a href="%s" title="%s">%s</a>""".format(u, title, label),
        Some(ILink(newwid, label)))
    } else // hide it from google
    if (rk)
      ("""<a href="http://""" + Services.config.rk + """/wiki/%s" title="%s">%s<sup><b style="color:red">^</b></sup></a>""".format(wid.formatted.wpath, title, label),
        Some(ILink(wid, label)))
    else
      ("""<a href="/wikie/show/%s" title="%s">%s<sup><b style="color:red">++</b></sup></a>""".format(wid.wpath, hover.getOrElse("Missing page"), label),
        Some(ILink(wid, label)))
  }

  def shouldFlag(name: String, label: String, content: String): Option[String] = {
    val a = Array(name, label, content)

    if (a.exists(_.matches("(?i)^.*<(" + WikiParser.hnok + ")([^>]*)>"))) Some("WIKI_FORBIDDEN_HTML")
    else if (hasporn(content, softporn)) Some("WIKI_HAS_SOFTPO")
    else None
  }

  private def include(c2: String)(implicit errCollector: VErrors): Option[String] = {
    var done = false
    val res = try {
      val INCLUDE = """(?<!`)\[\[include:([^\]]*)\]\]""".r
      INCLUDE.replaceAllIn(c2, { m =>
        done = true
        (for (
          wid <- WID.fromPath(m.group(1)) orErr ("bad format for page");
          p <- wid.page orErr (s"page ${wid.wpath} not found")
        ) yield {
          if (wid.section.isDefined) {
            wid.section.flatMap(p.section("section", _)).map(_.content).getOrElse("[ERR section %s not found]".format(wid.wpath))
          } else {
            p.content
          }
        }).map(_.replaceAll("\\$", "\\\\\\$")).getOrElse("`[ERR Can't include $1 " + errCollector.mkString + "]`")
      })
    } catch {
      case s: Throwable => log("Error: ", s); "`[ERR Can't process an include]`"
    }
    if (done) Some(res) else None
  }

  /** this is the actual parser to use - combine your own and set it here in Global */
  var wparser : String => WikiParser.State = WikiParser.apply

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

      (for (
        s @ WikiParser.State(a0, tags, ilinks, decs) <- Some(wparser(c2))
      ) yield s) getOrElse WikiParser.State("")
    case TEXT => WikiParser.State(content.replaceAll("""\[\[([^]]*)\]\]""", """[[\(1\)]]"""))
    case _ => WikiParser.State("UNKNOWN MARKUP " + markup + " - " + content)
  }

  /** main formatting function */
  private def format1(wid: WID, markup: String, icontent: String, we: Option[WikiEntry] = None) = {
    val res = try {
      var content = we.map(_.preprocessed).getOrElse(preprocess(wid, markup, noporn(icontent))).s
      // TODO index noporn when saving/loading page, in the WikiIndex
      // TODO have a pre-processed and formatted page index I can use - for non-scripted pages, refreshed on save
      // run scripts
      val S_PAT = """`\{\{(call):([^#}]*)#([^}]*)\}\}`""".r

      content = S_PAT replaceSomeIn (content, { m =>
        try {
          val pageWithScripts = WID.fromPath(m group 2).flatMap(x => Wikis.find(x)).orElse(we)
          pageWithScripts.flatMap(_.scripts.find(_.name == (m group 3))).filter(_.checkSignature).map(s => runScript(s.content, we))
          //        Some("xx")
        } catch { case _: Throwable => Some("!?!") }
      })

      val XP_PAT = """`\{\{\{(xp[l]*):([^}]*)\}\}\}`""".r

      content = XP_PAT replaceSomeIn (content, { m =>
        try {
          we.map(x => runXp(m group 1, x, m group 2))
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
      case e @ (_: Throwable) => {
        Audit.logdbWithLink("ERR_FORMATTING", wid.ahref, "[[ERROR FORMATTING]]: " + e.toString)
        log("[[ERROR FORMATTING]]: " + icontent.length + e.toString + "\n"+e.getStackTraceString)
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
    //        else "TOO MANY to list"), None))
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
        !s.startsWith("www.coolscala.com")
        ))
        Some("""$1$2 title="External site"><i>$3</i><sup>&nbsp;<b style="color:darkred">^^</b></sup>$4""")
      else None
    })

    //    // modify external sites mapped to external URLs
    //    // TODO optimize - either this logic or a parent-based approach
    //    for (site <- Wikis.urlmap)
    //      res = res.replaceAll ("""<a +href="%s""".format(site._1), """<a href="%s""".format(site._2))

    res
  }

  private def runScript(s: String, page: Option[WikiEntry]) = {
    val up = razie.NoStaticS.get[model.WikiUser]
    val q = razie.NoStaticS.get[QueryParms]
    WikiScripster.impl.runScript(s, page, up, q.map(_.q.map(t => (t._1, t._2.mkString))).getOrElse(Map()))
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

/** encapsulates the knowledge to use the wiki-defined domain model */
class WikiDomain (realm:String) {

  //todo load it

  // get all zends as List (to, role)
  def gzEnds(aEnd: String) =
    (for (
      c <- Wikis.categories if (c.contentTags.get("roles:" + aEnd).isDefined);
      t <- c.contentTags.get("roles:" + aEnd).toList;
      r <- t.split(",")
    ) yield (c.name, r)).toList

//    Wikis.categories.filter(_.contentTags.get("roles:" + aEnd).isDefined).flatMap(c=>c.contentTags.get("roles:" + aEnd).get.split(",").toList.map(x=>(c,x)))

  def gaEnds(zEnd: String) =
    for (
      c <- Wikis.category(zEnd).toList;
      t <- c.contentTags if (t._1 startsWith "roles:");
      r <- t._2.split(",")
    ) yield (t._1.split(":")(1), r)

  def zEnds(aEnd: String, role: String) =
    Wikis.categories.filter(_.contentTags.get("roles:" + aEnd).map(_.split(",")).exists(_.contains(role) || role=="")).toList

  def aEnds(zEnd: String, role: String) =
    for (
      c <- Wikis.category(zEnd).toList;
      t <- c.contentTags if (t._2.split(",").contains(role) || role=="")
    ) yield t._1.split(":")(1)

  def needsOwner(cat: String) =
    Wikis.category(cat).flatMap(_.contentTags.get("roles:" + "User")).exists(_.split(",").contains("Owner"))

  def noAds(cat: String) =
    Wikis.category(cat).flatMap(_.contentTags.get("noAds")).isDefined

  def needsParent(cat: String) =
    Wikis.category(cat).exists(_.contentTags.exists { t =>
      t._1.startsWith("roles:") && t._2.split(",").contains("Parent")
    })

  def labelFor(wid: WID, action: String) = Wikis.category(wid.cat) flatMap (_.contentTags.get("label." + action))
}

object WikiDomain extends WikiDomain ("rk") {

}

