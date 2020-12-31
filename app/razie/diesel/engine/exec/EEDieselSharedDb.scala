/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports._
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom.WTypes
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes.{EError, EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * same as memdb, but it is shared across all users, like a real micro-service would behave
  */
class EEDieselSharedDb extends EExecutor("diesel.db.shared") {
  final val SHAREDDB = "diesel.db.shared"

  /** map of active contexts per transaction */
  val tables = new TrieMap[String, mutable.HashMap[String, P]]()

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == SHAREDDB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    val col = ctx("collection")

    def upsert(col: String, id: String, doc: P) = {
      if (!tables.contains(col))
        tables.put(col, new mutable.HashMap[String, P]())

      val id = if (ctx("id").length > 0) ctx("id") else new ObjectId().toString

      tables(col).put(id, doc)
    }

    in.met match {

      case "get" | "getsert" => {
        val id = ctx.getRequired("id")
        tables.get(col)
            .flatMap(_.get(id))
            .orElse {
              ctx.getp("default").map { doc =>
                upsert(col, id, doc)
                doc
              }
            }
            .orElse {
              Some(P.undefined(Diesel.PAYLOAD))
            }
            .map(x => EVal(x.copy(name = Diesel.PAYLOAD)))
            .toList
      }

      case "remove" => {
        tables.get(col).flatMap(_.remove(ctx("id"))).map(
          x => EVal(x)
        ).toList
      }

      case "upsert" => {
        val id = if (ctx("id").length > 0) ctx("id") else new ObjectId().toString
        upsert(col, id, ctx.getRequiredp("document"))
        EVal(P(Diesel.PAYLOAD, id)) :: Nil
      }

      case "query" => {
        val col = ctx.getRequired("collection")
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .filter(_.ttype != WTypes.UNDEFINED)

        val res = tables.get(col).toList.flatMap(_.values.toList.filter(x =>
          // parse docs and filter by attr
          if (x.isOfType(WTypes.wt.JSON)) {
            val m = x.calculatedTypedValue.asJson
            others.foldRight(true)((a, b) => b && m.contains(a.name) && m.get(a.name).exists(_ == a.calculatedValue))
          } else {
            true
          }
        ))

        List(
          EVal(P.fromSmartTypedValue(Diesel.PAYLOAD, res))
        )
      }

      case "log" => {
        val res = tables.keySet.map { k =>
          "Collection: " + k + "\n" +
              tables(k).keySet.map { id =>
                "  " + id + " -> " + tables(k)(id).toString
              }.mkString("  ", "\n", "")
        }.mkString("\n")
        EVal(P(Diesel.PAYLOAD, res)) :: Nil
      }

      case "clear" => {
        ctx.get("collection").map {col=>
          tables.get(col).map(_.clear())
        }.getOrElse {
          tables.clear()
        }
        Nil
      }

      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::shareddb "

  override val messages: List[EMsg] =
    EMsg(SHAREDDB, "upsert") ::
      EMsg(SHAREDDB, "get") ::
      EMsg(SHAREDDB, "log") ::
      EMsg(SHAREDDB, "remove") ::
      EMsg(SHAREDDB, "clear") :: Nil
}


