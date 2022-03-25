/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports.ObjectId
import java.sql.{DriverManager, Statement}
import razie.clog
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes._
import razie.diesel.expr.{DieselExprException, ECtx}
import scala.collection.mutable.{HashMap, ListBuffer}


object EEDieselPostgressDb {
  final val DB = "diesel.db.postgress"
}

import razie.diesel.engine.exec.EEDieselPostgressDb.DB

/**
  * Postgress DB connector
  *
  * Sample URL: "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true"
  */
case class EPostgressConnector (
  realm:String,
  override val name:String,
  url:String,
  assigned:Boolean = false
) extends EEConnector(name, DB) {

  var lastSQL = "n/a"

  var istatus = EEConnectors.STATUS_INIT
  override def status = istatus

  var conn = DriverManager.getConnection(url)

  final val TBL = "DieselDb"

  override def connect = {
    conn = DriverManager.getConnection(url)
  }

  override def reconnect = connect

  override def stop: Unit = conn.close()

  // todo tricky, tricky...
  override def assigningTo (p:P):PValue[EAssignable] = {
    if(assigned) PValue(this, WTypes.wt.OBJECT) else {
      val newOne = this.copy(name=p.name, assigned=true)

      // should I remove?
      EEConnectors.remove(realm, name)
      EEConnectors.add(realm, newOne)

      PValue(newOne, WTypes.wt.OBJECT)
    }
  }

  private val eTypes = new HashMap[String,String]()

  private def ensureEntityTableExists (name:String) = {
    if(!eTypes.contains(name)) {
      if(conn.getMetaData.getTables(null, null, name, null).next()) {
        eTypes.put(name, name)
      }
    }

    if(!eTypes.contains(name)) createEntityTable(name)
  }

  private def createEntityTable (name:String) = {
    val SQL =
      s"""
         |CREATE TABLE IF NOT EXISTS $name (
         |  realm      varchar(225) NOT NULL,
         |  env        varchar(225) NOT NULL,
         |  entityType varchar(225) NOT NULL,
         |  entityId   varchar(225) NOT NULL,
         |  content    JSON,
         |  UNIQUE (realm, env, entityType, entityId)
         |  )""".stripMargin

    val ps = conn.prepareStatement(SQL)

    try {
      lastSQL = SQL
      val res = ps.executeUpdate
      clog << res
      eTypes.put(name, name) // mark type as having table
    } finally {
      if (ps != null) ps.close()
    }
  }

  def upsertEntity(realm:String, env:String, table:String, entityType:String, entityId:String, entity:P) = {
    ensureEntityTableExists(table)
    val SQL =
      s"""
         |INSERT INTO $table(realm,env,entityType,entityId,content)
         |VALUES(?,?,?,?,?)
         |ON CONFLICT (realm,env,entityType,entityId)
         |DO UPDATE SET content=EXCLUDED.content;
         |""".stripMargin

    val res = try {
      val pstmt = conn.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS)
      try {
        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)
        pstmt.setString(4, entityId)

        val json = entity.currentStringValue

        import org.postgresql.util.PGobject
        val jsonObject = new PGobject
        jsonObject.setType("json")
        jsonObject.setValue(json)

        pstmt.setObject(5, jsonObject)

        lastSQL = SQL
        val affectedRows = pstmt.executeUpdate

        // check the affected rows
        if (affectedRows > 0) { // get the ID back
//          try {
//            val rs = pstmt.getGeneratedKeys
//            try if (rs.next) id = rs.getLong(1)
//            catch {
//              case ex: SQLException =>
//                System.out.println(ex.getMessage)
//            } finally if (rs != null) rs.close()
//          }
          List(
            EVal(P.fromTypedValue("id", entityId)),
            EVal(P.fromTypedValue("payload", entityId))
          )
        } else {
          throw new DieselExprException("Can't create entity!")
        }
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL, res)
  }

  def deleteEntity(realm:String, env:String, table:String, entityType:String, entityId:String) = {
    ensureEntityTableExists(table)

    val SQL =
      s"""
         |DELETE FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    val res = try {
      val pstmt = conn.prepareStatement(SQL)
      try {
        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)
        pstmt.setString(4, entityId)

        lastSQL = SQL
        val affectedRows = pstmt.executeUpdate

        if (affectedRows > 0) { // get the ID back
          List(
            EVal(P.fromTypedValue("id", entityId)),
            EVal(P.fromTypedValue("payload", entityId))
          )
        } else Nil
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL, res)
  }

  def findOne(realm:String, env:String, table:String, entityType:String, entityId:String) = {
    ensureEntityTableExists(table)

    val SQL =
      s"""
         |SELECT content FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    var res = Some(P("document", "", WTypes.wt.UNDEFINED))

    try {
      val pstmt = conn.prepareStatement(SQL)
      val x = try {
        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)
        pstmt.setString(4, entityId)

        lastSQL = SQL
        val rs = pstmt.executeQuery()

        if(rs.next()) {
          res = Some(P.fromTypedValue("document", rs.getString(1), WTypes.JSON))
        }
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL,res)
  }

  def find(
    realm:String,
    env:String,
    table:String,
    entityType:String,
    offset:Option[Long],
    limit:Long,
    query:Map[String,String]) = {

    ensureEntityTableExists(table)

    // build sql query
    // todo for numbers too
    var q = query.map{t=>
      val op = if(t._2.contains("*")) "LIKE" else "="
      val v = if(t._2.contains("*")) t._2.replaceAllLiterally("*", "%") else t._2
      val k = if(t._1.startsWith("content.")) t._1.replaceFirst("content\\.", "content->>'") + "'" else t._1
      s" $k $op '$v' "
    }.mkString(" AND ")

    if(q.trim.length > 0) q = " AND " + q

    val off = offset.map (x=> s"OFFSET $x").getOrElse("")
    val lim = s"LIMIT ${limit}"

    val SQL1 =
      s"""
         |SELECT count(*) FROM $table
         |WHERE realm=? AND env=? AND entityType=? $q
         |""".stripMargin

    val SQL =
      s"""
         |SELECT content FROM $table
         |WHERE realm=? AND env=? AND entityType=? $q
         |$lim
         |$off
         |""".stripMargin

    var count = 0L
    val res = new ListBuffer[HashMap[String, Any]]

    try {
      val pstmt1 = conn.prepareStatement(SQL1)
      val pstmt = conn.prepareStatement(SQL)

      try {
        pstmt1.setString(1, realm)
        pstmt1.setString(2, env)
        pstmt1.setString(3, entityType)

        lastSQL = SQL1
        val rs1 = pstmt1.executeQuery()

        if(rs1.next()) {
          count = rs1.getLong(1)
        }

        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)

        lastSQL = SQL
        val rs = pstmt.executeQuery()

        while(rs.next()) {
          res append razie.js.parse(rs.getString(1))
        }
      } finally {
        if (pstmt1 != null) pstmt1.close()
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL1, SQL, count, res)
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx) = {
    Nil
  }
}

