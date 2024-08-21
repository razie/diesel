/*
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.google.inject.{Inject, Singleton}
import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import salat._
import mod.diesel.controllers.DomainController
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, text}
import play.api.mvc.{Action, AnyContent, Cookie, Request}
import play.twirl.api.Html
import razie.audit.Audit
import razie.db.{ROne, RazMongo, Txn}
import razie.db.RazSalatContext._
import razie.hosting.{Website, WikiReactors}
import razie.tconf.Visibility.PUBLIC
import razie.wiki.model.features.WikiCount
import razie.wiki.model._
import razie.wiki.util.{PlayTools, QueryParms, Staged}
import razie.wiki.{Config, Enc, Services}
import razie.{Logging, js}
import scala.Array.canBuildFrom
import scala.concurrent.Future
import scala.util.Try

/** common wiki utils - reused in other controllers */
class WikiBase extends RazController with Logging with WikiAuthorization {
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility",
                we: Option[WikiEntry] = None)(implicit errCollector: VErrors = IgnoreErrors): Boolean =
    Services.wikiAuth.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)
             (implicit errCollector: VErrors): Option[Boolean] =
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
    * @return
    */
  def getRealm(irealm: String = UNKNOWN)(implicit request: Request[_]) = {
    // todo I think this function is obsoleted - reactors add themselves in Websites...?
    if (UNKNOWN == irealm || "" == irealm.trim) {
      PlayTools.getHost.flatMap(x => Website.forHost(x)).map(_.reactor).getOrElse(WikiReactors.RK)
    } else irealm
  }

  import collection.JavaConverters._

  def showForm(wid: WID, iname: Option[String], page: Option[WikiEntry], user: Option[User], shouldCount: Boolean,
               errors: Map[String, String], canEdit: Boolean, print: Boolean = false)(implicit stok: RazRequest) = {
    // form design
    page.flatMap(_.section("section", "formData")).foreach { s =>
      // parse form data
      val data = razie.Snakk.jsonParsed(s.content)
      data.keys.asScala.map(_.toString).map { name =>
        val x = data.getString(name)
        //          cout << "FIELD " + name + "="+x
        page.get.fields.get(name).foreach(f => page.get.fields.put(f.name, f.withValue(x)))
        //          cout << "FIELDs " + page.get.fields.toString
      }
    }

    ROK.k noLayout { implicit stok =>
      views.html.wiki.wikiForm(wid, iname, page, user, errors, canEdit, print)
    }
  }

}

/** wiki controller - generally view/read ops. The edit ops are in Wikie */
@Singleton
class Wiki @Inject()(domainController: DomainController) extends WikiBase {

  import WikiUtil._

  implicit def obtob(o: Option[Boolean]): Boolean = o.contains(true)

  /** show a global tag, no parent */
  def showTag(tag: String, irealm: String) = Action.async { implicit request =>
    search(getRealm(irealm), "", "", Enc.fromUrl(tag)).apply(request)
  }

  /** content assist for [[ ]] topics - search all topics  provide either q or curTags
    *
    * cat can be a comma-sep-list
    */
  //TODO optimize - index or whatever
  //todo cnt should be a parameter - API
  def wikieOptions(irealm:String, cat:String, q: String, scope:String, curTags:String="", cnt:Int) = Action { implicit request =>
    val realm = getRealm(irealm)

    val index = Wikis(realm).index
    val c1 = index.getOptions(cat, q, cnt)

    // todo stupid name to label - should use indexed labels ?? should I
    Ok("["+(c1).map(s=>s""" "${s.replaceAllLiterally("_", " ")}" """).mkString(",")+"]").as("text/json")
  }

  /** max size returned from searches */
  def MAX_LIST = Services.config.prop("wiki.search.max", "1000").toInt

