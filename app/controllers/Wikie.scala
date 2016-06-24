/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.{Config}

import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.novus.salat._
import org.joda.time.DateTime

import com.typesafe.config.{ConfigObject, ConfigValue}
import mod.diesel.model.Diesel
import model.{UserWiki, User, Users, Perm}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.wiki.{Services, Enc}
import razie.wiki.admin._
import razie.wiki.dom.WikiDomain
import razie.wiki.parser.WAST
import razie.wiki.util.{IgnoreErrors, Corr, PlayTools, VErrors}
import razie.{cout, clog, cdebug, Log}
import scala.Array.canBuildFrom
import com.mongodb.DBObject
import razie.db.{REntity, RazMongo, ROne, RMany}
import razie.db.RazSalatContext.ctx
import razie.wiki.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{Result, AnyContent, Action, Request}
import razie.wiki.model._


class WikieBase extends WikiBase {

  def before(e: WikiEntry, what: String)(implicit errCollector: VErrors = IgnoreErrors): Boolean = {
    WikiObservers.before[WikiEntry](WikiEvent(what, "WikiEntry", e.wid.wpath, Some(e)))
  }
  def after(e: WikiEntry, what: String, au:Option[User])(implicit errCollector: VErrors = IgnoreErrors): Unit = {
    Services ! WikiAudit(what, e.wid.wpathFull, au.map(_._id), None, Some(e))
  }

  case class EditWiki(label: String, markup: String, content: String, visibility: String, edit: String, oldVer:String, tags: String, notif: String)

  val editForm = Form {
    mapping(
      "label" -> nonEmptyText.verifying(vPorn, vSpec),
      "markup" -> nonEmptyText.verifying("Unknown!", {x:String=> Wikis.markups.contains(x)}),
      "content" -> nonEmptyText,
      "visibility" -> nonEmptyText,
      "wvis" -> nonEmptyText,
      "oldVer" -> nonEmptyText,
      "tags" -> text.verifying(vPorn, vSpec),
      "draft" -> text.verifying(vPorn, vSpec))(EditWiki.apply)(EditWiki.unapply) verifying (
      "Your entry failed the obscenity filter", { ew: EditWiki => !Wikis.hasBadWords(ew.content)
    })
  }

  case class ReportWiki(reason: String)

  val reportForm = Form {
    mapping(
      "reason" -> nonEmptyText)(ReportWiki.apply)(ReportWiki.unapply) verifying (
      "Your entry failed the obscenity filter", { ew: ReportWiki => !Wikis.hasBadWords(ew.reason)
    })
  }

  case class LinkWiki(how: String, notif: String, markup: String, comment: String)

  def linkForm(implicit request: Request[_]) = Form {
    mapping(
      "how" -> nonEmptyText,
      "notif" -> nonEmptyText,
      "markup" -> text.verifying("Unknown!", request.queryString("wc").headOption.exists(_ == "0") || Wikis.markups.contains(_)),
      "comment" -> text)(LinkWiki.apply)(LinkWiki.unapply) verifying (
      "Your entry failed the obscenity filter", { ew: LinkWiki => !Wikis.hasBadWords(ew.comment)
    })
  }

  val addForm = Form(
    "name" -> nonEmptyText.verifying(vPorn, vSpec))

  def renameForm(realm:String) = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name already in use", { t: (String, String) => !Wikis(realm).index.containsName(Wikis.formatName(t._2))
      })
  }

  def replaceAllForm = Form {
    tuple(
      "realm" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "old" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "new" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "action" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("haha", { t: (String, String, String, String) => true
      })
  }
}

