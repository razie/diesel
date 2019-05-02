package mod.diesel.controllers

import controllers.RazRequest
import difflib.{DiffUtils, Patch}
import mod.diesel.controllers.DomGuardian.{addStoryToAst, catPages, prepEngine, startCheck}
import razie.diesel.utils.DomHtml.quickBadge
import mod.diesel.controllers.DomSessions.Over
import mod.diesel.model._
import mod.diesel.model.exec.EESnakk
import model._
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
import razie.diesel.ext._
import razie.diesel.utils.{DomCollector, SpecCache}
import razie.hosting.Website
import razie.tconf.DTemplate
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{Logging, js}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/** the incoming message / request
  *
  * @param uri
  * @param protocol
  * @param method
  * @param contentType
  * @param body
  */
case class DomReq (
                    uri:String,
                    protocol:String,
                    method:String,
                    contentType:String,
                    body:String
                  ) {
  def this (req : Request[AnyContent]) =
    this (
      req.uri,
      "http",
      req.method,
      req.contentType.mkString,
      req.body.toString)

  def toj = Map (
    "uri" -> uri,
    "protocol" -> protocol,
    "method" -> method,
    "contentType" -> contentType,
    "body" -> body
  )

  override def toString = razie.js.tojsons(this.toj)

  /** validate that incoming chars are parseable later */
  def validate = {
    Try {
      val s = razie.js.tojsons(this.toj)
      val j = razie.js.parse(s)
    }
  }

  def addTo (e:ECtx) = e.put(P("request", this.toString).withValue(this))
}

/** controller for server side fiddles / services */
class DomApi extends DomApiBase  with Logging {

