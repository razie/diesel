/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.dom
import razie.diesel.dom.RDOM.P.asString
import razie.diesel.dom.RDOM._
import razie.diesel.engine.{DomEngECtx, DomEngineSettings}
import razie.diesel.engine.nodes.{EMsg, EnginePrep}
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselTarget
import razie.tconf.{DSpecInventory, FullSpecRef, TagQuery}
import razie.{Snakk, js}
import scala.collection.mutable

/** based on diesel rules domain plugin
  * this assumes you implemented the inventory rules matching you className,
  * like diesel.inv.impl.listAll
  */
class DieselRulesInventory (
  override val name: String = "diesel",
  var props: Map[String, String] = Map.empty
) extends DomInventory {

  override def toString = s"DieselRulesInventory: $name"

  var env: String = ""

  override def conn = props.getOrElse("conn.name", "default")

  var realm: String = ""
  var specInv: Option[DSpecInventory] = None
  var iprops : Map[String,String] = Map.empty

  /**
    * can this support the class? You can look at it's annotations etc
    *
    * You can either use the `diesel.inv.register` message or annotate your known classes withe a
    * specific annotation like `odata.name` etc
    */
  override def isRegisteredFor(realm: String, c: C): Boolean = {
    // don't need to handle default here - it's handled in findInventoriesForClass
//    c.props.find(_.name == "inventory").map (inv=>
      DomInventories.invRegistry.get(realm+"."+c.name).exists(_ == this.name)
//    ).getOrElse(
      // default inventory - nothing else must be registered
//      ! DomInventories.invRegistry.contains(realm+"."+c.name) &&
//          ! c.stereotypes.contains(razie.diesel.dom.WikiDomain.WIKI_CAT)
//    )
  }


  /** create an instance -  */
  override def mkInstance(irealm: String, ienv:String, wi: DSpecInventory, newName:String, dprops: Map[String, String] = Map.empty): List[DomInventory] = {
    // todo optimize - load only if a set of classes matches or something
    // if classes are annotated with db.name or something
    // but they could also be registered in EnvSettings - before the dom is loaded ??

    val specs = DieselTarget.tqSpecs(irealm, TagQuery.EMPTY)
    specs
        .find { spec =>
          // don't have to actually parse, eh just look for str?
          val s = spec.content
          s.exists(_.contains("diesel.inv.impl.findByRef"))
        }.map { wprops =>
      val ret = new DieselRulesInventory(newName, iprops)// ++ wprops.allProps)
      ret.realm = irealm
      ret.specInv = Option(wi)
      ret.iprops = dprops
      ret.env = ienv
      ret
      // todo find EnvironmentProperties for env ?
    }.toList

    // todo add observer when form changes and not reset all the time
    // todo design mechanism for wiki changes propagating and objects changing state
  }

  final val TABLE = "table"

  override def testConnection(dom: RDomain, epath: String): Either[P,EMsg] = {
    Right(
      new EMsg(
      "diesel.inv.impl",
      "testConnection",
      List(
        P.of("inventory", this.name),
        P.of("connection", this.conn),
        P.of("env", this.env)
      )
      )
    )
  }

  override def connect(dom:RDomain, env:String): Either[P,EMsg] = {
    Right(
      new EMsg(
      "diesel.inv.impl",
      "connect",
      List(
        P.of("inventory", this.name),
        P.of("connection", this.conn),
        P.of("env", env)
      )
    )
    )
  }

  override def upsert(dom: RDomain, ref: FullSpecRef, asset:DieselAsset[_]) : Either[Option[DieselAsset[_]], EMsg] = {
    Right(
      new EMsg(
      "diesel.inv.impl",
        "upsert",
      List(
        P.of("inventory", this.name),
        P.of("connection", this.conn),
        P.of("className", ref.cls),
        P.of("table", classOname(ref.cls)),
        P.of("key", ref.key),
        P.of("assetRef", ref.toJson),
        asset.getValueP.copy(name = "entity")
      ))
    )
  }

  /**
    * remove by entity/ref
    */
  override def remove(dom: RDomain, ref: FullSpecRef)
  : Either[Option[DieselAsset[_]], EMsg] = {
    Right(
      new EMsg(
        "diesel.inv.impl",
        "remove",
        List(
          P.of("inventory", this.name),
          P.of("connection", this.conn),
          P.of("className", ref.cls),
          P.of("table", classOname(ref.cls)),
          P.of("ref", ref.toJson)
        ))
    )
  }

  /** list all elements of class */
  override def listAll(dom: RDomain, ref: FullSpecRef,
                       from: Long, limit: Long, sort: Array[String],
                       countOnly: Boolean = false,
                       collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[DIQueryResult, EMsg] = {
    Right(
      new EMsg(
        "diesel.inv.impl",
        "listAll",
        List(
          P.of("inventory", this.name),
          P.of("connection", this.conn),
          P.of("className", ref.cls),
          P.of("table", classOname(ref.cls)),
          P.of("ref", ref.toJson),
          P.of("from", from),
          P.of("size", limit)
        ))
    )
  }

  /**
    * find by field value
    *
    * if the field and id is null, then no filter
    */
  override def findByQuery(dom: RDomain, ref: FullSpecRef, epath: Either[String, collection.Map[String, Any]],
                           from: Long = 0, size: Long = 100,
                           sort: Array[String],
                           countOnly: Boolean = false,
                           collectRefs: Option[mutable.HashMap[String, String]] = None):
  Either[DIQueryResult, EMsg] = {

    val attrs = epath.fold(
      s => {
        // todo what ???
        // query by path
        val PAT = DomInventories.CLS_FIELD_VALUE
        val PAT(cls, field, id) = epath.left.get

        {
          if (id == "" || id == "*" || id == "'*'")
            P.fromSmartTypedValue("query", Map())
          else
            P.fromSmartTypedValue("query", Map(field -> id))
        }
      },
      m => P.fromSmartTypedValue("query", m)
    )

    Right(
      new EMsg(
        "diesel.inv.impl",
        "findByQuery",
        List(
          P.of("inventory", this.name),
          P.of("connection", this.conn),
          P.of("className", ref.cls),
          P.of("table", classOname(ref.cls)),
          P.of("from", from),
          P.of("size", size),
          P.of("countOnly", countOnly),
          P.of("sort", sort.mkString(",")),
          attrs
        ))
    )
  }

  /**
    * find by entity/ref
    */
  override def findByRef(dom: RDomain, ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[Option[DieselAsset[_]], EMsg] = {

    Right(
      new EMsg(
        "diesel.inv.impl",
        "findByRef",
        List(
          P.of("inventory", this.name),
          P.of("connection", this.conn),
          P.of("className", ref.cls),
          P.of("table", classOname(ref.cls)),
          P.of("ref", ref.toJson)
        ))
    )
  }

  private def classOname(c: C): String =
    c.props.find(_.name == TABLE).map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)

  private def classOname(cls: String): String = {
    val dom = WikiDomain(realm)
    val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
    c.props.find(_.name == TABLE).map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
  }

  // reset during calls
  var completeUri: String = ""

  /** html for the supported actions */
  override def htmlActions(elem: DE, ref:Option[FullSpecRef]): String = {
    elem match {
      case c: C => {
        val oname = classOname(c)

        def mkListAll2 = s"""<a href="/diesel/dom/list/${c.name}">listAll</a>"""

        def mkNew =
          if (WikiDomain.canCreateNew(realm, c.name))
            s""" <a href="/doe/diesel/dom/startCreate/${c.name}">new</a>"""
          else
            ""

//        def mkListAll = s"""<a href="/diesel/dom/$name/$conn/${c.name}/listAll"><small>list(deprecated)</small></a>"""

        s"$mkListAll2 | $mkNew"
      }

      case _ => "n/a"
    }
  }

  /**
    * do an action on some domain entity (explore, browse etc)
    *
    * @param dom         the domain
    * @param action      the action to execute
    * @param completeUri the entire URL called (use it to get host/port etc)
    * @param epath       id of the entity
    * @return
    */
  override def doAction(dom: RDomain, conn: String, action: String, completeUri: String, epath: String): String = {
    try {
      this.completeUri = completeUri
      val ref = new FullSpecRef(this.name, conn, epath, "", "", realm)

      // todo get a proper ctx from somewhere
      implicit val ctx: ECtx = new DomEngECtx(new DomEngineSettings())

      action match {

        case "testConnection" => {
          DomInventories.resolve(false, ref, testConnection(dom, epath)).currentStringValue
        }

        case "listAll" => {
          DomInventories.resolve(true,
            dom.name,
            ref,
            listAll(dom, ref, 0, 100, Array.empty[String])
          ).data.map { da =>
            asString(da.getValueP)
          }.mkString("\n")
        }

        case _ => throw new NotImplementedError(s"doAction $action - $completeUri - $epath")
      }
    } catch {
      case e: Throwable =>
        // something to reset/clean/reconnect?
        // .todo send reconnect
        throw e
    }
  }

  val filterAttrs = "".split(",")

  private def getValueFromResp(url: String): List[Map[String, Any]] = {
    val jj = Snakk.jsonParsed(Snakk.body(Snakk.url(url)))
    val m = js.fromObject(jj)

    m("value").asInstanceOf[List[Map[String, Any]]]
  }

  private def getValueFromBody(b: String): List[Map[String, Any]] = {
    val jj = Snakk.jsonParsed(b)
    val m = js.fromObject(jj)

    m("value").asInstanceOf[List[Map[String, Any]]]
  }

  private def mprops: Map[String, Any] = List(
    P.fromTypedValue("inventory", name),
    P.fromTypedValue("connection", conn)
  ).map(x => (x.name, x)).toMap


  /** list all elements of class */
  def pToList(ref: FullSpecRef, p: Option[P]): List[DieselAsset[_]] = {
    p
        .filter(_.isOfType(WTypes.wt.ARRAY))
        .toList
        .flatMap(_.value.map(_.asArray))
        .map(x =>
          dom.DieselAsset(
            ref.copy(key = "n/a"),
            P.toObject(x)
          )
        )
    Nil
  }

}

/**
  * static inventories - these are available in all realms
  */
object DieselRulesInventory {
  val PREDEF_NAMES = Array (
    "diesel.db.inmem",
    "diesel.db.memshared",
    "diesel.db.col",
    "diesel.db.postgres",
    "diesel.db.elk"
  )

  /** these are pre-defined and serve all apps */
  lazy val PREDEF_INVENTORIES = PREDEF_NAMES.map { name =>
    val ret = new DieselRulesInventory(name)
    ret
  }.toList

  val DEFAULT = "diesel.inv.default"
  val defaultInv = new DieselRulesInventory(DEFAULT)
}

//DomInventories.pluginFactories.flatMap(x => x.mkInstance(realm, "", wi, x.name))