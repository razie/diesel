package mod.diesel.controllers

import java.util.regex.Pattern

import difflib.{DiffUtils, Patch}
import DomGuardian.{addStoryToAst, collectAst, prepEngine, startCheck}
import mod.diesel.controllers.DomSessions.Over
import razie.diesel.engine.RDExt._
import mod.diesel.model._
import mod.diesel.model.exec.EESnakk
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc._
import play.twirl.api.Html
import razie.audit.Audit
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.{DieselAppContext, DieselTrace, DomEngineSettings, RDExt}
import razie.diesel.ext._
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{Logging, js}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/** controller for server side fiddles / services */
class DomApi extends DomApiBase  with Logging {

  /** API msg sent to wiki#section */
  def wreact(cwid: CMDWID) = Action.async { implicit request =>
    val stok = ROK.r
    val errors = new ListBuffer[String]()

    val resultMode = stok.query.getOrElse("resultMode", "")

    cwid.wid.map(stok.prepWid).flatMap(wid => wid.page.orElse {
      // 1. figure out the specs
      if ((wid.cat == "Spec" || wid.cat == "Story") && wid.name == "fiddle") {
        val x = Autosave.find(s"DomFid${wid.cat}." + stok.realm + ".", stok.au.map(_._id)).flatMap(_.get("content")).mkString
        val page = new WikiEntry(wid.cat, "fiddle", "fiddle", "md", x, stok.au.map(_._id).getOrElse(new ObjectId()), Seq("dslObject"), stok.realm)
        Some(page)
      } else None
    }).map { we =>
      // 2. run it, if page exists
      val PAT = """(\w*)/(\w*)""".r
      val PAT(e, a) = cwid.rest

      if (stok.query.contains("dfiddle")) {
        // just a dfiddle section
        val newWid = we.wid.copy(section = stok.query.get("dfiddle"))

        // if the page had existed, remove the current fiddle from it and use this page as a source
        val newPage = if (we.name == "fiddle") None else
          Some(new WikiEntry(we.wid.cat, "fiddle", "fiddle", "md",
            mkC(we.content, "", "dfiddle", stok.query.get("dfiddle").mkString),
            stok.au.map(_._id).getOrElse(new ObjectId()), Seq("dslObject"), stok.realm))
        irunDom(e, a, Some(newWid), None, None, newPage).apply(request)
      } else {
        // normal full page / section
        irunDom(e, a, Some(we.wid)).apply(request)
      }
    } getOrElse {
      // 3. oops, no page found
      errors append "WPath not found: [[" + cwid.wpath.mkString + "]]"

      val ret = if ("value" == resultMode) {
        Ok("")
      } else {
        // multiple values as json
        var m = Map(
          "values" -> Map.empty,
          "failureCount" -> 0,
          "errors" -> errors.toList
        )

        retj << m
      }

      Future.successful(ret)
    }
  }

  /** API msg sent to reactor */
  def react(e: String, a: String) = Action.async { implicit request =>
    irunDom(e, a, None).apply(request)
  }

