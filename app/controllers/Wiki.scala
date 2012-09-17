package controllers

import org.joda.time.DateTime
import admin._
import model._
import model.Perm
import model.WID
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import razie.Logging
import com.mongodb.DBObject

/** wiki controller */
object Wiki extends RazController with Logging {

  case class EditWiki(label: String, markup: String, content: String, visibility: String = "Public")

  val editForm = Form {
    mapping(
      "label" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "markup" -> nonEmptyText.verifying("Unknown!", Wikis.markups.contains(_)),
      "content" -> nonEmptyText,
      "visibility" -> nonEmptyText)(EditWiki.apply)(EditWiki.unapply) verifying (
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
      ("Name already in use", { t: (String, String) => Wikis.withIndex { index => !index.idx.contains(Wikis.formatName(t._2)) }
      })
  }

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      cat <- request.queryString("cat").headOption;
      name <- request.queryString("name").headOption
    ) yield wikieEdit(WID(cat, name)).apply(request)) getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  def isInSameClub (member:User, owner:User) (implicit errCollector: VError = IgnoreErrors) = {
    // all clubs where member
    val m1 = member.wikis.filter(x=>x.wid.cat == "Club" && x.role != "Fan").toList

    // owner is the club
    (owner.roles.contains(UserType.Organization.toString) && m1.exists(_.wid.name == owner.userName)) 
  }
  
  def isVisible(u: Option[User], props: Map[String, String]) (implicit errCollector: VError = IgnoreErrors) = {
    // TODO optimize
    def uname(id:Option[String]) = id.flatMap(Users.findUserById(_)).map(_.userName).getOrElse(id.getOrElse(""))
          
    (!props.get("visibility").isDefined) ||
      (props("visibility") == "Public") || // if changing while edit, it will have a value even when public
      (props("visibility") == "Private" && u.isDefined && props.get("owner") == Some(u.get.id) ||
      (props("visibility") == "Club" && u.isDefined && (
          props.get("owner") == Some(u.get.id) || props.get("owner").flatMap(Users.findUserById(_)).exists(c=>isInSameClub(u.get,c))) orCorr (
              cNotMember(uname(props.get("owner"))))).getOrElse(
       u.exists(_.hasPerm(Perm.adminDb))))
  }
  
  def canSee(wid: WID, au: Option[User], w: Option[WikiEntry])(implicit errCollector: VError) = {
    lazy val isAdmin = au.exists(_.hasPerm(Perm.adminDb))
    lazy val we = if (w.isDefined) w else Wikis.find(wid)
    val cat = wid.cat
    val name = wid.name
    (for (
      hidden <- ("Hidden" != cat || au.exists(_.hasPerm(Perm.adminDb))) orErr ("can't see admin pages");
      pubProfile <- ("User" != cat || Wikis.withIndex(_.get2(name, wid).isDefined) || au.map(name == _.userName).getOrElse(isAdmin)) orErr ("Sorry - profile not found or is private! %s : %s".format(cat, name));
      mine <- ("WikiLink" != cat || au.map(x => name.split(":")(1) == x.id).getOrElse(isAdmin)) orErr ("Sorry - topic not foudn or is private!"); // TODO report
      mine2 <- ("WikiLink" == cat || !we.isDefined || isVisible(au, we.get.props)) orErr ("Sorry - topic is not visible!"); // TODO report
      t <- true orErr ("just can't, eh")
    ) yield true)
    // TODO parent can see child's profile
  }

  final val corrVerified = new Corr("not verified", """Sorry - you need to <a href="/user/task/verifyEmail">verify your email address</a>, to create or edit public topics.\n If you already did, please describe the issue in a  <a href="/doe/support?desc=email+already+verified">support request</a> below.""");

  def canEdit2(wid: WID, u: Option[User], w: Option[WikiEntry]) = {
    implicit val errCollector = new VError()
    canEdit(wid, u, w)
  }

  def canEdit(wid: WID, u: Option[User], w: Option[WikiEntry], props:Option[Map[String,String]]=None)(implicit errCollector: VError) = {
    val cat = wid.cat
    val name = wid.name
    lazy val we = if (w.isDefined) w else Wikis.find(cat, name)
    lazy val wprops = if(we.isDefined) we.map(_.props) else props
    (for (
      cansee <- canSee(wid, u, w);
      au <- u orCorr cNoAuth;
      isA <- checkActive(au);
      r1 <- ("Category" != cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      r2 <- ("Admin" != cat || au.hasPerm(Perm.adminDb)) orErr ("no permission to edit an Admin entry");
      mine <- ("User" != cat || name == au.userName) orErr ("Can only edit your own public profile!");
      mine1 <- ("User" != cat || au.canHasProfile) orErr ("Sorry - you cannot have a public profile - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=parent+should+allow\">support request</a> below.");
      mine2 <- ("WikiLink" == cat || au.canHasProfile) orErr ("Sorry - you cannot create or edit public topics - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a  <a href=\"/doe/support?desc=cannot+have+public+profile\">support request</a> below.");
      pro <- au.profile orCorr cNoProfile;
      verif <- ("WikiLink" == cat || "User" == cat || au.hasPerm(Perm.eVerified)) orCorr corrVerified;
      owner <- !(WikiDomain.needsOwner(cat)) ||
        we.exists(_.isOwner(au.id)) || 
        wprops.flatMap(_.get("visibility")).exists(_ == "Club" && isVisible(u, wprops.get)) || 
        !wprops.flatMap(_.get("visibility")).isDefined orErr ("Sorry - you are not the owner of this topic");
      memod <- (au.hasPerm(Perm.adminDb) || w.flatMap(_.tags.get("moderator")).map(_ == au.userName).getOrElse(true)) orErr ("Sorry - this is moderated and you are not the moderator, are you?");
      t <- true orErr ("can't")
    ) yield true)
  }

  def widFromPath(path: String): Option[WID] = WID fromPath path

  def wikieEdit(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    val n = Wikis.formatName(wid)

    debug("wikieEdit " + wid)

    Wikis.find(wid) match {
      case Some(w) =>
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), Some(w));
          res <- (!w.isReserved || au.hasPerm(Perm.uReserved)) orErr ("Category is reserved");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Ok(views.html.wiki.wikiEdit(w.wid, editForm.fill(EditWiki(w.label, w.markup, w.content, (w.props.get("visibility").getOrElse("Public")))), auth))
        }) getOrElse
          noPerm(wid, "EDIT")
      case None =>
        val parentProps = wid.findParent.map(_.props)
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(wid, Some(au), None, parentProps);
          r3 <- ("any" != wid.cat) orErr ("can't create in category any");
          w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
          res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == wid.cat) orErr ("Category is reserved");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Audit.missingPage("wiki " + wid);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(wid, Wikis.MD, wid.name)
          val tags = preprocessed.tags
          val contentFromTags = tags.foldLeft("") { (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          val visibility = wid.findParent.flatMap(_.props.get("visibility")).getOrElse(Wikis.visibilityFor(wid.cat).headOption.getOrElse("Public"))
          
          Ok(views.html.wiki.wikiEdit(wid, editForm.fill(EditWiki(wid.name.replaceAll("_", " "), Wikis.MD, contentFromTags + "Edit content here", visibility)), auth))
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
        case we @ EditWiki(l, m, co, vis) => {
          log("Wiki.save " + wid)
          Wikis.find(wid) match {
            case Some(w) =>
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, Some(w));
                r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
                res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == wid.cat) orErr ("Category is reserved");
                nochange <- (w.label != l || w.markup != m || w.content != co || (w.props.get("visibility").map(_ != vis).getOrElse(vis != "Public"))) orErr ("no change");
                newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
                newVer <- Some(w.cloneNewVer(newlab, m, co, au.userName));
                upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr ("Not allowerd")
              ) yield {
                var we = newVer

                // visibility?
                if (we.props.get("visibility").map(_ != vis).getOrElse(vis != "Public"))
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au.userName)

                // can only change label of links OR if the formatted name doesn't change
                w.update(we)
                Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                Redirect(controllers.Wiki.w(we))
              }) getOrElse
                noPerm(wid, "HACK_SAVEEDIT")
            case None =>
              // create a new topic
              val parentProps = wid.findParent.map(_.props)
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(wid, auth, None, parentProps);
                r3 <- ("any" != wid.cat) orErr ("can't create in category any");
                w <- Wikis.category(wid.cat) orErr ("cannot find the category " + wid.cat);
                r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission;
                res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == wid.cat) orErr ("Category is reserved")
              ) yield {
                var we = model.WikiEntry(wid.cat, wid.name, l, m, co, au.userName, 1, wid.parent)

                // needs owner?
                if (model.Wikis.category(wid.cat).flatMap(_.tags.get("roles:" + "User")).map(_.split(",")).map(_.contains("Owner")).getOrElse(false)) {
                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au.userName)
                  //                  val wl = model.WikiLink(WID("User", au.id), we.wid, "Owner")
                  //                  wl.create
                  this dbop model.UserWiki(au._id, wid, "Owner").create
                  RazController.cleanAuth()
                }

                // visibility?
                if (vis != "Public")
                  we = we.cloneProps(we.props ++ Map("visibility" -> vis), au.userName)

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
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid))
                Redirect(controllers.Wiki.w(we))
              }) getOrElse
                noPerm(wid, "HACK_SAVEEDIT")
          }
        }
      })
  }

  def search(q: String) = Action { implicit request =>
    // TODO make case-insensitive
    // TODO limit the number of searches - is this performance critical?
    val wikis = Mongo.withDb(Mongo.db("WikiEntry")) { t =>
      for (u <- t if ((q.length>1 && u.get("name").asInstanceOf[String].contains(q)) || (q.length() > 3 && u.get("content").asInstanceOf[String].contains(q)))) yield u
    }.toList

    if (wikis.count(x=>true) == 1)
      Redirect(controllers.Wiki.w(WikiEntry.grated(wikis.head).wid))
    else
      Ok(views.html.wiki.wikiList(q, wikis.map(WikiEntry.grated _).toList, auth))
  }

  def show2c(cat: String, name: String, colon: String = ":", cat1: String, name1: String, c1: String) = Action { implicit request =>
    debug("show2c")
    val parent = Wikis.find(cat, name)
    parent.map { p =>
      Wikis.find(WID(cat1, name1, Some(p._id))).map(w => {
        debug("show2c " + w)
        showPage(w)
      }) getOrElse Msg2("Child %s:%s Not found!".format(cat1, name1))
    } getOrElse Msg2("Parent %s:%s Not found!".format(cat, name))
  }

  def show2(cat: String, name: String, colon: String = ":") = show(WID(cat, name))
  def show1(cat: String, name: String) = show(WID(cat, name))
  def showId(id: String) = Action { implicit request =>
    (for (w <- Wikis.findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(we: WID) = Wikis.urlmap("/wiki/" + we.wpath)

  def w(we: WikiEntry): String = w(we.wid)

  def w(cat: String, name: String) = Wikis.urlmap("/wiki/%s:%s".format(cat, name))
  def w(name: String) = Wikis.urlmap("/wiki/" + name)

  def call[A, B](value: A)(f: A => B) = f(value)

  /** serve a site */
  def site(name: String) = show(WID("Site", name))

  def show(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    debug("show2")
    // TODO stupid routes - can't match without the :
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(WID(cat, iwid.name))

    val wid = WID(cat, name, iwid.parent)

    // special pages
    if ("Page" == cat && "home" == name) Redirect("/")
    else if ("Admin" == cat && "home" == name) Redirect("/")
    else if ("any" == cat) {
      val wl = Wikis.findAny(name).filter(page => canSee(page.wid, auth, Some(page)).getOrElse(false)).toList
      if (wl.size == 1)
        // redirect to use the proper Category display
        // TODO this is fucked up
        Redirect(controllers.Wiki.w(wl.head))
      else if (wl.size > 0)
        Ok(views.html.wiki.wikiList("category any", wl, auth))
      else
        wikiPage(wid, Some(iwid.name), None, auth)
    } else if (Wikis.config(Wikis.TOPICRED).exists(_.contains(wid.wpath))) {
        log ("- redirecting " + wid.wpath)
        Redirect(controllers.Wiki.w(WID.fromPath(Wikis.config(Wikis.TOPICRED).get.apply(wid.wpath)).get))
    } else {
      // normal request with cat and name
      val w = Wikis.find(cat, name)
      if (!canSee(wid, auth, w).getOrElse(false))
        noPerm(wid, "SHOW")
      else
        w.map(w => {
          // redirect a simple alias with no other content
          if (w.alias.isDefined)
            Redirect(controllers.Wiki.w(w.alias.get.formatted))
          else if (w.findParent.isDefined)
            Redirect(controllers.Wiki.w(w.wid))
          else
            wikiPage(wid, Some(iwid.name), Some(w), auth)
        }) getOrElse
          wikiPage(wid, Some(iwid.name), None, auth)
    }
  }

  private def wikiPage(wid: model.WID, iname: Option[String], page: Option[model.WikiEntry], user: Option[model.User]) =
    if (Array("Site", "Page").contains(wid.cat) && page.isDefined)
      Ok(views.html.wiki.wikiSite(wid, iname, page, user))
    else
      Ok(views.html.wiki.wikiPage(wid, iname, page, user))

  private def showPage(w: WikiEntry)(implicit request: Request[_]) = {
    implicit val errCollector = new VError()

    if (!canSee(w.wid, auth, Some(w)).getOrElse(false))
      noPerm(w.wid, "SHOW")
    else
      // redirect a simple alias with no other content
      w.alias.map { wid =>
        Redirect(controllers.Wiki.w(wid.cat, Wikis.formatName(wid)))
      } getOrElse
        wikiPage(w.wid, None, Some(w), auth)
  }

  def debug(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    // TODO stupid routes - can't match without the :
    val cat = if (iwid.cat.endsWith(":")) iwid.cat.substring(0, iwid.cat.length - 1) else iwid.cat
    val name = Wikis.formatName(iwid)

    val wid = WID(cat, name)
    // special pages
    // normal request with cat and name
    val w = Wikis.find(cat, name)
    if (!canSee(wid, auth, w).getOrElse(false))
      noPerm(wid, "DEBUG")
    else
      w.map(
        w => {
          // redirect a simple alias with no other content
          w.alias.map { wid =>
            Redirect(controllers.Wiki.w(wid.cat, Wikis.formatName(wid)))
          } getOrElse
            Ok(views.html.wiki.wikiDebug(wid, Some(iwid.name), Some(w), auth))
        }) getOrElse
        Ok(views.html.wiki.wikiDebug(wid, Some(iwid.name), None, auth))
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

  def add(cat: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that name!", Some("/wiki/" + cat)),
      {
        case we @ AddWiki(name) =>
          Redirect(routes.Wiki.wikieEdit(WID(cat, name)))
      })
  }

  def addLinked(cat: String, pwid: WID, role: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that name!", Some("/wiki/" + cat)),
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

  def moderator(wid: WID) = Wikis.find(wid).flatMap(_.tags.get("moderator"))

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
      model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au.id).cloneProps(Map("owner" -> au.id), au.userName).create
    }
    RazController.cleanAuth(Some(au))
  }

  def linkedUserAccept(expiry:String, userId: String, club: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    def hows = {
      Wikis.category("Club").flatMap(_.tags.get("roles:" + "User")) match {
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
      Emailer.sendEmailLinkOk(au, club)
      Msg2("OK, added!", Some("/"))
    }) getOrElse {
      error("ERR_CANT_LINK_USER " + session.get("email"))
      Unauthorized("Oops - cannot create this link... " + errCollector)
    }
  }

  def linkedUserDeny(userId: String, club: String, how: String) = Action { implicit request =>
    Emailer.sendEmailLinkDenied(Users.findUserById(userId).get, club)
    Msg2("OK, denied!", Some("/"))
  }

  def linkedUser(userId: String, wid: WID, withComment: Boolean) = Action { implicit request =>

    def hows = {
      Wikis.category(wid.cat).flatMap(_.tags.get("roles:" + "User")) match {
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
            val mod = moderator(wid).flatMap(mid=>{println(mid); Users.findUserByUsername(mid)})
            
            if ("Club" == wid.cat && mod.isDefined) {
              Emailer.sendEmailLink(mod.get, au, wid.name, how)
              Msg2("An email has been sent to the moderator, you will receive an email when they're done!", Some("/"))
            } else {
              this dbop model.UserWiki(au._id, wid, how).create
              if (withComment) {
                val wl = model.WikiLink(WID("User", au.id), wid, how)
                wl.create
                model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(wid), mark, comment, au.id).cloneProps(Map("owner" -> au.id), au.userName).create
              }
              RazController.cleanAuth()
              Msg2("OK, added!", Some("/"))
            }
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            Unauthorized("Oops - cannot create this link... " + errCollector)
          }
      })
  }

  def reserve(wid: WID, how: Boolean) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.reserve " + wid)
    (for (
      au <- auth orCorr cNoAuth;
      w <- Wikis.find(wid);
      r1 <- ("Category" != wid.cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      r1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
      nochange <- (w.isReserved != how) orErr ("no change");
      newVer <- Some(w.cloneProps(w.props + ("reserved" -> (if (how) "yes" else "no")), au.userName));
      upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_TOGGLE_RESERVED) orErr ("Not allowerd")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      w.update(newVer)
      Notif.entityUpdateAfter(newVer, WikiEntry.UPD_TOGGLE_RESERVED)
      Redirect(controllers.Wiki.w(wid))
    }) getOrElse
      noPerm(wid, "ADMIN_RESERVE")
  }

  /** delete step 1: confirm */
  def wikieDelete1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.delete1 " + wid)
    (for (
      au <- auth orCorr cNoAuth;
      w <- Wikis.find(wid);
      r1 <- ("Category" != wid.cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      r1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission
    ) yield {
      Msg2C("DELETE forever - are you sure? TODO show/remove links FROM, links TO, Wikientry.parent==me etc, userWiki=me", Some(routes.Wiki.wikieDelete2(wid)))
    }) getOrElse
      noPerm(wid, "ADMIN_DELETE1")
  }

  /** delete step 2: do it */
  def wikieDelete2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.delete2 " + wid)
    (for (
      au <- auth orCorr cNoAuth;
      w <- Wikis.find(wid);
      r1 <- ("Category" != wid.cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      r1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      w.delete(au.userName)
      Msg2("DELETED forever - no way back!")
    }) getOrElse
      noPerm(wid, "ADMIN_DELETE2")
  }

  /** rename step 1: form for new name */
  def wikieRename1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      ok <- ("WikiLink" != wid.cat && "User" != wid.cat) orErr ("can't rename this category");
      w <- Wikis.find(wid) orErr ("topic not found")
    ) yield {
      Ok(views.html.wiki.wikiRename(wid, renameForm.fill((w.label, w.label)), auth))
    }) getOrElse
      noPerm(wid, "RENAME")
  }

  /** rename step 2: do it */
  def wikieRename2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VError()
    renameForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiRename(wid, formWithErrors, auth)),
      {
        case (_, n) =>
          (for (
            au <- auth orCorr cNoAuth;
            ok <- ("WikiLink" != wid.cat && "User" != wid.cat) orErr ("can't rename this category");
            w <- Wikis.find(wid) orErr ("topic not found")
          ) yield {
            val newp = (w.cloneRenamed(n))
            w.update(newp)
            //            Mongo("WikiLink").m.update(Map("to"->wid), Map("to" -> newp.wid))
            Msg("OK, renamed!", WID(wid.cat, Wikis.formatName(n)))
          }) getOrElse
            noPerm(wid, "RENAME2")
      })
  }

  import play.api.libs.json._

  def xpold(cat: String, name: String, c: String, path: String) = xp(WID(cat, name), path)

  def xp(wid: WID, path: String) = Action { request =>
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
}
