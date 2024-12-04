/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.nodes._
import razie.diesel.engine.{DomAst, InfoAccumulator}
import razie.diesel.expr.{DieselExprException, ECtx}
import razie.{cdebug, clog}
import scala.collection.mutable.HashMap
import scala.util.Try

object EEDieselElasticDb {
  final val DB = "diesel.db.elastic"
}

import razie.diesel.engine.exec.EEDieselElasticDb.DB

/** Elastic connector for object persistance */
case class EElasticConnector (
  realm:String,
  override val name:String,
  elasticUrl:String,
  secret:String,
  key:String,
  assigned:Boolean = false
) extends EEConnector(name, DB) {

  var istatus = EEConnectors.STATUS_INIT
  override def status = istatus

  // todo tricky, tricky...
  override def assigningTo (p:P):PValue[EAssignable] = {
    if(assigned) PValue(this, WTypes.wt.OBJECT) else {
      val newOne = copy(name=p.name, assigned=true)

      // should I remove?
      EEConnectors.remove(realm, name)
      EEConnectors.add(realm, newOne)

      PValue(newOne, WTypes.wt.OBJECT)
    }
  }

  final val TBL = "DieselDb"

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val col = ctx("collection")

    def realm = ctx.root.settings.realm.mkString

    if(in.met == "new") {
      throw new DieselExprException("Can't call new on an instance...")
    } else {
      def coll = ctx.getRequired("collection")

      def key = ctx.getRequired("key")

      def userId = None//ctx.root.settings.userId

      in.met match {

        case "upsert" => {
          upsert(realm, coll, key)
        }

        case "query" => {
//          val others = in
//              .attrs
//              .filter(_.name != "collection")
//              .filter(_.name != "key")
//              .map(p => ("content." + p.name, p.calculatedValue))
//              .toMap
//
//          val res = RazMongo(TBL).find(Map(
//            "coll" -> coll,
//            "realm" -> realm
//          ) ++ others)
//
//          val p = P.fromTypedValue(
//            "documents",
//            res.map(x =>
//              x.getAs[Map[String, Any]]("content").get
//            ).toList
//          )
//
//          List(
//            EVal(p),
//            EVal(p.copy(name = "payload"))
//          )
          Nil
        }

        case action@("get" | "getsert") => {

//          val res = RazMongo(TBL).findOne(Map(
//            "coll" -> coll,
//            "key" -> key,
//            "realm" -> realm
//          ))
//
//          val p = res.map(x =>
//            P.fromTypedValue("document", x.getAs[Map[String, Object]]("content").get, WTypes.JSON)
//          ).orElse {
//             if default defined, create it
//            val x = ctx.getp("default")
//            if ("getsert".equals(action) && x.isDefined) {
//              val y = x.get.calculatedTypedValue
//              assert(y.contentType == WTypes.JSON)
//              val j = y.asJson
//
//              RazMongo(TBL) += Map(
//                "coll" -> coll,
//                "key" -> key,
//                "content" -> j,
//                "realm" -> realm
//              )
//
//              Some(P.fromTypedValue("document", j, WTypes.JSON))
//            } else {
//              Some(P("document", "", WTypes.UNDEFINED))
//            }
//          }.toList
//
//          p.toList.flatMap { p =>
//            List(
//              EVal(p),
//              EVal(p.copy(name = "payload"))
//            )
//          }
          Nil
        }

        case "remove" => {
//          val res = RazMongo(TBL).findOne(Map(
//            "coll" -> coll,
//            "key" -> key,
//            "realm" -> realm
//          ))
//
//          RazMongo(TBL).remove(Map(
//            "coll" -> coll,
//            "key" -> key,
//            "realm" -> realm
//          ))
//
//          val p = res.map(x =>
//            P.fromTypedValue("document", x.getAs[Map[String, Object]]("content").get, WTypes.JSON)
//          ).getOrElse(
//            P("document", "", WTypes.UNDEFINED)
//          )
//
//          List(
//            EVal(p),
//            EVal(p.copy(name = "payload"))
//          )
          Nil
        }

        case "clear" => {
//          val res = RazMongo(TBL).count(Map(
//            "coll" -> coll,
//            "realm" -> realm,
//            "userId" -> userId
//          ))
//
//          RazMongo(TBL).remove(Map(
//            "coll" -> coll,
//            "realm" -> realm,
//            "userId" -> userId
//          ))
//
//          List(EInfo("Deleted " + res + " docs"))
          Nil
        }

        case s@_ => {
          new EError(s"ctx.$s - unknown activity ") :: Nil
        }
      }
    }
  }

  // if the key is empty, this will insert
  private def upsert(realm: String, coll: String, key: String)(implicit ctx: ECtx) = {
    val p = ctx.getRequiredp("document").calculatedTypedValue
    val j = new HashMap[String, Any]()
        p.asJson.map(t=> j.put(t._1, t._2))

    j.put("key", key)

    val res = None //if(key.nonEmpty) RazMongo(TBL).findOne(Map(
//      "key" -> key
//    )) else None

    val id = key//res.map(_.get("_id")).getOrElse(new ObjectId)

//    if (res.isDefined) {
//      res.get.put("content", j)

//      RazMongo(TBL).update(Map(
//        "coll" -> coll(),
//        "key" -> key(),
//        "realm" -> realm()
//      ),
//        res.get
//      )
//    } else {
//      RazMongo(TBL) += Map(
//        "coll" -> coll(),
//        "key" -> key(),
//        "realm" -> realm(),
//        "content" -> j,
//        "_id" -> id
//      )
//    }


      // format index url like ELK/indexBase-2019-09-24/...
//      val dtm = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDateTime.now)
//      val surl = url + "-" + dtm + "/_doc/?pretty"
//      val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(
//        TimeZone.getTimeZone("UTC").toZoneId).format(LocalDateTime.now)

    val surl = elasticUrl + "/" + coll + "/_doc/?pretty"
    clog << "saveToElastic url: " + surl

    val startMillis = System.currentTimeMillis()

    val sc = SnakkCall("", "POST", surl, Map(WTypes.CONTENT_TYPE -> List(WTypes.Mime.appJson)), razie.js.tojsons(j.toMap), None)

    val r = Try {
      val snakkres = sc.makeCall
      cdebug << "  elastic response: " + snakkres

      List(
        EInfo(sc.toCurl),
        EVal(P.fromTypedValue("id", id.toString)),
        EVal(P.fromTypedValue("payload", id.toString))
      )
    } recover {
      case e:Throwable =>
        EInfo(sc.toCurl) ::
            EESnakk.caughtSnakkException(e, Map("responseCode" -> "201"), "?", startMillis, new InfoAccumulator())(ctx)
    }

    r.get
  }

}

