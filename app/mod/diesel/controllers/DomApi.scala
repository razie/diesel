package mod.diesel.controllers

import com.google.inject.Singleton
import controllers.{Profile, RazRequest}
import difflib.{DiffUtils, Patch}
import java.net.URLDecoder
import mod.diesel.model._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.mvc._
import razie.audit.Audit
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.{NVP, P}
import razie.diesel.dom._
import razie.diesel.engine.DomEngineSettings.DIESEL_USER_ID
import razie.diesel.engine.RDExt._
import razie.diesel.engine._
import razie.diesel.engine.exec.{EECtx, EESnakk, SnakkCall}
import razie.diesel.engine.nodes.EnginePrep.{catPages, listStoriesWithFiddles}
import razie.diesel.engine.nodes._
import razie.diesel.expr.{ECtx, SimpleECtx, StaticECtx}
import razie.diesel.model.DieselMsg.ENGINE.DIESEL_REST
import razie.diesel.model.{DieselMsg, DieselMsgString, DieselTarget}
import razie.diesel.model.DieselMsg.HTTP
import razie.diesel.samples.DomEngineUtils
import razie.diesel.utils.{AutosaveSet, DomCollector, DomWorker, SpecCache}
import razie.hosting.Website
import razie.tconf.{DTemplate, EPos}
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.wiki.{Enc, WikiConfig}
import razie.{Logging, Snakk, ctrace, js}
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try
import scalaz.\&/.That
import RazRequestUtils.printRequest
/**
  * the incoming message / request
  */
case class DomReq (
                    uri:String,
                    protocol:String,
                    method:String,
                    contentType:String,
                    body:String,
                    rawQueryString:String
                  ) {
  def this (req : Request[AnyContent]) =
    this (
      req.uri,
      "http",
      req.method,
      req.contentType.mkString,
      req.body.toString,
      req.rawQueryString)

  def toj = Map (
    "uri" -> uri,
    "protocol" -> protocol,
    "method" -> method,
    "contentType" -> contentType,
    "body" -> body,
    "rawQueryString" -> rawQueryString
  )

  override def toString = razie.js.tojsons(this.toj)

  /** validate that incoming chars are parseable later */
  def validate = {
    Try {
      val s = razie.js.tojsons(this.toj)
      razie.js.parse(s)
    }
  }

  def addTo (e:ECtx) {
    e.put(P("request", this.toString).withValue(this))
  }
}

