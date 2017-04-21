/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import admin._
import com.google.inject.AbstractModule
import com.mongodb.casbah.{MongoConnection, MongoDB}
import com.mongodb.casbah.Imports._
import controllers.{ModRkExec, RazWikiAuthorization, RkViewService, ViewService}
import mod.diesel.controllers.{DieselMod, FiddleMod}
import model.WikiUsersImpl
import razie.base.Audit
import razie.db.{RMongo, RazMongo, UpgradeDb}
import razie.diesel.ext.Executors
import razie.wiki.admin.SendEmail
import razie.wiki.mods.WikiMods
import razie.wiki.{EncryptService, Services}
import razie.wiki.model.{WikiReactors, WikiUsers}
import razie.wiki.util.AuthService
import razie.{Log, clog, cout}

/** NOT WORKING !!!!!!!!!! */
class Module extends AbstractModule {
  def configure() = {
    clog << "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ configure"

    Services.auth = new RazAuthService ()
    bind(classOf[AuthService[_]]).toInstance(Services.auth)

    Services.config = Config

    mongoInit()

    // init after DB because it needs DB
    Audit.impl = new MdbAuditService
    RMongo.setInstance(Audit.impl)

    WikiUsers.impl = WikiUsersImpl

    EncryptService.impl = admin.CypherEncryptService
    ViewService.impl = RkViewService
    Services.wikiAuth = RazWikiAuthorization

    //todo look these up in Website
    Services.isSiteTrusted = {s=>
      Config.trustedSites.exists(x=>s.startsWith(x))
    }

    mod.diesel.model.RDExt.init
    WikiMods register new FiddleMod
    WikiMods register new DieselMod

    Executors.add (ModRkExec)

//    WikiReactors.apply("rk") // weird stuff happens to diesel parser if I do this
    Audit.logdb("NODE_RESTARTED", Services.config.node)
  }

  def mongoInit(): Unit = {
    /************** MONGO INIT *************/
    RazMongo.setInstance {
      val UPGRADE_AGAIN = false // only the last upgrade
      val mongoUpgrades: Map[Int, UpgradeDb] = Map(
          1 -> Upgrade1, 2 -> Upgrade2, 3 -> Upgrade3, 4 -> Upgrade4, 5 -> Upgrade5,
          6 -> U6, 7 -> U7, 8 -> U8, 9 -> U9, 10 -> U10, 11 -> U11, 12 -> U12, 13 -> U13,
          14 -> U14, 15 -> U15, 16 -> U16, 17 -> U17) /* NOTE as soon as you list it here, it will apply */

      def mongoDbVer = mongoUpgrades.keySet.max + 1

      lazy val conn = MongoConnection(admin.Config.mongohost)

      /** the actual database - done this way to run upgrades before other code uses it */
      com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
      com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

      // authenticate
      val db = conn(Config.mongodb)
      if (!db.authenticate(Config.mongouser, admin.Config.mongopass)) {
        clog << "ERR_MONGO_AUTHD"
        throw new Exception("Cannot authenticate. Login failed.")
      }

      //upgrading db version if needed
      def prep(adb:MongoDB) = {
        // upgrade if needed
        var curs = adb("Ver").find(Map("name" -> "version"))
        var ver = if(curs.hasNext) curs.next() else {
          // initialize new database, nothign to upgrade
          // todo antipattern - should not auto-initialize the database
          adb("Ver").insert(Map("name" -> "version", "ver" -> mongoDbVer))
          adb("Ver").find(Map("name" -> "version")).next
        }

        var dbVer : Option[Int] = Option(ver.get("ver").toString.toInt)
        if (UPGRADE_AGAIN) dbVer = dbVer.map(x => mongoDbVer - 1)

        var upgradingLoop = false // simple recursive protection

        // if i don't catch - there's no ending since it's a lazy val init...
        try {
              var ver = dbVer.get
              while (ver < mongoDbVer && mongoUpgrades.contains(ver)) {
                if(upgradingLoop)
                  throw new IllegalStateException("already looping to update - recursive DB usage while upgrading, check code")
                upgradingLoop = true
                mongoUpgrades.get(ver).fold (
                  Log.error("NO UPGRADES FROM VER " + ver)
                ) { u =>
                  cout << "1 " + Thread.currentThread().getName()
                  Log audit s"UPGRADING DB from ver $ver to ${mongoDbVer}"
                  Thread.sleep(2000) // often screw up and goes in  a loop...
                  u.upgrade(adb)
                  adb("Ver").update(Map("ver" -> ver), Map("ver" -> mongoDbVer))
                  Log.audit("UPGRADING DB... DONE")
                }
                ver = ver + 1
                upgradingLoop = false
              }
        } catch {
          case e: Throwable => {
            Log.error("Exception during DB migration - darn thing won't work at all probably\n" + e, e)
            e.printStackTrace()
          }
        }

        // that's it, db initialized?
        adb
      }

      prep(db)
    }
  }
}

