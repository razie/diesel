/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin._
import com.mongodb.casbah.Imports._
import com.novus.salat._
import db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import db.{ROne, RazMongo}
import model.{CMDWID, WikiAudit, WikiEntryOld, _}
import play.api.mvc.{Action, AnyContent, Request}
import razie.{cout, Logging}

import scala.Array.canBuildFrom

/** reused in other controllers */
class WikiBase extends RazController with Logging with WikiAuthorization {
  /** yeah, I hate myself - happy? */
  var authImpl: WikiAuthorization = new NoWikiAuthorization

  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry] = None)(implicit errCollector: VErrors = IgnoreErrors): Boolean =
    authImpl.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] =
    authImpl.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VErrors): Option[Boolean] =
    authImpl.canEdit(wid, u, w, props)(errCollector)

}

/** visibility settings of topics */
object Visibility {
  final val PUBLIC = "Public"
  final val PRIVATE = "Private"
  final val CLUB = "Club"
  final val CLUB_ADMIN = "ClubAdmin"
}

/** wiki controller */
object Wiki extends WikiBase {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  /** make a relative href for the given tag. give more tags with 1/2/3 */
  def hrefTag(wid:WID, t:String,label:String) = {
    if(Array("Blog","Forum") contains wid.cat) {
      s"""<b><a href="${w(wid)}/tag/$t">$label</a></b>"""
    } else {
      if(wid.parentWid.isDefined) {
        s"""<b><a href="${w(wid.parentWid.get)}/tag/$t">$label</a></b>"""
      } else {
        s"""<b><a href="${routes.Wiki.showTag(t)}">$label</a></b>"""
      }
    }
  }

  /** show a tag */
  def showTag(tag: String, realm:String) = Action { implicit request=>
    // redirect all sites to RK for root tags
    if (Website.getHost.exists(_ != Config.hostport) && !Config.isLocalhost)
      Redirect("http://" + Config.hostport + "/wiki/tag/" + tag)
    else
      search ("", "", Enc.fromUrl(tag)).apply(request).value.get.get
  }

