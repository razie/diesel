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
import razie.diesel.engine.nodes.{EError, EInfo, EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import razie.wiki.Config
import razie.wiki.model.WikiEventBase
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

object EEDieselDb {
  // todo if paid, should be more
  final val MAX_TABLES = 10
  final val MAX_ENTRIES = 10
  final val EXPIRY_MSEC = 10 * 60 * 1000 // 10 min
}

/** clustered events */
case class EEDbEvent(
  op: String,
  db: String,
  sessionId: String,
  col: String,
  id: String,
  doc: P
) extends WikiEventBase {
  override def node = ""

  override def consumedAlready: Boolean = true
}

/** built-in in-memory document db */
class EEDieselMemDbBase(name: String) extends EExecutor(name) {
  val DB = "diesel.db.inmem"

  import EEDieselDb._

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

  /** session can be per user or engine or even realm - something */
  def getSessionId(ctx: ECtx): String = {
    // user id or anon session (one workflow)
    ctx.credentials.getOrElse(ctx.root.engine.get.id)
  }

  /** cleanup old sessions */
  def cleanup = {
    val oldies = sessions.filter(System.currentTimeMillis() - _._2.time > EXPIRY_MSEC)
    for (elem <- oldies) sessions.remove(elem._1)
  }

  def upsert(session: Session, col: String, id: String, doc: P, toclusterize: Boolean = true) = {
    val tables = session.tables
    if (tables.size > MAX_TABLES && !Config.isLocalhost)
      throw new IllegalStateException("Too many collections (10)")

    if (!tables.contains(col))
      tables.put(col, Col(col))

    if (tables(col).entries.size > MAX_ENTRIES && !Config.isLocalhost)
      throw new IllegalStateException("Too many entries in collection (15)")

    tables(col).entries.put(id, doc)

    if (toclusterize) {
      clusterize(EEDbEvent("upsert", DB, session.name, col, id, doc))
    }
  }

  def clusterize(event: EEDbEvent) = {}

  def getSession(sessionId: String) = {
    sessions.get(sessionId).getOrElse {
      if (sessions.size > 400)
        throw new IllegalStateException("Too many in-mem db sessions")

      val x = Session(sessionId)
      sessions.put(sessionId, x)
      x.time = System.currentTimeMillis() // should I or not? this prevents expiry when used
      x
    }
  }

  /** process messages */
  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    cleanup
    val sessionId = getSessionId(ctx)
    val session = getSession(sessionId)
    val tables = session.tables

    def logList = {
      tables.keySet.map { k =>
        "Collection: " + k + "\n" +
            tables(k).entries.keySet.map { id =>
              "  " + id
            }.mkString("  ", "\n", "")
      }.mkString("\n")
    }

    def log = {
      tables.keySet.map { k =>
        "Collection: " + k + "\n" +
            tables(k).entries.keySet.map { id =>
              "  " + id + " -> " + tables(k).entries(id).toString
            }.mkString("  ", "\n", "")
      }.mkString("\n")
    }

    in.met match {

      case cmd@("upsert") => {
        val col = ctx.getRequired("collection")
        require(col.length > 0)
        val id = if (ctx.get("id").exists(_.length > 0)) ctx.apply("id") else new ObjectId().toString

        upsert(session, col, id, ctx.getRequiredp("document"))

        EVal(P(Diesel.PAYLOAD, id)) :: Nil
      }

      case "get" | "getsert" => {
        val col = ctx.getRequired("collection")
        require(col.length > 0)
        val id = ctx.getRequired("id")

        tables.get(col)
            .flatMap(_.entries.get(id))
            .orElse {
              ctx.getp("default").map { doc =>
                upsert(session, col, id, doc)
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
//                EVal(P.fromSmartTypedValue("tbl", tables.get(col).get.entries.mkString)),
//                EVal(P.fromSmartTypedValue("sessionId", sessionId)),
//                EVal(P.fromSmartTypedValue("session", session.toString))
              ))
      }

      case "query" => {
        val col = ctx.getRequired("collection")
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .filter(x => !x.isUndefinedOrEmpty)

        val res = tables.get(col).toList.flatMap(_.entries.values.toList
            .filter(x =>
              // parse docs and filter by attr
              if (x.isOfType(WTypes.wt.JSON)) {
                val m = x.calculatedTypedValue.asJson
                // todo better comparison
                others.foldRight(true)(
                  (a, b) => b && m.contains(a.name) && m.get(a.name).exists(v =>
                    v.toString == a.calculatedValue ||
                        v.toString.matches(a.calculatedValue)
                  ))
              } else {
                true
              }
//            ).map(x =>
//          // transform
//          if (x.isOfType(WTypes.wt.JSON)) {
//            val m = x.calculatedTypedValue.asJson
//            m
//          } else {
//            x
//          }
            )
        )

        List(
          EVal(P.fromSmartTypedValue(Diesel.PAYLOAD,
            Map(
              "total" -> res.size,
              "data" -> res
            )
          ))
        )
      }

      case "remove" => {
        val col = ctx.getRequired("collection")
        val res = tables.get(col).flatMap(_.entries.remove(ctx("id"))).map(
          x => EVal(x)
        ).toList

        if (res.size > 0) res
        else List(EInfo("No match..."))
      }

      case "logAll" => {
        val res = s"Sessions: ${sessions.size}\n" + log
        EVal(P(Diesel.PAYLOAD, res)) :: Nil
      }

      case "log" => {
        EVal(P(Diesel.PAYLOAD, logList)) :: Nil
      }

      case "debug" => {
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

  override def toString = "$executor::" + DB + " "

  override val messages: List[EMsg] =
    EMsg(DB, "upsert") ::
        EMsg(DB, "get") ::
        EMsg(DB, "getsert") ::
        EMsg(DB, "query") ::
        EMsg(DB, "remove") ::
        EMsg(DB, "log") ::
        EMsg(DB, "debug") ::
        EMsg(DB, "clear") :: Nil
}

/** built-in in-memory document db */
class EEDieselMemDb extends EEDieselMemDbBase("diesel.db.inmem") {
  override def getSessionId(ctx: ECtx): String = {
    ctx.credentials.getOrElse(ctx.root.engine.get.id)
  }
}

