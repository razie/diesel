package mod.diesel.controllers

import controllers.{RazRequest, Res}
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import mod.diesel.guard.DieselDebug.Guardian
import mod.diesel.guard.DieselDebug.Guardian.{ISENABLED, ISENABLED_LOCALHOST}
import mod.diesel.guard.DomGuardian
import mod.diesel.guard.DomGuardian.startCheck
import mod.diesel.model._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.twirl.api.Html
import razie.audit.Audit
import razie.diesel.dom._
import razie.diesel.engine.RDExt._
import razie.diesel.engine._
import razie.diesel.engine.nodes.EnginePrep
import razie.diesel.samples.DomEngineUtils
import razie.diesel.utils.DomHtml.quickBadge
import razie.diesel.utils.{AutosaveSet, DomCollector, DomWorker, SpecCache}
import razie.hosting.WikiReactors
import razie.wiki.{Config, Enc, Services}
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

  /** fine the engine */
  def findEngine (id: String) : Option[DomEngine] = {
      DomCollector.withAsts(
        _.find(_.id == id)
            .map(_.engine)
            // todo why arent' some active engines not in collector?
            .orElse(DieselAppContext.activeEngines.get(id))
      )
  }

  /** canel a running engine */
  def dieselEngineCancel(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      Redirect("/diesel/listAst")
    } else {
      findEngine(id)
          .map { eng =>
            eng.stopNow
            Redirect(mod.diesel.controllers.routes.DomGuard.dieselEngineView(id).url)
          } getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.engineView(None)
        }
      }
    }
  }

  /** canel a running engine */
  def dieselEngineQueue(id: String) = FAUR { implicit stok =>
      findEngine(id)
          .map { eng =>
            Ok(eng.stashedMsg.mkString("\n"))
          } getOrElse {
        Ok("Not found");
        }
  }

  /** canel a running engine */
  def dieselEnginePause(id: String) = FAUR { implicit stok =>
      DieselAppContext ! DEPause(id)
      Ok("Ok, trying...")
  }

  /** canel a running engine */
  def dieselEnginePlay(id: String) = FAUR { implicit stok =>
      DieselAppContext ! DEPlay(id)
      Ok("Ok, trying...")
  }

  /** canel a running engine */
  def dieselEngineContinue(id: String) = FAUR { implicit stok =>
    DieselAppContext ! DEContinue(id)
    Ok("Ok, trying...")
  }

  // todo this and /viewAst are the same...
  /** view an engine or AST collected */
  def dieselEngineView(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      Redirect("/diesel/listAst")
    } else {
      var engine = DomCollector.findAst(id).map(_.engine)

      engine.map { eng =>
        stok.fqhoParm("format", "html").toLowerCase() match {

          case "json" => {
            val m = eng.toj
            Ok(js.tojsons(m).toString).as("application/json")
          }

//          case "html" => { // just the engine html, no wrappers
//              Ok(eng.root.toHtmlInPage).as("text/html")
//          }

          case _ => {
            ROK.k reactorLayout12 {
              views.html.modules.diesel.engineView(Some(eng))
            }
          }
        }
      } orElse {
          DieselAppContext.activeEngines.get(id).map ( e =>
            ROK.k reactorLayout12 {
              views.html.modules.diesel.engineView(Some(e))
            }
          )
      } getOrElse (
        NotFound("Engine trace not found - We only store a limited amount of traces...")
      )
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

  // todo this and /engine/view are the same...
  // view an AST from teh collection
  def dieselViewAst(id: String, format: String) = FAUR ("viewAst") { implicit stok =>
    DomCollector.findAst(id).map { ast =>
        if (format == "html") {
          Ok(ast.engine.root.toHtml)
        } else if ("junit" == format) {
          Res.Ok(
            views.html.modules.diesel.engineJUnitView(ast.engine)(stok)
          ).as("application/xml")
        } else {
          dieselEngineView(id).apply(stok.req).value.get.get
        }
    }.orElse(
        DieselAppContext.activeEngines.get(id).map(e =>
          ROK.k reactorLayout12 {
            views.html.modules.diesel.engineView(Some(e))
          }
        )
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
      if (stok.au.exists(_.isMod))
        """ mod - sees all realms
          | (<a href = "/diesel/cleanAst" > clean all </a>)""".stripMargin
      else {
        if (stok.au.exists(_.isMod)) " mod - sees all users "
        else " - regular user "
      }
    }

    val r = if (stok.au.exists(_.isAdmin)) "all" else stok.realm

    val list = DomCollector.withAsts { asts =>
      asts.filter(a => stok.au.exists(_.isAdmin) ||
          a.realm == stok.realm &&
              (a.userId.isEmpty ||
                  a.userId.exists(_ == stok.au.map(_.id).mkString) ||
                  stok.au.exists(_.isMod)
                  )
      )
    }

      val total = GlobalData.dieselEnginesTotal.get()

      var table = list.sortWith(
        (a, b) => a.engine.createdDtm.isAfter(b.engine.createdDtm)
      ).zipWithIndex.map { z =>
        Try {
          val a = z._1
          val i = z._2
          val uname = a.userId.map(u => findUname(u)).getOrElse("[auto]")
          val duration = a.engine.root.tend - a.engine.root.tstart

          val st =
            if (DomState.isDone(a.engine.status)) a.engine.status
            else if(a.engine.paused) s"<b>paused!</b>"
            else s"<b>${a.engine.status}</b>"

          val dtm = a.dtm.toLocalDateTime

          // todo this is mean
          s"""
             |<td><a href="/diesel/viewAst/${a.id}">...${a.id.takeRight(4)}</a></td>
             |<td>${a.stream}</td>
             |<td>${a.realm}</td>
             |<td><span title="${a.collectGroup}">${a.collectGroup.takeRight(8)}</span></td>
             |<td>${uname}</td>
             |<td>$st</td>
             |<td title="${dtm.toLocalDate.toString()}">${dtm.toString("HH:mm:ss.SS")}</td>
             |<td align="right">$duration</td>
             |<td><small><code>${Enc.escapeComplexHtml(a.engine.description.take(200))}</code></small></td>
             |<td><small><code>${Enc.escapeComplexHtml(a.engine.resultingValue.take(200))}</code></small></td>
             |<td> </td>
             |""".stripMargin
        }.getOrElse("-can't print engine-")
      }.mkString(
        s"""
           |<small>
           |<table class="table table-condensed">
           |<tr>
           |<th>Id</th>
           |<th>Stream</th>
           |<th>Realm</th>
           |<th>Group</th>
           |<th>User</th>
           |<th>Status</th>
           |<th>Dtm</th>
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

      if (stok.au.exists(_.isAdmin)) {
        // add active engines to debug things that get stuck
        val a =
          """<p>----------------active engines------------------</p>""" + {
              DieselAppContext.activeEngines.map(t =>
                s"""<br><a href="/diesel/viewAst/${t._1}">...${t._1} - ${t._2.description}</a>"""
              ).mkString("")
          }

        table = table + a
      }

      val title =
        s"""Flow history realm: $r showing ${list.size} of $total since start and user $un""".stripMargin
      val title2 =
        s"""Stats: Flows: ${GlobalData.dieselEnginesActive} active (${DieselAppContext.activeEngines.size} - ${
          DieselAppContext.activeEngines.values.filter(_.status != DomState.DONE).size
        }) /
           | Streams: ${GlobalData.dieselStreamsActive} active of ${GlobalData.dieselStreamsTotal} since start /
           | Actors: ${DieselAppContext.activeActors.size} active /
           | Crons: ${GlobalData.dieselCronsActive} active of ${GlobalData.dieselCronsTotal} since start"""
            .stripMargin

      ROK.k reactorLayout12FullPage {
        views.html.modules.diesel.engineListAst(title, title2, table)
      }
   }

  def dieselCleanAst = FAUR { implicit stok =>
    if (stok.au.exists(_.isAdmin)) {
      DomCollector.cleanAst
      Redirect(s"""/diesel/listAst""")
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
        if (m contains DieselJsonFactory.TREE) DieselJsonFactory.fromj(m(DieselJsonFactory.TREE).asInstanceOf[Map[String, Any]]).asInstanceOf[DomAst]
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
        if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm))
          startCheck(stok.realm, stok.au, Guardian.autoQuery(stok.realm))

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
               | <b><a href="/diesel/guard/runCheck?tq=story%2F-skip%2F-manual">Re-run check</a></b> (
               | <a href="/diesel/guard/runCheck?tq=story%2Fsanity%2F-skip%2F-manual">Just sanity</a>)
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
            val (f, e, _) = startCheck(stok.realm, stok.au, Guardian.autoQuery(stok.realm))
            s"""One just auto-started <a href="/diesel/viewAst/${e.map(_.id).getOrElse("n/a")}">view</a> """
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
                   |  <br><b><a href="/diesel/guard/runCheck?tq=story%2F-skip%2F-manual">Re-run check</a></b> (
                   |  <a href="/diesel/guard/runCheck?tq=story/sanity/-skip/-manual">Just sanity</a>)
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
<a href="/diesel/guard/runCheckAll">Re-run all checks</a> (may have to wait a while)...
""".stripMargin +

              """<hr><h2>Abstract</h2>""" +

              /* abstract */
              DomGuardian.lastRuns.map { t =>
                val r = t._2
                s"""Realm: ${r.realm}""" +
                    s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon
glyphicon-question-sign"></span></a></sup>: <a href="/diesel/guard/runCheck">Re-run check</a>  (${r.duration} msec) ${
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
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon
glyphicon-question-sign"></span></a></sup>: <a href="/diesel/guard/runCheck">Re-run check</a>  (${r.duration} msec) ${
                  quickBadge(r.failed, r.total, r.duration)
                }<br>
<small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a href="/diesel/cleanAst">clean all</a>)
</small><br><br>""".stripMargin +
                    views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
              }.toList.mkString +

              """<hr><h2>Current engines report</h2>""" +
              DieselAppContext.report
        )
      }
    }
  }

  /** run another check current reactor */
  def dieselRunCheck(tq: String, format: String, wait: String, storyRealm: String) = Filter(activeUser).async
  { implicit stok =>
    if (DomGuardian.enabled(stok.realm)) {
      val x@(f, e, _) = startCheck(stok.realm, stok.au, tq, Some(DomEngineHelper.settingsFrom(stok)))

      if (wait.isEmpty || !wait.toBoolean) {
        Future.successful(
          e
              .map(e => Redirect(s"""/diesel/viewAst/${e.id}?format=$format"""))
              .getOrElse(
                NotFound(s"Test can't start = likely no stories matched tagQuery $tq"))
        )
      } else {
        // wait and format
        if ("junit" == format) {
          f.flatMap(_.engine.finishF).map { eng =>
            Res.Ok(
              views.html.modules.diesel.engineJUnitView(f.value.get.get.engine)(stok)
            ).as("application/xml")
          }
        } else {
          f.map(report => Redirect(s"""/diesel/viewAst/${report.engine.id}?format=$format"""))
        }
      }
    }
    else Future.successful(
      Ok("GUARDIAN DISABLED in realm: " + stok.realm))
  }

  /** run another check all reactors */
  def dieselRunCheckAll = Filter(adminUser).async { implicit stok =>
    if (!Services.config.isLocalhost && ISENABLED || ISENABLED_LOCALHOST) Future.sequence(
      WikiReactors.allReactors.keys.map { k =>
        if (DomGuardian.enabled(k)) startCheck(k, stok.au, "")._1
        else Future.successful(DomGuardian.EMPTY_REPORT)
      }
    ).map { x =>
      Redirect(s"""/diesel/guard/reportAll""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  /** run another check current reactor */
  def whoami = RAction { implicit stok =>
    Ok(InetAddress.getLocalHost.getHostName)
  }

}