  //TODO optimize - index or whatever
  /** search all topics  provide either q or curTags */
  def search(q: String, scope:String, curTags:String="") = Action { implicit request =>
    //TODO limit the number of searches - is this performance critical?
    val qi = q.toLowerCase()
    val qt = curTags.split("/").filter(_ != "tag") //todo only filter if first is tag ?

    def filter (u:DBObject) = {
      if (q.length <= 0)
        qt.size > 0 && u.containsField("tags") && qt.foldLeft(true)((a, b) => a && u.get("tags").toString.toLowerCase.contains(b))
      else
        (q.length > 1 && u.get("name").asInstanceOf[String].toLowerCase.contains(qi)) ||
          (q.length > 1 && u.get("label").asInstanceOf[String].toLowerCase.contains(qi)) ||
          (q.length() > 3 && u.get("content").asInstanceOf[String].toLowerCase.contains(qi))
    }
    lazy val parent = WID.fromPath(scope).flatMap(x=>Wikis.find(x).orElse(Wikis.findAnyOne(x.name)))

    val wikis =
      if(scope.length > 0 && parent.isDefined) {
        val p = parent.get

        def src (t:MongoCollection) = {
          for (
            u <- t.find(Map("parent" -> p._id)) if filter(u)
          ) yield u
        }.toList

        if(WikiDomain.aEnds(p.category, "Child").contains("Item"))
          RazMongo.withDb(RazMongo("weItem").m) (src)
        else
          RazMongo.withDb(RazMongo("WikiEntry").m) (src)
      } else {
        RazMongo.withDb(RazMongo("WikiEntry").m) { t =>
          for (
            u <- t if filter(u)
          ) yield u
        }.toList
      }

    if(!isFromRobot) {
      if(q.length > 0) Audit.logdb("QUERY", q, s"Scope: $scope", "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
      else Audit.logdb("QUERY_TAG", curTags, "Scope: "+parent.map(_.wid.wpath).getOrElse(s"??? $scope"), "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
    }

    if (wikis.size == 1)
      Redirect(controllers.Wiki.w(WikiEntry.grated(wikis.head).wid))
    else {
      val wl = wikis.map(WikiEntry.grated _).take(500).toList
      val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").filter(x=> !qt.contains(x)).groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
      Ok(views.html.wiki.wikiList(
        q, q, curTags, wl.map(w => (w.wid, w.label)), tags,
        (if(q.length>1) "/wikie/search/tag/"
        else if(scope.length > 0) s"/wiki/$scope/tag/"
         else "/wiki/tag/"),
        (if(q.length>1) "?q="+q else ""))(auth,request)
      )
    }
  }

  private def topicred(wpath: String) = {
    if (Config.config(Config.TOPICRED).exists(_.contains(wpath))) {
      log("- redirecting " + wpath)
      Some(Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wpath)).get)))
    } else
      None
  }

  /**
   * show an older version of a page
   *  TODO this is authorized against the old permissions fro the version - should it be against the new perms?
   */
  def showWidVer(cw: CMDWID, ver: Int, realm:String="rk") = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- cw.wid;
      w <- ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      wikiPage(wid, Some(wid.name), Some(w.entry), false, false)(auth, request)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER")
    }
  }

  /**
   * show conetnt of current version
   *  TODO is this authorized?
   */
  def showWidContent(cw: CMDWID, realm:String="rk") = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- cw.wid;
      w <- wid.page;
      can <- canSee(wid, auth, wid.page.orElse(Some(w))) orCorr cNoPermission
    ) yield {
      Ok(w.content)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.CONTENT")
    }
  }

  /**
   * show conetnt of current version
   *  TODO is this authorized?
   */
  def showWidContentVer(cw: CMDWID, ver: Int, realm:String="rk") = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- cw.wid;
      w <- ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      Ok(w.entry.content)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER.CONTENT")
    }
  }

  def headWid(cw: CMDWID, realm:String) = showWid(cw, 0, realm)

  // show wid prefixed with parent
  def showWidUnder(parent:String, cw: CMDWID, count: Int, realm:String) = {
    showWid(CMDWID(cw.wpath.map(x=>parent+"/"+x), cw.wid.flatMap(x=>WID.fromPath(parent+"/"+x.wpath)), cw.cmd, cw.rest), count, realm)
  }

  /** show specific pages like about/tos/etc from a site or default to the RK pages from admin */
  def showSitePage(name: String, count:Int) = Action { implicit request =>
    Website.getHost.flatMap(x=>Website(x)).flatMap(web=>Wikis.find(WID("Page", name, Some(web.we._id)))).map {we=>
      show(we.wid, count).apply(request).value.get.get //todo already have the page - optimize
    } getOrElse {
      // normal - continue showing the page
      show(WID("Admin", name), count).apply(request).value.get.get
    }
  }

  /** show a page */
  def showWid(cw: CMDWID, count: Int, realm:String) = {
    if (cw.cmd == "xp") xp(cw.wid.get, cw.rest)
    else if (cw.cmd == "xpl") xpl(cw.wid.get, cw.rest)
    else if (cw.cmd == "tag") {
      search("", cw.wpath getOrElse "", cw.rest)
    }
    else Action { implicit request =>
      // must check if page is WITHIN site, otherwise redirect to main site
      val fhost = Website.getHost
      val redir = fhost flatMap (Config.urlfwd(_))
      val canon = fhost flatMap (fh=> Config.urlcanon(cw.wpath.get, None).map(_.startsWith("http://"+fh)))

      // if not me, no redirection and not the redirected path, THEN redirect
      if (fhost.exists(_ != Config.hostport) &&
        redir.isDefined &&
        !cw.wpath.get.startsWith(redir.get.replaceFirst(".*/wiki/", "")) &&
        !canon.exists(identity)) {
        log("  REDIRECTED FROM - " + fhost)
        log("    TO http://" + Config.hostport + "/wiki/" + cw.wpath.get)
        Redirect("http://" + Config.hostport + "/wiki/" + cw.wpath.get)
      } else fhost.flatMap(x=>Website(x)).map { web=>
        show(cw.wid.get, count).apply(request).value.get.get
      } getOrElse {
        // normal - continue showing the page
        show(cw.wid.get, count).apply(request).value.get.get
      }
    }
  }

  /** show a page */
  def printWid(cw: CMDWID, realm:String="rk") = show(cw.wid.get, 0, true)

  /** POST against a page - perhaps a trackback */
  def postWid(wp: String, realm:String) = Action { implicit request =>
    //    if (model.BannedIps isBanned request.headers.get("X-FORWARDED-HOST")) {
    //      admin.Audit.logdb("POST-BANNED", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //      Ok("")
    //    } else {
    //      admin.Audit.logdb("POST", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //    Services.audit.unauthorized(s"POST Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
    unauthorized("Oops - can't POST here", false)
    //    }
  }

  def show1(cat: String, name: String, realm:String) = show(WID(cat, name))
  def showId(id: String, realm:String) = Action { implicit request =>
    (for (w <- Wikis.findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(we: UWID):String = we.wid.map(wid=>w(wid)).getOrElse("ERR_NO_URL_FOR_"+we.toString)
  def w(we: WID, shouldCount: Boolean = true):String = Config.urlmap(we.urlRelative + (if (!shouldCount) "?count=0" else ""))

  def w(cat: String, name: String) =
  //    if("Blog" == cat) Config.urlmap("/blog/%s:%s".format(cat, name))
    Config.urlmap("/wiki/%s:%s".format(cat, name))
  def w(name: String) = Config.urlmap("/wiki/" + name)

  def call[A, B](value: A)(f: A => B) = f(value)

  /** serve a site */
  def site(name: String) = show(WID("Site", name))

  def wikieShow(iwid: WID, count: Int = 0) = show(iwid, count)

  def show(iwid: WID, count: Int = 1, print: Boolean = false): Action[AnyContent] = Action { implicit request =>
    implicit val errCollector = new VErrors()
    implicit val au = auth

    val shouldNotCount = request.flash.get("count").exists("0" == _) || (count == 0) ||
      isFromRobot(request) || au.exists("Razie" == _.userName)

    debug("show2 " + iwid.wpath)
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    // optimize - don't reload some crap already in the iwid
    val wid = if (cat == iwid.cat && name == iwid.name) iwid else WID(cat, name, iwid.parent)

    // so they are available to scripts
    razie.NoStaticS.put(model.QueryParms(request.queryString))

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("any" == cat || (cat.isEmpty && wid.parent.isEmpty)) {
      // search for any name only if cat is missing OR there is no parent

      // TODO optimize to load just the WID - i'm redirecting anyways
      val wl = Wikis.findAny(name).filter(page => canSee(page.wid, au, Some(page)).getOrElse(false)).toList
      if (wl.size == 1) {
        if (Array("Blog", "Post") contains wl.head.wid.cat) {
          // Blogs and other topics are allowed nicer URLs, without category
          // search engines don't like URLs with colons etc
          show(wl.head.wid, count, print).apply(request).value.get.get
        } else
        // redirect to use the proper Category display
          Redirect(controllers.Wiki.w(wl.head.wid))
      } else if (wl.size > 0) {
        val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
        Ok(views.html.wiki.wikiList("category any", "", "", wl.map(x => (x.wid, x.label)), tags))
      }
      else
        wikiPage(wid, Some(iwid.name), None, !shouldNotCount, au.isDefined && canEdit(wid, au, None).get)
    } else {
      // normal request with cat and name
      val w = wid.page

      if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(wid.wpath))) {
        log("- redirecting " + wid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wid.wpath)).get))
      } else if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(iwid.wpath))) {
        // specifically when rules changes- the reformated wid not longer working, try original
        log("- redirecting " + iwid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(iwid.wpath)).get))
      } else {
        // finally there!!
        //        cout << "1"
        if (!canSee(wid, au, w).getOrElse(false)) {
          if (isFromRobot(request)) {
            Audit.logdb("ROBOT", wid.wpath)
            noPerm(wid, "SHOW", false)
          } else
            noPerm(wid, "SHOW")
        } else
        //        cout << "2"
          w.map { w =>
            // redirect a simple alias with no other content
            w.alias.map { wid =>
              Redirect(controllers.Wiki.w(wid.formatted))
            } getOrElse
              wikiPage(wid, Some(iwid.name), Some(w), !shouldNotCount, au.isDefined && canEdit(wid, au, Some(w)), print)
          } getOrElse
            wikiPage(wid, Some(iwid.name), None, !shouldNotCount, au.isDefined && canEdit(wid, au, None), print)
      }
    }
  }

  private def wikiPage(wid: model.WID, iname: Option[String], page: Option[model.WikiEntry], shouldCount: Boolean, canEdit: Boolean, print: Boolean = false)(implicit au: Option[model.User], request:Request[_]) = {
    if (shouldCount) page.foreach { p =>
      Audit ! WikiAudit("SHOW", p.wid.wpath, au.map(_._id))
      Audit ! WikiCount(p._id)
    }

    page.map(_.preprocessed) // just make sure it's processed

    if (Array("Site", "Page").contains(wid.cat) && page.isDefined)
      Ok(views.html.wiki.wikiSite(wid, iname, page))
    //    Ok(views.html.wiki.wikiPage(wid, iname, page, canEdit, print))
    else if (page.exists(!_.fields.isEmpty)) {
      showForm(wid, iname, page, au, shouldCount, Map.empty, canEdit, print)
    } else
      Ok(views.html.wiki.wikiPage(wid, iname, page, canEdit, print))
  }

  def showForm(wid: model.WID, iname: Option[String], page: Option[model.WikiEntry], user: Option[model.User], shouldCount: Boolean, errors: Map[String, String], canEdit: Boolean, print: Boolean = false) = {
    // form design
    page.flatMap(_.section("section", "formData")).foreach { s =>
      // parse form data
      val data = razie.Snakk.jsonParsed(s.content)
      razie.MOLD(data.keys).map(_.toString).map { name =>
        val x = data.getString(name)
        //          cout << "FIELD " + name + "="+x
        page.get.fields.get(name).foreach(f => page.get.fields.put(f.name, f.withValue(x)))
        //          cout << "FIELDs " + page.get.fields.toString
      }
    }
    Ok(views.html.wiki.wikiForm(wid, iname, page, user, errors, canEdit, print))
  }

  def wikieDebug(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val cat = iwid.cat
    val name = Wikis.formatName(iwid)

    val wid = WID(cat, name)

    razie.NoStaticS.put(model.QueryParms(request.queryString))

    Wikis.find(cat, name) match {
      case x @ Some(w) if !canSee(wid, auth, x).getOrElse(false) => noPerm(wid, "DEBUG")
      case y @ _ => Ok(views.html.wiki.wikiDebug(wid, Some(iwid.name), y, auth))
    }
  }

  def all(cat: String, realm:String="rk") = Action { implicit request =>
    Ok(views.html.wiki.wikiAll(cat, auth))
  }

  import play.api.libs.json._

  def xpold(cat: String, name: String, c: String, path: String) = xp(WID(cat, name), path)

  def xp(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    (for (
      worig <- page orElse Wikis.find(wid);
      w <- worig.alias.flatMap(x => Wikis.find(x)).orElse(Some(worig)) // TODO cascading aliases?
    ) yield {
      val node = new WikiWrapper(wid)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      Audit.logdb("XP", wid.wpath + "/xp/" + path)

      val xpath = "*/" + path
      val res: List[String] =
        if (razie.GPath(xpath).isAttr) (root xpla xpath)
        else (root xpl xpath).collect {
          case we: WikiWrapper => we.wid.wpath
        }

      Ok(Json.toJson(res))
    }) getOrElse
      Ok("Nothing... for " + wid + " XP " + path)
  }

  def xpl(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    (for (
      worig <- page orElse Wikis.find(wid);
      w <- worig.alias.flatMap(x => Wikis.find(x)).orElse(Some(worig)) // TODO cascading aliases?
    ) yield {
      val node = new WikiWrapper(wid)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      Audit.logdb("XP-L", wid.wpath + "/xpl/" + path)

      val xpath = "*/" + path
      // TODO use label not name
      val res = (root xpl xpath).collect {
        case we: WikiWrapper => (we.wid, we.wid.name, we.w.toSeq.flatMap(_.tags))
      }

      val tags = res.flatMap(_._3).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
      Ok(views.html.wiki.wikiList(path, "", "", res.map(t=>(t._1, t._2)), tags)(auth, request))
    }) getOrElse
      Ok("Nothing... for " + wid + " XP " + path)
  }

  /** wid is the script name,his parent is the actual topic */
  def wikieApiCall(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      r1 <- (au.hasPerm(Perm.apiCall) || au.hasPerm(Perm.adminDb)) orErr ("no permission, eh? ");
      widp <- wid.parentWid;
      w <- Wikis.find(widp)
    ) yield {
      // default to category
      val res = try {
        val sec = wid.name
        val script = w.scripts.find(sec == _.name).orElse(model.Wikis.category(widp.cat) flatMap (_.scripts.find(sec == _.name)))
        val res: String = script.filter(_.checkSignature).map(s => {
          val up = Config.currUser
          model.WikiScripster.impl.runScript(s.content, Some(w), up, request.queryString.map(t => (t._1, t._2.mkString)))
        }) getOrElse ""
        Audit.logdb("SCRIPT_RESULT", res)
        res
      } catch { case _: Throwable => "?" }
      Ok(res)
    }) getOrElse unauthorized()
  }

  /** wid is the script name,his parent is the actual topic */
  def wikieNextStep(id: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
      // default to category
      Audit.logdb("WF_NEXT_STEP", id)
      act.WikiWf.event("WF_NEXT_STEP", Map("id" -> id))
      Ok("next step...")
    }) getOrElse unauthorized()
  }

  /** try to link to something - find it */
  def social(label:String, url:String) = Action { implicit request =>
    Ok(views.html.wiki.social(label, url, auth))
  }
}


