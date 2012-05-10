package model

import com.mongodb.casbah.Imports._
import admin.Audit
import com.novus.salat._
import razie.Log

package object RazSalatContext {

  implicit val ctx = new Context {
    val name = "When-Necessary-Context"
    override val typeHintStrategy = NeverTypeHint
  }
}

/** mongo db utils */
object Mongo {
  lazy val conn = MongoConnection(System.getProperty("mongohost", "localhost"))

  final val CURR_VER = 2
  final val upgrades = Map (1 -> Upgrade1)

  lazy val db = {
    com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
    com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

    // authenticate
    val db = conn (System.getProperty("mongodb", "rk"))
    if (!db.authenticate(System.getProperty("mongouser", "r"), System.getProperty("mongopass", "r"))) {
      razie.Log.info("ERR_MONGO_AUTHD")
      throw new Exception("Cannot authenticate. Login failed.")
    }

    // upgrade if needed
    val dbVer = db("Ver").findOne.map(_.as[Int]("ver"))
    dbVer match {
      case Some(v) => if (v < CURR_VER) {
        upgrades.get(v).map { u =>
          Log.audit ("UPGRADING DB from ver " + v)
          u.upgrade
          db("Ver").update(Map("ver" -> v), Map("ver" -> CURR_VER))
          Log.audit ("UPGRADING DB... DONE")
        } getOrElse { Log.error("NO UPGRADES FROM VER " + v) }
      }
      case None => db("Ver") += Map("ver" -> CURR_VER)
    }

    // that's it, db initialized?
    db
  }

  def count (table:String) = db(table).count
  
  def apply(table: String) = new Table(table)

  case class Table(table: String) {
    val m = db(table)
    def +=(o: DBObject) = {
      m += o
    }

    def +=(pairs: Map[String, Any]) = {
      val x: DBObject = pairs
      m += x
    }

    def find(pairs: Map[String, Any]) = {
      val x: DBObject = pairs
      m.find(x)
    }

    def findOne(pairs: Map[String, Any]) = {
      val x: DBObject = pairs
      m.findOne(x)
    }
  }
}

abstract class UpgradeDb(val fromVer: Int) {
  def upgrade: Boolean
}

object Upgrade1 extends UpgradeDb(1) {
  def upgrade = true
}
