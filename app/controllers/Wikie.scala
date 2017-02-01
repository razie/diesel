/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.{Config}
import mod.snow.{RkHistory, RacerKidz}
import razie.base.Audit

import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.novus.salat._
import org.joda.time.DateTime

import com.typesafe.config.{ConfigObject, ConfigValue}
import mod.diesel.model.Diesel
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.wiki.{Services, Enc}
import razie.wiki.admin._
import razie.wiki.dom.WikiDomain
import razie.wiki.parser.WAST
import razie.wiki.util.PlayTools
import razie.{cout, clog, cdebug, Log}
import scala.Array.canBuildFrom
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
    WikiObservers.before(WikiEvent(what, "WikiEntry", e.wid.wpath, Some(e)))
  }
  def after(e: WikiEntry, what: String, au:Option[User])(implicit errCollector: VErrors = IgnoreErrors): Unit = {
    Services ! WikiAudit(what, e.wid.wpathFull, au.map(_._id), None, Some(e))
  }

  case class EditWiki(label: String, markup: String, content: String, visibility: String, edit: String, oldVer:String, tags: String, notif: String)

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
    "name" -> nonEmptyText.verifying(vBadWords, vSpec))

  def renameForm(wid:WID) = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name in same category already in use", { t: (String, String) =>
