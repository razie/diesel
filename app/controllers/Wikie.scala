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
import admin._
import model._
import db.RazMongo
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{SimpleResult, AnyContent, Action, Request}
import razie.{cout, Logging, clog}
import db.ROne
import db.RMany
import model.WikiAudit
import model.WikiEntryOld
import model.WikiLink
import model.CMDWID

class WikieBase extends WikiBase {

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

  val addForm = Form(
    "name" -> nonEmptyText.verifying(vPorn, vSpec))

  def renameForm(realm:String) = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name already in use", { t: (String, String) => !Wikis(realm).index.containsName(Wikis.formatName(t._2))
      })
  }

}

/** wiki controller */
object Wikie extends WikieBase {
  import Visibility._

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = Action { implicit request =>
    implicit val errCollector = new VErrors()
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
    implicit val errCollector = new VErrors()

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
    implicit val errCollector = new VErrors()

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
              we.preprocessed
              //todo verify permissiont o create in realm
              //                if (wep.props.get("realm").exists(_ != we.realm))
              //                  we = we.copy(realm=wep.props("realm"))

              // needs owner?
              if (WikiDomain.needsOwner(wid.cat)) {
                we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                this dbop model.UserWiki(au._id, we.uwid, "Owner").create
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
                for (s <- model.Staged.find("WikiLinkStaged").filter(x => x.content.get("from").asInstanceOf[DBObject].get("cat") == wid.cat && x.content.get("from").asInstanceOf[DBObject].get("name") == wid.name)) {
                  val wls = Wikis.fromGrated[WikiLinkStaged](s.content)
                  for(ufrom <- wls.from.uwid;
                      uto <- wls.to.uwid) {
                    val wl = WikiLink(ufrom, uto, wls.how)
                    wl.create
                  }
                  we = we.cloneParent(Wikis.find(wls.to).map(_._id)) // should still change
                  s.delete
                }

                // needs parent?
                we.wid.parentWid.flatMap(_.uwid).foreach { puwid =>
                  if (ROne[WikiLink]("from" -> we.uwid, "to" -> puwid, "how" -> "Child").isEmpty)
                    WikiLink(we.uwid, puwid, "Child").create
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

  /** notify all followers of new topic/post */
  private def notifyFollowersCreate(wpost: WikiEntry, au: User)(implicit mailSession: MailSession) = {
    // 1. followers of this topic or followers of parent

    wpost.parent flatMap (Wikis.find(_)) map { w =>
      // user wikis
      (Users.findUserById(w.by).map(_._id).toList ++ model.Users.findUserLinksTo(w.uwid).filter(_.notif == model.UW.EMAIL_EACH).toList.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).map(u => Emailer.sendEmailNewTopic(u, au, w.wid, wpost)))

      // followers by email
      model.Users.findFollowerLinksTo(w.uwid).toList.groupBy(_.followerId).values.map(_.head).map(flink =>
        flink.follower.map(follower => {
          Emailer.sendEmailFollowerNewTopic(follower.email.dec, au, w.wid, wpost, flink.comment)
        }))
    }
  }

  def report(wid: WID) = Action { implicit request =>
    auth match {
      case Some(user) =>
        Ok(views.html.wiki.wikieReport(wid, reportForm.fill(ReportWiki("")), auth))
      case None => {
        clog << "need logged in to report a wiki"
        val msg = "You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page..."
        Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid), auth))
      }
    }
  }

  def reported(wid: WID) = Action { implicit request =>
    reportForm.bindFromRequest.fold(
    formWithErrors => BadRequest(views.html.wiki.wikieReport(wid, formWithErrors, auth)),
    {
      case we @ ReportWiki(reason) =>
        Wikis.flag(wid, "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
        SendEmail.withSession { implicit session =>
          SendEmail.send(SUPPORT, SUPPORT, "WIKI_FLAGGED",
            "link: " + wid.ahref + "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
        }
    })
    Msg("OK, page " + wid.wpath + " reported!", wid)
  }

  // from category - add a ...
  def add(cat: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
    formWithErrors => Msg2("Oops, can't add that name!" + formWithErrors, Some("/wiki/" + cat)),
    {
      case name: String => Redirect(routes.Wikie.wikieEdit(WID(cat, name)))
    })
  }

  // add a child/related to another topic
  def addLinked(cat: String, pwid: WID, role: String) = Action { implicit request =>
    addForm.bindFromRequest.fold(
    formWithErrors => Msg2("Oops, can't add that name!", Some(pwid.urlRelative)),
    {
      case name: String => forActiveUser { au=>
        val n = Wikis.formatName(WID(cat, name))
        Stage("WikiLinkStaged", WikiLinkStaged(WID(cat, n, pwid.findId), pwid, role).grated, au.userName).create
        //todo remove this in october
        //          if (pwid.name.length == 0)
        //            Redirect(routes.Wiki.wikieEdit(WID(cat, name)))
        //          else
        Redirect(routes.Wikie.wikieEdit(WID(cat, name, pwid.findId)))
      }
    })
  }

  private def canDelete(wid: WID)(implicit errCollector: VErrors, request: Request[_]) = {
    for (
      au <- activeUser;
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w));
      ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission
    ) yield (au, w)
  }

  /** delete step 1: confirm */
  def wikieDelete1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    log("Wiki.delete1 " + wid)
    canDelete(wid).collect {
      case (au, w) =>
        Msg2C(
          "DELETE forever - are you sure? TODO show/remove links FROM, links TO, Wikientry.parent==me etc, userWiki=me",
          Some(routes.Wikie.wikieDelete2(wid)))
    } getOrElse
      noPerm(wid, "ADMIN_DELETE1")
  }

  /** delete step 2: do it */
  def wikieDelete2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    log("Wiki.delete2 " + wid)
    if (wid.cat != "Club") canDelete(wid).collect {
      case (au, w) =>
        db.tx("Wiki.delete") { implicit txn =>
          RMany[WikiLink]("to" -> w.uwid.grated).toList.foreach(_.delete)
          RMany[WikiLink]("from" -> w.uwid.grated).toList.foreach(_.delete)
          var done = false
          RMany[UserWiki]("uwid" -> w.uwid.grated).toList.foreach(wl => {
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

  private def canRename(wid: WID)(implicit errCollector: VErrors, request: Request[_]) = {
    for (
      au <- activeUser;
      ok <- ("WikiLink" != wid.cat && "User" != wid.cat) orErr ("can't rename this category");
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w))
    ) yield (au, w)
  }

  /** rename step 1: form for new name */
  def wikieRename1(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    canRename(wid).collect {
      case (au, w) =>
        Ok(views.html.wiki.wikieRename(wid, renameForm(wid.getRealm).fill((w.label, w.label)), auth))
    } getOrElse
      noPerm(wid, "RENAME")
  }

  /** rename step 2: do it */
  def wikieRename2(wid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    renameForm(wid.getRealm).bindFromRequest.fold(
    formWithErrors => BadRequest(views.html.wiki.wikieRename(wid, formWithErrors, auth)),
    {
      case (_, n) =>
        canRename(wid).collect {
          case (au, w) =>
            val newp = (w.cloneRenamed(n))
            db.tx("Wiki.Rename") { implicit txn =>
              w.update(newp)

              //                RMany[WikiLink]("to" -> wid.grated).toList.map(wl => {
              //                  wl.delete
              //                  WikiLink(wl.from, newp.wid, wl.how).create
              //                })

              //                RMany[WikiLink]("from" -> wid.grated).toList.map(wl => {
              //                  wl.delete
              //                  WikiLink(newp.wid, wl.to, wl.how).create
              //                })

              var done = false
              //                RMany[UserWiki]("wid" -> wid.grated).toList.map(wl => {
              //                  wl.delete
              //                  UserWiki(wl.userId, newp.wid, wl.role).create
              //                  done = true
              //                })

              if (done) cleanAuth() // it probably belongs to the current user, cached...
            }

            Msg("OK, renamed!", WID(wid.cat, Wikis.formatName(n)))
        } getOrElse
          noPerm(wid, "RENAME2")
    })
  }

  def wikieAdd(cat: String, realm:String="rk") = FAU {
    implicit au => implicit errCollector => implicit request =>
    (for (
      w <- Wikis.category(cat)
    ) yield {
      Ok(views.html.wiki.wikieAdd(w.wid, Some(w), auth))
    }) getOrElse unauthorized()
  }

  def wikieCreate(cat: String, realm:String="rk") = FAU {
    implicit au => implicit errCollector => implicit request =>
    (for (
      w <- Wikis.category(cat)
    ) yield {
      Ok(views.html.wiki.wikieCreate(w.wid, Some(w), auth))
    }) getOrElse unauthorized()
  }

  /** change owner */
  def uowner(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

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
  def reserve(wid: WID, how: Boolean) = FAU {
    implicit au => implicit errCollector => implicit request =>

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

  /** move to new parent */
  def wikieMove1(id:String, realm:String="rk") =  FAU {
    implicit au => implicit errCollector => implicit request =>
    (for (
      w <- Wikis.findById(id)
    ) yield {
      val parentCats1 = Wikis(realm).domain.aEnds(w.wid.cat, "Parent")
      val parentCats2 = Wikis(realm).domain.zEnds(w.wid.cat, "Parent").map(_.wid.cat)
      val parents = parentCats1.flatMap {c=>
        Wikis.pages(c).filter(w=>canEdit(w.wid, auth, Some(w)).exists(_ == true)).map(w=>(w.uwid, w.label))
      }
      Ok(views.html.wiki.wikiMove(w.wid, w, parents, auth))
    }) getOrElse unauthorized()
  }

  /** move to new parent */
  def wikieMove2(page:String, from:String, to:String, realm:String="rk") = FAU {
    implicit au => implicit errCollector => implicit request =>
    (for (
      pageW <- Wikis.findById(page);
      fromW <- Wikis.findById(from);
      toW <- Wikis.findById(to);
      hasP <- pageW.parent.exists(_.toString == from) orErr "does not have a parent"
    ) yield {
      db.tx("Wiki.Move") { implicit txn =>
        pageW.update(pageW.copy(parent=Some(toW._id)))
        RMany[WikiLink]("from" -> pageW.uwid.grated, "to" -> fromW.uwid.grated, "how"->"Child").toList.foreach{ link=>
          link.delete
          link.copy(to = toW.uwid).create
        }
      }
      Redirect(controllers.Wiki.w(pageW.wid, false)).flashing("count" -> "0")
    }) getOrElse unauthorized()
  }
}


