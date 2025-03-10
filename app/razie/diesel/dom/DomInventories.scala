/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import java.util.HashMap
import org.json.JSONObject
import razie.diesel.dom.RDOM.P.isArrayType
import razie.diesel.{Diesel, dom}
import razie.diesel.dom.RDOM._
import razie.diesel.engine.{AstKinds, DomAst, DomEngECtx, DomEngineSettings, DomState, KeepOnlySomeSiblings}
import razie.diesel.engine.nodes.{EInfo, EMsg, flattenJson}
import razie.diesel.expr.{CExpr, DieselExprException, ECtx, SimpleECtx, StaticECtx}
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.diesel.samples.DomEngineUtils
import razie.tconf.{FullSpecRef, SpecRef}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * helpers for inventory and asset management
  */
object DomInventories extends razie.Logging {

  final val RESERVEDP = Array(
    "inventory",
    "connection",
    "class",
    "className",
    "key",
    "section"
  )

  // todo add the CRMR plugin only if the reactor has one...
  var pluginFactories: List[DomInventory] =
    new DomInvOdataCRMPlugin ::
        new DieselRulesInventory() ::
        new DieselInternalInventory() ::
        new DomInvWikiPlugin(null, "", "", "") ::
        new DomWikiJSONInventory() ::
        Nil

  /**
    * register (realm.class, inventory) - we allow just one per
    * - the actual plugins and stuff is in the WikiDomainImpl, once the inventory is connected
    */
  var invRegistry = new TrieMap[String, String]()

  /** register one plugin per class */
  def registerPluginForClass(realm: String, clsName: String, inv: String) = {
    invRegistry.put(realm + "." + clsName, inv)
  }

  // you must provide factory and the domain when loading the realm will instantiate all plugins and connections

  /** find the right plugin by name and conn */
  def getPlugin(realm: String, inv: String, conn: String): Option[DomInventory] = {
    val dom = WikiDomain(realm)
    val list = dom.findPlugins(inv)
    val p = (if (conn.nonEmpty) list.filter(_.conn == conn) else list).headOption
    trace(s"  Found inv $p")
    p
  }

 /** if user specified inv use it otherwise search for one */
  def getPluginToUse(dom:WikiDomain, cls: String, inv:String, conn: String): Option[DomInventory] = {
    if(inv.trim != "") getPlugin(dom.realm, inv, conn)
    else dom.rdom.classes.get(cls).flatMap(x=>
      getPluginForClass(dom.realm, x, conn)
    )
  }

  /** find the right plugin by realm and class */
  def getPluginForClass(realm: String, cls: DE, conn: String = ""): Option[DomInventory] = {
    val dom = WikiDomain(realm)
    var list = dom.findInventoriesForClass(cls)
    if (list.isEmpty) {
      invRegistry
          .get(realm + "." + cls.asInstanceOf[C].name)
          .orElse(invRegistry.get(realm + ".*"))
          .foreach(inv =>
            list = dom.findPlugins(inv, conn)
          )
    }
    val p = (if (conn.nonEmpty) list.filter(_.conn == conn) else list).headOption
    trace(s"  Found inv $p")
    p
  }

  /** aggregate applicable actions on element in realm's plugins */
  def htmlActions(realm: String, c: DE, ref:Option[FullSpecRef]) = {
    val oinv = WikiDomain(realm).findInventoriesForClass(c)
    val s = oinv
        .foldLeft("")(
          (a, b) => a + (if (a != "") " <b>|</b> " else "") + b.htmlActions(c, ref) +
      s"""
        |<span
        |  class="glyphicon glyphicon-info-sign"
        |  title="Inventory: ${b.name}"></span>
        |</a></small>
        |""".stripMargin

    )

    // todo add an info question mark popup to prompt them to read about inventoryies and assets etc
    if (s.trim.isEmpty || oinv.find(_.isInstanceOf[dom.DieselRulesInventory]).exists(_.asInstanceOf[DieselRulesInventory].name == DieselRulesInventory.DEFAULT))
      """
        |<small><i><span style="color:red">no inventory registered</span></i>
        |<a href="/Topic/Assets,_Entities_and_Inventories">
        |<span
        |  class="glyphicon glyphicon-info-sign"
        |  title="Read more about inventories"></span>
        |</a></small>
        |""".stripMargin + s
    else s
  }

