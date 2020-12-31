/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports._
import razie.db.RazMongo
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom.WTypes
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes.{EError, EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** built-in in-memory document db */
class EEDieselMemDb extends EExecutor("diesel.db.memdb") {
  final val DB = "diesel.db.memdb"

  /** a collection */
  case class Col(name: String, entries: mutable.HashMap[String, P] = new mutable.HashMap[String, P]())

  /** a session, per user or per engine (when anonymous) */
  case class Session(name: String, var time: Long = System.currentTimeMillis(), tables: mutable.HashMap[String, Col]
  = new mutable.HashMap[String, Col]())

  /** map of active contexts per transaction */
  val sessions = new TrieMap[String, Session]()

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    cleanup
    // user id or anon session (one workflow)
    val sessionId = ctx.credentials.getOrElse(ctx.root.engine.get.id)
    val session = sessions.get(sessionId).getOrElse {
      if (sessions.size > 400)
        throw new IllegalStateException("Too many in-mem db sessions")

      val x = Session(sessionId)
      sessions.put(sessionId, x)
      x
    }
    session.time = System.currentTimeMillis() // should I or not?
    val tables = session.tables


    def log = {
      tables.keySet.map { k =>
        "Collection: " + k + "\n" +
            tables(k).entries.keySet.map { id =>
              "  " + id + " -> " + tables(k).entries(id).toString
            }.mkString("  ", "\n", "")
      }.mkString("\n")
    }

    def upsert(col: String, id: String, doc: P) = {
      if (tables.size > 10)
        throw new IllegalStateException("Too many collections (10)")

      if (!tables.contains(col))
        tables.put(col, Col(col))

      if (tables(col).entries.size > 15)
        throw new IllegalStateException("Too many entries in collection (15)")

      tables(col).entries.put(id, doc)
    }

    in.met match {

      case "get" | "getsert" => {
        val col = ctx.getRequired("collection")
        require(col.length > 0)
        val id = ctx.getRequired("id")

        tables.get(col)
            .flatMap(_.entries.get(id))
            .orElse {
              ctx.getp("default").map { doc =>
                upsert(col, id, doc)
                doc
              }
            }
            .orElse {
              Some(P.undefined(Diesel.PAYLOAD))
            }
            .toList
            .flatMap(x =>
              List(
                EVal(x),
                EVal(x.copy(name = Diesel.PAYLOAD))
              ))
      }

      case "listAll" => {
        val col = ctx.getRequired("collection")
        require(col.length > 0)

        val x = tables.get(col).toList.flatMap(_.entries).map(x => P("document", x.toString))
        EVal(P.fromSmartTypedValue(Diesel.PAYLOAD, x)) :: Nil
      }

      case cmd@("upsert") => {
        val col = ctx.getRequired("collection")
        require(col.length > 0)
        val id = if (ctx.get("id").exists(_.length > 0)) ctx.apply("id") else new ObjectId().toString

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

        val res = tables.get(col).toList.flatMap(_.entries.values.toList.filter(x =>
          // parse docs and filter by attr
          true
        ))

        List(
          EVal(P.fromSmartTypedValue(Diesel.PAYLOAD, res))
        )
      }

      case "remove" => {
        val col = ctx.getRequired("collection")
        tables.get(col).flatMap(_.entries.remove(ctx("id"))).map(
          x => EVal(x)
        ).toList
      }

      case "logAll" => {
        val res = s"Sessions: ${sessions.size}\n" + log
        EVal(P(Diesel.PAYLOAD, res)) :: Nil
      }

      case "log" => {
        EVal(P(Diesel.PAYLOAD, log)) :: Nil
      }

      case "clear" => {
        tables.clear()
        Nil
      }
      case s@_ => {
        new EError(s"$DB.$s - unknown activity ") :: Nil
      }
    }
  }

  def cleanup = {
    val oldies = sessions.filter(System.currentTimeMillis() - _._2.time > 10 * 60 * 1000)
    for (elem <- oldies) sessions.remove(elem._1)
  }

  override def toString = "$executor::memdb "

  override val messages: List[EMsg] =
    EMsg(DB, "upsert") ::
      EMsg(DB, "get") ::
      EMsg(DB, "log") ::
      EMsg(DB, "clear") :: Nil
}

