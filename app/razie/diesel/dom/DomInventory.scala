/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.engine.nodes.EMsg
import razie.diesel.expr.ECtx
import razie.tconf.{DSpecInventory, FullSpecRef}
import scala.collection.mutable

// todo study with cmd pattern - should we do actors?
class DIBase()

case class DIConnect(dom: RDomain, env: String, ctx: ECtx) extends DIBase

case class DITestConnection(dom: RDomain, env: String, ctx: ECtx) extends DIBase

/** results of query and list type commands */
case class DIQueryResult(total: Long, data: List[DieselAsset[_]] = Nil, errors: List[Any] = Nil)

/**
  * a domain plugin - can adapt a domain to an external implementation
  *
  * name is the type of connector and conn is the actual connection for this instance
  *
  * for instance (name=CRM, conn=CRM1) (name=CRM, conn=CRM2)
  *
  * Any kind of connection pooling is done inside the plugin - outside they are referenced by name
  */
trait DomInventory {
  def name: String
  def realm: String
  def env: String
  def conn: String

  /** import domain from external */
  def canImportDomain: Boolean = false

  /** import a subdomain from external model - if this can import it, there's a special operation to import it
    *
    * which you can call manually to sync the definitions
    *
    * this imported definition should be saved and that will add it to the domain
    *
    * standard topic for imported domains: DomNameDomain
    */
  def importDomain: RDomain = ???

  /** test this connection */
  def testConnection(dom: RDomain, epath: String): Either[P, EMsg] = ???

  def connect(dom: RDomain, env: String): Either[P, EMsg] = ???

  /** create an element
    *
    * @param dom
    * @param ref
    * @param asset
    * @return
    */
  def upsert(dom: RDomain, ref: FullSpecRef, asset: DieselAsset[_]): Either[Option[DieselAsset[_]], EMsg] = ???

  /** list all elements of class
    *
    * @param dom  current domain
    * @param ref  reference with basic info
    * @param from pagination start from index
    * @param size pagination size
    * @param sort list of fields to sort by, format: field:desc or field:asc
    * @param collectRefs
    * @return either a result or a future message which will give the result
    */
  def listAll(dom: RDomain, ref: FullSpecRef,
              start: Long, limit: Long,
              sort: Array[String],
              countOnly: Boolean = false,
              collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[DIQueryResult, EMsg] = ???

  // todo syncDomain into external too ?

  /**
    * make instance for specific realm - read the specs and if any applicable, create a plugin and initialize it's
    * connections
    * Each plugin is responsible to configure itself, there's no standard -
    * usually they're added as ReactorMod, see the OdataCrmPlugin for example
    *
    * @param realm  the realm this is for
    * @param env    which environment
    * @param wi     - spec inventory, use it to lookup configuration topics, diesel plugin topics etc
    * @param iprops initial properties, when created via diesel message
    */
  def mkInstance(realm: String,
                 env: String,
                 wi: DSpecInventory,
                 newName: String,
                 iprops: Map[String, String] = Map.empty): List[DomInventory] = Nil

  /**
    * can this support the class? You can look at it's annotations etc
    *
    * You can either use the `diesel.inv.register` message or annotate your known classes withe a
    * specific annotation like `odata.name` etc
    */
  def isDefinedFor(realm: String, c: C): Boolean = {
    DomInventories.invRegistry.get(realm+"."+c.name).exists(_ == this.name)
  }

  /** find an element by ref
    *
    * @param dom current domain
    * @param ref ref to find
    * @param collectRefs
    * @return
    */
  def findByRef(dom: RDomain, ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[Option[DieselAsset[_]], EMsg] = ???

  /** find an element by query
    *
    * @param dom   current domain
    * @param ref   reference with basic info
    * @param epath either a query, query path or list of attributes with AND
    * @param from  pagination start from index
    * @param size  pagination size
    * @param sort  list of fields to sort by, format: field:desc or field:asc
    * @param collectRefs
    * @return either a result or a future message which will give the result
    */
  def findByQuery(dom: RDomain, ref: FullSpecRef, epath: Either[String, collection.Map[String, Any]],
                  from: Long = 0, size: Long = 100,
                  sort: Array[String],
                  countOnly: Boolean = false,
                  collectRefs: Option[mutable.HashMap[String, String]] = None
                 ):
  Either[DIQueryResult, EMsg] = ???

  /** remove an element by ref */
  def remove(dom: RDomain, ref: FullSpecRef)
  : Either[Option[DieselAsset[_]], EMsg] = ???

  /** html for the supported actions */
  def htmlActions(elem: DE): String

  /** reset oauth tokens, connections etc on error */
  def resetOnError(error: Throwable) = {}

  /**
    * do an action on some domain entity (explore, browse etc)
    *
    * @param r           the domain
    * @param action      the action to execute
    * @param completeUri the entire URL called (use it to get host/port etc)
    * @param epath       id of the entity
    * @return
    */
  def doAction(r: RDomain, conn: String, action: String, completeUri: String, epath: String): String
}

/** default inventory for wiki defined classes */
class DefaultRDomainPlugin(val specInv: DSpecInventory, val realm: String, override val conn: String = "default", ienv:String) extends
    DomInventory {
  override def name = "wiki"
  var env = ienv

  override def isDefinedFor(realm: String, c: C): Boolean = {
    c.stereotypes.contains(razie.diesel.dom.WikiDomain.WIKI_CAT)
  }

  // just replicate myself
  /**
    * make instance for specific realm - read the specs and if any applicable, create a plugin and initialize it's
    * connections
    * Each plugin is responsible to configure itself, there's no standard -
    * usually they're added as ReactorMod, see the OdataCrmPlugin for example
    *
    * @param realm the realm this is for
    * @param wi - spec inventory, use it to lookup configuration topics, diesel plugin topics etc
    * @param iprops initial properties, when created via diesel message
    */
  override def mkInstance(realm: String, env:String, wi: DSpecInventory, newName:String, iprops: Map[String, String]): List[DomInventory] = {
    List(new DomInvWikiPlugin(wi, realm, "default", env))
  }

  override def connect(dom: RDomain, env: String): Either[P, EMsg] =
    Left(P.of(Diesel.PAYLOAD, "ok"))

  /** html for the supported actions */
  def htmlActions(elem: DE): String = {
    elem match {
      case c: C => {
        def mkList = s"""<a href="/diesel/list2/${c.name}">list</a>"""

        // todo delegate decision to tconf domain - when domain is refactored into tconf
        def mkNew =
          if ("User" != name && "WikiLink" != name)
          //todo move to RDomain
          // if (ctx.we.exists(w => WikiDomain.canCreateNew(w.specPath.realm.mkString, name)))
            s""" <a href="/doe/diesel/create/${c.name}">new</a>"""
          else
            ""

        s"$mkList | $mkNew"
      }

      case _ => "?"
    }
  }


  def doAction(r: RDomain, conn: String, action: String, uri: String, epath: String) = ???
}

