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
import model._
import razie.db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import razie.db.{ROne, RazMongo}
import play.api.mvc.{Action, AnyContent, Request}
import razie.wiki.admin.Audit
import razie.wiki.util.{PlayTools, VErrors}
import razie.{cout, Logging}
import razie.wiki.model._
import scala.Array.canBuildFrom
import razie.wiki.Enc
import razie.wiki.dom.WikiDomain
import razie.wiki.model.WikiAudit
import razie.wiki.util.IgnoreErrors

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

  val RK: String = Wikis.RK
  val UNKNOWN: String = "?"

  /** determine the realm for the current request
    *
    * known realms take precedence for /w/REALM/xxx
    *
    * @param irealm - the realm hint from request, if any
    * @param request
    * @return
    */
  def getRealm (irealm:String = UNKNOWN) (implicit request : Request[_]) = {
    if(UNKNOWN == irealm) {
      val host = PlayTools.getHost
      host.flatMap(x=>Website(x)).map(_.reactor).orElse {
        host.map {h=>
          // auto-websites of type REACTOR.coolscala.com
          RkReactors.forHost(h).getOrElse(Reactors.DFLT)
        }
      }.getOrElse(Reactors.DFLT)
    } else irealm
  }

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
//        s"""<b><a href="${routes.Wiki.showTag(t, wid.getRealm)}">$label</a></b>"""
        s"""<b><a href="/wiki/tag/$t">$label</a></b>"""
      }
    }
  }

  /** show a tag */
  def showTag(tag: String, irealm:String) = Action { implicit request=>
    // redirect all sites to RK for root tags
    if (PlayTools.getHost.exists(_ != Config.hostport) && !Config.isLocalhost)
      Redirect("http://" + Config.hostport + "/wiki/tag/" + tag)
    else
      search (getRealm(irealm), "", "", Enc.fromUrl(tag)).apply(request).value.get.get
  }

  //TODO optimize - index or whatever
  /** search all topics  provide either q or curTags */
  def options(irealm:String, q: String, scope:String, curTags:String="") = Action { implicit request =>
    val realm = if (UNKNOWN == irealm) getRealm(irealm) else irealm

    val index = Wikis(realm).index
    val c1 = index.getOptions(q)

    Ok("["+(c1).map(s=>s""" "${s.replaceAllLiterally("_", " ")}" """).mkString(",")+"]").as("text/json")
  }

  //TODO optimize - index or whatever
  /** search all topics  provide either q or curTags */
  def search(irealm:String, q: String, scope:String, curTags:String="") = Action { implicit request =>
    val realm = if(UNKNOWN == irealm) getRealm(irealm) else irealm

    //TODO limit the number of searches - is this performance critical?

    val qi = q.toLowerCase()
    val qt = curTags.split("/").filter(_ != "tag") //todo only filter if first is tag ?

    def filter (u:DBObject) = {
      def uf(n:String) = if(u.containsField(n)) u.get(n).asInstanceOf[String] else ""
      if (q.length <= 0)
        qt.size > 0 && u.containsField("tags") && qt.foldLeft(true)((a, b) => a && u.get("tags").toString.toLowerCase.contains(b))
      else
        (q.length > 1 && uf("name").toLowerCase.contains(qi)) ||
          (q.length > 1 && uf("label").toLowerCase.contains(qi)) ||
          (q.length() > 3 && uf("content").toLowerCase.contains(qi))
    }
    lazy val parent = WID.fromPath(scope).flatMap(x=>Wikis.find(x).orElse(Wikis(realm).findAnyOne(x.name)))

    val wikis =
      if(scope.length > 0 && parent.isDefined) {
        val p = parent.get

        def src (t:MongoCollection) = {
          for (
            u <- t.find(Map("realm"->realm, "parent" -> p._id)) if filter(u)
          ) yield u
        }.toList

        if(WikiDomain(realm).aEnds(p.category, "Child").contains("Item"))
          RazMongo.withDb(RazMongo("weItem").m) (src)
        else
          RazMongo.withDb(RazMongo("WikiEntry").m) (src)
      } else {
        RazMongo.withDb(RazMongo("WikiEntry").m) { t =>
          for (
            u <- t.find(Map("realm"->realm)) if filter(u)
          ) yield u
        }.toList
      }

    if(!isFromRobot) {
      if(q.length > 0) Audit.logdb("QUERY", q, s"Realm: $realm, Scope: $scope", "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
      else Audit.logdb("QUERY_TAG", curTags, s"Realm: $realm, Scope: "+parent.map(_.wid.wpath).getOrElse(s"??? $scope"), "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
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
        (if(q.length>1) "?q="+q else ""), realm)(auth,request)
      )
    }
  }

