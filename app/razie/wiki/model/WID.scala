/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.model

import salat._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import model.CMDWID
import org.bson.types.ObjectId
import razie.base.data.TripleIdx
import razie.db.RazSalatContext._
import razie.diesel.dom.DieselAssets
import razie.hosting.WikiReactors
import razie.tconf.TSpecRef
import razie.wiki.Services

/** a cluster node id, type-safe marker */
case class DCNode (name:String) {
  override def toString = name
}

/**
  * a wiki id, a pair of cat and name - can reference a wiki entry or a section of an entry
  *
  * THIS IS how topics are referenced everywhere in code. In DB they are reference with UWID
  *
  * format is parent/realm.cat:name#section
  *
  * with node hint: format is parent/(world,node)realm.cat:name#section
  *
  * assumption is that when we link between wikis, we'll have wiki/parent/cat:name#section -
  *
  * NOTE: equals does not look at parent !!!
  *
  * @param cat category
  * @param name the id
  * @param parent optional parent ref
  * @param section optional section inside this ref
  * @param realm optional realm
  * @param sourceUrl not persisted/read, just temporary
  */
case class WID(
  cat: String,
  name: String,
  parent: Option[ObjectId] = None,
  section: Option[String] = None,
  realm: Option[String] = None,
  @transient sourceUrl: Option[String] = None) { // todo what is sourceUrl for?

  override def toString = "[[" + wpath + "]]"

  lazy val grated = grater[WID].asDBObject(this)
  lazy val findParent = parent flatMap (p => Wikis(getRealm).find(p))
  lazy val parentWid = parent flatMap (p => WikiIndex.withIndex(getRealm)
  { index => index.find { case (a, b, c) => c == p }.map(_._2) }) orElse (findParent map (_.wid))

  /** clone with new sourceUrl */
  def withSourceUrl(s: String) = this.copy(sourceUrl = Some(s))

  /** find a parent of the given category */
  def parentOf(category: String => Boolean) = {
    def f(p: Option[WID]) = if (p.isEmpty) None else p.filter(x => category(x.cat)).orElse(p.flatMap(_.parentWid))

    f(parentWid)
  }

  private val _cachedPage : AtomicReference[Option[Option[WikiEntry]]] = new AtomicReference[Option[Option[WikiEntry]]](None)

  def hasCachedPage = _cachedPage.get().nonEmpty

  def resetCachedPage(newp:Option[WikiEntry]) = {_cachedPage.set(Some(newp)); this}
  def withCachedPage(newp:WikiEntry) = {_cachedPage.set(Some(Some(newp))); this}

  /** find the page for this, if any - respects the NOCATS */
  def page : Option[WikiEntry] = {
    if(hasCachedPage) _cachedPage.get().get
    else {
      val w = if (Services.config.cacheWikis) {
        WikiCache.getEntry(this.wpathFullNoSection + ".page").map { x => x
        }
      } else None

      w.orElse {
        val ret = findPageNocache
        _cachedPage.compareAndSet(None, Some(ret))
        // the page may not be processed in the context of a user, so we can't cache it
        ret
      }
    }
  }

  def find = page

  /** find the page without using caches, if any - respects the NOCATS */
  def findPageNocache : Option[WikiEntry] = {
      val ret = {
        if (cat.isEmpty)
          findId flatMap Wikis(getRealm).find // special for NOCATS
        else
          Wikis(getRealm).find(this)
      }

      ret
  }

  def isEmpty = cat == "?" && name == "?" || cat == "-" && name == "-"

  /** should this entry be indexed in memory */
  def shouldIndex = !(Wikis.PERSISTED contains cat)

  /** find the full section, if one is referenced */
  def findSection = section.flatMap { s => page.flatMap(_.sections.find(_.name == s)) }

  /** get textual content, unprocessed, of this object, if found */
  def content = section.map { s =>
    // allow that section contains signature
    val sar = s.split(":", 2)

    page.flatMap(p =>
      p.sections.find(t => t.name == s && (sar.size < 2 || t.signature == sar.last)) orElse
          p.templateSections.find(t => t.name == s && (sar.size < 2 || t.signature == sar.last))).map(
      _.content) getOrElse
        s"`[Section $s not found in $toString!]`"
  } orElse {
    page.map(_.content)
  }

  /** withRealm - convienience builder. Note that you can't override a category prefix */
  def r(r: String) =
    if (CAT.unapply(cat).flatMap(_.realm).isDefined || r.length <= 0) this
    else this.copy(realm = Some(r))
  //  def r(r:String) = if(Wikis.DFLT == r || CAT.unapply(cat).flatMap(_.realm).isDefined) this else this.copy(realm
  //  = Some(r))

  /** withRealm - dn't overwrite realm if set already */
  def defaultRealmTo(r: String) =
    if (realm.isDefined) this
    else this.r(r)

  /** withRealm - dn't overwrite realm if set already or if you pass in empty or None */
  def defaultRealmTo(r: Option[String]) =
    if (realm.isDefined || r.isEmpty || r.exists(_.length == 0)) this
    else this.r(r.get)

  /** if wid has no realm, should get the realm or the default - note taht the CAT prefix rules */
  def getRealm = realm orElse CAT.unapply(cat).flatMap(_.realm) getOrElse Wikis.DFLT

  /** find the ID for this page, if any - respects the NOCATS */
  def findId =
    if (ObjectId.isValid(name)) Some(new ObjectId(name))
    else findCatId().map(_._2)

  /** find the category, if missing */
  def findCat = findCatId().map(_._1)

  /** find the ID for this page, if any - respects the NOCATS */
  def findId  (curRealm:String) =
    if(ObjectId.isValid(name)) Some(new ObjectId(name))
    else findCatId(curRealm).map(_._2)

  /** find the category, if missing */
  def findCat (curRealm:String) = findCatId(curRealm).map(_._1)

  /** find the proper category and ID for this wid (name, or name and cat etc) */
  private def findCatId(curRealm:String=""): Option[(String, ObjectId)] = {
    def q = {idx: TripleIdx[String, WID, ObjectId] =>
      if(! cat.isEmpty)
        idx.get2(name, this).map((cat, _))
      else {
        // get by name and see if cat we found is NOCATS
        idx.get1k(name).find(x =>
          WID.NOCATS.contains(x.cat) || curRealm.isEmpty
        ).flatMap(x=>
          idx.get2(name, x)).map((cat, _)
        )
        //todo maybe forget this branch and enhance equals to look at nocats ?
      }
    }

    //todo performance of these is horrendous
    //todo i should ignore curRealm if I have my own
    // first current realm
    if(curRealm.isEmpty) {
      realm.orElse(CAT.unapply(cat).flatMap(_.realm)).map{r=>
        // was there a
        WikiIndex.withIndex(r)(q) orElse Wikis.find(this).map(x=>(x.category, x._id))
      } getOrElse {
        // try all indexes real quick
        var y : Option[(String, ObjectId)] = None
        WikiReactors.reactors.map(_._2.wiki.index).find{x=>
          x.withIndex(q).map{catid=>
            y = Some(catid)
            y
          }.isDefined
        }

        y orElse Wikis.find(this).map(x=>(x.category, x._id))
      }
    }
    else
      WikiIndex.withIndex(curRealm)(q) orElse WikiIndex.withIndex(getRealm)(q) orElse Wikis.find(this).map(x=>(x.category, x._id))
  }

  /** some topics don't use cats */
  override def equals (other:Any) = other match {
    case o: WID =>
      this.cat == o.cat && this.name == o.name &&
      (this.getRealm == o.getRealm || this.realm.isEmpty || o.realm.isEmpty) &&
      (this.section.isEmpty && o.section.isEmpty || this.section == o.section)
    case _ => false
  }

  def uwid = findCatId() map {x=>UWID(x._1, x._2, realm)}

  /** cat with realm */
  def cats = if(realm.exists(_.length > 0)) (realm.get + "." + cat) else cat

  /** format into nice url */
  def wpath: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 && !WID.NOCATS.contains(cat)) (cats + ":") else "") + name + (section.map("#" + _).getOrElse(""))

  /** this one used for simple cats with /w/:realm */
  def wpathnocats: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 && !WID.NOCATS.contains(cat)) (cat + ":") else "") + name + (section.map("#" + _).getOrElse(""))

  /** full categories allways, with realm prefix if not RK */
  def wpathFull: String = wpathFullNoSection +
      (section.map("#" + _).getOrElse(""))

  /** full path but without the section part */
  def wpathFullNoSection: String = parentWid.map(_.wpath + "/").getOrElse("") + (
    if (cat != null && cat.length > 0 ) (cats + ":") else "") + name

  def formatted = this.copy(name=Wikis.formatName(this))

  /** even when running in localhost mode, use the remote url */
  def urlRemote: String = {
    val hasRealm = realm.isDefined && WikiReactors(realm.get).websiteProps.prop("domain").exists(_.length > 0)

    "http://" + {
      if (hasRealm)
        WikiReactors(realm.get).websiteProps.prop("domain").get
      else
      //todo current realm
        Services.config.hostport
    } + "/" + {
      if (realm.isDefined) DieselAssets.mkLink(this, wpath)
      else canonpath
    }
  }

  /** remote url when pointing at a specific server, not the default cloud */
  def urlForTarget(target: String): String = {

    if (target.endsWith("www.dieselapps.com")) // accomodate http://
      urlRemote // reuse this to use proper realm domains
    else
      "http://" + {
        target
      } + "/" + {
        if (realm.isDefined) DieselAssets.mkLink(this, wpath)
        else canonpath
      }
  }

  /** the canonical URL with the proper hostname for reactor */
  def urlForEdit: String = intUrl(DieselAssets.mkEditLink)

  /** the canonical URL with the proper hostname for reactor */
  def url: String = intUrl(DieselAssets.mkLink)

  /** find hostport */
  def hostport(forceRemote: Boolean = false): String = {
    val hasRealm = realm.isDefined && WikiReactors(realm.get).websiteProps.prop("domain").exists(_.length > 0)

    (if (hasRealm && (forceRemote || !Services.config.isLocalhost)) {
      "http://" + WikiReactors(realm.get).websiteProps.prop("domain").get
    } else {
      //todo current realm
      // todo in localhost, it may run wiht a different IP/port to don't use the hostport, just relative
//        Services.config.hostport
      ""
    })
  }

  /** the canonical URL with the proper hostname for reactor */
  private def intUrl(func: (WID, String) => String): String = {
    hostport() + "/" + {
      if (realm.isDefined) func(this, wpath)
      else canonpath
    }
  }

  //todo this is stupid
  def urlRelative : String = urlRelative(Wikis.RK)

  /** use when coming from a known realm */
  def urlRelative (fromRealm:String) : String =
  "/" + {
    if(realm.exists(_ != fromRealm)) DieselAssets.mkLink(this, wpathFull)
    else canonpath
  }

  /** canonical path - may be different from wpath */
  def canonpath =
    if(parent.isEmpty && WID.PATHCATS.contains(cat))
      s"$cat/$name" + (section.map("#" + _).getOrElse(""))
    else
      DieselAssets.mkLink(this, wpathnocats)

  def ahref: String = "<a href=\"" + url + "\">" + toString + "</a>"
  def ahrefRelative (fromRealm:String=Wikis.RK): String = "<a href=\"" + urlRelative(fromRealm) + "\">" + toString + "</a>"
  def ahrefNice (fromRealm:String=Wikis.RK): String = "<a href=\"" + urlRelative(fromRealm) + "\">" + getLabel() + "</a>"

  /** helper to get a label, if defined or the default provided */
  //todo labels should not be in domain but in index...
  def label(action: String, alt: String) = WikiReactors(getRealm).wiki.labelFor(this, action).getOrElse(alt)

  def label(action: String) = WikiReactors(getRealm).wiki.labelFor(this, action).getOrElse(action)

  // this is my label
  def getLabel() = WikiReactors(getRealm).wiki.label(this)

  def toSpecPath = WID.WidSpecPath(this)

  def w: String = urlRelative

  def w(shouldCount: Boolean = true): String = urlRelative + (if (!shouldCount) "?count=0" else "")
}

