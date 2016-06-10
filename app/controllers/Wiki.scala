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
import mod.diesel.controllers.DieselControl
import model._
import play.twirl.api.Html
import razie.db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import razie.db.{ROne, RazMongo}
import play.api.mvc.{Action, AnyContent, Request}
import razie.diesel.RDOM
import razie.wiki.admin.Audit
import razie.wiki.util.{PlayTools, VErrors}
import razie.{cout, Logging}
import razie.wiki.model._
import scala.Array.canBuildFrom
import razie.wiki.{Services, Enc}
import razie.wiki.dom.WikiDomain
import razie.wiki.model.WikiAudit
import razie.wiki.util.IgnoreErrors

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import Visibility.PUBLIC

import scala.util.Try

/** reused in other controllers */
class WikiBase extends RazController with Logging with WikiAuthorization {
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry] = None)(implicit errCollector: VErrors = IgnoreErrors): Boolean =
    Services.wikiAuth.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canEdit(wid, u, w, props)(errCollector)

  val RK: String = Wikis.RK
  val UNKNOWN: String = "?"

  /** determine the realm for the current request
    *
    * known realms take precedence for /w/REALM/xxx
    *
    * todo isn't this just a redirect to Website.getRealm
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
          RkReactors.forHost(h).getOrElse(Reactors.RK)
        }
      }.getOrElse(Reactors.RK)
    } else irealm
  }

}

/** wiki controller */
object Wiki extends WikiBase {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  /** make a relative href for the given tag. give more tags with 1/2/3 */
  def hrefTag(curRealm:String, wid:WID, t:String,label:String) = {
    if(Array("Blog","Forum") contains wid.cat) {
      s"""<b><a href="${wr(wid, curRealm)}/tag/$t">$label</a></b>"""
    } else {
      if(wid.parentWid.isDefined) {
        s"""<b><a href="${wr(wid.parentWid.get, curRealm)}/tag/$t">$label</a></b>"""
      } else {
//        s"""<b><a href="${routes.Wiki.showTag(t, wid.getRealm)}">$label</a></b>"""
        s"""<b><a href="/wiki/tag/$t">$label</a></b>"""
      }
    }
  }

  /** show a global tag, no parent */
  def showTagOld(tag: String, irealm:String) = Action { implicit request=>
    Redirect(routes.Wiki.showTag(tag, irealm))
  }

  /** show a global tag, no parent */
  def showTag(tag: String, irealm:String) = Action.async { implicit request=>
    // todo don't knwo why i do this redir
    // it's meant to work for other sites without reactors, that have no local tags
    if (PlayTools.getHost.exists(_ != Config.hostport) && !Config.isLocalhost && getRealm(irealm) == Wikis.RK)
      Future.successful(Redirect("http://" + Config.hostport + "/wiki/tag/" + tag))
    else
      search (getRealm(irealm), "", "", Enc.fromUrl(tag)).apply(request)
  }

  /** content assist for [[ ]] topics - search all topics  provide either q or curTags */
  //TODO optimize - index or whatever
  //todo cnt should be a parameter - API
  def options(irealm:String, q: String, scope:String, curTags:String="", cnt:Int) = Action { implicit request =>
    val realm = getRealm(irealm)

    val index = Wikis(realm).index
    val c1 = index.getOptions(q, cnt)

    // todo stupid name to label - should use indexed labels ?? should I
    Ok("["+(c1).map(s=>s""" "${s.replaceAllLiterally("_", " ")}" """).mkString(",")+"]").as("text/json")
  }

