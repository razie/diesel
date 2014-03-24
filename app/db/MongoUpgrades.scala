package db

import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.annotations._
import model.Wikis
import model.Profile
import model.UserTask
import model.Enc
import admin.NogoodCipherCrypt
import admin.Config
import model.RacerKidz
import razie.clog
import razie.cout
import model.RacerKidAssoc
import model.RK
import model.WikiLink
import model.UserWiki
import model.WID
import controllers.Club
import model.Users

object Upgrade1 extends UpgradeDb {
  def upgrade(db: MongoDB) {}
}

// add roles to user
object Upgrade2 extends UpgradeDb {
  def upgrade(db: MongoDB) {
    val t = db("User")
    for (u <- t) {
      u.put("roles", Array(u.removeField("userType")))
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
        u.put("name", toup(u.get("name").toString))
        t.save(u)
      }
    }

    withDb(db("User")) { t =>
      for (u <- t) {
        val a = u.get("roles").asInstanceOf[BasicDBList]
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
  def name(u: DBObject) = u.get("name").asInstanceOf[String]
  def nameo(u: DBObject) = u.get("entry").asInstanceOf[DBObject].get("name").asInstanceOf[String]

  import db.RazSalatContext._

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
        p <- Some(grater[Profile].asObject(po)) if (!p.perms.contains("+eVerified"))
      ) {
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
  import db.RazSalatContext._

  def upgrade(db: MongoDB) {
    withDb(db("User")) { t =>
      for (u <- t) {
        u.put("email", Enc(new NogoodCipherCrypt().decrypt(u.as[String]("email"))))
        u.put("pwd", Enc(new NogoodCipherCrypt().decrypt(u.as[String]("pwd"))))
        t.save(u)
      }
    }
  }
}

// add "role" to UserWiki
object U7 extends UpgradeDb with razie.Logging {
  import db.RazSalatContext._

  def upgrade(db: MongoDB) {
    withDb(db("UserWiki")) { t =>
      for (u <- t) {
        u.put("role", "Racer")
        t.save(u)
      }
    }
  }
}

case class OldUserWiki8(userId: ObjectId, cat: String, name: String, role: String) {}

// switch to WID
object U8 extends UpgradeDb with razie.Logging {
  import db.RazSalatContext._

  def upgrade(db: MongoDB) {
    withDb(db("UserWiki")) { t =>
      for (u <- t) {
        val old = grater[OldUserWiki8].asObject(u)
        t.remove(u)
        t += grater[model.UserWiki].asDBObject(model.UserWiki(old.userId, model.WID(old.cat, old.name), old.role: String))
      }
    }
  }
}

// rename Season category to Calendar
object U9 extends UpgradeDb with razie.Logging {
  import db.RazSalatContext._

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
      for (u <- t if (S == u.get("wid").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("wid").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }

    withDb(db("WikiLink")) { t =>
      for (u <- t if (S == u.get("from").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("from").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }
    withDb(db("WikiLink")) { t =>
      for (u <- t if (S == u.get("to").asInstanceOf[DBObject].get("cat"))) {
        log("UPGRADING " + u)
        u.get("to").asInstanceOf[DBObject].put("cat", C)
        t.save(u)
      }
    }

    log("+++++++++++++ UPGRADE REPORT - list of wikis to update:")
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

  def name(u: DBObject) = u.get("name").asInstanceOf[String]
  def nameo(u: DBObject) = u.get("entry").asInstanceOf[DBObject].get("name").asInstanceOf[String]

  def uid(uname: String) = users.get(uname) orElse users.get("Razie")
  val users = scala.collection.mutable.Map[String, ObjectId]()

  import db.RazSalatContext._

  def upgrade(db: MongoDB) {

    withDb(db("User")) { t =>
      for (u <- t) {
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

// some model udpates
object U11 extends UpgradeDb with razie.Logging {

  def name(u: DBObject) = u.get("name").asInstanceOf[String]

  def un(uid: ObjectId) = users.get(uid).get
  val users = scala.collection.mutable.Map[ObjectId, String]()

  var ran = false

  import db.RazSalatContext._

  def upgrade(db: MongoDB) {
    ran = true

    // load user name/id
    clog << "Upgrading U11 - Users"
    withDb(db("User")) { t =>
      for (u <- t) {
        users.put(u._id.get, u.as[String]("userName"))
      }
    }

    clog << "Upgrading U11 - Club"
    withDb(db("Club")) { t =>
      for (u <- t) {
        val userName = un(u.as[ObjectId]("userId"))
        u.put("userName", userName)
        t.save(u)
      }
    }

    clog << "Upgrading U11 - RacerKidAssoc"
    withDb(db("RacerKidAssoc")) { t =>
      for (u <- t) {
        u.put("year", Config.curYear)
        t.save(u)
      }
    }
  }

  // requires db setup - needs called separately, see Global.scala
  def upgradeRk(db: MongoDB) {
    if (ran) { // only if this upgrade ran this time
      withDb(db("User")) { t =>
        for (u <- t) {
          RacerKidz.checkMyself(u._id.get)
        }
      }

      RMany[model.UserWiki]().filter(_.wid.cat == "Club").foreach { uw =>
        val rk = model.RacerKidz.myself(uw.userId)
        cout << uw
        val c = controllers.Club(uw.wid.name)
        c.foreach { c =>
          model.RacerKidAssoc(c.userId, rk._id, model.RK.ASSOC_LINK, uw.role, c.userId).create
        }
      }

      ran = false // make sure it's not run in this jvm anymore - ever, basically
    }
  }

  // requires db setup - needs called separately, see Global.scala
  def upgradeRaz(db: MongoDB) {
    val r = new ObjectId("4fae5f2b0cf23a3faa46794f")
    val to = RacerKidz.myself(new ObjectId("4fae5f2b0cf23a3faa46794d"))
    if (RMany[RacerKidAssoc]("from" -> r, "to" -> to._id).isEmpty) {
      RacerKidAssoc(r, RacerKidz.myself(new ObjectId("4fae5f2b0cf23a3faa46794d"))._id, RK.ASSOC_CHILD, RK.ROLE_KID, r).create
      RacerKidAssoc(r, RacerKidz.myself(new ObjectId("4fae5f2b0cf23a3faa46794e"))._id, RK.ASSOC_CHILD, RK.ROLE_KID, r).create
    } else {
      ROne[RacerKidAssoc]("from" -> r, "to" -> RacerKidz.myself(new ObjectId("4fae5f2b0cf23a3faa46794d"))._id).map(_.copy(assoc = RK.ASSOC_CHILD).update)
      ROne[RacerKidAssoc]("from" -> r, "to" -> RacerKidz.myself(new ObjectId("4fae5f2b0cf23a3faa46794e"))._id).map(_.copy(assoc = RK.ASSOC_CHILD).update)
    }
  }

  // add crDtm to WikiLinks
  def upgradeWL(db: MongoDB) {
    cout << "=======================DB updating "
    RMany[WikiLink]().toList.map { wl =>
      cout << " 1 DB updating " + wl.toString
      RUpdate[WikiLink](wl) // use now()
      wl.pageFrom.map { p =>
        cout << " 2 DB updating " + (wl.copy(crDtm = p.crDtm)).toString
        RUpdate[WikiLink](wl.copy(crDtm = p.crDtm))
      }
    }
  }

  // add crDtm to WikiLinks
  def upgradeGlacierForums() {
    var removed = RMany[UserWiki]().toList.filter(uw => uw.role != "Owner" && uw.wid.cat == "Blog" && uw.wid.name.startsWith("Glacier")).map { uw =>
      cout << " 1 DB remove " + uw.toString
      uw.delete
    }.size
    cout << "=======================DB removing " + removed
    if (removed > 1) {
      var cnt = 0
      Club("Glacier_Ski_Club").foreach { club =>
        club.userLinks.toList.filter(uw => uw.role != "Owner").map { uw =>
          club.newFollows.foreach { rw =>
            val role = rw.role
            val newuw = model.UserWiki(uw.userId, rw.wid, role)
            newuw.create
            cout << " 1 DB creating " + "   ===>>>   " + newuw.toString
            cnt = cnt + 1
          }
        }
      }
      cout << "=======================DB creating " + cnt
    }
  }

  // add crDtm to WikiLinks
  private def clubFollowsWid(cname: String, wpath: String, role: String) {
    val wid = WID.fromPath(wpath).get
    var cnt = 0
    var all = 0
    Club(cname).foreach { club =>
      club.userLinks.toList.filter(uw => uw.role != "Owner").map { uw =>
        all = all + 1
        Users.findUserById(uw.userId).filter(! _.wikis.exists(_.wid== wid)).foreach { rw =>
          val newuw = model.UserWiki(uw.userId, wid, role)
          newuw.create
          cout << " 1 DB creating " + "   ===>>>   " + newuw.toString
          cnt = cnt + 1
        }
      }
    }
    cout << s"=======================DB created $cnt of $all"
  }

  // add crDtm to WikiLinks
  def upgradeGlacierForums2() {
    val cname = "Glacier_Ski_Club"
    val wpath = "Club:Glacier_Ski_Club/Forum:Glacier_Rides_and_Stuff"
    val role = "Contributor" // "Fan"

    clubFollowsWid(cname, wpath, role)
  }

}
