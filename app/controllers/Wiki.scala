/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import scala.Array.canBuildFrom
import org.joda.time.DateTime
import com.mongodb.DBObject
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin._
import model.Enc
import db.RazMongo
import model.Perm
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import model.Stage
import model.User
import model.UserType
import model.UserWiki
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, Action, Request}
import razie.Logging
import razie.cout
import db.ROne
import db.RMany
import model.WikiCount
import model.WikiIndex
import model.Wikis
import model.CMDWID
import model.WikiAudit
import model.WikiEntry
import model.WikiEntryOld
import model.WID
import model.WikiLink
import model.WikiDomain
import model.WikiWrapper
import model.WikiXpSolver
import model.WikiUser
import razie.clog
import scala.Some
import model.WikiAudit
import model.WikiEntryOld
import model.WikiLink
import model.CMDWID
import model.UserWiki
import model.User
import model.Stage

/** reused in other controllers */
class WikiBase1 extends RazController with Logging with WikiAuthorization {
  /** yeah, I hate myself - happy? */
  var authImpl: WikiAuthorization = new NoWikiAuthorization

  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry] = None)(implicit errCollector: VError = IgnoreErrors): Boolean =
    authImpl.isVisible(u, props, visibility)(errCollector)

  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VError): Option[Boolean] =
    authImpl.canSee(wid, au, w)(errCollector)

  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VError): Option[Boolean] =
    authImpl.canEdit(wid, u, w, props)(errCollector)

}

class WikiBase extends WikiBase1 {

  case class EditWiki(label: String, markup: String, content: String, visibility: String, edit: String, tags: String, notif: String)

  val editForm = Form {
    mapping(
      "label" -> nonEmptyText.verifying(vPorn, vSpec),
      "markup" -> nonEmptyText.verifying("Unknown!", Wikis.markups.contains(_)),
      "content" -> nonEmptyText,
      "visibility" -> nonEmptyText,
      "wvis" -> nonEmptyText,
      "tags" -> text.verifying(vPorn, vSpec),
      "draft" -> text.verifying(vPorn, vSpec))(EditWiki.apply)(EditWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: EditWiki => !Wikis.hasporn(ew.content)
        })
  }

  case class ReportWiki(reason: String)

  val reportForm = Form {
    mapping(
      "reason" -> nonEmptyText)(ReportWiki.apply)(ReportWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: ReportWiki => !Wikis.hasporn(ew.reason)
        })
  }

  val addForm = Form(
    "name" -> nonEmptyText.verifying(vPorn, vSpec))

  case class LinkWiki(how: String, notif: String, markup: String, comment: String)

  def linkForm(implicit request: Request[_]) = Form {
    mapping(
      "how" -> nonEmptyText,
      "notif" -> nonEmptyText,
      "markup" -> text.verifying("Unknown!", request.queryString("wc").headOption.exists(_ == "0") || Wikis.markups.contains(_)),
      "comment" -> text)(LinkWiki.apply)(LinkWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: LinkWiki => !Wikis.hasporn(ew.comment)
        })
  }

  case class FollowerLinkWiki(email1: String, email2: String, v1: Int, v2: Int, comment: String)

  def followerLinkForm(implicit request: Request[_]) = Form {
    mapping(
      "email1" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "email2" -> nonEmptyText.verifying("Wrong format!", vldEmail(_)).verifying("Invalid characters", vldSpec(_)),
      "v1" -> play.api.data.Forms.number,
      "v2" -> play.api.data.Forms.number,
      "comment" -> play.api.data.Forms.text)(FollowerLinkWiki.apply)(FollowerLinkWiki.unapply) verifying
      ("Email mismatch - please type again", { reg: FollowerLinkWiki =>
        if (reg.email1.length > 0 && reg.email2.length > 0 && reg.email1 != reg.email2) false
        else true
      })
  }

  // profile
  val renameForm = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name already in use", { t: (String, String) => !WikiIndex.containsName(Wikis.formatName(t._2))
      })
  }
}

object Visibility {
  final val PUBLIC = "Public"
  final val PRIVATE = "Private"
  final val CLUB = "Club"
  final val CLUB_ADMIN = "ClubAdmin"
}

