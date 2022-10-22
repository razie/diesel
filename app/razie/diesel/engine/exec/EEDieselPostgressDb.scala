/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import com.mongodb.casbah.Imports.ObjectId
import java.sql.{DriverManager, Statement}
import razie.Log.log
import razie.clog
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.{AstKinds, DomAst}
import razie.diesel.engine.nodes._
import razie.diesel.expr.{DieselExprException, ECtx}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{HashMap, ListBuffer}


object EEDieselPostgressDb {
  final val DB = "diesel.db.postgres"

  // todo move into connector below
  // todo accomodate multiple connections, per realm etc - each has it's own eTypes, so move it into conn
  // (tableName, listofdenormcolumns)
  val eTypes = new TrieMap[String,List[String]]()
}

import razie.diesel.engine.exec.EEDieselPostgressDb.DB

/**
  * Postgress DB connector
  *
  * todo connectors
  *
  * Sample URL: "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true"
  */
case class EPostgressConnector (
  realm:String,
  override val name:String,
  url:String,
  assigned:Boolean = false
) extends EEConnector(name, DB) {

  import EEDieselPostgressDb.eTypes

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

  def onError(t:Throwable) = {
    // todo close conn
    // todo issues if this is multithreaded...?
//    conn.close()
    eTypes.clear() // force
    // todo just this realm, not all
    throw new RuntimeException(t)
  }

  def rkey(realm:String, name:String) = name + "." + realm

  /** it's optimized with a cache, no worries calling it */
  private def ensureEntityTableExists (realm:String, name:String)(implicit ctx: ECtx) = {
    if(!eTypes.contains(rkey(realm,name))) {
      if(conn.getMetaData.getTables(null, null, name, null).next()) {
        val cls = ctx.root.domain.flatMap(_.classes.get(name))
        val keys = cls.toList.flatMap(_.parms.filter(_.stereotypes.contains("column"))).map(_.name)
        eTypes.put(rkey(realm,name), keys)
      }
    }

    if(!eTypes.contains(rkey(realm,name))) createEntityTable(realm, name)
  }

  /** actually create table if it didn't exist */
  private def createEntityTable (realm:String, name:String)(implicit ctx: ECtx) = {
    //    val cls = WikiDomain.apply(realm).rdom.classes.get(name)
    // use this engine's domain
    val cls = ctx.root.domain.flatMap(_.classes.get(name))
    val keys = cls.toList.flatMap(_.parms.filter(_.stereotypes.contains("column"))).map(_.name)
    var columns = keys.map(p=> s"""$p varchar(225)""").mkString(",\n")

    if(columns.trim.length > 0) columns = columns + ",\n"

    val SQL =
      s"""
         |CREATE TABLE IF NOT EXISTS $name (
         |  realm      varchar(225) NOT NULL,
         |  env        varchar(225) NOT NULL,
         |  entityType varchar(225) NOT NULL,
         |  entityId   varchar(225) NOT NULL,
         |  content    JSON,
         |  $columns
         |  UNIQUE (realm, env, entityType, entityId)
         |  )""".stripMargin

    log(s"Postgress createEntityTable SQL $SQL")

    val ps = conn.prepareStatement(SQL)

    try {
      lastSQL = SQL
      val res = ps.executeUpdate
      clog << res
      eTypes.put(rkey(realm,name), keys) // mark type as having table
    } catch {
      case t:Throwable => onError(t)
    } finally {
      if (ps != null) ps.close()
    }
  }

  def upsertEntity(realm:String, env:String, table:String, entityType:String, entityId:String, entity:P)(implicit ctx: ECtx) = {
    ensureEntityTableExists(realm, table)

    {
      val json = entity.currentStringValue
      val o = entity.calculatedTypedValue.asJson

      var columns = eTypes.get(rkey(realm,table)).toList.flatMap { keys =>
        keys.filter(p=> o.contains(p))
      }

      var scolumns = columns.mkString(",\n")

      if (scolumns.trim.length > 0) scolumns = "," + scolumns
      val sqs = scolumns.replaceAll("[^,]+", "?")

      // todo reconcole on conflict with denorm columns
      val SQL =
        s"""
           |INSERT INTO $table(realm,env,entityType,entityId,content$scolumns)
           |VALUES(?,?,?,?,?$sqs)
           |ON CONFLICT (realm,env,entityType,entityId)
           |DO UPDATE SET content=EXCLUDED.content;
           |""".stripMargin

      log(s"Postgress upsertEntity $entityType:$entityId SQL $SQL")

      val pstmt = conn.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS)

      val res = try {
        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)
        pstmt.setString(4, entityId)

        import org.postgresql.util.PGobject
        val jsonObject = new PGobject
        jsonObject.setType("json")
        jsonObject.setValue(json)

        pstmt.setObject(5, jsonObject)

        var i = 6
        columns.foreach{col=>
          pstmt.setString(i, o.get(col).mkString) // todo use types like number
          i += 1
        }

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
      } catch {
        case t:Throwable => onError(t)
      } finally {
        if (pstmt != null) pstmt.close()
      }
      (SQL, res)
    }
  }

  def deleteEntity(realm:String, env:String, table:String, entityType:String, entityId:String)(implicit ctx: ECtx) = {
    ensureEntityTableExists(realm, table)

    val SQL =
      s"""
         |DELETE FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    log(s"Postgress deleteEntity $entityType:$entityId SQL $SQL")

    val res = {
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
      } catch {
        case t:Throwable => onError(t)
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL, res)
  }

  def deleteQuery(realm:String, env:String, table:String, entityType:String, query:Map[String,String])(implicit ctx: ECtx) = {
    ensureEntityTableExists(realm, table)
    val q = mapToQuery(realm, table, query)

    val SQL =
      s"""
         |DELETE FROM $table
         |WHERE realm=? AND env=? AND entityType=? $q
         |""".stripMargin

    log(s"Postgress deleteQuery $entityType:$query SQL $SQL")

    val res = {
      val pstmt = conn.prepareStatement(SQL)
      try {
        pstmt.setString(1, realm)
        pstmt.setString(2, env)
        pstmt.setString(3, entityType)

        lastSQL = SQL
        val affectedRows = pstmt.executeUpdate

        if (affectedRows > 0) { // get the ID back
          List(
            EInfo(s"Deleted $affectedRows"),
            EVal(P.fromTypedValue("deleteCount", affectedRows))
          )
        } else Nil
      } catch {
        case t:Throwable => onError(t)
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL, res)
  }

  def findOne(realm:String, env:String, table:String, entityType:String, entityId:String)(implicit ctx: ECtx) = {
    ensureEntityTableExists(realm, table)

    val SQL =
      s"""
         |SELECT content FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    log(s"Postgress findOne $entityType:$entityId SQL $SQL")

    var res = Some(new P("document", "", WTypes.wt.UNDEFINED))

    {
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
      } catch {
        case t:Throwable => onError(t)
      } finally {
        if (pstmt != null) pstmt.close()
      }
    }

    (SQL,res)
  }

  /** build simpile map query to sql query */
  def mapToQuery(realm:String,name:String,query:Map[String,String]) = {
    // build sql query

    val simpleQuery = query.keys.map(_.replaceFirst("content\\.", "")).toList

    // figure out the denorm columns
    var columns = eTypes.get(rkey(realm,name)).toList.flatMap { keys =>
      keys.filter(p=> simpleQuery.contains(p))
    }

    // todo for numbers too
    var q = query.map{t=>
      val op = if(t._2.contains("*")) "LIKE" else "="
      val v = if(t._2.contains("*")) t._2.replaceAllLiterally("*", "%") else t._2
      val k = if(t._1.startsWith("content.")) {
        val justName = t._1.replaceFirst("content\\.", "")
        if(columns.contains(justName)) justName
        else t._1.replaceFirst("content\\.", "content->>'") + "'"
      } else t._1
      s" $k $op '$v' "
    }.mkString(" AND ")

    if(q.trim.length > 0) q = " AND " + q
    q
  }

  def find(
    realm:String,
    env:String,
    table:String,
    entityType:String,
    offset:Option[Long],
    limit:Long,
    query:Map[String,String],
    countOnly:Boolean = false)(implicit ctx: ECtx) = {

    ensureEntityTableExists(realm, table)

    val q = mapToQuery(realm, table, query)

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

    log(s"Postgress find $entityType:$query SQL $SQL")

    var count = 0L
    val res = new ListBuffer[HashMap[String, Any]]

    {
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

        if(!countOnly) {
          pstmt.setString(1, realm)
          pstmt.setString(2, env)
          pstmt.setString(3, entityType)

          lastSQL = SQL
          val rs = pstmt.executeQuery()

          while (rs.next()) {
            res append razie.js.parse(rs.getString(1))
          }
        }
      } catch {
        case t:Throwable => onError(t)
      } finally {
        if (pstmt1 != null) pstmt1.close()
        if (pstmt != null) pstmt.close()
      }
    }

    if(countOnly) (SQL1, "", count, res)
    else (SQL1, "n/a", count, res)
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

  /** get the referenced connection, without reconnecting */
  def currConn (implicit ctx: ECtx) =
    EEConnectors.get(realm, connectionName)
        .map(_.asInstanceOf[EPostgressConnector])

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
            EVal(new P(name, "", WTypes.wt.OBJECT.withSchema(DB)).withValue(x, WTypes.wt.OBJECT.withSchema(DB)))
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

          List(ETrace("Connection stopped"))
        }

        case "upsert" => {
          val conn = getConn

          val p = ctx.getRequiredp("document").calculatedP

          val (sql, l) = conn.upsertEntity(realm, env, coll, coll, newKey, p)

          ETrace("SQL", sql) :: l
        }

        case "query" => {

          val countOnly = ctx.get("countOnly").getOrElse("false").toBoolean
          val conn = getConn

          val (sql1, sql, count, resList) =
            conn.find(realm, env, coll, coll, getQFrom, getQSize, otherQueryParms(in), countOnly)

          ETrace("SQL1", sql1) ::
              ETrace("SQL", sql) ::
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
            ETrace("SQL", sql) ::
                res.toList.map { p =>
                  EVal(p.copy(name = Diesel.PAYLOAD))
                }
          } else {
            val p = ctx.getp("default").map(_.calculatedP)

            p.map { p =>
              val (sql2, l) = conn.upsertEntity(realm, env, coll, coll, key, p)

              ETrace("SQL", sql) :: ETrace("SQL", sql2) :: l
            }.getOrElse {
              // todo copy paste
              ETrace("SQL", sql) ::
                  res.toList.map { p =>
                    EVal(p.copy(name = Diesel.PAYLOAD))
                  }
            }
          }
        }

        case "remove" => {
          val conn = getConn

          if(in.attrs.exists(p=> p.name == "id" || p.name == "key")) {
            val (sql1, doc) = conn.findOne(realm, env, coll, coll, key)
            val (sql2, l) = conn.deleteEntity(realm, env, coll, coll, key)

            ETrace("SQL", sql2) :: l :::
                (doc
                    .orElse {
                      Some(new P("document", "", WTypes.wt.UNDEFINED))
                    }
                    .toList.map { p =>
                  EVal(p.copy(name = Diesel.PAYLOAD))
                })
          } else {
            val (sql2, l) = conn.deleteQuery(realm, env, coll, coll, otherQueryParms(in))

            ETrace("SQL", sql2) :: l
          }
        }

        case s@_ => {
          new EError(s"ctx.$s - unknown activity ") :: Nil
        }
      }
    } catch {
      case t: Throwable => {
        // any exception, force close to reconnect the connection
        val res = ETrace("Last SQL", currConn.map(_.lastSQL).mkString) ::
            new EError("Exception", t) :: Nil
        currConn.foreach(_.conn.close())
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