/** wid utils */
object WID {
  /** SEO optimization - WIDs in these categories do not require the category in wpath */
  final val NOCATS = Array("Blog", "Post")
  final val PATHCATS = Array("Club", "Pro", "School", "Talk", "Topic", "Session", "Drill", "Pathway") // timid start to shift to /cat/name

  //cat:name#section OR cat:name::section - # doesn't go through path
  private val REGEX =
    """([^/:\]]*[:])?([^#|\]]+)(#[^|\]]+)?""".r

  //(node)realm.cat:name#section
  private val REGEX_WITHNODE =
    """(\([^/:\()]]+\))?([^/:\]]*[:])?([^#|\]]+)(#[^|\]]+)?""".r

  /** parse a wid
    *
    * @param a the list of wids from a path, parent to child */
  def widFromSegWithNode(str:String, curRealm: String = ""):Option[(Option[DCNode],Option[String],String,String,Option[String])] = {
      str match {
        case REGEX_WITHNODE(node, c, n, s) => {

          val nod = if(node == null) None else Option(node) // todo could include world

          val cat =
            if (c == null) ""
            else c.replaceFirst("[^.]+\\.", "").replaceFirst(":", "")

          val name =
            if (n == null) ""
            else if (cat.length <= 0) n.replaceFirst("[^.]+\\.", "")
            else n

          val realm = if (c != null && c.contains("."))
            Some(c.replaceFirst("\\..*", ""))
          else if (cat.length <= 0 && n != null && n.contains("."))
          // only if cat is not specified
            Some(n.replaceFirst("\\..*", ""))
          else None

          Some((
            nod.map(DCNode),
            realm,
            cat,
            name,
            Option(s).filter(_.length > 1).map(_.substring(1))
          ))

        }
        case _ => None
    }
  }