/** wiki controller */
object Wiki extends WikiBase {
  import Visibility._

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      cat <- request.queryString("cat").headOption;
      name <- request.queryString("name").headOption
    ) yield wikieEdit(WID(cat, name)).apply(request).value.get.get) getOrElse {
      error("ERR_HACK Wiki.email2")
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  /** serve page for edit */
  def wikieEdit(wid: WID, icontent: String = "") = Action { implicit request =>
    implicit val errCollector = new VError()
    val n = Wikis.formatName(wid)

    debug("wikieEdit " + wid)

    Wikis.find(wid) match {
      case Some(w) =>
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), Some(w));
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki")
        ) yield {
          Ok(views.html.wiki.wikiEdit(w.wid, editForm.fill(
            EditWiki(w.label,
              w.markup,
              w.content,
              (w.props.get("visibility").getOrElse(PUBLIC)),
              wvis(Some(w.props)).getOrElse(PUBLIC),
              w.tags.mkString(","),
              w.props.get("draft").getOrElse("Notify"))),
            Some(au)))
        }) getOrElse
          noPerm(wid, "EDIT")
      case None =>
        val parentProps = wid.findParent.map(_.props)
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), None, parentProps);
          r3 <- ("any" != wid.cat) orErr ("can't create in category any");
          w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki")
        ) yield {
          Audit.missingPage("wiki " + wid);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(wid, Wikis.MD, wid.name)
          val tags = preprocessed.tags
          val contentFromTags = tags.foldLeft("") { (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          val visibility = wid.findParent.flatMap(_.props.get("visibility")).getOrElse(Wikis.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))
          val wwvis = wvis(wid.findParent.map(_.props)).getOrElse(Wikis.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))

          Ok(views.html.wiki.wikiEdit(wid, editForm.fill(
            EditWiki(wid.name.replaceAll("_", " "),
              Wikis.MD,
              contentFromTags + icontent + "\nEdit content here",
              visibility,
              wwvis,
              (if ("Topic" == wid.cat) "" else wid.cat.toLowerCase),
              "Notify")),
            auth))
        }) getOrElse
          noPerm(wid, "EDIT")
    }
  }

  /** save an edited wiki - either new or updated */
  def save(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    editForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.wiki.wikiEdit(wid, formWithErrors, auth))
      },
      {
        case we @ EditWiki(l, m, co, vis, wvis, tags, notif) => {
          log("Wiki.save " + wid)
          Wikis.find(wid) match {
            case Some(w) =>
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, Some(w));
                r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
                hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
                nochange <- (w.label != l || w.markup != m || w.content != co || (
                  w.props.get("visibility").map(_ != vis).getOrElse(vis != PUBLIC) ||
                  w.props.get("wvis").map(_ != wvis).getOrElse(wvis != PUBLIC)) ||
                  w.props.get("draft").map(_ != notif).getOrElse(false) ||
                  w.tags.mkString(",") != tags) orErr ("no change");
                newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
                newVer <- Some(w.cloneNewVer(newlab, m, co, au._id));
                upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr ("Not allowerd")
              ) yield {
                var we = newVer

                // visibility?
                if (we.props.get("visibility").map(_ != vis).getOrElse(vis != PUBLIC))
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                if (we.props.get("wvis").map(_ != wvis).getOrElse(wvis != PUBLIC))
                  we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)

                val shouldPublish =
                  if (notif != "Draft" && we.props.contains("draft")) {
                    we = we.copy(props = we.props - "draft")
                    notif == "Notify" // Silent means no notif
                  } else false

                if (we.tags.mkString(",") != tags)
                  we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

                // signing scripts
                if (au.hasPerm(Perm.adminDb)) {
                  if (!we.scripts.filter(_.signature startsWith "REVIEW").isEmpty) {
                    var c2 = we.content
                    for (s <- we.scripts.filter(_.signature startsWith "REVIEW")) {
                      def sign(s: String) = Enc apply Enc.hash(s)

                      c2 = we.PATTSIGN.replaceSomeIn(c2, { m =>
                        clog << "SIGNING:" << m << m.groupNames.mkString
                        if (s.name == (m group 2)) Some("{{%s:%s:%s}}%s{{/%s}}".format(
                          m group 1, m group 2, sign(s.content), s.content.replaceAll("""\\""", """\\\\"""), m group 1))
                        else None
                      })
                    }
                    we = we.cloneContent(c2)
                  }
                }

                if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Config.isLocalhost)) {
                  noPerm(wid, "HACK_SCRIPTS1")
                } else {
                  db.tx("Wiki.Save") { implicit txn =>
                    // can only change label of links OR if the formatted name doesn't change
                    w.update(we)
                    Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
                    Emailer.laterSession { implicit mailSession =>
                      au.quota.incUpdates
                      if (shouldPublish) notifyFollowersCreate(we, au)
                      au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                    }
                  }
                  Audit ! WikiAudit("EDIT", w.wid.wpath, Some(au._id))

                  Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
                }
              }) getOrElse
                Redirect(controllers.Wiki.w(wid, false)) // no change
            case None =>
              // create a new topic
              val parent= wid.findParent
              val parentProps = parent.map(_.props)
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, None, parentProps);
                hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
                r3 <- ("any" != wid.cat) orErr ("can't create in category any");
                w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
                r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission("uWiki")
              ) yield {
                //todo find the right realm from the url or something like Config.realm
                import razie.OR._
                var we = model.WikiEntry(wid.cat, wid.name, l, m, co, au._id, Seq(), parent.map(_.realm) OR "rk", 1, wid.parent)

                if (we.tags.mkString(",") != tags)
                  we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

                // special properties
                val wep = preprocess(we,false)
                //todo verify permissiont o create in realm
                if (wep.props.get("realm").exists(_ != we.realm))
                  we = we.copy(realm=wep.props("realm"))

                // needs owner?
                if (WikiDomain.needsOwner(wid.cat)) {
                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                  this dbop model.UserWiki(au._id, wid, "Owner").create
                  cleanAuth()
                }

                // visibility?
                if (vis != PUBLIC)
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                if (wvis != "Public")
                  we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)
                if (notif == "Draft")
                  we = we.cloneProps(we.props ++ Map("draft" -> notif), au._id)

                db.tx("wiki.create") { implicit txn =>
                  // anything staged for this?
                  for (s <- model.Staged.find("WikiLink").filter(x => x.content.get("from").asInstanceOf[DBObject].get("cat") == wid.cat && x.content.get("from").asInstanceOf[DBObject].get("name") == wid.name)) {
                    val wl = Wikis.fromGrated[WikiLink](s.content)
                    wl.create
                    we = we.cloneParent(Wikis.find(wl.to).map(_._id)) // should still change
                    s.delete
                  }

                  // needs parent?
                  we.wid.parentWid.foreach { pwid =>
                    if (ROne[WikiLink]("from" -> we.wid.grated, "to" -> pwid.grated, "how" -> "Child").isEmpty)
                      WikiLink(we.wid, pwid, "Child").create
                  }

                  we = we.cloneProps(we.props ++ Map("titi" -> "t"), au._id)

                  // needs parent owner? // the context of the page
                  we.findParent.flatMap(_.props.get("owner")).foreach { po =>
                    if (!we.props.get("owner").exists(_ == po))
                      we = we.cloneProps(we.props ++ Map("parentOwner" -> po), au._id)
                  }

                  we.create
                  Audit ! WikiAudit("CREATE", we.wid.wpath, Some(au._id))

                  admin.SendEmail.withSession { implicit mailSession =>
                    au.quota.incUpdates
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)
                    if (notif == "Notify") notifyFollowersCreate(we, au)
                    Emailer.tellRaz("New Wiki", au.userName, wid.ahref)
                  }
                }

                Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
              }) getOrElse
                noPerm(wid, "HACK_SAVEEDIT")
          }
        }
      })
  }

  private def preprocess(iwe:WikiEntry, isNew:Boolean):WikiEntry = {
    var we = iwe
    val wep = we.preprocessed
    var moreTags = ""

    // apply content tags
//    if(wep.tags("name") != we.name) we = we.copy(name=wep.tags("name"))

    //todo add tags from content
//    val atags = alltags(moreTags)
//    if (we.tags != atags)
//      we = we.withTags(atags, au._id)

    we
  }

  /** notify all followers of new topic/post */
  private def notifyFollowersCreate(wpost: WikiEntry, au: User)(implicit mailSession: MailSession) = {
    // 1. followers of this topic or followers of parent

    wpost.parent flatMap (Wikis.find(_)) map { w =>
      // user wikis
      (Users.findUserById(w.by).map(_._id).toList ++ model.Users.findUserLinksTo(w.wid).filter(_.notif == model.UW.EMAIL_EACH).toList.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).map(u => Emailer.sendEmailNewTopic(u, au, w.wid, wpost)))

      // followers by email
      model.Users.findFollowerLinksTo(w.wid).toList.groupBy(_.followerId).values.map(_.head).map(flink =>
        flink.follower.map(follower => {
          Emailer.sendEmailFollowerNewTopic(follower.email.dec, au, w.wid, wpost, flink.comment)
        }))
    }
  }

  // TODO optimize
  def search(q: String) = Action { implicit request =>
    // TODO limit the number of searches - is this performance critical?
    val qi = q.toLowerCase()
    val wikis = RazMongo.withDb(RazMongo("WikiEntry").m) { t =>
      for (
        u <- t if (
          (q.length > 1 && u.get("name").asInstanceOf[String].toLowerCase.contains(qi)) ||
          (q.length > 1 && u.get("label").asInstanceOf[String].toLowerCase.contains(qi)) ||
          (q.length() > 3 && u.get("content").asInstanceOf[String].toLowerCase.contains(qi)))
      ) yield u
    }.toList

    Audit.logdb("QUERY", q, "Results: " + wikis.size)

    if (wikis.count(x => true) == 1)
      Redirect(controllers.Wiki.w(WikiEntry.grated(wikis.head).wid))
    else
      Ok(views.html.wiki.wikiList(q, wikis.map(WikiEntry.grated _).toList.map(w => (w.wid, w.label)), auth))
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
   *  TODO is this authorized?
   */
  def showWidVer(cw: CMDWID, ver: Int) = Action { implicit request =>
    val wid = cw.wid.get
    ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver).map { p =>
      wikiPage(wid, Some(wid.name), Some(p.entry), false, false)(auth, request)
    } getOrElse {
      Oops("Version " + ver + " of " + wid + " NOT FOUND...", wid)
    }
  }

  /**
   * show conetnt of current version
   *  TODO is this authorized?
   */
  def showWidContent(cw: CMDWID) = Action { implicit request =>
    val wid = cw.wid.get
    wid.page.map { p =>
      Ok(p.content)
    } getOrElse {
      Oops("" + wid + " NOT FOUND...", wid)
    }
  }

  /**
   * show conetnt of current version
   *  TODO is this authorized?
   */
  def showWidContentVer(cw: CMDWID, ver: Int) = Action { implicit request =>
    val wid = cw.wid.get
    ROne[WikiEntryOld]("entry.category" -> wid.cat, "entry.name" -> wid.name, "entry.ver" -> ver).map { p =>
      Ok(p.entry.content)
    } getOrElse {
      Oops("Version " + ver + " of " + wid + " NOT FOUND...", wid)
    }
  }

  def headWid(cw: CMDWID, realm:String) = showWid(cw, 0, realm)

  // show wid prefixed with parent
  def showWidUnder(parent:String, cw: CMDWID, count: Int, realm:String) = {
    showWid(CMDWID(cw.wpath.map(x=>parent+"/"+x), cw.wid.flatMap(x=>WID.fromPath(parent+"/"+x.wpath)), cw.cmd, cw.rest), count, realm)
  }

  /** show a page */
  def showWid(cw: CMDWID, count: Int, realm:String) = {
    if (cw.cmd == "xp") xp(cw.wid.get, cw.rest)
    else if (cw.cmd == "xpl") xpl(cw.wid.get, cw.rest)
    else if (cw.cmd == "tag") xpl(cw.wid.get, "Post[" + cw.rest.split(",").map(x => s"tags~='.*$x.*'").mkString(" && ") + "]")
    else Action { implicit request =>
      // must check if page is WITHIN site, otherwise redirect to main site
      val fhost = request.headers.get("X-FORWARDED-HOST")
//            val fhost=Some("glacierskiclub.com")    // for testing locally
//            val fhost=Some("enduroschool.com")    // for testing locally
      val redir = fhost flatMap (Config.urlfwd(_))
      val canon = fhost flatMap (fh=> Config.urlcanon(cw.wpath.get).map(_.startsWith("http://"+fh)))

      // if not me, no redirection and not the redirected path, THEN redirect
      if (fhost.exists(_ != Config.hostport) && 
        redir.isDefined &&
        !cw.wpath.get.startsWith(redir.get.replaceFirst(".*/wiki/", "")) &&
        !canon.exists(identity)) {
        log("  REDIRECTED FROM - " + fhost)
        log("    TO http://" + Config.hostport + "/wiki/" + cw.wpath.get)
        Redirect("http://" + Config.hostport + "/wiki/" + cw.wpath.get)
      } else {
        // normal - continue showing the page
        show(cw.wid.get, count).apply(request).value.get.get
      }
    }
  }

  /** show a page */
  def printWid(cw: CMDWID) = show(cw.wid.get, 0, true)

  /** POST against a page - perhaps a trackback */
  def postWid(wp: String) = Action { implicit request =>
    //    if (model.BannedIps isBanned request.headers.get("X-FORWARDED-HOST")) {
    //      admin.Audit.logdb("POST-BANNED", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
    //      Ok("")
    //    } else {
    //      admin.Audit.logdb("POST", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
//    Services.audit.unauthorized(s"POST Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
    unauthorized("Oops - can't POST here", false)
    //    }
  }

  def show1(cat: String, name: String) = show(WID(cat, name))
  def showId(id: String) = Action { implicit request =>
    (for (w <- Wikis.findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(we: WID, shouldCount: Boolean = true) = Config.urlmap(we.urlRelative + (if (!shouldCount) "?count=0" else ""))

  def w(cat: String, name: String) =
//    if("Blog" == cat) Config.urlmap("/blog/%s:%s".format(cat, name))
    Config.urlmap("/wiki/%s:%s".format(cat, name))
  def w(name: String) = Config.urlmap("/wiki/" + name)

  def call[A, B](value: A)(f: A => B) = f(value)

  /** serve a site */
  def site(name: String) = show(WID("Site", name))

  def wikieShow(iwid: WID, count: Int = 0) = show(iwid, count)

  def show(iwid: WID, count: Int = 0, print: Boolean = false): Action[AnyContent] = Action { implicit request =>
    implicit val errCollector = new VError()
    implicit val au = auth

    val shouldNotCount = (request.flash.get("count").exists("0" == _) || (count == 0) ||
      isFromRobot(request) || au.exists("Razie" == _.userName))

    debug("show2 " + iwid.wpath)
    // TODO stupid routes - can't match without the :
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    // optimize - don't reload some crap already in the iwid
    val wid = if (cat == iwid.cat && name == iwid.name) iwid else WID(cat, name, iwid.parent)

    // so they are available to scripts
    razie.NoStaticS.put(model.QueryParms(request.queryString))

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("any" == cat || cat.isEmpty) {

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
      } else if (wl.size > 0)
        Ok(views.html.wiki.wikiList("category any", wl.map(x => (x.wid, x.label)), auth))
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
    implicit val errCollector = new VError()

    val cat = iwid.cat
    val name = Wikis.formatName(iwid)

    val wid = WID(cat, name)

    razie.NoStaticS.put(model.QueryParms(request.queryString))

    Wikis.find(cat, name) match {
      case x @ Some(w) if (!canSee(wid, auth, x).getOrElse(false)) => noPerm(wid, "DEBUG")
      case y @ _ => Ok(views.html.wiki.wikiDebug(wid, Some(iwid.name), y, auth))
    }
  }

  def all(cat: String) = Action { implicit request =>
    Ok(views.html.wiki.wikiAll(cat, auth))
  }

  def report(wid: WID) = Action { implicit request =>
    auth match {
      case Some(user) =>
        Ok(views.html.wiki.wikiReport(wid, reportForm.fill(ReportWiki("")), auth))
      case None => {
        Audit.auth("need logged in to report a wiki")
        val msg = "You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page..."
        //would audit again ERR_? Oops("You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page...", wid)
        Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid), auth))
      }
    }
  }

  def reported(wid: WID) = Action { implicit request =>
    reportForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiReport(wid, formWithErrors, auth)),
      {
        case we @ ReportWiki(reason) =>
          Wikis.flag(wid, "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
          SendEmail.withSession { implicit session =>
            SendEmail.send(SUPPORT, SUPPORT, "WIKI_FLAGGED",
              "link: <a href=\"http://" + Config.hostport + w(wid) + "\">" + wid.wpath + "</a> " + "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
          }
      })
    Msg("OK, page " + wid.wpath + " reported!", wid)
  }

  // from category - add a ... 
  def add(cat: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors => Msg2("Oops, can't add that name!" + formWithErrors, Some("/wiki/" + cat)),
      {
        case name: String => Redirect(routes.Wiki.wikieEdit(WID(cat, name)))
      })
  }

  // add a child/related to another topic
  def addLinked(cat: String, pwid: WID, role: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors => Msg2("Oops, can't add that name!", Some(pwid.urlRelative)),
      {
        case name: String => {
          val n = Wikis.formatName(WID(cat, name))
          Stage("WikiLink", WikiLink(WID(cat, n, pwid.findId), pwid, role).grated, auth.get.userName).create
          if (pwid.name.length == 0)
            Redirect(routes.Wiki.wikieEdit(WID(cat, name)))
          else
            Redirect(routes.Wiki.wikieEdit(WID(cat, name, pwid.findId)))
        }
      })
  }

  def link(fromCat: String, fromName: String, toCat: String, toName: String) = {
    if ("User" == fromCat) linkUser(WID(toCat, toName))
    else
      TODO
  }

  /** user unlikes page */
  def unlinkUser(wid: WID, really: String = "n") = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission("uProfile")
    ) yield {
      if (wid.cat == "Club" && really != "y") {
        Msg3(really + "Are you certain you want to leave club? You will not be able to follow calendars, register or see any of the forums etc...<p>Choose Leave only if certain.",
          Some(w(wid)),
          Some("Leave" -> s"/wikie/unlinkuser/${wid.wpath}?really=y"))
      } else {
        // if he was already, just say it
        au.pages(wid.cat).find(_.wid.name == wid.name).map { wl =>
          // TODO remove the comments page as well if any
          //        wl.wlink.page.map { wlp =>
          //          Redirect(routes.Wiki.wikieEdit(WID("WikiLink", wl.wname)))
          //        } 
          wl.delete
          cleanAuth()
          Msg2("OK, removed link!", Some("/"))
        } getOrElse {
          // need to link now
          Msg2("OOPS - you don't like this, nothing to unlike!", Some("/"))
        }
      }
    }) getOrElse
      noPerm(wid, "UNLINKUSER")
  }

  /** user 'likes' page - link the current user to the page */
  def linkUser(wid: WID, withComment: Boolean = false) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      // even new users that didn't verify their email can register for club
      r1 <- (au.hasPerm(Perm.uProfile) || "Club" == wid.cat) orCorr cNoPermission("uProfile")
    ) yield {
      def content = """[[User:%s | You]] -> [[%s:%s]]""".format(au.id, wid.cat, wid.name)

      // if he was already, just say it
      au.pages(wid.cat).find(_.wid.name == wid.name).map { wl =>
        wl.wlink.page.map { wlp =>
          Redirect(routes.Wiki.wikieEdit(WID("WikiLink", wl.wname)))
        } getOrElse {
          Msg2("Already added!", Some("/"))
        }
      } getOrElse {
        // need to link now
        Ok(views.html.wiki.wikiLink(WID("User", au.id), wid,
          linkForm.fill(LinkWiki("Enjoy", model.UW.EMAIL_EACH, Wikis.MD, content)), withComment, auth))
      }
    }) getOrElse
      noPerm(wid, "LINKUSER")
  }

  /** NEW user 'likes' page - link the current user to the page */
  def linkFollower1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    if (auth.isDefined) Redirect(routes.Wiki.linkUser(wid, false))
    else {
      (for (
        exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
        r1 <- canSee(wid, None, wid.page)
      ) yield {
        val v2 = (10 + math.random * 11).toInt
        Ok(views.html.wiki.wikiFollowerLink1(wid, v2, followerLinkForm.fill(FollowerLinkWiki("", "", 0, v2, ""))))
      }) getOrElse
        noPerm(wid, "LINKUSER")
    }
  }

  /** send email to confirm following */
  def linkFollower2(wid: WID) = Action { implicit request =>

    implicit val errCollector = new VError()

    followerLinkForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiFollowerLink1(wid, 21, formWithErrors)),
      {
        case we @ FollowerLinkWiki(email1, email2, v1, v2, comment) =>
          (for (
            exists <- wid.page.isDefined orErr ("Cannot link to non-existent page: " + wid.name);
            goodMath <- (v1 == v2 && v2 > 1 || v1 == 21) orErr {
              Audit.logdb("BAD_MATH", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
              ("Bad math")
            };
            r1 <- canSee(wid, None, wid.page)
          ) yield {
            val es = email1.enc
            if (model.Users.findFollowerLinksTo(wid).toList.flatMap(_.follower).exists(_.email == es)) {
              Msg2("You already subscribed with that email... Enjoy!", Some(wid.urlRelative))
            } else {
              Emailer.laterSession { implicit mailSession =>
                Emailer.sendEmailFollowerLink(email1, wid, comment)
                Emailer.tellRaz("Subscribed", email1 + " ip=" + request.headers.get("X-Forwarded-For"), wid.ahref, comment)
              }
              Msg2("You got an email with a link, to activate your subscription. Enjoy!", Some(wid.urlRelative))
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            unauthorized("Oops - cannot create this link... ")
          }
      })
  }

  /** clicked confirm in the email -> so follow */
  def linkFollower3(expiry: String, email: String, comment: String, wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    (for (
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      r1 <- canSee(wid, None, wid.page)
    ) yield {

      // TODO should I notify or ask the moderator?
      //            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })

      if (model.Users.findFollowerLinksTo(wid).toList.flatMap(_.follower).exists(_.email == email)) {
        Msg2("You already subscribed with that email... Enjoy!", Some(wid.urlRelative))
      } else {
        val f = model.Users.findFollowerByEmail(email).getOrElse {
          val newf = model.Follower(email, "")
          this dbop db.RCreate(newf)
          newf
        }
        this dbop db.RCreate(model.FollowerWiki(f._id, comment.decUrl, wid))

        Emailer.withSession { implicit mailSession =>
          Emailer.tellRaz("Subscription confirmed", email.dec, wid.ahref, comment.decUrl)
        }

        Msg2("Ok - you are subscribed to %s via email!".format(wid.page.map(_.label).getOrElse(wid.name)), Some(wid.urlRelative))
      }
    }) getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def unlinkFollower4(expiry: String, email: String, wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    (for (
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      r1 <- canSee(wid, None, wid.page)
    ) yield {
      //            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })
      //
      //            val f = model.Follower(email1, "??")
      //            this dbop f.create
      //            this dbop model.FollowerWiki(f._id, wid).create
      //            
      // TODO remove follower, possibly notify owner of topic or moderator
      Msg2("Sorry - please submit a support request...", Some(wid.urlRelative))
    }) getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def moderator(wid: WID) = Wikis.find(wid).flatMap(_.contentTags.get("moderator"))

  def linked(fromCat: String, fromName: String, toCat: String, toName: String, withComment: Boolean) = {
    if ("User" == fromCat) linkedUser(fromName, WID(toCat, toName), withComment)
    else
      TODO
  }

  // link a user for moderated club was approved
  private def createLinkedUser(au: User, wid: WID, withComment: Boolean, how: String, mark: String, comment: String)(implicit request: Request[_], txn: db.Txn) = {
    this dbop model.UserWiki(au._id, wid, how).create
    if (withComment) {
      val wl = model.WikiLink(WID("User", au.id), wid, how)
      wl.create
      model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
    }

    if (wid.cat == "Club")
      Club.linkUser(au, wid.name, how)

    cleanAuth(Some(au))
  }

  // link a user for moderated club was approved
  def linkAccept(expiry: String, userId: String, club: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VError()

    def hows = {
      Wikis.category("Club").flatMap(_.contentTags.get("roles:" + "User")) match {
        case Some(s) => s.split(",").toList
        case None => Wikis.pageNames("Link").toList
      }
    }

    import model.Sec._
    val wid = WID("Club", club)

    (for (
      // play 2.0 workaround - remove in play 2.1
      date <- (try { Option(DateTime.parse(expiry.dec)) } catch { case _: Throwable => (try { Option(DateTime.parse(expiry.replaceAll(" ", "+").dec)) } catch { case _: Throwable => None }) }) orErr ("token faked or expired");
      notExpired <- date.isAfterNow orCorr cExpired;
      user <- Users.findUserById(userId);
      isA <- checkActive(user);
      admin <- auth orCorr cNoAuth;
      modUname <- moderator(WID("Club", club));
      isMod <- (admin.hasPerm(Perm.adminDb) || admin.userName == modUname) orErr ("You do not have permission!!!");
      ok <- hows.contains(how) orErr ("invalid role");
      again <- (!user.wikis.exists(_.wid == wid)) orErr ("Aldready associated to club");
      c <- Club(club)
    ) yield {
      db.tx("linkUser.toWiki") { implicit txn =>
        ilinkAccept(user, c, how)
      }
      Msg2("OK, added!", Some("/"))
    }) getOrElse {
      error("ERR_CANT_LINK_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  // link a user for moderated club was approved
  private def ilinkAccept(user: User, club: Club, how: String)(implicit request: Request[_], txn: db.Txn) {
    // only if there is a club/user entry for that Club page
    val rk = model.RacerKidz.myself(user._id)
    model.RacerKidAssoc(club.userId, rk._id, model.RK.ASSOC_LINK, user.role, club.userId).create

    createLinkedUser(user, WID("Club", club.userName), false, how, "", "")

    if (!user.quota.updates.exists(_ > 10))
      user.quota.reset(50)

    Emailer.withSession { implicit mailSession =>
      Emailer.sendEmailLinkOk(user, club.userName)
      Emailer.tellRaz("User joined club", "Club: " + club.userName, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.email.dec}")
      club.props.filter(_._1.startsWith("link.notify.")).foreach { t =>
        Emailer.tell(t._2, "User joined club", "Club: " + club.userName, "Role: " + how, s"User: ${user.firstName} ${user.lastName} (${user.userName} ${user.email.dec}")
      }
    }
  }

  def linkDeny(userId: String, club: String, how: String) = Action { implicit request =>
    Emailer.withSession { implicit mailSession =>
      Emailer.sendEmailLinkDenied(Users.findUserById(userId).get, club)
    }
    Msg2("OK, denied!", Some("/"))
  }

  /** a user linked to a WID */
  def linkedUser(userId: String, wid: WID, withComment: Boolean) = Action { implicit request =>

    razie.clog << s"METHOD linkedUser($userId, $wid, $withComment)"

    def hows = {
      Wikis.category(wid.cat).flatMap(_.contentTags.get("roles:" + "User")) match {
        case Some(s) => s.split(",").toList
        case None => Wikis.pageNames("Link").toList
      }
    }

    implicit val errCollector = new VError()

    linkForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiLink(WID("User", auth.get.id), wid, formWithErrors, withComment, auth)),
      {
        case we @ LinkWiki(how, notif, mark, comment) =>
          (for (
            au <- activeUser;
            isMe <- (au.id equals userId) orErr {
              Audit.security("Another user tried to link...", userId, au.id)
              ("invalid user")
            };
            page <- wid.page orErr (s"Page $wid not found");
            ok <- hows.contains(how) orErr ("invalid role");
            xxx <- Some("")
          ) yield {
            db.tx("wiki.linkeduser") { implicit txn =>
              val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })

              if ("Club" == wid.cat && mod.isDefined) {
                if (Club(wid.name).exists(_.props.get("link.auto").mkString == "yes")) {
                  ilinkAccept(au, Club(wid.name).get, how)
                  Msg2("OK, added!", Some("/"))
                } else {
                  Emailer.withSession { implicit mailSession =>
                    Emailer.sendEmailLink(mod.get, au, wid.name, how)
                  }
                  Msg2(s"An email has been sent to the moderator of <strong>${page.label}</strong>, you will receive an email when they're done!",
                    Some("/"))
                }
              } else {
                model.UserWiki(au._id, wid, how).create
                if (withComment) {
                  val wl = model.WikiLink(WID("User", au.id), wid, how)
                  wl.create
                  model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
                }
                cleanAuth()
                Msg2("OK, added!", Some("/"))
              }
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            unauthorized("Oops - cannot create this link... ")
          }
      })
  }

  /** change owner */
  def uowner(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    val uownerForm = Form("newowner" -> nonEmptyText)

    uownerForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
        case newowner =>
          log("Wiki.uowner " + wid + ", " + newowner)
          (for (
            au <- activeUser;
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            w <- Wikis.find(wid);
            newu <- model.WikiUsers.impl.findUserByUsername(newowner) orErr (newowner + " User not found");
            nochange <- (!w.owner.exists(_.userName == newowner)) orErr ("no change");
            newVer <- Some(w.cloneProps(w.props + ("owner" -> newu._id.toString), au._id));
            upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_UOWNER) orErr ("Not allowerd")
          ) yield {
            // can only change label of links OR if the formatted name doesn't change
            db.tx("Wiki.uowner") { implicit txn =>
              w.update(newVer)
            }
            Notif.entityUpdateAfter(newVer, WikiEntry.UPD_UOWNER)
            Redirect(controllers.Wiki.w(wid))
          }) getOrElse
            noPerm(wid, "ADMIN_UOWNER")
      })
  }

  /** mark a wiki as reserved - only admin can edit */
  def reserve(wid: WID, how: Boolean) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.reserve " + wid)
    (for (
      au <- activeUser;
      w <- Wikis.find(wid);
      ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
      ok2 <- canEdit(wid, Some(au), Some(w));
      nochange <- (w.isReserved != how) orErr ("no change");
      newVer <- Some(w.cloneProps(w.props + ("reserved" -> (if (how) "yes" else "no")), au._id));
      upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_TOGGLE_RESERVED) orErr ("Not allowerd")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      db.tx("Wiki.Reserve") { implicit txn =>
        w.update(newVer)
      }
      Notif.entityUpdateAfter(newVer, WikiEntry.UPD_TOGGLE_RESERVED)
      Redirect(controllers.Wiki.w(wid))
    }) getOrElse
      noPerm(wid, "ADMIN_RESERVE")
  }

  private def canDelete(wid: WID)(implicit errCollector: VError, request: Request[_]) = {
    for (
      au <- activeUser;
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w));
      ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission
    ) yield (au, w)
  }

  /** delete step 1: confirm */
  def wikieDelete1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.delete1 " + wid)
    canDelete(wid).collect {
      case (au, w) =>
        Msg2C(
          "DELETE forever - are you sure? TODO show/remove links FROM, links TO, Wikientry.parent==me etc, userWiki=me",
          Some(routes.Wiki.wikieDelete2(wid)))
    } getOrElse
      noPerm(wid, "ADMIN_DELETE1")
  }

  /** delete step 2: do it */
  def wikieDelete2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.delete2 " + wid)
    if (wid.cat != "Club") canDelete(wid).collect {
      case (au, w) =>
        db.tx("Wiki.delete") { implicit txn =>
          RMany[WikiLink]("to" -> wid.grated).toList.foreach(_.delete)
          RMany[WikiLink]("from" -> wid.grated).toList.foreach(_.delete)
          var done = false
          RMany[UserWiki]("wid" -> wid.grated).toList.foreach(wl => {
            wl.delete
            done = true
          })

          // can only change label of links OR if the formatted name doesn't change
          // delete at last, so if any links fail, the thing stays there
          w.delete(au.userName)

          if (done) cleanAuth() // it probably belongs to the current user, cached...
        }
        Msg2("DELETED forever - no way back!")
    } getOrElse
      noPerm(wid, "ADMIN_DELETE2")
    else
      Msg2("Can't delete a " + wid.cat)
  }

  private def canRename(wid: WID)(implicit errCollector: VError, request: Request[_]) = {
    for (
      au <- activeUser;
      ok <- ("WikiLink" != wid.cat && "User" != wid.cat) orErr ("can't rename this category");
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w))
    ) yield (au, w)
  }

  /** rename step 1: form for new name */
  def wikieRename1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    canRename(wid).collect {
      case (au, w) =>
        Ok(views.html.wiki.wikiRename(wid, renameForm.fill((w.label, w.label)), auth))
    } getOrElse
      noPerm(wid, "RENAME")
  }

  /** rename step 2: do it */
  def wikieRename2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    renameForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiRename(wid, formWithErrors, auth)),
      {
        case (_, n) =>
          canRename(wid).collect {
            case (au, w) =>
              val newp = (w.cloneRenamed(n))
              db.tx("Wiki.Rename") { implicit txn =>
                w.update(newp)

                RMany[WikiLink]("to" -> wid.grated).toList.map(wl => {
                  wl.delete
                  WikiLink(wl.from, newp.wid, wl.how).create
                })

                RMany[WikiLink]("from" -> wid.grated).toList.map(wl => {
                  wl.delete
                  WikiLink(newp.wid, wl.to, wl.how).create
                })

                var done = false
                RMany[UserWiki]("wid" -> wid.grated).toList.map(wl => {
                  wl.delete
                  UserWiki(wl.userId, newp.wid, wl.role).create
                  done = true
                })

                if (done) cleanAuth() // it probably belongs to the current user, cached...
              }

              Msg("OK, renamed!", WID(wid.cat, Wikis.formatName(n)))
          } getOrElse
            noPerm(wid, "RENAME2")
      })
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
        case we: WikiWrapper => (we.wid, we.wid.name) 
        }

      Ok(views.html.wiki.wikiList(path, res, auth))
    }) getOrElse
      Ok("Nothing... for " + wid + " XP " + path)
  }

  /** wid is the script name,his parent is the actual topic */
  def wikieApiCall(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
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
    implicit val errCollector = new VError()
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
  def wikieAdd(cat: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      w <- Wikis.category(cat)
    ) yield {
      Ok(views.html.wiki.wikieAdd(w.wid, Some(w), auth))
    }) getOrElse unauthorized()
  }

  /** try to link to something - find it */
  def wikieCreate(cat: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      w <- Wikis.category(cat)
    ) yield {
      Ok(views.html.wiki.wikieCreate(w.wid, Some(w), auth))
    }) getOrElse unauthorized()
  }

  /** try to link to something - find it */
  def social(label:String, url:String) = Action { implicit request =>
    Ok(views.html.wiki.social(label, url, auth))
  }
}