  /** find an element by ref */
  def findByRef(ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)(implicit ctx: ECtx) : Option[DieselAsset[_]]
  = {
    trace(s"findByRef $ref")
    val dom = WikiDomain(ref.realm)
    val p = getPluginToUse(dom, ref.cls, ref.inventory, ref.conn)
    trace(s"  Found inv $p")
    val o = p.flatMap(inv =>
      resolve(
        false,
        ref.realm,
        ref,
        inv.findByRef(dom.rdom, ref, collectRefs)
      )
    )
//    trace(s"  Found obj $o")
    o
  }

  /** find an element by query
    *
    * @param ref contains the plugin,conn,class, no ID
    * @param epath
    * @param collectRefs
    * @return
    */
  def findByQuery(ref: FullSpecRef, epath: Either[String, collection.Map[String, Any]],
                  from: Long = 0, size: Long = 100, sort: Array[String],
                  countOnly: Boolean = false,
                  collectRefs: Option[mutable.HashMap[String, String]] = None)(implicit ctx: ECtx)
  : DIQueryResult = {
    val dom = WikiDomain(ref.realm)
    val p = getPluginToUse(dom, ref.cls, ref.inventory, ref.conn)
    val o = p.map(inv =>
      resolve(
        true,
        ref.realm,
        ref,
        inv.findByQuery(dom.rdom, ref, epath, from, size, sort, countOnly, collectRefs)
      )
    )
    o.getOrElse(DIQueryResult(0))
  }

  /** query all
    *
    * @param ref contains the plugin,conn,class, no ID
    * @param epath
    * @param collectRefs
    * @return
    */
  def listAll(ref: FullSpecRef,
              start: Long = 0,
              limit: Long = 100,
              sort: Array[String],
              countOnly: Boolean = false,
              collectRefs: Option[mutable
              .HashMap[String, String]]
              = None)(implicit ctx: ECtx) : DIQueryResult = {
    val dom = WikiDomain(ref.realm)
    val p = getPluginToUse(dom, ref.cls, ref.inventory, ref.conn)
    val o = p.map(inv =>
      resolve(
        flattenData = true,
        ref.realm,
        ref,
        inv.listAll(dom.rdom, ref, start, limit, sort, countOnly, collectRefs)
      )
    )
    o.getOrElse(DIQueryResult(0))
  }

  /** turn a json value into a nice object, merge with class def and mark refs etc */
  def oFromJMap(name: String, j: Map[String, Any], classDef: C, invClsName: String, filterAttrs: Array[String]) = {

    // move parms containing name/desc to the top of the list
    val parmNames = j.keySet
        .toArray
        .toList
        .map(_.toString)
        .filter(n => !n.startsWith("@"))
        .filter(n => !filterAttrs.contains(n))
    //      .sorted
    val a1 = parmNames.filter(n => n.contains("name") || n.contains("key"))
    val a2 = parmNames.filter(n => n.contains("description") || n.contains("code"))
    val b = parmNames.filterNot(
      n => n.contains("key") || n.contains("name") || n.contains("description") || n.contains("code"))

    // augment each parm with ttype from class def
    val parms = (a1 ::: a2 ::: b)
        .map { k =>
          val jvalue = j.get(k)
          val kn = k.toString
          val oname = invClsName

          // todo simplify this - i'm really just trying to decorate with known types from classdef - otherwise should preserve the types found...

          // classdef has parm defn as object - try to parse as json
          classDef.parms.find(_.name == kn).map { cp =>
            if(cp.isOfType(WTypes.wt.JSON) && jvalue.isDefined) {
              cp.copy(value = P.fromTypedValue(k, jvalue.get, cp.ttype).value)
            } else if(cp.ttype.isArray && jvalue.isDefined) {
                cp.copy(value = P.fromTypedValue(k, jvalue.get, cp.ttype).value)
              } else {
                cp.copy(value = P.fromTypedValue(k, jvalue.mkString).value)
              }
          } getOrElse {
            // classdef don't have member - see if it's json
            // todo this is odata remnants...
            if (kn.startsWith("_") && kn.endsWith(("_value"))) {
              val PAT = """_(.+)_value""".r
              val PAT(n) = kn

              val value = jvalue.mkString
              classDef.parms.find(_.name == n).map { cpk =>
                cpk.copy(value = P.fromTypedValue("", value).value)
              } getOrElse {
                P.of(kn, value)
              }
            } else {
              // this is for normal fields
              jvalue.map(v=>
                P.of(kn, v) // try typed value
              ).getOrElse {
                // todo why not undefined?
                P.of(kn, "")
              }
            }
          }
        }

    val kn = classDef.key
    val kv = parms.find(_.name == kn).map(_.currentStringValue).getOrElse(name)

    val ref = new FullSpecRef(
      "",
      "", // todo find inv/conn
      classDef.name,
      kv,
      "",
      "" // todo find realm
    )

    O(name, classDef.name, parms).withRef(ref)
  }