  /** parse a wid
    *
    * @param a the list of wids from a path, parent to child */
  private def widFromSeg(a: Array[String], curRealm: String = "") = {
    val w = a.map { x =>
      x match {
        case REGEX(c, n, s) => {
          val cat =
            if (c == null) ""
            else {
              // todo here we need to now unpack the first before . and see if it's a known realm-
              // otherwise it's a class path a.b.class from domain
              c.replaceFirst("[^.]+\\.", "").replaceFirst(":", "")
            }
          val name =
            if (n == null) ""
            else if (cat.length <= 0) n.replaceFirst("[^.]+\\.", "")
            else n
          WID(
            cat,
            name,
            None,
            Option(s).filter(_.length > 1).map(_.substring(1)),
            // if cat has realm, use it
            if (c != null && c.contains("."))
              Some(c.replaceFirst("\\..*", ""))
            else if (cat.length <= 0 && n != null && n.contains("."))
            // only if cat is not specified
              Some(n.replaceFirst("\\..*", ""))
            else None)
        }
        case _ => empty
      }
    }
    val res = w.foldLeft[Option[WID]](None)((x, y) => Some(WID(y.cat, y.name, x.flatMap(_.findId(curRealm)), y.section,
      //always follow parent's realm if provided
      x.flatMap(_.realm).orElse(y.realm))))
    res
  }

