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


object EEDieselElastic2Db {
  final val DB = "diesel.db.2elastic"
}

import razie.diesel.engine.exec.EEDieselElastic2Db.DB

/**
  * Elastic DB connector
  *
  * Sample URL: "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true"
  */
case class EElastic2BaseConnector (
  realm:String,
  val name:String,
  url:String,
  assigned:Boolean = false
) {

  var lastSQL = "n/a"

  final val TBL = "DieselDb"

  def connect = {
  }

  def reconnect = connect

  def stop: Unit = ???

  private val eTypes = new HashMap[String,String]()

  private def ensureEntityTableExists (name:String) = {
//    if(!eTypes.contains(name)) {
//      if(conn.getMetaData.getTables(null, null, name, null).next()) {
//        eTypes.put(name, name)
//      }
//    }
//
//    if(!eTypes.contains(name)) createEntityTable(name)
  }

  private def createEntityTable (name:String) = {
//    val SQL =
//      s"""
//         |CREATE TABLE IF NOT EXISTS $name (
//         |  realm      varchar(225) NOT NULL,
//         |  env        varchar(225) NOT NULL,
//         |  entityType varchar(225) NOT NULL,
//         |  entityId   varchar(225) NOT NULL,
//         |  content    JSON,
//         |  UNIQUE (realm, env, entityType, entityId)
//         |  )""".stripMargin
//
//    val ps = conn.prepareStatement(SQL)
//
//    try {
//      lastSQL = SQL
//      val res = ps.executeUpdate
//      clog << res
//      eTypes.put(name, name) // mark type as having table
//    } finally {
//      if (ps != null) ps.close()
//    }
  }

  def upsertEntity(realm:String, env:String, table:String, entityType:String, entityId:String, entity:String) = {
    ensureEntityTableExists(table)
    val SQL =
      s"""
         |INSERT INTO $table(realm,env,entityType,entityId,content)
         |VALUES(?,?,?,?,?)
         |ON CONFLICT (realm,env,entityType,entityId)
         |DO UPDATE SET content=EXCLUDED.content;
         |""".stripMargin

        val json = entity

        lastSQL = SQL

    (SQL, entityId)
  }

  def deleteEntity(realm:String, env:String, table:String, entityType:String, entityId:String) = {
    ensureEntityTableExists(table)

    val SQL =
      s"""
         |DELETE FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    (SQL, entityId)
  }

  def findOne(realm:String, env:String, table:String, entityType:String, entityId:String) = {
    ensureEntityTableExists(table)

    val SQL =
      s"""
         |SELECT content FROM $table
         |WHERE realm=? AND env=? AND entityType=? AND entityId=?
         |""".stripMargin

    var res = ""

        lastSQL = SQL

    (SQL, res)
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

        lastSQL = SQL
    (SQL, count, res)
  }

}