  /** turn a json value into a nice object, merge with class def and mark refs etc */
  def oFromJ(name: String, j: JSONObject, c: C, invClsName: String, filterAttrs: Array[String]) = {

    // move parms containing name/desc to the top of the list
    val parmNames = j.keySet
        .toArray
        .toList
        .map(_.toString)
        .filter(n => !n.startsWith("@"))
        .filter(n => !filterAttrs.contains(n))
    //      .sorted
    val a1 = parmNames.filter(n => n.contains("name") || n.contains("key"))
    val a2 = parmNames.filter(n => n.contains("description") || n.contains("code"))
    val b = parmNames.filterNot(n => n.contains("name") || n.contains("description") || n.contains("code"))

    val parms = (a1 ::: a2 ::: b)
        .map { k =>
          val value = j.get(k).toString
          val kn = k.toString
          val oname = invClsName

          c.parms.find(_.name == kn).map { cp =>
//            cp.copy(dflt = value) // todo add PValue
//            cp.withValue(value)
            P.fromTypedValue(cp.name, value, cp.ttype) // doesn't copy annot, expr etc - simplifies view
          } getOrElse {
            // key refs
            if (kn.startsWith("_") && kn.endsWith(("_value"))) {
              val PAT = """_(.+)_value""".r
              val PAT(n) = kn

              c.parms.find(_.name == n).map { cpk =>
                cpk.copy(dflt = value) // todo add PValue
              } getOrElse {
                P.of(kn, value)
              }
            } else
              P.of(kn, value)
          }
        }

    O(name, c.name, parms)
  }

  val CLS_FIELD_VALUE = SpecRef.CLS_FIELD_VALUE


  private def runEngFork (m:EMsg, ctx:ECtx) = {
    val ast = DomAst(m, AstKinds.RECEIVED)
    DomEngineUtils.runMsgSyncInFork(ast, ctx, 30)
  }

  /** for synchornous people */
  def resolve(flattenData: Boolean, ref: FullSpecRef, e: Either[P, EMsg])(implicit ctx: ECtx) : P = {
    // resolve EMrg's parameters in an empty context and run it and await?
    e.fold(
      p => p,
//      m => DomEngineUtils.runMsgSync(new DieselMsg(m, DieselTarget.ENV("n/a")).withSettings(ctx.root.settings), 30)
      m => runEngFork(m, ctx)
        ._2
        .getOrElse(P.undefined(Diesel.PAYLOAD))
    )
  }

  // resolve for messages that return an option not a list - reuse the list one
  def resolve(flattenData: Boolean, realm: String, ref: FullSpecRef, e: Either[Option[DieselAsset[_]], EMsg]) (implicit ctx: ECtx)
  : Option[DieselAsset[_]] = {
    resolve(flattenData, realm,
      ref,
      e.fold(
        p => Left(DIQueryResult(p.toList.size, p.toList)),
        m => Right(m)
      )
    ).data.headOption
  }