/** controller for server side fiddles / services */
@Singleton
class DomApi extends DomApiBase with Logging {

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
        val m = Map(
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
    * @param path is the useful path (without prefix). Either an e.a or e/a or template match
    * @param useThisStory  if nonEmpty then will use this (find it first) plus blender
    * @param useThisStoryPage if nonEmpty then will use this plus blender
    */
  private def irunDomStr(strMsg: DieselMsgString) (implicit stok:RazRequest) : Future[Result] = {

    var path = strMsg.msg
    if(path.length > 1000) path = path.take(1000) + "..."

    // not always the same as the request...
    val reactor = stok.website.dieselReactor
    val website = Website.forRealm(reactor).getOrElse(stok.website)

      DieselMsg.logdb("DIESEL_FIDDLE_iRUNSTR", stok.au.map(_.userName).getOrElse("Anon"), s"EA : ${path.take(500)}")

      var settings = DomEngineHelper.settingsFrom(stok)
      settings = settings.copy(realm = Some(reactor))

      // todo review security. Should use current user but if this is a spawn, the original user is passed in.
      // how to sign it? who can impersonate other users?
      val userId = settings.userId.map(new ObjectId(_)) orElse stok.au.map(_._id)

      val engine = strMsg.mkEngine

      engine.root.prependAllNoEvents(List(
        DomAst(
          EInfo("STRMSG Request details", path),
          AstKinds.DEBUG)
            .withStatus(DomState.SKIPPED)
      ))

      setHostname(engine.ctx.root)

      // incoming message
      val msg: Option[EMsg] = strMsg.getEMsg

      val ea = msg.map(_.ea).mkString
      val e = msg.map(_.entity).mkString
      val a = msg.map(_.met).mkString

      def isApiKeyGood = stok.validateXApiKey

      val isPublic = msg.exists(isMsgPublic(_, reactor, website))
      val isTrusted = isMemberOrTrusted(msg, reactor, website)

      engine.withInitialMsg(msg)

      clog << s"irunDomSTRMSG: Message: $msg isPublic=$isPublic isTrusted=$isTrusted " +
          s"isApiKeyGood=$isApiKeyGood"

      if (isPublic || isTrusted || isApiKeyGood) {

        setApiKeyUser(isApiKeyGood, website, engine)

        val RETURN501 = true // per realm setting?

        var body:String = ""

        engine.process.map { engine =>
          cdebug << s"Engine done 1 ... ${engine.id}"
          val errors = new ListBuffer[String]()

          // no rules matched for any of the first level messages
          var ok = if (RETURN501 && (
              engine.root.children
                  .exists(y => y.children
                  .exists(x =>
                    x.value.isInstanceOf[EWarning] &&
                        x.value.asInstanceOf[EWarning].code == DieselMsg.ENGINE.ERR_NORULESMATCH)
              ))) {
            // no rules applied - 501 - prevents us from returning settings values when nothing matched
            body = s"No rules matched for path: (${path}) message: (${msg.get.ea})" + s" info: ${engine.settings.realm}"
            info(body)

            Status(NOT_IMPLEMENTED)(body)
                .withHeaders("diesel-reason" -> body)

          } else if (
            "value" == settings.resultMode || "" == settings.resultMode
          ) {
            val resp = engine.extractOldValue(ea)
            val resValue = resp.map(_.currentStringValue).getOrElse("")

            val ctype =
            // is there a desired type
              engine.ctx.get(DieselMsg.HTTP.CTYPE).filter(_.length > 0)
                  .getOrElse(
                    resp.map(p => WTypes.getContentType(p.ttype)).getOrElse(WTypes.Mime.textPlain)
                  )

            body = stripQuotes(resValue)

            // exception?
            val code = resp
                .filter(_.isOfType(WTypes.wt.EXCEPTION))
                .map(_.calculatedTypedValue(engine.ctx).value)
                .filter(_.isInstanceOf[DieselException])
                .flatMap(_.asInstanceOf[DieselException].code)

            // is there a desired status
            val httpCode = code.orElse(
              engine.ctx.get(DieselMsg.HTTP.STATUS).filter(_.length > 0).map(_.toInt)
            ) getOrElse {
              200
            }

            // should stream response?

            Status(httpCode)(body).as(ctype)

          } else {
            // multiple values as json
            val valuesp = engine.extractValues(e,a)
            val values = valuesp.map(p => (p.name, p.currentStringValue))

            var m = Map(
              "values" -> values.toMap,
              "totalCount" -> engine.totalTestCount,
              "failureCount" -> engine.failedTestCount,
              "errors" -> errors.toList,
              DieselJsonFactory.dieselTrace -> DieselTrace(engine.root, settings.node, engine.id, "diesel", "runDom",
                settings.parentNodeId).toJson
            )

            if ("treeHtml" == settings.resultMode) m = m + ("tree" -> engine.root.toHtml)
            if ("treeJson" == settings.resultMode) m = m + ("tree" -> engine.root.toJson)

            if ("debug" == settings.resultMode) {
              body = engine.root.toString
              Ok(body).as(WTypes.Mime.appText)
            } else if ("dieselTree" == settings.resultMode) {
              val m = engine.root.toj
              val y = DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
              val x = js.tojsons(y.toj)
              body = x
              Ok(body).as(WTypes.Mime.appJson)
            } else
              body = js.tojsons(m)
            Ok(body).as(WTypes.Mime.appJson)
          }

          ok = ok.withHeaders(
            DomEngineHelper.dieselFlowId -> engine.id,
            DomEngineSettings.DIESEL_HOST -> engine.settings.dieselHost.mkString,
            DomEngineSettings.DIESEL_NODE_ID -> engine.settings.node
          )

          engine.addResponseInfo(ok.header.status, body, ok.header.headers)

          ok
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
        val infomsg = s"Unauthorized msg access (key) [irundom] for ${msg.map(m => m.entity + m.met)}"
        info(infomsg)

        Future.successful(
          Unauthorized(infomsg)
        )
      }
  }

  /** execute message to given reactor
    *
    * @param path is the useful path (without prefix). Either an e.a or e/a or template match
    * @param useThisStory  if nonEmpty then will use this (find it first) plus blender
    * @param useThisStoryPage if nonEmpty then will use this plus blender
    */
  private def irunDomInt(path: String, useThisStory: Option[WID], useThisStoryPage: Option[WikiEntry] = None, useThisSpecPage: List[WikiEntry] = Nil) (implicit stok:RazRequest) : Future[Result] = {

    var t1 = System.currentTimeMillis()

    // not always the same as the request...
    val reactor = stok.website.dieselReactor
    val website = Website.forRealm(reactor).getOrElse(stok.website)

    // see if client wanted to force a response code
    stok.qhParm("dieselHttpResponse").filter(_ != "200").map {code =>
      Future.successful( new Status(code.toInt)
        .apply("client requested code: "+code)
        .withHeaders("diesel-reason" -> s"client requested dieselHttpResponse $code in realm ${stok.realm}")
      )
    }.getOrElse {

      DieselMsg.logdb("DIESEL_FIDDLE_iRUNDOM", stok.au.map(_.userName).getOrElse("Anon"), s"EA : $path",
        " story: " + useThisStory)

      var settings = DomEngineHelper.settingsFrom(stok)
      settings = settings.copy(realm = Some(reactor))

      // todo review security. Should use current user but if this is a spawn, the original user is passed in.
      // how to sign it? who can impersonate other users?
      val userId = settings.userId.map(new ObjectId(_)) orElse stok.au.map(_._id)

      val pages = if (settings.blenderMode) { // blend all specs and stories
        val stories = if (settings.sketchMode)
          EnginePrep.catPages("Story", reactor). /*filter(_.name != stw.get.name).*/ toList else Nil

        val specs = EnginePrep.catPagesFiltered("Spec", reactor)
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

      var t2 = System.currentTimeMillis()

      // no need to log, in't traced in trace
      //ctrace << "irunDom.specs: \n  " + pages.map(_.wid.wpathFull).mkString("\n  ")

      // to domain
      val dom =
        (pages ::: useThisSpecPage).flatMap(p => SpecCache.orcached(p, WikiDomain.domFrom(p)).toList)
          .foldLeft(RDomain.empty)((a, b) => a.plus(b)).revise.addRoot

      val story2 = if (settings.sketchMode && useThisStoryPage.isEmpty) {
        // in sketch mode, add the temp fiddle tests - filter out messages, as we already have one
        useThisStory.map { p =>
          Autosave
              .find("wikie", p.defaultRealmTo(reactor), userId)
              .flatMap(_.get("content")) getOrElse p.content.mkString
        } getOrElse Autosave.find("wikie", WID("", "").r(reactor), userId).flatMap(_.get("content")).mkString
      } else if (useThisStory.isDefined) {
        useThisStoryPage.map(_.content).getOrElse(useThisStory.get.content.mkString)
      } else ""

      val story = /*story + "\n"+ */ story2.lines.filterNot(x =>
        x.trim.startsWith("$msg") || x.trim.startsWith("$receive")
      ).mkString("\n") + "\n"

      val sname = useThisStoryPage.map(_.wid).orElse(useThisStory).map(_.name + "-Fiddle").getOrElse("runDomFiddle")

      val ipage = new WikiEntry("Story", sname, "fiddle", "md", story, stok.au.map(_._id).getOrElse(NOUSER),
        Seq("dslObject"), reactor)
      val idom = WikiDomain.domFrom(ipage).get.revise.addRoot

      var t3 = System.currentTimeMillis()

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
        ipage :: pages ::: useThisSpecPage ::: useThisStory.flatMap(_.page).orElse(
          useThisStoryPage).toList map WikiDomain.spec,
        DieselMsg.irunDom + path
      )

      var t4 = System.currentTimeMillis()

      var str = engine.settings.postedContent.map(_.body).mkString
      if(str.length > 1000) str = str.take(1000) + "..."

      engine.root.prependAllNoEvents(List(
        DomAst(
          ETrace("AUTH details",
            s"User: ${stok.au.map(_.userName)}, roles: ${settings.user.map(_.roles)}, clients: ${settings.user.map(_.authClient)}, realm:${settings.user.map(_.authRealm)}, method: ${settings.user.map(_.authMethod)}"),
          AstKinds.DEBUG)
            .withStatus(DomState.SKIPPED),
        DomAst(
          EInfo("HTTP Request details", printRequest(stok.req, str)),
          AstKinds.DEBUG)
            .withStatus(DomState.SKIPPED)
      ))

      setHostname(engine.ctx.root)

      // find template matching the input message, to parse attrs
      val t@(trequest, e, a, matchedParms) = findEA(path, engine, useThisStory)
      val ea = e + "." + a

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
         if(matchedParms.isDefined) msg.copy(attrs = msg.attrs ::: matchedParms.get.toList.map(t=>new P(t._1, t._2)))
         else msg
       )

      val isApiKeyGood = stok.validateXApiKey

      val isPublic = msg.exists(isMsgPublic(_, reactor, website))
      val isTrusted = isMemberOrTrusted(msg, reactor, website)

      engine.withInitialMsg(msg)

      var t5 = System.currentTimeMillis()

      clog << s"irunDom: Message: $msg isPublic=$isPublic isTrusted=$isTrusted " +
          s"isApiKeyGood=$isApiKeyGood"

      if (isPublic || isTrusted || isApiKeyGood) {

        setApiKeyUser(isApiKeyGood, website, engine)

        val msgAst = msg.map { msg =>
          EnginePrep.addMsgToAst(engine.root, msg)
        }

        val RETURN501 = true // per realm setting?

        var body:String = ""

        engine.root.prependAllNoEvents(List(
          DomAst(
            EInfo(s"Eng prep (irunInt) total=${t5-t1} ", s"total=${t5-t1} getTopics=${t2-t1} domFromTopics=${t3-t2} mkEngine=${t4-t3} msgFind=${t5-t4}"),
            AstKinds.TRACE)
              .withStatus(DomState.SKIPPED)
              .withDuration(t1, t5)
        ))

        engine.prepTime = t5-t1

        engine.process.map { engine =>
          cdebug << s"Engine done 1 ... ${engine.id}"
          val errors = new ListBuffer[String]()

          // no rules matched
          var ok = if (RETURN501 && (msgAst.isEmpty ||
              msgAst.get.children.headOption
                  .exists(x =>
                    x.value.isInstanceOf[EWarning] &&
                        x.value.asInstanceOf[EWarning].code == DieselMsg.ENGINE.ERR_NORULESMATCH)
              )) {
            // no rules applied - 501 - prevents us from returning settings values when nothing matched
            body = s"No rules matched for path: (${path}) message: (${msg.get.ea})" + s" info: ${engine.settings.realm}"
            info(body)

            Status(NOT_IMPLEMENTED)(body)
                .withHeaders("diesel-reason" -> body)

          } else if (RETURN501 && (msgAst.isEmpty ||
              // it was a diesel.rest but nothing matched underneath
                msgAst.get.children
                    .exists(
                        a=> a.kind == AstKinds.RECEIVED &&
                        a.value.isInstanceOf[EMsg] &&
                        a.value.asInstanceOf[EMsg].ea == DieselMsg.ENGINE.DIESEL_REST &&
                        ! a.children.exists(x=>
                          x.kind == AstKinds.GENERATED)
                    )
                )) {
              // no rules applied - 501 - prevents us from returning settings values when nothing matched
            body = s"No rules matched for path: (${path}) message: (${msg.get.ea})" + s" info: ${engine.settings.realm}"
              info(body)

              Status(NOT_IMPLEMENTED)(body)
                  .withHeaders("diesel-reason" -> body)

          } else if (
            "value" == settings.resultMode || "" == settings.resultMode
          ) {
            val resp = engine.extractOldValue(ea)
            val resValue = resp.map(_.currentStringValue).getOrElse("")

            val ctype =
            // is there a desired type
              engine.ctx.get(DieselMsg.HTTP.CTYPE).filter(_.length > 0)
                  .getOrElse(
                    resp.map(p => WTypes.getContentType(p.ttype)).getOrElse(WTypes.Mime.textPlain)
                  )

            body = stripQuotes(resValue)

            // exception?
            val code = resp
                .filter(_.isOfType(WTypes.wt.EXCEPTION))
                .map(_.calculatedTypedValue(engine.ctx).value)
                .filter(_.isInstanceOf[DieselException])
                .flatMap(_.asInstanceOf[DieselException].code)

            // is there a desired status
            code.orElse(
              engine.ctx.get(DieselMsg.HTTP.STATUS).filter(_.length > 0).map(_.toInt)
            ).map { st =>
              Status(st)(body)
            } getOrElse {
              Ok(body)
            }.as(ctype)

          } else {
            // multiple values as json
            val valuesp = engine.extractValues(e,a)
            val values = valuesp.map(p => (p.name, p.currentStringValue))

            var m = Map(
              "values" -> values.toMap,
              "totalCount" -> engine.totalTestCount,
              "failureCount" -> engine.failedTestCount,
              "errors" -> errors.toList,
              DieselJsonFactory.dieselTrace -> DieselTrace(root, settings.node, engine.id, "diesel", "runDom",
                settings.parentNodeId).toJson
            )

            if ("treeHtml" == settings.resultMode) m = m + ("tree" -> root.toHtml)
            if ("treeJson" == settings.resultMode) m = m + ("tree" -> root.toJson)

            if ("debug" == settings.resultMode) {
              body = root.toString
              Ok(body).as(WTypes.Mime.appText)
            } else if ("dieselTree" == settings.resultMode) {
              val m = root.toj
              val y = DieselJsonFactory.fromj(m).asInstanceOf[DomAst]
              val x = js.tojsons(y.toj)
              body = x
              Ok(body).as(WTypes.Mime.appJson)
            } else
              body = js.tojsons(m)
            Ok(body).as(WTypes.Mime.appJson)
          }

          ok = ok.withHeaders(
            DomEngineHelper.dieselFlowId -> engine.id,
            DomEngineSettings.DIESEL_HOST -> engine.settings.dieselHost.mkString,
            DomEngineSettings.DIESEL_NODE_ID -> engine.settings.node
          )

          engine.addResponseInfo(ok.header.status, body, ok.header.headers)

          ok
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
        val infomsg = s"Unauthorized msg access (key) [irundom] for ${msg.map(m => m.entity + m.met)}"
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
  def runRest(path: String, verb: String, mock: Boolean): Action[RawBuffer] = Action(parse.raw) { implicit request =>
    implicit val stok = razRequest

    try {

      // not always the same as the request...
      val reactor = stok.website.dieselReactor
      val website = Website.forRealm(reactor).getOrElse(stok.website)

      val postDetails1 = verb + " " + path
      val postDetails2 = stok.headers

      // see if client wanted to force a response code
      stok.qhParm("dieselHttpResponse").filter(_ != "200").map { code =>
        new Status(code.toInt)
            .apply("client requested code: " + code)
            .withHeaders("diesel-reason" -> s"client requested dieselHttpResponse $code in realm ${stok.realm}")
      }.getOrElse {

        var t1Start = System.currentTimeMillis()

        val requestContentType = stok.req.contentType

        val uid = stok.au.map(_._id).getOrElse(NOUSER)

        val raw = request.body.asBytes()
        val body = raw.map(a => new String(a)).getOrElse("")
        val postedContent = Some(new EContent(body, stok.req.contentType.mkString, 200, Map.empty, None, raw))

        // save to check results later
        var dieselRestMsg: Option[EMsg] = None

        // make the diesel.rest message
        def mkDieselRest(ctx: ECtx) = {
          val qparams = DomEngineHelper.parmsFromRequestHeader(request)
          val hparams = DomEngineHelper.headers(request)
          val qFlat = qparams.map(t => P.fromTypedValue(t._1, t._2, WTypes.wt.STRING)).toList
          val qJson = P.fromTypedValue("dieselQuery", qparams, WTypes.wt.JSON)
          val hJson = P.fromTypedValue("dieselHeaders", hparams, WTypes.wt.JSON)

          // looking at the posted content now...
          var posted =
            if (
              ("POST".equals(verb) ||
                  "PUT".equals(verb) ||
                  "PATCH".equals(verb))
                  && postedContent.isDefined) {
              postedContent.get.asDieselParams(new SimpleECtx())
            } else Nil

          // payload goes to root
          posted.filter(_.name == Diesel.PAYLOAD).map(ctx.put)
          posted = posted.filter(_.name != Diesel.PAYLOAD)

          dieselRestMsg = Some(EMsg(
            DieselMsg.ENGINE.DIESEL_REST,
            List(
              P.fromTypedValue("fromApigw", true),
              P.fromTypedValue("path", path),
              P.fromTypedValue("verb", verb),
              P.fromTypedValue("queryStringEncoded", stok.req.rawQueryString),
              P.fromTypedValue("queryString", URLDecoder.decode(stok.req.rawQueryString)),
              hJson,
              qJson
              )
            ::: qFlat ::: posted
          ))

          dieselRestMsg
        }

        // more testing options
        stok.qhParm("dieselSleep").map { code =>
          ctrace << s"MOCK DIESEL SLEEPING... $code ms"
          Thread.sleep(code.toInt)
        }

        // todo sort out this mess
        val settings = DomEngineHelper.settingsFromRequestHeader(stok.req, postedContent).copy(realm = Some(reactor))
        settings.mockMode = mock
        // if the config tag includes "draft" then use drafts
        // todo have a trust settings passed: this call is made by backend to itself - put a temp token or smth to
        //  trust itself, so nobody can replay this call
        settings.draftMode = settings.configTag.mkString.contains("draft") && settings.userId.exists(
          u => settings.configTag.exists(_.contains(u)))

        settings.user = stok.au

        ctrace << s"RUN_REST_REQUEST verb:$verb mock:$mock path:$path realm:${reactor}\nheaders: ${stok.req.headers}" +
            body

        var description = s"runRest:$verb:$path"
        if (stok.req.rawQueryString.trim.length > 0) {
          description = description + s"?${stok.req.rawQueryString}"
        }

        var t2StartPrepEngine = System.currentTimeMillis()

        var engine = EnginePrep.prepEngine(new ObjectId().toString,
          settings,
          reactor,
          None,
          false,
          stok.au,
          description,
          None,
          // empty story so nothing is added to root
          List(new WikiEntry("Story", "temp", "temp", "md", "", uid, Seq("dslObject"), reactor))
        )

        engine.root.prependAllNoEvents(
          List(DomAst(
            ETrace("AUTH details",
              s"User: ${stok.au.map(_.userName)}, roles: ${settings.user.map(_.roles)}, clients: ${settings.user.map(_.authClient)}, realm:${settings.user.map(_.authRealm)}, method: ${settings.user.map(_.authMethod)}"),
            AstKinds.DEBUG)
              .withStatus(DomState.SKIPPED),
            DomAst(
            EInfo("HTTP Request details2",
              printRequest(request, body)), AstKinds.DEBUG)
              .withStatus(DomState.SKIPPED)
          ))

        // add query parms
        val q = stok.req.queryString.map(t => (t._1, t._2.mkString))
        engine.ctx.putAll(q.map(t => new P(t._1, t._2)).toList)

        // add the request
        new DomReq(stok.req).addTo(engine.ctx)

        var t2EndPrepEngine = System.currentTimeMillis()

        // find template matching the input message, to parse attrs
        val t@(trequest, e: String, a: String, matchedParms) = {
          val fea = findEA(path, engine)
          fea._1
              .map { x => fea }
              .orElse {
                // if a message exists, map to it
                spec(fea._2, fea._3)(engine.ctx).map(x => fea)
          }.orElse {
            // we're going to make a diesel.rest message later
            mkDieselRest(engine.ctx).map(x => (None, x.entity, x.met, None))
          }.getOrElse {
            fea
          }
        }

        // message specified return mappings, if any
        val msgSpec = spec(e, a)(engine.ctx).filter(m =>
          // see msgSpec usage below - we need to not do for diesel.rest - if someone does `$msg diesel.rest` it will
          // find a spec for it and mess up badly later...
          ("diesel" != m.entity || "rest" != m.met)
        )

        val inSpec = msgSpec.toList.flatMap(_.attrs)
        val outSpec = msgSpec.toList.flatMap(_.ret)

        // collect any parm specs
        val outSpecs = outSpec.map(p => (p.name, p.currentStringValue, p.expr.mkString))

        // stuff like incoming content-type
        val incomingMetas = stok.headers.toSimpleMap

        // try to match incoming
        def findIt = findMessage(
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
          postedContent,
          requestContentType)

        // incoming message
        // first templates already matched,
        // then if there was a spec, findIt
        // else diesel.rest
        // else e.a
        val msg: Option[EMsg] =
        (trequest.flatMap(_ => findIt) orElse
            msgSpec.flatMap(_ => findIt) orElse
            mkDieselRest(engine.ctx) orElse
            findIt)

            .map(msg =>
              // add matched parms - they come from any template matching
              // todo why map matched parms and not keep type/value what abt numbers, escaped json etc?
              if (matchedParms.isDefined) msg.copy(
                attrs = msg.attrs ::: matchedParms.get.toList.map(t => new P(t._1, t._2)))
              else msg
            )

        if (msg.isDefined && !msg.exists(_.spec.isDefined)) {
          // try to find a spec
          msg.get.spec = spec(msg.get.entity, msg.get.met)(engine.ctx)
        }

        val isApiKeyGood = stok.validateXApiKey

        cdebug << s"Message found: ${Enc.shorten(msg.toString, 5000)} isApiKeyGood=$isApiKeyGood"

        // add a debug point with the post info
        val desc = (DomAst(EInfo(postDetails1, postDetails2.toString), AstKinds.DEBUG).withStatus(DomState.SKIPPED))
//      engine.root.append(desc)(engine)

        var t3FoundMessage = System.currentTimeMillis()

        // is message visible?
        if (msg.isDefined && isMsgVisible(msg.get, reactor, website) || isMemberOrTrusted(msg, reactor,
          website) || isApiKeyGood) {
          msg.map { msg =>

            setApiKeyUser(isApiKeyGood, website, engine)

            val msgAst = EnginePrep.addMsgToAst(engine.root, msg)
            DomCollector.collectAst("runRest", stok.realm, engine, stok.uri)

            var t4StartProcess = System.currentTimeMillis()

            // move the eng prep node under the REST prep node
            val engprepi = engine.root.childrenCol.indexWhere { a =>
              a.value.isInstanceOf[EInfo] && a.value.asInstanceOf[EInfo].msg.startsWith("Eng prep")
            }

            val engprep = if(engprepi >= 0) Option(engine.root.childrenCol.remove(engprepi)) else None

            val restprep =
              DomAst(
                EInfo(s"REST prep time total=${t4StartProcess-t1Start} ", s"total=${t4StartProcess-t1Start} parseREST=${t2StartPrepEngine-t1Start} engPrepTime=${t2EndPrepEngine - t2StartPrepEngine} findMsg=${t3FoundMessage-t2EndPrepEngine} prepEngine=${t4StartProcess-t3FoundMessage}"),
                AstKinds.TRACE)
                  .withStatus(DomState.SKIPPED)
                  .withDuration(t1Start, t4StartProcess)
            // note this will reflect better the durations. ALSO eng prep is included!!

            engprep.foreach(x => restprep.childrenCol.append(x))

            engine.root.prependAllNoEvents(List(restprep))
            engine.prepTime = t4StartProcess - t1Start

            // process message
            val res = engine.process.map { engine =>

              cdebug << s"Engine done ... ${engine.id}"

              //        val res = engine.extractValues(e, a)

              // find output template and format output
              // todo this is weird - need something more regular...
              val templateResp =
                  if(engine.useTemplates)
                    engine.ctx.findTemplate(e + "." + a, "response").orElse {
                // see if there is only one message child... and it has an output template - we'll use that one
                val m = engine
                    .root
                    .children
                    // without hardcoded engine messages - diesel.rest and ping is allowed
                    .filterNot(x =>
                      x.value.isInstanceOf[EMsg] &&
                          x.value.asInstanceOf[EMsg].entity == DieselMsg.ENGINE.ENTITY &&
                          x.value.asInstanceOf[EMsg].met != "rest" &&
                          x.value.asInstanceOf[EMsg].met != "ping")
                    .filter(x =>
                      // skip debug infos and other stuff = we need the first message
                      x.value.isInstanceOf[EMsg])
                    .head
                    .children
                    .find(_.children.headOption.exists(_.value.isInstanceOf[EMsg]))

                m.flatMap { m =>
                  val msg = m.children.head.value.asInstanceOf[EMsg]
                  engine.ctx.findTemplate(msg.entity + "." + msg.met, "response")
                }
              }
              else None

              // todo optimize
              //ctrace << engine.root.toString

              var response: Option[Result] = None

              var ctype =
                templateResp.flatMap(_.parm("content-type")) // response ctype
                    .orElse(trequest.flatMap(_.parm("content-type"))) // or request ctype
                    .orElse {
                      // is there a desired type
                      engine.ctx.get(DieselMsg.HTTP.CTYPE).filter(_.length > 0)
                    }
                    .getOrElse("text/plain") // or plain

              templateResp.map { t =>
                // we have a response template

                //          val s = EESnakk.formatTemplate(t.content, ECtx(res))
                // engine.ctx accumulates results and we add the input

                // templates strt with a \n normally
                val content = t.content.replaceFirst("^\\r?\\n", "")

                ctrace << s"Found templateResp... ${t.name}"

                val s = if (content.length > 0) {
                  // template has content - format it
                  val allValues = new StaticECtx(msg.attrs, Some(engine.ctx))
                  EESnakk.formatTemplate(content, allValues)
                } else {
                  // engine.ctx.get(Diesel.PAYLOAD).getOrElse("no msg recognized, no result, no response template")

                  engine.ctx.getp(Diesel.PAYLOAD).map { p =>
//                if (p.value.isDefined) {
                    if (p.hasCurrentValue) {
//                  ctype = WTypes.getContentType(p.value.get.cType)
                      ctype = WTypes.getContentType(p.currentValue.ttype)

                      val v = p.calculatedTypedValue(engine.ctx)
                      v.value match {
                        case x: Array[Byte] =>
                          response = Some(Ok(x).as(ctype))
                        case _ =>
                          response = Some(Ok(v.asString).as(ctype))
                      }

                      ""
                    } else
                      engine.ctx.get(Diesel.PAYLOAD).getOrElse("no msg recognized, no result, no response template")
                  }.getOrElse(
                    "no msg recognized, no result, no response template"
                  )
                }

                ctrace << s"RUN_REST_REPLY $verb $mock $path\n$s as $ctype"

                response.map { res =>
                  // found a response template and made the response - add template parms as headers
                  val headers = t.parms
                      .filter(_._1.toLowerCase.trim != "content-type")
                      .filter(_._1.startsWith("http.header."))
                      .map(t => (t._1.replaceFirst("http.header.", ""), t._2))
                      .toSeq

                  // just use it to add the response info
                  mkStatus(Some(P.fromSmartTypedValue("x", res.body.toString)), engine).as(ctype)

                  res.withHeaders(headers: _*)
                }.getOrElse {
                  // no response formatting found
                  mkStatus(Some(P.fromSmartTypedValue("x", s)), engine).as(ctype)
                }

              }.getOrElse {
                // no response template - send the payload

                val res = s"No response template for ${e}.${a}\n" + engine.root.toString
                ctrace << s"RUN_REST_REPLY $verb $mock $path\n" + res
                val payload = engine.ctx.getp(Diesel.PAYLOAD).filter(_.ttype != WTypes.wt.UNDEFINED)
                // todo set ctype based on payload if found

                payload.map { p =>
                  if (p.value.isDefined) {
                    ctype =
                    // is there a desired type
                      engine.ctx.get(DieselMsg.HTTP.CTYPE).filter(_.length > 0)
                          .getOrElse(
                            // if not, infer from data
                            WTypes.getContentType(p.value.get.cType)
                          )

                    p.value.get.value match {
                      case x: Array[Byte] =>
                        response = Some(Ok(x).as(ctype))
//                      case s: DomStream =>
//                        val hts = new HttpEntity.Streamed()
//                        response = Some(Ok.sendEntity(hts))
                      case _ =>
                        response = Some(Ok(p.value.get.asString).as(ctype))
                    }
                  }
                }

                val result = mkStatus(payload, engine, Some(msgAst)).as(ctype)

                // don't show this for diesel.rest
                dieselRestMsg
                    .map(x => result)
                    .getOrElse {
                      result
                          .withHeaders(
                            "diesel-reason" -> s"response template not found for $path in realm ${stok.realm}")
                    }
              }
            }

            // must allow for ctx.sleeps
            // todo why 50 sec
            var dur = WikiConfig.getInstance.get.prop("diesel.rest.timeout", "50 seconds")
            val tcode = WikiConfig.getInstance.get.prop("diesel.rest.timeoutCode", "504")

            // any overrides?
            Website.getRealmProps(reactor).get("diesel.rest.timeout.exclusions")
                .flatMap(_.value).toList.flatMap(_.asArray).foreach {o =>
              val x = o.asInstanceOf[collection.mutable.HashMap[String,String]]
              if (engine.description.matches(x("pattern"))) {
                dur = x("timeout")
                engine.root.appendAllNoEvents(
                  List(
                    DomAst(
                      EInfo(s"diesel.rest.timeout reset to: ${dur}", s"timeout override to: ($dur)"), AstKinds.VERBOSE)
                        .withStatus(DomState.SKIPPED)
                  ))
              }
            }

            try {
              Await.result(res, Duration(dur))
            } catch {
              case e: java.util.concurrent.TimeoutException => {
                engine.stopNow()
                engine.root.appendAllNoEvents(
                  List(
                    DomAst(
                      EInfo(s"Engine timedout at ${dur} !", s"DomApi:Workflow took too long ($dur)"), AstKinds.DEBUG)
                        .withStatus(DomState.SKIPPED)
                  )
                )
                val msg = (s"Workflow took too long ($dur) - not enough resources?")
                val st = new Status(Integer.parseInt(tcode))(msg)
                    .withHeaders("diesel-reason" -> s"Flow didn't complete in $dur")
                engine.withReturned(msg, tcode.toInt)
                st
              }
            }
          } getOrElse {
            val msg = (s"ERR Realm(${reactor}): Template or message not found for path: " + path)
            engine.withReturned(msg, 404)
            NotFound(msg)
                .withHeaders("diesel-reason" -> s"template or message not found for $path in realm ${reactor}")
          }
        } else {
          val x = s"Unauthorized msg access [runrest] (diesel.visibility:${stok.website.dieselVisiblity}, ${
            stok.au.map(_.ename).mkString
          })"
          info(x)
          info(s"msg: $msg - ${msg.get.isPublic} - ${msg.get.spec.toString} - reactor: $reactor")
          engine.withReturned(x, 401)
          Unauthorized(x)
        }
      }
    } catch {
      case t:Throwable => {
        // leave a record behind
//        val settings = new DomEngineSettings().copy(realm = Some(stok.realm))
        val settings = DomEngineHelper.settingsFromRequestHeader(stok.req, None).copy(realm = Some(stok.realm))
        val engine = EnginePrep.prepEngine(
          new ObjectId().toString,
          settings,
          stok.realm,
          None,
          false,
          stok.au,
          "EXCEPTION:" + stok.method + ":" + stok.uri,
          None,
          Nil
        )

        engine.root.prependAllNoEvents(
          List(
            DomAst(
              ETrace("AUTH details",
                s"User: ${stok.au.map(_.userName)}, roles: ${settings.user.map(_.roles)}, clients: ${settings.user.map(_.authClient)}, realm:${settings.user.map(_.authRealm)}, method: ${settings.user.map(_.authMethod)}"),
              AstKinds.DEBUG)
                .withStatus(DomState.SKIPPED),
            DomAst(
              EInfo("HTTP Request details2",
                printRequest(request, "xx")), AstKinds.DEBUG)
                .withStatus(DomState.SKIPPED),
            DomAst(
            EVal(P.fromSmartTypedValue(Diesel.PAYLOAD, t.getClass.getName + ":" + t.getMessage)), AstKinds.GENERATED)
              .withStatus(DomState.SKIPPED)
          )
        )

        engine.status = DomState.CANCEL
        engine.engineDone(false, false)
        DomCollector.collectAst("runRest", stok.realm, engine, stok.uri)

        val sres = t.toString
        engine.withReturned(sres, 500)
        InternalServerError(sres)
      }
    }
  }

  /** /diesel/mock/ path  */
  def dieselMockGET(path: String) = runRest("/" + path, "GET", true)
  def dieselMockPOST(path: String) = runRest("/" + path, "POST", true)
  def dieselMockPUT(path: String) = runRest("/" + path, "PUT", true)
  def dieselMockPATCH(path: String) = runRest("/" + path, "PATCH", true)
  def dieselMockDELETE(path: String) = runRest("/" + path, "DELETE", true)

  /** /diesel/rest/ path  */
  def dieselRestGET(path: String) = runRest("/" + path, "GET", mock=false)
  def dieselRestPOST(path: String) = runRest("/" + path, "POST", mock=false)
  def dieselRestPUT(path: String) = runRest("/" + path, "PUT", mock=false)
  def dieselRestPATCH(path: String) = runRest("/" + path, "PATCH", mock=false)
  def dieselRestDELETE(path: String) = runRest("/" + path, "DELETE", mock=false)

  // simple file uplaod form
  def dieselIoTest(path: String, andThen:String) = Filter(noRobots).async { implicit stok =>
    if (WikiConfig.getInstance.get.isLocalhost && stok.au.exists(_.isActive)) {
      Future.successful(
        Ok(
          views.html.fiddle.dieselIoTest(path, andThen)
        ))
    } else {
      Future.successful(
        Unauthorized("not auth")
      )
    }
  }

  // test upload files
  def dieselIoUpload(fileName: String, andThen:String) = Action.async(parse.multipartFormData) { request =>
    implicit val stok = razRequest(request)

    if (WikiConfig.getInstance.get.isLocalhost &&
        (stok.au.exists(_.isActive)) //||
    ) {
      request.body.file("dieselFile").map { file =>
        import java.io.File
        val filename = file.filename
        val contentType = file.contentType
        file.ref.moveTo(new File(s"./$fileName"))

        if(andThen.length > 0) {

          val m = if(andThen startsWith "$") andThen else "$msg " + andThen
          val strMsg = DieselMsgString(
            m,
            DieselTarget.ENV(stok.realm),
            Map("status" -> "done.success")
          )

          irunDomStr(strMsg)
        } else {
          Future.successful(
            Ok(s"""{"status": "done.success", "msg":"File uploaded to $fileName"} """).as("application/json")
          )
        }
      }.getOrElse {
        Future.successful(InternalServerError("Error: 'dieselFile' is missing"))
      }
    } else {
      Future.successful(Unauthorized("Not localhost or not authorized!"))
    }
  }

  // test upload files
  def dieselIoDelete(fileName: String, andThen:String) = Action.async { request =>
    implicit val stok = razRequest(request)

    if (WikiConfig.getInstance.get.isLocalhost &&
        (stok.au.exists(_.isActive)) //||
    ) {
        import java.io.File
        val newpath = fileName.replaceAllLiterally("/", "_")
        val file = new File(s"./$newpath")
        val status = if(file.delete()) "done.success" else "done.fail"

        if(andThen.length > 0) {
          val m = if(andThen startsWith "$") andThen else "$msg " + andThen
          val strMsg = DieselMsgString(
            m,
            DieselTarget.ENV(stok.realm),
            Map("status" -> status)
          )

          irunDomStr(strMsg)
        } else {
          Future.successful(
            Ok(s"""{"status": "$status", "msg":"Delete file path:$fileName"} """).as("application/json")
          )
        }
    } else {
      Future.successful(Unauthorized("Not localhost or not authorized!"))
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

  /** /diesel/react/ea
    * API msg sent to reactor
    */
  def react(e: String, a: String) = Filter(noRobots).async { implicit stok =>
    // insert a dot only if needed
    val ea = (
        if(e.length > 0 && a.length > 0) e + "." + a else e+a
        ).replaceAllLiterally("/", ".")
    irunDom(ea, None)
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

    def diffTable(p: Patch) = s"""<small>${views.html.admin.diffTable("", p, Some(("How", "Original", "Draft")))
    }</small>"""

    def diffT = diffTable(DiffUtils.diff(stw.lines.toList, story.lines.toList))

    def diffP = diffTable(DiffUtils.diff(spw.lines.toList, spec.lines.toList))

    retj << Map(
      "specDiff" -> (if (specWpath.length > 0) diffP else ""),
      "storyDiff" -> (if (storyWpath.length > 0) diffT else "")
    )
  }

  /** did the flow have a preferrred http response status/headers? */
  def mkStatus(response:Option[P], engine:DomEngine, msgAst:Option[DomAst] = None) (implicit stok:RazRequest) = {
    var body:String = ""

    var ok = {

      val RETURN501 = true // per realm setting?

      val RETURNEXC = true // per realm setting?

        val errors = new ListBuffer[String]()

        // no rules matched
        if (RETURN501 && (
            msgAst.flatMap(_.children.headOption)
                .exists(x =>
                  x.value.isInstanceOf[EWarning] &&
                      x.value.asInstanceOf[EWarning].code == DieselMsg.ENGINE.ERR_NORULESMATCH)
            )) {
          // no rules applied - 501 - prevents us from returning settings values when nothing matched
          body = s"No rules matched for: (${engine.description}) message: (${
            msgAst.map(_.value.toString)
          })" + s" info: ${engine.settings.realm}"
          info(body)

          Status(NOT_IMPLEMENTED)(body)
              .withHeaders("diesel-reason" -> body)

        } else if (RETURN501 && (
            // it was a diesel.rest but nothing matched underneath
            msgAst
                .exists(
                  a => a.kind == AstKinds.RECEIVED &&
                      a.value.isInstanceOf[EMsg] &&
                      a.value.asInstanceOf[EMsg].ea == DieselMsg.ENGINE.DIESEL_REST &&
                      a.find(_.kind == AstKinds.GENERATED).isEmpty
                )
            )) {
          // no rules applied - 501 - prevents us from returning settings values when nothing matched
          body = s"No rules matched for: (${engine.description}) message: (${
            msgAst.map(_.value.toString)
          })" + s" info: ${engine.settings.realm}"
          info(body)

          Status(NOT_IMPLEMENTED)(body)
              .withHeaders("diesel-reason" -> body)

        } else {
          // generic inferred statuses

          body = response.map(_.currentStringValue).mkString

          // is there a desired status
          engine.ctx.get(DieselMsg.HTTP.STATUS).filter(_.length > 0).map {st=>
            Status(st.toInt)(body)
          } getOrElse {

            //payload is undefined - not found. COMMENTED OUT, never used yet... worried it may cause issues
            // clients go down the onSuccess or onError
            if((response.isEmpty || response.get.ttype == WTypes.wt.UNDEFINED) && stok.website.dieselUse404) {
              val msg = ("Flow ran but returned no payload!")
              NotFound(msg)
                .withHeaders("diesel-reason" -> msg)
            } else
              Ok(body)
          }
        }
    }

    // add headers
    engine.ctx.flattenAllAttrs.filter(_.name startsWith HTTP.HEADER_PREFIX).map { p =>
      ok = ok.withHeaders(p.name.replace(HTTP.HEADER_PREFIX, "") -> p.calculatedValue(engine.ctx))
    }

    ok = ok.withHeaders(
      DomEngineHelper.dieselFlowId -> engine.id,
      DomEngineSettings.DIESEL_HOST -> engine.settings.dieselHost.mkString,
      DomEngineSettings.DIESEL_NODE_ID -> engine.settings.node
    )

    // or from json
//    engine.ctx.getp(HTTP.RESPONSE).filter(_.ttype == "JSON").map {p=>
//      ok = ok.withHeaders(
//        p.name.replace("diesel.response.http.header.", "") -> p.calculatedValue(engine.ctx)
//      )
//    }

    engine.addResponseInfo(ok.header.status, body, ok.header.headers)

    ok
  }

  /** find e/a in engine, either e.a or matching template URL
    */
  private def findEA (path:String, engine:DomEngine, useThisStory:Option[WID]=None) : (Option[DTemplate], String, String, Option[Map[String,String]]) = {
    var e = ""
    var a = ""
    var m: Option[Map[String, String]] = None

    //some realms may not want this, it is quite heavy
    val useTemplates = engine.useTemplates

    val direction = "request"
    val eapath = if (path.startsWith("/")) path.substring(1) else path

    val trequest = if (!useTemplates) {
      engine.root.prependAllNoEvents(List(
        DomAst(
          EInfo(s"NOTE - Templates disabled, realm ${engine.settings.realm.mkString}"),
          AstKinds.TRACE)
            .withStatus(DomState.SKIPPED)
      ))

      None
    } else
      engine
          .ctx
          // first try  with e.a
          .findTemplate(eapath, direction)
          .map { t =>
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
                (content.startsWith("GET") ||
                    content.startsWith("POST") ||
                    content.startsWith("PUT") ||
                    content.startsWith("DELETE")) &&
                    // todo add and compare request header parms
                    EESnakk.templateMatchesUrl(t, "*", path, t.content).isDefined &&
                    (
                        !useThisStory.exists(
                          w => !(t.specRef.wpath startsWith w.wpath)) // startsWith becase tspec includes #section
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
        parms.map(p => engine.ctx.put(new P(p._1, p._2)))
        Some(new EMsg(e, a, parms.map(p => new P(p._1, p._2)).toList))
      } catch {
        case t: Throwable => {
          razie.Log.log("error parsing", t)
          engine.root.appendAllNoEvents({
            EError("Error parsing: " + template.specRef, t.toString) ::
                new EError("Exception : ", t) :: Nil
          }.map(DomAst(_, AstKinds.ERROR))
          )
          None
        }
      }

    } orElse {

      // no template found - match a message?
      val headers = DomEngineHelper.parmsFromRequestHeader(stok.req, content)

      // extract parms from request
      if(verb == "GET" || verb == "POST" || verb == "PUT") {
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
              razie.js.fromObject(js)
            } catch {
              case t: Throwable => {
                razie.Log.log("NO TEMPLATE found - error trying to parse body as json", t)
                engine.root.appendAllNoEvents({
                  EWarning("No template found for path: " + path) ::
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
        val plist = parms.map(p => P.fromTypedValue(p._1, p._2)).toList
        plist.map(engine.ctx.put)
//        parms.map(p => engine.ctx.put(P(p._1, p._2)))
        Some(new EMsg(e, a, plist))
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
      stok.au.exists {u=>
        // is member of diesel realm
        var isMember = u.isAdmin || u.hasRealm(reactor) ||
        // is member from trusted realm, for protected messages
//        m.exists(_.isProtected) &&
          u.hasRealm(stok.realm) && website.dieselTrust.contains(stok.realm)

//        spec.map(_.arch).exists(_ contains PROTECTED) ||
//            spec.map(_.stype).exists(_ contains PROTECTED)

//        if(isMember && m.exists(_.spec.map(_.arch)))

        // todo settle arch vs sType, what the heck. For messages nd rules

        if(isMember &&
            m.isDefined &&
            DIESEL_REST != m.get.ea &&
            m.get.spec.exists(s=> s.arch.nonEmpty || s.stype.nonEmpty)) {
          val uroles = if(stok.au.isDefined) stok.au.get.roles else Set.empty[String]
          val eroles = (m.get.spec.get.arch + "," + m.get.spec.get.stype).split(",").filter(_.startsWith("role."))

          // if the user matches one of the roles, it's ok
          var matched = eroles.isEmpty || eroles.exists(r => uroles.contains(r.substring(5)))
          var reason = s"roles=${eroles.mkString} "

          if(!matched) {
            val oauth = Website.getRealmPropAsP(reactor, "oauth").map(_.getJsonStructure)
            val mr = oauth.get.get("masterRole").map(_.toString).mkString.trim

            if(mr.nonEmpty) matched = uroles.contains(mr)
            reason = s"$reason mr=$mr "

            if(!matched) {
              val eclients = (m.get.spec.get.arch + "," + m.get.spec.get.stype).split(",").filter(_.startsWith("client."))
              val uclient = stok.au.flatMap(_.authClient).mkString

              // rule contains client
              if(uclient.nonEmpty) matched = eclients.contains(uclient)
              reason = s"$reason clients=${eclients.mkString} "

              if(!matched) {
                val mc = oauth.get.get("masterClient").map(_.toString).mkString.trim
                reason = s"$reason mc=$mc "

                // user comes from masterclient
                if(mc.nonEmpty) matched = uclient equals mc
              }

            }
          }

          if(!matched) {
            // super admins can call APIs
            matched = stok.au.exists(_.isAdmin)
          }

          if(! matched)
            throw new DieselException(s"AUTH FAILED for API ${m.get.ea}! ($reason)", Some(401))
        }

        isMember
      }
  }

  /** only call this if an api key was required and present */
  def setApiKeyUser (isApiKeyGood:Boolean, website:Website, engine:DomEngine)(implicit stok:RazRequest) = {
    if(stok.au.isEmpty && engine.settings.userId.isEmpty && isApiKeyGood) {
      // no user in request, but xapikey passed - set the default test user
      (new EECtx).setAuthUser(engine.ctx)
      engine.settings.userId = engine.ctx.get(DIESEL_USER_ID)
    }
  }

  /** can user execute message */
  def isMsgPublic (m:EMsg, reactor:String, website:Website)(implicit stok:RazRequest) = {
    m.isPublic ||
    website.dieselVisiblity == "public" ||
    DIESEL_REST == m.ea  // if diesel.rest we deem public so it can fail in the engine expansion if rule matched
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
      session.overrides.prepend(OverSession(wid, newVer, icontent, sType, sName))

      // just from the fiddle, not the entire page
      var links = WikiDomain.domFilter(newVer.copy(content = icontent + "\n")) {
        case st: EMsg =>
          st.toHref(sName)
        case st: EMock =>
          st.rule.e.asMsg.withPos(st.pos).toHref(sName, "value") +
            " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", sName, "json") + ") " +
            " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("debug", sName) + ") "
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

      DieselMsg.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("Anon"))
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

    var needsCAMap = stok.formParm("needsCAMap").toBoolean
    var needsBaseCA = stok.formParm("needsBaseCA").toBoolean
    val now = DateTime.now

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

    DieselMsg.logdb("DIESEL_FIDDLE_RUN", stok.au.map(_.userName).getOrElse("anon"))

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
    val specDom = pages.flatMap(p=>
      if(anySpecOverwrites.isDefined)
        WikiDomain.domFrom(p).toList
      else
        SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), stok.realm)

    var res = ""
    var captureTree = ""

    val storyDom = WikiDomain.domFrom(ipage).get.revise addRoot

    val fullDom = specDom plus storyDom

    val root = DomAst("root", AstKinds.ROOT).withDetails("(from story)")

    // start processing all elements
    val engine = DieselAppContext.mkEngine(fullDom, root, settings, ipage :: pages map WikiDomain.spec, "anonRunFiddle")
    setHostname(engine.ctx.root)

    EnginePrep.addStoriesToAst(engine, List(ipage))

    // decompose all tree or just testing? - if there is a capture, I will only test it
    val fut =
      engine.process

    fut.map { engine =>
      res += engine.root.toHtml

      // the anon fiddle allows mod both spec and story, let's rebuild dom...
      val storyCA = if(needsCAMap)  RDExt.toCAjmap(fullDom) else Map.empty // in blenderMode dom is full
//      val baseCA  = if(needsBaseCA) RDExt.toCAjmap(specDom) else Map.empty // in blenderMode dom is full

      val m = Map(
        "info" -> Map(
          "clientId" -> now,
          "compileOnly" -> false,
          "timeStamp" -> now,
          "totalCount" -> (engine.totalTestCount),
          "failureCount" -> engine.failedTestCount,
          "errorCount" -> engine.errorCount,
          "engineId" -> engine.id,
          "progress" -> engine.progress,
          "engineStatus" -> engine.status,
          "enginePaused" -> engine.paused.toString,
          "engineDone" -> DomState.isDone(engine.status)
        ),
        "clientId" -> now,
        "res" -> res,
        "capture" -> captureTree,
        "storyCA" -> storyCA, // in blenderMode dom is full
        "baseCA" -> Map("msg" -> Nil, "attr" -> Nil),
        "totalCount" -> (engine.totalTestCount),
        "failureCount" -> engine.failedTestCount,
        "ast" -> DomFiddles.getAstInfo(ipage)
      )

      retj << m
    }

  }

  /** set user's preferred current environment */
  def setEnv (env:String) = Filter(activeUser).async { implicit stok =>
    val au = stok.au.get
    val u = Profile.updateUser(au, au.setPrefs(stok.realm, Map("dieselEnv" -> env)))
    cleanAuth()
    // it's not actually redirecting, see client
    Future.successful(Redirect("/", SEE_OTHER))
  }

  /** todo this will parse all specs to find the object and link to it... OPTIMIZE ?? */
  def msg (ea:String) = RAction.async { implicit stok =>
      // todo use drafts??
    val d = catPages("Spec", stok.realm)
    val dom = d.flatMap(p =>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot

    val m = dom.moreElements.collectFirst {
      case m:EMsg if (m.ea == ea) => m
    }

    Future.successful(
      m.flatMap(_.specPos).orElse(m.flatMap(_.pos)).map {pos=>
          val href = if(pos.wpath.contains("Spec:")) {
            "/diesel/fiddle/playDom"+"?line="+pos.line+"&col="+pos.col+"&spec="+pos.wpath
          } else if(pos.wpath.contains("Story:")) {
            "/diesel/fiddle/playDom"+"?line="+pos.line+"&col="+pos.col+"&story="+pos.wpath
          } else {
            "/"
          }

        Redirect(href, SEE_OTHER)
//        ROK.k noLayout {implicit stok=>
//          Html(
//            s"""
//               |${m.toHtmlInPage}
//               |<script src="/public/assets/javascripts/weDieselDom.js")"></script>
//               |<script src="/public/assets/javascripts/weFiddles.js")"></script>
//               |
//               |""".stripMargin)
//        }
      }.getOrElse {
        Ok("Msg not found")
      }
    )
  }

}

object RazRequestUtils {
  /** request to string */
  def printRequest(implicit request: RequestHeader, rawBody: String = "") = {

    // make the diesel.rest message
    val qparams = DomEngineHelper.parmsFromRequestHeader(request)
    val hparams = DomEngineHelper.headers(request)

    val m = Map(
      "path" -> request.path,
      "verb" -> request.method,
      "queryString" -> URLDecoder.decode(request.rawQueryString),
      "headers" -> hparams,
      "queryParams" -> qparams
    )

    razie.js.tojsons(m) +
        s"\n***************** body (${Enc.niceNumber(rawBody.length)} length) *****************\n" +
        rawBody
  }

}

