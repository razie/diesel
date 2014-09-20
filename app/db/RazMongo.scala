/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package db

import admin.Services
import com.mongodb.casbah.Imports.{DBObject, MongoCollection, MongoDB, map2MongoDBObject}
import com.novus.salat.{Context, NeverTypeHint}
import razie.{Log, cout}

import scala.util.Try

/** don't remember why i need this... but i somehow do... */
package object RazSalatContext {
  implicit val ctx = new Context {
    val name = "When-Necessary-Context"
    override val typeHintStrategy = NeverTypeHint
  }
}

/** mongo db utils */
object RazMongo {
  val UPGRADE_AGAIN = false

  /** the actual database - done this way to run upgrades before other code uses it */
  private lazy val db = {
    com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
    com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

    // create DB and authenticate etc
    val db = Services.mkDb()

    // upgrade if needed
    var dbVer = db("Ver").findOne.map(_.get("ver").toString).map(_.toInt)
    if (UPGRADE_AGAIN) dbVer = dbVer.map(_ - 1)

    var upgradingLoop = false // simple recursive protection

    // if i don't catch - there's no ending since it's a lazy val init...
    try {
      dbVer match {
        case Some(v) => {
          var ver = v
          while (ver < Services.mongoDbVer && Services.mongoUpgrades.contains(ver)) {
            if(upgradingLoop)
              throw new IllegalStateException("already looping to update - recursive DB usage while upgrading, check code")
            upgradingLoop = true
            Services.mongoUpgrades.get(ver).fold (
              Log.error("NO UPGRADES FROM VER " + ver)
            ) { u =>
              cout << "1 " + Thread.currentThread().getName()
              Log audit s"UPGRADING DB from ver $ver to ${Services.mongoDbVer}"
              Thread.sleep(2000) // often screw up and goes in  a loop...
              u.upgrade(db)
              db("Ver").update(Map("ver" -> ver), Map("ver" -> Services.mongoDbVer))
              Log.audit("UPGRADING DB... DONE")
            }
            ver = ver + 1
            upgradingLoop = false
          }
        }
        case None => db("Ver") += Map("ver" -> Services.mongoDbVer) // create a first ver entry
      }
    } catch {
      case e: Throwable => {
        Log.error("Exception during DB migration - darn thing won't work at all probably\n" + e, e)
        e.printStackTrace()
      }
    }

    // that's it, db initialized?
    db
  }

  /** important during the upgrades themselves - you can't recursively use db */
  def withDb[B](d: MongoCollection, reason:String="?")(f: MongoCollection => B) = 
    dbop(s"withDb ${d.name} reason=$reason") {
      f(d);
    }

  def collectionNames = db.collectionNames filter (!_.startsWith("system."))

  def apply(table: String) = new Table(table)

  /** wrap all access to the DB object */
  case class Table(table: String) {
    lazy val m = db(table)

    def exists = db.collectionExists(table)
    def drop = db.getCollection(table).drop
    def size = {
      m.size
    }
    def count(pairs: Map[String, Any]) = m.count(pairs)

    def +=(o: DBObject) = {
      m += o
    }

    def +=(pairs: Map[String, Any]) = dbop("create " + table + " " + pairs.mkString(",")) {
      val x: DBObject = pairs
      m += x
    }

    def findAll() = dbop("findAll " + table) {
      m.find()
    }

    def find(pairs: Map[String, Any]) = dbop("find " + table + " " + pairs.mkString(",")) {
      val x: DBObject = pairs
      retry (1) {
        m.find(x)
      }
    }

    def findOne(pairs: Map[String, Any]) = dbop("findOne " + table + " " + pairs.mkString(",")) {
      val x: DBObject = pairs
      retry (1) {
        m.findOne(x)
      }
    }

    def remove(pairs: Map[String, Any]) = dbop("remove " + table + " " + pairs.mkString(",")) {
      m.remove(pairs)
    }

    def update(pairs: Map[String, Any], o: DBObject) = dbop("update " + table + " " + pairs.mkString(",")) {
      m.update(pairs, o)
    }

  }

  /** generic retry of idempotent operations (find/read)
    *
    * @param i how many times to retry
    * @param f the function to retry
    * @param ex internal recursive state
    * @tparam T whatever return type of f
    * @return
    */
  def retry[T] (i:Int) (f: => T) : T = {
    iretry (i) (f) (None)
  }

  private def iretry[T] (i:Int) (f: => T) (ex:Option[Throwable] = None) : T = {
    if(i <= 0)
      throw ex.map { e=>
        new RuntimeException(e)
      } getOrElse {
        new IllegalStateException("can't retry zero times")
      }
    val res = Try {
        f
      } recover {
        case e : com.mongodb.MongoException.Network => {
          Thread.sleep(500)
          iretry (i-1) (f) (Some(e))
        }
        case x@_ => iretry (0) (f) (Some(x))
      }
    res.get
  }
}

/** derive from here and list in upgrades above */
abstract class UpgradeDb {
  def upgrade(db: MongoDB): Unit
  def withDb(d: MongoCollection)(f: MongoCollection => Unit) { f(d) }
}