  /** O to DieselAsset */
  def oToA(o: O, ref: FullSpecRef, j: collection.Map[String, Any], realm: String, c: Option[C]) = {
    val kparm = c.map(_.key).getOrElse("key")
    var r = j.get("assetRef")
        .filter(_.isInstanceOf[collection.Map[String, _]])
        .map(_.asInstanceOf[collection.Map[String, _]])
        .map(_.toMap)
        .map(SpecRef.fromJson)
    val key = r.map(_.key).filter(_.nonEmpty).orElse(j.get(kparm)).mkString

    if (r.exists(_.cls.isEmpty)) {
      r = r.map(_.copy(cls = o.base))
    }
    r
    val dom = WikiDomain(realm)
    val inv = dom.rdom.classes.get(o.base).flatMap(c => dom.findInventoriesForClass(c).headOption)

    new DieselAsset[O](SpecRef.make(realm, "", "", o.base, o.name), o)
    new DieselAsset[O](
      ref = r.getOrElse(new FullSpecRef(
        inv.map(_.name).getOrElse("n/a"),
        inv.map(_.conn).getOrElse("n/a"),
        o.base,
        key,
        "",
        ""
      )
      ),
//      DomInventories.oFromJ(name, j, c, classOname(c), filterAttrs)
      value = o,
      valueO = Some(o)
    )
  }

  /** json map to DieselAsset */
  def jToA(p: P, j: collection.Map[String, Any], realm: String, c: Option[C]) = {
    var r = j.get("assetRef")
        .filter(_.isInstanceOf[collection.Map[String, _]])
        .map(_.asInstanceOf[collection.Map[String, _]])
        .map(_.toMap)
        .map(SpecRef.fromJson)
    val kparm = c.map(_.key).getOrElse("key")
    val key = r.map(_.key).orElse(j.get(kparm)).mkString

    if (r.exists(_.cls.isEmpty)) {
      r = r.map(_.copy(cls = p.ttype.schema))
    }

    val dom = WikiDomain(realm)
    val inv = dom.rdom.classes.get(p.ttype.getClassName).flatMap(c => dom.findInventoriesForClass(c).headOption)

    new DieselAsset[P](
      ref = r.getOrElse(new FullSpecRef(
        inv.map(_.name).getOrElse("n/a"),
        inv.map(_.conn).getOrElse("n/a"),
        p.ttype.getClassName,
        key,
        "",
        ""
      )
      ),
//      DomInventories.oFromJ(name, j, c, classOname(c), filterAttrs)
      value = p,
      valueO = Some(
        O(
          key,
          p.ttype.schema,
          flattenJson(p)(ECtx.empty)
        )
      )
    )
  }

  /** extract key value from class json, based on domain and/or specific key/ref/name attr */
  def jtok(j: scala.collection.Map[String, Any], c: Option[C]) = {
    // todo some use assetRef
    val kparm = c.fold("key")(_.key)
    val k = j.get(kparm)
        .orElse(j.get("ref"))
        .orElse(j.get("name"))
        .mkString
    k
  }