  /** parse WID from path */
  def fromPath(path: String): Option[WID] = fromPath(path, "")

  /** parse WID from path */
  def fromPath(path: String, curRealm: String): Option[WID] = {
    if (path == null || path.length() == 0)
      None
    else {
      val a = path.split("/")
      widFromSeg(a, curRealm)
    }
  }

  /** more weird parse - never returs a None, also use realm as default, if none in wpath */
  def fromPathWithRealm(path: String, curRealm: String): Option[WID] = {
    val ret = if (path == null || path.length() == 0)
      Some(WID("",""))
    else {
      val a = path.split("/")
      widFromSeg(a, curRealm)
    }

    if(ret.exists(_.realm.isDefined)) ret
    else ret.map(_.r(curRealm))
  }

  /** parse CMDWID in path */
  def cmdfromPath(path: String): Option[CMDWID] = {
    if (path == null || path.length() == 0)
      None
    else {
      def splitIt(tag: String, path: String) = {
        val b = path split tag
        val a = b.head split "/"
        CMDWID(b.headOption, widFromSeg(a), tag.replaceAllLiterally("/", ""), b.tail.headOption.getOrElse(""))
      }

      // TODO optimize this copy/paste later
      //todo if the name contains the sequence /debug this won't work - should check i.e. /debug$
      Array("/xp/", "/xpl/", "/tag/", "/react/").collectFirst {
        case tag if path contains tag => splitIt(tag, path)
      } orElse
        Array("/rss.xml", "/debug", "/edit", "/content", "/included", "/dualView", "/meta").collectFirst {
          case tag if path endsWith tag => splitIt(tag, path)
        } orElse
        Some(CMDWID(Some(path), widFromSeg(path split "/"), "", ""))
    }
  }