  //TODO optimize - index or whatever
  /** search all topics - provide either q or curTags
    *
    * @param iq initial query / search string
    */
  def search(irealm:String, iq: String, scope:String, curTags:String="") = Action { implicit request =>
    var q = iq
    var allRealms = "all" == irealm // changed below

    // if the search start with a realm like ski:something then ignore the irealm
    // todo should check permission or something?
    val cidx = iq.indexOf(':')
    val realm = if (cidx > 0) {

      val r = q.substring(0, cidx)
      q = if (cidx < iq.length - 1) iq.substring(cidx + 1, q.length) else ""

      allRealms = "all" == r

      val res = if (!allRealms || !auth.exists(_.isAdmin)) getRealm(r) else r
      if (res == Wikis.RK) getRealm(irealm) else res
    } else {
      if ("all" != irealm || !auth.exists(_.isAdmin)) getRealm(irealm) else irealm
    }

    val qi = if (q.length > 0 && q(0) == '-') q.substring(1).toLowerCase else q.toLowerCase
    val qt = curTags.split("/").filter(_ != "tag").map(_.split(","))

    def isTagOnly = qi.length == 0 && qt.size > 0

    val wl: List[WikiEntry] =
      if (
        (qi.length() > 3 || isTagOnly) &&
            (qt.size <= 6 || qt.size <= 3 && isFromRobot) ||
            auth.exists(_.isAdmin)
      )
        WikiSearch.getList(realm, q, scope, curTags, MAX_LIST)
      else
        Nil

    if (!isFromRobot && qi.length > 0)
      Audit.logdb("QUERY", q, s"Realm: $realm, Scope: $scope", "Results: " + wl.size,
        "User-Agent: " + request.headers.get("User-Agent").mkString)

    if (wl.size == 1 && iq != "")
    // if just one found and not a tag browsing
      Redirect(controllers.WikiUtil.w(wl.head.wid))
    else {
      // the list of tags, sorted by count of occurences
      val tags = wl
          .flatMap(_.tags)
          .filter(_ != Tags.ARCHIVE)
          .filter(_ != "")
        .filter(x=> !qt.contains(x))
        .groupBy(identity)
        .map(t => (t._1, t._2.size))
        .toSeq
        .sortBy(_._2)
        .reverse

      val result = { implicit stok:StateOk =>
        views.html.wiki.wikiList(
          q,
          q,
          curTags,
          wl.map(w => (w.wid, w.label)),
          tags,
          (if(q.length>1) "/wikie/search/tag/"
          else if(scope.length > 0) s"/wiki/$scope/tag/"
          else "/tag/"),
          (if(q.length>1) "?q="+q else ""),
          realm,
          allRealms) // showRealm if looking in all
      }

      if(wl.nonEmpty) ROK.r reactorLayout12 result
      else            ROK.r notFound12      result // Google likes 404 here or reports it as a "soft 404"
    }
  }

  /** prepare the wid to be fully defined with realm and whatnot */
  def prepWid (cw:CMDWID, irealm:String) (implicit request:Request[_]) =
    cw.wid.map(iwid =>
      if(iwid.realm.isDefined) iwid
      else iwid.r(getRealm(irealm)))

