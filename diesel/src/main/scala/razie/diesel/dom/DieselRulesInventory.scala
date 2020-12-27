/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import com.razie.pub.comms.{CommRtException, Comms}
import java.net.{HttpURLConnection, URI}
import org.json.JSONObject
import razie.diesel.dom.RDOM._
import razie.diesel.engine.nodes.EMsg
import razie.diesel.expr.ECtx
import razie.tconf.{DSpecInventory, FullSpecRef, SpecRef}
import razie.wiki.Sec
import razie.{Snakk, clog, js}
import scala.collection.mutable

/** based on diesel rules domain plugin */
class DieselRulesInventory(
  override val name:String = "diesel",
  var props: Map[String, String] = Map.empty
) extends DomInventory {

  var env: String = ""

  override def conn = props.getOrElse("conn.name", "default")

  var realm: String = ""
  var specInv: Option[DSpecInventory] = None
  var iprops : Map[String,String] = Map.empty

  /** if the ReactorMod is present and a connection, create it - just one conn */
  override def mkInstance(irealm: String, ienv:String, wi: DSpecInventory, newName:String, dprops: Map[String, String] = Map.empty): List[DomInventory] = {
    // todo optimize - load only if a set of classes matches or something
    // if classes are annotated with db.name or something
    val x = wi.querySpecs(irealm, "", "", "spec")

    wi.querySpecs(irealm, "", "", "spec")
        .find { spec =>
            // don't have to actually parse, eh?
//          val rest = spec.collector.getOrElse(RDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
//          val rules = rest.find(_.isInstanceOf[ERule]).map(_.asInstanceOf[ERule])
//          rules.exists(_.e.ea == "diesel.inv.impl.findByRef")
          spec.content.contains("diesel.inv.impl.findByRef")
        }.map { wprops =>
      val ret = new DieselRulesInventory(newName, iprops)// ++ wprops.allProps)
      ret.realm = irealm
      ret.specInv = Some(wi)
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
        P("inventory", this.name),
        P("connection", this.conn),
        P("env", this.env)
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
        P("inventory", this.name),
        P("connection", this.conn),
        P("env", env)
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
        P("inventory", this.name),
        P("connection", this.conn),
        P("class", ref.cls),
        P("key", ref.key),
        P.fromSmartTypedValue("assetRef", ref.toJson),
        asset.getValueP.copy(name="entity")
      ))
    )
  }

  /** list all elements of class */
  override def listAll(dom: RDomain, ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[List[DieselAsset[_]], EMsg] = {
//    pToList(ref, runMsg(dom, "diesel.inv.impl", "listAll", mprops))
    Right(
      new EMsg(
        "diesel.inv.impl",
        "listAll",
        List(
          P("inventory", this.name),
          P("connection", this.conn),
          P("class", ref.cls),
          P.fromSmartTypedValue("ref", ref.toJson)
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
          P("inventory", this.name),
          P("connection", this.conn),
          P("class", ref.cls),
          P("class", ref.cls),
          P.fromSmartTypedValue("ref", ref.toJson)
        ))
    )
  }

  def classOname(c: C): String =
    c.props.find(_.name == TABLE).map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)

  // reset during calls
  var completeUri: String = ""

  /** html for the supported actions */
  def htmlActions(elem: DE): String = {
    elem match {
      case c: C => {
        val oname = classOname(c)

        def mkMetA = s"""<a href="/diesel/plugin/$name/$conn/attrs/${c.name}">attrs</a>"""

        def mkListAll = s"""<a href="/diesel/plugin/$name/$conn/listAll/${c.name}">listAll</a>"""

        s"$mkMetA | $mkListAll"
      }

      case _ => "?"
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
  def doAction(dom: RDomain, conn: String, action: String, completeUri: String, epath: String): String = {
    try {
      this.completeUri = completeUri

      action match {
        case "testConnection" => DomInventories.resolve(testConnection(dom, epath)).currentStringValue
        case "findByRef" => findByRefs(dom, epath)
        case "findByQuery" => findByQuerys(dom, epath)
        case "listAll" => listAll(dom, null).toString()
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

    m("value")
        .asInstanceOf[List[Map[String, Any]]]
  }

  private def getValueFromBody(b: String): List[Map[String, Any]] = {
    val jj = Snakk.jsonParsed(b)
    val m = js.fromObject(jj)

    m("value")
        .asInstanceOf[List[Map[String, Any]]]
  }

  /**
    * find by entity/ref
    */
  private def findByRefs(dom: RDomain, epath: String, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : String = {
    val host = new URI(completeUri).getHost
    val PAT = """([^/]+)/(.+)""".r
    val PAT(cls, id) = epath

    dom.classes.get(cls).map { classDef =>
      val oname = classOname(classDef)
      val u = "" + s"/api/data/v8.2/${oname}s($id)"

      val b = crmJson(u)

      b.node.j.asInstanceOf[JSONObject].toString(2)
    } getOrElse
        "Class not found..."
  }

  /**
    * find by field value
    */
  private def findByQuerys(dom: RDomain, epath: String, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : String = {
    val host = new URI(completeUri).getHost
    val PAT = """([^/]+)/(.+)/(.+)""".r
    val PAT(cls, field, id) = epath
    val filter = Sec.encUrl(s"$field eq $id")

//    doQuery(cls, dom, filter, collectRefs)
    ""
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
          DieselAsset(
            ref.copy(key = "?"),
            P.toObject(x)
          )
        )
    Nil
  }

  /** turn a json value into a nice object, merge with class def and mark refs etc */
  def oFromJ(name: String, j: JSONObject, c: C) = {
    DomInventories.oFromJ(name, j, c, classOname(c), filterAttrs)
  }

  def crmJson(url: String) = {
    clog << "Snakking: " + url
    try {
      val b = Snakk.body(Snakk.url(
        url,
        Map("Authorization" -> "")
      ))

      Snakk.json(b)
    } catch {
      case cre: CommRtException if cre.uc != null => {
        val resCode = cre.uc.getHeaderField(0)
        val c = Comms.readStream(cre.uc.asInstanceOf[HttpURLConnection].getErrorStream)
        val msg = "Could not fetch data from url " + url + ", resCode=" + resCode + ", content=" + c
        clog << "SNAKK COMM ERR: " + (msg)

        val jc = if (c.startsWith("{") && c.endsWith("}")) c else s""""$c""""

        val jo = new JSONObject()
        jo.append("url", url)

        if (c.startsWith("{") && c.endsWith("}"))
          jo.append("error", c)
        else
          jo.append("error", c)

        // just in case it may have expired - reset the token

        Snakk.json(jo)
      }
    }
  }

  /**
    * find by field value
    *
    * if the field and id is null, then no filter
    */
  override def findByQuery(dom: RDomain, ref: FullSpecRef, epath: String, collectRefs: Option[mutable.HashMap[String,
      String]] = None): List[DieselAsset[_]] = {
    val host = new URI(completeUri).getHost
    val PAT = """([^/]+)/(.+)/(.+)""".r
    val PAT(cls, field, id) = epath

    dom.classes.get(cls).toList.flatMap { classDef =>
      val oname = classOname(classDef)

      // the idiots use an englishly-correct plural
      val plural = if (oname endsWith "y") oname.take(oname.length - 1) + "ies" else oname + "s"

      val filter = {
        if (id == "" || id == "*" || id == "'*'")
          s"$$top=50"
        else
          s"$$filter=" + Sec.encUrl(s"$field eq $id")
      }

      val u = "" + s"/api/data/v8.2/$plural?" + filter

      val b = crmJson(u)

      val v = b \ "value"

      v.nodes.toList.map { n =>
        val jo = n.j.asInstanceOf[JSONObject]
        val key = if (jo.has(oname + "id")) jo.get(oname + "id").toString else epath
        val o = oFromJ(key, jo, classDef)
        new DieselAsset[O](SpecRef.make(ref.realm, name, conn, classDef.name, key), o)
      }
    }
  }

}