/**
 * Postgress DB executor
 */
class EEDieselPostgressDb extends EEDieselDbExecutor(DB) {

  override def test(ast:DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity startsWith DB
  }

  /** get the referenced connection */
  def getConn (implicit ctx: ECtx) =
    EEConnectors.get(realm, connectionName)
        .map(_.asInstanceOf[EPostgressConnector])
        .map {possiblyClosedConn=>
          if(possiblyClosedConn.conn.isClosed) {
            possiblyClosedConn.reconnect
          }
          possiblyClosedConn
        }
        .getOrElse {
          throw new IllegalArgumentException(s"Data connection name $connectionName not found!")
        }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    try {
      in.met match {

        case "new" => {  // factory method (new(name,url)

          val name = ctx.getRequiredp("connection").calculatedValue
          val url = ctx.getRequiredp("url").calculatedValue

          // if exist, return it
          val conn = EEConnectors.get(realm, name)
          val x = conn
              .map(
                _.asInstanceOf[EPostgressConnector]
              )
              .filter(_.url == url)
              .map { x =>
                x
              } getOrElse {
            conn.foreach(_.stop)

            val x = EPostgressConnector(realm, name, url)
            EEConnectors.add(realm, x)
            x
          }

          List(
            EVal(P(name, "", WTypes.wt.OBJECT.withSchema(DB)).withValue(x, WTypes.wt.OBJECT.withSchema(DB)))
          )
//    } else {
//      // route Msg: find instance and delegate
//      // todo in the future this can be bypassed if I allow calling messages on objects in context, before executors
//      EEConnectors.get(realm, in.entity)
//          .map(_.apply(in, destSpec))
//          .getOrElse {
//            List(
//              EError(s"Cannot find instance for ${in.entity} in realm $realm")
//            )
//          }
        }

        case "close" => {  // factory method (new(name,url)
          getConn.stop

          List(EInfo("Connection stopped"))
        }

        case "upsert" => {
          val conn = getConn

          val p = ctx.getRequiredp("document").calculatedP

          val (sql, l) = conn.upsertEntity(realm, env, coll, coll, newKey, p)

          EInfo("SQL", sql) :: l
        }

        case "query" => {

          val conn = getConn

          val (sql1, sql, count, resList) =
            conn.find(realm, env, coll, coll, getQFrom, getQSize, otherQueryParms(in))

          EInfo("SQL1", sql1) ::
          EInfo("SQL", sql) ::
              List(
                EVal(P.fromSmartTypedValue(Diesel.PAYLOAD,
                  Map(
                    "total" -> count,
                    "data" -> resList
                  )
                ))
              )
        }

        case action@("get" | "getsert") => {

          val conn = getConn

          val (sql, res) = conn.findOne(realm, env, coll, coll, key)

          if ("get".equals(action) || res.exists(!_.isUndefined)) {
            EInfo("SQL", sql) ::
                res.toList.map { p =>
                  EVal(p.copy(name = Diesel.PAYLOAD))
                }
          } else {
            val p = ctx.getp("default").map(_.calculatedP)

            p.map { p =>
              val (sql2, l) = conn.upsertEntity(realm, env, coll, coll, key, p)

              EInfo("SQL", sql) :: EInfo("SQL", sql2) :: l
            }.getOrElse {
              // todo copy paste
              EInfo("SQL", sql) ::
                  res.toList.map { p =>
                    EVal(p.copy(name = Diesel.PAYLOAD))
                  }
            }
          }
        }

        case "remove" => {
          val conn = getConn

          val (sql1, doc) = conn.findOne(realm, env, coll, coll, key)
          val (sql2, l) = conn.deleteEntity(realm, env, coll, coll, key)

          EInfo("SQL", sql2) :: l :::
              (doc
                  .orElse {
                    Some(P("document", "", WTypes.wt.UNDEFINED))
                  }
                  .toList.map { p =>
                EVal(p.copy(name = Diesel.PAYLOAD))
              })
        }

        case s@_ => {
          new EError(s"ctx.$s - unknown activity ") :: Nil
        }
      }
    } catch {
      case t: Throwable => {
        // any exception, force close to reconnect the connection
        val res = EInfo("Last SQL", getConn.lastSQL) ::
            new EError("Exception", t) :: Nil
        getConn.conn.close()
        res
      }
    }
  }

  override def toString = "$executor::"+name

  override val messages: List[EMsg] =
    EMsg(DB, "new") ::
        EMsg(DB, "upsert") ::
        EMsg(DB, "query") ::
        EMsg(DB, "get") ::
        EMsg(DB, "remove") ::
        Nil
}

