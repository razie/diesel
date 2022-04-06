/*
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import difflib.{DiffUtils, Patch}
import mod.diesel.model.Diesel
import mod.notes.controllers.DomC.retj
import mod.snow.RacerKidz
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{EssentialAction, Request}
import razie.audit.Audit
import razie.cout
import razie.db.RazSalatContext.ctx
import razie.db.{REntity, RMany, ROne, RazMongo, _}
import razie.diesel.dom.WikiDomain
import razie.diesel.utils.DieselData
import razie.hosting.{Website, WikiReactors}
import razie.tconf.Visibility._
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin._
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.wiki.parser.WAST
import razie.wiki.util.{PlayTools, Stage, Staged}
import razie.wiki.{Config, Enc, Services}
import com.google.inject._
import controllers.WikiUtil.{EditWiki, ReportWiki, after, applyStagedLinks, before}
import play.api.Configuration


/** a simple edit lock
  *
  * // todo candidate for a different temporary store, other than Mongodb
  *
  * @param uwid  for existing pages, quick lookup
  * @param wpath for new pages
  */
@RTable
case class EditLock(uwid: UWID, wpath: String, ver: Int, uid: ObjectId, uname: String, dtm: DateTime = DateTime.now()
                    , _id: ObjectId = new ObjectId()) extends REntity[EditLock] {

  def isLockedFor(userId: ObjectId) = dtm.plusSeconds(300).isAfterNow && uid != userId

  /** extend the lock for another period, on autosave etc */
  def extend = {
    this.copy(dtm = DateTime.now()).updateNoAudit(tx.auto)
  }
}

object EditLock {
  implicit def txn = tx.auto

  /** lock a page by a user */
  def lock (uwid:UWID, wpath:String, ver:Int, editor:User) : Boolean = {
    if(isLocked(uwid, wpath, editor)) false
    else {
      unlock(uwid, wpath, editor)
      EditLock(uwid, wpath, ver, editor._id, editor.userName).createNoAudit
      true
    }
  }

  /** unlock a page when saving etc */
  def unlock (uwid:UWID, wpath:String, u:User) = {
    if(isLocked(uwid, wpath, u)) throw new IllegalStateException("page is locked: "+wpath)

    find(uwid, wpath).map(_.deleteNoAudit)
    keepClean()
  }

  def canSave (uwid:UWID, wpath:String, u:User) = {
    // locked by someone else
    if(isLocked(uwid, wpath, u)) false
    else {
      true
    }
  }

  def find (uwid:UWID, wpath:String) =
    if(uwid == UWID.empty)
      ROne[EditLock] ("wpath" -> wpath)
    else
      ROne[EditLock] ("uwid.id" -> uwid.id)

  def isLocked (uwid:UWID, wpath:String, u:User) =
    find(uwid, wpath)
      .exists(_.isLockedFor(u._id))

  def who (uwid:UWID, wpath:String) =
    find(uwid, wpath)
        .map(_.uname).mkString

  /** sometimes they stay behind - we'll keep this clean and fast */
  def keepClean() = {
    // todo spawn async task
    val threshold = DateTime.now().minusHours(2)
    val x = RMany[EditLock]().filter(_.dtm.isBefore(threshold)).toList
    x.map(_.deleteNoAudit)
  }
}

/** wiki edits controller */
@Singleton
class Wikie @Inject()(config: Configuration) extends WikieBase {

