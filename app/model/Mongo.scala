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

  val CURR_VER = 4
  val upgrades = Map (1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3)

  lazy val db = {
    com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
    com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

    // authenticate
    val db = conn (System.getProperty("mongodb", "rk"))
    if (!db.authenticate(System.getProperty("mongouser", "r"), System.getProperty("mongopass", "r"))) {
      Log.info("ERR_MONGO_AUTHD")
      throw new Exception("Cannot authenticate. Login failed.")
    }

    // upgrade if needed
    val dbVer = db("Ver").findOne.map(_.get("ver").toString).map(_.toInt)
    dbVer match {
      case Some(v) => {
        var ver = v
        while (ver < CURR_VER && upgrades.contains(ver)) {
          upgrades.get(ver).map { u =>
            Log.audit ("UPGRADING DB from ver " + ver)
            u.upgrade(db)
            db("Ver").update(Map("ver" -> ver), Map("ver" -> CURR_VER))
            Log.audit ("UPGRADING DB... DONE")
            ver += 1
          } getOrElse { Log.error("NO UPGRADES FROM VER " + ver) }
        }
      }
      case None => db("Ver") += Map("ver" -> CURR_VER) // create a first ver entry
    }

    // that's it, db initialized?
    db
  }

  def count(table: String) = db(table).count

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

/** derive from here and list in upgrades above */
abstract class UpgradeDb {
  def upgrade(db: MongoDB): Unit
}

object Upgrade1 extends UpgradeDb {
  def upgrade(db: MongoDB) {}
}

// add roles to user
object Upgrade2 extends UpgradeDb {
  def upgrade(db: MongoDB) {
    val t = db("User")
    for (u <- t) {
      u.put ("roles", Array(u.removeField("userType")))
      t.save(u)
    }
  }
}

// drop the wiki old table - new structure
object Upgrade3 extends UpgradeDb {
  def upgrade(db: MongoDB) {
    db.getCollection("WikiEntryOld").drop
  }
}