  /** API msg sent to wiki#section
    *
    * find the named fiddles, make an engine and run the requested message on that engine
    */
  def wreact(cwid: CMDWID) = Filter(noRobots).async { implicit stok =>
    val errors = new ListBuffer[String]()

    val resultMode = stok.query.getOrElse("resultMode", "")

    cwid.wid.map(stok.prepWid).flatMap(wid => wid.page.orElse {
      // 1. figure out the specs
      if ((wid.cat == "Spec" || wid.cat == "Story") && wid.name == "fiddle") {
        val x = Autosave.find(s"wikie", WID("","").r(stok.realm), stok.au.map(_._id)).flatMap(_.get("content")).mkString
        val page = new WikiEntry(
          wid.cat,
          "fiddle",
          "fiddle",
          "md",
          x,
          stok.au.map(_._id).getOrElse(new ObjectId()),
          Seq("dslObject"),
          stok.realm
        )
        Some(page)
      } else None
    }).map { we =>
      // 2. run it, if page exists
      val path = cwid.rest

      // what if it's just a dfiddle section within a page
      if (stok.query.contains("dfiddle")) {
        val newWid = we.wid.copy(section = stok.query.get("dfiddle"))
        val fidName = stok.query.get("dfiddle").mkString

        // if the page had existed, remove the current fiddle from it and use this page as a source
        var newSpecPage =
          if (we.name == "fiddle") None
          else
          Some(new WikiEntry(we.wid.cat, /*we.wid.name+"#"+newWid.section.mkString*/"yyfiddle", "fiddle", "md",
            mkC(we.content, "", "dfiddle", fidName+""),
            // todo replace the spec and the fiddle individually when todo in mkC greedy is fixed
//            mkC(we.content, "", "dfiddle", fidName+":spec"),
            stok.au.map(_._id).getOrElse(new ObjectId()), Seq("dslObject"), stok.realm))

        // new - why was I using the page when I entangle the fiddles anyways?
        newSpecPage = None

        // add all entangled fiddles too
        val pspec = DomGuardian.sectionsToPages(
          we,
          we.sections.filter(s=>s.stype == "dfiddle" && (Array("spec") contains s.signature) && (
            s.name == fidName ||  // entangle same name
            s.args.get("includeFor").exists(pat=> fidName.matches(pat)) // or if it's meant to be included
            ))
        )

        irunDom(path, Some(newWid), None, newSpecPage.toList ::: pspec)
      } else {
        // normal full page / section - include all sections
        val pspec = DomGuardian.sectionsToPages(
          we,
          we.sections.filter(s=>s.stype == "dfiddle" && (Array("spec") contains s.signature))
        )

        irunDom(path, Some(we.wid), None, pspec)
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
          "totalCount" -> 0,
          "failureCount" -> 0,
          "errors" -> errors.toList
        )

        retj << m
      }

      Future.successful(ret)
    }
  }

  /** API msg sent to reactor */
  def react(e: String, a: String) = Filter(noRobots).async { implicit stok =>
    // insert a dot only if needed
    val ea = (
      if(e.length > 0 && a.length > 0) e + "." + a else e+a
      ).replaceAllLiterally("/", ".")
    irunDom(ea, None)
  }

  /** execute message to given reactor
    *
    * @param is the useful path (without prefix). Either an e.a or e/a or template match
    * @param useThisStory  if nonEmpty then will use this (find it first) plus blender
    * @param useThisStoryPage if nonEmpty then will use this plus blender
    */
  private def irunDom(path: String, useThisStory: Option[WID], useThisStoryPage: Option[WikiEntry] = None, useThisSpecPage: List[WikiEntry] = Nil) (implicit stok:RazRequest) : Future[Result] = {

    val reactor = stok.website.dieselReactor
    val website = Website.forRealm(reactor).getOrElse(stok.website)
    val xapikey = website.prop("diesel.xapikey")

    // see if client wanted to force a response code
    stok.qhParm("dieselHttpResponse").filter(_ != "200").map {code =>
      Future.successful( new Status(code.toInt)
        .apply("client requested code: "+code)
        .withHeaders("diesel-reason" -> s"client requested dieselHttpResponse $code in realm ${stok.realm}")
      )
    }.getOrElse {

      Audit.logdb("DIESEL_FIDDLE_iRUNDOM", stok.au.map(_.userName).getOrElse("Anon"), s"EA : $path", " story: " + useThisStory)

      var settings = DomEngineHelper.settingsFrom(stok)
      settings = settings.copy(realm = Some(reactor))
      val userId = settings.userId.map(new ObjectId(_)) orElse stok.au.map(_._id)

      val pages = if (settings.blenderMode) { // blend all specs and stories
        val stories = if (settings.sketchMode) catPages("Story", reactor). /*filter(_.name != stw.get.name).*/ toList else Nil
        val specs = catPages("Spec", reactor).toList
        val d = (specs ::: stories).map { p => // if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("wikie", p.wid.defaultRealmTo(reactor), userId).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        d
      } else { //no blender - use either specified or fiddle
        val spec =
          if (useThisSpecPage.nonEmpty) "" else { // use the contents of the fiddle
            Autosave.find("wikie", WID("", "").r(reactor), userId).flatMap(_.get("content")).mkString
          }
        val page = new WikiEntry("Spec", "fiddle", "fiddle", "md", spec, stok.au.map(_._id).getOrElse(NOUSER), Seq("dslObject"), reactor)
        List(page)
      }

      // to domain
      val dom =
        ((pages ::: useThisSpecPage).flatMap(p => SpecCache.orcached(p, WikiDomain.domFrom(p)).toList))
          .foldLeft(RDomain.empty)((a, b) => a.plus(b)).revise.addRoot

      val story2 = if (settings.sketchMode && useThisStoryPage.isEmpty) { // in sketch mode, add the temp fiddle tests - filter out messages, as we already have one
        useThisStory.map { p =>
          Autosave
            .find("wikie", p.defaultRealmTo(reactor), userId)
            .flatMap(_.get("content")) getOrElse p.content.mkString
        } getOrElse Autosave.find("wikie", WID("","").r(reactor), userId).flatMap(_.get("content")).mkString
      } else if (useThisStory.isDefined) {
        useThisStoryPage.map(_.content).getOrElse(useThisStory.get.content.mkString)
      } else ""

      val story = /*story + "\n"+ */ story2.lines.filterNot(x =>
        x.trim.startsWith("$msg") || x.trim.startsWith("$receive")
      ).mkString("\n") + "\n"

      val ipage = new WikiEntry("Story", "xxfiddle", "fiddle", "md", story, stok.au.map(_._id).getOrElse(NOUSER), Seq("dslObject"), reactor)
      val idom = WikiDomain.domFrom(ipage).get.revise addRoot

      var res = ""

      val root = DomAst("root", "root")
      // no need to - passing ipage to the engine as the list of stories to use anyhow, this is duplicate
      // addStoryToAst(root, List(ipage))

      // start processing all elements
      val engine = DieselAppContext.mkEngine(
        dom plus idom,
        root,
        settings,
        // storyPage may be a temp fiddle, so first try the story WID
        ipage :: pages ::: useThisSpecPage ::: useThisStory.flatMap(_.page).orElse(useThisStoryPage).toList map WikiDomain.spec,
        "irunDom:"+path
      )

      setHostname(engine.ctx.root)

      // find template matching the input message, to parse attrs
      val t@(trequest, e, a) = findEA(path, engine, useThisStory)

      // incoming message
      val msg: Option[EMsg] = findMessage(
        trequest,
        engine,
        path,
        Nil,
        Map.empty,
        stok.req.method,
        stok,
        e,
        a,
        body = engine.settings.postedContent.map(_.body).mkString,
        content = engine.settings.postedContent,
        stok.req.contentType)

      val xx = stok.qhParm("X-Api-Key").mkString
      def needsApiKey = xapikey.isDefined
      def isApiKeyGood = xapikey.isDefined && xapikey.exists { x =>
        x.length > 0 && x == xx
      }

      val isPublic = msg.exists(isMsgPublic(_, reactor, website))
      val isTrusted = isMemberOrTrusted(msg, reactor, website)

      clog << s"Message: $msg isPublic=$isPublic isTrusted=$isTrusted needsApiKey=$needsApiKey isApiKeyGood=$isApiKeyGood"

      if (
        isPublic ||
        isTrusted ||
        needsApiKey && isApiKeyGood
      ) {

        msg.map { msg =>
          RDExt.addMsgToAst(engine.root, msg)
        }

        engine.process.map { engine =>
          val errors = new ListBuffer[String]()

          // find the spec and check its result
          // then find the resulting value.. if not, then json
          val omsgs = dom.moreElements.collect {
            case n: EMsg if n.entity == e && n.met == a => n
          }.headOption.toList

          val oattrs = omsgs.flatMap(_.ret)

          if (oattrs.isEmpty) {
            val msgFound = if (omsgs.isEmpty) "msg NOT found" else "message found"
            errors append s"WARNING - Can't find the spec for $e.$a return type ($msgFound) !"
          }

          import razie.diesel.ext.stripQuotes

          // collect values
          val valuesp = root.collect {
            case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => p
          }
          val values = valuesp.map(p => (p.name, p.dflt))

          if (
            "value" == settings.resultMode || "" == settings.resultMode &&
            (oattrs.size == 1 || oattrs.size == 0 && values.size == 1)
            // either found msg def OR no msg def but just one value calculated... (payload)
          ) {
            // one value - take last so we can override within the sequence
            val res = values.lastOption.map(_._2).getOrElse("")
            if (valuesp.lastOption.exists(_.ttype == WTypes.JSON))
              Ok(stripQuotes(res)).as("application/json")
            else
              Ok(stripQuotes(res))
          } else {
            // multiple values as json
            var m = Map(
              "values" -> values.toMap,
              "totalCount" -> (engine.totalTestCount),
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

        // is message visible?
      } else if (msg.isDefined && !isMsgVisible(msg.get, reactor, website)) {
          info(s"Unauthorized msg access (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})")

          Future.successful(
            Unauthorized(s"Unauthorized msg access (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})")
          )

      } else {
        // good security keys for non-members (devs if logged in don't need security)
        info(s"Unauthorized msg access (key) for ${msg.map(m=>m.entity+m.met)}")

        Future.successful(
          Unauthorized(s"Unauthorized msg access (key) for ${msg.map(m=>m.entity+m.met)}")
        )
      }
    }
  }

  /** execute message to given reactor
    *
    * this is only used from the CQRS, internally - notice no request
    */
  def runDom(msg:String, specs:List[WID], stories: List[WID], settings:DomEngineSettings) : Future[Map[String,Any]] = {
    val realm = settings.realm getOrElse specs.headOption.map(_.getRealm).mkString
    val page = new WikiEntry("Spec", "fiddle", "fiddle", "md", "", NOUSER, Seq("dslObject"), realm)

    Audit.logdb("DIESEL_FIDDLE_RUNDOM ", msg)
    val pages = (specs ::: stories).filter(_.section.isEmpty).flatMap(_.page)

    // to domain
    val dom = WikiDomain.domFrom(page, pages)

    // make up a story
    val FILTER = Array("sketchMode", "mockMode", "blenderMode", "draftMode")
    var story = if (msg.trim.startsWith("$msg") || msg.trim.startsWith("$send")) msg else "$msg " + msg
    clog << "STORY: " + story

    // todo this has no EPos - I'm loosing the epos on sections
    // put together all sections
    val story2 = (specs ::: stories).filter(_.section.isDefined).flatMap(_.content).mkString("\n")
    story = story + "\n" + story2.lines.filterNot(x =>
      x.trim.startsWith("$msg") || x.trim.startsWith("$send")
    ).mkString("\n") + "\n"

    val ipage = new WikiEntry("Story", "fiddle", "fiddle", "md", story, NOUSER, Seq("dslObject"), realm)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = ""

    val root = DomAst("root", "root")
    addStoryToAst(root, List(ipage))

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom plus idom, root, settings, ipage :: pages map WikiDomain.spec, "runDom:"+msg)

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
        case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => (p.name, p.dflt)
      }

      var m = Map(
        "value" -> values.headOption.map(_._2).map(stripQuotes).getOrElse(""),
        "values" -> values.toMap,
        "totalCount" -> (engine.totalTestCount),
        "failureCount" -> engine.failedTestCount,
        "errors" -> errors.toList,
        "root" -> root,
        "dieselTrace" -> DieselTrace(root, settings.node, engine.id, "diesel", "runDom", settings.parentNodeId).toJson
      )
      m
    }
  }

  /**
    * deal with a REST request. use the in/out for message
    *
    * force the raw parser, to deal with all content types
    */
  def runRest(path: String, verb:String, mock:Boolean, imsg:Option[EMsg] = None, custom:Option[DomEngine => DomEngine] = None) : Action[RawBuffer] = Action(parse.raw) { implicit request =>
    implicit val stok = razRequest

    val reactor = stok.website.dieselReactor
    val website = Website.forRealm(reactor).getOrElse(stok.website)
    val xapikey = website.prop("diesel.xapikey")

    // see if client wanted to force a response code
    stok.qhParm("dieselHttpResponse").filter(_ != "200").map {code =>
      new Status(code.toInt)
        .apply("client requested code: "+code)
        .withHeaders("diesel-reason" -> s"client requested dieselHttpResponse $code in realm ${stok.realm}")
    }.getOrElse {

      // more testing options
      stok.qhParm("dieselSleep").map { code =>
        clog << s"MOCK DIESEL SLEEPING... $code ms"
        Thread.sleep(code.toInt)
      }

      val requestContentType = stok.req.contentType

      val uid = stok.au.map(_._id).getOrElse(NOUSER)

      val raw = request.body.asBytes()
      val body = raw.map(a => new String(a)).getOrElse("")
      val content = Some(new EEContent(body, stok.req.contentType.mkString, Map.empty, None, raw))

      // todo sort out this mess
      val settings = DomEngineHelper.settingsFromRequestHeader(stok.req, content).copy(realm=Some(reactor))
      settings.mockMode = mock
      val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))

      clog << s"RUN_REST_REQUEST verb:$verb mock:$mock path:$path realm:${reactor}\nheaders: ${stok.req.headers}" + body

      var engine = prepEngine(new ObjectId().toString,
        settings,
        reactor,
        None,
        false,
        stok.au,
        "DomApi.runRest:"+path,
        // empty story so nothing is added to root
        List(new WikiEntry("Story", "temp", "temp", "md", "", uid, Seq("dslObject"), reactor))
      )

      new DomReq(stok.req).addTo(engine.ctx)

      if(custom.isDefined)
        engine = custom.get.apply(engine)

      // find template matching the input message, to parse attrs
      val t@(trequest, e, a) =
        imsg.map(x=>(None, x.entity, x.met))
          .getOrElse(findEA(path, engine))

      // message specified return mappings, if any
      val inSpec = spec(e, a)(engine.ctx).toList.flatMap(_.attrs)
      val outSpec = spec(e, a)(engine.ctx).toList.flatMap(_.ret)

      // collect any parm specs
      val outSpecs = outSpec.map(p => (p.name, p.dflt, p.expr.mkString))

      // stuff like incoming content-type
      val incomingMetas = stok.headers.toSimpleMap

      // incoming message
      val msg : Option[EMsg] = imsg orElse findMessage (
        trequest,
        engine,
        path,
        inSpec,
        incomingMetas,
        verb,
        stok,
        e,
        a,
        body,
        content,
        requestContentType)

      clog << s"Message found: $msg xapikey=$xapikey"

      // is message visible?
      if (msg.isDefined && !isMsgVisible(msg.get, reactor, website)) {
        Unauthorized(s"Unauthorized msg access (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})")
      } else if (
        !isMemberOrTrusted(msg, reactor, website) &&
        xapikey.isDefined && xapikey.exists { x =>
          x.length > 0 && x != stok.qhParm("X-Api-Key").mkString
        }
        ) {
        // good security keys for non-members (devs if logged in don't need security)
          Unauthorized(s"Unauthorized msg access (key, dieselTrust)").withHeaders(
          )
      } else msg.map {msg=>

        RDExt.addMsgToAst(engine.root, msg)
        DomCollector.collectAst("runRest", stok.realm, engine.id, stok.au.map(_.id), engine, stok.uri)

        // process message
        val res = engine.process.map { engine =>

          clog << s"Engine done ... ${engine.id}"

          //        val res = engine.extractValues(e, a)

          // find output template and format output
          val templateResp =
            engine.ctx.findTemplate(e + "." + a, "response").orElse {
              // see if there is only one message child... and it has an output template - we'll use that one
              val m = engine
                .root
                .children
                // without hardcoded engine messages - diesel.rest is allowed
                .filterNot(x=>
                    x.value.isInstanceOf[EMsg] &&
                      x.value.asInstanceOf[EMsg].entity == "diesel" &&
                      x.value.asInstanceOf[EMsg].met != "rest")
                .head
                .children
                .find(_.children.headOption.exists(_.value.isInstanceOf[EMsg]) )

              m.flatMap { m =>
                val msg = m.children.head.value.asInstanceOf[EMsg]
                engine.ctx.findTemplate(msg.entity + "." + msg.met, "response")
              }
            }

          clog << engine.root.toString

          var response : Option[Result] = None

          var ctype =
            templateResp.flatMap(_.parm("content-type")) // response ctype
              .orElse(trequest.flatMap(_.parm("content-type"))) // or request ctype
              .getOrElse("text/plain") // or plain

          templateResp.map { t =>
            //          val s = EESnakk.formatTemplate(t.content, ECtx(res))
            // engine.ctx accumulates results and we add the input

            // templates strt with a \n normally
            val content = t.content.replaceFirst("^\\r?\\n", "")

            clog << s"Found templateResp... ${t.name}"

            val s = if(content.length > 0) {
              val allValues = new StaticECtx(msg.attrs, Some(engine.ctx))
              EESnakk.formatTemplate(content, allValues)
            }
            else {
              //              engine.ctx.get("payload").getOrElse("no msg recognized, no result, no response template")

              engine.ctx.getp("payload").map {p=>
                if(p.value.isDefined) {
                  ctype=p.value.get.contentType

                  p.value.get.value match {
                    case x : Array[Byte] =>
                      response = Some(Ok(x).as(ctype))
                    case _ =>
                      response = Some(Ok(p.value.get.value.toString).as(ctype))
                  }

                  ""
                } else
                  engine.ctx.get("payload").getOrElse("no msg recognized, no result, no response template")
              }.getOrElse("no msg recognized, no result, no response template")
            }

            clog << s"RUN_REST_REPLY $verb $mock $path\n$s as $ctype"

            response.map {res=>
              // add template parms as headers
              val headers = t.parms
                .filter(_._1.toLowerCase.trim != "content-type")
                .filter(_._1.startsWith("http.header"))
                .map(t=>(t._1.replaceFirst("http.header.", ""), t._2))
                .toSeq

              res.withHeaders(headers: _*)
            }.getOrElse(mkStatus(s, engine).as(ctype))

          }.getOrElse {
            val res = s"No response template for $e $a\n" + engine.root.toString
            clog << s"RUN_REST_REPLY $verb $mock $path\n" + res
            val response = engine.ctx.get("payload").getOrElse(res)

            mkStatus(response, engine).as(ctype)
              .withHeaders("diesel-reason" -> s"response template not found for $path in realm ${stok.realm}")
              .withHeaders("diesel-trace-id" -> s"engine id: ${engine.id}")
          }
        }

        // must allow for ctx.sleeps
        // todo why 50 sec
        Await.result(res, Duration("50seconds"))
      } getOrElse {
        //      Future.successful(
        NotFound(s"ERR Realm(${reactor}): Template or message not found for path: " + path)
          .withHeaders("diesel-reason" -> s"template or message not found for $path in realm ${reactor}")
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

  def rest(path: String) = runRestPath(path, "GET")
  def restPost(path: String) = runRestPath(path, "POST")

  private def runRestPath(path: String, verb:String) = {
    runRest(
      path,
      "GET",
      true,
      Some(EMsg("diesel", "rest", List(
        P("path", "/" + path),
        P("verb", verb)
      )))
    )
  }

  /** proxy real service GET */
  def proxy(path: String) = Filter(noRobots) { implicit stok =>
    val engine = prepEngine(new ObjectId().toString,
      DomEngineHelper.settingsFrom(stok),
      stok.realm,
      None,
      false,
      stok.au,
    "DomApi.proxy")

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
    DomWorker later AutosaveSet("DomFidPath", reactor, "", stok.au.get._id, Map(
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
    DomCollector.withAsts {asts=>
      asts.find(_.id == id).map { ast =>
        if (format == "html")
          Ok(ast.engine.root.toHtml)
        else
          engineView(id).apply(stok.req).value.get.get
      }.getOrElse {
        NotFound("ast not found")
      }
    }
  }

  // list the collected ASTS
  def listAst = FAUR { implicit stok =>
    val un = stok.userName + (if(stok.au.exists(_.isAdmin)) " admin - sees all" else " - regular user")

    DomCollector.withAsts {asts=>
      val x =
        asts
          .filter(a=> stok.au.exists(_.isAdmin) ||
            a.realm == stok.realm &&
              (
                a.userId.isEmpty ||
                a.userId.exists(_ == stok.au.map(_.id).mkString))
              )
          .map{a=>

          val uname = a.userId.map(u=> Users.nameOf(new ObjectId(u))).getOrElse("[auto]")

          s"""
             |<td><a href="/diesel/viewAst/${a.id}">${a.id}</a></td>
             |<td>${a.stream}</td>
             |<td>${a.realm}</td>
             |<td>${uname}</td>
             |<td>${a.engine.status}</td>
             |<td>${a.dtm}</td>
             |<td>${a.engine.description}</td>
             |""".stripMargin
          }.mkString(
          s"""
             |Traces captured for realm: ${stok.realm} and user $un<br><br>
             |<table>
             |<tr>
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
    if(stok.au.exists(_.isAdmin)) {
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

    val engine = prepEngine(xid, settings, reactor, Some(root), true, stok.au, "DomApi.postAst")
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


  /** did the flow have a preferrred http response status/headers? */
  def mkStatus(s:String, engine:DomEngine) = {
    // is there a desired status
    var ok = engine.ctx.get("diesel.response.http.status").filter(_.length > 0).map {st=>
      Status(st.toInt)
    } getOrElse
      Ok(s)

    // add headers
    engine.ctx.listAttrs.filter(_.name startsWith "diesel.http.response.header.").map {p=>
      ok = ok.withHeaders(p.name.replace("diesel.response.http.header.", "") -> p.calculatedValue(engine.ctx))
    }

    // or from json
    engine.ctx.getp("diesel.http.response").filter(_.ttype == "JSON").map {p=>
      ok = ok.withHeaders(p.name.replace("diesel.response.http.header.", "") -> p.calculatedValue(engine.ctx))
    }

    ok
  }

  /** find e/a in engine, either e.a or matching template URL
    * @param dieselUri "wreact" or else..
    */
  private def findEA (path:String, engine:DomEngine, useThisStory:Option[WID]=None) : (Option[DTemplate], String, String) = {
    var e = ""
    var a = ""

    val direction = "request"

    val trequest =
      engine
        .ctx
        // first try  with e.a
        .findTemplate(path, direction)
        .map {t=>
          // found template by path name, so parse as entity/action
          val PAT = """([\w.]+)[./](\w+)""".r
          val PAT(ee, aa) = path
          e = ee
          a = aa

          t
        }
        .orElse {
          // try to find http templates by URL
          engine
            .ctx
            .findTemplate {t=>
              val content = t.content.replaceFirst("^\n", "") // multiline template start with \n

              // is it http?
              (t.parmStr.startsWith(direction)) && {
                (content.startsWith ("GET") ||
                  content.startsWith ("POST") ||
                  content.startsWith ("PUT") ||
                  content.startsWith ("DELETE")) &&
                  // todo add and compare request header parms
                  EESnakk.templateMatchesUrl(t, "*", path, t.content) &&
                  (
                    !useThisStory.exists(w=> ! (t.specPath.wpath startsWith w.wpath)) // startsWith becase tspec includes #section
                  )
              }
            }
            .map {t=>
              // found template by path URL , so parse its name as entity/action
              val PAT = """([\w.]+)[./](\w+)""".r
              val PAT(ee, aa) = t.name//.split(":").head
              e = ee
              a = aa

              t
            }
        }

    // materialize the laziness, so e/a are calculated
    val PATS = """([\w./]+)[./](\w+)"""

    if(trequest.isEmpty && path.matches(PATS)) {
      val PAT = PATS.r
      val PAT(ee, aa) = path

      // replace a/b/c/d with a.b.c.d
      e = ee.replace('/', '.')
      a = aa.replace('/', '.')
    }

    (trequest, e, a)
  }

  /** create a message from the incoming request - find what that may be */
  private def findMessage (trequest:Option[DTemplate],
                           engine:DomEngine,
                           path:String,
                           inSpec:List[P],
                           incomingMetas:NVP,
                           verb:String,
                           stok:RazRequest,
                           e:String,
                           a:String,
                           body:String,
                           content:Option[EEContent],
                           requestContentType:Option[String]) : Option[EMsg] = {
    // incoming message
    val msg : Option[EMsg] = trequest.flatMap { template =>

      // with template
      try {
        val sc = EESnakk.parseTemplate(Some(template), template.content, Nil, Some(engine.ctx))

        // parse url incomgin parsm
        var u = sc.url
        if(u.startsWith("/diesel/"))
          u = u.replaceFirst("/diesel/(mock|rest|wreact/[^/]+/react)/", "")

        val urlParms = sc.parseUrl(u, path, inSpec, incomingMetas)

        // merge but not overwrite not empty values
        def mergeMaps (a:Map[_ <: String,String], b:Map[String,String]) = {
          (a.toSeq ++ b.toSeq).groupBy(_._1).map (t => t._1 -> t._2.find(_._2 != "").map(_._2).getOrElse(""))
        }

        // parse incoming template and parms in querystring
        val incParms = if(body.trim.length > 0) sc.parseIncoming(body, inSpec, incomingMetas) else Map.empty
        val hparms = DomEngineHelper.parmsFromRequestHeader(stok.req, content)

        val parms = mergeMaps (incParms, mergeMaps( hparms, urlParms))

        clog << "PARSED incoming PARMS: " + parms.mkString

        // add parms to context, so they're available to all inside
        parms.map(p => engine.ctx.put(P(p._1, p._2)))
        Some(new EMsg(e, a, parms.map(p => P(p._1, p._2)).toList))
      } catch {
        case t: Throwable => {
          razie.Log.log("error parsing", t)
          engine.root.children.appendAll({
            EError("Error parsing: " + template.specPath, t.toString) ::
              new EError("Exception : ", t) :: Nil
          }.map(DomAst(_, AstKinds.ERROR))
          )
          None
        }
      }
    } orElse {

      // no template
      val headers = DomEngineHelper.parmsFromRequestHeader(stok.req, content)

      // extract parms from request
      if(verb == "GET" || verb == "POST") {
        // query parms for GET
        val pQuery = stok.query.filter(x => !DomEngineSettings.FILTER.contains(x._1))
        var pPost = if (verb == "POST") {
          if (requestContentType.exists(_ == "text/plain") && !body.trim.startsWith("{")) {
            // plain text - see who can parse this later, snakkers etc
            Map("request" -> body)
          } else if (requestContentType.exists(_ == "application/x-www-form-urlencoded")) {
            // normal form - each parm
            stok.formParms.filter(x => !DomEngineSettings.FILTER.contains(x._1))
          } else {
            // assume it's json with a bunch of input parms
            try {
              val js = new JSONObject(body)
              import scala.collection.JavaConverters._
              js.keys.asScala.toList.map(k => (k, js.get(k).toString)).toMap
            } catch {
              case t: Throwable => {
                razie.Log.log("NO TEMPLATE found - error trying to pars body as json", t)
                engine.root.children.appendAll({
                  EError("No template found: " ) ::
                  EError("Error parsing: " + body, t.toString) ::
                    new EError("Exception : ", t) :: Nil
                }.map(DomAst(_, AstKinds.ERROR))
                )
                Map.empty
              }
            }
          }

        } else Map.empty

        // if it's a fiddle, filter more stuff
        if(stok.query.contains("dfiddle")) {
          val f = Array("storyWpath", "reactor", "story", "spec", "specWpath")
          pPost = pPost.filter(x => !f.contains(x._1))
        }

        val parms = pQuery ++ pPost ++ DomEngineHelper.parmsFromRequestHeader(stok.req, content)
        parms.map(p => engine.ctx.put(P(p._1, p._2)))
        Some(new EMsg(e, a, parms.map(p => P(p._1, p._2)).toList))
      } else None
    }

    msg.map {m=>
      // find spec - so we cann check archetypes, permissions etc
      val spec = RDExt.spec(m)(engine.ctx)

      m.typecastParms(spec).withSpec(spec)
    }
  }

 /** member of diesel realm or member of trusted realm
    *
    * @param m message to check
    * @param reactor message belongs to this, possibly different from the users's
    * @param website website - website the message belongs to, possibly not this website
    * @param stok
    * @return
    */
  def isMemberOrTrusted (m:Option[EMsg], reactor:String, website:Website)(implicit stok:RazRequest) = {
      stok.au.exists(u=>
        // is member of diesel realm
        u.isAdmin || u.hasRealm(reactor) ||
        // is member from trusted realm, for public messages
        m.exists(_.isPublic) &&
          u.hasRealm(stok.realm) && website.dieselTrust.contains(stok.realm)
      )
  }

  /** can user execute message */
  def isMsgPublic (m:EMsg, reactor:String, website:Website)(implicit stok:RazRequest) = {
    m.isPublic ||
    website.dieselVisiblity == "public"
  }

  /** can user execute message */
  def isMsgVisible (m:EMsg, reactor:String, website:Website)(implicit stok:RazRequest) = {
    m.isPublic ||
    website.dieselVisiblity == "public" ||
    isMemberOrTrusted(Some(m), reactor, website)
  }

  /** roll up and navigate the definitions */
  def navigate() = FAUR { implicit stok =>

    val engine = prepEngine(new ObjectId().toString,
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
    val config = Autosave.OR("DomEngineConfig", WID("", "").r(stok.realm), stok.au.get._id,
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
    if(cfg.realm.isEmpty) cfg.realm = Some(stok.realm)

    DomWorker later AutosaveSet("DomEngineConfig", stok.realm, "", stok.au.get._id,
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
      }) getOrElse {
        ROK.k reactorLayout12 {
          views.html.modules.diesel.engineView(None)
        }
      }
    }
  }

  /** replace in oldContent the given section with iContent (escaped)
    *
    * @param sName can be name or name:spec or anything like that
    */
  def mkC(oldContent: String, icontent: String, sType: String, sName: String) = {
    // todo this is too greedy - fix it !
    val re = s"(?s)\\{\\{(\\.?)$sType $sName([^}]*)\\}\\}(.*)\\{\\{/$sType\\}\\}".r
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
  def anonRunWiki(cwid: CMDWID) = Filter(noRobots).async { implicit stok =>
    val sid = stok.fqhParm("dieselSessionId")

    (for (
    // we expect the page to have done a getSession first
      existingSession <- sid.flatMap(s => DomSessions.sessions.get(s)) orErr ("no session found: " + sid);
      anyOverwrites <- cwid.wid.flatMap(wid => existingSession.overrides.find(_.wid == wid)) orErr "no session override found"
    ) yield {
      //      val we = cwid.wid.map(stok.prepWid).flatMap(wid => session.overrides.get(wid)).get.page
      val we = cwid.wid.flatMap(wid => existingSession.overrides.find(_.wid == wid)).get.page

      val nw =
        if (stok.query.contains("dfiddle")) we.wid.copy(section = stok.query.get("dfiddle"))
        else we.wid

      Audit.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("Anon"))
      irunDom(cwid.rest, Some(nw), Some(we.copy(content = anyOverwrites.newContent)))
    }).getOrElse {
      Future {
        Unauthorized("Session not found... it expired or there's too much load...")
      }
    }
  }

  /** anon fiddle - with tests etc */
  def anonRunFiddle(cwid: CMDWID) = RAction.async { implicit stok =>
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
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec, "anonRunFiddle")
    setHostname(engine.ctx.root)

    // decompose all tree or just testing? - if there is a capture, I will only test it
    val fut =
      engine.process

    fut.map {engine =>
      res += engine.root.toHtml

      val m = Map(
        "res" -> res,
        "capture" -> captureTree,
        "ca" -> RDExt.toCAjmap(dom plus idom), // in blenderMode dom is full
        "totalCount" -> (engine.totalTestCount),
        "failureCount" -> engine.failedTestCount,
        "ast" -> DomFiddles.getAstInfo(ipage)
      )

      retj << m
    }

  }

  /** status badge for current realm */
  def status= RAction.async { implicit stok =>
    stok.au.map {au=>
      DomGuardian.findLastRun(stok.realm, au.userName).map {r=>
        Future.successful {
          Ok(quickBadge(r.failed, r.total, r.duration))
        }
      }.getOrElse {
        // start a check in the background
        if(DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm)) startCheck (stok.realm, stok.au)

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
      }.foldLeft(t)((a,b) => (a._1+b._1, a._2+b._2, a._3+b._3))

      Future.successful {
        Ok(quickBadge(x._1, x._2, x._3, "All"))
      }
  }

  def report = Filter(activeUser).async { implicit stok =>
    // todo this should run all the time when stories change
      DomGuardian.findLastRun(stok.realm, stok.au.get.userName).map {r=>
        Future.successful {
          ROK.k reactorLayout12 {
            new Html(
              s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${quickBadge(r.failed, r.total, r.duration)}<br>
<small>${DomGuardian.stats} (<a href="/diesel/listAst">list all</a>)(<a href="/diesel/cleanAst">clean all</a>) </small><br><br>""".stripMargin +
                views.html.modules.diesel.engineView(Some(r.engine))(stok).toString
            )
          }
          //          Redirect(s"""/diesel/engine/view/${r.engine.id}""")
        }
      }.getOrElse {
        if(DomGuardian.enabled(stok.realm) && DomGuardian.onAuto(stok.realm)) startCheck (stok.realm, stok.au)

        // new
        Future.successful{
          Ok(s"""no run available - check this later <a href="/diesel/runCheck">Re-run check</a> """).as("text/html")
        }

        //old used to wait...
//        eid.map { r =>
//          Ok(s"""no run available - check this later - <a href="/diesel/engine/view/${r.engine.id}">report</a>""").as("text/html")
//        }
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
                DomGuardian.lastRuns.map {t=>
                  val r = t._2
                  s"""Realm: ${r.realm}""" +
                    s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${quickBadge(r.failed, r.total, r.duration)}<br>
""".stripMargin
                }.toList.mkString +

"""<hr><h2>Details</h2>""" +

/* details */

  DomGuardian.lastRuns.map {t=>
    val r = t._2
    s"""<p>Realm: ${r.realm}</p>""" +
    s"""
Guardian report<a href="/wiki/Guardian_Guide" ><sup><span class="glyphicon glyphicon-question-sign"></span></a></sup>: <a href="/diesel/runCheck">Re-run check</a>  (${r.duration} msec) ${quickBadge(r.failed, r.total, r.duration)}<br>
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
    if(DomGuardian.enabled(stok.realm)) startCheck (stok.realm, stok.au).map { engine =>
      Redirect(s"""/diesel/report""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  /** run another check all reactors */
  def runCheckAll = Filter(adminUser).async { implicit stok =>
    if(DomGuardian.ISENABLED) Future.sequence(
      WikiReactors.allReactors.keys.map {k=>
        if(DomGuardian.enabled(k)) startCheck (k, stok.au)
        else Future.successful(DomGuardian.EMPTY_REPORT)
      }
    ).map {x=>
      Redirect(s"""/diesel/reportAll""")
    }
    else Future.successful(Ok("GUARDIAN DISABLED"))
  }

  def pluginAction (plugin:String, conn:String, action:String, epath:String) = Filter(activeUser).async { implicit stok =>
    Future.successful {
      val url = "http" + (if(stok.secure) "s" else "") + "://" + stok.hostPort
      val c = WikiDomain(stok.realm).plugins.find(_.name == plugin).map(_.doAction(WikiDomain(stok.realm).rdom, conn, action, url, epath)).mkString

      if(c.startsWith("<"))
        Ok(c).as("text/html")
      else
        Ok(c)
    }
  }

}