  /** when no pages found in 'any', i captured 'cat' in a form */
  def edit2 = FAU { implicit au =>
    implicit errCollector =>
      implicit request =>
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

  def wikieEditSimple(wid: WID) = wikieEdit(wid, "", "simple")

  def wikieEditNew(wid: WID, noshow: String = "", tags:String="") =
    wikieEdit(wid, "", noshow, false, tags)

  def wikieEditOld(wid: WID, noshow: String = "", tags:String="") =
    wikieEdit(wid, "", noshow, true, tags)

  def wikieEdit(wid: WID, icontent: String = "", noshow:String="", old:Boolean=true, tags:String="") = FAU {
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

    Wikis.clearCache(wid, nwid) // make sure it's loaded from db

    val x = wid.page // better version, uses id version as well as name
    x.filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {

      case Some(w) =>   // existing page: edit

        (for (
          can <- canEdit(wid, Some(au), Some(w));
          hasQuota <- (au.isAdmin || au.isMod ||au.quota.canUpdate) orCorr cNoQuotaUpdates;
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
          lock <- EditLock.lock(w.uwid, w.wid.wpath, w.ver, au) orErr s"Page edited by ${EditLock.find(w.uwid, w.wid.wpath).map(_.uname).mkString}"
        ) yield {

          //look for drafts
          val draft = Autosave.OR("wikie", w.wid, stok.au.get._id, Map(
            "content"  -> w.content,
            "tags" -> w.tags.mkString(",")
          ))
          val hasDraft = Autosave.find("wikie",w.wid, stok.au.get._id).isDefined

          val atags = draft.getOrElse("tags", w.tags.mkString(",")) // when the autosave was created by fiddles without tags

          // JS, JSON etc
            if(false && (w.markup == Wikis.JS || w.markup == Wikis.JSON || w.markup == Wikis.SCALA))
              ROK.s noLayout { implicit stok =>
                views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEditJS(
                  w.wid,
                  Map.empty,
                  editForm.fill(
                    EditWiki(w.label,
                      w.markup,
                      draft("content"),
                      w.props.get("visibility").orElse(WikiReactors(wid.getRealm).props.prop("default.visibility")).getOrElse(PUBLIC),
                      wvis(Some(w.props)).orElse(WikiReactors(wid.getRealm).props.prop("default.wvis")).getOrElse(PUBLIC),
                      w.ver.toString,
                      atags,
                      w.props.get("draft").getOrElse("Silent")))),
                Seq.empty
                )
              }
            // EVENT
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
                      atags,
                      w.props.get("draft").getOrElse("Silent"))), hasDraft, noshow),
                  Seq.empty
                )
              }
          // normal - default
          else if(noshow != "simple")
            ROK.s noLayout { implicit stok =>
              views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEdit(old, w.wid, editForm.fill(
                  EditWiki(w.label,
                    w.markup,
                    draft("content"),
                    w.props.get("visibility").orElse(WikiReactors(realm).props.prop("default.visibility")).getOrElse(
                      PUBLIC),
                    wvis(Some(w.props)).orElse(WikiReactors(realm).props.prop("default.wvis")).getOrElse(PUBLIC),
                    w.ver.toString,
                    atags,
                    w.props.get("draft").getOrElse("Silent"))), hasDraft, noshow),
                Seq.empty
              )
            }
          // simple
          else
            ROK.s noLayout { implicit stok =>
              views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEditSimple(w.wid, editForm.fill(
                  EditWiki(w.label,
                    w.markup,
                    draft("content"),
                    w.props.get("visibility").orElse(WikiReactors(realm).props.prop("default.visibility")).getOrElse(PUBLIC),
                    wvis(Some(w.props)).orElse(WikiReactors(realm).props.prop("default.wvis")).getOrElse(PUBLIC),
                    w.ver.toString,
                    atags,
                    w.props.get("draft").getOrElse("Silent"))), hasDraft, noshow),
                    Seq.empty
              )
            }
        }) getOrElse
          noPerm(wid, "edit.wiki")

      case None =>   // new page

        val parentProps = wid.findParent.map(_.props)
        (for (
          can <- canEdit(wid, Some(au), None, parentProps) orErr ("Can't edit");
          r3 <- ("any" != wid.cat) orErr ("can't create in category any");
          w <- WikiDomain(realm).rdom.classes.get(wid.cat)
              .orElse(
                // for Topic just default
                if (wid.cat == "Topic") WikiDomain("wiki").rdom.classes.get(wid.cat)
                else None
              ) orErr (s"cannot find the category ${wid.cat} realm $realm");
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
          lock <- EditLock.lock(UWID.empty, wid.wpath, 0, au) orErr s"Page also being created by ${
            EditLock.find(UWID.empty, wid.wpath).map(_.uname).mkString
          }"
        ) yield {
          Audit.missingPage("wiki " + wid);

          // try to parse the name for tags - then add them to the content
          val preprocessed = Wikis.preprocess(wid, Wikis.MD, wid.name, None)._1.fold(WAST.context(None))
          val props = preprocessed.props
          val contentFromTags = props.foldLeft("") { (x, t) => x + "{{" + t._1 + ":" + t._2 + "}}\n\n" }

          val visibility = Wikis.mkVis(wid, realm)

          val wwvis = Wikis.mkwVis(wid, realm)

          val draft = wid.findParent
            .flatMap(_.contentProps.get("editMode"))
            .orElse(WikiDomain(realm).prop(wid.cat, "editMode"))
            .orElse(WikiReactors(realm).props.prop("default.editMode")).getOrElse("Notify")

          val ttt = if ("Topic" == wid.cat) "" else wid.cat.toLowerCase
          val newTags =
            (
              List(ttt) ::: tags.split(",").toList
            ).distinct.filter(_.length > 0).mkString(",")

          if (Wikis.isEvent(wid.cat))
            ROK.s noLayout { implicit stok =>
              views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEditEvent(nwid, editForm.fill(
                  EditWiki(wid.name.replaceAll("_", " "),
                    Wikis.MD,
                    contentFromTags + icontent,
                    visibility,
                    wwvis,
                    "0",
                    newTags,
                    draft)), false,
                  noshow),
                Seq.empty
              )
            }
          else ROK.s noLayout { implicit stok =>
            if (noshow != "simple")
              views.html.util.reactorLayout12FullPage(
                views.html.wiki.wikiEdit(old, nwid, editForm.fill(
                  EditWiki(wid.name.replaceAll("_", " "),
                    Wikis.MD,
                    contentFromTags + icontent,
                    visibility,
                    wwvis,
                    "0",
                    newTags,
                    draft)), false,
                  noshow),
                Seq.empty
              )
            else
              views.html.wiki.wikiEditSimple(nwid, editForm.fill(
                EditWiki(wid.name.replaceAll("_", " "),
                  Wikis.MD,
                  contentFromTags + icontent,
                  visibility,
                  wwvis,
                  "0",
                  newTags,
                  draft)), false,
                noshow)
          }
        }) getOrElse
          noPerm(wid, "create.wiki")
    }
  }

  private def signScripts (iwe:WikiEntry, au:User) = {
    /** pattern for all sections requiring signing - (?s) means multi-line */
    val PATTSIGN = """(?s)\{\{(\.?)(template|def|lambda|inline)[: ]*([^:}]*)(:SIG[^}]*)\}\}((?>.*?(?=\{\{/)))\{\{/(template|def|lambda|inline)?\}\}""".r //?s means DOTALL - multiline

    var we = iwe

    if (au.hasPerm(Perm.adminDb)) {
      val weScripts = we.scriptsNoInclude

      if (!weScripts.filter(_.signature startsWith "SIG").isEmpty) {
        var c2 = we.content
        for (s <- weScripts.filter(_.signature startsWith "SIG")) {
          def sign(s: String) = Enc apply Enc.hash(s)

          c2 = PATTSIGN.replaceSomeIn(c2, { m =>
            clog << "SIGNING:" << m << m.groupNames.mkString
            if (s.name == (m group 3)) Some("{{%s:%s:%s}}%s{{/%s}}".format(
              (m group 1)+(m group 2), m group 3, "SIG"+sign(s.content), s.content.replaceAll("""\\""", """\\\\""").replaceAll("\\$", "\\\\\\$"), m group 2))
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
    Autosave.delete("wikie",w, au._id)
  }

  def deleteDraft (wid:WID) = FAUR { stok=>
    EditLock.unlock(wid.uwid.getOrElse(UWID.empty), wid.wpath, stok.au.get)

    Autosave.deleteDraft(wid, stok.au.get._id)
    Ok("")
  }

  def saveDraft (wid:WID) = FAUR { implicit stok =>
    val content = stok.formParm("content")
    val tags = stok.formParm("tags")
    var timeStamp = stok.formParm("timeStamp")

    // extend lock
    EditLock.find(wid.uwid.getOrElse(UWID.empty), wid.wpath)
        .filter(_.uid == stok.au.get._id)
        .map(_.extend)

    val now = DateTime.now

    //autosave draft - if none and there are changes

    val autoRec = Autosave.recDraft(wid, stok.au.get._id)

    // first check if it's newer - if the user clicks "back", a stale editor may overwrite a newer draft
    if (timeStamp.nonEmpty && autoRec.exists(_.updDtm.isAfter(new DateTime(timeStamp.toLong)))) {
      // don't change "staleid" - used as search
      Conflict(s"staleid - please refresh page... $timeStamp - ${autoRec.get.updDtm.toInstant.getMillis}")
    } else {
      timeStamp = now.toInstant.getMillis.toString

      Autosave.set("wikie", wid, stok.au.get._id,
        Map(
          "content" -> content,
          "tags" -> tags
        ), Some(now))

      if (EditLock.isLocked(wid.uwid.getOrElse(UWID.empty), wid.wpath, stok.au.get))
        Conflict(s"edited by ${EditLock.who(wid.uwid.getOrElse(UWID.empty), wid.wpath)}")
      else
        retj << Map(
          "message" -> "ok, saved",
          "info" -> Map( // just like fiddleUpdated
            "timeStamp" -> s"$timeStamp"
          )
      )
    }
  }

  /** calc the diff draft to original for story and spec */
  def draftDiff(wid: WID) = FAUR { implicit stok =>

    val w = wid.page.map(_.content).getOrElse("?")

    val draft = Autosave.find("wikie",wid, stok.au.get._id).flatMap(_.get("content")).getOrElse("?")

    import scala.collection.JavaConversions._

    def diffTable(p: Patch) = s"""<small>${views.html.admin.diffTable("R", p, Some(("How", "Original", "Autosaved")))}</small>"""

    def diff = diffTable(DiffUtils.diff(w.lines.toList, draft.lines.toList))

    retj << Map(
      "diff" -> diff
    )
  }

  /** api to set content remotely - used by sync and such */
  def setSection(wid: WID) = FAUR("setSection", true) {
    implicit request =>

      val sType = request.formParm("sectionType")
      val sName = request.formParm("sectionName")
      val icontent = request.formParm("content")
      val au = request.au.get

      def mkC (oldContent:String, icontent:String) = {
        // multiline non-greedy
        val re = s"(?s)\\{\\{(\\.?)$sType $sName([^:}]*)\\}\\}((?>.*?(?=\\{\\{/)))\\{\\{/$sType\\s*\\}\\}".r
        val re2 = s"(?s)\\{\\{(\\.?)$sType $sName([^}]*)\\}\\}(.*)\\{\\{/$sType\\s*\\}\\}".r
        val res = re.replaceSomeIn(oldContent, {m=>
          Some{
            val c = icontent.replaceAll("""\\""", """\\\\""").replaceAll("\\$", "\\\\\\$")
            val s = s"{{$$1$sType ${sName}$$2}}\n${c}\n{{/$sType}}"
            s
          }
        })
        res
      }

      val xx = Wikis.find(wid)
      val yy = wid.page

      (for(
        w <- wid.page; //Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) orErr "wiki not found "+wid.wpath  ;
        _ <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
        _ <- canEdit(wid, auth, Some(w)) orErr "can't edit";
        _ <- (au.isAdmin || au.isMod ||au.quota.canUpdate) orCorr cNoQuotaUpdates;
        newC <- Some(mkC(w.content, icontent).replaceAll("\r", ""));
        newVerNo <- Some(w.ver + 1 );
        newVer <- Some(w.copy(content = newC, ver = newVerNo, updDtm = DateTime.now()));
        _ <- (w.content != newC) orErr ("no change");
        _ <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
        ) yield {
          log("Wiki.setSection " + wid)
          var we = newVer

          we = signScripts(we, au)

            razie.db.tx("Wiki.setSection", request.userName) { implicit txn =>
              WikiEntryOld(w, Some("setSection")).create
              w.update(we, Some("setSection"))
              clearDrafts(we.wid, au)
              Wikis.clearCache(we.wid)
              Emailer.withSession(request.realm) { implicit mailSession =>
                au.quota.incUpdates
                //                      au.shouldEmailParent("Everything").map(parent => Emailer
                //                      .sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
              }
            }
        Services ! WikiAudit(WikiAudit.UPD_SET_CONTENT, w.wid.wpathFull, Some(au._id), None, Some(we), Some(w))

        Ok("ok, section updated")
      })
  }

  /** api to set content remotely - used by sync and such
    *
    * @param iwid
    * @param id optional (backwards compat) if passed in, can support renamed WIDs
    * @return
    */
  def setContent(iwid: WID, id: String) = FAUR("setContent", true) {
    implicit request =>
      def getRealm(remote: WikiEntry) = iwid.realm.getOrElse(remote.realm)

      // todo implement using id to support renaming WIDs

      def fromJ(s: String) = {
        val dbo = com.mongodb.util.JSON.parse(s).asInstanceOf[DBObject];
        Some(grater[WikiEntry].asObject(dbo))
      }

      val data = PlayTools.postData(request.req)

      (for (
        wej <- data.get("we") orErr "bad we";
        remoteHostPort <- data.get("remote").orElse(Some("")); // orElse for tmeporary compatibility
        remote <- fromJ(wej) orErr "can't J";
        au <- request.au.map(_.forRealm(getRealm(remote)));
        r1 <- (au.hasPerm(Perm.uWiki) || au.isDev) orCorr cNoPermission("uWiki,dev");
        hasQuota <- (au.isMod || au.quota.canUpdate) orCorr cNoQuotaUpdates
      ) yield {
        val toRealm = getRealm(remote)
        // make sure wid has realm
        val wid = iwid.r(toRealm)

        log(
          s"Wiki.setContent toRealm: $toRealm | remote: $remoteHostPort / ${remote.wid} | local: ${request.hostPort} " +
              s"/ $wid")

        val idToUse = if (ObjectId.isValid(id)) id else remote._id.toString

        Wikis(toRealm)
            .ifind(wid)
            .map(grater[WikiEntry].asObject(_)) // use ifind so no fallbacks
            .orElse(
              Wikis
                  .findById(idToUse) // look by ID so this works with diffAll as well
                  .filter(x => request.hostPort == remoteHostPort.mkString) // but only if same server
                  .filter(x => remote.realm == toRealm) // and same realm (across realms can't share IDs)
            )
        match {

            // existing page
          case Some(w) => {
            debug("  found: " + w.wid.wpathFull)

            (for (
              can <- canEdit(wid, Some(au), Some(w)) orErr "can't edit";
              newVerNo <- Some(
                if (w.ver < remote.ver) remote.ver // normal: remote is newer, so reset version to it
                else w.ver + 1 // remote overwrites local, just keep increasing local ver
              );
              nochange <- (w.content != remote.content || w.tags != remote.tags || w.props != remote.props || w
                  .markup != remote.markup) orErr ("no change");
              newVer <- Some(w.copy(
                content = remote.content,
                tags = remote.tags,
                props = remote.props,
                ver = newVerNo,
                markup = remote.markup,
                updDtm = remote.updDtm
              ));
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              // todo check lock

              we = signScripts(we, au)

                razie.db.tx("Wiki.setContent", request.userName) { implicit txn =>
                  WikiEntryOld(w, Some("setContent")).create
                  w.update(we, Some("setContent"))
                  clearDrafts(we.wid, au)
                  Emailer.withSession(request.realm) { implicit mailSession =>
                    au.quota.incUpdates
                    //                      au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                }
                Services ! WikiAudit(WikiAudit.UPD_SET_CONTENT, w.wid.wpathFull, Some(au._id), None, Some(we), Some(w))

                Ok("ok")
            })
          }

          // new wiki: create it
          case None => {
            debug("  no topics found - create new one")

            (for (
              can <- canEdit(wid, Some(au), None) orErr "can't edit";
              wej <- data.get("we") orErr "bad we";
              source <- fromJ(wej) orErr "can't J";
              newVer <- Some(source.copy(
                ver = 1,
                realm = wid.getRealm,
                updDtm = DateTime.now)
              ); // not copying over history so reset to now
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              // check that there's no screwup
              if(Wikis(toRealm)
                .weTable(wid.cat)
                .find(Map("realm" -> toRealm,  "name" -> Wikis.formatName(wid.name), "category" -> wid.cat))
                .size > 0) {
                Unauthorized("Page exists already - internal error - contact support !")
              } else {

                // if on the same host but differnt realm, gets a new id
                if (wid.getRealm != source.realm) {
                  we = we.copy(
                    _id = new ObjectId()
                  )
                }

                // todo check lock

                we = signScripts(we, au)

                  razie.db.tx("Wiki.setContent", request.userName) { implicit txn =>
                    we.create
                    Services ! WikiAudit(WikiAudit.CREATE_API, we.wid.wpathFull, Some(au._id), None, Some(we))
                  }

                  Ok("ok")
              }
            })
          }
        }
      }).flatten
  }

  /** POST new content to preview an edited wiki */
  def preview(wid: WID) = FAUR { implicit stok =>
    val content = stok.formParm("content")
    val tags = stok.formParm("tags")
    var markup = stok.formParm("markup")
    if(markup.length <= 0) markup="md"

    var page = WikiEntry(wid.cat, wid.name, wid.name, markup, content, stok.au.get._id, Tags(tags), stok.realm)

    // sign scripts temporarily, so they run in preview
    // todo is this some kind of security threat? answer here so i remember
    page = signScripts(page, stok.au.get)

    ROK.k noLayout {implicit stok=>
      // important to pass altContent, so it will bypass format caches
      views.html.wiki.wikiFrag(wid, Some(content), true, Some(page))
    }
  }

  /** save an edited wiki - either new or updated */
  def wikieEdited(wid: WID) = FAUR {
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

        newContent = newContent.replaceAll("\r", "")

        Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {

          case Some(w) =>
            // edited topic
            (for (
              can <- canEdit(wid, auth, Some(w));
              r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
              hasQuota <- (au.isAdmin || au.isMod ||au.quota.canUpdate) orCorr cNoQuotaUpdates;
              nochange <- (w.label != newLabel || w.markup != m || w.content != newContent ||
                (!w.props.get("visibility").exists(_ == vis)) ||
                (!w.props.get("wvis").exists(_ == wvis)) ||
                (!w.props.get("module:reg").exists(_ == "yes") != ("on" == stok.formParm("reg"))) ||
                (!w.props.get("module:reg-open").exists(_ == "yes") != ("on" == stok.formParm("regopen"))) ||
                w.props.get("draft").map(_ != notif).getOrElse(false) ||
                // temp allow admin to reset to draft
                (au.isMod && notif == "Draft" && !w.props.contains("draft")) ||
                w.tags.mkString(",") != tags) orErr ("no change");
              conflict <- (oldVer == w.ver.toString) orCorr
                  new Corr (
                    s"Topic modified in between ($oldVer ${w.ver})",
                    "Edit this last vesion and make your changes again.");
              stillLocked <- EditLock.canSave(w.uwid, w.wid.wpath, stok.au.get) orErr "You lost the lock on this page, to ?";
              newlab <- Some(if ("WikiLink" == wid.cat || "User" == wid.cat) l else if (wid.name == Wikis.formatName(l)) l else w.label);
              newVer <- Some(w.cloneNewVer(newlab, m, newContent, au._id));
              upd <- before(newVer, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
            ) yield {
              var we = newVer

              EditLock.unlock(w.uwid, w.wid.wpath, stok.au.get)

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
              // todo why??? this removes updated date time in all topics pretty much
//              if (au.isAdmin && notif == "Silent" && !we.props.contains("draft")) {
//                we = we.copy(updDtm = w.updDtm)
//              }

                // do parents think it's draft?
              val plink = we.wid.parentWid.flatMap(_.uwid).flatMap { puwid =>
                  ROne[WikiLink]("from.id" -> we.uwid.id, "to.id" -> puwid.id, "how" -> "Child")
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
                  plink.foreach( _.copy(draft=None, crDtm = DateTime.now).update )

                  notif == "Notify" || notif == "Site" || notif.contains("History") // Silent means no notif
                } else false

              if (we.tags.mkString(",") != tags)
                we = we.withTags(Tags(tags), au._id)

                we = signScripts(we, au)

              // clean with this realm too - when mixins find a base realm topic, will be cached with current realm
              Wikis.clearCache(we.wid, we.wid.r(stok.realm))

                razie.db.tx("Wiki.Save", stok.userName) { implicit txn =>
                  // can only change label of links OR if the formatted name doesn't change
                  w.update(we)
                  // clean with this realm too - when mixins find a base realm topic, will be cached with current realm
                  Wikis.clearCache(we.wid, we.wid.r(stok.realm))
                  clearDrafts(we.wid, au)
                  Emailer.withSession(stok.realm) { implicit mailSession =>
                    au.quota.incUpdates
                    if (shouldPublish) notifyFollowersCreate(we, au, notif, true)
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  }
                }
                Services ! WikiAudit(WikiAudit.UPD_EDIT, w.wid.wpathFull, Some(au._id), None, Some(we), Some(w))

              Redirect(controllers.WikiUtil.wr(we.wid, getRealm(), true)).flashing("count" -> "0")
            }) getOrElse
                Msg("Can't edit topic: " + errCollector.mkString, wid)
//              Redirect(controllers.WikiUtil.wr(wid, getRealm(), false)) // no change

          case None =>    // create a new topic

            val parent= wid.findParent
            val parentProps = parent.map(_.props)
            (for (
              can <- canEdit(wid, auth, None, parentProps);
              hasQuota <- (au.isAdmin || au.isMod || au.quota.canUpdate) orCorr cNoQuotaUpdates;
              r3 <- ("any" != wid.cat) orErr ("can't create in category any");
              w <- WikiDomain(wid.realm getOrElse getRealm()).rdom.classes.get(wid.cat)
                  .orElse(
                    // for Topic just default
                    if (wid.cat == "Topic") WikiDomain("wiki").rdom.classes.get(wid.cat)
                    else None
                  ) orErr (s"cannot find the category ${wid.cat} realm ${wid.getRealm}");
              r1 <- (au.hasPerm(Perm.uWiki)) orCorr cNoPermission("uWiki");
              stillLocked <- EditLock.canSave(wid.uwid.getOrElse(UWID.empty), wid.wpath,
                stok.au.get) orErr "You lost the lock on this page, to ?"
            ) yield {
              //todo find the right realm from the url or something like Config.realm
              var we = WikiEntry(wid.cat, newName, newLabel, m, newContent, au._id, Seq(), parent.map(_.realm) orElse wid.realm getOrElse getRealm(), 1, wid.parent)

              EditLock.unlock(wid.uwid.getOrElse(UWID.empty), wid.wpath, stok.au.get)

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

              razie.db.tx("wiki.create", stok.userName) { implicit txn =>
                // anything staged for this?
                we = applyStagedLinks(wid, we)

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
                  Emailer.tellAdmin("New Wiki", au.userName, wid.ahref)
                }
              }

              Redirect(controllers.WikiUtil.wr(we.wid, getRealm(), true)).flashing("count" -> "0")
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
          // if "Notify" and no parent, default to entire site
          if((notif contains "Site") || ((notif contains "Notify") && wpost.parent.isEmpty))
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
        Ok(views.html.util.utilErr(msg, controllers.WikiUtil.w(wid)))
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
          session.send(session.SUPPORT, session.SUPPORT, "WIKI_FLAGGED",
            "link: " + wid.ahref + "reported by user: " + auth.map(_.ename) + " BECAUSE " + reason)
        }
    })
    Msg("OK, page " + wid.wpath + " reported!", wid)
  }

  def wikieCreate(cats: String, tags:String) = FAUR("wikieCreate") {implicit stok =>
    val name = ""
    for (
      au <- stok.au;
      isMember <- (au.realms.contains(stok.realm) || au.isAdmin) orErr "not a website member"; // member
      can <- (stok.website.membersCanCreateTopics || au.isMod) orErr "members can't create topics"; // member
      cat <- CAT.unapply(cats);
      wcat<- Wikis(cat.realm getOrElse getRealm()).category(cat.cat) orElse Wikis(cat.realm getOrElse getRealm()).category("Topic") orErr s"category ${cat.cat} not found"
    ) yield {
      val realm = getRealm(cat.realm.mkString)
      ROK.k apply { implicit stok =>
        assert(stok.realm == (cat.realm getOrElse stok.realm))
        // use whatever cat I found...
        views.html.wiki.wikieCreate(wcat.name, tags:String)
      }
    }
  }

  /** POSTed from category, has name -> create topic in edit mode */
  def addWithName(cat: String, tags:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
    val realm = CAT.unapply(cat).flatMap(_.realm).getOrElse(getRealm())

    addForm.bindFromRequest.fold( formWithErrors =>
      ROK.s apply { implicit stok =>
        views.html.wiki.wikieCreate(cat, tags)
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
        WikiDomain(realm).rdom.assocsWhereTheyHaveRole(cat, "Spec").headOption.map { t =>
          ROK.s apply { implicit stok =>
            (views.html.wiki.wikieAddWithSpec(cat, name, "Spec", t, realm))
          }
        } getOrElse
          Redirect(routes.Wikie.wikieEditNew(WID(cat, name).r(realm), "", tags))
      }
    })
  }

  // create something with a category and template and spec
  def addWithSpec(cat: String, iname:String, templateWpath:String, torspec:String, realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val name = PlayTools.postData.getOrElse("name", iname).toLowerCase
      val kind = PlayTools.postData.getOrElse("kind", "prod")

      val E = " - go back and try another name..."
      val e =
        if(name.length < 3) s"Name ($name) too short"
      else if (name.length > 20 && !au.isAdmin) s"Name ($name) too long"
      else if (Config.reservedNames.contains(name)) s"Name ($name) is reserved"
      else if (!name.matches("(?![-_])[A-Za-z0-9-_]{1,63}(?<![-_])")) s"Name ($name) cannot contain special characters"
      else if (
          ! "dev,qa".split(",").contains(kind) &&
          ! au.hasMembershipLevel(Perm.Basic.s)
        ) s"With a free account you can only create dev or qa projects..."
      else ""

      if(e.isEmpty) {
        Profile.updateUser(au, au.addModNote(
          realm,
          s"${DateTime.now().toString} - user accepted terms and condtions for creating new project named:$name"
        ))

        Realm.createR2(cat, templateWpath, torspec:String, realm).apply(request).value.get.get
      }
      else Msg("Error: " + Corr(e, Some(E)).toString)
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
        Msg(s"Can't find template [[$templateWpath]]")
  }

  /** add a child/related to another topic - this stages the link and begins creation of kid.
    *
    * At the end, the staged link is persisted */
  def addLinked(cat: String, pwid: WID, role: String) = FAUR {implicit request =>
    addForm.bindFromRequest.fold(
    formWithErrors => Msg2("Oops, can't add that name!", Some(pwid.urlRelative)),
    {
      case xname: String => {
        val name = xname.replaceAll("/", "_") // it messes up a lot of stuff... can't have it
        val n = Wikis.formatName(WID(cat, name).r(pwid.getRealm))
        Stage("WikiLinkStaged", WikiLinkStaged(WID(cat, n, pwid.findId).r(pwid.getRealm), pwid, role).grated, request.au.get.userName).create

        WikiDomain(request.realm).rdom.assocsWhereTheyHaveRole(cat, "Spec").headOption.map { t =>
          ROK.r apply { implicit stok =>
            views.html.wiki.wikieAddWithSpec(cat, name, "Spec", t, request.realm)
          }
        } getOrElse
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

  // todo make this a POST in routes, it's a GET

  /** delete step 2: do it */
  def wikieDelete2(wid: WID) = FAU {
    implicit au =>
      implicit errCollector =>
        implicit request =>

          var done = false
          var count = 0

          def del(w: WikiEntry)(implicit txn: Txn): Unit = {
            val children = RMany[WikiLink]("to.id" -> w.uwid.id, "how" -> "Child").map(_.pageFrom).toList.flatMap(
              _.toList)
            RMany[WikiLink]("to.id" -> w.uwid.id).toList.foreach(_.delete)
            RMany[WikiLink]("from.id" -> w.uwid.id).toList.foreach(_.delete)
            RMany[UserWiki]("uwid.id" -> w.uwid.id).toList.foreach(wl => {
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

        // delete all reactor pages. if realm != name it's a mistake, should allow delete without all pages
        case (au, w) if wid.cat == "Reactor" && wid.getRealm == wid.name => {
          val realm = wid.name
          razie.db.tx("Wiki.delete", au.userName) { implicit txn =>
            RMany[WikiEntry]("realm" -> wid.name).toList.map {we=>
              del(we)
//              count += 1
//              we.delete(au.userName)
              RacerKidz.rmHistory(we._id)
//              clearDrafts(we.wid, au)
            }

            WikiReactors.reload(realm)

            RDelete[Autosave]("realm" -> realm)
            RDelete[DieselSettings]("realm" -> realm)
            RDelete[DieselData]("realm" -> realm)
            RazMongo("DieselDb").remove(Map("realm" -> realm))

            RMany[User]().filter(_.realms.contains(wid.name)).map {u=>
              u.update(u.copy(realms=u.realms.filter(_ != wid.name), realmSet = u.realmSet.filter(_._1 != wid.name)))

              // todo remove this realm from all users.x.
            }

            Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, Some(au._id), None, Some(w), None, Some(w._id.toString))

            cleanAuth()
          }
          Msg(s"REACTOR DELETED forever - no way back! Deleted $count topics")
        }

        case (au, w) => {
          razie.db.tx("Wiki.delete", au.userName) { implicit txn =>
            del(w)

            if (done) cleanAuth() // it probably belongs to the current user, cached...
          }
          w.findParent.map(w =>
            Msg(s"DELETED forever ok - no way back! Deleted $count topics", w.wid)
          ) getOrElse Msg(s"DELETED forever ok - no way back! Deleted $count topics")
        }
      } getOrElse
        noPerm(wid, "ADMIN_DELETE2")
      else
        Msg("Can't delete a " + wid.cat)
  }

  private def canRename(wid: WID)(implicit errCollector: VErrors, stok: RazRequest) = {
    for (
      au <- activeUser;
      ok <- ("WikiLink" != wid.cat && "User" != wid.cat) orErr ("can't rename this category");
      w <- Wikis.find(wid) orErr ("topic not found");
      ok2 <- canEdit(wid, Some(au), Some(w));
      noDrafts <- Autosave.find(wid, stok.au.map(_._id)).isEmpty orErr ("This topic has drafts - please edit and cancel first, that will clear the drafts!")
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
  def wikieRename2(wid: WID) = FAUR("rename.wiki") { implicit request =>
    renameForm(wid).bindFromRequest.fold(
    formWithErrors => Some(ROK.r badRequest {implicit stok=> views.html.wiki.wikieRename(wid, formWithErrors, auth)}),
    {
      case (_, n) =>
        canRename(wid).collect {
          case (au, w) =>
            val newp = w.copy(name = Wikis.formatName(n), label = n, ver = w.ver + 1, updDtm = DateTime.now)
              w.update(newp, Some("renamed"))
              Services ! WikiAudit(WikiAudit.UPD_RENAME, newp.wid.wpathFull, Some(au._id), None, Some(newp), Some(w), Some(w.wid.wpathFull))
              cleanAuth()

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
      razie.db.tx("Wiki.Reserve", au.userName) { implicit txn =>
        w.update(newVer, Some("reserve"))
      }
      after(Some(w), newVer, WikiAudit.UPD_TOGGLE_RESERVED, Some(au))
      Redirect(controllers.WikiUtil.w(wid))
    }
  }

  /** someone votes a wiki */
  def like(wid: WID) = RAction { implicit request =>
    log("Wiki.vote " + wid)
    (for (
      how <- request.fqhParm("how").map(_.toInt);
      reason <- request.fqhParm("reason");
      w <- Wikis.find(wid);
      newVer <- activeUser.map { au =>
        val ulike = UserLike (au._id.toString, how == 1, reason.trim)
        val like = if (how == 1) Some(au) else None
        val dislike = if (how == 0) Some(au) else None
        (w.copy(
          userFeedback = List(ulike) ::: w.userFeedback,
          likes = like.map(_._id.toString).toList ::: w.likes,
          dislikes = dislike.map(_._id.toString).toList ::: w.dislikes,
          dislikeReasons = (if (how == 1 || reason.trim.isEmpty) Nil else List(reason)) ::: w.dislikeReasons
        ))
      } orElse {
        Some(w.copy(likeCount = w.likeCount + how, dislikeCount = w.dislikeCount + (if (how == 0) 1 else 0)))
      };
      upd <- before(newVer, WikiAudit.UPD_LIKE) orErr ("Not allowed")
    ) yield {
      razie.db.tx("Wiki.like", auth.map(_.userName).getOrElse("Anonymous")) { implicit txn =>
        RUpdate.noAudit[WikiEntry](Wikis(w.realm).weTables(wid.cat), Map("_id" -> newVer._id), newVer)
      }
      after(Some(w), newVer, WikiAudit.UPD_LIKE, auth)
      if (how == 1)
        Audit.logdb("VOTE.UP", "" + request.au.map(_.userName) + wid.wpath)
      else {
        Audit.logdb("VOTE.DOWN", "" + request.au.map(_.userName) + " WHY: [" + reason + "] " + wid.wpath)
      }
      Ok(s"{likeCount:${newVer.likeCount}, dislikeCount:${newVer.dislikeCount}").as("application/json")
    }) getOrElse {
      Unauthorized("")
    }
  }

  /** START find and replace content in pages */
  def replaceAll1() = FAUR {implicit request =>
    Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill("", "", "", ""), false))
  }

  /** DO find and replace content in pages */
  def replaceAll3() = FAUR { implicit request =>
      replaceAllForm.bindFromRequest()(request.ireq).fold(
      formWithErrors => Msg(formWithErrors.toString + "Oops, can't !"), {
        case (realm, q, news, action) =>
          log("replace all " + q + " -> " + news)

          def update (u:DBObject):DBObject = {
            Audit.logdb("replace ", ""+u.get("name"))
            u.put("content", u.get("content").asInstanceOf[String].replaceAll(q, news))
            u
          }

          if("replace" == action && request.au.get.isAdmin) {
            for (
              (u, m) <- WikiUtil.isearch(q, realm, Some(update))
            ) {
            }
          }
          Ok(views.html.wiki.wikieReplaceAll(replaceAllForm.fill(realm, q, news, ""), false))
      })
  }

  /** DO find and replace content in pages */
  def replaceAllTag3() = FAUR { implicit request =>
      replaceAllForm.bindFromRequest()(request.ireq).fold(
        formWithErrors => Msg(formWithErrors.toString + "Oops, can't !"), {
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

  /** rename step 1: form for new name */
  def wikieTag(realm: String, name: String) = FAUR("wikie.tag.show") { implicit stok =>
    WikiTag.find(realm, name).map { wtag =>
      ROK.k apply {
        views.html.wiki.wikiTag(wtag)
      }
    }.orElse {
      Some(Ok("Create"))
    }
  }

  /** create or update tag */
  def wikieTagUpsert(realm: String, name: String) = FAUR("wikie.tag.create") { implicit stok =>
    val wtag = WikiTag.update(realm, name)
    Some(
      ROK.k apply {
        views.html.wiki.wikiTag(wtag)
      }
    )
  }

}


