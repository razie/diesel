package mod.diesel.controllers

import controllers.RazRequest
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import mod.diesel.guard.DomGuardian
import mod.diesel.guard.DomGuardian.startCheck
import mod.diesel.model._
import model._
import org.bson.types.ObjectId
import play.twirl.api.Html
import razie.audit.Audit
import razie.diesel.dom._
import razie.diesel.engine.RDExt._
import razie.diesel.engine._
import razie.diesel.engine.nodes.EnginePrep
import razie.diesel.utils.DomHtml.quickBadge
import razie.diesel.utils.{AutosaveSet, DomCollector, DomWorker, SpecCache}
import razie.hosting.WikiReactors
import razie.wiki.Config
import razie.wiki.admin.{Autosave, GlobalData}
import razie.wiki.model._
import razie.wiki.util.NoAuthService
import razie.{Logging, js}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

/** controller for server side fiddles / services */
class DomGuard extends DomApiBase with Logging {

  /** canel a running engine */
  def dieselEngineCancel(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      Redirect("/diesel/listAst")
    } else {
      DomCollector.withAsts(_.find(_.id == id).map(_.engine).map { eng =>
          eng.stopNow
          Redirect(mod.diesel.controllers.routes.DomGuard.dieselEngineView(id).url)
      }) getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.engineView(None)
        }
      }
    }
  }

  /** view an engine or AST collected */
  def dieselEngineView(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      Redirect("/diesel/listAst")
    } else {
      DomCollector.withAsts(_.find(_.id == id).map(_.engine).map { eng =>

        stok.fqhoParm("format", "html").toLowerCase() match {

          case "json" => {
            var m = Map(
              //      "values" -> values.toMap,
              "totalCount" -> (eng.totalTestCount),
              "failureCount" -> eng.failedTestCount,
              //      "errors" -> errors.toList,
              DieselTrace.dieselTrace -> DieselTrace(eng.root, eng.settings.node, eng.id, "diesel", "runDom",
                eng.settings.parentNodeId).toJson,
              "settings" -> eng.settings.toJson,
              "specs" -> eng.pages.map(_.specRef)
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
  def dieselNavigate(scope:String) = FAUR { implicit stok =>

      val msgs = if(scope == "local") {
        val pages = Wikis(stok.realm).pages("Spec").toList

        val dom = pages.flatMap(p =>
          SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
        ).foldLeft(
          RDomain.empty
        )((a, b) => a.plus(b)).revise.addRoot

        RDExt.summarize(dom).toList
      } else {
        val engine = EnginePrep.prepEngine(
          new ObjectId().toString,
          DomEngineHelper.settingsFrom(stok),
          stok.realm,
          None,
          false,
          stok.au,
          "DomApi.navigate")

        RDExt.summarize(engine.dom).toList
      }

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
    val desc = stok.fqParm("desc", "You can add a description here...")
    val tq = stok.fqParm("tq", "sub|fibe/spec/-dsl")

    ROK.k noLayout { implicit stok =>
      Wikis(stok.realm).index.withIndex { idx => }
      val tags = Wikis(stok.realm).index.usedTags.keySet.toList
      views.html.modules.diesel.engineConfigTags(title, desc, tags, tq)
    }
  }

  // view an AST from teh collection
  def dieselViewAst(id: String, format: String) = FAUR ("viewAst") { implicit stok =>
    DomCollector.withAsts { asts =>
      for (
        ast <- asts.find(_.id == id) orCorr ("ID not found" -> "We only store a limited amount of traces...")
      ) yield {
        if (format == "html")
          Ok(ast.engine.root.toHtml)
        else
          dieselEngineView(id).apply(stok.req).value.get.get
      }
    }.orElse(
      DieselAppContext.synchronized {
        DieselAppContext.activeEngines.get(id).map(e =>
          ROK.k reactorLayout12 {
            views.html.modules.diesel.engineView(Some(e))
          }
        )
      }
    ).orElse(
      Some(NotFound("Engine trace not found - We only store a limited amount of traces..."))
    )
  }

  private def findUname(u: String): String = {
    Try {
      Users.nameOf(new ObjectId(u))
    }.getOrElse("???")
  }

  // list the collected ASTS
  def dieselListAst = FAUR { implicit stok =>
    val un = stok.userName + {
      if (stok.au.exists(_.isAdmin)) " admin - sees all realms " else {
        if (stok.au.exists(_.isMod)) " mod - sees all users "
        else " - regular user "
      }
    }

    val r = if (stok.au.exists(_.isAdmin)) "all" else stok.realm

    DomCollector.withAsts { asts =>
      val list =
        asts.filter(a => stok.au.exists(_.isAdmin) ||
            a.realm == stok.realm &&
                (a.userId.isEmpty ||
                    a.userId.exists(_ == stok.au.map(_.id).mkString) ||
                    stok.au.exists(_.isMod)
                    )
        )

      val total = GlobalData.dieselEnginesTotal.get()

      var table = list.zipWithIndex.map { z =>
        Try {
          val a = z._1
          val i = z._2
          val uname = a.userId.map(u => findUname(u)).getOrElse("[auto]")
          val duration = a.engine.root.tend - a.engine.root.tstart

          val st =
            if (DomState.isDone(a.engine.status)) a.engine.status
            else s"<b>${a.engine.status}</b>"

          // todo this is mean
          s"""
             |<td>${i}</td>
             |<td><a href="/diesel/viewAst/${a.id}">...${a.id.takeRight(4)}</a></td>
             |<td>${a.stream}</td>
             |<td>${a.realm}</td>
             |<td>${uname}</td>
             |<td>$st</td>
             |<td>${a.dtm.toString("HH:mm:ss.SS")}</td>
             |<td align="right">$duration</td>
             |<td><small>${a.engine.description}</small></td>
             |<td><small>${a.engine.resultingValue.take(100)}</small></td>
             |<td> </td>
             |""".stripMargin
        }.getOrElse("??")
      }.mkString(
        s"""
           |<small>
           |<table class="table table-condensed">
           |<tr>
           |<th>No</th>
           |<th>Id</th>
           |<th>Stream</th>
           |<th>Realm</th>
           |<th>User</th>
           |<th>Status</th>
           |<th>dtm</th>
           |<th class="text-right">Msec</th>
           |<th>Desc</th>
           |<th>Result</th>
           |<th></th>
           |</tr>
           |""".stripMargin,
        "</tr><tr>",
        s"""
           |</tr>
           |</table>
           |</small>
           |""".stripMargin
      )
      //      Ok(x).as("text/html")

      if (stok.au.exists(_.isAdmin)) {
        // add active engines to debug things that get stuck
        val a =
          """<p>----------------active engines------------------</p>""" + {
            DieselAppContext.synchronized {
              DieselAppContext.activeEngines.map(t =>
                s"""<br><a href="/diesel/viewAst/${t._1}">...${t._1}</a>"""
              )
            }.mkString("")
          }

        table = table + a
      }

      val title = s"""Flow history realm: $r showing ${list.size} of $total and user $un"""
      ROK.k reactorLayout12FullPage {
        views.html.modules.diesel.engineListAst(title, table)
      }
    }
  }

  def dieselCleanAst = FAUR { implicit stok =>
    if (stok.au.exists(_.isAdmin)) {
      DomCollector.cleanAst
      Ok("ok").as("text/html")
    } else
      Unauthorized("no permission")
  }

  def dieselPostAst(stream: String, id: String, parentId: String) = FAUPRaAPI(true) { implicit stok =>
    val reactor = stok.website.dieselReactor
    val capture = stok.formParm("capture")
    val m = js.parse(capture)
    //    val root = DieselJsonFactory.fromj(m).asInstanceOf[DomAst].withDetails("(POSTed ast)")
    // is teh map from a debug session or just the AST
    val root = (
        if (m contains "tree") DieselJsonFactory.fromj(m("tree").asInstanceOf[Map[String, Any]]).asInstanceOf[DomAst]
        else DieselJsonFactory.fromj(m.toMap).asInstanceOf[DomAst]
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
  def dieselStatus = RAction.async { implicit stok =>
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

  @inline class cs() {
    val sb = new StringBuilder()
    def <<(x: Any) = { (sb append x + "\n"); this }

    override def toString = sb.toString
  }

  def dieselPerf(threads:Int, cycles:Int) = Filter(activeUser).async { implicit stok =>
    val start = System.currentTimeMillis()
    val flowCount = new AtomicInteger(0)
    val testCount = new AtomicInteger(0)
    val failCount = new AtomicInteger(0)

    // use the play pool
    import scala.concurrent.ExecutionContext.Implicits.global

    Future {

      razie.Threads.forkjoin(Range(0, threads).toList) { i =>
        Range(0, cycles).toList.foreach { j =>
          val fut = DomGuardian.runReqUnsafe(Some(NoAuthService.harry), "specs", "", Some("Story/-noperf"))._1

          val fe = Await.result(
            fut,
            15 minutes
          )

          flowCount.incrementAndGet()
          testCount.addAndGet(fe.engine.totalTestCount)
          failCount.addAndGet(fe.engine.failedTestCount)

//          assert(fe.engine.resultingValue contains s"Greetings, Jane$i-$j")
        }
      }

      val end = System.currentTimeMillis()
      val dur = (end-start)/1000

      val cs = new cs()
      cs << "==========================================="
      cs << DieselAppContext.activeEngines.values.toList.mkString("\n")

//      assert(DieselAppContext.activeEngines.size == 0)
//      assert(DieselAppContext.activeActors.size == 0)

      val perf = if(dur > 0) flowCount.get() / dur else -1
      cs << s"T O T A L  $testCount tests"
      cs << s"F A I L    $failCount tests"
      cs << s"T O T A L  $flowCount flows in $dur seconds meaning $perf per sec"

      clog << cs.toString
      Ok("done... " + cs.toString)
    }
  }

  /** status badge for all realms */
  def dieselStatusAll = Filter(activeUser).async { implicit stok =>
    val t = (0, 0, 0L)

    val x = DomGuardian.lastRuns.values.map { r =>
      (r.failed, r.total, r.duration)
    }.foldLeft(t)((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3))

    Future.successful {
      Ok(quickBadge(x._1, x._2, x._3, "All"))
    }
  }

  def dieselReport = Filter(activeUser).async { implicit stok =>
    // todo this should run all the time when stories change

    val runs = stok.website.dieselEnvList.split(",").filter(_.length > 0).map { e =>
      s"""(<a href="/diesel/start/diesel.guardian.run?realm=${stok.realm}&env=$e">target <b>$e</b></a>)"""
    }.mkString(" | ")

    DomGuardian.findLastRun(stok.realm, stok.au.get.userName).map { r =>
      Future.successful {
        ROK.k reactorLayout12 {
          new Html(
            s"""
               | Guardian report<a href="/wiki/Guardian" ><sup><span class="glyphicon
               | glyphicon-question-sign"></span></a></sup>:
               | <b><a href="/diesel/runCheck">Re-run check</a></b> (
               | <a href="/diesel/runCheck?tq=story/sanity">Just sanity</a>)
               |               |   (${r.duration} msec) | ${
              quickBadge(r.failed, r.total, r.duration)
            }<br>
               | <small>Guardian - enabled:${DomGuardian.enabled(stok.realm)} auto:${
              DomGuardian.onAuto(stok.realm)
            }</small>
               | <small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a
               | href="/diesel/cleanAst">clean all</a>) </small><br>
               | $runs<br><br>
               | """.stripMargin +
                views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
          )
        }
      }
    }.getOrElse {
      var started =
        Try {
          if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm)) {
            val (f, e) = startCheck(stok.realm, stok.au)
            s"""One just auto-started <a href="/diesel/viewAst/${e.id}">view</a> """
          } else
            "Can't auto-start one"
        }.recover {
          case throwable: Throwable => throwable.getMessage
        }


      // new
      Future.successful {

        val otherInRealm =
          DomGuardian.lastRuns.filter(_._1.startsWith(stok.realm + ".")).map { t =>
            (t._1, t._2.realm, t._2.engine.description, t._2.failed)
          }.mkString("<br>")

        ROK.k reactorLayout12 {
          new Html(
            guardianMenu +
                s"""
                   |No run available yet (<b>$started</b>) - check this later
                   |  <br><b><a href="/diesel/runCheck">Re-run check</a></b> (
                   |  <a href="/diesel/runCheck?tq=story/sanity">Just sanity</a>)
                   | $runs
                   |<br>
                   |Other in realm:<br>$otherInRealm""".
                    stripMargin
          )
        }
      }
    }
  }

  def guardianMenu(implicit stok: RazRequest) =
    s"""
       | Guardian menu <a href="/wiki/Guardian" ><sup><span class="glyphicon
       | glyphicon-question-sign"></span></a></sup>:
       | (<b><a href="/diesel/listAst">list all traces</a></b>)
       |<br>
       | <small>
       | Guardian - enabled:${DomGuardian.enabled(stok.realm)} auto:${DomGuardian.onAuto(stok.realm)}
       | </small>
       |<br>
       |Guardian report<a href="/wiki/Guardian" ><sup><span class="glyphicon
       |glyphicon-question-sign"></span></a></sup>:
       |<small>${DomGuardian.stats} (<a href="/diesel/listAst"><b>list all</b></a>)(<a href="/diesel/cleanAst">clean
       |all</a>)
       |</small><br><br>
       | """.stripMargin

  // todo implement and optimize
  def dieselReportAll = Filter(adminUser).async { implicit stok =>
    Future.successful {
      ROK.k reactorLayout12 {
        new Html(
          guardianMenu +
              s"""
<a href="/diesel/runCheckAll">Re-run all checks</a> (may have to wait a while)...
""".stripMargin +

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
  def dieselRunCheck(tq: String) = Filter(activeUser).async { implicit stok =>
    if (DomGuardian.enabled(stok.realm)) {
      val x@(f, e) = startCheck(stok.realm, stok.au, tq)
      val id = if (e != null) e.id else ""

      Future.successful(
        Redirect(s"""/diesel/viewAst/$id"""))
    }
    else Future.successful(
      Ok("GUARDIAN DISABLED in realm: " + stok.realm))
  }

  /** run another check all reactors */
  def dieselRunCheckAll = Filter(adminUser).async { implicit stok =>
    if (!Config.isLocalhost && DomGuardian.ISENABLED || DomGuardian.ISENABLED_LOCALHOST) Future.sequence(
      WikiReactors.allReactors.keys.map { k =>
        if (DomGuardian.enabled(k)) startCheck(k, stok.au)._1
        else Future.successful(DomGuardian.EMPTY_REPORT)
      }
    ).map { x =>
      Redirect(s"""/diesel/reportAll""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  /** run another check current reactor */
  def whoami = RAction { implicit stok =>
    Ok(InetAddress.getLocalHost.getHostName)
  }

}

