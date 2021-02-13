/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  **/
package mod.diesel.guard

import java.io.{File, FileInputStream}
import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier
import java.util.Properties
import mod.diesel.guard.EEDieselExecutors.getAllData
import mod.notes.controllers.NotesLocker
import model.WikiScripster
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import razie.db.{ROne, RazMongo}
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.{AstKinds, DieselException, DomAst}
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EError, EMsg, EVal, _}
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.utils.DomCollector
import razie.hosting.{Website, WikiReactors}
import razie.wiki.admin.{GlobalData, SendEmail}
import razie.wiki.model.WikiConfigChanged
import razie.wiki.{Config, Services}
import razie.{cdebug, clog}
import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.io.Source

/** properties - from system or file
  */
class EEDieselExecutors extends EExecutor("diesel.props") {
  val DT = DieselMsg.PROPS.ENTITY
  val DIO = "diesel.io"

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DT ||
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
        Services ! new WikiConfigChanged("", Config)

        List(
          EVal(P.fromTypedValue(Diesel.PAYLOAD, "OK"))
        )
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

        val m = if (Config.isLocalhost) {
          System.getProperties.asScala
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

        val m = if (Config.isLocalhost) {
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

        val m = if (Config.isLocalhost) {
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

      case "diesel.props.file" => {

        val result = ctx.get("result").getOrElse(Diesel.PAYLOAD)
        val name = ctx.getRequired("path")

        val m = if (Config.isLocalhost) {
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

        val m = if (Config.isLocalhost) {
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

        List(
          EVal(P.fromSmartTypedValue(
            Diesel.PAYLOAD,
            getAllData())
          )
        )
      }

      case s@_ => {
        new EError(s"$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.props "

  override val messages: List[EMsg] =
    EMsg(DT, "system") ::
        EMsg(DT, "configReload") ::
        EMsg(DT, "jsonFile") ::
        EMsg(DT, "file") ::
        EMsg("diesel.io", "textFile") :: Nil
}

object EEDieselExecutors {
  def getAllData() = {
    val osm: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();

    val mstats = new HashMap[String, Any]
    val mstatsDb = new HashMap[String, Any]

    val stats = RazMongo.db.getStats()

    if (stats.ok()) {
      val s = stats.asScala.filter(t => t._1 != "db" && t._1 != "serverUsed")
      s.foreach(t => mstats.put(t._1.toString, t._2.toString))
      s.foreach(t => mstatsDb.put("db." + t._1.toString, t._2.toString))
    }

    GlobalData.toMap() ++
        Map(
          "scriptsRun" -> WikiScripster.count,
          "DieselCron.size" -> DieselCron.withRealmSchedules(_.size),
          "DomGuardian.size" -> DomGuardian.lastRuns.size,
          "DomCollector.size" -> DomCollector.withAsts(_.size),
          "os" -> osusage(""),
          "db" -> mstats
        ) ++ osusage("os.") ++ mstatsDb
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
            nice(vn)
          )
      } // if
    } // for
    s
  }

  def nice(l: Long) =
    if (l > 2L * (1024L * 1024L * 1024L))
      l / (1024L * 1024L * 1024L) + "G"
    else if (l > 2 * (1024L * 1024L))
      l / (1024L * 1024L) + "M"
    else if (l > 1024)
      l / 1024 + "K"
    else
      l.toString

}