  /** for synchornous people */
  def resolve (flattenData: Boolean, realm: String, ref: FullSpecRef, e: Either[DIQueryResult, EMsg])(implicit ctx: ECtx) : DIQueryResult = {
    // resolve EMrg's parameters in an empty context and run it and await?
    e.fold(
      p => p,
      m => {
        val (newe, origp) =
          if (ctx.root.engine.isEmpty) DomEngineUtils.runMsgSync(new DieselMsg(m, DieselTarget.ENV(realm)).withSettings(ctx.root.settings), 30)
          else runEngFork (m, ctx)

        var p = origp

        // todo add more info to queryResult:
        // count, totalCount, from/size etc for pagination

        val err = p.exists(_.isOfType(WTypes.wt.JSON)) &&
          p.get
              .calculatedTypedValue(ECtx.empty)
              .asJson
              .get("status")
              .mkString
              .contains("fail")

        // flattenData - some ops return an array in "data" with "total"
        // todo use the "total" returned as well - below it's overriden by the paginated size of (data)
        if (p.exists(_.isOfType(WTypes.wt.JSON))) {
          val j = p.get.calculatedTypedValue(ECtx.empty).asJson
          val data = j.get("data")
          if (j.contains("total") && data.isDefined) {
            val l = data.get
            p = Option(P.fromSmartTypedValue(Diesel.PAYLOAD, l))
          }
        }

        if (err || p.isEmpty || !p.get.isOfType(WTypes.wt.JSON) && !p.get.isOfType(WTypes.wt.ARRAY)) {
          log("sub-flow return nothing, error or not a list - so no asset found!")
          DIQueryResult(0, Nil, p.toList)
        } else if (p.get.isOfType(WTypes.wt.JSON)) {
          // we got one object from a find()
          val j = p.get.calculatedTypedValue(ECtx.empty).asJson

          // if schema populated, if not get from ref
          val cls = if (p.get.ttype.schema.trim.length > 0) p.get.ttype.schema
          else ref.cls

          val c = WikiDomain(realm).rdom.classes.get(cls)

          if (c.isDefined) {
            val k = jtok(j, c)
            val o = oFromJMap(k, j.toMap, c.get, c.get.name, Array.empty)
            DIQueryResult(1, List(oToA(o, ref, j.toMap, realm, c)))
          }
          else
            DIQueryResult(1, List(jToA(p.get, j, realm, None)))
        } else {
          // we got an array from a query
          val l = p.get.calculatedTypedValue(ECtx.empty).asArray
          val cls = WikiDomain(realm).rdom.classes.get(
            // if schema populated, if not get from ref
            p.get.ttype.wrappedType.getOrElse(ref.cls)
          )

          DIQueryResult(l.size, l.collect {
            case o: P => {
              val j = o.calculatedTypedValue(ECtx.empty).asJson
              val c = WikiDomain(realm).rdom.classes.get(p.get.ttype.schema).orElse(cls)

              if (c.isDefined) {
                val k = jtok(j, c)
                val o = oFromJMap(k, j.toMap, c.get, c.get.name, Array.empty)
                oToA(o, ref.copy(key = k), j.toMap, realm, c)
              }
              else
                jToA(o, j, realm, None)
            }
            case j: collection.Map[String, Any] => {
              val c = {
                WikiDomain(realm).rdom.classes.get(p.get.ttype.schema)
                    .orElse(
                      WikiDomain(realm).rdom.classes.get(ref.cls) // assume it's of the type we were looking for
                    )
                    .orElse(cls)
              }

              if (c.isDefined) {
                val k = jtok(j, c)
                val o = oFromJMap(k, j.toMap, c.get, c.get.name, Array.empty)
                oToA(o, ref.copy(key = k), j.toMap, realm, c)
              }
              else
                jToA(P.fromSmartTypedValue(Diesel.PAYLOAD, j), j, realm, None)
            }
            case x@_ => throw new DieselExprException("Unknown type for: " + x)
          }.toList)
        }
      }
    )
  }

  /** for synchornous people */
  def resolveEntity(realm: String, ref: FullSpecRef, e: Either[Option[DieselAsset[_]], EMsg])(implicit ctx: ECtx) : Option[DieselAsset[_]] = {
    // resolve EMrg's parameters in an empty context and run it and await?
    e.fold(
      p => p,
      m => {
        var (newe, p) = DomEngineUtils.runMsgSync(new DieselMsg(m, DieselTarget.ENV(realm)).withSettings(ctx.root.settings), 30)

        if (p.isEmpty || !p.get.isOfType(WTypes.wt.JSON)) {
          log("sub-flow return nothing or not a list - so no asset found!")
          None
        } else if (p.get.isOfType(WTypes.wt.JSON)) {
          val j = p.get.calculatedTypedValue(ECtx.empty).asJson

          // if schema populated, if not get from ref
          val cls = if (p.get.ttype.schema.trim.length > 0) p.get.ttype.schema
          else ref.cls

          val c = WikiDomain(realm).rdom.classes.get(cls)

          if (c.isDefined) {
            val k = jtok(j, c)
            val o = oFromJMap(k, j.toMap, c.get, c.get.name, Array.empty)
            Option(oToA(o, ref, j.toMap, realm, c))
          }
          else
            Option(jToA(p.get, j, realm, None))
        } else {
          None
        }
      }
    )
  }

