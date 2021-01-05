/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.dom

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
    DomInventories.pluginFactories.flatMap(x => x.mkInstance(realm, "", wi, x.name))
  )

  def allPlugins = _allPlugins.toList

  def addPlugin(inv: DomInventory): List[DomInventory] = {
    _allPlugins += inv
    allPlugins
  }

  private var irdom: RDomain = null

  @volatile var isLoading: Boolean = false

  def rdom: RDomain = synchronized {
    if (irdom == null) {
      isLoading = true

      irdom =
          WikiSearch.getList(realm, "", "", WikiDomain.domTagQuery.tags)
              .flatMap(p => WikiDomain.domFrom(p).toList)
              .fold(createRDom)(_ plus _.revise)

      isLoading = false
    }
    irdom
  }

  def resetDom = synchronized {
    irdom = null
  }

  import RDOM._

  def isWikiCategory(cat: String): Boolean =
    rdom.classes.values.exists(c=> c.name == cat && c.stereotypes.contains(WikiDomain.WIKI_CAT))

  /** parse categories into domain model */
  def createRDom : RDomain = {
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
        P(t._1, t._2)
      }
      C(cat.name, "", WikiDomain.WIKI_CAT, base.toList, "", Nil, Nil, assocs.toList, props.toList)
    }

    var x = new RDomain(realm, classes.map(c=>(c.name, c)).toMap, classes.flatMap(_.assocs).toList, List.empty, Map.empty)
    x
  }
}


