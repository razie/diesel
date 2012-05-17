package controllers

import admin.Audit
import model.Api
import model.User
import model.Wikis
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import model.WID
import razie.Logging
import admin.SendEmail

/** wiki controller */
object Wiki extends RazController with Logging {

  case class EditWiki(label: String, markup: String, content: String)

  val editForm = Form {
    mapping(
      "label" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", !spec(_)),
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

  def checkEditPerm(c: String, n: String)(implicit request: Request[_]) =
    ("Category" == c && hasPerm("uCategory") || hasPerm("uWiki"))

  //  def privateCheck (k:Option[WikiEntry], cat:String) =
  //    k match {
  //    case Some(w) => w.isPrivate
  //  }

  def xcall(other: Action[AnyContent]) (implicit request:Request[AnyContent]) =
    other.apply(request)

  /** used when no pages foudn in any, cat i captured in a form */
  def edit2 = Action{ implicit request =>
    implicit val errCollector = new Error()
    val x = (for (
      cat <- request.queryString("cat").firstOption;
      name <- request.queryString("name").firstOption
    ) yield xcall(edit(cat, name)))
    x getOrElse {
      error("ERR_CANT_UPDATE_USER " + session.get("email"))
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  // TODO don't display private pages
  def edit(category: String, name: String) = Action{ implicit request =>
    implicit val errCollector = new Error()
    val n = Wikis.formatName(name)
    Wikis.find(category, n) match {
      case Some(w) =>
        (for (
          au <- auth orCorr new Corr("not logged in", "Sorry - need to log in to edit a page"); //cLogin;
          r1 <- ("Category" != category || hasPerm("uCategory")) orErr ("no permission to edit a Category");
          r1 <- (hasPerm("uWiki")) orErr ("no permission");
          res <- (!w.isReserved || hasPerm("uReserved")) orErr ("Category is reserved")
        ) yield {
          Ok (views.html.wiki.wikiEdit(category, n, editForm.fill(EditWiki(w.label, w.markup, w.content)), auth))
        }) getOrElse
          noPerm(category, name, errCollector.mkString)
      case None =>
        (for (
          au <- auth orCorr new Corr("not logged in", "Sorry - need to log in to create a page"); //cLogin;
          r3 <- ("any" != category) orErr ("can't create in category any");
          w <- Wikis.find("Category", category) orErr ("cannot find the category " + category);
          r1 <- ("Category" != category || hasPerm("uCategory")) orErr ("no permission to edit a Category");
          r2 <- (hasPerm("uWiki")) orErr ("no permission");
          res <- (!w.isReserved || hasPerm("uReserved")) orErr ("Category is reserved")
        ) yield {
          Audit.missingPage("wiki " + category + ":" + name);
          Ok (views.html.wiki.wikiEdit(category, n, editForm.fill(EditWiki(name, Wikis.MD, "Edit content here")), auth))
        }) getOrElse
          noPerm(category, name, errCollector.mkString)
    }
  }

  // TODO don't display private pages
  def save(category: String, name: String) = Action { implicit request =>
    implicit val errCollector = new Error()
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
                au <- auth orCorr new Corr("not logged in", "Sorry - need to log in to edit a page"); //cLogin;
                r1 <- ("Category" != category || hasPerm("uCategory")) orErr ("no permission to edit a Category");
                r1 <- (hasPerm("uWiki")) orErr ("no permission");
                res <- (!w.isReserved || hasPerm("uReserved")) orErr ("Category is reserved");
                nochange <- (w.label != l || w.markup != m || w.content != co) orErr ("no change")
              ) yield {
                // can only change label of links OR if the formatted name doesn't change
                val newlab = if ("WikiLink" == category) l else if (name == Wikis.formatName(l)) l else w.label
                w.update(w.newVer(newlab, m, co, au.userName))
                Redirect(controllers.Wiki.w(category, name))
              }) getOrElse
                noPerm(category, name, errCollector.mkString)
            case None =>
              (for (
                au <- auth orCorr new Corr("not logged in", "Sorry - need to log in to create a page"); //cLogin;
                r3 <- ("any" != category) orErr ("can't create in category any");
                w <- Wikis.find("Category", category) orErr ("cannot find the category " + category);
                r1 <- ("Category" != category || hasPerm("uCategory")) orErr ("no permission to edit a Category");
                r1 <- (hasPerm("uWiki")) orErr ("no permission");
                res <- (!w.isReserved || hasPerm("uReserved")) orErr ("Category is reserved")
              ) yield {
                model.WikiEntry(category, name, l, m, co, au.userName).create
                Redirect(controllers.Wiki.w(category, name))
              }) getOrElse
                noPerm(category, name, errCollector.mkString)
          }
        }
      })
  }

  def search(q:String) = show("any", q) // TODO more complex search

  def show2(cat: String, name: String, colon: String = ":") = show (cat, name)
  def show1(cat: String, name: String) = show (cat, name)

  def w(cat: String, name: String) = "/wiki/%s:%s".format(cat, name)

  def call[A, B](value: A)(f: A => B) = f(value)

  // TODO don't display private pages
  def show(category: String, name: String) = Action { implicit request =>
    // TODO stupid routes - can't match without the :
    val cat = if (category.endsWith(":")) category.substring(0, category.length - 1) else category

    // special pages
    if ("Page" == cat && "home" == name) Redirect ("/")
    else if ("User" == cat) Redirect ("/")
    else if ("any" == cat) {
      val l = Wikis.findAny(name)
      if (l.size >= 1)
        // redirect to use the proper Category display
        // TODO this is fucked up
        Wikis.findAny(name).collectFirst { case p => Redirect (controllers.Wiki.w(p.category, name)) }.get
      else
        Ok (views.html.wiki.wikiPage(cat, name, None, auth))
    } else {
      // normal request with cat and name
      Wikis.find(cat, name).map(
        w => {
          if ("WikiLink" == w.category) Wikis.linkFromName(w.name).isPrivate // TODO so what?
        })
      Ok (views.html.wiki.wikiPage(cat, name, None, auth))
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
          Wikis.flag(cat, name, "reported by user: " + reason)
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
    auth match {
      case Some(user) =>
        if (hasPerm("uProfile"))
          if (user.pages(cat).exists(_.name == name))
            Redirect(routes.Wiki.edit("WikiLink", user.pages(cat).filter(_.name == name).first.wname))
          else
            Ok (views.html.wiki.wikiLink(WID("User", user.id), WID(cat, name),
              linkForm.fill (LinkWiki("Enjoy", Wikis.MD, """[[User:%s | You]] -> [[%s:%s]]""".format(user.id, cat, name))), auth))
        else
          noPerm(cat, name)
      case None => Ok (views.html.util.utilErr("You need to be logged in to link to a page!", controllers.Wiki.w(cat, name), auth))
    }
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

    implicit val errCollector = new Error()

    linkForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.wiki.wikiLink(WID("User", auth.get.id), WID(cat, name), formWithErrors, auth)),
      {
        case we @ LinkWiki(how, mark, comment) =>
          (for (
            user <- auth orCorr cLogin;
            isMe <- (user.id == userId) orErr {
              Audit.security("Another user tried to link...", userId, user.id)
              ("invalid user")
            };
            ok <- hows.contains(how) orErr ("invalid role")
          ) yield {
            val wl = model.WikiLink(WID("User", user.id), WID(cat, name), how)
            wl.create
            model.WikiEntry("WikiLink", wl.wname, "You like " + Wikis.label(cat, name), mark, comment, user.id).props(Map("owner" -> user.id)).create
            model.UserWiki(user._id, cat, name).create
            Msg2 ("OK, added!", Some("/"))
          }) getOrElse {
            error("ERR_CANT_UPDATE_USER " + session.get("email"))
            Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
          }
      })
  }

  def reserve(cat: String, name: String, how: Boolean) = Action { implicit request =>
    implicit val errCollector = new Error()

    log("Wiki.reserve " + cat + ":" + name)
    (for (
      au <- auth orCorr new Corr("not logged in", "Sorry - need to log in to edit a page"); //cLogin;
      w <- Wikis.find(cat, name);
      r1 <- ("Category" != cat || hasPerm("uCategory")) orErr ("no permission to edit a Category");
      r1 <- (hasPerm("adminDb")) orErr ("no permission");
      nochange <- (w.isReserved != how) orErr ("no change")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      w.update(w.props(w.props + ("reserved" -> (if (how) "yes" else "no"))))
      Redirect(controllers.Wiki.w(cat, name))
    }) getOrElse
      noPerm(cat, name, errCollector.mkString)
  }

}