  /** execute message to given reactor
    *
    * @param useThisStory  if nonEmpty then will use this (find it first) plus blender
    * @param useThisStoryPage if nonEmpty then will use this plus blender
    */
  private def irunDom(e: String, a: String, useThisStory: Option[WID], useThisStoryPage: Option[WikiEntry] = None, useThisSpec: Option[WID] = None, useThisSpecPage: Option[WikiEntry] = None) = RActiona { implicit stok =>
    val reactor = stok.realm

    Audit.logdb("DIESEL_FIDDLE_iRUNDOM", stok.au.map(_.userName).getOrElse("Anon"), s"EA : $e.$a", "spec: " + useThisSpec + " story: " + useThisStory)

    val settings = DomEngineHelper.settingsFrom(stok)
    val userId = settings.userId.map(new ObjectId(_)) orElse stok.au.map(_._id)

    val RES_API =
      """
                    |Send result mode to control output:
                    | * val one single value, no matter what
                    | * json one or more values as Json
                    | * tree include the tree
                    |
                    | The Json always includes any errors.
                  """.stripMargin
    // setup a default page, eith erempty or what the user wanted
    val page =
        new WikiEntry("Spec", "fiddle", "fiddle", "md",
          useThisSpecPage.map(_.content).orElse(useThisSpec.flatMap(_.content)).getOrElse("") ,
          stok.au.map(_._id).getOrElse(NOUSER), Seq("dslObject"), stok.realm)
    val pages = if(settings.blenderMode) { // blend all specs and stories
      val stories = if(settings.sketchMode) Wikis(reactor).pages("Story")./*filter(_.name != stw.get.name).*/toList else Nil
      val specs = Wikis(reactor).pages("Spec").toList
      val d = (specs ::: stories).map{ p=> // if draft mode, find the auto-saved version if any
        if(settings.draftMode) {
          val c = Autosave.find("DomFid"+p.category+"."+reactor+ "."+ p.wid.wpath, userId).flatMap(_.get("content")).mkString
          if(c.length > 0)  p.copy(content=c)
          else p
        } else p
      }
      d
    } else { //no blender - use either specified or fiddle
      val spec =
        if(useThisSpecPage.isDefined || useThisSpec.isDefined) "" else { // use the contents of the fiddle
          Autosave.find("DomFidSpec."+reactor+".", userId).flatMap(_.get("content")).mkString
        }
      val page = new WikiEntry("Spec", "fiddle", "fiddle", "md", spec, stok.au.map(_._id).getOrElse(NOUSER), Seq("dslObject"), stok.realm)
      //      WikiDomain.domFrom(page).get.revise.addRoot
      List(page)
    }

    // to domain
    val dom = pages.
      flatMap( p=>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
//      WikiDomain.domFrom(p).toList
    ).foldLeft(WikiDomain.domFrom(page).get)((a, b) => a.plus(b)).revise.addRoot

    // make up a story with the input
    // add all the parms passed in as query parms
    var story = "$msg "+e+"."+ a+" ("+stok.query.filter(x=> !DomEngineSettings.FILTER.contains(x._1)).map(t=>t._1+"=\"" + t._2+"\"").mkString(",")+")\n"
    clog << "STORY: " + story

    val story2 = if(settings.sketchMode && useThisStoryPage.isEmpty) { // in sketch mode, add the temp fiddle tests - filter out messages, as we already have one
      useThisStory.map { p=> Autosave.find("DomFidStory."+ reactor+"."+p.wpath, userId).flatMap(_.get("content")) getOrElse p.content.mkString
      } getOrElse Autosave.find("DomFidStory."+reactor+".", userId).flatMap(_.get("content")).mkString
    } else if(useThisStory.isDefined) {
      useThisStoryPage.map(_.content).getOrElse(useThisStory.get.content.mkString)
    } else ""

    story = story + "\n"+ story2.lines.filterNot(x=>
      x.trim.startsWith("$msg") || x.trim.startsWith("$receive")
    ).mkString("\n")+"\n"

    val ipage = new WikiEntry("Story", "fiddle", "fiddle", "md", story, stok.au.map(_._id).getOrElse(NOUSER), Seq("dslObject"), stok.realm)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = ""

    val root =DomAst("root", "root")
    addStoryToAst(root, List(ipage))

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom plus idom, root, settings, ipage :: pages map WikiDomain.spec)
    setHostname(engine.ctx)

