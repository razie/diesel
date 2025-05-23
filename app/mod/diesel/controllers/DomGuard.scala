package mod.diesel.controllers

import controllers.{RazRequest, Res}
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import razie.diesel.guard.DieselDebug.Guardian
import razie.diesel.guard.DieselDebug.Guardian.{ISENABLED, ISENABLED_LOCALHOST}
import razie.diesel.guard.DomGuardian.startCheck
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
import razie.diesel.guard.DomGuardian
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
import services.DieselCluster

/** controller for server side fiddles / services */
class DomGuard extends DomApiBase with Logging {

  /** find the engine - either collected in mem or active */
  private def findEngine (id: String) : Option[DomEngine] = {
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
      val eng = findEngine(id)
      DieselAppContext ! DECancel(id, s"User [${stok.au.map(_.userName)}] requested via API", "?", "")
      eng.map { eng =>
        Redirect(mod.diesel.controllers.routes.DomGuard.dieselViewAst(id).url)
      } getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.viewAst(None)
        }
      }
    }
  }

  /** see the running queue of a paused engine */
  def dieselEngineQueue(id: String) = FAUR { implicit stok =>
      findEngine(id)
          .map { eng =>
            Ok(eng.stashedMsg.mkString("\n"))
          } getOrElse {
        Ok("Not found");
        }
  }

  /** pause a running engine */
  def dieselEnginePause(id: String) = FAUR { implicit stok =>
      DieselAppContext ! DEPause(id)
      Ok("Ok, sent pause command...")
  }

  /** play one more message of a paused engine */
  def dieselEnginePlay(id: String) = FAUR { implicit stok =>
      DieselAppContext ! DEPlay(id)
      Ok("Ok, sent play command...")
  }

  /** continue a paused engine to the end */
  def dieselEngineContinue(id: String) = FAUR { implicit stok =>
    DieselAppContext ! DEContinue(id)
    Ok("Ok, continue command...")
  }

  // todo this and /engine/view are the same...
  // view an AST from teh collection
  def dieselViewAst(id: String, format: String) = FAUR ("viewAst") { implicit stok =>

    if (!ObjectId.isValid(id)) { // maybe remote ref

      val ref = DomRefs.parseDomAssetRef(id)

      if(ref.isDefined) {
        // todo show remote engines
        val m = Map("remote_engine" -> id)
        Option(Ok(js.tojsons(m)).as("application/json"))
      } else {
        Option(Redirect("/diesel/listAst"))
      }

    } else DomCollector.findAst(id).map { ast => // simple local id

      format match {
        case "json" => {
          val m = ast.engine.toj
          Ok(js.tojsons(m)).as("application/json")
        }
        // just the engine html, no wrappers
        case "html"=> Ok(ast.engine.root.toHtmlInPage).as("text/html")
        case "junit" =>
          Res.Ok(
            views.html.modules.diesel.engineJUnitView(ast.engine)(stok)
          ).as("application/xml")
        case _ =>
          ROK.k reactorLayout12 {
            views.html.modules.diesel.viewAst(Option(ast.engine))
          }
      }
    }.orElse(
      DieselAppContext.activeEngines.get(id).map(e =>
        ROK.k reactorLayout12 {
          views.html.modules.diesel.viewAst(Some(e))
        }
      )
    ).orElse(
      Option(dieselListAst("Trace not found - we only store a limited amount of traces").apply(stok.req).value.get.get)
    )
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

        DomDocs.summarize(dom).toList
      } else {
        val engine = EnginePrep.prepEngine(
          new ObjectId().toString,
          DomEngineHelper.settingsFrom(stok),
          stok.realm,
          None,
          false,
          stok.au,
          "DomApi.navigate")

        DomDocs.summarize(engine.dom).toList
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

  private def findUname(u: String): String = {
    Try {
      Users.nameOf(new ObjectId(u))
    }.getOrElse("???")
  }

  /** list the collected ASTS
    *
    * @param errMessages when reusing, pass something
    * @return
    */
  def dieselListAst (errMessage:String = "") = FAUR { implicit stok =>
    val follow = stok.query.get("follow").filter (x=> x != "null" && x != null).mkString
    val filters = stok.query.get("filter").filter (x=> x != "null" && x != null).mkString

    clog << s"dieselListAst with filter=$filters and follow=${follow.mkString}"

    val un = stok.userName + {
      if (stok.au.exists(_.isAdmin))
        s""" mod - sees all realms
          | (<a href = "/diesel/cleanAst?filter=${filters}&follow=${follow.mkString}" > clean all </a>)""".stripMargin
      else {
        if (stok.au.exists(_.isMod)) " mod - sees all users "
        else " - regular user "
      }
    }

    // in cloud we show more details - locally some never change
    val showDetails = ! Config.isLocalhost

    if(errMessage != "") stok.withErrors(List(errMessage))

    if(follow.trim.nonEmpty) DomCollector.following = follow.split(",").filter(_.trim.length > 1)

    val r = if (stok.au.exists(_.isAdmin)) "all" else stok.realm

    def canSee (a:DomEngine) =
      stok.au.exists(_.isAdmin) ||
          a.settings.realm.mkString == stok.realm &&
              (a.settings.userId.isEmpty ||
                  a.settings.userId.contains(stok.au.map(_.id).mkString) ||
                  stok.au.exists(_.isMod)
                  )

    def msgFor (a:DomEngine) =
      Enc.escapeComplexHtml(
        if(a.status startsWith "final.") a.returnedRestPayload.take(200)
        else {
          // this is the wrong progress... needs abstractised
          a.returnedRestPayload.take(200)
//          if(a.progress == "") a.returnedRestPayload.take(200)
//          else a.progress.take(200)
        }
      )

    val list1 = DomCollector.withAsts { asts =>
      asts.map(_.engine)
          .filter(canSee).filter(a=> filters.isEmpty || a.description.contains(filters))
    }
    val list2 = DieselAppContext.activeEngines.values
        .filter(canSee).filter(a=> filters.isEmpty || a.description.contains(filters))
        .toList

    val list = (list1 ++ list2).distinct

    val total = GlobalData.dieselEnginesTotal.get()

    def pause(engine:DomEngineState) = if(engine.paused)
      s"""<span id="continue-${engine.id}" class="glyphicon glyphicon-play" onclick="javascript:cancelEnginePlease('${engine.id}', 'continue');" style="cursor:pointer; color:red" title="Pause flow"></span>"""
    else
      s"""<span id="pause-${engine.id}" class="glyphicon glyphicon-pause" onclick="javascript:cancelEnginePlease('${engine.id}', 'pause');" style="cursor:pointer; color:red" title="Pause flow"></span>"""

    val tdNode = if(Config.clusterModeBool) s"<td>Node</td>" else ""

    val HEADING = (s"""
                     |<small>
                     |<table class="table table-condensed">
                     |<tr>
                     |<th>Id</th>""" +
        (if(showDetails) s"""
                     |<th>Realm</th>
                     |<th title="Collect group">Group</th>
                     |<th>User</th>""" else """""") +
            s"""
                     |$tdNode
                     |<th>Status</th>
                     |<th>Dtm</th>
                     |<th class="text-right">Msec</th>
                     |<th>Desc</th>
                     |<th>Code</th>
                     |<th>Result</th>
                     |<th></th>
                     |</tr>
                     |""").stripMargin

    val ENDING = s"""
                    |</tr>
                    |</table>
                    |</small>
                    |""".stripMargin

    var table = list.sortWith(
      (a, b) => a.createdDtm.isAfter(b.createdDtm)
    ).zipWithIndex.map { z =>
      Try {
          val a = z._1
          val i = z._2
          val uname = a.settings.userId.map(u => findUname(u)).getOrElse("[auto]")
          val duration = a.root.tend - a.root.tstart

          val st =
            if (DomState.isDone(a.status)) a.status
            else if(a.paused) """<span style="color:orange">paused!</span>&nbsp"""
            else
              s"""
                 |<small>
                 |<span id="cancel-${a.id}" class="glyphicon glyphicon-remove" onclick="javascript:cancelEnginePlease('${a.id}','cancel')
                 |;" style="cursor:pointer; color:red" title="Cancel flow"></span>&nbsp
                 |${pause(a)}
                 |</small>&nbsp;
                 |<span style="color:orange">${a.engine.status}</span>""".stripMargin

          val dtm = a.createdDtm.toLocalDateTime

        val resCodeStyle = a.returnedRestCode.map { i =>
          if (i / 100 == 2) """ style="color:green" """ else if (i / 100 == 4) """ style="color:orange" """ else """ style="color:red" """
        }.mkString

        val fail = if(a.errorCount <= 0) "" else """<span class="glyphicon glyphicon-warning-sign" style="cursor:pointer; color:red" title="Has Errors"></span>&nbsp;"""

        val valNode = if(Config.clusterModeBool) s"<td>${a.settings.node}</td>" else ""

          // todo this is mean
        (s"""
             |<td><a href="/diesel/viewAst/${a.id}?follow=$follow&filter=$filters">...${a.id.takeRight(4)}</a></td>""" +
            (if(showDetails) s"""
             |<td>${a.settings.realm.mkString}</td>
             |<td><span title="${a.collectGroup}">${a.collectGroup.takeRight(8)}</span></td>
             |<td>${uname}</td>""" else """""") +
            s"""
             |$valNode
             |<td><small><span>$st<span></small></td>
             |<td title="${dtm.toLocalDate.toString()}"><small>${dtm.toString("HH:mm:ss.SS")}</small></td>
             |<td align="right">$duration</td>
             |<td><small><code>${Enc.escapeComplexHtml(a.description.take(200))}</code></small></td>
             |<td><small>$fail<code $resCodeStyle>${Enc.escapeComplexHtml(a.returnedRestCode.mkString)}</code></small></td>
             |<td><small><code>${msgFor(a)}</code></small></td>
             |<td> </td>
             |""").stripMargin
        }.getOrElse("-can't print engine-")
      }.mkString(
           HEADING,
        "</tr><tr>",
        ENDING
      )

      if (stok.au.exists(_.isAdmin)) {
        // add active engines to debug things that get stuck
        val actives = DieselAppContext.activeEngines
          val a =
          """<h3> Active Engines | <small>also in list above</small></h3>""" + {
            if (actives.isEmpty) "-none-" else {
              actives.map {t =>
                s"""<br>&nbsp;
                   |<small><span id="cancel-${t._1}" class="glyphicon glyphicon-remove" onclick="javascript:cancelEnginePlease('${t._1}', 'cancel');" style="cursor:pointer; color:red" title="Cancel flow"></span>
                   |${pause(t._2.engine)}
                   |""".stripMargin +
                s""" |  <a href="/diesel/viewAst/${t._1}?follow=$follow&filter=$filters">${t._1}</a>
                   | | ${t._2.engine.createdDtm.toString("HH:mm:ss.SS")}
                   | | ${t._2.description}
                   | </small>""".stripMargin
              }.mkString("")
            }
          }

        table = table + a
      }

     val wb="/diesel/dom/browse"
    val wl="/diesel/dom/list"

//    http://localhost:9000/diesel/dom/list/DieselCron

    val title =
      s"""<small>Flow history realm: $r showing ${list.size} of $total since start and user $un</small>""".stripMargin
    val dieselStatus =
      s"""Stats: <a href="/diesel/listAst">Flows</a>: ${GlobalData.dieselEnginesActive} active (${DieselAppContext.activeEngines.size} - ${
        DieselAppContext.activeEngines.values.count(_.status != DomState.DONE)
      }) /
         | <a href="$wl/DieselStream">Streams</a>: ${GlobalData.dieselStreamsActive} active of ${GlobalData.dieselStreamsTotal} since start /
         |  Actors: ${DieselAppContext.activeActors.size} active /
         |   <a href="$wl/DieselCron">Crons</a>: ${GlobalData.dieselCronsActive} active of ${GlobalData.dieselCronsTotal} since start"""
          .stripMargin

    val cluster = Services.cluster
    val clusterStatus = if(Services.config.clusterModeBool) {
      val me = s"""<a href="$wb/DieselNode/${cluster.clusterNodeSimple}">${cluster.clusterNodeSimple}</a>"""
      val sngl =
        if (cluster.singleton.currentSingletonNode != "?")
          s"""<a href="$wb/DieselNode/${cluster.singleton.currentSingletonNode}">${cluster.singleton.currentSingletonNode}</a>"""

        else s"""<span style="color:red">${cluster.singleton.currentSingletonNode}</span>"""

      val clusterColor = if(cluster.totalNodesUp >= 1) "darkgreen" else "red"

      s"""<br>Cluster: I am $me |
         |<a href="$wl/DieselNode">Nodes</a>:
         |<span style="color:$clusterColor">${cluster.totalNodesUp}/${cluster.totalNodes}</span> |
         | Singleton: $sngl"""
          .stripMargin
    } else ""

      ROK.k reactorLayout12FullPage {
        views.html.modules.diesel.engineListAst(
          title,
          dieselStatus + clusterStatus,
          table,
          DomCollector.following.mkString,
          if(errMessage == "") None else Option(s"""/diesel/listAst?follow=$follow&filter=$filters""")
        )
      }
   }

  def dieselCleanAst = FAUR { implicit stok =>
    if (stok.au.exists(_.isAdmin)) {
      val filters = stok.query.get("filter").mkString
      val follow = stok.query.get("follow").mkString
      DomCollector.cleanAst
      Redirect(s"""/diesel/listAst?follow=$follow&filter=$filters""")
    } else
      Unauthorized("no permission, hacker eh?")
  }

  // todo engine id below should use xid loaded
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

    // todo the engine id should be overwritten with this xid here
    val engine = EnginePrep.prepEngine(
      xid,
      settings,
      reactor,
      Some(root), true, stok.au, "DomApi.postAst")
    DomCollector.collectAst(stream, reactor, engine)

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
  def dieselGuardStatus = RAction.async { implicit stok =>
    stok.au.map { au =>
      DomGuardian.findLastRun(stok.realm, au.userName).map { r =>
        Future.successful {
          Ok(quickBadge(r.failed, r.total, r.duration)).withHeaders("Access-Control-Allow-Origin" -> "*")
        }
      }.getOrElse {
        // start a check in the background
        if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm))
          startCheck(stok.realm, stok.au, Guardian.autoQuery(stok.realm))

        // just return right away
        Future.successful {
          Ok(quickBadge(-1, -1, -1, "")).withHeaders("Access-Control-Allow-Origin" -> "*")
        }
      }
    }.getOrElse {
      Future.successful {
        Ok("").withHeaders("Access-Control-Allow-Origin" -> "*") // todo when no user, don't call this
      }
    }
  }

  // deprected already - remove in June '24
  def dieselStatus2 = RAction.async { implicit stok =>
    stok.au.map { au =>
      DomGuardian.findLastRun(stok.realm, au.userName).map { r =>
        Future.successful {
          Ok(quickBadge(r.failed, r.total, r.duration)).withHeaders("Access-Control-Allow-Origin" -> "*")
        }
      }.getOrElse {
        // start a check in the background
        if (DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm))
          startCheck(stok.realm, stok.au, Guardian.autoQuery(stok.realm))

        // just return right away
        Future.successful {
          Ok(quickBadge(-1, -1, -1, "")).withHeaders("Access-Control-Allow-Origin" -> "*")
        }
      }
    }.getOrElse {
      Future.successful {
        Ok("").withHeaders("Access-Control-Allow-Origin" -> "*") // todo when no user, don't call this
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

  /** status badge for all realms - admins can see this */
  def dieselGuardStatusAll = Filter(activeUser).async { implicit stok =>
    val t = (0, 0, 0L)

    val x = DomGuardian.lastRuns.values.map { r =>
      (r.failed, r.total, r.duration)
    }.foldLeft(t)((a, b) => (a._1 + b._1, a._2 + b._2, a._3 + b._3))

    Future.successful {
      Ok(quickBadge(x._1, x._2, x._3, "All")).withHeaders("Access-Control-Allow-Origin" -> "*")
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
                views.html.modules.diesel.viewAst(Some(r.engine))(stok).toString
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
                    views.html.modules.diesel.viewAst(Some(r.engine))(stok).toString
              }.toList.mkString +

              """<hr><h2>Current engines report</h2>""" +
              DieselAppContext.report
        )
      }
    }
  }

  def dieselRunStory(tq: String, wpath:String, format: String, wait: String, inRealm:String) =
    dieselRunCheck(tq, wpath, format, wait, inRealm)

  /** run another check current reactor */
  def dieselRunCheck(tq: String, wpath:String, format: String, wait: String, inRealm:String) = Filter(activeUser).async { implicit stok =>

    if (DomGuardian.enabled(stok.realm)) {
      val q = stok.query

      val x@(f, e, _) = startCheck(
        stok.realm,
        stok.au,
        tq,
        Some(DomEngineHelper.settingsFrom(stok)),
        q,
        (if(wpath.length <= 0) None else Some(wpath)),
      )

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

