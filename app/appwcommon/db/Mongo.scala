/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package db

import com.mongodb.casbah.Imports.DBObject
import com.mongodb.casbah.Imports.MongoCollection
import com.mongodb.casbah.Imports.MongoConnection
import com.mongodb.casbah.Imports.MongoDB
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.novus.salat.Context
import com.novus.salat.NeverTypeHint
import admin.WikiConfig
import razie.Log
import admin.Services
import razie.cout

/** don't remember why i need this... but i somehow do... */
package object RazSalatContext {
  implicit val ctx = new Context {
    val name = "When-Necessary-Context"
    override val typeHintStrategy = NeverTypeHint
  }
}

/** mongo db utils */
object Mongo {
  val AGAIN = false

  /** the actual database - done this way to run upgrades before other code uses it */
  lazy val db = {
    com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
    com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

    // create DB and authenticate etc
    val db = Services.mkDb()

    // upgrade if needed
    var dbVer = db("Ver").findOne.map(_.get("ver").toString).map(_.toInt)
    if (AGAIN) dbVer = dbVer.map(_ - 1)

    // if i don't catch - there's no ending since it's a lazy val init...
    try {
      dbVer match {
        case Some(v) => {
          var ver = v
          while (ver < Services.mongoDbVer && Services.mongoUpgrades.contains(ver)) {
            Services.mongoUpgrades.get(ver).map { u =>
              cout << "1 "+Thread.currentThread().getName()
              Log audit s"UPGRADING DB from ver $ver to ${Services.mongoDbVer}"
              Thread.sleep(2000) // often screw up and goes in  a loop...
              u.upgrade(db)
              db("Ver").update(Map("ver" -> ver), Map("ver" -> Services.mongoDbVer))
              Log.audit ("UPGRADING DB... DONE")
            } getOrElse { Log.error("NO UPGRADES FROM VER " + ver) }
            ver = ver+1
          }
        }
        case None => db("Ver") += Map("ver" -> Services.mongoDbVer) // create a first ver entry
      }
    } catch {
      case e: Throwable => {
        Log.error ("Exception during DB migration - darn thing won't work at all probably\n" + e, e)
        e.printStackTrace()
      }
    }

    // that's it, db initialized?
    db
  }

  /** important during the upgrades themselves - you can't recursively use db */
  def withDb[B](d: MongoCollection)(f: MongoCollection => B) = { f(d) }

  def count(table: String) = db(table).size

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
  def upgrade (db: MongoDB): Unit
  def withDb (d: MongoCollection)(f: MongoCollection => Unit) { f(d) }
}