//  private def topicred(wpath: String) = {
//    if (Config.config(Config.TOPICRED).exists(_.contains(wpath))) {
//      log("- redirecting " + wpath)
//      Some(Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wpath)).get)))
//    } else
//      None
//  }


  /**
   * show an older version of a page
   *  TODO this is authorized against the old permissions fro the version - should it be against the new perms?
   */
  def showWidVer(cw: CMDWID, ver: Int, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- cw.wid.map(_.r(getRealm(irealm)));
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
  def showWidContent(cw: CMDWID, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      iwid <- cw.wid;
      wid <- if(iwid.realm.isDefined) Some(iwid) else Some(iwid.r(getRealm(irealm)));
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
  def showWidContentVer(cw: CMDWID, ver: Int, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      iwid <- cw.wid;
      wid <- if(iwid.realm.isDefined) Some(iwid) else Some(iwid.r(getRealm(irealm)));
      w <- ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      Ok(w.entry.content)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER.CONTENT")
    }
  }

  def headWid(cw: CMDWID, irealm:String) = showWid(cw, 0, irealm)

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
  def showWid(cw: CMDWID, count: Int, irealm:String) = {
    cw.cmd match {
      case "xp"  => xp(cw.wid.get, cw.rest)
      case "xpl" => xpl(cw.wid.get, cw.rest)
      case "rss.xml" => rss(cw.wid.get, cw.rest)
      case "debug" => Action { implicit request =>
        val realm = getRealm(irealm)
        val wid = cw.wid.get.r(realm)
        wikieDebug(wid, realm).apply(request).value.get.get
      }
      case "tag" => Action { implicit request =>
        // stupid path like /wiki//tag/x comes here...
        if(cw.wpath.isEmpty || cw.wpath.exists(_.isEmpty)) showTag(cw.rest, irealm).apply(request).value.get.get
        else search(getRealm(irealm), "", cw.wpath getOrElse "", cw.rest).apply(request).value.get.get
      }
      case _ => Action { implicit request =>
        val realm = getRealm(irealm)
        val wid = cw.wid.get.r(realm)
        // must check if page is WITHIN site, otherwise redirect to main site
        val fhost = Website.getHost
        val redir = fhost flatMap Config.urlfwd
        val canon = fhost flatMap (fh=> Config.urlcanon(cw.wpath.get, None).map(_.startsWith("http://"+fh)))
        val newcw = if(wid.realm.isDefined || Wikis.RK == realm) cw else cw.copy(wid=cw.wid.map(_.copy(realm=Some(realm))))

        // if not me, no redirection and not the redirected path, THEN redirect
        if (fhost.exists(_ != Config.hostport) &&
          redir.isDefined &&
          !cw.wpath.get.startsWith(redir.get.replaceFirst(".*/wiki/", "")) &&
          !canon.exists(identity)) {
          log("  REDIRECTED FROM - " + fhost)
          log("    TO http://" + Config.hostport + "/wiki/" + cw.wpath.get)
//          Redirect("http://" + Config.hostport + "/wiki/" + cw.wpath.get)
          Redirect(newcw.wid.get.url)
        } else fhost.flatMap(x=>Website(x)).map { web=>
          show(wid, count).apply(request).value.get.get //todo what the heck is this?
        } getOrElse {
          // normal - continue showing the page
          show(wid, count).apply(request).value.get.get
        }
      }
    }
  }

  /** show a page */
  def printWid(cw: CMDWID, irealm:String) = Action { implicit request =>
    show(cw.wid.get.r(getRealm(irealm)), 0, true).apply(request).value.get.get
  }

  /** POST against a page - perhaps a trackback */
  def postWid(wp: String, irealm:String) = Action { implicit request =>
    //    if (model.BannedIps isBanned request.headers.get("X-FORWARDED-HOST")) {
    //      admin.Audit.logdb("POST-BANNED", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //      Ok("")
    //    } else {
    //      admin.Audit.logdb("POST", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //    Services.audit.unauthorized(s"POST Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
    unauthorized("Oops - can't POST here", false)
    //    }
  }

  def show1(cat: String, name: String, irealm:String) = Action { implicit request =>
    show(WID(cat, name).r(getRealm(irealm))).apply(request).value.get.get
  }
  def showId(id: String, irealm:String) = Action { implicit request =>
    val realm = getRealm(irealm)
    (for (w <- Wikis(realm).findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(we: UWID):String = we.wid.map(wid=>w(wid)).getOrElse("ERR_NO_URL_FOR_"+we.toString)
  def w(we: WID, shouldCount: Boolean = true):String = Config.urlmap(we.urlRelative + (if (!shouldCount) "?count=0" else ""))

  /** @deprecated use the realm version */
  def w(cat: String, name: String) =
    Config.urlmap(WID(cat, name).urlRelative)
  def w(cat: String, name: String, realm:String) =
      Config.urlmap(WID(cat, name).r(realm).urlRelative)

  /** @deprecated use the realm version */
  def w(name: String) = Config.urlmap(s"/wiki/$name") //todo remove

  def call[A, B](value: A)(f: A => B) = f(value)

  /** serve a site */
  def site(name: String) = show(WID("Site", name))

  def wikieShow(iwid: WID, count: Int = 0, irealm:String=UNKNOWN) =
    Action { implicit request =>
      show(iwid.r(getRealm(irealm)), count).apply(request).value.get.get
  }

  def show(iwid: WID, count: Int = 1, print: Boolean = false): Action[AnyContent] = Action { implicit request =>
    implicit val errCollector = new VErrors()
    implicit val au = auth

    val shouldNotCount = request.flash.get("count").exists("0" == _) || (count == 0) ||
      isFromRobot(request) || au.exists("Razie" == _.userName)

    debug("show2 " + iwid.wpath)
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    // optimize - don't reload some crap already in the iwid
    val wid = if (cat == iwid.cat && name == iwid.name) iwid else WID(cat, name, iwid.parent, iwid.section, iwid.realm)

    // so they are available to scripts
    razie.NoStaticS.put(QueryParms(request.queryString))

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("Reactor" == cat && !iwid.realm.exists(_ != Wikis.RK)) Redirect("/w/"+wid.name+"/wiki/"+wid.wpath)
    else if ("any" == cat || (cat.isEmpty && wid.parent.isEmpty)) {
      // search for any name only if cat is missing OR there is no parent

      // TODO optimize to load just the WID - i'm redirecting anyways
      val wl = Wikis(wid.getRealm).findAny(name).filter(page => canSee(page.wid, au, Some(page)).getOrElse(false)).toList
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
        Ok(views.html.wiki.wikiList("category any", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", wid.getRealm))
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

  private def wikiPage(wid: WID, iname: Option[String], page: Option[WikiEntry], shouldCount: Boolean, canEdit: Boolean, print: Boolean = false)(implicit au: Option[model.User], request:Request[_]) = {
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

  def showForm(wid: WID, iname: Option[String], page: Option[WikiEntry], user: Option[model.User], shouldCount: Boolean, errors: Map[String, String], canEdit: Boolean, print: Boolean = false) = {
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

  def wikieDebug(iwid: WID, realm:String) = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()

    val wid = iwid.formatted

    razie.NoStaticS.put(QueryParms(request.queryString))

    Wikis(wid.getRealm).find(wid) match {
      case x @ Some(w) if !canSee(wid, auth, x).getOrElse(false) => noPerm(wid, "DEBUG")
      case y @ Some(w) => Ok(views.html.wiki.wikieDebug(wid, Some(iwid.name), y, realm, auth))
      case None => Msg2 (s"${wid.wpath} not found")
    }
  }

  def all(cat: String, irealm:String) = Action { implicit request =>
    Ok(views.html.wiki.wikiAll(getRealm(irealm), cat, auth))
  }

  import play.api.libs.json._

  def xpold(cat: String, name: String, c: String, path: String) = xp(WID(cat, name), path)

  def xp(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    (for (
      worig <- page orElse Wikis(wid.getRealm).find(wid);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) // TODO cascading aliases?
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
      worig <- page orElse Wikis(wid.getRealm).find(wid);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) // TODO cascading aliases?
    ) yield {
      val root = new razie.Snakk.Wrapper(new WikiWrapper(w.wid), WikiXpSolver)

      Audit.logdb("XP-L", wid.wpath + "/xpl/" + path)

      val xpath = "*/" + path
      // TODO use label not name
      val res = (root xpl xpath).collect {
        case we: WikiWrapper => (we.wid, we.wid.name, we.w.toSeq.flatMap(_.tags))
      }

      val tags = res.flatMap(_._3).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
      Ok(views.html.wiki.wikiList(path, "", "", res.map(t=>(t._1, t._2)), tags, "./", "", wid.getRealm)(auth, request))
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
      w <- Wikis(wid.getRealm).find(widp)
    ) yield {
      // default to category
      val res = try {
        val sec = wid.name
        val script = w.scripts.find(sec == _.name).orElse(Wikis.category(widp.cat) flatMap (_.scripts.find(sec == _.name)))
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

  /** build a feed for the respective blog/forum */
  def rss(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      worig <- page orElse Wikis(wid.getRealm).find(wid);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig));
      can <- canSee(w.wid, auth, Some(w))// TODO cascading aliases?
    ) yield {

      if(!isFromRobot) Audit.logdb("RSS", wid.wpath + "/rss/ " + " from: "+PlayTools.getHost)

      Ok(views.xml.wiki.wikiRss(w, Wikis.linksTo(w.uwid).map(_.from).toList))
    }) getOrElse
      Ok("No feed found for " + wid + " TAGS " + path + "\n" + errCollector.mkString)
  }

}


