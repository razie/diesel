/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  **/
package razie.diesel.engine.exec

import model.DieselSettings
import java.io.{File, FileInputStream}
import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier
import java.util.Properties
import model.WikiScripster
import razie.{cdebug, clog}
import razie.db.RazMongo
import razie.diesel.Diesel
import razie.diesel.cron.DieselCron
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.exec.EEDieselExecutors.updatingDeleted
import razie.diesel.engine.nodes._
import razie.diesel.engine.{DieselException, DomAst}
import razie.diesel.expr.ECtx
import razie.diesel.guard.DomGuardian
import razie.diesel.model.DieselMsg
import razie.diesel.utils.DomCollector
import razie.hosting.Website
import razie.wiki.admin.GlobalData
import razie.wiki.model.WikiConfigChanged
import razie.wiki.{Config, Enc, Services}
import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.io.Source
import scala.util.Try
import services.DieselCluster

/** properties - from system or file
  */
class EEDieselExecutors extends EExecutor("diesel.props") {
  val DP = DieselMsg.PROPS.ENTITY
  val DIO = "diesel.io"
  val DR = "diesel.realm"

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DP ||
        m.entity == DR ||
        m.entity == DIO ||
        m.ea == DieselMsg.ENGINE.DIESEL_PING
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val realm = ctx.root.settings.realm.mkString

    cdebug << "EEDieselProps: apply " + in

    in.ea match {

      case "diesel.props.configReload" => {

        // reset all settings
        clog << "diesel.props.configReload sending WikiConfigChanged..."
        Services ! new WikiConfigChanged("", Services.config)

        List(
          EVal(P.fromTypedValue(Diesel.PAYLOAD, "OK"))
        )
      }

      case "diesel.realm.switch" => {

        val realm = ctx.getRequired("realm")

        if (Services.config.isLocalhost) {
          Services.config.isimulateHost = s"$realm.dieselapps.com"
          DieselSettings(None, None, "isimulateHost", Services.config.isimulateHost).set

          val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)

          List(
            EVal(P.fromTypedValue(result, Services.config.isimulateHost))
          )
        } else
          throw new DieselException("Can only set realm in localhost")
      }

      case "diesel.props.realm" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)

        val m = Website.getRealmProps(ctx.root.settings.realm.mkString)

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.system" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)

        val m = if (Services.config.isLocalhost) {
          System.getProperties.asScala
        } else {
          throw new IllegalArgumentException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.env" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)

        val m = if (Services.config.isLocalhost) {
          System.getenv().asScala
        } else {
          throw new IllegalArgumentException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case DieselMsg.IO.TEXT_FILE => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Services.config.isLocalhost) {
          Source.fromInputStream(new FileInputStream(name)).mkString
        } else {
          throw new DieselException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case DieselMsg.IO.LIST_FILES => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Services.config.isLocalhost) {
          val f = new File(name)
          val l = f.list()
          val res = new ListBuffer[String]()
          if (l != null) res.appendAll(l) // jesus - one of those APIs...
          res.toList
        } else {
          throw new DieselException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case DieselMsg.IO.CAN_READ => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Services.config.isLocalhost) {
          val f = new File(name)
          val l = f.exists()
          val r = f.canRead()
          l && r
        } else {
          throw new DieselException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.file" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Services.config.isLocalhost) {
          val p = new Properties()
          p.load(new FileInputStream(name))

          p.asScala
        } else {
          throw new DieselException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case "diesel.props.jsonFile" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Services.config.isLocalhost) {
          val s = Source.fromInputStream(new FileInputStream(name)).mkString
          razie.js.parse(s)
        } else {
          throw new DieselException("Error: No permission")
        }

        List(
          EVal(P.fromTypedValue(result, m))
        )
      }

      case DieselMsg.ENGINE.DIESEL_PING => {

        // auto-restart logic
        if (!updatingDeleted) Try {
          updatingDeleted = true
//          new File("../updating").delete()
        }

        List(
          EVal(P.fromSmartTypedValue(
            Diesel.PAYLOAD,
            EEDieselExecutors.dieselPing())
          )
        )
      }

      case s if s.startsWith("diesel.realm") => {
        Nil // lifecycle messages should not be flagged as unknown
      }

      case s@_ => {
        new EError(s"$s - unknown activity") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.props "

  override val messages: List[EMsg] =
    EMsg(DP, "system") ::
        EMsg(DP, "configReload") ::
        EMsg(DP, "jsonFile") ::
        EMsg(DP, "file") ::
        EMsg("diesel.io", "textFile") :: Nil
}

