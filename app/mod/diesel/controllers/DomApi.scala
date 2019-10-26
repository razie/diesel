package mod.diesel.controllers

import controllers.{DieselSettings, Profile, RazRequest}
import difflib.{DiffUtils, Patch}
import mod.diesel.guard.DomGuardian.startCheck
import razie.diesel.utils.DomHtml.quickBadge
import mod.diesel.controllers.DomSessions.Over
import mod.diesel.guard.DomGuardian
import mod.diesel.model._
import mod.diesel.model.exec.{EECtx, EESnakk}
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
import razie.diesel.engine.DomEngineSettings.DIESEL_USER_ID
import razie.diesel.engine.RDExt._
import razie.diesel.engine._
import razie.diesel.exec.SnakkCall
import razie.diesel.ext.{EnginePrep, _}
import razie.diesel.model.DieselMsg
import razie.diesel.samples.DomEngineUtils
import razie.diesel.utils.{AutosaveSet, DomCollector, DomWorker, SpecCache}
import razie.hosting.Website
import razie.tconf.DTemplate
import razie.wiki.Enc
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.{Logging, Snakk, ctrace, js}
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

  /** /diesel/wreact/wpath
    *
    * API msg sent to wiki#section
    *
    * find the named fiddles, make an engine and run the requested message on that engine
    */
  def wreact(cwid: CMDWID) = Filter(noRobots).async { implicit stok =>
    val errors = new ListBuffer[String]()

    val resultMode = stok.query.getOrElse("resultMode", "value")
    val sketchMode = stok.query.getOrElse("sketchMode", "false").toBoolean

    // in sketch mode we include the stories as well
    def applies(s:WikiSection) = {
      (Array("spec") contains s.signature) ||
      (Array("story") contains s.signature) && sketchMode
    }

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

        // add all entangled fiddles too
        val pspec = EnginePrep.sectionsToPages(
          we,
          we.sections.filter(s=>s.stype == "dfiddle" && applies(s) && (
            s.name == fidName ||  // entangle same name
            s.args.get("includeFor").exists(pat=> fidName.matches(pat)) // or if it's meant to be included
            ))
        )

        irunDom(path, Some(newWid), None, pspec)
      } else {
        // normal full page / section - include all sections
        val pspec = EnginePrep.sectionsToPages(
          we,
          we.sections.filter(s=>s.stype == "dfiddle" && applies(s))
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

  /** /diesel/start/ea
    * API msg sent to reactor
    */
  def start(e: String, a: String) = Filter(noRobots).async { implicit stok =>
      if(stok.au.exists(_.isActive)) {
        // insert a dot only if needed
        val ea = (
            if (e.length > 0 && a.length > 0) e + "." + a else e + a
            ).replaceAllLiterally("/", ".")
        irunDom(ea, None)
      } else {
        Future.successful(
          unauthorized ("Can't start a message without an active account...")
        )
      }
  }

  /** /diesel/fiddle/react/ea
    * API msg sent to reactor
    */
  def react(e: String, a: String) = Filter(noRobots).async { implicit stok =>
    // insert a dot only if needed
    val ea = (
      if(e.length > 0 && a.length > 0) e + "." + a else e+a
      ).replaceAllLiterally("/", ".")
    irunDom(ea, None)
  }

  private def irunDom(path: String, useThisStory: Option[WID], useThisStoryPage: Option[WikiEntry] = None, useThisSpecPage: List[WikiEntry] = Nil) (implicit stok:RazRequest) : Future[Result] = {
    val x = Try {
      irunDomInt(path, useThisStory, useThisStoryPage, useThisSpecPage)
    } recover {
      case e : Throwable => Future.successful(BadRequest("ERROR: " + e.getMessage))
    }
    x.get
  }

  /** execute message to given reactor
    *
    * @param is the useful path (without prefix). Either an e.a or e/a or template match
    * @param useThisStory  if nonEmpty then will use this (find it first) plus blender
    * @param useThisStoryPage if nonEmpty then will use this plus blender
    */
  private def irunDomInt(path: String, useThisStory: Option[WID], useThisStoryPage: Option[WikiEntry] = None, useThisSpecPage: List[WikiEntry] = Nil) (implicit stok:RazRequest) : Future[Result] = {

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
        val stories = if (settings.sketchMode) EnginePrep.catPages("Story", reactor). /*filter(_.name != stw.get.name).*/ toList else Nil
        val specs = EnginePrep.catPages("Spec", reactor).toList
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

      cdebug << "irunDom.specs: \n  " + pages.map(_.wid.wpathFull).mkString("\n  ")

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
        DieselMsg.irunDom+path
      )

      setHostname(engine.ctx.root)

      // find template matching the input message, to parse attrs
      val t@(trequest, e, a, matchedParms) = findEA(path, engine, useThisStory)

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
       .map (msg=>
         // add matched parms
           // todo why map matched parms and not keep type/value what abt numbers, escaped json etc?
         if(matchedParms.isDefined) msg.copy(attrs = msg.attrs ::: matchedParms.get.toList.map(t=>P(t._1, t._2)))
         else msg
       )

      val xx = stok.qhParm("X-Api-Key").mkString
      def needsApiKey = xapikey.isDefined
      def isApiKeyGood = xapikey.isDefined && xapikey.exists { x =>
        x.length > 0 && x == xx
      }

      val isPublic = msg.exists(isMsgPublic(_, reactor, website))
      val isTrusted = isMemberOrTrusted(msg, reactor, website)

      clog << s"irunDom: Message: $msg isPublic=$isPublic isTrusted=$isTrusted needsApiKey=$needsApiKey isApiKeyGood=$isApiKeyGood"

      if (
        isPublic ||
        isTrusted ||
        needsApiKey && isApiKeyGood
      ) {

        setApiKeyUser(needsApiKey, isApiKeyGood, website, engine)

        msg.map { msg =>
          EnginePrep.addMsgToAst(engine.root, msg)
        }

        engine.process.map { engine =>
          val errors = new ListBuffer[String]()

          if (
            "value" == settings.resultMode || "" == settings.resultMode
          ) {
            val resp = engine.extractFinalValue(e,a)
            val resValue = resp.map(_.currentStringValue).getOrElse("")

            val ctype = resp.map(p=>WTypes.getContentType(p.ttype)).getOrElse(WTypes.Mime.textPlain)
            Ok(stripQuotes(resValue)).as(ctype)
          } else {
            // multiple values as json
            val valuesp = engine.extractValues(e,a)
            val values = valuesp.map(p => (p.name, p.currentStringValue))

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
              Ok(root.toString).as(WTypes.Mime.appText)
            } else if ("dieselTree" == settings.resultMode) {
              val m = root.toj
              val y = DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
              val x = js.tojsons(y.toj).toString
              Ok(x).as(WTypes.Mime.appJson)
            } else
              Ok(js.tojsons(m).toString).as(WTypes.Mime.appJson)
          }
        }

        // is message visible?
      } else if (msg.isDefined && !isMsgVisible(msg.get, reactor, website)) {
        val infomsg = s"Unauthorized msg access [irundom] (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})"
        info(infomsg)

          Future.successful(
            Unauthorized(infomsg)
          )
      } else {
        // good security keys for non-members (devs if logged in don't need security)
        val infomsg = s"Unauthorized msg access (key) [irundom] for ${msg.map(m=>m.entity+m.met)}"
        info(infomsg)

        Future.successful(
          Unauthorized(infomsg)
        )
      }
    }
  }

  /**
    * deal with a REST request. use the in/out for message
    *
    * force the raw parser, to deal with all content types
    *
    * mock is important - no template matching for mock
    */
  def runRest(path: String, verb:String, mock:Boolean, imsg:Option[EMsg] = None, custom:Option[DomEngine => DomEngine] = None) : Action[RawBuffer] = Action(parse.raw) { implicit request =>
    implicit val stok = razRequest

    // not always the same as the request...
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
        ctrace << s"MOCK DIESEL SLEEPING... $code ms"
        Thread.sleep(code.toInt)
      }

      val requestContentType = stok.req.contentType

      val uid = stok.au.map(_._id).getOrElse(NOUSER)

      val raw = request.body.asBytes()
      val body = raw.map(a => new String(a)).getOrElse("")
      val content = Some(new EContent(body, stok.req.contentType.mkString, 200, Map.empty, None, raw))

      // todo sort out this mess
      val settings = DomEngineHelper.settingsFromRequestHeader(stok.req, content).copy(realm=Some(reactor))
      settings.mockMode = mock
      val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))

      ctrace << s"RUN_REST_REQUEST verb:$verb mock:$mock path:$path realm:${reactor}\nheaders: ${stok.req.headers}" + body

      var engine = EnginePrep.prepEngine(new ObjectId().toString,
        settings,
        reactor,
        None,
        false,
        stok.au,
        "DomApi.runRest:"+path,
        None,
        // empty story so nothing is added to root
        List(new WikiEntry("Story", "temp", "temp", "md", "", uid, Seq("dslObject"), reactor))
      )

      new DomReq(stok.req).addTo(engine.ctx)

      if(custom.isDefined)
        engine = custom.get.apply(engine)

      // find template matching the input message, to parse attrs
      val t@(trequest, e, a, matchedParms) = {
        val fea = findEA(path, engine)
        fea._1
            .map { x => fea }
            .orElse {
              imsg.map(x => (None, x.entity, x.met, None))
            }.getOrElse {
          fea
        }
      }

      // message specified return mappings, if any
      val inSpec = spec(e, a)(engine.ctx).toList.flatMap(_.attrs)
      val outSpec = spec(e, a)(engine.ctx).toList.flatMap(_.ret)

      // collect any parm specs
      val outSpecs = outSpec.map(p => (p.name, p.currentStringValue, p.expr.mkString))

      // stuff like incoming content-type
      val incomingMetas = stok.headers.toSimpleMap

      // try to match incoming
      def findIt = findMessage (
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

      // incoming message
      // first templates already matched, then imsg (diesel.rest) else e.a
      val msg : Option[EMsg] = (trequest.flatMap(_ => findIt) orElse imsg orElse findIt)
          .map (msg=>
            // add matched parms
            // todo why map matched parms and not keep type/value what abt numbers, escaped json etc?
            if(matchedParms.isDefined) msg.copy(attrs = msg.attrs ::: matchedParms.get.toList.map(t=>P(t._1, t._2)))
            else msg
          )

      if(msg.isDefined && !msg.exists(_.spec.isDefined)) {
        // try to find a spec
        msg.get.spec = spec(msg.get.entity, msg.get.met)(engine.ctx)
      }

      cdebug << s"Message found: $msg xapikey=$xapikey"

      val xx = stok.qhParm("X-Api-Key").mkString
      def needsApiKey = xapikey.isDefined
      def isApiKeyGood = xapikey.isDefined && xapikey.exists { x =>
        x.length > 0 && x == xx
      }

      // is message visible?
      if (msg.isDefined && !isMsgVisible(msg.get, reactor, website)) {
        info(s"Unauthorized msg access [runrest] (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})")
        info(s"msg: $msg - ${msg.get.isPublic} - ${msg.get.spec.toString} - reactor: $reactor")
        Unauthorized(s"Unauthorized msg access [runrest] (diesel.visibility:${stok.website.dieselVisiblity}, ${stok.au.map(_.ename).mkString})")
      } else if (
        !isMemberOrTrusted(msg, reactor, website) &&
        needsApiKey &&
        isApiKeyGood
        ) {
        // good security keys for non-members (devs if logged in don't need security)
          info(s"Unauthorized msg access [runrest] (key, dieselTrust)")
          info(s"msg: $msg - reactor: $reactor")
          Unauthorized(s"Unauthorized msg access [runrest] (key, dieselTrust)").withHeaders(
          )
      } else msg.map {msg=>

        setApiKeyUser(needsApiKey, isApiKeyGood, website, engine)

        EnginePrep.addMsgToAst(engine.root, msg)
        DomCollector.collectAst("runRest", stok.realm, engine.id, stok.au.map(_.id), engine, stok.uri)

        // process message
        val res = engine.process.map { engine =>

          cdebug << s"Engine done ... ${engine.id}"

          //        val res = engine.extractValues(e, a)

          // find output template and format output
          // todo this is weird - need something more regular...
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
                .filter(x=>
                    // skip debug infos and other stuff = we need the first message
                    x.value.isInstanceOf[EMsg])
                .head
                .children
                .find(_.children.headOption.exists(_.value.isInstanceOf[EMsg]) )

              m.flatMap { m =>
                val msg = m.children.head.value.asInstanceOf[EMsg]
                engine.ctx.findTemplate(msg.entity + "." + msg.met, "response")
              }
            }

          // todo optimize
          ctrace << engine.root.toString

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

            ctrace << s"Found templateResp... ${t.name}"

            val s = if(content.length > 0) {
              val allValues = new StaticECtx(msg.attrs, Some(engine.ctx))
              EESnakk.formatTemplate(content, allValues)
            }
            else {
              //              engine.ctx.get("payload").getOrElse("no msg recognized, no result, no response template")

              engine.ctx.getp("payload").map {p=>
                if(p.value.isDefined) {
                  ctype = WTypes.getContentType(p.value.get.cType)

                  p.value.get.value match {
                    case x : Array[Byte] =>
                      response = Some(Ok(x).as(ctype))
                    case _ =>
                      response = Some(Ok(p.value.get.asString).as(ctype))
                  }

                  ""
                } else
                  engine.ctx.get("payload").getOrElse("no msg recognized, no result, no response template")
              }.getOrElse("no msg recognized, no result, no response template")
            }

            ctrace << s"RUN_REST_REPLY $verb $mock $path\n$s as $ctype"

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
            val res = s"No response template for ${e}.${a}\n" + engine.root.toString
            ctrace << s"RUN_REST_REPLY $verb $mock $path\n" + res
            val response = engine.ctx.get("payload").getOrElse(res)
            // todo set ctype based on payload if found

            mkStatus(response, engine).as(ctype)
              .withHeaders("diesel-reason" -> s"response template not found for $path in realm ${stok.realm}")
              .withHeaders("diesel-trace-id" -> s"engine id: ${engine.id}")
          }
        }

        // must allow for ctx.sleeps
        // todo why 50 sec
        import DieselAppContext.executionContext
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

  /** /diesel/mock/ path  */
  def mock(path: String) = runRest("/" + path, "GET", true)
  def mockPost(path: String) = runRest("/" + path, "POST", true)

  /** /diesel/rest/ path  */
  def rest(path: String) = runRestPath("/" + path, "GET")
  def restPost(path: String) = runRestPath("/" + path, "POST")

  private def runRestPath(path: String, verb:String) = {
    runRest(
      path,
      verb,
      true,
      Some(EMsg("diesel", "rest", List(
        P.fromTypedValue("path", path),
        P.fromTypedValue("verb", verb)
      )))
    )
  }

  /** /diesel/proxy/path   proxy real service GET */
  def proxy(ipath: String) = Filter(noRobots) { implicit stok =>
    val  path = Enc.fromUrl(ipath + "?") + stok.ireq.rawQueryString
    clog << "diesel/proxy GET " + path
    val sc = SnakkCall("http", "GET", path, Map.empty, "").setUrl(Snakk.url(path))
    val ec = sc.eContent
    Ok(ec.body)
        .as(ec.contentType)
        .withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  /** proxy real service GET */
  def proxyPost(ipath: String) = RAction { implicit stok =>
    val  path = Enc.fromUrl(ipath + "?" + stok.ireq.rawQueryString)
    clog << "diesel/proxy POST " + path
    val sc = SnakkCall("http", "POST", path, Map.empty, "")
    val ec = sc.eContent
    Ok(ec.body)
        .as(ec.contentType)
        .withHeaders("Access-Control-Allow-Origin" -> "*")
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
  private def findEA (path:String, engine:DomEngine, useThisStory:Option[WID]=None) : (Option[DTemplate], String, String, Option[Map[String,String]]) = {
    var e = ""
    var a = ""
    var m : Option[Map[String,String]] = None

    val direction = "request"
    val eapath  = if (path.startsWith("/")) path.substring(1) else path

    val trequest =
      engine
        .ctx
        // first try  with e.a
        .findTemplate(eapath, direction)
        .map {t=>
          // found template by path name, so parse as entity/action
          val EMsg.REGEX(ee, aa) = eapath
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
              (t.tags.contains(direction)) &&
                  // if tagged with out, don't match for in
              !t.tags.contains("out") && {
                (content.startsWith ("GET") ||
                  content.startsWith ("POST") ||
                  content.startsWith ("PUT") ||
                  content.startsWith ("DELETE")) &&
                  // todo add and compare request header parms
                  EESnakk.templateMatchesUrl(t, "*", path, t.content).isDefined &&
                  (
                    !useThisStory.exists(w=> ! (t.specPath.wpath startsWith w.wpath)) // startsWith becase tspec includes #section
                  )
              }
            }
            .map {t=>
              // found template by path URL , so parse its name as entity/action
              val EMsg.REGEX(ee, aa) = t.name//.split(":").head
              e = ee
              a = aa

              // todo perf I'm doing this twice - expensive
              m = EESnakk.templateMatchesUrl(t, "*", path, t.content)
              t
            }
        }

    // materialize the laziness, so e/a are calculated
    val PATS = """([\w./]+)[./](\w+)""" // THIS IS different from Emsg.REGEX - accepts path style

    if(trequest.isEmpty && path.matches(PATS)) {
      val PAT = PATS.r
      var PAT(ee, aa) = path

      // root starts with /, so remove it...
      if(ee.startsWith("/"))
        ee = ee.replaceFirst("/", "")

      // replace a/b/c/d with a.b.c.d
      e = ee.replace('/', '.')
      a = aa.replace('/', '.')

    }

    (trequest, e, a, m)
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
                           content:Option[EContent],
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

        /** merge but not overwrite not empty values */
        def mergeMaps (a:Map[_ <: String,String], b:Map[String,String]) = {
          (a.toSeq ++ b.toSeq).groupBy(_._1).map (t => t._1 -> t._2.find(_._2 != "").map(_._2).getOrElse(""))
        }

        // parse incoming template and parms in querystring
        val incParms = if(body.trim.length > 0) sc.parseIncoming(body, inSpec, incomingMetas) else Map.empty
        val hparms = DomEngineHelper.parmsFromRequestHeader(stok.req, content)

        val parms = mergeMaps (incParms, mergeMaps( hparms, urlParms))

        ctrace << "PARSED incoming PARMS: " + parms.mkString

        // add parms to context, so they're available to all inside
        // todo how to handle typed numbers and json parms?
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
        // if POST, see if there's something coming in
        var pPost = if (verb == "POST") {
          if (requestContentType.exists(_ == "text/plain") && !body.trim.startsWith("{")) {
            // plain text - see who can parse this later, snakkers etc
            Map("request" -> body)
          } else if (requestContentType.exists(_ == "application/x-www-form-urlencoded")) {
            // normal form - each parm
            stok.formParms.filter(x => !DomEngineSettings.FILTER.contains(x._1))
          } else if(body.trim.length > 0) {
            // if any body sent in, assume it's json with a bunch of input parms
            try {
              val js = new JSONObject(body)
              import scala.collection.JavaConverters._
              js.keys.asScala.toList.map(k => (k, js.get(k).toString)).toMap
            } catch {
              case t: Throwable => {
                razie.Log.log("NO TEMPLATE found - error trying to parse body as json", t)
                engine.root.children.appendAll({
                  EWarning("No template found for path: " +path ) ::
                  EWarning("Error parsing: " + body, t.toString) ::
                  new EWarning("Exception : ", t) :: Nil
                }.map(DomAst(_, AstKinds.ERROR))
                )
                Map.empty
              }
            }
          } else Map.empty

        } else Map.empty

        // if it's a fiddle, filter more stuff
        if(stok.query.contains("dfiddle")) {
          val f = Array("storyWpath", "reactor", "story", "spec", "specWpath")
          pPost = pPost.filter(x => !f.contains(x._1))
        }

        val parms = pQuery ++ pPost ++ DomEngineHelper.parmsFromRequestHeader(stok.req, content)
        // todo how to handle typed numbers and jsong and other parms?
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
        // is member from trusted realm, for protected messages
//        m.exists(_.isProtected) &&
          u.hasRealm(stok.realm) && website.dieselTrust.contains(stok.realm)
      )
  }

  def setApiKeyUser (needsApiKey:Boolean, isApiKeyGood:Boolean, website:Website, engine:DomEngine)(implicit stok:RazRequest) = {
    if(stok.au.isEmpty && engine.settings.userId.isEmpty && needsApiKey && isApiKeyGood) {
      // no user in request, but xapikey passed - set the default test user
      (new EECtx).setAuthUser(engine.ctx)
      engine.settings.userId = engine.ctx.get(DIESEL_USER_ID)
    }
  }

  /** can user execute message */
  def isMsgPublic (m:EMsg, reactor:String, website:Website)(implicit stok:RazRequest) = {
    m.isPublic ||
    website.dieselVisiblity == "public"
  }

  /** can user execute message */
  def isMsgVisible (m:EMsg, reactor:String, website:Website)(implicit stok:RazRequest) = {
    isMsgPublic(m, reactor, website) ||
    isMemberOrTrusted(Some(m), reactor, website)
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
    val dfiddle = stok.query.get("dfiddle").mkString

    val anySpecOverwrites = session.flatMap { existingSession=>
      cwid.wid.flatMap(wid => existingSession.overrides.find(o=>
        o.wid == wid && o.sName == (dfiddle + ":spec"))
      )
    }
    val anyStoryOverwrites = session.flatMap { existingSession=>
      cwid.wid.flatMap(wid => existingSession.overrides.find(o=>
        o.wid == wid && o.sName == (dfiddle + ":spec"))
      )
    }

    val origPage = cwid.wid.flatMap(_.page)
    val origFiddle = origPage.flatMap(_.section("dfiddle", dfiddle))

    Audit.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("anon"))

    val settings = DomEngineHelper.settingsFrom(stok)

    val reactor = stok.formParm("reactor")
    val specWpath = stok.formParm("specWpath")
    val storyWpath = stok.formParm("storyWpath")

    // page sends the spec text and story text here
    val spec = anySpecOverwrites.map(_.newContent) getOrElse stok.formParm("spec")
    val story = anyStoryOverwrites.map(_.newContent) getOrElse stok.formParm("story")

    val uid = stok.au.map(_._id).getOrElse(NOUSER)

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
    val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")

    val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), stok.realm)
    // add other fiddles
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
      EnginePrep.addStoriesToAst(d, List(ipage))
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

  def setEnv (env:String) = Filter(isMod).async { implicit stok =>
    val au = stok.au.get
    val u = Profile.updateUser(au, au.setPrefs(stok.realm, Map("dieselEnv" -> env)))
    cleanAuth()
    // it's not actually redirecting, see client
    Future.successful(Redirect("/", SEE_OTHER))
  }

}

