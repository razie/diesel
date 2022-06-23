package admin

import com.mongodb.casbah.Imports._
import com.novus.salat._
import controllers.Club
import mod.snow.{RK, RacerKidAssoc, RacerKidz}
import model._
import razie.cout
import razie.db._
import razie.db.tx.txn
import razie.hosting.WikiReactors
import razie.wiki.{Config, Enc}
import razie.wiki.model._
import scala.collection.mutable.ListBuffer

/******************************************
  *
  * YOU CANNOT DELETE THEESE
  *
  * the reason is that you can restore old databases...
  *
  *****************************************/

// WID replaced wiht UWID
object U14 extends UpgradeDb with razie.Logging {
  import razie.db.RazSalatContext._

  val missed = ListBuffer[String]()

  def upgrade(db: MongoDB) {

    def getuwid (wid:WID)(implicit t:MongoCollection) = {
      val td = db(Wikis.rk.weTables(wid.cat))
      val res = if (wid.parent.isDefined)
        td.findOne(Map("category" -> wid.cat, "name" -> wid.name, "parent" -> wid.parent.get))
      else
        td.findOne(Map("category" -> wid.cat, "name" -> Wikis.formatName(wid.name)))

      res.map(r=>UWID(wid.cat, r.as[ObjectId]("_id"))) getOrElse {
        missed append (s"${t.name} for ${wid.cat} ${wid.name}")
        UWID("?", new ObjectId())
      }
    }

    var i = 0;

    withDb(db("FollowerWiki")) { implicit t =>
      for (u <- t if(u.contains("wid"))) {
        cdebug << "UPGRADING " + t.name + u
        val uwid = getuwid (grater[WID].asObject(u.as[BasicDBObject]("wid")))
        u.remove("wid")
        u.put("uwid", uwid.grated)
        t.save(u)
        i = i+1
      }
    }

    withDb(db("WikiLink")) { implicit t =>
      for (u <- t) {
        cdebug << "UPGRADING " + t.name + u
        val uf = getuwid (grater[WID].asObject(u.as[DBObject]("from")))
        val ut = getuwid (grater[WID].asObject(u.as[DBObject]("to")))
        u.remove("from")
        u.remove("to")
        u.put("from", uf.grated)
        u.put("to", ut.grated)
        t.save(u)
        i = i+1
      }
    }

    withDb(db("UserWiki")) { implicit t =>
      for (u <- t if(u.contains("wid"))) {
        cdebug << "UPGRADING " + t.name + u
        val uwid = getuwid (grater[WID].asObject(u.as[DBObject]("wid")))
        u.remove("wid")
        u.put("uwid", uwid.grated)
        t.save(u)
        i = i+1
      }
    }

    clog < s"""MISSED ${missed.size} : \n${missed.mkString("\n")} """
    clog < s"UPGRADED $i entries"
  }
}

// share tag to shared
object U15 extends UpgradeDb with razie.Logging {

  def upgrade(db: MongoDB) {
    var i = 0;

    withDb(db("weNote")) { implicit t =>
      for (u <- t;
           c <- u.getAs[String]("content") if (c.contains(".share "))) {
        cdebug << "UPGRADING " + t.name + u
        u.put("content", c.replaceAll(".share ", ".shared "))
        t.save(u)
        i = i+1
      }
    }

    clog < s"UPGRADED $i entries"
  }
}

// realm to notes
object U16 extends UpgradeDb with razie.Logging {

  def upgrade(db: MongoDB) {
    var i = 0;

    withDb(db("weNote")) { implicit t =>
      for (u <- t) {
        cdebug << "UPGRADING " + t.name + u
        u.put("realm", "notes")
        t.save(u)
        i = i+1
      }
    }

    clog < s"UPGRADED $i entries"
  }
}

// UWID gets realm
object U17 extends UpgradeDb with razie.Logging {
  import razie.db.RazSalatContext._

  val missed = ListBuffer[String]()

  def upgrade(db: MongoDB) {

    def fixuwid (u:UWID)(implicit t:MongoCollection) = {
      db("WikiEntry").findOne(Map("_id" -> u.id)).map(grater[WikiEntry].asObject(_)).map(_.uwid).filter(_.realm.isDefined) // only update those with a realm
    }

    var i = 0;

    def fix (table:String, col:String) = {
      withDb(db(table)) { implicit t =>
        for (u <- t if(u.contains(col))) {
          cdebug << "UPGRADING " + t.name + u
          val uwid = fixuwid (grater[UWID].asObject(u.as[BasicDBObject](col)))
          uwid.foreach { uw =>
            u.put(col, uw.grated)
            t.save(u)
            i = i + 1
          }
        }
      }
    }

    fix("FollowerWiki", "uwid")
    fix("WikiLink", "from")
    fix("WikiLink", "to")
    fix("UserWiki", "uwid")

    clog < s"""MISSED ${missed.size} : \n${missed.mkString("\n")} """
    clog < s"UPGRADED $i entries"
  }
}

// Autosave more explicit
// realm to notes
object U18 extends UpgradeDb with razie.Logging {

  def upgrade(db: MongoDB) {
    var i = 0;

    withDb(db("Autosave")) { implicit t =>
      for (u <- t) {
        cdebug << "UPGRADING " + t.name + u
        val n =
          if(u.containsField("oldname"))
            u.get("oldname").toString
          else
            u.get("name").toString

        val a = n.split ("\\.")

        val name =
          if(a.size > 2) a(2)
          else ""

        u.put("what", a(0))
        u.put("realm", a(1))
        u.put("name", name)
        u.put("oldname", n)
        t.save(u)
        i = i+1
      }
    }

    clog < s"UPGRADED $i entries"
  }
}

object U19 extends UpgradeDb with razie.Logging {

  def upgrade(db: MongoDB) {
    var i = 0;

    withDb(db("Comment")) { implicit t =>
      for (u <- t) {
        cdebug << "UPGRADING " + t.name + u
        val n =
            u.get("userId").toString

        val s = db("User").findOneByID(new ObjectId(n)).map(_.get("userName").toString)
        u.put("userName", s)
        t.save(u)
        i = i+1
      }
    }

    clog < s"UPGRADED $i entries"
  }
}

object MoreUpgrades {

  def sayHi = razie.cout << "Applying more upgrades"

  razie.db.tx("upgrades", "?") { implicit txn =>

    RazMongo.upgradeMaybe("upgradeUserRealms3", Array.empty) {
      RMany[User]().foreach { u =>

        val newUser = u.copy(
          perms = Set()
        )
        RUpdate.noAudit(newUser)
      }
    }

    RazMongo.upgradeMaybe("upgradeUserApiKey", Array.empty) {
      RMany[User]().foreach { u =>

        val newUser = u.copy(
          apiKey = Some(new ObjectId().toString)
        )
        RUpdate.noAudit(newUser)
      }
    }

    // end of upgrades
  }
}