    engine.process.map { engine =>
      val errors = new ListBuffer[String]()

      // find the spec and check its result
      // then find the resulting value.. if not, then json
      val oattrs = dom.moreElements.collect {
        case n: EMsg if n.entity == e && n.met == a => n
      }.headOption.toList.flatMap(_.ret)

      if (oattrs.isEmpty) {
        errors append s"Can't find the spec for $e.$a"
      }

      import razie.diesel.ext.stripQuotes

      // collect values
      val valuesp = root.collect {
        case d@DomAst(EVal(p), /*"generated"*/ _, _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => p
      }
      val values = valuesp.map(p=> (p.name, p.dflt))

      if ("value" == settings.resultMode || "" == settings.resultMode && oattrs.size == 1) {
        // one value - take last so we can override within the sequence
        val res = values.lastOption.map(_._2).getOrElse("")
        if(valuesp.lastOption.exists(_.ttype == WTypes.JSON))
          Ok(stripQuotes(res)).as("application/json")
        else
          Ok(stripQuotes(res))
      } else {
        // multiple values as json
        var m = Map(
          "values" -> values.toMap,
          "failureCount" -> engine.failedTestCount,
          "errors" -> errors.toList,
          "dieselTrace" -> DieselTrace(root, settings.node, engine.id, "diesel", "runDom", settings.parentNodeId).toJson
        )

        if ("treeHtml" == settings.resultMode) m = m + ("tree" -> root.toHtml)
        if ("treeJson" == settings.resultMode) m = m + ("tree" -> root.toJson)

        if ("debug" == settings.resultMode) {
          Ok(root.toString).as("application/text")
        } else if ("dieselTree" == settings.resultMode) {
          val m = root.toj
          val y = DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
          val x = js.tojsons(y.toj).toString
          Ok(x).as("application/json")
        } else
          Ok(js.tojsons(m).toString).as("application/json")
      }
    }
  }

  /** execute message to given reactor
    *
    * not in a context of a request, but client-side API
    */
  def runDom(msg:String, specs:List[WID], stories: List[WID], settings:DomEngineSettings) : Future[Map[String,Any]] = {
    val realm = specs.headOption.map(_.getRealm).mkString
    val page = new WikiEntry("Spec", "fiddle", "fiddle", "md", "", NOUSER, Seq("dslObject"), realm)

    Audit.logdb("DIESEL_FIDDLE_RUNDOM")
    val pages = (specs ::: stories).filter(_.section.isEmpty).flatMap(_.page)

    // to domain
    val dom = WikiDomain.domFrom(page, pages)

    // make up a story
    val FILTER = Array("sketchMode", "mockMode", "blenderMode", "draftMode")
    var story = if (msg.trim.startsWith("$msg")) msg else "$msg " + msg
    clog << "STORY: " + story

    // todo this has no EPos - I'm loosing the epos on sections
    // put together all sections
    val story2 = (specs ::: stories).filter(_.section.isDefined).flatMap(_.content).mkString("\n")
    story = story + "\n" + story2.lines.filterNot(x =>
      x.trim.startsWith("$msg") || x.trim.startsWith("$receive")
    ).mkString("\n") + "\n"

    val ipage = new WikiEntry("Story", "fiddle", "fiddle", "md", story, NOUSER, Seq("dslObject"), realm)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = ""

    val root = DomAst("root", "root")
    addStoryToAst(root, List(ipage))

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom plus idom, root, settings, ipage :: pages map WikiDomain.spec)

    engine.process.map { engine =>

      val errors = new ListBuffer[String]()

      // find the spec and check its result
      // then find the resulting value.. if not, then json
      val oattrs = dom.moreElements.collect {
        //      case n:EMsg if n.entity == e && n.met == a => n
        case n: EMsg if msg.startsWith(n.entity + "." + n.met) => n
      }.headOption.toList.flatMap(_.ret)

      if (oattrs.isEmpty) {
        errors append s"Can't find the spec for $msg"
      }

      import razie.diesel.ext.stripQuotes

      // collect values
      val values = root.collect {
        case d@DomAst(EVal(p), /*"generated"*/ _, _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => (p.name, p.dflt)
      }

      var m = Map(
        "value" -> values.headOption.map(_._2).map(stripQuotes).getOrElse(""),
        "values" -> values.toMap,
        "failureCount" -> engine.failedTestCount,
        "errors" -> errors.toList,
        "root" -> root,
        "dieselTrace" -> DieselTrace(root, settings.node, engine.id, "diesel", "runDom", settings.parentNodeId).toJson
      )
      m
    }
  }

