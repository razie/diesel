package controllers

import scala.Array.canBuildFrom

import org.joda.time.DateTime

import com.mongodb.DBObject
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater

import admin.Audit
import admin.Config
import admin.Corr
import admin.IgnoreErrors
import admin.MailSession
import admin.Notif
import admin.SendEmail
import admin.VError
import model.CMDWID
import model.Enc
import model.Mongo
import model.Perm
import model.RazSalatContext.ctx
import model.Sec.toENCR
import model.Stage
import model.User
import model.UserType
import model.UserWiki
import model.Users
import model.WID
import model.WikiAudit
import model.WikiCount
import model.WikiDomain
import model.WikiEntry
import model.WikiIndex
import model.WikiLink
import model.WikiWrapper
import model.WikiXpSolver
import model.Wikis
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging

/** wiki controller */
object Wiki extends RazController with Logging {

  case class EditWiki(label: String, markup: String, content: String, visibility: String, edit: String, tags: String)

  val editForm = Form {
    mapping(
      "label" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "markup" -> nonEmptyText.verifying("Unknown!", Wikis.markups.contains(_)),
      "content" -> nonEmptyText,
      "visibility" -> nonEmptyText,
      "wvis" -> nonEmptyText,
      "tags" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)))(EditWiki.apply)(EditWiki.unapply) verifying (
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

  case class AddWiki(name: String)

  val addForm = Form {
    mapping(
      "name" -> nonEmptyText)(AddWiki.apply)(AddWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: AddWiki => !Wikis.hasporn(ew.name)
        })
  }

  case class LinkWiki(how: String, markup: String, comment: String)

  def linkForm(implicit request: Request[_]) = Form {
    mapping(
      "how" -> nonEmptyText,
      "markup" -> text.verifying("Unknown!", request.queryString("wc").headOption.exists(_ == "0") || Wikis.markups.contains(_)),
      "comment" -> text)(LinkWiki.apply)(LinkWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: LinkWiki => !Wikis.hasporn(ew.comment)
        })
  }

  // profile
  val renameForm = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name already in use", { t: (String, String) => WikiIndex.withIndex { index => !index.idx.contains(Wikis.formatName(t._2)) }
      })
  }

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      cat <- request.queryString("cat").headOption;
      name <- request.queryString("name").headOption
    ) yield wikieEdit(WID(cat, name)).apply(request)) getOrElse {
      error("ERR_HACK Wiki.email2")
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  def isInSameClub(member: User, owner: User) = {//}(implicit errCollector: VError = IgnoreErrors) = {
    // all clubs where member
    val m1 = member.wikis.filter(x => x.wid.cat == "Club" && x.role != "Fan").toList

    println (member)
    println (owner)
    
    (
      // owner is the club
      (owner.roles.contains(UserType.Organization.toString) && m1.exists(_.wid.name == owner.userName)) ||
      // owner is someone else => club lists intersect?
      (! owner.roles.contains(UserType.Organization.toString) && {
        val m2 = member.wikis.filter(x => x.wid.cat == "Club" && x.role != "Fan").toList
        m1.exists(x1 => m2.exists (_.wid.name == x1.wid.name))
      }) 
    )
  }

  def isVisible(u: Option[User], props: Map[String, String], visibility: String = "visibility")(implicit errCollector: VError = IgnoreErrors) = {
    // TODO optimize
    def uname(id: Option[String]) = id.flatMap(Users.findUserById(_)).map(_.userName).getOrElse(id.getOrElse(""))

    (!props.get(visibility).isDefined) || u.exists(_.hasPerm(Perm.adminDb)) ||
      (props(visibility) == "Public") || // if changing while edit, it will have a value even when public
      (u.isDefined orCorr cNoAuth).exists(_ == true) && // anything other than public needs logged in
      (
          props(visibility) == "Private" && u.isDefined && props.get("owner") == Some(u.get.id) ||
        (
          props(visibility) == "Club" && u.isDefined && (
            props.get("owner") == Some(u.get.id) || props.get("owner").flatMap(Users.findUserById(_)).exists(c => isInSameClub(u.get, c))) orCorr (
              cNotMember(uname(props.get("owner"))))).getOrElse(
                u.exists(_.hasPerm(Perm.adminDb))))
  }

  def canSee(wid: WID, au: Option[User], w: Option[WikiEntry])(implicit errCollector: VError) = {
    lazy val isAdmin = au.exists(_.hasPerm(Perm.adminDb))
    lazy val we = if (w.isDefined) w else Wikis.find(wid)
    val cat = wid.cat
    val name = wid.name
    (for (
      pubProfile <- ("User" != cat || WikiIndex.withIndex(_.get2(name, wid).isDefined) || au.map(name == _.userName).getOrElse(isAdmin)) orErr ("Sorry - profile not found or is private! %s : %s".format(cat, name));
      mine2 <- (!we.isDefined || isVisible(au, we.get.props)) orErr ("Sorry - topic is not visible!"); // TODO report
      t <- true orErr ("just can't, eh")
    ) yield true)
    // TODO parent can see child's profile
  }

  final val corrVerified = new Corr("not verified", """Sorry - you need to <a href="/user/task/verifyEmail">verify your email address</a>, to create or edit public topics.\n If you already did, please describe the issue in a  <a href="/doe/support?desc=email+already+verified">support request</a> below.""");

  /** call canEdit ignoring errors */
  def canEdit2(wid: WID, u: Option[User], w: Option[WikiEntry]) = {
    implicit val errCollector = IgnoreErrors
    canEdit(wid, u, w)
  }

  private def wvis(props: Option[Map[String, String]]): Option[String] =
    props.flatMap(p => p.get("wvis").orElse(p.get("visibility"))).map(_.asInstanceOf[String])

  def canEdit(wid: WID, u: Option[User], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VError) = {
    val cat = wid.cat
    val name = wid.name
    lazy val we = if (w.isDefined) w else Wikis.find(cat, name)
    lazy val wprops = if (we.isDefined) we.map(_.props) else props
    if (u.isDefined && u.exists(_.hasPerm(Perm.adminDb)))
      Some(true)
    else (for (
      cansee <- canSee(wid, u, w);
      au <- u orCorr cNoAuth;
      isA <- checkActive(au);
      r1 <- ("Category" != cat || au.hasPerm(Perm.adminWiki)) orErr ("no permission to edit a Category");
      r2 <- ("Admin" != cat || au.hasPerm(Perm.adminWiki)) orErr ("no permission to edit an Admin entry");
      mine <- ("User" != cat || name == au.userName) orErr ("Can only edit your own public profile!");
      mine1 <- ("User" != cat || au.canHasProfile) orErr ("Sorry - you cannot have a public profile - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=parent+should+allow\">support request</a> below.");
      mine2 <- ("WikiLink" == cat || au.canHasProfile) orErr ("Sorry - you cannot create or edit public topics - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=cannot+have+public+profile\">support request</a> below.");
      pro <- au.profile orCorr cNoProfile;
      verif <- ("WikiLink" == cat || "User" == cat || au.hasPerm(Perm.eVerified)) orCorr corrVerified;
      res <- (!w.exists(_.isReserved) || au.hasPerm(Perm.adminWiki) || "User" == wid.cat) orErr ("Category is reserved");
      owner <- !(WikiDomain.needsOwner(cat)) ||
        we.exists(_.isOwner(au.id)) ||
        (wprops.flatMap(_.get("wvis")).isDefined && isVisible(u, wprops.get, "wvis")) ||
        wprops.flatMap(_.get("visibility")).exists(_ == "Club" && isVisible(u, wprops.get, "visibility")) ||
        !wvis(wprops).isDefined orErr ("Sorry - you are not the owner of this topic");
      memod <- (w.flatMap(_.contentTags.get("moderator")).map(_ == au.userName).getOrElse(true)) orErr ("Sorry - this is moderated and you are not the moderator, are you?");
      t <- true orErr ("can't")
    ) yield true)
  }

  //  def widFromPath(path: String): Option[WID] = WID fromPath path

  /** serve page for edit */
  def wikieEdit(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    val n = Wikis.formatName(wid)

    debug("wikieEdit " + wid)

    Wikis.find(wid) match {
      case Some(w) =>
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), Some(w));
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Ok(views.html.wiki.wikiEdit(w.wid, editForm.fill(
            EditWiki(w.label,
              w.markup,
              w.content,
              (w.props.get("visibility").getOrElse("Public")),
              wvis(Some(w.props)).getOrElse("Public"),
              w.tags.mkString(","))),
            auth))
        }) getOrElse
          noPerm(wid, "EDIT")
      case None =>
        val parentProps = wid.findParent.map(_.props)
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), None, parentProps);
          r3 <- ("any" != wid.cat) orErr ("can't create in category any");
          w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Audit.missingPage("wiki " + wid);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(wid, Wikis.MD, wid.name)
          val tags = preprocessed.tags
          val contentFromTags = tags.foldLeft("") { (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          val visibility = wid.findParent.flatMap(_.props.get("visibility")).getOrElse(Wikis.visibilityFor(wid.cat).headOption.getOrElse("Public"))
          val wwvis = wvis(wid.findParent.map(_.props)).getOrElse(Wikis.visibilityFor(wid.cat).headOption.getOrElse("Public"))

          Ok(views.html.wiki.wikiEdit(wid, editForm.fill(
            EditWiki(wid.name.replaceAll("_", " "),
              Wikis.MD,
              contentFromTags + "Edit content here",
              visibility,
              wwvis,
              (if ("Topic" == wid.cat) "" else wid.cat.toLowerCase))),
            auth))
        }) getOrElse
          noPerm(wid, "EDIT")
    }
  }

  def save(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    editForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.wiki.wikiEdit(wid, formWithErrors, auth))
      },
      {
        case we @ EditWiki(l, m, co, vis, wvis, tags) => {
          log("Wiki.save " + wid)
          Wikis.find(wid) match {
            case Some(w) =>
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, Some(w));
                r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
                hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
                nochange <- (w.label != l || w.markup != m || w.content != co || (
                  w.props.get("visibility").map(_ != vis).getOrElse(vis != "Public") ||
                  w.props.get("wvis").map(_ != wvis).getOrElse(wvis != "Public")) ||
                  w.tags.mkString(",") != tags) orErr ("no change");
                newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
                newVer <- Some(w.cloneNewVer(newlab, m, co, au._id));
                upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr ("Not allowerd")
              ) yield {
                var we = newVer

                // visibility?
                if (we.props.get("visibility").map(_ != vis).getOrElse(vis != "Public"))
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                if (we.props.get("wvis").map(_ != wvis).getOrElse(wvis != "Public"))
                  we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)

                if (we.tags.mkString(",") != tags)
                  we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

                // signing scripts
                if (au.hasPerm(Perm.adminDb)) {
                  if (!we.scripts.filter(_.signature == "REVIEW").isEmpty) {
                    var c2 = we.content
                    for (s <- we.scripts.filter(_.signature == "REVIEW")) {
                      def sign(s: String) = Enc apply Enc.hash(s)

                      c2 = we.PATTSIGN.replaceSomeIn(c2, { m =>
                        if (s.name == (m group 2)) Some("{{%s:%s:%s}}%s{{/%s}}".format(
                          m group 1, m group 2, sign(s.content), s.content.replaceAll("""\\""", """\\\\"""), m group 1))
                        else None
                      })
                    }
                    we = we.cloneContent(c2)
                  }
                }

                if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !au.hasPerm(Perm.adminDb)) {
                  noPerm(wid, "HACK_SCRIPTS1")
                } else {
                  // can only change label of links OR if the formatted name doesn't change
                  w.update(we)
                  Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
                  Emailer.withSession { implicit mailSession =>
                    au.quota.incUpdates
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                  WikiAudit("EDIT", w.wid.wpath, Some(au._id)).create
                  Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
                }
              }) getOrElse
                noPerm(wid, "HACK_SAVEEDIT")
            case None =>
              // create a new topic
              val parentProps = wid.findParent.map(_.props)
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, None, parentProps);
                hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
                r3 <- ("any" != wid.cat) orErr ("can't create in category any");
                w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
                r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission
              ) yield {
                var we = model.WikiEntry(wid.cat, wid.name, l, m, co, au._id, 1, wid.parent)

                if (we.tags.mkString(",") != tags)
                  we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

                // needs owner?
                if (model.Wikis.category(wid.cat).flatMap(_.contentTags.get("roles:" + "User")).map(_.split(",")).exists(_.contains("Owner"))) {
                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                  //                  val wl = model.WikiLink(WID("User", au.id), we.wid, "Owner")
                  //                  wl.create
                  this dbop model.UserWiki(au._id, wid, "Owner").create

                  RazController.cleanAuth()
                }

                // visibility?
                if (vis != "Public")
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)

                // anything staged for this?
                for (s <- model.Staged.find("WikiLink").filter(x => x.content.get("from").asInstanceOf[DBObject].get("cat") == wid.cat && x.content.get("from").asInstanceOf[DBObject].get("name") == wid.name)) {
                  val wl = Wikis.fromGrated[WikiLink](s.content)
                  wl.create
                  we = we.cloneParent(Wikis.find(wl.to).map(_._id)) // should still change
                  s.delete
                }

                // needs parent?
                we.wid.parentWid.foreach { pwid =>
                  if (Mongo("WikiLink").findOne(Map("from" -> we.wid.grated, "to" -> pwid.grated, "how" -> "Child")).isEmpty)
                    WikiLink(we.wid, pwid, "Child").create
                }

                we.create
                WikiAudit("CREATE", we.wid.wpath, Some(au._id)).create

                admin.SendEmail.withSession { implicit mailSession =>
                  au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)
                  notifyFollowersCreate(we, au)
                }

                Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
              }) getOrElse
                noPerm(wid, "HACK_SAVEEDIT")
          }
        }
      })
  }

  private def notifyFollowersCreate(wpost: WikiEntry, au: User)(implicit mailSession: MailSession) = {
    // 1. followers of this topic or followers of parent

    wpost.parent flatMap (Wikis.find(_)) map { w =>
      (Users.findUserById(w.by).map(_._id).toList ++ w.userWikis.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).map(u => Emailer.sendEmailNewTopic(u, au, w.wid, wpost)))
    }
  }

  def search(q: String) = Action { implicit request =>
    // TODO make case-insensitive
    // TODO limit the number of searches - is this performance critical?
    val wikis = Mongo.withDb(Mongo.db("WikiEntry")) { t =>
      for (u <- t if ((q.length > 1 && u.get("name").asInstanceOf[String].contains(q)) || (q.length() > 3 && u.get("content").asInstanceOf[String].contains(q)))) yield u
    }.toList

    if (wikis.count(x => true) == 1)
      Redirect(controllers.Wiki.w(WikiEntry.grated(wikis.head).wid))
    else
      Ok(views.html.wiki.wikiList(q, wikis.map(WikiEntry.grated _).toList, auth))
  }

  private def topicred(wpath: String) = {
    if (Config.config(Config.TOPICRED).exists(_.contains(wpath))) {
      log("- redirecting " + wpath)
      Some(Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wpath)).get)))
    } else
      None
  }

  def showWid(cw: CMDWID, count: Int) = {
    if (cw.cmd == "xp") xp(cw.wid.get, cw.rest)
    else Action { implicit request =>
      // must check if page is WITHIN site, otherwise redirect to main site
      val fhost = request.headers.get("X-FORWARDED-HOST")
//      val fhost=Some("glacierskiclub.com")    // for testing locally
      val redir = fhost flatMap (Config.urlfwd(_))

      if (fhost.exists(_ != Config.hostport) &&
          redir.isDefined &&
          !cw.wpath.get.startsWith(redir.get.replaceFirst(".*/wiki/", ""))) {
        log("  REDIRECTED FROM - " + fhost)
        log("    TO http://" + Config.hostport + "/wiki/" + cw.wpath.get)
        Redirect("http://" + Config.hostport + "/wiki/" + cw.wpath.get)
      } else {
        // normal - continue showing the page
        show(cw.wid.get, count).apply(request)
      }
    }
  }

  //  def show2c(cat: String, name: String, colon: String = ":", cat1: String, name1: String, c1: String, count: Int) = Action { implicit request =>
  //    val redWpath = cat + ":" + name + "/" + cat1 + ":" + name1
  //    log("show2c " + redWpath)
  //    val topic = WID.fromPath(redWpath).flatMap(Wikis.find)
  //    topic.map { w =>
  //      showPage(w, count)
  //    } getOrElse {
  //      topicred(redWpath) getOrElse Msg2("Topic %s Not found!".format(redWpath))
  //    }
  //  }

  //  def show2(cat: String, name: String, colon: String = ":", count: Int) = show(WID(cat, name), count)

  def show1(cat: String, name: String) = show(WID(cat, name))
  def showId(id: String) = Action { implicit request =>
    (for (w <- Wikis.findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(we: WID, shouldCount: Boolean = true) = Config.urlmap("/wiki/" + we.wpath + (if (!shouldCount) "?count=0" else ""))

  def w(cat: String, name: String) = Config.urlmap("/wiki/%s:%s".format(cat, name))
  def w(name: String) = Config.urlmap("/wiki/" + name)

  def call[A, B](value: A)(f: A => B) = f(value)

  /** serve a site */
  def site(name: String) = show(WID("Site", name))

  def wikieShow(iwid: WID, count: Int = 0) = show(iwid, count)

  def show(iwid: WID, count: Int = 0) = Action { implicit request =>
    implicit val errCollector = new VError()

    val shouldCount = !(request.flash.get("count").exists("0" == _) || (count == 0))

    debug("show2 " + iwid.wpath)
    // TODO stupid routes - can't match without the :
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    val wid = WID(cat, name, iwid.parent)

    razie.NoStaticS.put(model.QueryParms(request.queryString))

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("any" == cat || cat.isEmpty) {
      val wl = Wikis.findAny(name).filter(page => canSee(page.wid, auth, Some(page)).getOrElse(false)).toList
      if (wl.size == 1)
        // redirect to use the proper Category display
        // TODO this is fucked up
        Redirect(controllers.Wiki.w(wl.head.wid))
      else if (wl.size > 0)
        Ok(views.html.wiki.wikiList("category any", wl, auth))
      else
        wikiPage(wid, Some(iwid.name), None, auth, shouldCount)
    } else {
      // normal request with cat and name
      val w = Wikis.find(cat, name)

      if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(wid.wpath))) {
        log("- redirecting " + wid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(wid.wpath)).get))
      } else if (!w.isDefined && Config.config(Config.TOPICRED).exists(_.contains(iwid.wpath))) {
        // specifically when rules changes- the reformated wid not longer working, try original
        log("- redirecting " + iwid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Config.config(Config.TOPICRED).get.apply(iwid.wpath)).get))
      } else {
        // finally there!!
        if (!canSee(wid, auth, w).getOrElse(false))
          noPerm(wid, "SHOW")
        else
          w.map { w =>
            // redirect a simple alias with no other content
            w.alias.map { wid =>
              Redirect(controllers.Wiki.w(wid.formatted))
            } getOrElse
              //            else if (w.findParent.isDefined)
              //              Redirect(controllers.Wiki.w(w.wid))
              //            else
              wikiPage(wid, Some(iwid.name), Some(w), auth, shouldCount)
          } getOrElse
            wikiPage(wid, Some(iwid.name), None, auth, shouldCount)
      }
    }
  }

  private def wikiPage(wid: model.WID, iname: Option[String], page: Option[model.WikiEntry], user: Option[model.User], shouldCount: Boolean) = {
    if (shouldCount) page.foreach { p =>
      WikiAudit("SHOW", p.wid.wpath, user.map(_._id)).create
      WikiCount(p._id).inc
    }

    if (Array("Site", "Page").contains(wid.cat) && page.isDefined)
      Ok(views.html.wiki.wikiSite(wid, iname, page, user))
    else
      Ok(views.html.wiki.wikiPage(wid, iname, page, user))
  }

  def wikieDebug(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    // TODO stupid routes - can't match without the :
    val cat = iwid.cat //if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
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
        Oops("You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page...", wid)
      }
    }
  }

  def reported(wid: WID) = Action { implicit request =>
    reportForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiReport(wid, formWithErrors, auth)),
      {
        case we @ ReportWiki(reason) =>
          Wikis.flag(wid, "reported by user: " + auth + " BECAUSE " + reason)
          SendEmail.withSession { implicit session =>
            SendEmail.send(SUPPORT, SUPPORT, "WIKI_FLAGGED", "link: <a href=\"" + w(wid) + "\">here</a> reported by user " + reason)
          }
      })
    Msg("OK, reported!", wid)
  }

  // from category - add a ... 
  def add(cat: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors => Msg2("Oops, can't add that name!", Some("/wiki/" + cat)),
      {
        case we @ AddWiki(name) => Redirect(routes.Wiki.wikieEdit(WID(cat, name)))
      })
  }

  // add a child/related to another topic
  def addLinked(cat: String, pwid: WID, role: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors => Msg2("Oops, can't add that name!", Some("/wiki/" + pwid.wpath)),
      {
        case we @ AddWiki(name) => {
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
  def unlinkUser(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      isA <- checkActive(au);
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission
    ) yield {
      // if he was already, just say it
      au.pages(wid.cat).find(_.wid.name == wid.name).map { wl =>
        // TODO remove the comments page as well if any
        //        wl.wlink.page.map { wlp =>
        //          Redirect(routes.Wiki.wikieEdit(WID("WikiLink", wl.wname)))
        //        } 
        this dbop wl.delete
        RazController.cleanAuth()
        Msg2("OK, removed link!", Some("/"))
      } getOrElse {
        // need to link now
        Msg2("OOPS - you don't like this, nothing to unlike!", Some("/"))
      }
    }) getOrElse
      noPerm(wid, "UNLINKUSER")
  }

  /** user 'likes' page - link the current user to the page */
  def linkUser(wid: WID, withComment: Boolean = false) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      isA <- checkActive(au);
      exists <- wid.page.isDefined orErr ("Cannot link to " + wid.name);
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission
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
          linkForm.fill(LinkWiki("Enjoy", Wikis.MD, content)), withComment, auth))
      }
    }) getOrElse
      noPerm(wid, "LINKUSER")
  }

  def moderator(wid: WID) = Wikis.find(wid).flatMap(_.contentTags.get("moderator"))

  def linked(fromCat: String, fromName: String, toCat: String, toName: String, withComment: Boolean) = {
    if ("User" == fromCat) linkedUser(fromName, WID(toCat, toName), withComment)
    else
      TODO
  }

  // link a user for moderated club was approved
  private def createLinkedUser(au: User, wid: WID, withComment: Boolean, how: String, mark: String, comment: String)(implicit request: Request[_]) = {
    this dbop model.UserWiki(au._id, wid, how).create
    if (withComment) {
      val wl = model.WikiLink(WID("User", au.id), wid, how)
      wl.create
      model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
    }
    RazController.cleanAuth(Some(au))
  }

  def linkedUserAccept(expiry: String, userId: String, club: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    def hows = {
      Wikis.category("Club").flatMap(_.contentTags.get("roles:" + "User")) match {
        case Some(s) => s.split(",").toList
        case None => Wikis.pageNames("Link").toList
      }
    }

    import model.Sec._

    (for (
      // play 2.0 workaround - remove in play 2.1
      date <- (try { Option(DateTime.parse(expiry.dec)) } catch { case _ => (try { Option(DateTime.parse(expiry.replaceAll(" ", "+").dec)) } catch { case _ => None }) }) orErr ("token faked or expired");
      notExpired <- date.isAfterNow orCorr cExpired;
      au <- Users.findUserById(userId);
      admin <- auth orCorr cNoAuth;
      modUname <- moderator(WID("Club", club));
      isMod <- (admin.hasPerm(Perm.adminDb) || admin.userName == modUname) orErr ("You do not have permission!!!");
      isA <- checkActive(au);
      ok <- hows.contains(how) orErr ("invalid role")
    ) yield {
      createLinkedUser(au, WID("Club", club), false, how, "", "")
      Emailer.withSession { implicit mailSession =>
        Emailer.sendEmailLinkOk(au, club)
      }
      Msg2("OK, added!", Some("/"))
    }) getOrElse {
      error("ERR_CANT_LINK_USER " + session.get("email"))
      unauthorized("Oops - cannot create this link... ")
    }
  }

  def linkedUserDeny(userId: String, club: String, how: String) = Action { implicit request =>
    Emailer.withSession { implicit mailSession =>
      Emailer.sendEmailLinkDenied(Users.findUserById(userId).get, club)
    }
    Msg2("OK, denied!", Some("/"))
  }

  def linkedUser(userId: String, wid: WID, withComment: Boolean) = Action { implicit request =>

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
        case we @ LinkWiki(how, mark, comment) =>
          (for (
            au <- auth orCorr cNoAuth;
            isA <- checkActive(au);
            isMe <- (au.id equals userId) orErr {
              Audit.security("Another user tried to link...", userId, au.id)
              ("invalid user")
            };
            ok <- hows.contains(how) orErr ("invalid role")
          ) yield {
            println("=====")
            val mod = moderator(wid).flatMap(mid => { println(mid); Users.findUserByUsername(mid) })

            if ("Club" == wid.cat && mod.isDefined) {
              Emailer.withSession { implicit mailSession =>
                Emailer.sendEmailLink(mod.get, au, wid.name, how)
              }
              Msg2("An email has been sent to the moderator, you will receive an email when they're done!", Some("/"))
            } else {
              this dbop model.UserWiki(au._id, wid, how).create
              if (withComment) {
                val wl = model.WikiLink(WID("User", au.id), wid, how)
                wl.create
                model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au._id).cloneProps(Map("owner" -> au.id), au._id).create
              }
              RazController.cleanAuth()
              Msg2("OK, added!", Some("/"))
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            unauthorized("Oops - cannot create this link... ")
          }
      })
  }

  def reserve(wid: WID, how: Boolean) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.reserve " + wid)
    (for (
      au <- auth orCorr cNoAuth;
      w <- Wikis.find(wid);
      ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
      ok2 <- canEdit(wid, Some(au), Some(w));
      nochange <- (w.isReserved != how) orErr ("no change");
      newVer <- Some(w.cloneProps(w.props + ("reserved" -> (if (how) "yes" else "no")), au._id));
      upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_TOGGLE_RESERVED) orErr ("Not allowerd")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      w.update(newVer)
      Notif.entityUpdateAfter(newVer, WikiEntry.UPD_TOGGLE_RESERVED)
      Redirect(controllers.Wiki.w(wid))
    }) getOrElse
      noPerm(wid, "ADMIN_RESERVE")
  }

  private def canDelete(wid: WID)(implicit errCollector: VError, request: Request[_]) = {
    for (
      au <- auth orCorr cNoAuth;
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
    canDelete(wid).collect {
      case (au, w) =>
        // can only change label of links OR if the formatted name doesn't change
        w.delete(au.userName)
        Mongo.withDb(Mongo.db("WikiLink")) { m =>
          m.find(Map("to" -> wid.grated)).map(grater[WikiLink].asObject(_)).toList.map(wl => {
            wl.delete
          })

          m.find(Map("from" -> wid.grated)).map(grater[WikiLink].asObject(_)).toList.map(wl => {
            wl.delete
          })
        }
        Mongo.withDb(Mongo.db("UserWiki")) { m =>
          var done = false
          m.find(Map("wid" -> wid.grated)).map(grater[UserWiki].asObject(_)).toList.map(wl => {
            wl.delete
            done = true
          })
          if (done) RazController.cleanAuth() // it probably belongs to the current user, cached...
        }
        Msg2("DELETED forever - no way back!")
    } getOrElse
      noPerm(wid, "ADMIN_DELETE2")
  }

  private def canRename(wid: WID)(implicit errCollector: VError, request: Request[_]) = {
    for (
      au <- auth orCorr cNoAuth;
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
              w.update(newp)
              Mongo.withDb(Mongo.db("WikiLink")) { m =>
                m.find(Map("to" -> wid.grated)).map(grater[WikiLink].asObject(_)).toList.map(wl => {
                  wl.delete
                  WikiLink(wl.from, newp.wid, wl.how).create
                })

                m.find(Map("from" -> wid.grated)).map(grater[WikiLink].asObject(_)).toList.map(wl => {
                  wl.delete
                  WikiLink(newp.wid, wl.to, wl.how).create
                })
              }
              Mongo.withDb(Mongo.db("UserWiki")) { m =>
                var done = false
                m.find(Map("wid" -> wid.grated)).map(grater[UserWiki].asObject(_)).toList.map(wl => {
                  wl.delete
                  UserWiki(wl.userId, newp.wid, wl.role).create
                  done = true
                })
                if (done) RazController.cleanAuth() // it probably belongs to the current user, cached...
              }
              Msg("OK, renamed!", WID(wid.cat, Wikis.formatName(n)))
          } getOrElse
            noPerm(wid, "RENAME2")
      })
  }

  import play.api.libs.json._

  def xpold(cat: String, name: String, c: String, path: String) = xp(WID(cat, name), path)

  def xp(wid: WID, path: String) = Action { implicit request =>
    (for (
      w <- Wikis.find(wid)
    ) yield {
      val node = new WikiWrapper(wid)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      val res: List[String] = if (razie.GPath(path).isAttr) (root \\@ path) else (root \ path).nodes.map(_.toString)
      Ok(Json.toJson(res))
    }) getOrElse
      Ok("Nothing... for " + wid + " XP " + path)
  }

  /** wid is the script name,his parent is the actual topic */
  def wikieApiCall(wid: WID) = Action { implicit request =>
    (for (
      au <- auth orCorr cNoAuth;
      r1 <- (au.hasPerm(Perm.apiCall) || au.hasPerm(Perm.adminDb)) orErr ("no permission, eh? ");
      widp <- wid.parentWid;
      w <- Wikis.find(widp)
    ) yield {
      // default to category
      val res = try {
        val sec = wid.name
        val script = w.scripts.find(sec == _.name).orElse(model.Wikis.category(widp.cat) flatMap (_.scripts.find(sec == _.name)))
        val res: String = script.filter(_.checkSignature).map(s => {
          val up = razie.NoStaticS.get[model.User]
          model.WikiScripster.runScript(s.content, Some(w), up, request.queryString.map(t=>(t._1, t._2.mkString)))
        }) getOrElse ""
        Audit.logdb("SCRIPT_RESULT", res)
        res
      } catch { case _ => "?" }
      Ok(res)
    }) getOrElse
      Unauthorized("You don't have permission to do this...")
  }
}
