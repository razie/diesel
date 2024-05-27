/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.dom

import razie.clog
import razie.hosting.WikiReactors
import razie.tconf.hosting.Reactors
import razie.wiki.model._
import scala.collection.mutable.ListBuffer

/** encapsulates the knowledge to use the wiki-defined domain model
  *
  * extracts the domain from a reactor and activates it
  */
class WikiDomainImpl (val realm:String, val wi:WikiInst) extends WikiDomain {

  // be a lazy val to avoid screwy init loops
  // all plugins registered for this domain/realm - in this list:
  //
  lazy val _allPlugins = new ListBuffer[DomInventory]().++=:(
    DieselRulesInventory.PREDEF_INVENTORIES :::
    DomInventories.pluginFactories.flatMap(x => x.mkInstance(realm, "", wi, x.name))
  )

  override def allPlugins = _allPlugins.toList

  override def addPlugin(inv: DomInventory): List[DomInventory] = {
    _allPlugins += inv
    allPlugins
  }

  /** the actual domain, reloadable */
  private var irdom: RDomain = null

  /** the realms that were mixedin here... */
  private var domainMixins: List[String] = Nil

  @volatile var isLoading: Boolean = false

  override def rdom: RDomain = synchronized {
    if (irdom == null) {
      isLoading = true

      loadDomain()

      isLoading = false
    }
    irdom
  }

  /** make sure to call this from a synchronized block */
  private def loadDomain(): Unit = {
      // add wiki mixins first, then domain mixins, so they can be overriden in leaf
      val realms = (Wikis(realm).mixins.l.map(_.realm) :::
          WikiReactors
              .getProperties(realm)
              .getOrElse("domain.mixins", "")
              .split(",")
              .filter(_.nonEmpty)
              .toList
      ).distinct

      clog << s"RDomain $realm loading mixins: $realms"

      // todo kind'a sucks to reparse all again, but need to exclude topics marked private
      val mixtopics = (realms flatMap (r=> WikiSearch.getList(r, "", "", WikiDomain.DOM_TAG_QUERY.tags))).filter(! _.tags.contains("private"))

      val mytopics = WikiSearch.getList(realm, "", "", WikiDomain.DOM_TAG_QUERY.tags)
      irdom = (mixtopics ::: mytopics)
            .flatMap(p => WikiDomain.domFrom(p).toList)
            .fold(createRDom) (_ plus _.revise)

      //.addRoot  // can't add here, it will show up all the time in browsers etc

   // but we should... /
    addRootIfMissing()

      /**
      irdom =
          WikiSearch.getList(realm, "", "", WikiDomain.DOM_TAG_QUERY.tags)
              .flatMap(p => WikiDomain.domFrom(p).toList)
              .fold {
                // add wiki mixins first, then domain mixins, so they can be overriden in leaf
                domainMixins = (
                    Wikis(realm).mixins.l.map(_.realm) :::
                    WikiReactors
                        .getProperties(realm)
                        .getOrElse("domain.mixins", "")
                        .split(",")
                        .filter(_.nonEmpty)
                        .toList
                    ).distinct

                clog << s"RDomain $realm loading mixins: $domainMixins"

                domainMixins
                    .map (Wikis(_).domain.rdom)
                    .fold(createRDom) (_ plus _.revise)
              }(_ plus _.revise)
              //.addRoot  // can't add here, it will show up all the time in browsers etc
*/
      // do i need to revise every time or one time at the end?
  }

  override def addRootIfMissing(): Unit = synchronized {
    if(! rdom.classes.contains("Domain")) {
      irdom = rdom.addRoot
    }
  }

  override def resetDom: Unit = synchronized {
    irdom = null
  }

  import razie.diesel.dom.RDOM._

  override def isWikiCategory(cat: String): Boolean =
    rdom.classes.get(cat).exists(_.stereotypes.contains(WikiDomain.WIKI_CAT))

  /** parse wiki categories into domain model - then you can add DSL constructs */
  override def createRDom : RDomain = {
    val diamonds = for (cat <- wi.categories if cat.contentProps.exists(t=>t._1.startsWith("diamond:"))) yield {
      val x = cat.contentProps.find(t=>t._1.startsWith("diamond"))
    }

    // inherit and linearize classes in mixins
    val allClasses = new ListBuffer[WikiEntry]()
    allClasses appendAll wi.categories

    // add all base classes from the mixins
    wi.fallBacks.foreach(
      _.categories.foreach(c=>
        if(!allClasses.exists(_.name == c.name)) allClasses.append(c)
      )
    )

    val classes = for (cat <- allClasses) yield {
      val assocs =
        for (
          t <- cat.contentProps if (t._1 startsWith "roles:");
          r <- t._2.split(",")
        ) yield {
          A("", cat.name, t._1.split(":")(1), "", r)
        }
      val base =
        for (
          t <- cat.contentProps if (t._1 startsWith "dom.base");
          r <- t._2.split(",")
        ) yield {
          r
        }
      val props =
        for (
          t <- cat.contentProps if !(
              t._1.startsWith("dom.base") ||
              t._1.startsWith("roles:") ||
              t._1.startsWith("name") ||
              t._1.startsWith("label") ||
              t._1.startsWith("id") ||
              t._1.startsWith("url") ||
              t._1.startsWith("category") ||
              t._1.startsWith("tags") ||
              t._2.length <= 0
          )
        ) yield {
        new P(t._1, t._2)
      }
      C(cat.name, "", WikiDomain.WIKI_CAT, base.toList, "", Nil, Nil, assocs.toList, props.toList)
    }

    val x = new RDomain(
      realm,
      classes.map(c => (c.name, c)).toMap,
      classes.flatMap(_.assocs).toList,
      List.empty,
      Map.empty)
    x
  }
}