  final val wikip2 = """\[\[alias:([^\]]*)\]\]"""
  final val wikip2r = wikip2.r

  /** is this just an alias?
    *
    * an alias is a topic that only contains the alias markup: [[alias:xxx]]
    */
  def alias(content: String): Option[WID] = {
    if (wikip2r.findFirstMatchIn(content).isDefined) {
      //    if (content.matches(wikip2)) {
      val wikip2r(wpath) = content
      WID.fromPath(wpath)
    } else None
  }

  /** is this just an alias?
    *
    * an alias is a topic that only contains the alias markup: [[alias:xxx]]
    *
    * todo is it faster to check startsWith and then pattern?
    */
  def isAlias(content: String): Boolean =
    content.startsWith("[[alias:") && wikip2r.findFirstMatchIn(content).isDefined

  final val empty = WID("-", "-")

  implicit class WidSpecPath(val wid: WID) extends TSpecRef {
    def source: String = ""

    def wpath: String = wid.wpath

    def key: String = wid.name

    def realm: String = wid.getRealm

    def ver: Option[String] = None

    def draft: Option[String] = None

    def ahref: Option[String] = Some(wid.ahref)

    def category: String = wid.cat

    override def toString = wid.toString
  }

  implicit def fromSpecPath (s:TSpecRef) : WID = fromPath(s.wpath).get
  implicit def fromSpecPathList (s:List[TSpecRef]) : List[WID] = s.map(fromSpecPath)
}

/** a unique ID - it is less verbose than the WID - used in data modelling.
  *
  * also, having a wid means a page exists or existed
  */
case class UWID(cat: String, id:ObjectId, realm:Option[String]=None) {

  /** find the name and build a wid - possibly expensive for non-indexed topics */
  def findWid = {
    WikiIndex.withIndex(getRealm) { idx =>
      idx.find((_,_,x)=>x == id).map(_._2)
    } orElse Wikis(getRealm).findById(cat, id).map(_.wid)
  }

  /** force finding or building a surrogate wid */
  lazy val wid = findWid orElse Some(WID(cat, id.toString).copy(realm=realm)) // used in too many places to refactor properly
  def nameOrId = wid.map(_.name).getOrElse(id.toString)
  lazy val grated = grater[UWID].asDBObject(this) //.copy(realm=None)) // todo I erase the realm for backwards compatibility
  lazy val page = Wikis(getRealm).find(this)

  /** get the realm or the default */
  def getRealm = realm.getOrElse(Wikis.DFLT)

  /** withRealm - convienience builder */
  def r(r:String) = if(Wikis.DFLT == r) this else this.copy(realm=Some(r))

  /** some topics don't use cats */
  override def equals (other:Any) = other match {
    case o: UWID => this.id == o.id // this way you can change cats without much impact
    case _ => false
  }
}

object UWID {
  final val empty = UWID("?", new ObjectId())
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