  def doAction(dom: RDomain, conn: String, action: String, completeUri: String, epath: String): String = {
    try {
      action match {
//      case "testConnection" => DomInventories.resolve(testConnection(dom, epath)).currentStringValue
//      case "findByRef" => findByRefs(dom, epath)
//      case "findByQuery" => findByQuerys(dom, epath)
//      case "listAll" => xlistAll(dom, epath)
//
//      case "accessToken" => accessToken
//      case "attrs" => getEntityAttrs(dom, action, epath)
//      case "sample" => redirectToSample(dom, action, epath)
//      case "listClasses" => listClasses(dom, epath)
//      case "metaClass" => metaClass(dom, epath)
//      case "metaAttrs" => metaAttrs(dom, epath)
//      case "makeClass" => makeClass(dom, epath, loadClasses(dom))
//      case "makeAllClasses" => makeAllClasses(dom, action, epath)
        case _ => throw new NotImplementedError(s"doAction $action - $completeUri - $epath")
      }
    } catch {
      case e: Throwable =>
//      resetOnError(e)
        throw e
    }
  }

  /** make sure the context has the domain populated */
  def defaultClassAttributes(origMap: List[(String, P)], c: Option[C])(implicit ctx: ECtx) = {
    val defaulted = c
        .toList
        .flatMap(_.parms.filter(_.expr.isDefined))
        .filter(p => !origMap.exists(_._1 == p.name))

    val newMap = if (defaulted.isEmpty) Nil else {
      val withTypes = origMap.map { t =>
        val x = t._2.copy(name = t._1).copyValueFrom(t._2)
        val cp = c.flatMap(_.parms.find(_.name == x.name))
        if ((x.isUndefined || x.ttype.name == "" || x.ttype.name == WTypes.STRING) && cp.isDefined) x.copy(ttype = cp.get.ttype)
        else x
      }
      val newCtx = new StaticECtx(withTypes, Option(ctx))

      // todo there is a weirdness where the recalculated depdencies are not reincluded in the context
      // make it so there is a newer ctx including the recalculated values for each parm based on parents...

      defaulted
          .map(p => p.calculatedP(ctx = newCtx))
          .map(p => (expandNameAsExpr(p.name), p)) // expand interpolated string
    }

    newMap
  }

  /** calculate attributes with excache */
  def calculatedClassAttributes(thiss:P, origMap: List[(String, P)], c: Option[C], skipFields:List[String])(implicit ctx: ECtx) = {
    var classParms = c
        .toList
        .flatMap(_.parms.filter(_.stereotypes.contains("excache")))

    // todo refine this sort to parse {...} expressions better
      classParms = classParms.sortWith((a,b)=> b.expr.exists(_.expr.contains("{" + a.name + "}")))

    // todo order by excache expr depyendencies

    // start with default values
    val newMap = if (classParms.isEmpty) Nil else {
      val newCtx = new StaticECtx(thiss :: origMap.map(t => t._2.copy(name = t._1).copyValueFrom(t._2)), Option(ctx))

     // todo there is a weirdness where it takes two trips because the recalculated depdencies are not reincluded in the context
     // make it so there is a newer ctx including the recalculated values for each parm based on parents...

      // use expressions
      classParms
        // if skip then use current/orig value if present, otherwise recalculate
        // A. this version would still return them with existing values
//          .map { p =>
//            origMap.find(o => skipFields.contains(p.name)).map(_._2).getOrElse(p.calculatedP(ctx = newCtx))
//          }
        // B. this will just skip them from return - client recalls their values
          .filter(p=> !skipFields.contains(p.name))
          .map { p =>
            (p.calculatedP(ctx = newCtx))
          }
        // nice names - names can also be interpolated
          .map(p => (expandNameAsExpr(p.name), p)) /* expand interpolated string in name */
          .filter(! _._2.isUndefinedOrEmpty)
          .map (t=> (t._1, t._2.currentStringValue))
    }

    newMap
  }

  /** recalculate value as expression if it is interpolated string */
  def expandNameAsExpr(s: String)(implicit ctx: ECtx) = {
    if (s contains "${") CExpr(s).apply("")
    else s
  }
}
