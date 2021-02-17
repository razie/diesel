/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import admin._
import com.google.inject.AbstractModule
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.{MongoConnection, MongoDB}
import controllers._
import mod.cart.{EEModCartExecutor, EEModUserExecutor}
import mod.diesel.controllers.{DieselMod, FiddleMod}
import mod.diesel.guard.{EEDieselApiGw, EEDieselCron, EEDieselExecutors, EEGuardian}
import mod.snow.EEModSnowExecutor
import mod.wiki.CaptchaMod
import model.WikiUsersImpl
import razie.audit.{Audit, AuditService, MdbAuditService}
import razie.db.{RMongo, RazMongo, UpgradeDb}
import razie.diesel.engine.DieselAppContext
import razie.diesel.engine.exec._
import razie.hosting.{Website, WikiReactors}
import razie.tconf.hosting.Reactors
import razie.wiki.admin.SecLink
import razie.wiki.model.WikiUsers
import razie.wiki.mods.WikiMods
import razie.wiki.{Config, EncryptService, Services, WikiConfig}
import razie.{Log, clog, cout, wiki}
import special.{CypherEncryptService, RazAuthService}

/** initialize this module */
class Module extends AbstractModule {

  lazy val config = {
    import com.typesafe.config.ConfigFactory
    import play.api.Configuration

    val config = new Configuration(ConfigFactory.load())
    config
  }

  def prop(name:String, dflt:String="") = {
    config.getString(name).getOrElse(dflt)
  }

  def secret() = prop("play.crypto.secret")

  def configure() = {
    clog << "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ configure rk Module"
    clog << "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ " + prop("wiki.home")

    // todo how do i inject this? I get into all sorts of trouble if not initialized here...
    WikiConfig.playConfig = config

    // Use the system clock as the default implementation of Clock
//    bind(classOf[Clock]).toInstance(Clock.systemDefaultZone)
    // Ask Guice to create an instance of ApplicationTimer when the
    // application starts.
//    bind(classOf[ApplicationTimer]).asEagerSingleton()
    // Set AtomicCounter as the implementation for Counter.
//    bind(classOf[Counter]).to(classOf[AtomicCounter])

    Services.auth = new RazAuthService()
//    bind(classOf[AuthService[_]]).toInstance(new RazAuthService())

    Services.config = Config
//    bind(classOf[WikiConfig]).toInstance(Config)

    mongoInit()

    MoreUpgrades.sayHi

    // init after DB because it needs DB
    Audit.setInstance(new MdbAuditService)
    RMongo.setInstance(Audit.getInstance)
    bind(classOf[AuditService]).toInstance(Audit.getInstance)

    WikiUsers.setImpl(WikiUsersImpl)
    Reactors.impl = WikiReactors

//    EncryptService.impl = new admin.CypherEncryptService(secret(), secret())
    EncryptService.impl = new CypherEncryptService("", "") // use default key

    ViewService.impl = RkViewService
    bind(classOf[ViewService]).toInstance(RkViewService)

    Services.wikiAuth = RazWikiAuthorization

    //todo look these up in Website
    Services.isSiteTrusted = { (r, s) =>
      val y = Website.forRealm(r).toList.flatMap(_.trustedSites.toList)

      Config.trustedSites.exists(x => s.startsWith(x)) ||
          (
              r.length > 0 &&
                  Website.forRealm(r).exists(_.trustedSites.exists(x => s.startsWith(x)))
              )
    }

    // setup diesel actors

    // using default akka system
    DieselAppContext.withActorSystemFactory(() => play.libs.Akka.system)

    // todo why can't i use this separate pool?

//    DieselAppContext.setActorSystemFactory{() =>
//      val ec = DieselAppContext.ec
//      val a = ActorSystem.apply("diesel", None, None, Some(ec))
//       a
//    }

    DieselAppContext.localNode = Services.config.node

    Executors.add(EEModRkExec)
    Executors.add(EEModRkExec)
    Executors.add (EEModUserExecutor)
    Executors.add (EEModCartExecutor)
    Executors.add (EEModSnowExecutor)
    Executors.add (new mod.diesel.model.exec.EEWiki)
    Executors.add (new mod.diesel.model.exec.EEMail)
    Executors.add(new EEDieselCron)
    Executors.add(new EEDieselExecutors)
    Executors.add(new EEGuardian)
    Executors.add (new EEDieselDT)
    Executors.add (new EEDieselMemDb)
    Executors.add (new EEDieselSharedDb)
    Executors.add (new EEDieselMongodDb)
    Executors.add(new EEDieselElasticDb)
    Executors.add(new EEDomInventory)
    Executors.add(new EEDieselApiGw)

    if(Config.isimulateHost == Config.REFERENCE_SIMULATE_HOST)
      DieselSettings.find(None, None, "isimulateHost").map { s=>
        Config.isimulateHost = s
    }

    WikiMods register new FiddleMod
    WikiMods register new DieselMod
    WikiMods register new CaptchaMod

    SecLink.purge

//    WikiReactors.apply("rk") // weird stuff happens to diesel parser if I do this
    Audit.logdb("NODE_RESTARTED", Services.config.node)
  }

  def mongoInit(): Unit = {
    /************** MONGO INIT *************/
    RazMongo.setInstance {

      val UPGRADE_AGAIN = false // set to true in local dev to rerun only the last upgrade

      val mongoUpgrades: Map[Int, UpgradeDb] = Map(
          14 -> U14, 15 -> U15, 16 -> U16, 17 -> U17, 18 -> U18, 19 -> U19)
      /* NOTE as soon as you list it here, it will apply */

      def mongoDbVer = mongoUpgrades.keySet.max + 1

      lazy val conn = MongoConnection(wiki.Config.mongohost)

      /** the actual database - done this way to run upgrades before other code uses it */
      com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
      com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

      // authenticate
      val db = conn(Config.mongodb)
      if (!db.authenticate(Config.mongouser, Config.mongopass)) {
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

