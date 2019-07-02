package mod.diesel.controllers

import admin.Config
import controllers.{DieselSettings, Profile, RazRequest}
import difflib.{DiffUtils, Patch}
import java.net.InetAddress
import mod.diesel.guard.DomGuardian.startCheck
import razie.diesel.utils.DomHtml.quickBadge
import mod.diesel.controllers.DomSessions.Over
import mod.diesel.guard.DomGuardian
import mod.diesel.model._
import mod.diesel.model.exec.EESnakk
import model._
import org.apache.xml.serialize.LineSeparator
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.mvc._
import play.twirl.api.Html
import razie.audit.Audit
import razie.diesel.dom.RDOM.{NVP, P}
import razie.diesel.dom._
import razie.diesel.engine.RDExt._
import razie.diesel.engine._
import razie.diesel.ext.{EnginePrep, _}
import razie.diesel.utils.{DomCollector, SpecCache}
import razie.hosting.{Website, WikiReactors}
import razie.tconf.DTemplate
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{Logging, js}
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/** controller for server side fiddles / services */
class DomGuard extends DomApiBase with Logging {

  /** roll up and navigate the definitions */
  def engineView(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      // list all engines
      Ok(DomCollector.withAsts(_.map { e =>
        s"""<a href="/diesel/engine/view/${e.id}">${e.id}</a><br> """
      }.mkString)).as("text/html")
    } else {
      DomCollector.withAsts(_.find(_.id == id).map(_.engine).map { eng =>

        stok.fqhoParm("format", "html").toLowerCase() match {

          case "json" => {
            var m = Map(
              //      "values" -> values.toMap,
              "totalCount" -> (eng.totalTestCount),
              "failureCount" -> eng.failedTestCount,
              //      "errors" -> errors.toList,
              "dieselTrace" -> DieselTrace(eng.root, eng.settings.node, eng.id, "diesel", "runDom",
                eng.settings.parentNodeId).toJson,
              "settings" -> eng.settings.toJson,
              "specs" -> eng.pages.map(_.specPath)
            )

            Ok(js.tojsons(m).toString).as("application/json")
          }

          case _ => {
            ROK.k reactorLayout12 {
              views.html.modules.diesel.engineView(Some(eng))
            }
          }
        }
      }) getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.engineView(None)
        }
      }
    }
  }

  /** roll up and navigate the definitions */
  def navigate() = FAUR { implicit stok =>

    val engine = EnginePrep.prepEngine(
      new ObjectId().toString,
      DomEngineHelper.settingsFrom(stok),
      stok.realm,
      None,
      false,
      stok.au,
      "DomApi.navigate")

    val msgs = RDExt.summarize(engine.dom).toList

    ROK.k reactorLayout12 {
      views.html.modules.diesel.navigateMsg(msgs)
    }
  }

  /** */
  def getEngineConfig() = FAUR { implicit stok =>
    val config = Autosave.OR(
      "DomEngineConfig", WID("", "").r(stok.realm), stok.au.get._id,
      DomEngineHelper.settingsFrom(stok).toJson
    )

    retj << config
  }

  /** roll up and navigate the definitions */
  def setEngineConfig() = FAUR { implicit stok =>
    val jconfig = stok.formParm("DomEngineConfig")
    val jmap = js.parse(jconfig)
    //    val cfg = DomEngineSettings.fromRequest(stok.req)
    val cfg = DomEngineSettings.fromJson(jmap.asInstanceOf[Map[String, String]])

    // make sure it has a realm
    if (cfg.realm.isEmpty) cfg.realm = Some(stok.realm)

    DomWorker later AutosaveSet(
      "DomEngineConfig", stok.realm, "", stok.au.get._id,
      cfg.toJson
    )

    Ok("ok, later")
  }

  /** roll up and navigate the definitions */
  def engineConfig() = FAUR { implicit stok =>
    ROK.k noLayout { implicit stok =>
      views.html.modules.diesel.engineConfig()
    }
  }

  /** roll up and navigate the definitions */
  def engineConfigTags() = FAUR { implicit stok =>
    val title = stok.fqParm("title", "TQ Configuration")
    val tq = stok.fqParm("tq", "sub|fibe/spec/-dsl")

    ROK.k noLayout { implicit stok =>
      Wikis(stok.realm).index.withIndex { idx => }
      val tags = Wikis(stok.realm).index.usedTags.keySet.toList
      views.html.modules.diesel.engineConfigTags(title, tags, tq)
    }
  }

  // view an AST from teh collection
  def viewAst(id: String, format: String) = FAUR ("viewAst") { implicit stok =>
    DomCollector.withAsts { asts =>
        for(
          ast <- asts.find(_.id == id) orCorr ("ID not found" -> "We only store a limited amount of traces...")
      ) yield {
        if (format == "html")
          Ok(ast.engine.root.toHtml)
        else
          engineView(id).apply(stok.req).value.get.get
      }
    }.orElse(Some(NotFound("Engine trace not found - We only store a limited amount of traces...")))
  }

  // list the collected ASTS
  def listAst = FAUR { implicit stok =>
    val un = stok.userName + (if (stok.au.exists(_.isAdmin)) " admin - sees all" else " - regular user")

    DomCollector.withAsts { asts =>
      val x =
        asts.filter(a => stok.au.exists(_.isAdmin) ||
            a.realm == stok.realm &&
                (a.userId.isEmpty || a.userId.exists(_ == stok.au.map(_.id).mkString))
        ).zipWithIndex.map { z =>
            val a = z._1
          val i = z._2
          val uname = a.userId.map(u => Users.nameOf(new ObjectId(u))).getOrElse("[auto]")

          // todo this is mean
          s"""
             |<td>${i}</td>
             |<td><a href="/diesel/viewAst/${a.id}">${a.id}</a></td>
             |<td>${a.stream}</td>
             |<td>${a.realm}</td>
             |<td>${uname}</td>
             |<td>${a.engine.status}</td>
             |<td>${a.dtm}</td>
             |<td>${a.engine.description}</td>
             |<td>RES=${a.engine.resultingValue.take(50)}</td>
             |<td> </td>
             |""".stripMargin
        }.mkString(
          s"""
             |Traces captured for realm: ${stok.realm} and user $un<br><br>
             |<table>
             |<tr>
             |<th>No</th>
             |<th>Id</th>
             |<th>Stream</th>
             |<th>Realm</th>
             |<th>User</th>
             |<th>Status</th>
             |<th>dtm</th>
             |<th>Desc</th>
             |<th>Result</th>
             |<th></th>
             |</tr>
             |""".stripMargin,
          "</tr><tr>",
          s"""
             |</tr>
             |</table>
             |""".stripMargin
        )
      Ok(x).as("text/html")
    }
  }

  def cleanAst = FAUR { implicit stok =>
    if (stok.au.exists(_.isAdmin)) {
      DomCollector.cleanAst
      Ok("ok").as("text/html")
    } else
      Unauthorized("no permission")
  }

  def postAst(stream: String, id: String, parentId: String) = FAUPRaAPI(true) { implicit stok =>
    val reactor = stok.website.dieselReactor
    val capture = stok.formParm("capture")
    val m = js.parse(capture)
    //    val root = DieselJsonFactory.fromj(m).asInstanceOf[DomAst].withDetails("(POSTed ast)")
    // is teh map from a debug session or just the AST
    val root = (
        if (m contains "tree") DieselJsonFactory.fromj(m("tree").asInstanceOf[Map[String, Any]]).asInstanceOf[DomAst]
        else DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
        ).withDetails("(from capture)")

    Audit.logdb("DIESEL_FIDDLE_POSTAST", stok.au.map(_.userName).getOrElse("Anon"))

    val xid = if (id == "-") new ObjectId().toString else id

    var settings = new DomEngineSettings(
      mockMode = true,
      blenderMode = false,
      draftMode = true,
      sketchMode = false,
      execMode = "sync"
    )

    val engine = EnginePrep.prepEngine(
      xid,
      settings,
      reactor,
      Some(root), true, stok.au, "DomApi.postAst")
    DomCollector.collectAst(stream, reactor, xid, stok.au.map(_.id), engine)

    // decompose test nodes and wait
    engine.processTests.map { engine =>

      var ret = Map(
        "ok" -> "true",
        "totalCount" -> (engine.totalTestCount),
        "failureCount" -> engine.failedTestCount,
        "successCount" -> engine.successTestCount
      )

      Ok(js.tojsons(ret).toString).as("application/json")
    }
  }

  /** status badge for current realm */
  def status = RAction.async { implicit stok =>
    stok.au.map { au =>
      DomGuardian.findLastRun(stok.realm, au.userName).map { r =>
        Future.successful {
          Ok(quickBadge(r.failed, r.total, r.duration))
        }
      }.getOrElse {
        // start a check in the background
        if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm)) startCheck(stok.realm, stok.au)

        // just return right away
        Future.successful {
          Ok(quickBadge(-1, -1, -1, ""))
        }
      }
    }.getOrElse {
      Future.successful {
        Ok("") // todo when no user, don't call this
      }
    }
  }

  /** status badge for all realms */
  def statusAll = Filter(activeUser).async { implicit stok =>
    val t = (0, 0, 0L)

    val x = DomGuardian.lastRuns.values.map { r =>
      (r.failed, r.total, r.duration)
    }.foldLeft(t)((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3))

    Future.successful {
      Ok(quickBadge(x._1, x._2, x._3, "All"))
    }
  }

  def report = Filter(activeUser).async { implicit stok =>
    // todo this should run all the time when stories change

    val runs = stok.website.dieselEnvList.split(",").map { e =>
      s"""(<a href="/diesel/start/diesel.guardian.run?realm=${stok.realm}&env=$e">run in $e</a>)"""
    }.mkString(" | ")

    DomGuardian.findLastRun(stok.realm, stok.au.get.userName).map { r =>
      Future.successful {
        ROK.k reactorLayout12 {
          new Html(
            s"""
               | Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec)
               | ${quickBadge(r.failed, r.total, r.duration)}<br>
               | <small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a href="/diesel/cleanAst">clean all</a>) </small><br>
               | $runs<br><br>
               | """.stripMargin +
                views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
          )
        }
      }
    }.getOrElse {
      if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm)) startCheck(stok.realm, stok.au)

      // new
      Future.successful {

        val otherInRealm =
          DomGuardian.lastRuns.filter(_._1.startsWith(stok.realm + ".")).map { t =>
            (t._1, t._2.realm, t._2.engine.description, t._2.failed)
          }.mkString("<br>")

        Ok(
          s"""
             |no run available - check this later <a href="/diesel/runCheck">Re-run check</a>
             | (<a href="/diesel/listAst">list all</a>)
             | $runs
             |<br>Other in realm:<br>$otherInRealm""".stripMargin
        ).as("text/html")
      }
    }
  }

  // todo implement and optimize
  def reportAll = Filter(adminUser).async { implicit stok =>
    Future.successful {
      ROK.k reactorLayout12 {
        new Html(
          s"""
<a href="/diesel/runCheckAll">Re-run all checks</a> (may have to wait a while)...
<br>""" +
              s"""
<br>
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>:
<small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a href="/diesel/cleanAst">clean all</a>) </small><br><br>""".stripMargin +

              """<hr><h2>Abstract</h2>""" +

              /* abstract */
              DomGuardian.lastRuns.map { t =>
                val r = t._2
                s"""Realm: ${r.realm}""" +
                    s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${
                  quickBadge(r.failed, r.total, r.duration)
                }<br>
""".stripMargin
              }.toList.mkString +

              """<hr><h2>Details</h2>""" +

              /* details */

              DomGuardian.lastRuns.map { t =>
                val r = t._2
                s"""<p>Realm: ${r.realm}</p>""" +
                    s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${
                  quickBadge(r.failed, r.total, r.duration)
                }<br>
<small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a href="/diesel/cleanAst">clean all</a>) </small><br><br>""".stripMargin +
                    views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
              }.toList.mkString +

              """<hr><h2>Current engines report</h2>""" +
              DieselAppContext.report
        )
      }
    }
  }

  /** run another check current reactor */
  def runCheck = Filter(activeUser).async { implicit stok =>
    if (DomGuardian.enabled(stok.realm)) startCheck(stok.realm, stok.au).map { engine =>
      Redirect(s"""/diesel/report""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  /** run another check all reactors */
  def runCheckAll = Filter(adminUser).async { implicit stok =>
    if (DomGuardian.ISENABLED) Future.sequence(
       WikiReactors.allReactors.keys.map { k =>
        if (DomGuardian.enabled(k)) startCheck(k, stok.au)
        else Future.successful(DomGuardian.EMPTY_REPORT)
      }
    ).map { x =>
      Redirect(s"""/diesel/reportAll""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  def pluginAction(plugin: String, conn: String, action: String, epath: String) = Filter(activeUser).async
  { implicit stok =>
    Future.successful {
      val url = "http" + (if (stok.secure) "s" else "") + "://" + stok.hostPort
      val c = WikiDomain(stok.realm).plugins.find(_.name == plugin).map(
        _.doAction(WikiDomain(stok.realm).rdom, conn, action, url, epath)).mkString

      if (c.startsWith("<"))
        Ok(c).as("text/html")
      else
        Ok(c)
    }
  }

  def setEnv(env: String) = Filter(isMod).async { implicit stok =>
    val au = stok.au.get
    val u = Profile.updateUser(au, au.setPrefs(stok.realm, Map("dieselEnv" -> env)))
    cleanAuth()
    // it's not actually redirecting, see client
    Future.successful(Redirect("/", SEE_OTHER))
  }

  /** run another check current reactor */
  def whoami = RAction{ implicit stok =>
    Ok(InetAddress.getLocalHost.getHostName)
  }

}

