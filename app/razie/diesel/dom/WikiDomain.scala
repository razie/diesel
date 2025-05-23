/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.dom.RDOM.{C, DE, O, T}
import razie.hosting.WikiReactors
import razie.tconf.TagQuery
import razie.wiki.model._

/**
  * The domain for a realm. All sub-domains should be combined in this one domain.
  *
  * Combine all domain tools with higher level helpers on top of a domain
  */
trait WikiDomain {
  def realm: String

  def wi: WikiInst

  /** all plugins for this domain and in this realm */
  def allPlugins: List[DomInventory]

  /** register a connected inventory.
    *
    * Note - only useable inventories should be registered, i.e. where they are already connected */
  def addPlugin(inv: DomInventory): List[DomInventory]

  def findPlugins(inventory: String, conn: String = ""): List[DomInventory] = {
    allPlugins
        .find(_.name == inventory)
        .orElse(
          // big catch-all
          if      (DieselRulesInventory.DEFAULT == inventory) Some(DieselRulesInventory.defaultInv)
//          else if (DomWikiJSONInventory.INV == inventory) Some(DomWikiJSONInventory.instance)
          else None
        ).toList
  }

  final val INVENTORY = "inventory"

  /** based on annotations etc */
  def findInventoriesForClass(c: DE): List[DomInventory] = {
    if (c.isInstanceOf[C]) {
      allPlugins
          .find(_.isRegisteredFor(realm, c.asInstanceOf[C]))
          .orElse(
            // get annotation "inventory"
            c.asInstanceOf[C].props
                .find(_.name == INVENTORY)
                .flatMap(inv =>
                    // needs to have been registered
                  allPlugins.find(_.name == inv.currentStringValue)
                )
          ).orElse(
        // try wiki cats
        allPlugins
            .find(_.isInstanceOf[DomInvWikiPlugin])
            .filter(_.isRegisteredFor(realm, c.asInstanceOf[C]))
          ).orElse(

        // big catch-all
            Some(DieselRulesInventory.defaultInv)
          )
          .toList
    } else Nil
  }

  def addRootIfMissing() : Unit

  /** the aggregated domain representation for this realm */
  def rdom: RDomain

  /* while loading, it may recursively try to do some stuff - */
  def isLoading: Boolean

  /** reload the dom from config/wikis */
  def resetDom: Unit

  /** get access list */
  def canAccess(cat: C, o:Option[O], email:Option[String], perms:Option[Set[String]]): Boolean

  /** is this an actual wiki category or a user-defined class or imported concept ??? */
  def isWikiCategory (cat: String): Boolean

  /** is this an actual wiki category or a user-defined class or imported concept ??? */
  def isParsedClass (cat: String): Boolean

  /** does it know this class (can be cat, class etc) */
  def containsCat (cat:String) = {
    // this may cause lots of recursion while loading
    if(!isLoading) rdom.classes.contains(cat)
    else false
  }

  /** parse categories into domain model */
  def createRDom: RDomain

  // todo expand these inline
  def zEnds(aEnd: String, zRole: String) = rdom.zEnds(aEnd, zRole)

  def needsOwner(cat: String) = rdom.needsOwner(cat)

  def prop(cat: String, name: String): Option[String] = rdom.prop(cat, name)

  def needsParent(cat: String) = rdom.needsParent(cat)

  def isA(what: String, cat: String): Boolean = rdom.isA(what, cat)

  def noAds(cat: String) =
    prop(cat, "noAds").isDefined

  // todo optimize
  def dtree(base: String): List[String] =
    (base :: rdom.classes.values.filter(_.base contains base).toList.flatMap(x => dtree(x.name))).distinct

  def roles(a: String, z: String): List[String] = {
    val mine = rdom.classes.get(a).toList.flatMap(_.assocs).filter(_.z == z).map(_.zRole)
    if (mine.isEmpty) rdom.classes.get(a).toList.flatMap(_.base).foldLeft(List.empty[String])(
      (a, b) => a ++ roles(b, z))
    else mine
  }

}

object WikiDomain {
  final val WIKI_CAT = "wikiCategory"
  final val PARSED_CAT = "parsed"

  def apply(realm: String): WikiDomain = WikiReactors(realm).domain

  /** todo does it really need to start with one */
  def domFrom (first:WikiEntry, pages:List[WikiEntry]) : RDomain = {
    RDomain.domFrom(first, pages)
  }

  /** crawl all domain pieces and build a domain */
  def domFromFiltered (we:WikiEntry)(filter:PartialFunction[Any,Any]) : Option[RDomain] = {
    we.preprocessed
    RDomain.domFrom(we, Some(filter))
  }

  /** crawl all domain pieces and build a domain */
  def domFrom (we:WikiEntry) : Option[RDomain] = {
    we.preprocessed
    RDomain.domFrom(we)
  }

  /** crawl all domain pieces and build a domain */
  def domFilter[T] (we:WikiEntry)(p:PartialFunction[Any,T]) : List[T] = {
    RDomain.domFilter(we)(p)
  }

  def canCreateNew (realm:String, cat:String) = "User" != cat && "WikiLink" != cat
  //todo can i create WIkiLink if I am admin?

  /** root categories we can create free instance from, used when navigating */
  def rootCats (realm:String) = {
    apply(realm)
        .rdom
        .classes
        .values
        .filter(_.stereotypes.contains(razie.diesel.dom.WikiDomain.WIKI_CAT))
        .map(_.name)
        .toList
  }

  /** present a WE  as a generic spec */
  def spec (we:WikiEntry) = we

  /** if any special DOM wiki changes, rebuild the domain */
  WikiObservers mini {
    case WikiEvent(_, "WikiEntry", _, Some(x), _, _, _)
      if DOM_TAG_QUERY.matches(x.asInstanceOf[WikiEntry])
    => {
      val we = x.asInstanceOf[WikiEntry]

      WikiDomain.apply(we.realm).resetDom
    }
  }

  def isPrimaryType (name:String) = WTypes.PRIMARY_TYPES.contains(name)

  /** these tags denote domain pages - use with WikiSearch.getList */
  final val DOM_TAGS      = "DslDomain,dsldomain,Category,domain"
  final val DOM_TAGS_ARR  = DOM_TAGS.split(",")
  final val DOM_TAG_QUERY = new TagQuery(DOM_TAGS)
}