  //TODO optimize - index or whatever
  /** search all topics  provide either q or curTags */
  def search(irealm:String, q: String, scope:String, curTags:String="") = Action { implicit request =>
    val realm = if("all" != irealm) getRealm(irealm) else irealm

    //TODO limit the number of searches - is this performance critical?

    val qi = if(q.length > 0 && q(0) == '-') q.substring(1).toLowerCase else q.toLowerCase
    val doesNotContain = q.length > 0 && q(0) == '-'

    def qnot(x:Boolean) = if(doesNotContain) !x else x

    //todo only filter if first is tag ?
    // array of array - first is AND second is OR
    val qt = curTags.split("/").filter(_ != "tag").map(_.split(","))

    //todo optimize: run query straight in the database ?
    def filter (u:DBObject) = {
      def uf(n:String) = if(u.containsField(n)) u.get(n).asInstanceOf[String] else ""
      def hasTags = {
        val utags = if(u.containsField("tags")) u.get("tags").toString.toLowerCase else ""
        qt.size > 0 &&
          u.containsField("tags") &&
          qt.foldLeft(true)((a, b) => a && (
            if(b(0).startsWith("-")) ! utags.contains(b(0).substring(1))
            else b.foldLeft(false)((a, b) => a || (utags.contains(b) ||
              b == "*" ||
              (b == "draft" && u.containsField("props") && u.getAs[DBObject]("props").exists(_.containsField("draft"))) ||
              (b == "public" && u.containsField("props") && u.getAs[DBObject]("props").exists(_.getAsOrElse[String]("visibility", PUBLIC) == PUBLIC)) ||
              u.get("category").toString.toLowerCase == b
              ))
            ))
      }
      if (qi.length <= 0) // just a tag search
        hasTags
      else
        qnot((qi.length > 1 && uf("name").toLowerCase.contains(qi)) ||
          (qi.length > 1 && uf("label").toLowerCase.contains(qi)) ||
          ((qi.length() > 3 || auth.exists(_.isAdmin)) && uf("content").toLowerCase.contains(qi))
          ) && hasTags
    }

    lazy val parent = WID.fromPath(scope).flatMap(x=>Wikis.find(x).orElse(Wikis(realm).findAnyOne(x.name)))

    val REALM = if("all" == realm && auth.exists(_.isAdmin)) Map.empty[String,String] else Map("realm"->realm)
    val wikis =
      if(scope.length > 0 && parent.isDefined) {
        val p = parent.get

        def src (t:MongoCollection) = {
          for (
            u <- t.find(REALM ++ Map("parent" -> p._id)) if filter(u)
          ) yield u
        }.toList

        if(WikiDomain(realm).zEnds(p.category, "Child").contains("Item"))
          RazMongo.withDb(RazMongo("weItem").m) (src)
        else
          RazMongo.withDb(RazMongo("WikiEntry").m) (src)
      } else {
        RazMongo.withDb(RazMongo("WikiEntry").m) { t =>
          for (
            u <- t.find(REALM) if filter(u)
          ) yield u
        }.toList
      }


    if(!isFromRobot) {
      if(qi.length > 0) Audit.logdb("QUERY", q, s"Realm: $realm, Scope: $scope", "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
//      else Audit.logdb("QUERY_TAG", curTags, s"Realm: $realm, Scope: "+parent.map(_.wid.wpath).getOrElse(s"??? $scope"), "Results: " + wikis.size, "User-Agent: "+request.headers.get("User-Agent").mkString)
    }

    if (wikis.size == 1)
      Redirect(controllers.Wiki.w(WikiEntry.grated(wikis.head).wid))
    else {
      val wl1 = wikis.map(WikiEntry.grated _).take(500)
      //todo optimize - split and sort up-front, not as a separate step
      val wl2 = wl1.partition(w=> qnot(w.name.toLowerCase.contains(qi) || w.label.toLowerCase.contains(qi)))
      val wl = if(qi.length > 0) wl2._1.sortBy(_.name.length) ::: wl2._2 else wl1
//      val wl = wl2._2 //if(q.length > 0) wl2._1 ::: wl2._2 else wl1

      // the list of tags, sorted by count of occurences
      val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").filter(x=> !qt.contains(x)).groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

      val result = { implicit stok:StateOk =>
        views.html.wiki.wikiList(
          q, q, curTags, wl.map(w => (w.wid, w.label)), tags,
          (if(q.length>1) "/wikie/search/tag/"
          else if(scope.length > 0) s"/wiki/$scope/tag/"
          else "/wiki/tag/"),
          (if(q.length>1) "?q="+q else ""), realm)
      }

      if(wl.nonEmpty) ROK(auth, request) reactorLayout12 result
      else ROK(auth, request) notFound result // Google likes 404 here or reports it as a "soft 404"
    }
  }

  /** prepare the wid to be fully defined with realm and whatnot */
  def prepWid (cw:CMDWID, irealm:String) (implicit request:Request[_]) =
    cw.wid.map(iwid => if(iwid.realm.isDefined) iwid else iwid.r(getRealm(irealm)))

  /**
   * show an older version of a page
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def showWidVer(cw: CMDWID, ver: Int, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- prepWid(cw, irealm);
      page <- wid.page orErr "page not found";
      w <- ROne[WikiEntryOld]("entry._id" -> page._id, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      wikiPage(wid, Some(wid.name), Some(w.entry), false, false)(auth, request)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER")
    }
  }


  /**
   * show conetnt of current version
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def showWidContent(cw: CMDWID, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- prepWid(cw, irealm);
      w <- wid.page;
      can <- canSee(wid, auth, Some(w)) orCorr cNoPermission
    ) yield {
        Ok(w.content)
      }) getOrElse {
      noPerm(cw.wid.get, "SHOW.CONTENT")
    }
  }

  /**
   * show conetnt of current version
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def showWidContentVer(cw: CMDWID, ver: Int, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- prepWid(cw, irealm);
      w <- ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      Ok(w.entry.content)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER.CONTENT")
    }
  }

  /**
   * show full entry as JSON
   */
  def showWidJson(cw: CMDWID, irealm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- prepWid(cw, irealm);
      w <- wid.page;
      can <- canSee(wid, auth, Some(w)) orCorr cNoPermission
    ) yield {
        Ok(w.grated.toString).as("application/json")
      }) getOrElse {
      noPerm(cw.wid.get, "SHOW.CONTENT")
    }
  }

  // todo optimize this - serve http HEAD
  def headWid(cw: CMDWID, irealm:String) = showWid(cw, 0, irealm)

  // show wid prefixed with parent
  def showWidUnder(parent:String, cw: CMDWID, count: Int, realm:String) = {
    showWid(CMDWID(cw.wpath.map(x=>parent+"/"+x), cw.wid.flatMap(x=>WID.fromPath(parent+"/"+x.wpath)), cw.cmd, cw.rest), count, realm)
  }

  /** show specific pages like about/tos/etc from a site or default to the RK pages from admin */
  def showSitePage(name: String, count:Int) = Action { implicit request =>
    Website.getHost.flatMap(x=>Website(x)).flatMap(web=>Wikis.find(WID("Page", name, web.we.map(_._id)))).map {we=>
      show(we.wid, count).apply(request).value.get.get //todo already have the page - optimize
    } getOrElse {
      // normal - continue showing the page
      show(WID("Admin", name), count).apply(request).value.get.get
    }
  }

  def showWidR(cw: CMDWID, count: Int, irealm:String) = {
      // if same realm and uses w/realm/wiki/wpath, just redirect, don't mess with google
      Action {implicit request=>
        if (irealm == getRealm()) {
          Redirect("/wiki/"+cw.wpath.mkString)
        }
        else
          showWid(cw, count, irealm).apply(request).value.get.get
      }
  }

  /** show a page */
  def showWid(cw: CMDWID, count: Int, irealm:String) = {
    cw.cmd match {
      case "xp"  => xp(cw.wid.get, irealm, cw.rest)
      case "xpl" => xpl(cw.wid.get, irealm, cw.rest)
      case "rss.xml" => rss(cw.wid.get, cw.rest)
      case "debug" | "usage" => Action.async { implicit request =>
        val realm = getRealm(irealm)
        val wid = cw.wid.get.r(realm)
        wikieDebug(wid, cw.cmd).apply(request)//.value.get.get
      }
      case "tag" => Action { implicit request =>
        // stupid path like /wiki//tag/x comes here...
        if(cw.wpath.isEmpty || cw.wpath.exists(_.isEmpty)) showTag(cw.rest, irealm).apply(request).value.get.get
        else search(getRealm(irealm), "", cw.wpath getOrElse "", cw.rest).apply(request).value.get.get
      }
      case _ => Action { implicit request =>
        val realm = getRealm(irealm)
        prepWid(cw, irealm).map {wid=>
        // must check if page is WITHIN site, otherwise redirect to main site
          val fhost = Website.getHost
          val redir = fhost flatMap Config.urlfwd
          val rewrite = fhost.flatMap(h=> Config.urlrewrite(h + request.path))
          val canon = fhost flatMap (fh=> Config.urlcanon(cw.wpath.get, None).map(_.startsWith("http://"+fh)))
          val newcw = if(wid.realm.isDefined || Wikis.RK == realm) cw else cw.copy(wid=cw.wid.map(_.copy(realm=Some(realm))))

          // removed topics redirected
          if (rewrite.isDefined) {
            log("  REWRITE: REDIRECTED FROM - " + fhost+request.path)
            log("    TO " + rewrite.get)
            Redirect(rewrite.get)
          } else if (fhost.exists(_ != Config.hostport) &&
            // if not me, no redirection and not the redirected path, THEN redirect
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
        }.getOrElse {
          NotFound ("WID not found")
        }
      }
    }
  }

  /** show a page */
  def printWid(cw: CMDWID, irealm:String) = Action { implicit request =>
    show(prepWid(cw, irealm).get, 0, true).apply(request).value.get.get
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
    (for (w <- Wikis(realm).findById(id)) yield Redirect(controllers.Wiki.wr(w.wid, realm))) getOrElse Msg2("Oops - id not found")
  }

  def contentById(cat:String, id: String, irealm:String) = Action { implicit request =>
    val realm = getRealm(irealm)
    (for (w <- Wikis(realm).findById(cat, id)) yield Ok(w.content)
      ) getOrElse NotFound("wiki not found")
  }

  def w(we: UWID, fromRealm:String):String = we.wid.map(wid=>wr(wid, fromRealm)).getOrElse("ERR_NO_URL_FOR_"+we.toString)

  /** @deprecated use the one with fromREalm */
  def w(we: UWID):String = we.wid.map(wid=>w(wid)).getOrElse("ERR_NO_URL_FOR_"+we.toString)

  /** @deprecated use the one with fromREalm */
  def w(we: WID, shouldCount: Boolean = true):String = Config.urlmap(we.urlRelative + (if (!shouldCount) "?count=0" else ""))
  def wr(wid: WID, fromRealm:String, shouldCount: Boolean = true):String =
    Config.urlmap(wid.urlRelative(fromRealm) + (if (!shouldCount) "?count=0" else ""))

  def w(cat: String, name: String, realm:String) =
      Config.urlmap(WID(cat, name).r(realm).urlRelative)

  /** @deprecated use the realm version */
  def w(name: String) = Config.urlmap(s"/wiki/$name") //todo remove

  def call[A, B](value: A)(f: A => B) = f(value)

  def wikieShow(iwid: WID, count: Int = 0, irealm:String=UNKNOWN) = Action { implicit request =>
    val wid = if(iwid.realm.isDefined) iwid else iwid.r(getRealm(irealm))
    show(wid, count).apply(request).value.get.get
  }

  def show(iwid: WID, count: Int = 1, print: Boolean = false): Action[AnyContent] = Action { implicit request =>
    implicit val errCollector = new VErrors()
    implicit val au = auth

    def canSeeMaybe(wid: WID, au: Option[WikiUser], w: Option[WikiEntry]) = {
      canSee(wid, au, w).getOrElse(false) || w.exists(_.contentProps.contains("publicAlternative"))
    }

    val shouldNotCount = request.flash.get("count").exists("0" == _) || (count == 0) ||
      isFromRobot(request) || au.exists("Razie" == _.userName) || au.exists("Ileana" == _.userName) || au.exists("Admin" == _.userName)

    debug("show2 " + iwid.wpath)
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    // optimize - don't reload some crap already in the iwid
    val wid = if (cat == iwid.cat && name == iwid.name) iwid else WID(cat, name, iwid.parent, iwid.section, iwid.realm)

    val realm = getRealm()
    // so they are available to scripts
    razie.NoStaticS.put(QueryParms(request.queryString))

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "Private_Messages" == name) Redirect("/doe/msg/PM")
    else if ("Reactor" == cat && iwid.name != Wikis.RK && !iwid.realm.exists(_ != Wikis.RK)) Redirect("/w/"+wid.name+"/wiki/"+wid.wpath)
    else if ("Category" == cat && !Wikis(iwid.getRealm).categories.exists(_.name == name))
      DieselControl.catBrowser(iwid.getRealm, name, "").apply(request).value.get.get
    else if ("any" == cat || (cat.isEmpty && wid.parent.isEmpty)) {
      // search for any name only if cat is missing OR there is no parent

      // TODO optimize to load just the WID - i'm redirecting anyways
      val wl = Wikis(wid.getRealm).findAny(name).filter(page => canSeeMaybe(page.wid, au, Some(page))).toList
      if (wl.size == 1) {
        if (Array("Blog", "Post", "Topic", "Page") contains wl.head.wid.cat) {
          // Blogs and other topics are allowed nicer URLs, without category
          // search engines don't like URLs with colons etc
          show(wl.head.wid, count, print).apply(request).value.get.get
        } else
        // redirect to use the proper Category display
          Redirect(controllers.Wiki.wr(wl.head.wid, realm))
      } else if (wl.nonEmpty) {
        val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
        ROK(auth, request) reactorLayout12 { implicit stok =>
          views.html.wiki.wikiList("category any", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", wid.getRealm)
        }
      }
      else {
        // last attempt: index contains lowercase name
        Wikis(wid.getRealm).index.getForLower(wid.name.toLowerCase).flatMap {newName=>
          log("- redirecting lower case: " + iwid.wpath)
          Wikis(wid.getRealm).index.getWids(newName).headOption.map { newWid =>
            Redirect(controllers.Wiki.wr(newWid, realm)) // perhaps different class name
          }
        } getOrElse
          wikiPage(wid, Some(iwid.name), None, !shouldNotCount, au.isDefined && canEdit(wid, au, None).exists(identity))
      }
    } else {
      // normal request with cat and name
      val w = wid.page

      if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(wid.wpath))) {
        log("- redirecting TOPICRED " + wid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wid.wpath)).get))
      } else if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(iwid.wpath))) {
        // specifically when rules changes- the reformated wid not longer working, try original
        log("- redirecting TOPICRED.iwid" + iwid.wpath)
        Redirect(controllers.Wiki.wr(WID.fromPath(Config.config(Config.TOPICRED).get.apply(iwid.wpath)).get, realm))
      } else if (!w.isDefined && Wikis(wid.getRealm).index.containsLower(wid.name.toLowerCase)) {
        val newName = Wikis(wid.getRealm).index.getForLower(wid.name.toLowerCase).get
        // it may be in a mixin realm
        Wikis(wid.getRealm).index.getWids(newName).headOption.map {newWid=>
          log(s"- redirecting lower case FOUND: ${iwid.wpath} -> ${newWid.wpath}")
          Redirect(controllers.Wiki.wr(newWid, realm)) // perhaps different class name
        } getOrElse {
          val newWid = wid.copy(name=newName)
          log(s"- redirecting lower case WHO?: ${iwid.wpath} -> ${newWid.wpath}")
          Redirect(controllers.Wiki.wr(newWid, realm)) // gave up - maybe... whatever
        }
      } else {
        // finally there!!
        //        cout << "1"
        if (!canSee(wid, au, w).getOrElse(false) && !w.exists(_.contentProps.contains("publicAlternative"))) {
          // HERE the user is not authorized to see - can we tease or not?
          val more = Website(request).flatMap(_.prop("msg.noPerm")).flatMap(WID.fromPath).flatMap(_.content).mkString
          val teaser = Website(request).flatMap(_.prop("msg.err.teaserCategories")).flatMap(_.split(",").find(_ == wid.cat)).flatMap(_ => w).map{
            page=> {
              def tags = page.tags.map(t=>s"""<a href="/wiki/tag/$t"><b>$t</b></a>""").mkString(" | ")
              s"""
                 |## ${page.getLabel} \n\n
                 |${page.getDescription} \n\n
                 |... this topic has <b>${page.wordCount}</b> words \n
                 |... tags: $tags \n
                 |""".stripMargin
            }
          }.mkString

//          if (isFromRobot(request)) Audit.logdb("ROBOT", wid.wpath)

          if(teaser.isEmpty)
            noPerm(wid, "SHOW " + more, !isFromRobot(request))
          else
            noPerm(wid, more, false, teaser)
        } else {
        //        cout << "2"
          // alternative public page
          if (!canSee(wid, au, w).getOrElse(false) && w.exists(_.contentProps.contains("publicAlternative")) && (WID fromPath w.get.contentProps("publicAlternative")).isDefined ) {
            val nwid = WID fromPath w.get.contentProps("publicAlternative")
            wikiPage(nwid.get, Some(iwid.name), nwid.flatMap(_.page), !shouldNotCount, au.isDefined && canEdit(wid, au, None), print)
          } else {
            w.map { w =>
              // redirect a simple alias with no other content
              w.alias.map { wid =>
                Redirect(controllers.Wiki.wr(wid.formatted, realm))
              } orElse w.redirect.map { url =>
                  Redirect(url)
              } getOrElse
                wikiPage(wid, Some(iwid.name), Some(w), !shouldNotCount, au.isDefined && canEdit(wid, au, Some(w)), print)
            } getOrElse
              wikiPage(wid, Some(iwid.name), None, !shouldNotCount, au.isDefined && canEdit(wid, au, None), print)
          }
        }
      }
    }
  }

  // this has been already authorized - will not check anymore
  private def wikiPage(wid: WID, iname: Option[String], page: Option[WikiEntry], shouldCount: Boolean, canEdit: Boolean, print: Boolean = false)(implicit au: Option[User], request:Request[_]) = {
    if (shouldCount) page.foreach { p =>
      Services ! WikiAudit("SHOW", p.wid.wpath, au.map(_._id))
      Services ! WikiCount(p._id)
    }

//    cdebug << "A"
    page.map(_.preprocess(au)) // just make sure it's processed
//    cdebug << "B"

    if (Array("Site", "xPage").contains(wid.cat) && page.isDefined)
      ROK(au, request) noLayout { implicit stok =>
        views.html.wiki.wikiSite(wid, iname, page)
      }
    //    Ok(views.html.wiki.wikiPage(wid, iname, page, canEdit, print))
    else if (page.exists(_.fields.nonEmpty)) {
      showForm(wid, iname, page, au, shouldCount, Map.empty, canEdit, print)
    } else {
//    cdebug << "C"
      ROK(au, request) noLayout { implicit stok =>
        views.html.wiki.wikiPage(wid, iname, page, canEdit, print)
      }
      }
  }

  def showForm(wid: WID, iname: Option[String], page: Option[WikiEntry], user: Option[User], shouldCount: Boolean, errors: Map[String, String], canEdit: Boolean, print: Boolean = false)(implicit au: Option[model.User], request:Request[_]) = {
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
    ROK(auth, request) noLayout { implicit stok =>
      views.html.wiki.wikiForm(wid, iname, page, user, errors, canEdit, print)
    }
  }

  def wikieUsage(iwid: WID) = wikieDebug (iwid, "usage")

  def wikieDebug(iwid: WID, what:String="debug") = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()

    val wid = iwid.formatted

    razie.NoStaticS.put(QueryParms(request.queryString))

    wid.page match {
      case x @ Some(w) if !canSee(wid, auth, x).getOrElse(false) => noPerm(wid, "DEBUG")
      case y @ Some(w) => if("usage" == what)
        ROK.s apply {implicit stok=> views.html.wiki.wikieUsage(w.wid, Some(iwid.name), y)}
        else
        ROK.s apply {implicit stok=> views.html.wiki.wikieDebug(w.wid, Some(iwid.name), y)}
      case None => Msg2 (s"${wid.wpath} not found")
    }
  }

  def all(cat: String, irealm:String) = Action { implicit request =>
    ROK.r noLayout {implicit stok=>views.html.wiki.wikiAll(getRealm(irealm), cat, auth)}
  }

  import play.api.libs.json._

  def xp(iwid: WID, irealm:String, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    val wid = iwid.r(getRealm(irealm))
    (for (
      worig <- xpRoot(wid, page);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) orErr "no page" // TODO cascading aliases?
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
      Unauthorized("Nothing... for " + wid + " XP " + path+" ERR: "+errCollector.mkString)
  }

  private def xpRoot (wid:WID, page:Option[WikiEntry])(implicit request:Request[_]) = {
    def wcat = WID("Category", "Category").r(getRealm())

    page orElse (
      if ("*" == wid.name) wcat.page else None
      ) orElse Wikis(wid.getRealm).find(wid);
  }

  def xpl(iwid: WID, irealm:String, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    val wid = iwid.r(getRealm(irealm))
    (for (
      worig <- xpRoot(wid, page);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) orErr "no page" // TODO cascading aliases?
    ) yield {
        val root = new razie.Snakk.Wrapper(new WikiWrapper(w.wid), WikiXpSolver)

        Audit.logdb("XP-L", wid.wpath + "/xpl/" + path)

        val xpath = "*/" + path
        // TODO use label not name
        val res = (root xpl xpath).collect {
          case we: WikiWrapper => (we.wid, we.wid.name, we.w.toSeq.flatMap(_.tags))
        }

        val tags = res.flatMap(_._3).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
        ROK(auth, request) reactorLayout12 { implicit stok =>
          views.html.wiki.wikiList(path, "", "", res.map(t => (t._1, t._2)), tags, "./", "", wid.getRealm)
        }
      }) getOrElse
      Unauthorized("Nothing... for " + wid + " XP " + path+" ERR: "+errCollector.mkString)
  }

  def listCat(irealm:String, cat:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val realm = getRealm(irealm)
      // TODO use label not name
      val res =
        (
          if(cat.isEmpty) Wikis(realm).categories.toList
          else Wikis(realm).pages(cat).toList
        ).collect {
          case we: WikiEntry => (we.wid, we.wid.name, we.tags)
        }

      val tags = res.flatMap(_._3).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
      ROK(auth, request) apply { implicit stok =>
        views.html.wiki.wikiList("", "", "", res.map(t => (t._1, t._2)), tags, "./", "", realm)
      }
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
        val res: String = script.filter(_.checkSignature(Some(au))).map(s => {
          model.WikiScripster.impl.runScript(s.content, "js", Some(w), Some(au), request.queryString.map(t => (t._1, t._2.mkString)))
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
  def wikiBrowse(wpath:String, ipath:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    val realm = getRealm()
    (for (
      we <- WID.fromPath(wpath).flatMap(Wikis(realm).find) orErr "cannot find page"
    ) yield {
      val ilinks = we.ilinks

      //todo page.get null
      val all  = we.ilinks.distinct.collect {
        case link if link.wid.page.isDefined =>
          (RDOM.A(we.getLabel, we.name, link.wid.name, "me", link.role.mkString), link.wid.page.get.tags.mkString)
      }

      val left   = all.filter(x=>x._2.contains("wiki") && !x._2.contains("talk")).map(_._1)
      val right  = all.filter(_._2.contains("drill")).map(_._1)
      val middle = all.filter(x=> (!x._2.contains("wiki") || x._2.contains("talk")) && !x._2.contains("drill")).map(_._1)

      val path = if(ipath == "/") ipath+wpath else ipath

      def mkLink (s:String) = routes.Wiki.wikiBrowse (s, path+"/"+s).toString()

      ROK(auth, request) apply {implicit stok=>
        views.html.modules.diesel.wikiBrowser(realm, Some(we), middle, left, right)(mkLink)
      }
    }) getOrElse unauthorized()
  }

  /** try to link to something - find it */
  def social(label:String, url:String) = Action { implicit request =>
    ROK(auth, request) { implicit stok =>
      (views.html.wiki.social(label, url))
    }
  }

  /** build a feed for the respective blog/forum */
  def rss(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    val realm = wid.realm.getOrElse(getRealm())
    (for (
      worig <- page orElse Wikis(realm).find(wid) orErr "page not found";
      w <- worig.alias.flatMap(x => Wikis(realm).find(x)).orElse(Some(worig)) orErr "alias not found";
      can <- canSee(w.wid, auth, Some(w)) orErr "can't see"// TODO cascading aliases?
    ) yield {
        if(!isFromRobot) Audit.logdb("RSS", wid.wpath + "/rss/ " + " from: "+PlayTools.getHost)

        Ok(views.xml.wiki.wikiRss(w, Wikis.linksTo(w.uwid).map(_.from).toList))
      }) getOrElse
      Ok("No feed found for " + wid + " TAGS " + path + "\n" + errCollector.mkString)
  }

  def tagOptions(irealm:String) = Action { implicit request =>
    val realm = if (UNKNOWN == irealm) getRealm(irealm) else irealm

    val tags = Wikis(realm).index.usedTags.keys

    Ok("["+(tags).map(s=>s""" "$s" """).mkString(",")+"]").as("text/json")
  }

  def wikieReport(what:String) = FAU { implicit au => implicit errCollector => implicit request =>
    ROK(Some(au), request) apply {implicit stok=>
      what match {
        case "views" =>
          views.html.wiki.reportViews()
        case _ =>
          views.html.wiki.reportViews()
      }
    }
  }

  //todo be a separate mod
  def wikiPropFeedRss(xurl:String) = Action { implicit request =>
    import razie.Snakk._
    val realUrl  = razie.wiki.Enc.fromUrl(xurl)
    val rss  = url (realUrl)
    val res = Try {
      (for (xn <- xml(scala.xml.XML.loadString(body(rss))) \ "channel" \ "item") yield {
        val n = xml(xn)
        // insulate external strings - sometimes the handling of underscore is bad
        // replace urls with MD markup:
        def ext(s: String) = s //.replaceAll("""(\s|^)(https?://[^\s]+)""", "$1[$2]")

        val link = ext(n \@ "link")
        val title = ext(n \@ "title")
        val desc = ext(n \@ "description")
        s"""<h3><a href="$link">$title</a></h3><pre>$desc</pre> """
      }).mkString
    }.recover{
      case e:Throwable => s"Error reading RSS from $realUrl: ${e.getClass.getSimpleName} : ${e.getLocalizedMessage}"
    }.get

    ROK(None, request) noLayout {implicit stok=>
        Html(res)
    }
  }

}

/** wiki controller */
object WikiApiv1 extends WikiBase {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  import razie.js

  /** prepare the wid to be fully defined with realm and whatnot */
  def prepWid (cw:CMDWID, irealm:String)(f: WID=>play.api.mvc.Result) (implicit request:Request[_]) =
    cw.wid.map(iwid => if(iwid.realm.isDefined) iwid else iwid.r(getRealm(irealm))) map (f) getOrElse(BadRequest("Bad WID: "+cw.toString))

  /** prepare the wid to be fully defined with realm and whatnot */
  def page (wid:WID)(f: WikiEntry=>play.api.mvc.Result) (implicit request:Request[_]) =
    wid.page map (f) getOrElse(NotFound("No page: "+wid.wpath))

  /** POST new content to preview an edited wiki */
  def preview(cw: CMDWID, irealm:String) = FAU { implicit au => implicit errCollector => implicit request =>
    prepWid(cw, irealm) { wid =>
      val data = PlayTools.postData
      val content = data("content")
      val page = WikiEntry(wid.cat, wid.name, wid.name, "md", content, au._id)

      // todo should I authorize this?
      ROK.s noLayout { implicit stok => views.html.wiki.wikiFrag(wid, Some(au), true, Some(page)) }
    }
  }

  /**
   * show full entry as html
   */
  def html(cw: CMDWID, irealm:String) = Action { implicit request => implicit val errCollector = IgnoreErrors
    prepWid(cw, irealm) {wid=>
      page(wid) {w=>
        if(canSee(wid, auth, Some(w)))
         ROK(auth, request) noLayout { implicit stok =>
           views.html.wiki.wikiFrag(w.wid, stok.au, true, Some(w))
         }
        else
          Unauthorized(s"Can't see wpath ${wid.wpath}")
      }
    }
  }

  /**
   * show full entry as JSON
   */
  def entry(cw: CMDWID, irealm:String) = Action { implicit request =>
    implicit val errCollector = IgnoreErrors
    prepWid(cw, irealm) {wid=>
      page(wid) {w=>
        if(canSee(wid, auth, Some(w)))
          Ok(filterJson(w.grated).toString).as("application/json")
        else
          Unauthorized(s"Can't see wpath ${wid.wpath}")
      }
    }
  }

  val fields = "category name label markup content tags realm ver props crDtm updDtm _id".split(" ")
  private def filterJson(o:DBObject) : DBObject = {
    o.filter(t=> fields contains t._1)
  }

  /**
   * show full entry as JSON
   */
  def entryId(id: String, irealm:String) = Action { implicit request =>
    implicit val errCollector = IgnoreErrors
    Wikis(getRealm(irealm)).findById(id) map {w=>
      if(canSee(w.wid, auth, Some(w)))
        Ok(filterJson(w.grated).toString).as("application/json")
      else
        Unauthorized(s"Can't see id $id")
      } getOrElse
    NotFound(s"Entry not found: id $id")
  }

  /**
   * show an older version of a page
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def entryVer(cw: CMDWID, ver: Int, irealm:String) = Action { implicit request =>
    implicit val errCollector = IgnoreErrors
    prepWid(cw, irealm) {wid=>
      page(wid) { w =>
        ROne[WikiEntryOld]("entry._id" -> w._id, "entry.ver" -> ver) map { weo =>
          if (canSee(wid, auth, Some(w)))
//            can <- canSee(wid, auth, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
            Ok(filterJson(weo.entry.grated).toString)
          else
            Unauthorized(s"Can't see wpath ${wid.wpath}")
        } getOrElse {
          NotFound(s"Version not found: $ver wpath ${wid.wpath}")
        }
      }
    }
  }

  /**
   * show conetnt of current version
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def content(cw: CMDWID, irealm:String) = Action { implicit request =>
    implicit val errCollector = IgnoreErrors
    prepWid(cw, irealm) {wid=>
      page(wid) {w=>
        if(canSee(wid, auth, Some(w)))
          Ok(w.content)
        else
          Unauthorized(s"Can't see wpath ${wid.wpath}")
      }
    }
  }
}