  /** calc the diff draft to original for story and spec */
  def diffDom(id: String) = FAUPRAPI(true) { implicit stok =>
    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = stok.formParm("spec")
    val story = stok.formParm("story")

    //1. which wids were you looking at last?
    DomWorker later AutosaveSet("DomFidPath." + reactor, stok.au.get._id, Map(
      "specWpath" -> specWpath,
      "storyWpath" -> storyWpath
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    import scala.collection.JavaConversions._

    def diffTable(p: Patch) = s"""<small>${views.html.admin.diffTable("", p, Some(("How", "Orig", "Autosaved")))}</small>"""

    def diffT = diffTable(DiffUtils.diff(stw.lines.toList, story.lines.toList))

    def diffP = diffTable(DiffUtils.diff(spw.lines.toList, spec.lines.toList))

    retj << Map(
      "specDiff" -> (if (specWpath.length > 0) diffP else ""),
      "storyDiff" -> (if (storyWpath.length > 0) diffT else "")
    )
  }

  // view an AST from teh collection
  def viewAst(id: String, format: String) = FAUR { implicit stok =>
    DomGuardian.withAsts {asts=>
      asts.find(_._2 == id).map { ast =>
        if (format == "html")
          Ok(ast._3.toHtml)
        else
          Ok(ast.toString())
      }.getOrElse {
        NotFound("ast not found")
      }
    }
  }

  // list the collected ASTS
  def listAst = FAUR { implicit stok =>
    DomGuardian.withAsts {asts=>
      val x = js.tojsons(asts.map(_._1), 1)
      Ok(x.toString).as("application/json")
    }
  }

  def postAst(stream: String, id: String, parentId: String) = FAUPRaAPI(true) { implicit stok =>
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

    val engine = prepEngine(xid, settings, stok.realm, Some(root), true, stok.au)

    // decompose test nodes and wait
    engine.processTests.map { engine =>
      DomGuardian.collectAst(stream, xid, root)

      var ret = Map(
        "ok" -> "true",
        "failureCount" -> engine.failedTestCount,
        "successCount" -> engine.successTestCount
      )

      Ok(js.tojsons(ret).toString).as("application/json")
    }
  }


  /**
    * deal with a REST request. use the in/out for message
    *
    * force the raw parser, to deal with all content types
    */
  def runRest(path: String, verb:String, mock:Boolean) = Action(parse.raw) { implicit request =>
    val stok = razRequest

    stok.qhParm("dieselHttpResponse").filter(_ != "200").map {code =>
      new Status(code.toInt)
        .apply("template not found for path: "+path)
        .withHeaders("diesel-reason" -> s"client requested dieselHttpResponse $code in realm ${stok.realm}")
    }.getOrElse {

      val PAT = """(\w*)\.(\w*)""".r
      val PAT(e, a) = path

      val uid = stok.au.map(_._id).getOrElse(NOUSER)

      val body = request.body.asBytes().map(a => new String(a)).getOrElse("")
      val content = Some(new EEContent(body, request.contentType.mkString))

      val settings = DomEngineHelper.settingsFromRequestHeader(stok.req, content)
      settings.mockMode = mock

      clog << s"RUN_REST_REQUEST verb:$verb mock:$mock path:$path realm:${stok.realm}\nheaders: ${request.headers}" + body

      val engine = prepEngine(new ObjectId().toString,
        settings,
        stok.realm,
        None,
        false,
        stok.au,
        // empty story so nothing is added to root
        List(new WikiEntry("Story", "temp", "temp", "md", "", uid, Seq("dslObject"), stok.realm))
      )

      // does the current request match the template?
      def matchesRequest(tpath: String, rpath: String) = {
        val a = rpath.split("/")
        val b = tpath.split("/")

        a.zip(b).foldLeft(true)((a, b) => a && b._1 == b._2 || b._2.matches("""\$\{([^\}]*)\}"""))
      }

      // message specified return mappings, if any
      val inSpec = spec(e, a)(engine.ctx).toList.flatMap(_.attrs)
      val outSpec = spec(e, a)(engine.ctx).toList.flatMap(_.ret)

      // collect any parm specs
      val inSpecs = inSpec.map(p => (p.name, p.dflt, p.expr.mkString))
      val outSpecs = outSpec.map(p => (p.name, p.dflt, p.expr.mkString))

      // find template
      engine.ctx.findTemplate(path).map { template =>
        val sc = EESnakk.parseTemplate(Some(template), template.content, Nil)

        // turn template into regex
        var re = sc.content.replaceAll("""\$\{(.+)\}""", "$1")
        re = re.replaceAll("""\$(\w+)""", "(?<$1>.*)")
        re = re.replaceAll("""[\r\n ]""", """\\s*""")
        re = "(?sm)" + re + ".*"

        // find and make message with parms
        val jrex = Pattern.compile(re).matcher(body)
        //          val hasit = jrex.find()
        val parms = if (jrex.find())
          (
            for (g <- inSpecs)
              yield (
                g._1,
                Try {
                  jrex.group(g._1)
                }.getOrElse(g._2)
              )
            ).toList
        else Nil

        // execute message
        val msg = new EMsg("", e, a, parms.map(p => P(p._1, p._2)))

        RDExt.addMsgToAst(engine.root, msg)

        val res = engine.process.map { engine =>
          //        val res = engine.extractValues(e, a)

          // find output template and format output
          val templateResp = engine.ctx.findTemplate(e + "." + a, "resp")
          templateResp.map { t =>
            //          val s = EESnakk.formatTemplate(t.content, ECtx(res))
            // engine.ctx accumulates results and we add the input

            // templates strt with a \n normally
            val content = t.content.replaceFirst("^\\n", "")

            val allValues = new StaticECtx(msg.attrs, Some(engine.ctx))
            val s = EESnakk.formatTemplate(content, allValues)

            clog << s"RUN_REST_REPLY $verb $mock $path\n" + s
            val ctype = t.parms.find(_._1.compareToIgnoreCase("content-type") == 0).map(_._2).map(stripQuotes).getOrElse("text/plain")
            Ok(s).as(ctype)
          }.getOrElse {
            clog << s"RUN_REST_REQUEST $verb $mock $path\n" + "No template"
            Ok(s"No response template for $e $a")
              .withHeaders("diesel-reason" -> s"response template not found for $path in realm ${stok.realm}")
          }
        }
        Await.result(res, Duration("5seconds"))
      } getOrElse {
        //      Future.successful(
        NotFound("template not found for path: " + path)
          .withHeaders("diesel-reason" -> s"template not found for $path in realm ${stok.realm}")
        //      )
      }
      //          val tpath = if(turl startsWith "http://") {
      //            turl.replaceFirst("https?://", "").replaceFirst(".*/", "/")
      //          } else turl
      //          matchesRequest(tpath, stok.req.path)
    }
  }

  def mock(path: String) = runRest(path, "GET", true)
  def mockPost(path: String) = runRest(path, "POST", true)

  def rest(path: String) = runRest(path, "GET", false)
  def restPost(path: String) = runRest(path, "POST", false)

  /** proxy real service GET */
  def proxy(path: String) = RAction { implicit stok =>
    val engine = prepEngine(new ObjectId().toString,
      DomEngineHelper.settingsFrom(stok),
      stok.realm,
      None,
      false,
      stok.au)

    val q = stok.req.queryString.map(t => (t._1, t._2.mkString))

    // does the current request match the template?
    def matchesRequest(tpath: String, rpath: String) = {
      val a = rpath.split("/")
      val b = tpath.split("/")

      a.zip(b).foldLeft(true)((a, b) => a && b._1 == b._2 || b._2.matches("""\$\{([^\}]*)\}"""))
    }

    //    val template = engine.ctx.specs.flatMap(_.templateSections.filter{t=>
    //      val turl = EESnakk.parseTemplate(t.content).url
    //      val tpath = if(turl startsWith "http://") {
    //        turl.replaceFirst("https?://", "").replaceFirst(".*/", "/")
    //      } else turl
    //      matchesRequest(tpath, stok.req.path)
    //    }).headOption
    //    val body = body("")

    Ok("haha").as("application/json")
  }

  /** proxy real service GET */
  def proxyPost(path: String) = RAction { implicit stok =>
    Ok("haha").as("application/json")
  }

  /** roll up and navigate the definitions */
  def navigate() = FAUR { implicit stok =>

    val engine = prepEngine(new ObjectId().toString,
      DomEngineHelper.settingsFrom(stok),
      stok.realm,
      None,
      false,
    stok.au)

    val msgs = RDExt.summarize(engine.dom).toList

    ROK.k reactorLayout12 {
      views.html.modules.diesel.navigateMsg(msgs)
    }
  }

  /** */
  def getEngineConfig() = FAUR { implicit stok =>
    val config = Autosave.OR("DomEngineConfig." + stok.realm, stok.au.get._id,
      DomEngineHelper.settingsFromRequest(stok.req).toJson
    )

    retj << config
  }

  /** roll up and navigate the definitions */
  def setEngineConfig() = FAUR { implicit stok =>
    val jconfig = stok.formParm("DomEngineConfig")
    val jmap = js.parse(jconfig)
    //    val cfg = DomEngineSettings.fromRequest(stok.req)
    val cfg = DomEngineSettings.fromJson(jmap.asInstanceOf[Map[String, String]])

    DomWorker later AutosaveSet("DomEngineConfig." + stok.realm, stok.au.get._id,
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

  /** roll up and navigate the definitions */
  def engineView(id: String) = FAUR { implicit stok =>
    if (!ObjectId.isValid(id)) {
      // list all engines
      Ok(DieselAppContext.engines.map { e =>
        s"""<a href="/diesel/engine/view/${e.id}">${e.id}</a><br> """
      }.mkString).as("text/html")
    } else {
      DieselAppContext.engMap.get(id).map { eng =>

        stok.fqhoParm("format", "html").toLowerCase() match {

          case "json" => {
            var m = Map(
              //      "values" -> values.toMap,
              "failureCount" -> eng.failedTestCount,
              //      "errors" -> errors.toList,
              "dieselTrace" -> DieselTrace(eng.root, eng.settings.node, eng.id, "diesel", "runDom", eng.settings.parentNodeId).toJson,
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
      } getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.engineView(None)
        }
      }
    }
  }

  /** replace in oldContent the given section with iContent (escaped) */
  def mkC(oldContent: String, icontent: String, sType: String, sName: String) = {
    val re = s"(?s)\\{\\{(\\.?)$sType $sName([ :,][^}]*)\\}\\}(.*)\\{\\{/$sType\\}\\}".r
    val res = re.replaceSomeIn(oldContent, { m =>
      Some {
        val c = icontent.replaceAll("""\\""", """\\\\""").replaceAll("\\$", "\\\\\\$")
        val s = s"{{$$1$sType ${sName}$$2}}\n${c}\n{{/$sType}}"
        s
      }
    })
    res
  }

  /**
    * api to overwrite DSL sections remotely
    *
    * we don't allow anons to modify any wiki content, but we can build a small
    * cache of overwrites, based on DomSessions, where we can simulate as if
    * they overwrote the content
    */
  def anonSetFiddleSection(wid: WID) = RAction { implicit request =>
    // see Wikie.setSection

    val sType = request.formParm("sectionType")
    val sName = request.formParm("sectionName")
    val icontent = request.formParm("content")
    val au = request.au

    Audit.logdb("DIESEL_FIDDLE_ANON_SET", au.map(_.userName).getOrElse("Anon"), wid.wpath, icontent)

    // todo share with Wikie.setSection

    (for (
      w <- wid.page orErr s"$wid not found";
      newVerNo <- Some(w.ver + 1);
      newC <- Some(mkC(w.content, icontent, sType, sName));
      // too annoying to fail if no change
      //        _ <- (w.content != newC) orErr ("no change");
      newVer <- Some(w.copy(content = newC, ver = newVerNo, updDtm = DateTime.now()));
      session <- request.req.cookies.get("dieselSessionId").flatMap(s => DomSessions.sessions.get(s.value)) orErr "no session"
    ) yield {
      log("Wiki.setSection " + wid)
      var we = newVer

      session.time = System.currentTimeMillis()
      session.overrides.prepend(Over(wid, newVer, icontent, sType, sName))

      // just from the fiddle, not the entire page
      var links = WikiDomain.domFilter(newVer.copy(content = icontent + "\n")) {
        case st: EMsg =>
          st.toHref(sName)
        case st: EMock =>
          st.rule.e.asMsg.withPos(st.pos).toHref(sName, "value") +
            " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", sName, "json") + ") " +
            " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("debug", sName, "debug") + ") "
      }.map { l =>
        l.replaceAll(
          "href=\"/diesel/wreact/([^\"]+)\"",
          "href=\"/diesel/anon/wiki/$1&dieselSessionId=" + session.id + "\"")
      }.mkString("\n")

      if (links == "") links = "no recognized messages"

      Ok(js.tojson(Map("links" -> links)).toString).as("application/json")
    }).getOrElse {
      Unauthorized("Oops [anonSetSection] " + request.errCollector.mkString)
    }
  }

  /** API msg sent to reactor */
  def anonRunWiki(cwid: CMDWID) = RActiona { implicit stok =>
    val sid = stok.fqhParm("dieselSessionId")

    (for (
    // we expect the page to have done a getSession first
      existingSession <- sid.flatMap(s => DomSessions.sessions.get(s)) orErr ("no session found: " + sid);
      anyOverwrites <- cwid.wid.flatMap(wid => existingSession.overrides.find(_.wid == wid)) orErr "no session override found"
    ) yield {
      //      val we = cwid.wid.map(stok.prepWid).flatMap(wid => session.overrides.get(wid)).get.page
      val we = cwid.wid.flatMap(wid => existingSession.overrides.find(_.wid == wid)).get.page
      val PAT = """([\w.]*)/(\w*)""".r
      val PAT(e, a) = cwid.rest

      val nw =
        if (stok.query.contains("dfiddle")) we.wid.copy(section = stok.query.get("dfiddle"))
        else we.wid

      Audit.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("Anon"))
      irunDom(e, a, Some(nw), Some(we.copy(content = anyOverwrites.newContent))).apply(stok.req)
    }).getOrElse {
      Future {
        Unauthorized("Session not found... it expired or there's too much load...")
      }
    }
  }

  /** anon fiddle - with tests etc */
  def anonRunFiddle(cwid: CMDWID) = RActiona { implicit stok =>
    // todo commonalities with fiddleStoryUpdated
    val sid = stok.req.cookies.get("dieselSessionId")
    val session = sid.flatMap(s => DomSessions.sessions.get(s.value))
    val dfiddle = stok.query.get("dfiddle")
    val anySpecOverwrites = session.flatMap { existingSession=>
      cwid.wid.flatMap(wid => existingSession.overrides.find(o=>
        o.wid == wid && o.sName == (dfiddle.mkString+":spec"))
      )
    }
    val anyStoryOverwrites = session.flatMap { existingSession=>
      cwid.wid.flatMap(wid => existingSession.overrides.find(o=>
        o.wid == wid && o.sName == (dfiddle.mkString+":spec"))
      )
    }

    Audit.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("Anon"))

    val settings = DomEngineHelper.settingsFrom(stok)

    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")
    val spec = anySpecOverwrites.map(_.newContent) getOrElse stok.formParm("spec")
    val story = anyStoryOverwrites.map(_.newContent) getOrElse stok.formParm("story")

    val uid = stok.au.map(_._id).getOrElse(NOUSER)

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
    val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

    val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), stok.realm)
    val pages = List(page)