  /**
   * show an older version of a page
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def showWidVer(cw: CMDWID, ver: Int, irealm:String) = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      wid <- prepWid(cw, irealm);
      page <- wid.page orErr "page not found";
      w <- ROne[WikiEntryOld]("entry._id" -> page._id, "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, request.au, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      wikiPage(wid, Some(wid.name), Some(w.entry), false, false)
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER")
    }
  }

  /**
   * show conetnt of current version
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def wikieContent(cw: CMDWID, irealm:String) = RAction { implicit request =>
     val awid = request.qhParm("wpath").flatMap (WID.fromPath).map(_.r(irealm)).orElse(prepWid(cw, irealm))
    (for (
      wid <- awid;
//      w <- wid.page;
      w <- wid.page orErr s"page not found: $wid";
      content <- wid.content orErr s"no content for $wid";
      can <- canSee(wid, request.au, Some(w)) orCorr cNoPermission
    ) yield {
        Ok(content.replaceAllLiterally("\r\n", "\n"))
      }) getOrElse {
      noPerm(cw.wid.get, "SHOW.CONTENT")
    }
  }

  /**
   * show conetnt of current version
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def showWidContentVer(cw: CMDWID, ver: Int, irealm:String) = RAction { implicit request =>
    (for (
      wid <- prepWid(cw, irealm);
      w <- ROne[WikiEntryOld](
        "entry.realm" -> wid.getRealm,
        "entry.category" -> wid.cat,
        "entry.name" -> wid.name,
        "entry.ver" -> ver) orErr "not found";
      can <- canSee(wid, request.au, wid.page.orElse(Some(w.entry))) orCorr cNoPermission
    ) yield {
      Ok(w.entry.content.replaceAllLiterally("\r\n", "\n"))
    }) getOrElse {
      noPerm(cw.wid.get, "SHOW.VER.CONTENT")
    }
  }

  /**
   * show full entry as JSON
   */
  def wikieJson(cw: CMDWID, irealm:String) = RAction { implicit request =>
    (for (
      wid <- prepWid(cw, irealm);
      w <- wid.page;
      can <- canSee(wid, request.au, Some(w)).orElse{
        // users can import common realms
        Some(
          request.au.exists(_.isActive) &&
          Array("rk", "wiki", "specs").contains(wid.realm)
        )
      } orCorr cNoPermission
    ) yield {
        Ok(w.cloneContent(w.content.replaceAllLiterally("\r\n", "\n")).grated.toString).as("application/json")
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


  val editForm = Form {
    mapping(
      "label" -> nonEmptyText.verifying(vBadWords, vSpec),
      "markup" -> nonEmptyText.verifying("Unknown!", {x:String=> Wikis.markups.contains(x)}),
      "content" -> text,
      "visibility" -> nonEmptyText,
      "wvis" -> nonEmptyText,
      "oldVer" -> nonEmptyText,
      "tags" -> text.verifying(vBadWords, vSpec),
      "draft" -> text.verifying(vBadWords, vSpec))(EditWiki.apply)(EditWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: EditWiki => !Wikis.hasBadWords(ew.content)
    })
  }

  /** use the same protocol that the current request came in with */
  def RazRoutingHttps (url:String) (implicit request:Request[AnyContent]) = {
    if(request.uri.startsWith("https:") && url.startsWith("http:")) url.replaceFirst("http:", "https:")
    else url
  }

  /** show a page */
  def showWid(cw: CMDWID, count: Int, irealm:String) = Action {implicit request=>
    prepWid(cw, irealm).map {wid=>
    cw.cmd match {

        // NOTE - these must be listed in WID.cmdfromPath

      case "meta"     => wid.page.map(w=> {
        // this is used when saving/migrating content, it's full meta json
        val j = w.grated
        j.put("content", "") // erase content, too big...
        j.remove("updDtm") // changes every time...
        j.remove("ver") // changes every time...
        Ok(j.toString).as("application/json")
      }).getOrElse(NotFound("WID not found:"+wid.wpath))

      case "json" =>
        wid.page.map(w=> Ok(w
              .copy(content="")
              .grated.toString)
              .as("text/plain"))
          .getOrElse(NotFound("WID not found:"+wid.wpath))

      case "content" => wid.page
          .map(w=> Ok(
            w.content
              .replaceAllLiterally("\r\n", "\n"))
              .as("text/plain"))
              .getOrElse(NotFound("WID not found:"+wid.wpath))

      case "included" => wid.page.map(w=> Ok(w.included).as("text/plain")).getOrElse(NotFound("WID not found:"+wid.wpath))
      case "xp"  => xp(wid, cw.rest).apply(request).value.get.get
      case "xpl" => xpl(wid, cw.rest).apply(request).value.get.get
      case "edit" => Redirect(routes.Wikie.wikieEdit(wid).url, request.queryString)
      case "rss.xml" => rss(wid, cw.rest).apply(request).value.get.get
      case "dualView"  => {
        wid.page.map(w=>
          ROK.r noLayout { implicit stok =>
            views.html.util.reactorLayout12FullPage(
              views.html.wiki.wikiDualView(w.wid, w, editForm.fill(
                EditWiki(w.label,
                  w.markup,
                  w.content,
                  PUBLIC,
                  PUBLIC,
                  w.ver.toString,
                  "",
                  w.props.get("draft").getOrElse("Silent"))) ),
              Seq.empty
            )
          }
        ).getOrElse(NotFound("WID not found:"+wid.wpath))
      }

      case "debug" | "usage" => {//Action.async { implicit request =>
        val realm = getRealm(irealm)
        val wid = cw.wid.get.r(realm)
        wikieDebug(wid, cw.cmd).apply(request).value.get.get
      }

      case "tag" => {
        // stupid path like /wiki//tag/x comes here...
        if(cw.wpath.isEmpty || cw.wpath.exists(_.isEmpty)) showTag(cw.rest, irealm).apply(request).value.get.get
        else search(getRealm(irealm), "", cw.wpath getOrElse "", cw.rest).apply(request).value.get.get
      }

      case _ => {
        val realm = getRealm(irealm)
        // must check if page is WITHIN site, otherwise redirect to main site
          val fhost = Website.getHost
          val redir = fhost flatMap Services.config.urlfwd
          val rewrite = fhost.flatMap(h=> Services.config.urlrewrite(h + request.path))
          val canon = fhost flatMap (fh=> Services.config.urlcanon(cw.wpath.get, None).map(_.startsWith("http://"+fh)))

          // removed topics redirected
          if (rewrite.isDefined) {
            log("  REWRITE: REDIRECTED FROM - " + fhost+request.path)
            log("    TO " + rewrite.get)
            Redirect(RazRoutingHttps(rewrite.get))
          } else if (fhost.exists(_ != Services.config.hostport) &&
            // if not me, no redirection and not the redirected path, THEN redirect
            redir.isDefined &&
            !cw.wpath.get.startsWith(redir.get.replaceFirst(".*/wiki/", "")) &&
            !canon.exists(identity)) {
            log("  REDIRECTED FROM - " + fhost)
            log("    TO http://" + Services.config.hostport + "/wiki/" + cw.wpath.get)
  //          Redirect("http://" + Config.hostport + "/wiki/" + cw.wpath.get)
            Redirect(RazRoutingHttps(wid.url))
          } else fhost.flatMap(x=>Website.forHost(x)).map { web=>
            show(wid, count).apply(request).value.get.get //todo what the heck is this?
          } getOrElse {
            // normal - continue showing the page
            show(wid, count).apply(request).value.get.get
          }
      }
    }
    }.getOrElse {
      NotFound ("WID not found")
    }
  }

  /** show a page */
  def printWid(cw: CMDWID, irealm:String) = Action { implicit request =>
    show(prepWid(cw, irealm).get, 0, print = true).apply(request).value.get.get
  }

  /** POST against a page - perhaps a trackback */
  def postWid(wp: String, irealm:String) = Action { implicit request =>
    //    if (model.BannedIps isBanned request.headers.get("X-FORWARDED-HOST")) {
    //      admin.Audit.logdb("POST-BANNED", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //      Ok("")
    //    } else {
    //      admin.Audit.logdb("POST", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //    Services.audit.unauthorized(s"POST Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
    unauthorized("Oops - can't POST here", shouldAudit = false)
    //    }
  }

  def show1(cat: String, name: String, irealm:String) = Action { implicit request =>
      show(WID(cat, name).r(getRealm(irealm))).apply(request).value.get.get
  }
  def showId(id: String, irealm:String) = Action { implicit request =>
    val realm = getRealm(irealm)
    (
        for (w <- Wikis(realm).findById(id)) yield
          Redirect(controllers.WikiUtil.wr(w.wid, realm))
    ) getOrElse
        Msg("Oops - id not found")
  }

  def contentById(cat:String, id: String, irealm:String) = Action { implicit request =>
    val realm = getRealm(irealm)
    (for (w <- Wikis(realm).findById(cat, id)) yield Ok(w.content)
      ) getOrElse NotFound("wiki not found")
  }


  def call[A, B](value: A)(f: A => B) = f(value)

  def wikieShow(iwid: WID, count: Int = 0, irealm:String=UNKNOWN) = Action { implicit request =>
    val wid = if(iwid.realm.isDefined) iwid else iwid.r(getRealm(irealm))
    show(wid, count).apply(request).value.get.get
  }

  /** find the page to display and ask wikiPage() to format and display the page */
  def show(iwid: WID, count: Int = 1, print: Boolean = false): Action[AnyContent] = RAction { implicit request =>
//    implicit val errCollector = new VErrors()
    implicit val au = request.au

    def canSeeMaybe(wid: WID, au: Option[WikiUser], w: Option[WikiEntry]) = {
      canSee(wid, au, w).getOrElse(false) || w.exists(_.contentProps.contains("publicAlternative"))
    }

    // since some test a lot, try to be fair with counting
    val shouldNotCount = request.flash.get("count").exists("0" == _) || (count == 0) ||
      isFromRobot(request.ireq) || au.exists("Razie" == _.userName) || au.exists("Ileana" == _.userName) || au.exists("Admin" == _.userName)

    debug("show2 " + iwid.wpath)
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    val realm = request.realm //getRealm(UNKNOWN)(request.ireq) // todo request.realm

    // optimize - don't reload some crap already in the iwid
    val wid = {
      if (ObjectId.isValid(iwid.name)) {
        // todo I do two lookups to serve by ID
        val wn = UWID(cat, new ObjectId(iwid.name), iwid.realm).findWid.map(_.name).getOrElse(UNKNOWN)
        WID(cat, wn, iwid.parent, iwid.section, iwid.realm)
      }
      else if (cat == iwid.cat && name == iwid.name) iwid
      else WID(cat, name, iwid.parent, iwid.section, iwid.realm)
    }.defaultRealmTo(realm)

    // so they are available to scripts
    razie.NoStaticS.put(QueryParms(request.queryString))

    def isSuperCat (cat:String) = Array("Blog", "Post", "Topic", "Page") contains cat

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "Private_Messages" == name) Redirect("/doe/msg/PM")
//    else if ("Reactor" == cat && iwid.name != Wikis.RK && !iwid.realm.exists(_ != Wikis.RK)) Redirect("/w/"+wid.name+"/wiki/"+wid.wpath)
    else if ("Category" == cat && !Wikis(iwid.getRealm).categories.exists(_.name == name))
      domainController.catBrowser("diesel", iwid.getRealm, iwid.getRealm, name, "").apply(
        request.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
    else if ("any" == cat || (cat.isEmpty && wid.parent.isEmpty)) {
      // search for any name only if cat is missing OR there is no parent

      // TODO optimize to load just the WID - i'm redirecting anyways
      // todo trying first the index and then cache - did not think this through
      var wl = Wikis(wid.getRealm).index.getWids(name).flatMap(
        x => Wikis.cachedPage(x, au) orElse Wikis(wid.getRealm).find(x))
      if (wl.isEmpty)

        wl = Wikis(wid.getRealm)
            .findAny(name)
            .toList

      wl = wl.filter(page => canSeeMaybe(page.wid, au, Some(page)))

      if (wl.size == 1) {
        if (isSuperCat(wl.head.wid.cat)) {
          // Blogs and other topics are allowed nicer URLs, without category
          // search engines don't like URLs with colons etc
          show(wl.head.wid, count, print).apply(request.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
        } else
        // redirect to use the proper Category display
          Redirect(controllers.WikiUtil.wr(wl.head.wid, realm))
      } else if (wl.nonEmpty) {
        val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
        ROK.k reactorLayout12 {
          views.html.wiki.wikiList("category any", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", wid.getRealm)
        }
      } else {
        // last attempt: index contains lowercase name
        Wikis(wid.getRealm).index.getForLower(wid.name.toLowerCase).flatMap {newName=>
          log("- redirecting lower case: " + iwid.wpath)
          Wikis(wid.getRealm).index.getWids(newName).headOption.map { newWid =>
            Redirect(controllers.WikiUtil.wr(newWid, realm)) // perhaps different class name
          }
        } getOrElse {
          wikiPage(wid, Some(iwid.name), None, !shouldNotCount, au.isDefined && canEdit(wid, au, None).exists(identity))
        }
      }
    } else {
      // normal request with cat and name OR empty cat but has parent

      // the idea is that as pages are displayed *with a user*, the cache fills up
      val w = Wikis.cachedPage(wid, au) orElse {
        // if isSuperCat then usually the WID won't match, but index can still find it
        Wikis(wid.getRealm).index.getWids(wid.name).headOption.flatMap(_.page)
      }

      if (!w.isDefined && Wikis(wid.getRealm).index.containsLower(wid.name.toLowerCase)) {
        val newName = Wikis(wid.getRealm).index.getForLower(wid.name.toLowerCase).get
        // it may be in a mixin realm
        Wikis(wid.getRealm).index.getWids(newName).headOption.map { newWid =>
          log(s"- redirecting lower case FOUND: ${iwid.wpath} -> ${newWid.wpath}")
          Redirect(controllers.WikiUtil.wr(newWid, realm)) // perhaps different class name
        } getOrElse {
          val newWid = wid.copy(name = newName)
          log(s"- redirecting lower case WHO?: ${iwid.wpath} -> ${newWid.wpath}")
          Redirect(controllers.WikiUtil.wr(newWid, realm)) // gave up - maybe... whatever
        }
      } else {
        // finally there!!
        //        cout << "1"
        val can = canSee(wid, au, w).getOrElse(false)

        if (!can && !w.exists(_.contentProps.contains("publicAlternative"))) {
          // HERE the user is not authorized to see - can we tease or not?
          val more = request.website.prop("msg.noPerm").flatMap(WID.fromPath).flatMap(_.content).mkString
          val teaser = request.website.prop("msg.err.teaserCategories").flatMap(_.split(",").find(_ == wid.cat)).flatMap(_ => w).map{
            page=> {
              def hostNameForRealm={
                WikiReactors(page.wid.getRealm).websiteProps.prop("url").getOrElse("http://www.dieselapps.com")
              }

              request.canonicalLink = Some(page).flatMap(p=> Services.config.urlcanon(p.wid.wpath, Some(page.tags))).orElse {
                  Some(s"$hostNameForRealm/${page.wid.canonpath}")
              }

              def tags = page.tags.map(t=>s"""<a href="/tag/$t"><b>$t</b></a>""").mkString(" | ")
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
            noPerm(wid, more, !isFromRobot(request.ireq), "", None, false)
          else
            noPerm(wid, more, false, teaser, Some(request), false)
        } else {
        //        cout << "2"
          // alternative public page
          if (!can && w.exists(_.contentProps.contains("publicAlternative")) && (WID fromPath w.get.contentProps("publicAlternative")).isDefined ) {
            val nwid = WID fromPath w.get.contentProps("publicAlternative")
            wikiPage(nwid.get, Some(iwid.name), nwid.flatMap(_.page), !shouldNotCount, au.isDefined && canEdit(wid, au, None), print)
          } else {
            w.map { w =>
              // redirect a simple alias with no other content
              w.alias.map { wid =>
                Redirect(controllers.WikiUtil.wr(wid.formatted, realm))
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
  /** format and display the page */
  private def wikiPage(wid: WID, iname: Option[String], page: Option[WikiEntry], shouldCount: Boolean, canEdit: Boolean, print: Boolean = false)(implicit stok:RazRequest) = {
    var res = {
      if (shouldCount) page.foreach { p =>
        Services ! WikiAudit("SHOW", p.wid.wpath, stok.au.map(_._id), stok.oreq.map(_.headers.toSimpleMap.mkString))
        Services ! WikiCount(p._id)
      }

      //    cdebug << "A"
      page.map(_.preprocess(stok.au)) // just make sure it's processed
      //    cdebug << "B"

      if (page.exists(_.fields.nonEmpty)) {
        showForm(wid, iname, page, stok.au, shouldCount, Map.empty, canEdit, print)
      } else {
        //    cdebug << "C"
        ROK.k noLayout { implicit stok =>
          views.html.wiki.wikiPage(wid, iname, page, canEdit, print)
        }
      }
    }

    // add any headers the page requires
    page.map { p =>
      p.contentProps.filter(_._1.startsWith("http.header.")).map { t =>
        res = res.withHeaders(t._1.replaceFirst("http.header.", "") -> t._2)
      }
    }

    res
  }

  def wikieReferences(iwid: WID) = wikieDebug (iwid, "references")
  def wikieUsage(iwid: WID) = wikieDebug (iwid, "usage")

  def wikieHistory(iwid: WID) = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()

    val wid = iwid.formatted

    razie.NoStaticS.put(QueryParms(request.queryString))

    wid.page match {
      case x @ Some(w) if !canSee(wid, auth, x).getOrElse(false) => noPerm(wid, "DEBUG")
      case y @ Some(w) =>
        ROK.s apply {implicit stok=> views.html.wiki.wikieHistory(w.wid, Some(iwid.name), y)}
      case None => Msg (s"${wid.wpath} not found")
    }
  }

  def wikieDebug(iwid: WID, what:String="debug") = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()

    val wid = iwid.formatted

    razie.NoStaticS.put(QueryParms(request.queryString))

    wid.page match {
      case x @ Some(w) if !canSee(wid, auth, x).getOrElse(false) => noPerm(wid, "DEBUG")
      case y @ Some(w) if("usage" == what) =>
        ROK.s apply {implicit stok=> views.html.wiki.wikieUsage(w.wid, Some(iwid.name), y)}
      case y @ Some(w) =>
        ROK.s apply {implicit stok=> views.html.wiki.wikieDebug(w.wid, Some(iwid.name), y)}
      case None => Msg (s"${wid.wpath} not found")
    }
  }

  def all(cat: String, irealm:String) = RAction { implicit stok =>
    val wl =
      if(cat != "" && cat != "Form" && cat != "User")
        Wikis(getRealm(irealm)(stok.req)).pages(cat)
      else if(cat == "") // all
        Wikis(getRealm(irealm)(stok.req)).table.find(Map("realm" -> stok.realm)) map (grater[WikiEntry].asObject(_))
      else
        List.empty[WikiEntry].toIterator

    ROK.k apply {implicit stok=>
      views.html.wiki.wikiListAnalyze("", "", "", wl.toIterator)
    }

//    Redirect("/wiki/analyze")
  }

  def analyze(q: String, tags:String, scope:String) = RAction { implicit stok =>
    val wl = WikiSearch.getList(stok.realm, q, scope, tags)

    ROK.k apply {implicit stok=>
      views.html.wiki.wikiListAnalyze(q, tags, scope, wl.toIterator)
    }
  }

  def xp(wid: WID, path: String, page: Option[WikiEntry] = None) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      worig <- xpRoot(wid, page);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) orErr "no page" // TODO cascading aliases?
    ) yield {
      val node = new WikiWrapper(wid)
      val root = new razie.XpWrapper(node, WikiXpSolver)

      Audit.logdb("XP", wid.wpath + "/xp/" + path)

      val xpath = "*/" + path
      val res: List[Any] =
        if(xpath.matches(".*/@\\((.*)\\)")) {
          // report style: /ha/ha/@(name,wpath,url)
          val names = xpath.replaceAll(".*/@\\((.*)\\)", "$1").split(",")
          (root xpl xpath.replaceAll("/@.*", "")).collect {
            case we: WikiWrapper => names.map{x=>
              (x -> WikiXpSolver.getAttr(we, x))
            }.toMap
          }
        } else if (razie.GPath(xpath).isAttr) (root xpla xpath)
        else (root xpla (xpath+"/@wpath"))

      Ok(js.tojsons(res, 1)).as("application/json")
    }) getOrElse
      Unauthorized("Nothing... for " + wid + " XP " + path+" ERR: "+errCollector.mkString)
  }

  private def xpRoot (wid:WID, page:Option[WikiEntry])(implicit request:Request[_]) = {
    def wcat = WID("Category", "Category").r(getRealm())

    page orElse (
      if ("*" == wid.name) wcat.page else None
      ) orElse Wikis(wid.getRealm).find(wid);
  }

  def xpl(wid: WID, path: String, page: Option[WikiEntry] = None) = RAction { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      worig <- xpRoot(wid, page);
      w <- worig.alias.flatMap(x => Wikis(wid.getRealm).find(x)).orElse(Some(worig)) orErr "no page" // TODO cascading aliases?
    ) yield {
        val root = new razie.XpWrapper(new WikiWrapper(w.wid), WikiXpSolver)

        Audit.logdb("XP-L", wid.wpath + "/xpl/" + path)

        val xpath = "*/" + path
        // TODO use label not name
        val res = (root xpl xpath).collect {
          case we: WikiWrapper => (we.wid, we.wid.name, we.w.toSeq.flatMap(_.tags))
        }

        val tags = res.flatMap(_._3).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
        ROK.k reactorLayout12 {
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
      ROK.r apply { implicit stok =>
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
        val script = w.scripts.find(sec == _.name).orElse(Wikis(wid.getRealm).category(widp.cat) flatMap (_.scripts.find(sec == _.name)))
        val res: String = script.filter(_.checkSignature(Some(au))).map(s => {
          model.WikiScripster.impl.runScript(s.content, "js", Some(w), Some(au), request.queryString.map(t => (t._1, t._2.mkString)), Map.empty)
        }) getOrElse ""
        Audit.logdb("SCRIPT_RESULT", res)
        res
      } catch { case _: Throwable => "?" }
      Ok(res)
    }) getOrElse unauthorized()
  }

  /**
    *
    */
  def wikiBrowse(wpath:String) = RAction { implicit request =>
    Redirect("/wiki/"+wpath)
      .withCookies(
        Cookie("weBrowser", "true").copy(httpOnly = false)
      )
  }

  /** try to link to something - find it */
  def social(label:String, url:String) = Action { implicit request =>
    ROK.r apply { implicit stok =>
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
      var links = Wikis.linksTo(w.uwid)
        .filter(_.draft.isEmpty)
        .toList
        .sortWith((a,b)=>b.crDtm.isBefore(a.crDtm))
        .take(30)
        .map(_.from)
        Ok(views.xml.wiki.wikiRss(w, links))
      }) getOrElse
      Ok("No feed found for " + wid + " TAGS " + path + "\n" + errCollector.mkString)
  }

  /** return all tags used as JSON array */
  def wikieTagOptions(irealm:String) = Action { implicit request =>
    val realm = if (UNKNOWN == irealm) getRealm(irealm) else irealm

    val tags = Wikis(realm).index.usedTags.keys

    Ok("["+(tags).map(s=>s""" "$s" """).mkString(",")+"]").as("text/json")
  }

  def wikieReport(what:String) = FAUR { implicit request =>
    ROK.k apply {implicit stok=>
      what match {
        case "views" =>
          views.html.wiki.reportViews()
        case "video" =>
          views.html.wiki.reportTag(new WTReport("video", request.realm))
        case "photo" =>
          views.html.wiki.reportTag(new WTReport("photo", request.realm))
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

    ROK.r noLayout {implicit stok=>
        Html(res)
    }
  }

}

/** wiki controller */
import com.google.inject.Singleton
@Singleton
class WikiApiv1 extends WikiBase {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

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
      ROK.s noLayout { implicit stok => views.html.wiki.wikiFrag(wid, None, true, Some(page)) }
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
   * return parts of a topic in different ways
   * todo interesting: if auth permissions changed in the mean time - should I use old or new perms?
   */
  def format(cw: CMDWID, form:String, irealm:String) = Action { implicit request =>
    implicit val errCollector = IgnoreErrors
    prepWid(cw, irealm) {wid=>
      page(wid) {w=>
        if(canSee(wid, auth, Some(w)))
          form match {
            case "content"  => Ok(w.content)
            case "json"     => Ok(w.grated.toString).as("application/json")
            case "fullpath" => Ok(wid.wpathFull)

            case "html"  =>
              ROK.r noLayout { implicit stok =>
                views.html.wiki.wikiFrag(w.wid, None, true, Some(w))
              }

            case "json"  =>
              // limited json of the page, only specific fields -
              // /weapi/v1/entry/*wpath
              Ok(filterJson(w.grated).toString).as("application/json")
          }
        else
          Unauthorized(s"Can't see wpath ${wid.wpath}")
      }
    }
  }

}

object WikiUtil {

  def before(e: WikiEntry, what: String)(implicit errCollector: VErrors = IgnoreErrors): Boolean = {
    WikiObservers.before(WikiEvent(what, "WikiEntry", e.wid.wpath, Some(e)))
  }

  def after(old: Option[WikiEntry], e: WikiEntry, what: String, au: Option[User])(implicit errCollector: VErrors =
  IgnoreErrors): Unit = {
    Services ! WikiAudit(what, e.wid.wpathFull, au.map(_._id), None, Some(e), old)
  }

  case class LinkWiki(how: String, notif: String, markup: String, comment: String)

  case class ReportWiki(reason: String)

  case class EditWiki(label: String, markup: String, content: String, visibility: String, edit: String,
                      oldVer: String, tags: String, notif: String)

  def w(we: UWID, fromRealm: String): String = we.wid.map(wid => wr(wid, fromRealm)).getOrElse(
    "ERR_NO_URL_FOR_" + we.toString)

  /** @deprecated use the one with fromREalm */
  def w(we: UWID): String = we.wid.map(wid => w(wid)).getOrElse("ERR_NO_URL_FOR_" + we.toString)

  /** @deprecated use the one with fromREalm */
  def w(we: WID): String = we.w

  def w(we: WID, shouldCount: Boolean): String = we.w(shouldCount)

  def wr(wid: WID, fromRealm: String, shouldCount: Boolean = true): String =
    wid.urlRelative(fromRealm) + (if (!shouldCount) "?count=0" else "")

  /** @deprecated use the one with fromREalm */
  def w(cat: String, name: String, realm: String) =
    WID(cat, name).r(realm).urlRelative

  /** @deprecated use the realm version */
  def w(name: String) = s"/wiki/$name" //todo remove

// todo duplicates from WikiBase

  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility",
                we: Option[WikiEntry] = None)(implicit errCollector: VErrors = IgnoreErrors): Boolean =
    Services.wikiAuth.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)
             (implicit errCollector: VErrors): Option[Boolean] =
    Services.wikiAuth.canEdit(wid, u, w, props)(errCollector)

  class WTReport(val tag: String, val realm: String) {
    // value, list of wikis
    val rep = new collection.mutable.HashMap[String, List[WID]]()

    Wikis(realm).foreach { dbo =>
      val we = grater[WikiEntry].asObject(dbo)

      val PATT = ("""(?s)\{\{""" + tag + """(\.\w*)?([: ])?([^ }]*)?""").r

      for (m <- PATT.findAllMatchIn(we.content)) {
        val url = m.group(3)
        if (!rep.contains(url))
          rep.put(url, List(we.wid))
        else
          rep.put(url, we.wid :: rep(url))
      }
    }
  }

  /** make a relative href for the given tag. give more tags with 1/2/3 */
  def hrefTag(curRealm: String, wid: WID, t: String, label: String) = {
    if (Array("Blog", "Forum") contains wid.cat) {
      s"""<b><a href="${wr(wid, curRealm)}/tag/$t">$label</a></b>"""
    } else {
      if (wid.parentWid.isDefined) {
        s"""<b><a href="${wr(wid.parentWid.get, curRealm)}/tag/$t">$label</a></b>"""
      } else {
//        s"""<b><a href="${routes.Wiki.showTag(t, wid.getRealm)}">$label</a></b>"""
        s"""<b><a href="/tag/$t">$label</a></b>"""
      }
    }
  }

  /** create links to parents or other wikis, based on staged WikiLinkStaged */
  def applyStagedLinks(wid: WID, w: WikiEntry)(implicit txn: Txn): WikiEntry = {
    var we = w

    for (s <- Staged.find("WikiLinkStaged").filter { x =>
      val from = x.content.get("from").asInstanceOf[DBObject]
      from.get("cat") == wid.cat && from.get("name") == wid.name
    }) {
      val wls = Wikis.fromGrated[WikiLinkStaged](s.content)

      //todo this nevet actually works because the we is not created yet...??
      // todo so i should use we.uwid insted - i know it's from anyways
      //but then it will be replicated for parent below - will need to prevent duplicates
      for (ufrom <- wls.from.uwid;
           uto <- wls.to.uwid) {
        val wl = WikiLink(ufrom, uto, wls.how)
        wl.create
      }

      we = we.copy(parent = Wikis.find(wls.to).map(_._id), updDtm = DateTime.now) // add parent
      s.delete
    }

    // needs parent?
    we.wid.parentWid.flatMap(_.uwid).foreach { puwid =>
      val isd = if (we.props.contains("draft")) Some("y") else None
      val wl = ROne[WikiLink]("from.id" -> we.uwid.id, "to.id" -> puwid.id, "how" -> "Child")
      if (wl.isDefined)
        wl.foreach(_.copy(draft = isd).update)
      else
        WikiLink(we.uwid, puwid, "Child", isd).create
    }

    we
  }

  /** simple search for all realms */
  def searchAll(qi: String, realm: String) = {
    val PAT = qi.r

    def min(a: Int, b: Int) = if (a > b) b else a

    def max(a: Int, b: Int) = if (a > b) a else b

    val wikis = isearch(qi, realm).map { t =>
      val (u, m) = t
      (WikiEntry grated u,
          m.before.subSequence(max(0, m.before.length() - 5), m.before.length()),
          m.matched,
          m.after.subSequence(0, min(5, m.after.length)))
    }

    val wl = wikis.take(500).toList
    wl
  }

  /** simple search for all realms */
  def searchAllTag(qi: String, realm: String) = {
    val PAT = qi.r

    def min(a: Int, b: Int) = if (a > b) b else a

    def max(a: Int, b: Int) = if (a > b) a else b

    val wikis = Wikis(realm)
        .cats.keys.toList
        .flatMap(cat => Wikis(realm).pages(cat).toList)
        .filter(_.tags.contains(qi))
        .take(500)
        .map { w =>
          val m = PAT.findAllMatchIn(w.tags.mkString(",")).collectFirst({ case x => x }).get
          (w,
              m.before.subSequence(0, m.before.length()),
              m.matched,
              m.after.subSequence(0, m.after.length))
        }

    wikis
  }

  /** search string AND update content if update function present */
  def isearch(qi: String, realm: String, update: Option[DBObject => DBObject] = None) = {
    val PAT = qi.r
    val table = RazMongo("WikiEntry")

    val wikis =
      for (
        u <- table.findAll() if qi.length >= 3 && (realm.length == 0 || u.get("realm") == realm);
//          m <- (tag.isDefined && u.get("tags").asInstanceOf[Seq[String]].contains(tag.get) ||
//            PAT.findAllMatchIn(u.get("content").asInstanceOf[String]))
        m <- PAT.findAllMatchIn(u.get("content").asInstanceOf[String])
      ) yield {
        if (update.isDefined) {
          table.save(update.get.apply(u))
        }
        (u, m)
      }
    wikis
  }

}