object EEDieselExecutors {

  var updatingDeleted = false

  /** get all ping data */
  def dieselPing() = {
    val osm: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();

    val mstats = new HashMap[String, Any] // no prefix

    val stats = RazMongo.db.getStats()

    if (stats.ok()) {
      val s = stats.asScala.filter(t => t._1 != "db" && t._1 != "serverUsed")
      s.foreach(t => mstats.put(t._1.toString, t._2.toString))
    }


    GlobalData.toMap() ++
        Map(
          "moreStats" -> Map(
            "scriptsRun" -> WikiScripster.count,
            "Diesel" -> GlobalData.perfMap(),
            "DomGuardian.size" -> DomGuardian.lastRuns.size,
            "DomCollector.size" -> DomCollector.withAsts(_.size)
          ),
          "os" -> osusage(""),
          "db" -> mstats,
          "build" -> Config.verMap,
          "config" -> Map(
            "dieselLocalUrl" -> Services.config.dieselLocalUrl,
            "localQuiet" -> Services.config.localQuiet,
            "node" -> Services.config.node,
            "isLocalhost" -> Services.config.isLocalhost,
            "trustLocalMods" -> Config.trustLocalMods,
            "trustLocalUsers" -> Config.trustLocalUsers,
            "cacheWikis" -> Services.config.cacheWikis,
            "cacheFormat" -> Services.config.cacheFormat,
            "cacheDb" -> Services.config.cacheDb
          ),
          "cron" -> DieselCron.cronStats,
          "cluster" -> Services.cluster.clusterStats,
          "memDb" -> inmemdbstats("")
        )
  }

  def inmemdbstats(prefix: String) = {
    Map(
      "statsSessions" -> EEDieselDb.statsSessions,
      "statsCollections" -> EEDieselDb.statsCollections,
      "statsObjects" -> EEDieselDb.statsObjects
    )
  }

  def osusage(prefix: String) = {
    val s = new HashMap[String, Any]

    val osm: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
    for (method <- osm.getClass().getDeclaredMethods()) {
      method.setAccessible(true);
      if (method.getName().startsWith("get") && Modifier.isPublic(method.getModifiers())) {
        val v = try {
          method.invoke(osm).toString;
        } catch {
          case e: Exception => e.toString
        } // try
        val vn = try {
          v.toLong
        } catch {
          case e: Exception => -1
        } // try
        s.put(
          prefix + method.getName(),
          v
        )
        if (vn != -1 && prefix.nonEmpty)
          s.put(
            "os.nice." + method.getName(),
            Enc.niceNumber(vn)
          )
      } // if
    } // for

    // from https://stackoverflow.com/questions/466878/can-you-get-basic-gc-stats-in-java
    import java.lang.management.ManagementFactory
    var totalGarbageCollections = 0L
    var garbageCollectionTime = 0L

    import scala.collection.JavaConversions._
    for (gc <- ManagementFactory.getGarbageCollectorMXBeans) {
      val count = gc.getCollectionCount
      if (count >= 0) totalGarbageCollections += count
      val time = gc.getCollectionTime
      if (time >= 0) garbageCollectionTime += time
    }

    s.put("totalGarbageCollections", totalGarbageCollections)
    s.put("garbageCollectionTime", garbageCollectionTime)

    s
  }
}