    // todo is adding page twice...
    val dom = pages.flatMap(p=>
      if(anySpecOverwrites.isDefined)
        WikiDomain.domFrom(p).toList
      else
        SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a,b) => a.plus(b)).revise.addRoot

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), stok.realm)

    var res = ""
    var captureTree = ""

    val root = {
      val d = DomAst("root", AstKinds.ROOT).withDetails("(from story)")
      addStoryToAst(d, List(ipage))
      d
    }

    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec)
    setHostname(engine.ctx)

    // decompose all tree or just testing? - if there is a capture, I will only test it
    val fut =
      engine.process

    fut.map {engine =>
      res += engine.root.toHtml

      val m = Map(
        "res" -> res,
        "capture" -> captureTree,
        "ca" -> RDExt.toCAjmap(dom plus idom), // in blenderMode dom is full
        "failureCount" -> engine.failedTestCount
      )

      retj << m
    }

  }

  def quickBadge(failed:Int, total:Int, duration:Long) = {
    if (failed > 0)
      s"""<a href="/diesel/report"><span class="badge" style="background-color: red" title="Guardian: tests failed ($duration msec)">$failed / $total </span></a>"""
    else if(total > 0)
      s"""<a href="/diesel/report"><span class="badge" style="background-color: green" title="Guardian: all tests passed ($duration msec)">$total </span></a>"""
    else
      s"""<a href="/diesel/report"><span class="badge" style="background-color: orange" title="Guardian is offline!">... </span></a>"""
  }

  def status = RActiona { implicit stok =>
    stok.au.map {au=>
      val failed = 1
      val total=12
      DomGuardian.findLastRun(stok.realm, au.userName).map {r=>
        Future.successful {
          Ok(quickBadge(r.failed, r.total, r.duration))
        }
      }.getOrElse {
        // start a check in the background
        val eid = startCheck (stok.realm, stok.au)
        clog << s"DIESEL startCheck ${stok.realm} for ${stok.au.map(_.userName)}"
        //        Ok(quickBadge(0,0))
        eid._2.map { r =>
          Ok(quickBadge(r.failed, r.total, r.duration))
        }
      }
    }.getOrElse {
      Future.successful {
        Ok("")
      }
    }
  }

  // todo implement and optimize
  def report = RActiona { implicit stok =>
    // simulate - this should run all the time when stories change

    stok.au.map {au=>
      val failed = 1
      val total=12
      DomGuardian.findLastRun(stok.realm, au.userName).map {r=>
        Future.successful {
          ROK.k reactorLayout12 {
            new Html(
s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${quickBadge(r.failed, r.total, r.duration)}<br>
<small>${DomGuardian.stats}</small><br><br>""".stripMargin +
              views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
              )
          }
          //          Redirect(s"""/diesel/engine/view/${r.engine.id}""")
        }
      }.getOrElse {
        val eid = startCheck (stok.realm, stok.au)
        eid._2.map { engine =>
          Ok(s"""no run available - check this later - <a href="/diesel/engine/view/${eid._1}">report</a>""").as("text/html")
        }
      }
    }.getOrElse {
      Future.successful {
        Ok("need to login")
      }
    }
  }

  def runCheck = RActiona { implicit stok =>
      // simulate - this should run all the time when stories change

      stok.au.map {au=>
          val eid = startCheck (stok.realm, stok.au)
          eid._2.map { engine =>
            Redirect(s"""/diesel/report""")
          }
      }.getOrElse {
        Future.successful {
          Ok("need to login")
        }
      }
    }

}


