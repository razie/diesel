package model

import com.mongodb.casbah.Imports._
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import razie.Log
import admin.Config
import admin.NogoodBase64Codec
import admin.NogoodCipherCrypt

package object RazSalatContext {

  implicit val ctx = new Context {
    val name = "When-Necessary-Context"
    override val typeHintStrategy = NeverTypeHint
  }
}

/** mongo db utils */
object Mongo {
  lazy val conn = MongoConnection(Config.mongohost)

  val AGAIN = false
  val CURR_VER = 11
  val upgrades = Map (1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5, 6 -> U6, 7 -> U7, 8->U8, 9->U9, 10->U10)

  lazy val db = {
    com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
    com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

    // authenticate
    val db = conn (Config.mongodb)
    if (!db.authenticate(Config.mongouser, Config.mongopass)) {
      Log.info("ERR_MONGO_AUTHD")
      throw new Exception("Cannot authenticate. Login failed.")
    }

    // upgrade if needed
    var dbVer = db("Ver").findOne.map(_.get("ver").toString).map(_.toInt)
    if (AGAIN) dbVer = dbVer.map(_ - 1)

    // if i don't catch - there's no ending since it's a lazy val init...
    try {
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
    } catch {
      case e: Throwable => {
        Log.error ("Exception during DB migration - darn thing won't work at all probably\n" + e, e)
        e.printStackTrace()
      }
    }

    // that's it, db initialized?
    db
  }

  def withDb[B](d: MongoCollection)(f: MongoCollection => B) = { f(d) }

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
  def withDb(d: MongoCollection)(f: MongoCollection => Unit) { f(d) }
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

// groups names and some users are lowercae
object Upgrade4 extends UpgradeDb {
  def upgrade(db: MongoDB) {
    withDb(db("UserGroup")) { t =>
      for (u <- t) {
        u.put ("name", toup(u.get("name").toString))
        t.save(u)
      }
    }

    withDb(db("User")) { t =>
      for (u <- t) {
        val a = u.get ("roles").asInstanceOf[BasicDBList]
        //        try {
        val aa = a.toArray map (x => toup(x.toString))
        u.put("roles", aa)
        t.save(u)
        //        } catch {
        //          case _ => t.remove(u)
        //        }
      }
    }

    withDb(db("WikiEntry")) { t =>
      for (u <- t if ("Category" == u.get("category") && "League" == u.get("name"))) {
        t.remove(u)
      }
    }
  }

  def toup(s: String) = s.substring(0, 1).toUpperCase + s.substring(1, s.length)
}

// rename all topics with bad names
object Upgrade5 extends UpgradeDb with razie.Logging {
  def name(u: DBObject) = u.get ("name").asInstanceOf[String]
  def nameo(u: DBObject) = u.get ("entry").asInstanceOf[DBObject].get("name").asInstanceOf[String]

  import model.RazSalatContext._
    
  def upgrade(db: MongoDB) {
    withDb(db("WikiEntry")) { t =>
      for (u <- t if ("WikiLink" != u.get("category") && Wikis.formatName(name(u)) != name(u))) {
        log("UPGRADING " + name(u))
        u.put("name", Wikis.formatName(name(u)))
        t.save(u)
      }
    }
    withDb(db("WikiEntryOld")) { t =>
      for (u <- t if ("WikiLink" != u.get("category") && Wikis.formatName(nameo(u)) != nameo(u))) {
        log("UPGRADING " + nameo(u))
        u.get("entry").asInstanceOf[DBObject].put("name", Wikis.formatName(nameo(u)))
        t.save(u)
      }
    }

    withDb(db("Profile")) { t =>
      for (
      po <- t;
      p <- Some(grater[Profile].asObject(po)) if (!p.perms.contains("+eVerified"))) {
        db("UserTask") += grater[UserTask].asDBObject(UserTask(p.userId, "verifyEmail"))
      }
    }
    
//    withDb(db("User")) { t =>
//      for ( u <- t if(!u.containsKey("email"))) {
//        u.put("email", Enc("r@racerkidz.com"))
//        t.save(u)
//      }
//    }
  }

}

// change base64 encoding to url safe
object U6 extends UpgradeDb with razie.Logging {
  import model.RazSalatContext._
    
