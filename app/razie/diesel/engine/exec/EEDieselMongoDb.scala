/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports._
import razie.db._
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx


/** actual share table. Collection model:
  * coll
  * */
class EEDieselMongodDb extends EExecutor("diesel.db.col") {
  final val MONGODB = "diesel.db.col"
  final val TBL = "DieselDb"

  import EEDieselDb._

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == MONGODB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    val col = ctx("collection")

    def realm = ctx.root.settings.realm.mkString

    def coll = ctx.getRequired("collection")

    def key = {
      // backward compliant for a while. Want to use id now not key
      ctx.get("id").orElse(ctx.get("key")).getOrElse(ctx.getRequired("id")) // funky to report missing id not key
    }

    def userId = None//ctx.root.settings.userId

    in.met match {

      case "upsert" => {
        val p = ctx.getRequiredp("document").calculatedTypedValue
        val j = p.asJson

        val res = RazMongo(TBL).findOne(Map(
          "coll" -> coll,
          "key" -> key,
          "realm" -> realm
        ))

        val id = res.map(_.get("_id")).getOrElse(new ObjectId)
        if(res.isDefined) {
          res.get.put("content", j)

          RazMongo(TBL).update(Map(
            "coll" -> coll,
            "key" -> key,
            "realm" -> realm
          ),
            res.get
          )
        } else {
          val count = RazMongo(TBL).count(Map(
            "realm" -> realm
          ))

          if (count > MAX_TABLES * MAX_ENTRIES) {
            throw new IllegalStateException("Too many entries or collections (10*10)")
          }
          RazMongo(TBL) += Map(
            "coll" -> coll,
            "key" -> key,
            "realm" -> realm,
            "content" -> j,
            "_id" -> id
          )

        }

        List(
          EVal(P.fromTypedValue("payload", id.toString))
        )
      }

      case "query" => {
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .filter (_.ttype != WTypes.UNDEFINED)
            .map(p=>("content." + p.name, p.calculatedValue))
            .toMap

        val res = RazMongo(TBL).find(Map(
          "coll" -> coll,
          "realm" -> realm
        ) ++ others)

        val p = P.fromTypedValue(
          "documents",
          res.map{x =>
//            val s = x.getAs[String]("content").get
            val s = x.get("content").toString
//            x.getAs[Map[String,Any]]("content").get
            val m = razie.js.parse(s)
            m
          }.toList
        )

        List(
          EVal(p),
          EVal(p.copy(name = Diesel.PAYLOAD))
        )
      }

      case action@ ("get" | "getsert") => {

        val res = RazMongo(TBL).findOne(Map(
          "coll" -> coll,
          "key" -> key,
          "realm" -> realm
        ))

        val p = res.map(x =>
          P.fromTypedValue("document", x.getAs[Map[String,Object]]("content").get, WTypes.JSON)
        ).orElse{
          // if default defined, create it
          val x = ctx.getp("default")
          if("getsert".equals(action) && x.isDefined) {
            val y = x.get.calculatedTypedValue
            assert(y.contentType == WTypes.JSON)
            val j = y.asJson

            RazMongo(TBL) += Map(
              "coll" -> coll,
              "key" -> key,
              "content" -> j,
              "realm" -> realm
            )

            Some(P.fromTypedValue("document", j, WTypes.JSON))
          } else {
            Some(P("document", "", WTypes.wt.UNDEFINED))
          }
        }.toList

        p.toList.flatMap{p=>
          List(
            EVal(p.copy(name = Diesel.PAYLOAD))
          )
        }
      }

      case "remove" => {
        val res = RazMongo(TBL).findOne(Map(
          "coll" -> coll,
          "key" -> key,
        "realm" -> realm
        ))

        RazMongo(TBL).remove(Map(
          "coll" -> coll,
          "key" -> key,
          "realm" -> realm
        ))

        val p = res.map(x =>
          P.fromTypedValue("document", x.getAs[Map[String,Object]]("content").get, WTypes.JSON)
        ).getOrElse(
          P("document", "", WTypes.wt.UNDEFINED)
        )

        List(
          EVal(p.copy(name="payload"))
        )
      }

      case "clear" => {
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .map(p=>("content." + p.name, p.calculatedValue))
            .toMap

        val res = RazMongo(TBL).count(Map(
          "coll" -> coll,
//          "userId" -> userId,
          "realm" -> realm
        ) ++ others)

        RazMongo(TBL).remove(Map(
          "coll" -> coll,
//          "userId" -> userId,
          "realm" -> realm
        ) ++ others)

        List(EInfo("Deleted "+res+" docs"))
      }

      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.db.col "

  override val messages: List[EMsg] =
    EMsg(MONGODB, "upsert") ::
        EMsg(MONGODB, "get") ::
        EMsg(MONGODB, "getsert") ::
        EMsg(MONGODB, "query") ::
        EMsg(MONGODB, "remove") ::
        EMsg(MONGODB, "clear") :: Nil
}

