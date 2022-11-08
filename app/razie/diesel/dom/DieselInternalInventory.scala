/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.cron.DieselCron
import razie.diesel.dom
import razie.diesel.dom.RDOM.P.asString
import razie.diesel.dom.RDOM._
import razie.diesel.engine.nodes.EMsg
import razie.diesel.expr.{DieselExprException, ECtx}
import razie.diesel.model.DieselTarget
import razie.tconf.{DSpecInventory, FullSpecRef, SpecRef, TagQuery}
import razie.{Snakk, js}
import scala.collection.mutable

/** based on diesel rules domain plugin */
class DieselInternalInventory(
  override val name: String = "diesel.internal",
  var props: Map[String, String] = Map.empty
) extends DomInventory {

  var env: String = ""

  override def conn = props.getOrElse("conn.name", "default")

  var realm: String = ""
  var specInv: Option[DSpecInventory] = None
  var iprops : Map[String,String] = Map.empty

  /** create an instance -  */
  override def mkInstance(irealm: String, ienv:String, wi: DSpecInventory, newName:String, dprops: Map[String, String] = Map.empty): List[DomInventory] = {
      val ret = new DieselInternalInventory(newName, iprops)// ++ wprops.allProps)
      ret.realm = irealm
      ret.specInv = Option(wi)
      ret.iprops = dprops
      ret.env = ienv
      List(ret)
  }

  override def testConnection(dom: RDomain, epath: String): Either[P,EMsg] = {
    Left(P.fromSmartTypedValue("", "Ok"))
  }

  override def connect(dom:RDomain, env:String): Either[P,EMsg] = {
    Left(P.fromSmartTypedValue("", "Ok"))
  }

  override def upsert(dom: RDomain, ref: FullSpecRef, asset:DieselAsset[_]) : Either[Option[DieselAsset[_]], EMsg] = {
    throw new DieselExprException("Can't create diesel object this way...")
  }

  def fromList (x:List[DieselAsset[_]]) : Either[DIQueryResult, EMsg] = {
    Left(DIQueryResult(x.size, x))
  }

  /** list all elements of class */
  override def listAll(dom: RDomain, ref: FullSpecRef,
                       from: Long, limit: Long, sort: Array[String],
                       countOnly: Boolean = false,
                       collectRefs: Option[mutable.HashMap[String, String]] = None):Either[DIQueryResult, EMsg] = {
    val prefix = ref.realm + "-"

    ref.cls match {

      case "DieselCron" => fromList (

        DieselCron.withRealmSchedules(all=> all/*.filter(_._1.startsWith(prefix))*/.map(t=>
            new DieselAsset(
              ref.copy(key=t._1),
              t,
              Option(
                new O("", ref.cls, t._2.toJson.map(x=>P.fromSmartTypedValue(x._1, x._2)).toList)
              )
            )
            ).toList
      ))

      case _ => Left(DIQueryResult(0, Nil))
    }
  }

  def cancelCron (ref:FullSpecRef) = {
    // remove the realm from key
    val k = if(ref.key.contains("-")) ref.key.replaceFirst("[^-]*-", "") else ref.key

      DieselCron.cancelSchedule(ref.realm, k).map { t =>
      new DieselAsset(
        ref,
        t,
        Option(
          new O("", ref.cls, t.toJson.map(x => P.fromSmartTypedValue(x._1, x._2)).toList)
        )
      )
    }
  }

  /**
    * remove by entity/ref
    */
  override def remove(dom: RDomain, ref: FullSpecRef)
  : Either[Option[DieselAsset[_]], EMsg] = {
    val prefix = ref.realm + "-"

    ref.cls match {

      case "DieselCron" => Left (cancelCron(ref))

      // todo if blinq cron also remove persistance?
      // todo maybe start a flow instead of directly removing it here

      case _ => Left(None)
    }
  }

  /** html for the supported actions */
  override def htmlActions(elem: DE, ref:Option[FullSpecRef]): String = {
    elem match {
      case c: C => {
        def mkListAll = s"""<a href="/diesel/dom/list/${c.name}">listAll</a>"""

        def mkNew =
          if (WikiDomain.canCreateNew(realm, c.name))
            s""" <a href="/doe/diesel/dom/startCreate/${c.name}">new</a>"""
          else
            ""

        def mkMore = c.name match {
          case "DieselCron" if ref.isDefined =>
            s"""| <a href="/doe/diesel/dom/action/cancel/${c.name}/${ref.get.key}">cancel</a>"""
          case _ => ""
        }

        s"$mkListAll | $mkNew $mkMore"
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
      val (cat, f, k) = SpecRef.parseEpath(epath)
      val ref = new FullSpecRef(this.name, conn, cat, k, "", realm)

      action match {

        case "testConnection" => {
          DomInventories.resolve(flattenData = false, ref, testConnection(dom, epath)).currentStringValue
        }

        case "listAll" => {
          DomInventories.resolve(flattenData = true,
            dom.name,
            ref,
            listAll(dom, ref, 0, 100, Array.empty[String])
          ).data.map { da =>
            asString(da.getValueP)
          }.mkString("\n")
        }

        case "cancel" if cat == "DieselCron" => cancelCron(ref).mkString

        case _ => throw new NotImplementedError(s"doAction $action - $completeUri - $epath")
      }
    } catch {
      case e: Throwable =>
        // something to reset/clean/reconnect?
        // .todo send reconnect
        throw e
    }
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

    val prefix = ref.realm + "-"

    ref.cls match {

      case "DieselCron" => fromList (

        DieselCron.withRealmSchedules(all=> all.filter(_._1.startsWith(prefix)).map(t=>
          new DieselAsset(
            ref.copy(key=t._1),
            t,
            Option(
              new O("", ref.cls, t._2.toJson.map(x=>P.fromSmartTypedValue(x._1, x._2)).toList)
            )
          )
        ).toList
        ))

      case _ => Left(DIQueryResult(0, Nil))
    }
  }

  /**
    * find by entity/ref
    */
  override def findByRef(dom: RDomain, ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[Option[DieselAsset[_]], EMsg] = {

    ref.cls match {

      case "DieselCron" => Left(

        DieselCron.withRealmSchedules(all=> all.get(ref.key).map(t=>
          new DieselAsset(
            ref,
            t,
            Option(
              new O("", ref.cls, t.toJson.map(x=>P.fromSmartTypedValue(x._1, x._2)).toList)
            )
          )
        )
        ))

      case _ => Left(None)
    }
  }

}