/** Elastic db connector
 */
class EEDieselElasticDb extends EEDieselDbExecutor(DB) {

  override def test(ast:DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity startsWith DB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    // factory method
    if(in.met == "new") {
        val name = ctx.getRequiredp("name").calculatedValue
        val u = ctx.getRequiredp("url").calculatedValue
        val s = ctx.get("secret").mkString
        val k = ctx.get("key").mkString

      // if exist, return it
      val x = EEConnectors.get(realm, name).map {x=>
        x
      } getOrElse {
        val x = EElasticConnector(realm, name, u, s, k)
        EEConnectors.add(realm, x)
        x
      }

      List(
        EVal(new P(name, "", WTypes.wt.OBJECT.withSchema(DB)).withValue(x, WTypes.wt.OBJECT.withSchema(DB)))
      )
    } else {
      // route Msg: find instance and delegate
      // todo in the future this can be bypassed if I allow calling messages on objects in context, before executors
      EEConnectors.get(realm, in.entity)
          .map(_.apply(in, destSpec))
          .getOrElse {
            List(
              EError(s"Cannot find instance for ${in.entity} in realm $realm")
            )
          }
    }
  }

  override def toString = "$executor::"+name

  override val messages: List[EMsg] =
    EMsg(DB, "new") ::
    EMsg(DB, "upsert") ::
        EMsg(DB, "get") ::
        EMsg(DB, "remove") ::
        EMsg(DB, "clear") :: Nil
}

