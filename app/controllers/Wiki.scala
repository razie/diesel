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

/** wiki controller */
object Wiki extends RazController with Logging {

  case class EditWiki(label: String, markup: String, content: String)

  val editForm = Form {
    mapping(
      "label" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "markup" -> nonEmptyText.verifying("Unknown!", Wikis.markups.contains(_)),
      "content" -> nonEmptyText) (EditWiki.apply)(EditWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: EditWiki => !Wikis.hasporn(ew.content)
        })
  }

  case class ReportWiki(reason: String)

  val reportForm = Form {
    mapping(
      "reason" -> nonEmptyText) (ReportWiki.apply)(ReportWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: ReportWiki => !Wikis.hasporn(ew.reason)
        })
  }

  case class AddWiki(name: String)

  val addForm = Form {
    mapping(
      "name" -> nonEmptyText) (AddWiki.apply)(AddWiki.unapply) verifying (
        "Your entry failed the obscenity filter", { ew: AddWiki => !Wikis.hasporn(ew.name)
        })
  }

  case class LinkWiki(how: String, markup: String, comment: String)

  val linkForm = Form {
    mapping(
      "how" -> nonEmptyText,
      "markup" -> nonEmptyText.verifying("Unknown!", Wikis.markups.contains(_)),
      "comment" -> text) (LinkWiki.apply)(LinkWiki.unapply) verifying (
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
  def edit2 = Action{ implicit request =>
    implicit val errCollector = new VError()
    (for (
      cat <- request.queryString("cat").headOption;
      name <- request.queryString("name").headOption
    ) yield edit(cat, name).apply(request)) getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  def canSee(cat: String, name: String, au: Option[User])(implicit errCollector: VError, request: Request[_]): Option[Boolean] = {
    lazy val isAdmin = au.map(_.hasPerm(Perm.adminDb)).getOrElse(false)
    (for (
      hidden <- ("Hidden" != cat || au.map(_.hasPerm(Perm.adminDb)).getOrElse(false)) orErr ("can't see admin pages");
      pubProfile <- ("User" != cat || Wikis.withIndex(_.get2(name, cat).isDefined) || au.map(name == _.userName).getOrElse(isAdmin)) orErr ("Sorry - profile not found or is private!");
      mine <- ("WikiLink" != cat || au.map(x => name.split(":")(1) == x.id).getOrElse(isAdmin)) orErr ("Sorry - topic not foudn or is private!"); // TODO report
      t <- true orErr ("can't")
    ) yield true)
    // TODO add user's personal pages
    // TODO parent can see child's profile
  }

  final val corrVerified = new Corr("not verified", "Sorry - you need to verify your email address, to create or edit public topics.\n If you already did, please describe the issue in a support request below.");

  def canEdit(cat: String, name: String, u: Option[User])(implicit errCollector: VError, request: Request[_]) = {
    println("------------------" + cat)
    (for (
      cansee <- canSee(cat, name, u);
      au <- u orCorr cNoAuth;
      isA <- au.isActive orErr ("This account is not active");
      r1 <- ("Category" != cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      mine <- ("User" != cat || name == au.userName) orErr ("Can only edit your own public profile!");
      mine1 <- ("User" != cat || au.canHasProfile) orErr ("Sorry - you cannot have a public profile - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a support request below.");
      mine2 <- ("WikiLink" == cat || au.canHasProfile) orErr ("Sorry - you cannot create or edit public topics - either no parent added or parent does not allow it! \n If you think you should have one, please describe the issue in a support request below.");
      pro <- au.profile orCorr cNoProfile;
      verif <- ("WikiLink" == cat || "User" == cat || au.hasPerm(Perm.eVerified)) orCorr corrVerified;
      t <- true orErr ("can't")
    ) yield true)
  }

  def edit(category: String, name: String) = Action{ implicit request =>
    implicit val errCollector = new VError()
    val n = Wikis.formatName(WID(category, name))

    Wikis.find(category, n) match {
      case Some(w) =>
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(category, name, Some(au));
          res <- (!w.isReserved || au.hasPerm(Perm.uReserved)) orErr ("Category is reserved");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Ok (views.html.wiki.wikiEdit(category, n, editForm.fill(EditWiki(w.label, w.markup, w.content)), auth))
        }) getOrElse
          noPerm(category, name, "EDIT " + errCollector.mkString)
      case None =>
        (for (
          au <- auth orCorr cNoAuth;
          can <- canEdit(category, name, Some(au));
          r3 <- ("any" != category) orErr ("can't create in category any");
          w <- Wikis.find("Category", category) orErr ("cannot find the category " + category);
          res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == category) orErr ("Category is reserved");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission
        ) yield {
          Audit.missingPage("wiki " + category + ":" + name);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(Wikis.MD, name)
          val tags = preprocessed.tags
          val contentFromTags = tags.foldLeft(""){ (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          Ok (views.html.wiki.wikiEdit(category, n, editForm.fill(EditWiki(name.replaceAll("_", " "), Wikis.MD, contentFromTags + "Edit content here")), auth))
        }) getOrElse
          noPerm(category, name, "EDIT " + errCollector.mkString)
    }
  }

  def save(category: String, name: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    editForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.wiki.wikiEdit(category, name, formWithErrors, auth))
      },
      {
        case we @ EditWiki(l, m, co) => {
          log("Wiki.save " + category + ":" + name)
          Wikis.find(category, name) match {
            case Some(w) =>
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(category, name, auth);
                r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
                res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == category) orErr ("Category is reserved");
                nochange <- (w.label != l || w.markup != m || w.content != co) orErr ("no change");
                newlab <- Some(if ("WikiLink" == category || "User" == category) l else if (name == Wikis.formatName(l)) l else w.label);
                newVer <- Some(w.newVer(newlab, m, co, auth.get.userName));
                upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr ("Not allowerd")
              ) yield {
                // can only change label of links OR if the formatted name doesn't change
                w.update(newVer)
                Notif.entityUpdateAfter(newVer, WikiEntry.UPD_CONTENT)
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                Redirect(controllers.Wiki.w(category, name))
              }) getOrElse
                noPerm(category, name, "HACK_SAVEEDIT " + errCollector.mkString)
            case None =>
              (for (
                au <- auth orCorr cNoAuth;
                can <- canEdit(category, name, auth);
                r3 <- ("any" != category) orErr ("can't create in category any");
                w <- Wikis.find("Category", category) orErr ("cannot find the category " + category);
                r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission;
                res <- (!w.isReserved || au.hasPerm(Perm.uReserved) || "User" == category) orErr ("Category is reserved")
              ) yield {
                model.WikiEntry(category, name, l, m, co, auth.get.userName).create
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(category, name)))
                Redirect(controllers.Wiki.w(category, name))
              }) getOrElse
                noPerm(category, name, "HACK_SAVEEDIT " + errCollector.mkString)
          }
        }
      })
  }

  def search(q: String) = show("any", q) // TODO more complex search

  def show2(cat: String, name: String, colon: String = ":") = show (cat, name)
  def show1(cat: String, name: String) = show (cat, name)
  def showId(id: String) = Action { implicit request =>
    (for (w <- Wikis.findById(id)) yield Redirect(controllers.Wiki.w(w.category, w.name))) getOrElse Msg2("Oops - id not found")
  }

  def w(cat: String, name: String) = "/wiki/%s:%s".format(cat, name)
  def w(name: String) = "/wiki/%s".format(name)

  def call[A, B](value: A)(f: A => B) = f(value)

  def show(category: String, iname: String) = Action { implicit request =>
    implicit val errCollector = new VError()

    // TODO stupid routes - can't match without the :
    val cat = if (category.endsWith(":")) category.substring(0, category.length - 1) else category
    val name = Wikis.formatName(WID(cat, iname))

    // special pages
    if (!canSee(cat, name, auth).map(identity).getOrElse(false)) noPerm(cat, name, "SHOW " + errCollector.mkString)
    else if ("Page" == cat && "home" == name) Redirect ("/")
    else if ("any" == cat) {
      val l = Wikis.findAny(name)
      if (l.size >= 1)
        // redirect to use the proper Category display
        // TODO this is fucked up
        Wikis.findAny(name).collectFirst { case p => Redirect (controllers.Wiki.w(p.category, name)) }.get
      else
        Ok (views.html.wiki.wikiPage(cat, name, Some(iname), None, auth))
    } else {
      // normal request with cat and name
      Wikis.find(cat, name).map(
        w => {
          // redirect a simple alias with no other content
          w.alias.map { wid =>
            Redirect(controllers.Wiki.w(wid.cat, wid.name))
          } getOrElse
            Ok (views.html.wiki.wikiPage(cat, name, Some(iname), Some(w), auth))
        }) getOrElse
        Ok (views.html.wiki.wikiPage(cat, name, Some(iname), None, auth))
    }
  }

  def debug(category: String, iname: String, c: String) = Action { implicit request =>
    implicit val errCollector = new VError()

    // TODO stupid routes - can't match without the :
    val cat = if (category.endsWith(":")) category.substring(0, category.length - 1) else category
    val name = Wikis.formatName(WID(cat, iname))

    // special pages
    if (!canSee(cat, name, auth).map(identity).getOrElse(false)) noPerm(cat, name, "DEBUG " + errCollector.mkString)
    else {
      // normal request with cat and name
      Wikis.find(cat, name).map(
        w => {
          // redirect a simple alias with no other content
          w.alias.map { wid =>
            Redirect(controllers.Wiki.w(wid.cat, wid.name))
          } getOrElse
            Ok (views.html.wiki.wikiDebug(cat, name, Some(iname), Some(w), auth))
        }) getOrElse
        Ok (views.html.wiki.wikiDebug(cat, name, Some(iname), None, auth))
    }
  }

  def report(cat: String, name: String) = Action { implicit request =>
    auth match {
      case Some(user) =>
        Ok (views.html.wiki.wikiReport(cat, name, reportForm.fill (ReportWiki("")), auth))
      case None => {
        Audit.auth("need logged in to report a wiki")
        Oops ("You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page...", cat, name)
      }
    }
  }

  def reported(cat: String, name: String) = Action { implicit request =>
    reportForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiReport(cat, name, formWithErrors, auth)),
      {
        case we @ ReportWiki(reason) =>
          Wikis.flag(cat, name, "reported by user: " + auth + " BECAUSE " + reason)
          SendEmail.send (SUPPORT, SUPPORT, "WIKI_FLAGGED", "link: <a href=\"" + w(cat, name) + "\">here</a> reported by user " + reason)
      })
    Msg ("OK, reported!", cat, name)
  }

  def add(cat: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that name!", Some("/wiki/" + cat)),
      {
        case we @ AddWiki(name) =>
          Redirect(routes.Wiki.edit(cat, name))
      })
  }

  def link(fromCat: String, fromName: String, toCat: String, toName: String) = {
    if ("User" == fromCat) linkUser(toCat, toName)
    else
      TODO
  }

  def linkUser(cat: String, name: String) = Action { implicit request =>
   implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      isA <- au.isActive orErr ("This account is not active");
      r1 <- au.hasPerm(Perm.uProfile) orCorr cNoPermission
    ) yield {
      if (au.pages(cat).exists(_.name == name))
        Redirect(routes.Wiki.edit("WikiLink", au.pages(cat).filter(_.name == name).head.wname))
      else
        Ok (views.html.wiki.wikiLink(WID("User", au.id), WID(cat, name),
          linkForm.fill (LinkWiki("Enjoy", Wikis.MD, """[[User:%s | You]] -> [[%s:%s]]""".format(au.id, cat, name))), auth))
    }) getOrElse
      noPerm(cat, name, "LINKUSER " + errCollector.mkString)
  }

  def linked(fromCat: String, fromName: String, toCat: String, toName: String) = {
    if ("User" == fromCat) linkedUser(fromName, toCat, toName)
    else
      TODO
  }

  def linkedUser(userId: String, cat: String, name: String) = Action { implicit request =>
      def hows = {
        Wikis.find("Category", cat).flatMap(_.tags.get("roles:" + "User")) match {
          case Some(s) => s.split(",").toList
          case None    => Wikis.pageNames("Link").toList
        }
      }

    implicit val errCollector = new VError()

    linkForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiLink(WID("User", auth.get.id), WID(cat, name), formWithErrors, auth)),
      {
        case we @ LinkWiki(how, mark, comment) =>
          (for (
            user <- auth orCorr cNoAuth;
            isA <- user.isActive orErr ("This account is not active");
            isMe <- (user.id equals userId) orErr {
              Audit.security("Another user tried to link...", userId, user.id)
              ("invalid user")
            };
            ok <- hows.contains(how) orErr ("invalid role")
          ) yield {
            val wl = model.WikiLink(WID("User", user.id), WID(cat, name), how)
            wl.create
            model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(cat, name), mark, comment, user.id).props(Map("owner" -> user.id)).create
            this dbop model.UserWiki(user._id, cat, name).create
            Msg2 ("OK, added!", Some("/"))
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
          }
      })
  }

  def reserve(cat: String, name: String, how: Boolean) = Action { implicit request =>
    implicit val errCollector = new VError()

    log("Wiki.reserve " + cat + ":" + name)
    (for (
      au <- auth orCorr cNoAuth;
      w <- Wikis.find(cat, name);
      r1 <- ("Category" != cat || au.hasPerm(Perm.uCategory)) orErr ("no permission to edit a Category");
      r1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
      nochange <- (w.isReserved != how) orErr ("no change");
      newVer <- Some(w.props(w.props + ("reserved" -> (if (how) "yes" else "no"))));
      upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_TOGGLE_RESERVED) orErr ("Not allowerd")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      w.update(newVer)
      Notif.entityUpdateAfter(newVer, WikiEntry.UPD_TOGGLE_RESERVED)
      Redirect(controllers.Wiki.w(cat, name))
    }) getOrElse
      noPerm(cat, name, "ADMIN_RESERVE " + errCollector.mkString)
  }

  def wikieRename1(cat: String, name: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      ok <- ("WikiLink" != cat && "User" != cat) orErr ("can't rename this category");
      w <- Wikis.find(cat, name) orErr ("topic not found")
    ) yield {
      Ok (views.html.wiki.wikiRename(cat, name, renameForm.fill ((w.label, w.label)), auth))
    }) getOrElse
      noPerm(cat, name, "RENAME " + errCollector.mkString)
  }

  def wikieRename2(cat: String, name: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    renameForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiRename(cat, name, formWithErrors, auth)),
      {
        case (_, n) =>
          (for (
            au <- auth orCorr cNoAuth;
            ok <- ("WikiLink" != cat && "User" != cat) orErr ("can't rename this category");
            w <- Wikis.find(cat, name) orErr ("topic not found")
          ) yield {
            w.update(w.renamed(n))
            Msg ("OK, renamed!", cat, Wikis.formatName(n))
          }) getOrElse
            noPerm(cat, name, "RENAME2 " + errCollector.mkString)
      })
  }

  import play.api.libs.json._

  def xp(cat: String, name: String, c: String, path: String) = Action { request =>
    (for (
      w <- Wikis.find(cat, name)
    ) yield {
      val node = new WikiWrapper(cat, name)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      val res: List[String] = if (razie.GPath(path).isAttr) (root \\@ path) else (root \ path).nodes.map(_.toString)
      Ok(Json.toJson(res))
    }) getOrElse
      Ok("Nothing... for " + cat + ":" + name + " : " + path)
  }
}