/** wiki edits controller */
object Wikie extends WikieBase {
  import Visibility._

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      cat <- request.queryString.get("cat").flatMap(_.headOption);
      name <- request.queryString.get("name").flatMap(_.headOption)
    ) yield {
          wikieEdit(WID(cat, name)).apply(request).value.get.get
      }) getOrElse {
      error("ERR_HACK Wiki.email2")
      Unauthorized("Oops - cannot create this link... " + errCollector.mkString)
    }
  }

  /** serve page for edit */
  def wikieEditnew(wid: WID, noshow: String = "") = wikieEdit(wid, "", noshow)

  def wikieEdit(wid: WID, icontent: String = "", noshow:String="") = FAU {
    implicit au => implicit errCollector => implicit request =>

    def realm = wid.realm getOrElse getRealm()

    val n = Wikis.formatName(wid)

    cdebug << "wikieEdit " + wid

    val x = Wikis.find(wid)
    Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {
      case Some(w) =>
        (for (
          can <- canEdit(wid, Some(au), Some(w));
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki")
        ) yield {
            if(w.markup == Wikis.JS || w.markup == Wikis.JSON || w.markup == Wikis.SCALA)
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEditJS(w.wid,
                  Map.empty,
                  editForm.fill(
                    EditWiki(w.label,
                      w.markup,
                      w.content,
                      w.props.get("visibility").orElse(Reactors(wid.getRealm).props.prop("default.visibility")).getOrElse(PUBLIC),
                      wvis(Some(w.props)).orElse(Reactors(wid.getRealm).props.prop("default.wvis")).getOrElse(PUBLIC),
                      w.ver.toString,
                      w.tags.mkString(","),
                      w.props.get("draft").getOrElse("Notify")))),
                Seq.empty
                )
              }
          else
              ROK(Some(au), request) noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEdit(w.wid, editForm.fill(
                  EditWiki(w.label,
                    w.markup,
                    w.content,
                    w.props.get("visibility").orElse(Reactors(realm).props.prop("default.visibility")).getOrElse(PUBLIC),
                    wvis(Some(w.props)).orElse(Reactors(realm).props.prop("default.wvis")).getOrElse(PUBLIC),
                    w.ver.toString,
                    w.tags.mkString(","),
                    w.props.get("draft").getOrElse("Notify"))), noshow),
                  Seq.empty
                )
              }
        }) getOrElse
          noPerm(wid, "EDIT")
      case None =>
        val parentProps = wid.findParent.map(_.props)
        (for (
          can <- canEdit(wid, Some(au), None, parentProps);
          r3 <- ("any" != wid.cat) orErr ("can't create in category any");
          w <- WikiDomain(realm).rdom.classes.get(wid.cat) orErr (s"cannot find the category ${wid.cat} realm $realm");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki")
        ) yield {
          Audit.missingPage("wiki " + wid);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(wid, Wikis.MD, wid.name, None).fold(WAST.context(None))
          val props = preprocessed.props
          val contentFromTags = props.foldLeft("") { (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          val visibility = wid.findParent.flatMap(_.props.get("visibility")).orElse(Reactors(realm).props.prop("default.visibility")).getOrElse(Reactors(realm).wiki.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))
          val wwvis = wvis(wid.findParent.map(_.props)).orElse(Reactors(realm).props.prop("default.wvis")).getOrElse(Reactors(realm).wiki.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))

          ROK(Some(au), request) noLayout  { implicit stok =>
            views.html.util.reactorLayout12FullPage(
            views.html.wiki.wikiEdit(wid, editForm.fill(
              EditWiki(wid.name.replaceAll("_", " "),
                Wikis.MD,
                contentFromTags + icontent + "\nEdit content here",
                visibility,
                wwvis,
                "0",
                (if ("Topic" == wid.cat) "" else wid.cat.toLowerCase),
                Reactors(realm).props.prop("default.editMode").getOrElse("Notify"))), noshow),
              Seq.empty
            )
          }
        }) getOrElse
          noPerm(wid, "EDIT")
    }
  }

  private def signScripts (iwe:WikiEntry, au:User) = {
    var we = iwe
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
  we
  }

  /** api to set content remotely - used by sync and such */
  def setContent(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      def fromJ (s:String) = {
        val dbo = com.mongodb.util.JSON.parse(s).asInstanceOf[DBObject];
        Some(grater[WikiEntry].asObject(dbo))
      }

      val data = PlayTools.postData

      log("Wiki.setContent " + wid)
      Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {
        case Some(w) =>
          (for (
            can <- canEdit(wid, auth, Some(w)) orErr "can't edit";
            r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
            wej <- data.get("we") orErr "bad we";
            remote <- fromJ (wej) orErr "can't J";
            hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
            nochange <- (w.content != remote.content || w.tags != remote.tags || w.props != remote.props) orErr ("no change");
            newVer <- Some(w.copy(content=remote.content, tags = remote.tags, props = remote.props, ver=w.ver+1, updDtm=remote.updDtm));
            upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
          ) yield {
            var we = newVer

            we = signScripts(we, au)

            if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Config.isLocalhost)) {
              noPerm(wid, "HACK_SCRIPTS1")
            } else {
              razie.db.tx("Wiki.setContent") { implicit txn =>
                WikiEntryOld(w, Some("setContent")).create
                w.update(we, Some("setContent"))
                Emailer.withSession { implicit mailSession =>
                  au.quota.incUpdates
//                      au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                }
              }
              Services ! WikiAudit(WikiAudit.UPD_SET_CONTENT, w.wid.wpathFull, Some(au._id), None, Some(w))

              Ok("ok")
            }
          }) getOrElse
            Unauthorized("oops - " + errCollector.mkString)
        case None => {
          (for (
            r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
            hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
            wej <- data.get("we") orErr "bad we";
            w <- fromJ (wej) orErr "can't J";
            can <- canEdit(wid, auth, Some(w));
            newVer <- Some(w.copy(ver=1, updDtm=DateTime.now)); // not copying over history so reset to now
            upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
          ) yield {
              var we = newVer

              we = signScripts(we, au)

              if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Config.isLocalhost)) {
                noPerm(wid, "HACK_SCRIPTS1")
              } else {
                razie.db.tx("Wiki.setContent") { implicit txn =>
                  we.create
                  Services ! WikiAudit(WikiAudit.CREATE_API, we.wid.wpathFull, Some(au._id), None, Some(we))
                }

                Ok("ok")
              }
            }) getOrElse
            Unauthorized("oops - " + errCollector.mkString)
        }
      }
  }


  /** POST new content to preview an edited wiki */
  def preview(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>
    val data = PlayTools.postData
      val content = data("content")
    val page = WikiEntry(wid.cat, wid.name, wid.name, "md", content, au._id)

    ROK.s noLayout {implicit stok=> views.html.wiki.wikiFrag(wid, Some(au), true, Some(page))}
  }

  /** save an edited wiki - either new or updated */
  def save(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

    editForm.bindFromRequest.fold(
    formWithErrors => {
      log(formWithErrors.toString)
      val markup = formWithErrors("markup").value.mkString
      if(Wikis.markups.isDsl(markup))
        //todo mod/plugin/factory for these views to allow future languages
        ROK.s noLayout {implicit stok=> views.html.wiki.wikiEditJS(wid, Map.empty, formWithErrors)}
      else
        ROK.s badRequest { implicit stok =>
          views.html.wiki.wikiEdit(wid, formWithErrors, "")
        }
    },
    {
      case we @ EditWiki(l, m, co, vis, wvis, oldVer, tags, notif) => {
        log("Wiki.save " + wid)
        val x = Wikis.find(wid)
        Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {
          case Some(w) =>
            (for (
              can <- canEdit(wid, auth, Some(w));
              r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
              hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
              nochange <- (w.label != l || w.markup != m || w.content != co ||
                (!w.props.get("visibility").exists(_ == vis)) ||
                (!w.props.get("wvis").exists(_ == wvis)) ||
                w.props.get("draft").map(_ != notif).getOrElse(false) ||
                // temp allow admin to reset to draft
                (au.isAdmin && notif == "Draft" && !w.props.contains("draft")) ||
                w.tags.mkString(",") != tags) orErr ("no change");
              conflict <- (oldVer == w.ver.toString) orCorr new Corr ("Topic modified in between", "Edit this last vesion and make your changes again.");
            //todo make this better: lock for editing or some kind of locks
              newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
              newVer <- Some(w.cloneNewVer(newlab, m, co, au._id));
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              // visibility?
              if (! we.props.get("visibility").exists(_ == vis))
                we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
              if (! we.props.get("wvis").exists(_ == wvis))
                we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)

              // allow published to be set to draft
              if (au.isAdmin && notif == "Draft" && !we.props.contains("draft")) {
                we = we.cloneProps(we.props ++ Map("draft" -> notif), au._id)
              }

              // moved from draft to else
              val shouldPublish =
                if (notif != "Draft" && we.props.contains("draft")) {
                  if (we.wid.cats == "Post")
                    we = we.copy(props = we.props - "draft", crDtm = DateTime.now) // reset created time
                  else
                    we = we.copy(props = we.props - "draft")

                  // update link to parent if any to non-draft
                  we.wid.parentWid.flatMap(_.uwid).foreach { puwid =>
                    val wl = ROne[WikiLink]("from" -> we.uwid.grated, "to" -> puwid.grated, "how" -> "Child").toList
                    wl.foreach( _.copy(draft=None).update )
                  }

                  notif == "Notify" // Silent means no notif
                } else false

              if (we.tags.mkString(",") != tags)
                we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

                we = signScripts(we, au)

              if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Config.isLocalhost)) {
                noPerm(wid, "HACK_SCRIPTS1")
              } else {
                razie.db.tx("Wiki.Save") { implicit txn =>
                  // can only change label of links OR if the formatted name doesn't change
                  w.update(we)
                  Emailer.withSession { implicit mailSession =>
                    au.quota.incUpdates
                    if (shouldPublish) notifyFollowersCreate(we, au)
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                }
                Services ! WikiAudit(WikiAudit.UPD_EDIT, w.wid.wpathFull, Some(au._id), None, Some(we), Some(w))

                Redirect(controllers.Wiki.wr(we.wid, getRealm(), true)).flashing("count" -> "0")
              }
            }) getOrElse
              Redirect(controllers.Wiki.wr(wid, getRealm(), false)) // no change
          case None =>
            // create a new topic
            val parent= wid.findParent
            val parentProps = parent.map(_.props)
            (for (
              can <- canEdit(wid, auth, None, parentProps);
              hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
              r3 <- ("any" != wid.cat) orErr ("can't create in category any");
              w <- WikiDomain(wid.realm getOrElse getRealm()).rdom.classes.get(wid.cat) orErr (s"cannot find the category ${wid.cat} realm ${wid.getRealm}");
              r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission("uWiki")
            ) yield {
              //todo find the right realm from the url or something like Config.realm
              import razie.OR._
              var we = WikiEntry(wid.cat, wid.name, l, m, co, au._id, Seq(), parent.map(_.realm) orElse wid.realm getOrElse getRealm(), 1, wid.parent)

              if (we.tags.mkString(",") != tags)
                we = we.withTags(tags.split(",").map(_.trim).toSeq, au._id)

              // special properties
              we.preprocess(Some(au))
              //todo verify permissiont o create in realm
              //                if (wep.props.get("realm").exists(_ != we.realm))
              //                  we = we.copy(realm=wep.props("realm"))

              // needs owner?
              if (WikiDomain(wid.getRealm).needsOwner(wid.cat)) {
                we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                model.UserWiki(au._id, we.uwid, "Owner").create
                cleanAuth()
              }

              // visibility?
              we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
              we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)
              if (notif == "Draft")
                we = we.cloneProps(we.props ++ Map("draft" -> notif), au._id)

              razie.db.tx("wiki.create") { implicit txn =>
                // anything staged for this?
                for (s <- Staged.find("WikiLinkStaged").filter(x => x.content.get("from").asInstanceOf[DBObject].get("cat") == wid.cat && x.content.get("from").asInstanceOf[DBObject].get("name") == wid.name)) {
                  val wls = Wikis.fromGrated[WikiLinkStaged](s.content)
                  for(ufrom <- wls.from.uwid;
                      uto <- wls.to.uwid) {
                    val wl = WikiLink(ufrom, uto, wls.how)
                    wl.create
                  }
                  we = we.copy(parent = Wikis.find(wls.to).map(_._id), updDtm = DateTime.now) // add parent
                  s.delete
                }

                // needs parent?
                we.wid.parentWid.flatMap(_.uwid).foreach { puwid =>
                  val isd = if(we.props.contains("draft")) Some("y") else None
                  val wl = ROne[WikiLink]("from" -> we.uwid.grated, "to" -> puwid.grated, "how" -> "Child")
                  if(wl.isDefined)
                    wl.foreach(_.copy(draft=isd).update)
                  else
                    WikiLink(we.uwid, puwid, "Child", isd).create
                }

                we = we.cloneProps(we.props ++ Map("titi" -> "t"), au._id)

                // needs parent owner? // the context of the page
                we.findParent.flatMap(_.props.get("owner")).foreach { po =>
                  if (!we.props.get("owner").exists(_ == po))
                    we = we.cloneProps(we.props ++ Map("parentOwner" -> po), au._id)
                }

                we.create
                Services ! WikiAudit(WikiAudit.CREATE_WIKI, we.wid.wpathFull, Some(au._id), None, Some(we))

                SendEmail.withSession { implicit mailSession =>
                  au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)
                  if (notif == "Notify") notifyFollowersCreate(we, au)
                  Emailer.tellRaz("New Wiki", au.userName, wid.ahref)
                }
              }

              Redirect(controllers.Wiki.wr(we.wid, getRealm(), true)).flashing("count" -> "0")
            }) getOrElse
              noPerm(wid, "HACK_SAVEEDIT")
        }
      }
    })
  }

  /** notify all followers of new topic/post */
  private def notifyFollowersCreate(wpost: WikiEntry, au: User)(implicit mailSession: MailSession) = {
    // 1. followers of this topic or followers of parent

    wpost.parent flatMap (Wikis(wpost.realm).find) map { w =>
      // user wikis
      (Users.findUserById(w.by).map(_._id).toList ++ model.Users.findUserLinksTo(w.uwid).filter(_.notif == model.UW.EMAIL_EACH).toList.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).map{u =>
          Emailer.sendEmailNewTopic(u, au, w, wpost)
        })

      // add posts to their feed, regardless of their notification
      (Users.findUserById(w.by).map(_._id).toList ++ model.Users.findUserLinksTo(w.uwid).toList.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).map{u =>
          u.myself.history.post(wpost, au)
        })

      // followers by email
      model.Users.findFollowerLinksTo(w.uwid).toList.groupBy(_.followerId).values.map(_.head).map(flink =>
        flink.follower.map(follower => {
          Emailer.sendEmailFollowerNewTopic(follower.email.dec, au, w.wid, wpost, flink.comment)
        }))
    }
  }

  /** screen to report a page */
  def report(wid: WID) = Action { implicit request =>
    auth match {
      case Some(user) =>
        ROK s(user,request) apply {implicit stok=> views.html.wiki.wikieReport(wid, reportForm.fill(ReportWiki("")))}
      case None => {
        clog << "need logged in to report a wiki"
        val msg = "You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page..."
        Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid)))
      }
    }
  }

  /** reported a page */
  def reported(wid: WID) = Action { implicit request =>
    reportForm.bindFromRequest.fold(
    formWithErrors => ROK.r badRequest {implicit stok=> views.html.wiki.wikieReport(wid, formWithErrors)},
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

  /** POSTed from category, has name -> create topic in edit mode */
  def addWithName(cat: String) = FAU {
    implicit au => implicit errCollector => implicit request =>
    val realm = CAT.unapply(cat).flatMap(_.realm).getOrElse(getRealm())
    addForm.bindFromRequest.fold(
    formWithErrors => Msg2("Oops, can't add that name!" + formWithErrors, Some("/wiki/" + cat)),
    {
      case name: String => {
        WikiDomain(realm).zEnds(cat, "Template").headOption.map { t =>
          ROK.s apply { implicit stok =>(views.html.wiki.wikieAddWithSpec(cat, name, "Template", t, realm))}
        } orElse
//          WikiDomain(realm).zEnds(cat, "Spec").headOption.map { t =>
        WikiDomain(realm).assocsWhereTheyHaveRole(cat, "Spec").headOption.map { t =>
          ROK.s apply { implicit stok =>(views.html.wiki.wikieAddWithSpec(cat, name, "Spec", t, realm))}
        } getOrElse
          Redirect(routes.Wikie.wikieEditnew(WID(cat, name).r(realm), ""))
      }
    })
  }

  // from category - add a ...
  def addWithSpec(cat: String, iname:String, templateWpath:String, torspec:String, realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      val name = PlayTools.postData.getOrElse("name", iname)
      val E = " - go back and try another name..."
      val e =
        if(name.length < 3 && !au.isAdmin) "Name too short"
      else if (name.length > 12 && !au.isAdmin) "Name too long"
      else if (Config.reservedNames.contains(name)) "Name is reserved"
      else if (!name.matches("(?![-_])[A-Za-z0-9-_]{1,63}(?<![-_])")) "Name cannot contain special characters"
      else ""

      if(e.isEmpty) Realm.createR2(cat, templateWpath, torspec:String, realm).apply(request).value.get.get
      else Msg2("Error: " + Corr(e, Some(E)).toString)
  }

  // create reactor
  def createReactor = FAU {
    implicit au => implicit errCollector => implicit request =>
      ROK.s apply { implicit stok =>
        views.html.wiki.wikieAddWithSpec("Reactor", "", "Template", "ReactorTemplate", "wiki")
      }
  }

  // select template here, then redirect to name
  def addWithSpec1(cat: String, tcat:String, realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      ROK.s apply { implicit stok =>
        views.html.wiki.wikieAddWithSpec(cat, "", "Template", tcat, realm)
      }
  }

  // selected the template, continue to capture name and form
  def addWithSpec2(cat: String, name:String, templateWpath:String, torspec:String, realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      WID.fromPath(templateWpath).flatMap(_.page).map { tpage =>
        // if there is a section called "form", use that WID
        val wid = tpage.sections.find(_.name == "form").map(_.wid).getOrElse(tpage.wid)
        // either make from DOM or use
        val formPage =
          if(WikiDomain(realm).isWikiCategory(cat)) wid.page
          else Some(Diesel.mkFormDef(realm, WikiDomain(realm).rdom.classes.get(cat).get, "temp", au))
        ROK.s apply { implicit stok =>
          views.html.wiki.wikieAddWithSpec2(cat, name, wid, formPage, torspec, Map.empty)
        }
      } getOrElse
        Msg2(s"Can't find template [[$templateWpath]]")
  }

  /** add a child/related to another topic - this stages the link and begins creation of kid.
    *
    * At the end, the staged link is persisted */
  def addLinked(cat: String, pwid: WID, role: String) = FAU {
    implicit au => implicit errCollector => implicit request =>
    addForm.bindFromRequest.fold(
    formWithErrors => Msg2("Oops, can't add that name!", Some(pwid.urlRelative)),
    {
      case xname: String => {
        val name = xname.replaceAll("/", "_") // it messes up a lot of stuff... can't have it
        val n = Wikis.formatName(WID(cat, name).r(pwid.getRealm))
        Stage("WikiLinkStaged", WikiLinkStaged(WID(cat, n, pwid.findId).r(pwid.getRealm), pwid, role).grated, au.userName).create
        Redirect(routes.Wikie.wikieEditnew(WID(cat, name, pwid.findId).r(pwid.getRealm), ""))
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
  def wikieDelete1(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      log("Wiki.delete1 " + wid)
      canDelete(wid).collect {
        case (au, w) =>
          Msg2C(
            views.html.wiki.msg.wmDelete(w.uwid).body,
            Some(routes.Wikie.wikieDelete2(wid)))
      } getOrElse
        noPerm(wid, "ADMIN_DELETE1")
  }

  /** delete step 2: do it */
  def wikieDelete2(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      log("Wiki.delete2 " + wid)
      if (wid.cat != "Club") canDelete(wid).collect {
        // delete all reactor pages
        case (au, w) if wid.cat == "Reactor" => {
          var count = 0
          val realm = wid.name
          razie.db.tx("Wiki.delete") { implicit txn =>
            RMany[WikiEntry]("realm" -> wid.name).toList.map {we=>
              count += 1
              we.delete(au.userName)
              // todo delete AutoSaves for reactor
            }
            Reactors.reload(realm)
            Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, Some(au._id), None, Some(w), None, Some(w._id.toString))
            cleanAuth()
          }
          Msg2(s"REACTOR DELETED forever - no way back! Deleted $count topics")
        }

        case (au, w) => {
          var count = 0
          razie.db.tx("Wiki.delete") { implicit txn =>
            RMany[WikiLink]("to" -> w.uwid.grated).toList.foreach(_.delete)
            RMany[WikiLink]("from" -> w.uwid.grated).toList.foreach(_.delete)
            var done = false
            RMany[UserWiki]("uwid" -> w.uwid.grated).toList.foreach(wl => {
              wl.delete
              done = true
            })
            Comments.findForWiki(w._id).toList.foreach(cs => {
              cs.delete
            })

            // can only change label of links OR if the formatted name doesn't change
            // delete at last, so if any links fail, the thing stays there
            w.delete(au.userName)
            count += 1
            Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, Some(au._id), None, Some(w), None, Some(w._id.toString))

            if (done) cleanAuth() // it probably belongs to the current user, cached...
          }
          Msg2(s"DELETED forever - no way back! Deleted $count topics")
        }
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
  def wikieRename1(wid: WID) = FAU("rename.wiki.1") { implicit au => implicit errCollector => implicit request =>
    canRename(wid).collect {
      case (au, w) =>
        ROK(Some(au), request) apply {implicit stok=> views.html.wiki.wikieRename(wid, renameForm(wid.getRealm).fill((w.label, w.label)), auth)}
    }
  }

  /** rename step 2: do it */
  def wikieRename2(wid: WID) = FAU("rename.wiki") { implicit au => implicit errCollector => implicit request =>
    renameForm(wid.getRealm).bindFromRequest.fold(
    formWithErrors => Some(ROK.r badRequest {implicit stok=> views.html.wiki.wikieRename(wid, formWithErrors, auth)}),
    {
      case (_, n) =>
        canRename(wid).collect {
          case (au, w) =>
            val newp = w.copy(name = Wikis.formatName(n), label = n, ver = w.ver + 1, updDtm = DateTime.now)
            razie.db.tx("Wiki.Rename") { implicit txn =>
              w.update(newp, Some("renamed"))
              Services ! WikiAudit(WikiAudit.UPD_RENAME, newp.wid.wpathFull, Some(au._id), None, Some(newp), Some(w), Some(w.wid.wpathFull))
              cleanAuth()
            }

            Msg("OK, renamed!", newp.wid)
        }
    })
  }

  /** link a whatever that is - find topics of that cat */
  def wikieLike(cats: String) = FAU("like.wiki") {
    implicit au => implicit errCollector => implicit request =>
    for (
      cat <- CAT.unapply(cats);
      w   <- Wikis(cat.realm getOrElse getRealm()).category(cat.cat)
    ) yield {
      ROK(Some(au), request) { implicit stok =>
        assert(stok.realm == (cat.realm getOrElse getRealm()))
        views.html.wiki.wikieLike(w.wid, Some(w))
      }
    }
  }

  def wikieCreate(cats: String) = FAU("create.wiki") {
    implicit au => implicit errCollector => implicit request =>
      for (
        cat <- CAT.unapply(cats);
        w   <- Wikis(cat.realm getOrElse getRealm()).category(cat.cat)
      ) yield {
        ROK(Some(au), request) noLayout { implicit stok =>
          assert(stok.realm == (cat.realm getOrElse getRealm()))
          views.html.wiki.wikieCreate(cat.cat)
        }
      }
  }

  def manage(id: String) = FAU("manage.wiki") {
    implicit au => implicit errCollector => implicit request =>
    for (
      w <- Wikis(getRealm()).findById(id)
    ) yield {
      ROK(Some(au), request) noLayout { implicit stok =>
        views.html.wiki.wikieManage(Some(w))
      }
    }
  }

  /** move the posts of another blof to another or just one post if this is it */
  def movePosts(sourceWid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val form = Form("newWid" -> nonEmptyText)

      form.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops - the form is errored out, man!"),
      {
        case newWid =>
          log("Wiki.movePosts " + sourceWid + ", " + newWid)
          (for (
            au <- activeUser;
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            sourceW <- Wikis.find(sourceWid);
            destWid <- WID.fromPath(newWid) orErr "no source";
            destW <- Wikis.find(destWid) orErr "no destination";
//            isFromPost <- ArWikiDomain.aEnds(sourceW.wid.cat, "Child").contains("Post") orErr "source has no child Posts/Items";
//            isToPost <- WikiDomain.aEnds(destW.wid.cat, "Child").contains("Post") orErr "dest has no child Posts/Items"
//            upd <- before(newVer, WikiEntry.UPD_UOWNER) orErr ("Not allowerd")
            nochange <- (sourceW.wid != destW.wid) orErr "no change"
          ) yield {
//            val links = RMany[WikiLink]("to" -> sourceW.uwid.grated, "how" -> "Child", "from.cat" -> "Post").toList
//            val pages = RMany[WikiEntry]("parent" -> Some(sourceW.uwid.id), "category"->"Post").toList

            val links =
              if(sourceWid.cat == "Post")
                ROne[WikiLink]("from" -> sourceW.uwid.grated, "how" -> "Child").orElse (
                  Some(WikiLink(sourceW.uwid, destW.uwid, "Child")).map(x=>{x.create; x})).toList
              else
                RMany[WikiLink]("to" -> sourceW.uwid.grated, "how" -> "Child").toList

            val pages =
              if(sourceWid.cat == "Post")
                List(sourceW)
              else
                RMany[WikiEntry]("parent" -> Some(sourceW.uwid.id)).toList
            razie.db.tx("Wiki.movePosts") { implicit txn =>
              links.foreach { _.copy(to = destW.uwid).update }
              pages.foreach{w=>
                w.update(w.copy(parent=Some(destW.uwid.id)), Some("moved_posts"))
                Services ! WikiAudit(WikiAudit.UPD_PARENT, sourceW.wid.wpathFull, Some(au._id))
              }
            }
            val m = s" ${links.size} WikiLinks and ${pages.size} WikiEntry /posts from ${sourceW.wid.wpath} to ${destW.wid.wpath}"
            Services ! WikiAudit(WikiAudit.MOVE_POSTS, sourceW.wid.wpathFull, Some(au._id), Some(m))
            Msg2(s"Moved $m", Some(controllers.Wiki.w(sourceWid)))
          }) getOrElse
            noPerm(sourceWid, "ADMIN_MOVEPOSTS")
      })
  }

  /** update parent */
  def setParent(sourceWid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val form = Form("newWid" -> nonEmptyText)

      form.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
      {
        case newWid if newWid == "n/a" =>
          log("Wiki.setParent REMOVE" + sourceWid + ", " + newWid)
          (for (
            au <- activeUser;
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            sourceW <- Wikis.find(sourceWid);
            newVer <- Some(sourceW.copy(parent=None));
            upd <- before(newVer, WikiAudit.UPD_SETP_PARENT) orErr ("Not allowerd")
          ) yield {
              razie.db.tx("Wiki.setparent") { implicit txn =>
                sourceW.update(newVer, Some("setParent"))
                ROne[WikiLink]("from" -> sourceW.uwid.grated, "how" -> "Child").foreach(_.delete)
              }
              Services ! WikiAudit(WikiAudit.UPD_SETP_PARENT, sourceW.wid.wpathFull, Some(au._id), Some(newWid.toString))
              Redirect(controllers.Wiki.w(sourceWid))
            }) getOrElse
            noPerm(sourceWid, "ADMIN_MOVEPOSTS")
        case newWid =>
          log("Wiki.setParent " + sourceWid + ", " + newWid)
          (for (
            au <- activeUser;
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            sourceW <- Wikis.find(sourceWid);
            destWid <- WID.fromPath(newWid) orErr "no dest found";
            destW <- Wikis.find(destWid) orErr "no destination";
            //            isFromPost <- ArWikiDomain.aEnds(sourceW.wid.cat, "Child").contains("Post") orErr "source has no child Posts/Items";
            //            isToPost <- WikiDomain.aEnds(destW.wid.cat, "Child").contains("Post") orErr "dest has no child Posts/Items"
            newVer <- Some(sourceW.copy(parent=Some(destW._id)));
            upd <- before(newVer, WikiAudit.UPD_SETP_PARENT) orErr ("Not allowerd")
          ) yield {
            razie.db.tx("Wiki.setparent") { implicit txn =>
              sourceW.update(newVer, Some("setParent"))
              ROne[WikiLink]("from" -> sourceW.uwid.grated, "how" -> "Child").foreach(_.delete)
              WikiLink(sourceW.uwid, destW.uwid, "Child").create
            }
            Services ! WikiAudit(WikiAudit.UPD_SETP_PARENT, sourceW.wid.wpathFull, Some(au._id), Some(newWid.toString))
            Redirect(controllers.Wiki.w(sourceWid))
          }) getOrElse
            noPerm(sourceWid, "ADMIN_MOVEPOSTS")
      })
  }

  /** change owner */
  def update(what:String, wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

    val uownerForm = Form("newvalue" -> nonEmptyText)

    uownerForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops, can't add that quota!"),
    {
      case newvalue =>
        what match {
          case "owner" => {
            log("Wiki.uowner " + wid + ", " + newvalue)
            (for (
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid);
              newu <- WikiUsers.impl.findUserByUsername(newvalue) orErr (newvalue + " User not found");
              nochange <- (!w.owner.exists(_.userName == newvalue)) orErr "no change";
              newVer <- Some(w.cloneProps(w.props + ("owner" -> newu._id.toString), au._id));
              upd <- before(newVer, WikiAudit.UPD_UOWNER) orErr "Not allowerd"
            ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Wiki.uowner") { implicit txn =>
                w.update(newVer)
              }
              Wikie.after(newVer, WikiAudit.UPD_UOWNER, Some(au))
              Redirect(controllers.Wiki.w(wid))
            }) getOrElse
              noPerm(wid, "ADMIN_UOWNER")
          }
          case "category" => {
            log("Wiki.category " + wid + ", " + newvalue)
            (for (
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid);
              nochange <- (w.category != newvalue) orErr "no change";
              newVer <- Some(w.copy(category=newvalue, ver = w.ver+1));
              upd <- before(newVer, WikiAudit.UPD_UOWNER) orErr "Not allowerd"
            ) yield {
                // can only change label of links OR if the formatted name doesn't change
                razie.db.tx("Wiki.ucategory") { implicit txn =>
                  w.update(newVer)
                }
                Wikie.after(newVer, WikiAudit.UPD_CATEGORY, Some(au))
                Redirect(controllers.Wiki.w(newVer.wid))
              }) getOrElse
              noPerm(wid, "ADMIN_UCATEGORY")
          }
          case "counter" => {
            log("Wiki.counter " + wid + ", " + newvalue)
            (for (
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid)
            ) yield {
                // can only change label of links OR if the formatted name doesn't change
                razie.db.tx("Wiki.counter") { implicit txn =>
                  WikiCount.findOne(w._id).foreach(_.set(newvalue.toLong))
                }
                Redirect(controllers.Wiki.w(wid))
              }) getOrElse
              noPerm(wid, "ADMIN_UOWNER")
          }
          case "realm" => {
            log("Wiki.urealm " + wid + ", " + newvalue)
            (for (
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid);
              nochange <- (w.realm != newvalue) orErr "no change";
              newVer <- Some(w.copy(realm=newvalue, ver = w.ver+1));
              upd <- before(newVer, WikiAudit.UPD_UOWNER) orErr "Not allowerd"
            ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Wiki.urealm") { implicit txn =>
                w.update(newVer)
              }
              Wikie.after(newVer, WikiAudit.UPD_REALM, Some(au))
              Redirect(controllers.Wiki.w(wid))
            }) getOrElse
              noPerm(wid, "ADMIN_UOWNER")
          }
          case "realmALL" => {
            log("Wiki.urealmALL " + wid + ", " + newvalue)
            (for (
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid);
//              nochange <- (w.realm != newvalue) orErr "no change";  // don't check nochange
              newVer <- Some(w.copy(realm=newvalue, ver = w.ver+1));
              upd <- before(newVer, WikiAudit.UPD_UOWNER) orErr "Not allowerd"
            ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Wiki.urealmALL") { implicit txn =>
                w.update(newVer)
                RMany[WikiLink]("to" -> w.uwid.grated, "how"->"Child").toList.foreach{ link=>
                  link.delete
                  link.pageFrom.map{p=>
                    val c = p.copy(realm=newvalue, ver = p.ver+1)
                    p.update(c)
                    link.copy(to = newVer.uwid, from = c.uwid).create
                  } getOrElse {
                    link.copy(to = newVer.uwid).create
                  }
                }
              }
              Wikie.after(newVer, WikiAudit.UPD_REALM, Some(au))
              Redirect(controllers.Wiki.w(wid))
            }) getOrElse
              noPerm(wid, "ADMIN_UOWNER")
          }
        }
    })
  }

  /** mark a wiki as reserved - only admin can edit */
  def reserve(wid: WID, how: Boolean) = FAU("wikie.reserve") {
    implicit au => implicit errCollector => implicit request =>

    log("Wiki.reserve " + wid)
    for (
      au <- activeUser;
      w <- Wikis.find(wid);
      ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
      ok2 <- canEdit(wid, Some(au), Some(w));
      nochange <- (w.isReserved != how) orErr "no change";
      newVer <- Some(w.cloneProps(w.props + ("reserved" -> (if (how) "yes" else "no")), au._id));
      upd <- before(newVer, WikiAudit.UPD_TOGGLE_RESERVED) orErr ("Not allowed")
    ) yield {
      // can only change label of links OR if the formatted name doesn't change
      razie.db.tx("Wiki.Reserve") { implicit txn =>
        w.update(newVer, Some("reserve"))
      }
      Wikie.after(newVer, WikiAudit.UPD_TOGGLE_RESERVED, Some(au))
      Redirect(controllers.Wiki.w(wid))
    }
  }

  /** someone likes a wiki */
  def like(wid: WID, how: Int) = FAU("wikie.like") {
    implicit au => implicit errCollector => implicit request =>

      log("Wiki.like " + wid)
      val like    = if(how==1) Some(au) else None
      val dislike = if(how==0)Some(au) else None
      for (
        au <- activeUser;
        w <- Wikis.find(wid);
        newVer <- Some(w.copy (likes=like.map(_._id.toString).toList ::: w.likes, dislikes=dislike.map(_._id.toString).toList ::: w.dislikes));
        upd <- before(newVer, WikiAudit.UPD_LIKE) orErr ("Not allowed")
      ) yield {
        razie.db.tx("Wiki.like") { implicit txn =>
          RUpdate.noAudit[WikiEntry](Wikis(w.realm).weTables(wid.cat), Map("_id" -> newVer._id), newVer)
        }
        Wikie.after(newVer, WikiAudit.UPD_LIKE, Some(au))
        Redirect(controllers.Wiki.w(wid))
      }
  }

  /** move to new parent */
  def wikieMove1(id:String, realm:String=Wikis.RK) =  FAU("wikie.move") {
    implicit au => implicit errCollector => implicit request =>
    for (
      w <- Wikis(realm).findById(id)
    ) yield {
      val parentCats1 = WikiDomain(realm).zEnds(w.wid.cat, "Parent")
      val parents = parentCats1.flatMap {c=>
        Wikis(realm).pages(c).filter(w=>canEdit(w.wid, auth, Some(w)).exists(_ == true)).map(w=>(w.uwid, w.label))
      }
      if(parents.size > 0)
        ROK(Some(au), request) apply { implicit stok =>
          views.html.wiki.wikiMove(w.wid, w, parents)
        }
      else
        Redirect(s"/wiki/id/$id")
    }
  }

  /** move to new parent */
  def wikieMove2(page:String, from:String, to:String, realm:String=Wikis.RK) = FAU("wikie.move") {
    implicit au => implicit errCollector => implicit request =>
    for (
      pageW <- Wikis(realm).findById(page);
      fromW <- Wikis(realm).findById(from);
      toW <- Wikis(realm).findById(to);
      hasP <- pageW.parent.exists(_.toString == from) orErr "does not have a parent"
    ) yield {
      razie.db.tx("Wiki.Move") { implicit txn =>
        pageW.update(pageW.copy(parent=Some(toW._id)))
        RMany[WikiLink]("from" -> pageW.uwid.grated, "to" -> fromW.uwid.grated, "how"->"Child").toList.foreach{ link=>
          link.delete
          link.copy(to = toW.uwid).create
        }
      }
      Redirect(controllers.Wiki.w(pageW.wid, false)).flashing("count" -> "0")
    }
  }

  /** START find and replace content in pages */
  def replaceAll1() = FAU {
    implicit au => implicit errCollector => implicit request =>
    Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill("", "", "", ""), false))
  }

  /** DO find and replace content in pages */
  def replaceAll3() = FAU {
    implicit au => implicit errCollector => implicit request =>
      replaceAllForm.bindFromRequest.fold(
      formWithErrors => Msg2(formWithErrors.toString + "Oops, can't !"), {
        case (realm, q, news, action) =>
          log("replace all " + q + " -> " + news)

          def update (u:DBObject):DBObject = {
            Audit.logdb("replace ", ""+u.get("name"))
            u.put("content", u.get("content").asInstanceOf[String].replaceAll(q, news))
            u
          }

          if("replace" == action && au.isAdmin) {
            for (
              (u, m) <- isearch(q, realm, Some(update))
            ) {
            }
          }
          Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill(realm, q, news, ""), false))
      })
  }

  /** DO find and replace content in pages */
  def replaceAllTag3() = FAU {
    implicit au => implicit errCollector => implicit request =>
      replaceAllForm.bindFromRequest.fold(
        formWithErrors => Msg2(formWithErrors.toString + "Oops, can't !"), {
        case (realm, q, news, action) =>
          log("replace all tag" + q + " -> " + news)

          def update (u:DBObject):DBObject = {
            Audit.logdb("replace ", ""+u.get("name"))
            val s = u.as[Seq[String]]("tags").filter(_ != q)
            if(news.length > 0)
              u.put("tags", (s ++ Seq(news)))
            else
              u.put("tags", s)
            u
          }

          if("replace" == action && au.isAdmin) {
              val table = RazMongo("WikiEntry")

              val wikis =
                for (
                  u <- table.findAll() if (realm.length == 0 || u.get("realm") == realm) && q.length >= 3 && u.containsField("tags") && u.as[Seq[String]]("tags").contains(q)
                ) yield {
                  table.save(update(u))
                  u
                }
            cout << "FOUND: " + wikis.size
            }
          Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill(realm, q, news, ""), true))
      })
  }

  /** search string AND update content if update function present */
  private def isearch(qi: String, realm:String, update:Option[DBObject=>DBObject]=None) = {
    val PAT = qi.r
    val table = RazMongo("WikiEntry")

    val wikis =
        for (
          u <- table.findAll() if qi.length >= 3 && (realm.length == 0 || u.get("realm") == realm);
//          m <- (tag.isDefined && u.get("tags").asInstanceOf[Seq[String]].contains(tag.get) ||
//            PAT.findAllMatchIn(u.get("content").asInstanceOf[String]))
          m <- PAT.findAllMatchIn(u.get("content").asInstanceOf[String])
        ) yield {
          if(update.isDefined) {
            table.save(update.get.apply(u))
          }
          (u, m)
        }
    wikis
  }

  /** simple search for all realms */
  def searchAll(qi: String, realm:String) = {
    val PAT = qi.r
    def min(a:Int, b:Int) = if(a>b)b else a
    def max(a:Int, b:Int) = if(a>b)a else b

    val wikis = isearch(qi, realm).map{t=>
      val (u,m) = t
      (WikiEntry grated u,
        m.before.subSequence(max(0,m.before.length()-5), m.before.length()),
        m.matched,
        m.after.subSequence(0,min(5,m.after.length)))
    }

    val wl = wikis.take(500).toList
    wl
  }

  /** simple search for all realms */
  def searchAllTag(qi: String, realm:String) = {
    val PAT = qi.r
    def min(a:Int, b:Int) = if(a>b)b else a
    def max(a:Int, b:Int) = if(a>b)a else b

    val wikis = Wikis(realm).cats.keys.toList.flatMap(cat=>Wikis(realm).pages(cat).toList).filter(_.tags.contains(qi)).take(500).map{ w=>
      val m = PAT.findAllMatchIn(w.tags.mkString(",")).collectFirst({case x => x}).get
      (w,
        m.before.subSequence(0, m.before.length()),
        m.matched,
        m.after.subSequence(0,m.after.length))
    }

    wikis
  }
}