//        !Wikis(wid.getRealm).index.containsName(Wikis.formatName(t._2))
        !Wikis(wid.getRealm).index.getWids(Wikis.formatName(t._2)).exists(_.cat == wid.cat)
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
  def wikieEditNew(wid: WID, noshow: String = "") =
    wikieEdit(wid, "", noshow)

  def wikieEdit(wid: WID, icontent: String = "", noshow:String="", old:Boolean=true) = FAU {
    implicit au => implicit errCollector => implicit request =>

    val stok = ROK.s

//    val wid = if(ObjectId.isValid(iwid.name)) {
//      Wikis.find(new ObjectId(iwid.name)).get.wid
//    } else iwid
//
    def realm = wid.realm getOrElse getRealm()

    val n = Wikis.formatName(wid)
    val nwid = wid.copy(name=n)

    cdebug << "wikieEdit " + wid

    Wikis.clearCache(wid) // make sure it's loaded from db

    val x = wid.page // better version, uses id version as well as name
    x.filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {

      case Some(w) =>   // existing page: edit

        (for (
          can <- canEdit(wid, Some(au), Some(w));
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki")
        ) yield {
            //look for drafts
            val draft = Autosave.OR("wikie."+w.wid.wpath, stok.au.get._id, Map(
              "content"  -> w.content,
              "tags" -> w.tags.mkString(",")
            ))
            val hasDraft = Autosave.find("wikie."+w.wid.wpath, stok.au.get._id).isDefined

            if(w.markup == Wikis.JS || w.markup == Wikis.JSON || w.markup == Wikis.SCALA)
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEditJS(w.wid,
                  Map.empty,
                  editForm.fill(
                    EditWiki(w.label,
                      w.markup,
                      draft("content"),
                      w.props.get("visibility").orElse(WikiReactors(wid.getRealm).props.prop("default.visibility")).getOrElse(PUBLIC),
                      wvis(Some(w.props)).orElse(WikiReactors(wid.getRealm).props.prop("default.wvis")).getOrElse(PUBLIC),
                      w.ver.toString,
                      draft("tags"),
                      w.props.get("draft").getOrElse("Silent")))),
                Seq.empty
                )
              }
            else if(Wikis.isEvent(wid.cat))
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                  views.html.wiki.wikiEditEvent(w.wid, editForm.fill(
                    EditWiki(w.label,
                      w.markup,
                      draft("content"),
                      w.props.get("visibility").orElse(WikiReactors(realm).props.prop("default.visibility")).getOrElse(PUBLIC),
                      wvis(Some(w.props)).orElse(WikiReactors(realm).props.prop("default.wvis")).getOrElse(PUBLIC),
                      w.ver.toString,
                      draft("tags"),
                      w.props.get("draft").getOrElse("Silent"))), hasDraft, noshow),
                  Seq.empty
                )
              }
          else
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEdit(old, w.wid, editForm.fill(
                  EditWiki(w.label,
                    w.markup,
                    draft("content"),
                    w.props.get("visibility").orElse(WikiReactors(realm).props.prop("default.visibility")).getOrElse(PUBLIC),
                    wvis(Some(w.props)).orElse(WikiReactors(realm).props.prop("default.wvis")).getOrElse(PUBLIC),
                    w.ver.toString,
                    draft("tags"),
                    w.props.get("draft").getOrElse("Silent"))), hasDraft, noshow),
                  Seq.empty
                )
              }
        }) getOrElse
          noPerm(wid, "edit.wiki")

      case None =>   // new page

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

          val visibility = wid.findParent.flatMap(_.props.get("visibility")).orElse(WikiReactors(realm).props.prop("default.visibility")).getOrElse(WikiReactors(realm).wiki.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))
          val wwvis = wvis(wid.findParent.map(_.props)).orElse(WikiReactors(realm).props.prop("default.wvis")).getOrElse(WikiReactors(realm).wiki.visibilityFor(wid.cat).headOption.getOrElse(PUBLIC))
          val draft = wid.findParent.flatMap(_.contentProps.get("editMode")).orElse(WikiReactors(realm).props.prop("default.editMode")).getOrElse("Notify")

            if(Wikis.isEvent(wid.cat))
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                  views.html.wiki.wikiEditEvent(nwid, editForm.fill(
                    EditWiki(wid.name.replaceAll("_", " "),
                      Wikis.MD,
                      contentFromTags + icontent,
                      visibility,
                      wwvis,
                      "0",
                      (if ("Topic" == wid.cat) "" else wid.cat.toLowerCase),
                      draft)), false,
                    noshow),
                  Seq.empty
                )
              }
          else ROK.s noLayout  { implicit stok =>
            views.html.util.reactorLayout12FullPage(
            views.html.wiki.wikiEdit(old, nwid, editForm.fill(
              EditWiki(wid.name.replaceAll("_", " "),
                Wikis.MD,
                contentFromTags + icontent,
                visibility,
                wwvis,
                "0",
                (if ("Topic" == wid.cat) "" else wid.cat.toLowerCase),
                draft)), false,
                noshow),
              Seq.empty
            )
          }
        }) getOrElse
          noPerm(wid, "create.wiki")
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
            if (s.name == (m group 3)) Some("{{%s:%s:%s}}%s{{/%s}}".format(
              (m group 1)+(m group 2), m group 3, sign(s.content), s.content.replaceAll("""\\""", """\\\\""").replaceAll("\\$", "\\\\\\$"), m group 2))
            else None
          })
        }
        we = we.cloneContent(c2)
      }
    }
  we
  }

  // clear all draft versions
  private def clearDrafts (w:WID, au:User) = {
    Autosave.delete("wikie."+w.wpath, au._id)
  }

  def deleteDraft (w:WID) = FAUR { stok=>
    Autosave.delete("wikie."+w.wpath, stok.au.get._id)
    Ok("")
  }

  // clear all draft versions
  def saveDraft (w:WID) = FAUR { implicit stok=>
    val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))
    val realm = w.realm.getOrElse(stok.realm)
    val content = stok.formParm("content")
    val tags = stok.formParm("tags")

    Autosave.set("wikie."+w.wpath, stok.au.get._id,
    Map(
      "content"  -> content,
      "tags" -> tags
    ))
    Ok("saved")
  }

  /** api to set content remotely - used by sync and such */
  def setContent(wid: WID) = FAUR("setContent") {
    /*implicit au => implicit errCollector =>*/ implicit request =>

      val au=request.au.get

      def fromJ (s:String) = {
        val dbo = com.mongodb.util.JSON.parse(s).asInstanceOf[DBObject];
        Some(grater[WikiEntry].asObject(dbo))
      }

      val data = PlayTools.postData(request.req)

      (for(
        r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
        hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
        wej <- data.get("we") orErr "bad we";
        remote <- fromJ (wej) orErr "can't J"
      ) yield {
        log("Wiki.setContent " + wid)
        Wikis.find(wid).orElse(Wikis.findById(remote._id.toString)).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {
          case Some(w) =>
            (for (
              can <- canEdit(wid, auth, Some(w)) orErr "can't edit";
              newVerNo <- Some(
                if (w.ver < remote.ver) remote.ver // normal: remote is newer, so reset version to it
                else w.ver + 1 // remote overwrites local, just keep increasing local ver
              );
              nochange <- (w.content != remote.content || w.tags != remote.tags || w.props != remote.props) orErr ("no change");
              newVer <- Some(w.copy(content = remote.content, tags = remote.tags, props = remote.props, ver = newVerNo, updDtm = remote.updDtm));
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              we = signScripts(we, au)

              if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Services.config.isLocalhost)) {
                noPerm(wid, "HACK_SCRIPTS1")
              } else {
                razie.db.tx("Wiki.setContent") { implicit txn =>
                  WikiEntryOld(w, Some("setContent")).create
                  w.update(we, Some("setContent"))
                  clearDrafts(we.wid, au)
                  Emailer.withSession { implicit mailSession =>
                    au.quota.incUpdates
                    //                      au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                }
                Services ! WikiAudit(WikiAudit.UPD_SET_CONTENT, w.wid.wpathFull, Some(au._id), None, Some(w))

                Ok("ok")
              }
            })
          //          getOrElse
          //            Unauthorized("oops - " + errCollector.mkString)

          case None => {
            // new wiki: create it
            (for (
              can <- canEdit(wid, auth, None) orErr "can't edit";
              wej <- data.get("we") orErr "bad we";
              w <- fromJ(wej) orErr "can't J";
              newVer <- Some(w.copy(ver = 1, updDtm = DateTime.now)); // not copying over history so reset to now
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              we = signScripts(we, au)

              if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Services.config.isLocalhost)) {
                noPerm(wid, "HACK_SCRIPTS1")
              } else {
                razie.db.tx("Wiki.setContent") { implicit txn =>
                  we.create
                  Services ! WikiAudit(WikiAudit.CREATE_API, we.wid.wpathFull, Some(au._id), None, Some(we))
                }

                Ok("ok")
              }
            })
            //          getOrElse
            //            Unauthorized("oops - " + errCollector.mkString)
          }
        }
      }).flatten
  }


  /** POST new content to preview an edited wiki */
  def preview(wid: WID) = FAUR { implicit stok =>
    val content = stok.formParm("content")
    val tags = stok.formParm("tags")
    val page = WikiEntry(wid.cat, wid.name, wid.name, "md", content, stok.au.get._id, Tags(tags))

    ROK.k noLayout {implicit stok=>
      // important to pass altContent, so it will bypass format caches
      views.html.wiki.wikiFrag(wid, Some(content), true, Some(page))
    }
  }

  /** save an edited wiki - either new or updated */
  def save(wid: WID) = FAUR {
    implicit stok=> //au => implicit errCollector => implicit request =>

      val au = stok.au.get

      def setEventProps(w:WikiEntry) = {
        var we = w
      if(Wikis.isEvent(wid.cat)) {
        val r = stok.formParm("reg")
        if("on" == r)
          we = we.cloneProps(we.props ++ Map("module:reg" -> "yes"), au._id)
        else
          we = we.cloneProps(we.props ++ Map("module:reg" -> "no"), au._id)
        val rx = stok.formParm("regopen")
        if("on" == stok.formParm("regopen"))
          we = we.cloneProps(we.props ++ Map("module:reg-open" -> "yes"), au._id)
        else
          we = we.cloneProps(we.props ++ Map("module:reg-open" -> "no"), au._id)
        var d = stok.formParm("when")
        if(d.length > 0)
          we = we.cloneProps(we.props ++ Map("date" -> d), au._id)
        var v = stok.formParm("where")
        if(v.length > 0)
          we = we.cloneProps(we.props ++ Map("venue" -> v), au._id)
        var p = stok.formParm("price")
        if(p.length > 0)
          we = we.cloneProps(we.props ++ Map("price" -> p), au._id)
      }
        we
      }


    editForm.bindFromRequest()(stok.req).fold(
    formWithErrors => {
      log(formWithErrors.toString)
      val markup = formWithErrors("markup").value.mkString
      if(Wikis.markups.isDsl(markup))
        //todo mod/plugin/factory for these views to allow future languages
        ROK.k noLayout {implicit stok=> views.html.wiki.wikiEditJS(wid, Map.empty, formWithErrors)}
      else
        ROK.k badRequest { implicit stok =>
          views.html.wiki.wikiEdit(true, wid, formWithErrors, false, "")
        }
    },
    {
      case we @ EditWiki(l, m, co, vis, wvis, oldVer, tags, notif) => {
        log("Wiki.save " + wid)
        val x = Wikis.find(wid)

        val newLabel = if(Wikis.isEvent(wid.cat)) {
          // remove the old odate
            var n = if(l contains "{{") l.replaceAll(" *\\{\\{date[^}]*\\}\\}", "") else l
            var d = stok.formParm("when")
            if(d.length > 0) n = n+s" {{date $d}}"
//            var v = stok.formParm("where")
//            if(v.length > 0) n = n+s" {{at $v}}"
            n
        } else l

        val newName = if(Wikis.isEvent(wid.cat)) {
          newLabel.replaceAll("[{}]","").replaceAll(" ", "_")
        } else wid.name

        var newContent = if(Wikis.isEvent(wid.cat)) {
          var d = stok.formParm("when")
          if(d.length > 0)
            co.replaceAll("\\{\\{date[^}]*\\}\\}", "")
          else co
        } else co

        if(newContent.length == 0) newContent = "no description"

        Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {
          case Some(w) =>
            // edited topic
            (for (
              can <- canEdit(wid, auth, Some(w));
              r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
              hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
              nochange <- (w.label != newLabel || w.markup != m || w.content != newContent ||
                (!w.props.get("visibility").exists(_ == vis)) ||
                (!w.props.get("wvis").exists(_ == wvis)) ||
                (!w.props.get("module:reg").exists(_ == "yes") != ("on" == stok.formParm("reg"))) ||
                (!w.props.get("module:reg-open").exists(_ == "yes") != ("on" == stok.formParm("regopen"))) ||
                w.props.get("draft").map(_ != notif).getOrElse(false) ||
                // temp allow admin to reset to draft
                (au.isMod && notif == "Draft" && !w.props.contains("draft")) ||
                w.tags.mkString(",") != tags) orErr ("no change");
              conflict <- (oldVer == w.ver.toString) orCorr new Corr ("Topic modified in between", "Edit this last vesion and make your changes again.");
            //todo make this better: lock for editing or some kind of locks
              newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
              newVer <- Some(w.cloneNewVer(newlab, m, newContent, au._id));
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              // visibility?
              if (! we.props.get("visibility").exists(_ == vis))
                we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
              if (! we.props.get("wvis").exists(_ == wvis))
                we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)

              we = setEventProps(we)
                // also rename auto
              if(Wikis.isEvent(wid.cat) && we.name != newName) {
                we = we.copy(name = newName, label = newLabel)
              }

              // allow published to be set to draft
              if (au.isMod && notif == "Draft" && !we.props.contains("draft")) {
                we = we.cloneProps(we.props ++ Map("draft" -> notif), au._id)
              }

              // don't change date if silent update while not draft
              if (au.isAdmin && notif == "Silent" && !we.props.contains("draft")) {
                we = we.copy(updDtm = w.updDtm)
              }

                // do parents think it's draft?
              val plink = we.wid.parentWid.flatMap(_.uwid).flatMap { puwid =>
                  ROne[WikiLink]("from" -> we.uwid.grated, "to" -> puwid.grated, "how" -> "Child")
                }

              // moved from draft to else
              val shouldPublish =
                if (notif != "Draft" && (
                  we.props.contains("draft") ||
                  plink.exists(_.draft.exists(_.toLowerCase == "y"))
                )) {
                  if (we.wid.cats == "Post")
                    we = we.copy(props = we.props - "draft", crDtm = DateTime.now) // reset created time
                  else
                    we = we.copy(props = we.props - "draft")

                  // update link to parent if any to non-draft
                  plink.foreach( _.copy(draft=None).update )

                  notif == "Notify" || notif == "Site" || notif.contains("History") // Silent means no notif
                } else false

              if (we.tags.mkString(",") != tags)
                we = we.withTags(Tags(tags), au._id)

                we = signScripts(we, au)

              if (!we.scripts.filter(_.signature == "ADMIN").isEmpty && !(au.hasPerm(Perm.adminDb) || Services.config.isLocalhost)) {
                noPerm(wid, "HACK_SCRIPTS1")
              } else {
                razie.db.tx("Wiki.Save") { implicit txn =>
                  // can only change label of links OR if the formatted name doesn't change
                  w.update(we)
                  clearDrafts(we.wid, au)
                  Emailer.withSession(stok.realm) { implicit mailSession =>
                    au.quota.incUpdates
                    if (shouldPublish) notifyFollowersCreate(we, au, notif, true)
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                }
                Services ! WikiAudit(WikiAudit.UPD_EDIT, w.wid.wpathFull, Some(au._id), None, Some(we), Some(w))

                Redirect(controllers.Wiki.wr(we.wid, getRealm(), true)).flashing("count" -> "0")
              }
            }) getOrElse
              Redirect(controllers.Wiki.wr(wid, getRealm(), false)) // no change

          case None =>    // create a new topic

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
              var we = WikiEntry(wid.cat, newName, newLabel, m, newContent, au._id, Seq(), parent.map(_.realm) orElse wid.realm getOrElse getRealm(), 1, wid.parent)

              if (we.tags.mkString(",") != tags)
                we = we.withTags(Tags(tags), au._id)

              // special properties
              we.preprocess(Some(au))
              //todo verify permissiont o create in realm
              //                if (wep.props.get("realm").exists(_ != we.realm))
              //                  we = we.copy(realm=wep.props("realm"))

              // needs owner?
              if (WikiDomain(wid.getRealm).needsOwner(wid.cat)) {
                we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
//                model.UserWiki(au._id, we.uwid, "Owner").create
                cleanAuth()
              }

              // visibility?
              we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
              we = we.cloneProps(we.props ++ Map("wvis" -> wvis), au._id)
              if (notif == "Draft")
                we = we.cloneProps(we.props ++ Map("draft" -> notif), au._id)

              we = setEventProps(we)

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

                // WTF
//                we = we.cloneProps(we.props ++ Map("titi" -> "t"), au._id)

                // needs parent owner? // the context of the page
                we.findParent.flatMap(_.props.get("owner")).foreach { po =>
                  if (!we.props.get("owner").exists(_ == po))
                    we = we.cloneProps(we.props ++ Map("parentOwner" -> po), au._id)
                }

                we.create
                clearDrafts(we.wid, au)

                Services ! WikiAudit(WikiAudit.CREATE_WIKI, we.wid.wpathFull, Some(au._id), None, Some(we))

                Emailer.withSession(stok.realm) { implicit mailSession =>
                  au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)
                  if (notif == "Notify" || notif == "Site" || notif.contains("History")) notifyFollowersCreate(we, au, notif, false)
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
  private def notifyFollowersCreate(wpost: WikiEntry, au: User, notif:String, edited:Boolean)(implicit mailSession: MailSession) = {
    // 1. followers of this topic or followers of parent

    // either parent or realm notif list
    val list =
      (wpost.parent flatMap (Wikis(wpost.realm).find)) orElse
        ((
          if(notif contains "Site")
            Website.forRealm(wpost.realm).flatMap(_.notifyList)
          else None
          ) flatMap (Wikis(wpost.realm).find)
        )

    list map { w =>
      // user wikis
      if(!notif.contains("History"))
      (
        w.by :: model.Users.findUserLinksTo(w.uwid).filter(_.notif == model.UW.EMAIL_EACH).toList.map(_.userId)
      ).distinct.filter(_ != au._id).map(uid =>
        Users.findUserById(uid).filter(_.isActive).map{u =>
          if (wpost.parent.isDefined) Emailer.sendEmailNewPost(u, au, w, wpost)
          else Emailer.sendEmailNewWiki(u, au, wpost)
        })

      // add posts to their feed, regardless of their notification
      val size = (
        w.by :: model.Users.findUserLinksTo(w.uwid).map(_.userId).toList
      ).distinct.filter(uid=>uid != au._id).flatMap(Users.findUserById(_).toList).filter(_.isActive).map(_._id).map{uid =>
          RacerKidz.myself(uid).history.post(wpost, au, Some("Topic edited significantly..."))
      }.size
      Audit.logdb("ENTITY_CREATE", size + " RkHistory entries")

      // followers by email
      if(!notif.contains("History"))
        model.Users.findFollowerLinksTo(w.uwid).toList.groupBy(_.followerId).values.map(_.head).map(flink =>
          flink.follower.map(follower => {
            Emailer.sendEmailFollowerNewTopic(follower.email.dec, au, w.wid, wpost, flink.comment)
          }))
    }
  }

  /** screen to report a page */
  def report(wid: WID) = RAction { implicit request =>
    request.au match {
      case Some(user) =>
        ROK.k apply {implicit stok=> views.html.wiki.wikieReport(wid, reportForm.fill(ReportWiki("")))}
      case None => {
        clog << "need logged in to report a wiki"
        val msg = "You need to be logged in to report a page! If you really must, please create a support request at the bottom of this page..."
        Ok(views.html.util.utilErr(msg, controllers.Wiki.w(wid)))
      }
    }
  }

  /** reported a page */
  def reported(wid: WID) = RAction { implicit request =>
    reportForm.bindFromRequest.fold(
    formWithErrors => ROK.r badRequest {implicit stok=> views.html.wiki.wikieReport(wid, formWithErrors)},
    {
      case we @ ReportWiki(reason) =>
        Wikis.flag(wid, "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
        Emailer.withSession(request.realm) { implicit session =>
          SendEmail.send(Config.SUPPORT, Config.SUPPORT, "WIKI_FLAGGED",
            "link: " + wid.ahref + "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
        }
    })
    Msg("OK, page " + wid.wpath + " reported!", wid)
  }

  /** POSTed from category, has name -> create topic in edit mode */
  def addWithName(cat: String) = FAU {
    implicit au => implicit errCollector => implicit request =>
    val realm = CAT.unapply(cat).flatMap(_.realm).getOrElse(getRealm())
    addForm.bindFromRequest.fold( formWithErrors =>
      ROK.s apply { implicit stok =>
        views.html.wiki.wikieCreate(cat)
      },
    {
      case name: String => {
        WikiDomain(realm).zEnds(cat, "Template").headOption.map { t =>
          ROK.s apply { implicit stok =>
            (views.html.wiki.wikieAddWithSpec(cat, name, "Template", t, realm))}
        } orElse
        WikiDomain(realm).zEnds(cat, "StaticTemplate").headOption.map { t =>
          Redirect(routes.Wikie.addWithSpec2(cat, name, t, "Template", realm))
        } orElse
        Wikis(realm).category(cat).filter(_.sections.find(_.name == "form").isDefined).map{we=>
            Redirect(routes.Wikie.addWithSpec2(cat, name, we.wid.wpath, "Template", realm))
        } orElse
//          WikiDomain(realm).zEnds(cat, "Spec").headOption.map { t =>
        WikiDomain(realm).assocsWhereTheyHaveRole(cat, "Spec").headOption.map { t =>
          ROK.s apply { implicit stok =>(views.html.wiki.wikieAddWithSpec(cat, name, "Spec", t, realm))}
        } getOrElse
          Redirect(routes.Wikie.wikieEditNew(WID(cat, name).r(realm), ""))
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
        // if it's cat, then use itself, either from temp classes, make from DOM
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
        Redirect(routes.Wikie.wikieEditNew(WID(cat, name, pwid.findId).r(pwid.getRealm), ""))
      }
    })
  }

  private def canDelete(wid: WID)(implicit errCollector: VErrors, request: Request[_]) = {
    for (
      au <- activeUser;
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w));
    // last check: let people only delete Items in forums
      ok1 <- (Club.canAdmin(wid, au) || wid.cat == "Item") orCorr cNoPermission
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

      var done = false
      var count = 0

      def del(w:WikiEntry)(implicit txn: Txn) : Unit = {
        val children = RMany[WikiLink]("to" -> w.uwid.grated, "how" -> "Child").map(_.pageFrom).toList.flatMap(_.toList)
        RMany[WikiLink]("to" -> w.uwid.grated).toList.foreach(_.delete)
        RMany[WikiLink]("from" -> w.uwid.grated).toList.foreach(_.delete)
        RMany[UserWiki]("uwid" -> w.uwid.grated).toList.foreach(wl => {
          wl.delete
          done = true
        })
        Comments.findForWiki(w._id).toList.foreach(cs => {
          cs.delete
        })
        WikiCount.findOne(w._id).foreach(_.delete)
        // can only change label of links OR if the formatted name doesn't change
        // delete at last, so if any links fail, the thing stays there
        w.delete(au.userName)
        clearDrafts(w.wid, au)
        RacerKidz.rmHistory(w._id)
        count += 1
        Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, Some(au._id), None, Some(w), None, Some(w._id.toString))

        children.foreach(del)
      }

      log("Wiki.delete2 " + wid)
      if (wid.cat != "Club") canDelete(wid).collect {
        // delete all reactor pages
        case (au, w) if wid.cat == "Reactor" => {
          val realm = wid.name
          razie.db.tx("Wiki.delete") { implicit txn =>
            RMany[WikiEntry]("realm" -> wid.name).toList.map {we=>
              del(we)
//              count += 1
//              we.delete(au.userName)
              RacerKidz.rmHistory(we._id)
//              clearDrafts(we.wid, au)
            }
            WikiReactors.reload(realm)
            Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, Some(au._id), None, Some(w), None, Some(w._id.toString))
            cleanAuth()
          }
          Msg2(s"REACTOR DELETED forever - no way back! Deleted $count topics")
        }

        case (au, w) => {
          razie.db.tx("Wiki.delete") { implicit txn =>
            del(w)

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
  def wikieRename1(wid: WID) = FAUR("rename.wiki.1") { implicit stok =>
    canRename(wid).collect {
      case (_, w) =>
        ROK.k apply {
          views.html.wiki.wikieRename(wid, renameForm(wid).fill((w.label, w.label)), stok.au)
        }
    }
  }

  /** rename step 2: do it */
  def wikieRename2(wid: WID) = FAU("rename.wiki") { implicit au => implicit errCollector => implicit request =>
    renameForm(wid).bindFromRequest.fold(
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
  def wikieLike(cats: String) = FAUR("like.wiki") {implicit stok =>
    for (
      cat <- CAT.unapply(cats) orErr s"No such category: $cats";
      w   <- Wikis(cat.realm getOrElse getRealm()).category(cat.cat) orErr s"No such category: $cats"
    ) yield {
      ROK.k apply { implicit stok =>
        assert(stok.realm == (cat.realm getOrElse stok.realm))
        views.html.wiki.wikieLike(w.wid, Some(w))
      }
    }
  }

  def wikieCreate(cats: String) = FAUR("create.wiki") {implicit stok =>
      val name = ""
      for (
        cat <- CAT.unapply(cats);
        w   <- Wikis(cat.realm getOrElse getRealm()).category(cat.cat)
      ) yield {
        val realm = getRealm(cat.realm.mkString)
        ROK.k apply { implicit stok =>
          assert(stok.realm == (cat.realm getOrElse stok.realm))
          views.html.wiki.wikieCreate(cat.cat)
        }
      }
  }

  def manage(id: String) = FAU("manage.wiki") {
    implicit au => implicit errCollector => implicit request =>
    for (
      w <- Wikis(getRealm()).findById(id)
    ) yield {
      ROK.s noLayout { implicit stok =>
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
              val owl = ROne[WikiLink]("from" -> sourceW.uwid.grated, "how" -> "Child")
              owl.foreach(_.delete)
              WikiLink(
                sourceW.uwid,
                destW.uwid,
                "Child",
                owl.flatMap(_.draft),
                owl.map(_.crDtm).getOrElse(DateTime.now())
              ).create
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
        ROK.s apply { implicit stok =>
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
  def replaceAll1() = FAUR {implicit request =>
    Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill("", "", "", ""), false))
  }

  /** DO find and replace content in pages */
  def replaceAll3() = FAUR { implicit request =>
      replaceAllForm.bindFromRequest()(request.ireq).fold(
      formWithErrors => Msg2(formWithErrors.toString + "Oops, can't !"), {
        case (realm, q, news, action) =>
          log("replace all " + q + " -> " + news)

          def update (u:DBObject):DBObject = {
            Audit.logdb("replace ", ""+u.get("name"))
            u.put("content", u.get("content").asInstanceOf[String].replaceAll(q, news))
            u
          }

          if("replace" == action && request.au.get.isAdmin) {
            for (
              (u, m) <- isearch(q, realm, Some(update))
            ) {
            }
          }
          Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill(realm, q, news, ""), false))
      })
  }

  /** DO find and replace content in pages */
  def replaceAllTag3() = FAUR { implicit request =>
      replaceAllForm.bindFromRequest()(request.ireq).fold(
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

          if("replace" == action && request.au.get.isAdmin) {
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