  def upgrade(db: MongoDB) {
    withDb(db("User")) { t =>
      for ( u <- t) {
        u.put("email", Enc(new NogoodCipherCrypt().decrypt(u.as[String]("email"))))
        u.put("pwd", Enc(new NogoodCipherCrypt().decrypt(u.as[String]("pwd"))))
        t.save(u)
      }
    }
  }
}

  // add "role" to UserWiki
object U7 extends UpgradeDb with razie.Logging {
  import model.RazSalatContext._
    
  def upgrade(db: MongoDB) {
    withDb(db("UserWiki")) { t =>
      for ( u <- t) {
        u.put("role", "Racer")
        t.save(u)
      }
    }
  }
}

case class OldUserWiki8(userId: ObjectId, cat: String, name: String, role: String) { }

// switch to WID
object U8 extends UpgradeDb with razie.Logging {
  import model.RazSalatContext._
  
  def upgrade(db: MongoDB) {
    withDb(db("UserWiki")) { t =>
      for ( u <- t) {
        val old = grater[OldUserWiki8].asObject(u)
        t.remove(u)
        t += grater[UserWiki].asDBObject(UserWiki(old.userId, WID(old.cat, old.name), old.role: String))
      }
    }
  }
}

// rename Season category to Calendar
object U9 extends UpgradeDb with razie.Logging {
  import model.RazSalatContext._

  final val S = "Season"
  final val C = "Calendar"
    
  def upgrade(db: MongoDB) {
    
    withDb(db("WikiEntry")) { t =>
      for (u <- t if ("Category" == u.get("category") && S == u.get("name"))) {
        log("UPGRADING " + u)
        u.put("name", C)
        t.save(u)
      }
    }
    withDb(db("WikiEntry")) { t =>
      for (u <- t if (S == u.get("category"))) {
        log("UPGRADING " + u)
        u.put("category", C)
        t.save(u)
      }
    }
    withDb(db("WikiEntryOld")) { t =>
      for (u <- t if (C == u.get("category") && S == u.get("entry.name"))) {
        log("UPGRADING " + u)
        u.get("entry").asInstanceOf[DBObject].put("name", C)
        t.save(u)
      }
    }
    withDb(db("WikiEntryOld")) { t =>
      for (u <- t if (S == u.get("category"))) {
        log("UPGRADING " + u)
        u.put("category", C)
        u.get("entry").asInstanceOf[DBObject].put("category", C)
        t.save(u)
      }
    }

    withDb(db("UserWiki")) { t =>
      for ( u <- t if(S == u.get("wid").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("wid").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }
    
    withDb(db("WikiLink")) { t =>
      for ( u <- t if(S == u.get("from").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("from").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }
    withDb(db("WikiLink")) { t =>
      for ( u <- t if(S == u.get("to").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("to").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }
    
    log ("+++++++++++++ UPGRADE REPORT - list of wikis to update:")
    withDb(db("WikiEntry")) { t =>
      for (u <- t) {
        if (u.get("content").asInstanceOf[String].contains("Season:"))
          log("  " + u.get("name"))
      }
    }
  }
}

// rename all topics with bad names
object U10 extends UpgradeDb with razie.Logging {

  def name(u: DBObject) = u.get ("name").asInstanceOf[String]
  def nameo(u: DBObject) = u.get ("entry").asInstanceOf[DBObject].get("name").asInstanceOf[String]
  
  def uid (uname:String) = users.get(uname) orElse users.get("Razie")
  val users = scala.collection.mutable.Map[String, ObjectId]()
    
  import model.RazSalatContext._
    
  def upgrade(db: MongoDB) {
    
     withDb(db("User")) { t =>
      for ( u <- t) {
        users.put(u.as[String]("userName"), u._id.get)
        users.put(u._id.get.toString, u._id.get)
      }
    }

    withDb(db("WikiEntry")) { t =>
      for (u <- t) {
        log("UPGRADING " + name(u))
        val userid = uid(u.as[Any]("by").toString)
        u.remove("by")
        u.put("by", userid)
        t.save(u)
      }
    }
    withDb(db("WikiEntryOld")) { t =>
      for (u <- t) {
        log("UPGRADING " + nameo(u))
        val userid = uid(u.get("entry").asInstanceOf[DBObject].as[Any]("by").toString)
        u.get("entry").asInstanceOf[DBObject].remove("by")
        u.get("entry").asInstanceOf[DBObject].put("by", userid)
        t.save(u)
      }
    }
  }
}

