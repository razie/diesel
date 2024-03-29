/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports._
import java.util.concurrent.locks.ReentrantReadWriteLock
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom.WTypes
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes.{EError, EInfo, EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import razie.wiki.{Config, Services}
import razie.wiki.model.WikiEventBase
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.HashMap

object EEDieselDb {
  // todo if paid, should be more
  private final val MAX_TABLES = 10
  private final val MAX_ENTRIES = 100
  private final val DFLT_MAX_TOTAL_ENTRIES = 25000
  final val EXPIRY_MSEC = 10 * 60 * 1000 // 10 min

  /** max no tables per session/realm */
  lazy val maxTables = if (Services.config.isLocalhost) Services.config.prop("diesel.db.maxtables", "100").toInt else MAX_TABLES

  /** max entries total (also enforced per table out of convenience, so we don't keep counting all) */
  lazy val maxEntries = if (Services.config.isLocalhost) Services.config.prop("diesel.db.maxentries", DFLT_MAX_TOTAL_ENTRIES.toString).toInt else MAX_ENTRIES

  var statsSessions = 0
  var statsObjects = 0
  var statsCollections = 0
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

  private val lock  = new ReentrantReadWriteLock()
  private val read  = lock.readLock()
  private val write = lock.writeLock()

  private def readLock[T] (f: => T):T = {
    var res:Option[T] = None
    try {
      read.lock()
      res = Some(f)
    } finally {
      read.unlock()
    }
    res.get
  }

  private def writeLock[T] (f: => T):T = {
    var res:Option[T] = None
    try {
      write.lock()
      res = Some(f)
    } finally {
      write.unlock()
    }
    res.get
  }

  /** a collection */
  case class Col(name: String, entries: TrieMap[String, P] = new TrieMap[String, P]())

  /** a session, per user or per engine (when anonymous) */
  case class Session(
    name: String,
    var time: Long = System.currentTimeMillis(),
    tables: TrieMap[String, Col] = new TrieMap[String, Col]()
  )

  /** map of active contexts per transaction */
  val sessions = new TrieMap[String, Session]()

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DB
  }

  def updStats() = {
    EEDieselDb.statsSessions = sessions.size
    EEDieselDb.statsCollections = sessions.values.map(_.tables.size).sum
    EEDieselDb.statsObjects = sessions.values.map(_.tables.map(_._2.entries.size).sum).sum
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

  def upsert(session: Session, col: String, id: String, doc: P, toclusterize: Boolean = true): Unit = {
    val tables = session.tables
    if (tables.size > maxTables)
      throw new IllegalStateException(s"Too many collections ($maxTables)")

    if (!tables.contains(col))
      tables.put(col, Col(col))

    val t = tables(col)
    if (t.entries.size > maxEntries)
      throw new IllegalStateException(s"Too many entries ($maxEntries)")

    t.entries.put(id, doc)

    if (toclusterize) {
      clusterize(EEDbEvent("upsert", DB, session.name, col, id, doc))
    }
  }

  /** multiple doc insert, with a list of map docs - the docs need a "key" */
  def mupsert(session: Session, col: String, docs: List[Any], toclusterize: Boolean = true): Unit = {
    val pp = docs.map(d => P.fromSmartTypedValue("", d.asInstanceOf[HashMap[String, Any]]))
    pp.map(doc => upsert(session, col, doc.value.map(_.asJson.get("key")).mkString, doc))
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
  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
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
      s"SessionId: $sessionId \n" +
          tables.keySet.map { k =>
        "Collection: " + k + "\n" +
            tables(k).entries.keySet.map { id =>
              "  " + id + " -> " + tables(k).entries(id).toString
            }.mkString("  ", "\n", "")
      }.mkString("\n")
    }

    val res = in.met match {

      case cmd@("upsert") => writeLock {
        val col = ctx.getRequired("collection")
        require(col.length > 0)
        val id = if (ctx.get("id").exists(_.length > 0)) ctx.apply("id") else new ObjectId().toString

        val docs = ctx.getp("documents")
        if (docs.isDefined)
          mupsert(session, col, docs.get.value.get.asArray.toList)
        else
          upsert(session, col, id, ctx.getRequiredp("document"))

        updStats()

        EVal(new P(Diesel.PAYLOAD, id)) :: Nil
      }

      case "get" | "getsert" => writeLock {
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

      case "query" => readLock {
        val col = ctx.getRequired("collection")
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .filter(_.name != "from")
            .filter(_.name != "size")
            .filter(x => !x.isUndefinedOrEmpty)
        val from = ctx.get("from").map(_.toInt)
        val size = ctx.get("size").map(_.toInt)

        val t = query(tables, col, from, size, others)

        List(
          EVal(P.fromSmartTypedValue(Diesel.PAYLOAD,
            Map(
              "total" -> t._1,
              "data" -> t._2.map(_._2)
            )
          ))
        )
      }

      case "remove" => writeLock {
        val col = ctx.getRequired("collection")
        val k = ctx.get("key").orElse(ctx.get("id"))
        val others = in
            .attrs
            .filter(_.name != "collection")
            .filter(_.name != "id")
            .filter(_.name != "key")
            .filter(x => !x.isUndefinedOrEmpty)

        val res = if (k.isDefined) {
          tables.get(col).flatMap(_.entries.remove(k.get)).map(
            x => EVal(x)
          ).toList
        } else {
          val t = query(tables, col, None, None, others)
          t._2.foreach { rec =>
            tables.get(col).flatMap(_.entries.remove(rec._1))
          }

          List(EInfo(s"Removed ${t._1} entries..."))
        }

        updStats()

        if (res.size > 0) res
        else List(EInfo("No match..."))
      }


      case "drop" => writeLock {
        val col = ctx.getRequired("collection")
        tables.get(col).map(_.entries.clear())

        updStats()

        List(EInfo("Ok, dropped..."))
      }

      case "logAll" => readLock {
        val res = s"Sessions: ${sessions.size}\n" + log
        EVal(new P(Diesel.PAYLOAD, res)) :: Nil
      }

      case "log" => readLock {
        EVal(new P(Diesel.PAYLOAD, logList)) :: Nil
      }

      case "debug" => readLock {
        EVal(new P(Diesel.PAYLOAD, log)) :: Nil
      }

      case "clear" => writeLock {
        tables.clear()
        updStats()
        Nil
      }
      case s@_ => {
        new EError(s"$DB.$s - unknown activity ") :: Nil
      }
    }

    res
  }

  /** actual query - returns map.entries, you take the value or key */
  def query(tables: TrieMap[String, Col], col: String, from: Option[Int], size: Option[Int], criteria: List[P])
           (implicit ctx: ECtx) = {

    var others = criteria.map(_.calculatedP) // make sure they calc once

    // split into several match patterns and extract just name,value

    // if pattern equiv to startsWith
    val startsw = others.filter(x => x.calculatedValue.matches(".*\\*$"))
        .map(x => (x.name, x.calculatedValue.dropRight(1)))
    others = others.filter(a => !startsw.exists(_._1 == a.name))

    // if pattern contains star make it regex all
    val matches = others.filter(x => x.calculatedValue.contains("*")).map(
      x => (x.name, x.calculatedValue.replaceAll("\\*", ".*")))
    others = others.filter(a => !matches.exists(_._1 == a.name))

    // if no * then must be equalst for now
    val equals = others.map(x => (x.name, x.calculatedValue))

    val ires = tables.get(col).toList.flatMap(_.entries.toList
        .filter(x =>
          // parse docs and filter by attr
          if (x._2.isOfType(WTypes.wt.JSON)) {
            val m = x._2.calculatedTypedValue.asJson

            // todo better comparison

            var b = true

            b = equals.foldRight(b)(
              (a, b) => b && {
//                m.contains(a.name) &&
                m.get(a._1).exists(v =>
                  v.toString == a._2
                )
              })

            if (b && startsw.size > 0)
              b = startsw.foldRight(b)(
                (a, b) => b && {
//                m.contains(a.name) &&
                  m.get(a._1).exists { v =>
                    v.toString.startsWith(a._2)
                  }
                })

            if (b && matches.size > 0)
              b = matches.foldRight(b)(
                (a, b) => b && {
//                m.contains(a.name) &&
                  m.get(a._1).exists { v =>
                    v.toString.matches(a._2)
                  }
                })

            b
          } else {
            true
          }
        )
    )

    var res = ires
    from.filter(_ > 0).foreach { x => res = res.drop(x) }
    size.filter(_ >= 0).filter(_ < res.size).foreach { x => res = res.take(x) }

    (ires.size, res)
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
        EMsg(DB, "drop") ::
        EMsg(DB, "clear") :: Nil
}

/** built-in in-memory document db */
class EEDieselMemDb extends EEDieselMemDbBase("diesel.db.inmem") {
  override def getSessionId(ctx: ECtx): String = {
    ctx.credentials.getOrElse(ctx.root.engine.get.id)
  }
}

