/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin.Audit
import admin.Config
import admin.Notif
import model.Enc
import db._
import db.RazSalatContext.ctx
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import razie.cout
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
import admin.Config
import admin.SendEmail
import admin.VError
import model.Sec.EncryptedS
import model.User
import model.WID
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.Request
import play.api.mvc.Result
import razie.Logging
import scala.Some
import model.WikiAudit
import model.User
import scala.Some
import model.WikiAudit
import controllers.AutosavedNote
import controllers.Locker
import model.User
import controllers.LockerOld

/** per topic reg */
@db.RTable
case class Locker(
  content: String,
  markup: String,
  tags: Seq[String],
  by: ObjectId,
  ver: Int = 1,
  parent: Option[ObjectId] = None,
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId) extends REntity[Locker] {

  // optimize access to User object
  lazy val user = by.as[User]

  override def toString: String = toJson
}

/** old wiki entries - a copy of each older version when udpated or deleted */
@db.RTable
case class LockerOld(
                      entry: Locker,
                      _id: ObjectId = new ObjectId()) {
  def create(implicit txn: db.Txn) = RCreate.noAudit[LockerOld](this)
}


/** autosaved notes */
@db.RTable
case class AutosavedNote(
                          uid: ObjectId,
                          nid: ObjectId,
ver:Int,
                          content: String,
                      tags: String,
                     _id: ObjectId = new ObjectId()) {
  def create(implicit txn: db.Txn) = RCreate.noAudit[AutosavedNote](this)
  def update (implicit txn: db.Txn) = RUpdate.noAudit(Map("_id" -> _id), this)
  def delete(implicit txn: db.Txn) = RDelete.noAudit[AutosavedNote](this)
}

object Lockers {
  //  def notesForUser (uid:ObjectId) = RMany[Locker]("by"->uid)
  //  def tagsForUser (uid:ObjectId) = notesForUser(uid).toList.flatMap(_.tags).distinct
  //  def notesForTag (uid:ObjectId, tag:String) = notesForUser(uid).filter(_.tags.contains(tag))

  val cat = "Locker"

  def notesById(id: ObjectId) =
    Wikis.weTable(cat).findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))
  def notesForUser(uid: ObjectId, archived: Boolean = false) =
    Wikis.weTable(cat).find(Map("by" -> uid)) map (grater[WikiEntry].asObject(_)) filter (n => !n.tags.contains(ARCHIVE) || archived)

  final val ARCHIVE = "archive"
  final val ALL = "all"
  final val NONE = "none"

  def tagsForUser(uid: ObjectId) = {
    notesForUser(uid).toList.flatMap(_.tags).filter(_ != ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
    // TODO somehow i can get empty tags...
  }

  def notesForTag(uid: ObjectId, tag: String) =
    notesForUser(uid, ARCHIVE == tag).filter(n => n.tags.contains(tag) || (tag == ALL) || (tag == NONE && n.tags.isEmpty))

  def notesForTags(uid: ObjectId, tags: Seq[String]) =
    notesForUser(uid).filter(n => tags.foldLeft(true)((b, t) => b && n.tags.contains(t)))
}

/** controller for club management */
object NotesLocker extends RazController with Logging {

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  var autosaved = 0L;

  /** for active user */
  def FUH(f: User => VError => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse {
      val harry = Users.findUserById("4fdb5d410cf247dd26c2a784")
      if ("no allow public" == "allow public" || request.queryString.contains("please")) {
        if(! isFromRobot) Audit.logdb("NOTES_AS_HARRY", request.toString)
        ((for (u <- harry) yield {
          Redirect(request.path).withSession(Config.CONNECTED -> Enc.toSession(u.email), "css" -> "light")
        }) getOrElse {
          Audit.logdb("ERR_HARRY", "account is missing???")
          unauthorized("CAN'T SEE PROFILE ")
        })
      } else if ("invite page" == "invite page") {
        if(! isFromRobot) Audit.logdb("NOTES_AS_HARRY", request.toString)
        if(request.path == "/notes")
          Ok(views.html.notes.notesmaininvite(NEWNOTE, autags(harry.get), Seq())(harry))
        else
          Redirect("/notes")
      } else {
        Ok("service status: n/a")
      }
    }
  }

  /** for active user */
  def FAU(f: User => VError => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T SEE PROFILE ")
  }

  def IDOS = (new ObjectId()).toString

  def lform = Form {
    tuple(
      "id" -> text,
      "ver" -> number,
      "content" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)),
      "tags" -> text).verifying("Can't use spaces in tags yet, sorry!",
    { t: (String, Int, String, String) => !t._4.contains(" ") })
  }

  def NEWNOTE = lform.fill(IDOS, 0, "", "")

  def index = FUH { implicit au =>
    implicit errCollector => implicit request =>

    ROne[AutosavedNote]("uid"->au._id) map {as=>
      Ok(views.html.notes.notesmain(lform.fill(as.nid.toString, as.ver, as.content, as.tags), autags, Seq(), true)(Some(au)))
      } getOrElse
      Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq())(Some(au)))
  }

  /** discard current autosaved note */
  def discard(id:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
    ROne[AutosavedNote]("uid"->au._id, "nid"->new ObjectId(id)) map (_.delete(db.tx.local("notes.create")))
    Redirect(routes.NotesLocker.index())
  }

  /** for active user */
  def saveFAU(f: User => VError => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse Ok(views.html.notes.notesnouser())
  }

  def save = saveFAU { implicit au =>
    implicit errCollector => implicit request =>
      lform.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.notes.notesmain(formWithErrors, autags, Seq("err" -> "[Errors...]"))(Some(au))),
      {
        case (ids, ver, content, tags) =>
          import Visibility._

          val id = if (ids.length > 0) new ObjectId(ids) else new ObjectId()
          val wid = WID("Locker", id.toString)

          var msg = ("msg" -> "[Saved in locker]")

          // tags from content
          def tagsfromc = {
            val line = content.lines.toList.headOption

            line.filter(_.startsWith("#")).map { t =>
              t.substring(1)
            } orElse line.filter(_.startsWith(".t ")).map { t =>
              t.substring(3)
            } getOrElse ("")
          }

          // delete here no matter what - if content was empty, then we'll just delete the auttosave

           ROne[AutosavedNote]("uid"->au._id, "nid"->id) foreach (_.delete(db.tx.local("notes.autosave.clear")))
      //        RMany[AutosavedNote]("uid" -> au._id) foreach (_.delete)

          val alltags = (tags + "," + tagsfromc).split("[, ]").map(_.trim.toLowerCase).filter(!_.isEmpty).distinct.toSeq

          Wikis.find(wid) match {
            case Some(w) => {
              msg = ("msg" -> "[Updated]")
              for (
                owns <- (w.by == au._id) orErr { msg = ("err" -> "[Not your note...]"); "no change" };
                //                  can <- Wiki.canEdit(wid, Some(au), Some(w));
                //                  r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission("uWiki");
                //                hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
                nochange <- (w.content != content ||
                  w.tags.mkString(",") != tags) orErr { msg = ("err" -> "[No change...]"); "no change" };
                tooold <- (ver >= w.ver) orErr { msg = ("err" -> "[Old auto-saved content...]"); "old autosaved" };
                nocontent <- ("" != content) orErr { msg = ("err" -> "[No content...]"); "no content" };
                newVer <- Some(w.cloneNewVer(w.label, "md", content, au._id));
                upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr { msg = ("err" -> "[Not allowed...]"); "Not allowed" }
              ) {
                var we = newVer

                if (we.tags.mkString(",") != alltags)
                  we = we.withTags(alltags, au._id)

                db.tx("notes.Save") { implicit txn =>
                  ROne[AutosavedNote]("nid"->id) foreach (_.delete)
                  we.update(we)
                  Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
                  //            Emailer.laterSession { implicit mailSession =>
                  //              au.quota.incUpdates
                  //              if (shouldPublish) notifyFollowersCreate(we, au)
                  //              au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  //            }
                }
                Audit ! WikiAudit("EDIT", w.wid.wpath, Some(au._id))
              }
            }

            case None => {
              if (content.trim.isEmpty)
                msg = ("err" -> "[Empty note...]")
              else {
                var we = model.WikiEntry("Locker", wid.name, "no label", "md", content, au._id, Seq(), "notes", 1, wid.parent)
                we = we.copy(_id = id)

                db.tx("notes.create") { implicit txn =>

                  if (!alltags.isEmpty)
                    we = we.withTags(alltags, au._id)

                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                  //cleanAuth()

                  // visibility?
                  //if (vis != PUBLIC)
                  //                    we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                  we = we.cloneProps(we.props ++ Map("visibility" -> PRIVATE), au._id)
                  we = we.cloneProps(we.props ++ Map("wvis" -> PRIVATE), au._id)

                  we.create
                  Audit ! WikiAudit("CREATE", we.wid.wpath, Some(au._id))

                  admin.SendEmail.withSession { implicit mailSession =>
                    au.quota.incUpdates
                    au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)
                    //          if (notif == "Notify") notifyFollowersCreate(we, au)
                    //          Emailer.tellRaz("New Locker", au.userName, wid.ahref)
                  }
                }
              }
            }
          }

          Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq(msg))(Some(au)))
      })
  }

  def autosave = saveFAU { implicit au =>
    implicit errCollector => implicit request =>
      var msg = ("msg" -> "[Auto Saved]")

      autosaved += 1;

      lform.bindFromRequest.fold(
        formWithErrors => Ok(("err" -> ("[Bad content] "+formWithErrors.errors.map(_.message).mkString)).toString),
        {
          case (ids, ver, content, tags) =>
            val nid = if (ids.length > 0) new ObjectId(ids) else new ObjectId()

            db.ROne[AutosavedNote]("uid"->au._id, "nid"->nid) match {
              case Some(as) => {
                if(as.ver != ver) {
                  msg = ("err" -> "[Different version - not autosaved]")
                } else {
                  msg = ("msg" -> "[Autosave Updated]")
                  for (
                    owns <- (as.uid == au._id) orErr { msg = ("err" -> "[Not your note...]"); "no change" };
                    nochange <- (as.content != content ||
                      as.tags != tags) orErr { msg = ("err" -> "[No change...]"); "no change" }
                  ) {
                    db.tx("notes.autosave.u") { implicit txn =>
                      as.copy(content=content, tags=tags).update
                    }
                  }
                }
              }

              case None => {
                val onote = Lockers.notesById(nid).toList.headOption
                if (content.trim.isEmpty  && tags.trim.isEmpty) {
                  msg = ("msg" -> "[No change]")
                } else if (onote.exists(_.ver > ver)) {
                  msg = ("err" -> "[Old autosave - please discard it]")
                } else {
                  db.tx("notes.autosave.c") { implicit txn =>
                    val as = AutosavedNote(au._id, nid, ver, content, tags)
                    as.create
                  }
                }
              }
          }
            Ok(msg.toString)
        })
  }

  def noteById(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Lockers.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id)
          Ok(views.html.notes.notesmain(lform.fill(n._id.toString, n.ver, n.content, n.tags.mkString(",")), autags, Seq())(Some(au)))
        else
          Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Not your note...]"))(Some(au)))
      } getOrElse {
        Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Note not found]"))(Some(au)))
      }
  }

  def note(wpath: model.CMDWID) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      // TODO NEEDS AUTH AND AUTH

      //      Wikis.find(wpath.wid.copy(cat="Note")).map { n =>
      //        if (n.by == au._id)
      //          Ok(views.html.notes.notesmain(lform.fill(n._id.toString, n.content, n.tags.mkString(",")), autags, Seq(), auth))
      //        else
      //          Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Not your note...]"), auth))
      //      } getOrElse {
      Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Note not found]"))(Some(au)))
    //      }
  }

  def alltips = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Ok(views.html.notes.notesalltips(autags, Seq("msg" -> s"[??]"), auth))
  }

  def alltags = FUH { implicit au =>
    implicit errCollector => implicit request =>
      Ok(views.html.notes.notesalltags(autags, Some(au)))
  }

  type Tags = Seq[(String, Int)]

  private def autags(implicit au: User) = Lockers.tagsForUser(au._id)

  def tag(tag: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      val ltag = tag.split("/").map(_.trim)

      val res = tag match {
        case "recent" => Lockers.notesForTag(au._id, "all").toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case "all" => Lockers.notesForTag(au._id, "all").toList
        case "none" => Lockers.notesForTag(au._id, "none").toList
        case "archive" => Lockers.notesForTag(au._id, "archive").toList
        case _ => Lockers.notesForTags(au._id, ltag).toList
      }

      val outTags = res.flatMap(_.tags).distinct.filterNot(ltag contains _)
      val counted = outTags.map(t => (t, res.count(_.tags contains t))).sortBy(_._2).reverse

      if (!res.isEmpty) {
        // last chance filtering - any moron can come through here and the Lockers may fuck up
        val notes = res.filter(_.by == au._id).take(20).toList

        tag match {
          case "contact" => Ok(views.html.notes.notestagcontact(tag, notes, counted,
            Seq("msg" -> s"[Found ${notes.size} notes]"), auth))
          case _ => Ok(views.html.notes.noteslist(tag, notes, counted,
            Seq("msg" -> s"[Found ${notes.size} notes]"), auth))
        }
      } else {
        // TODO redirect
        Ok(views.html.notes.notesmain(NEWNOTE, autags,
          Seq("msg" -> "[No notes found]"))(Some(au)))
      }
  }

  def invite(e: String, n:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
        Ok(views.html.notes.notesinvitenow(e, n, Seq("msg" -> "[]")))
  }

  // TODO optimize
  def search(q: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      // TODO limit the number of searches - is this performance critical?
      val qi = q.toLowerCase
      var msg = "msg" -> "[No notes found]"

      val wikis = RazMongo.withDb(RazMongo("weLocker").m, "search") { t =>
        for (
          short <- ((q.length() > 2) orErr { msg = "err" -> "[Query too short]"; "too short" }).toList;
          u <- t.find(Map("by" -> au._id)) if (
          (q.length > 1 && u.get("name").asInstanceOf[String].toLowerCase.contains(qi)) ||
            (q.length > 1 && u.get("label").asInstanceOf[String].toLowerCase.contains(qi)) ||
            (q.length() > 2 && u.get("content").asInstanceOf[String].toLowerCase.contains(qi)))
        ) yield u
      }.toList

      Audit.logdb("QUERY", q, "Results: " + wikis.size)

      if (!wikis.isEmpty) {
        val res = wikis.map(WikiEntry.grated _)
        // last chance filtering - any moron can come through here and the Lockers may fuck up
        val notes = res.filter(_.by == au._id).take(20).toList
        Ok(views.html.notes.noteslist(q, notes, autags,
          Seq("msg" -> s"[Found ${notes.size} notes]"), Some(au), true))
      } else {
        // TODO redirect
        Ok(views.html.notes.notesmain(NEWNOTE, autags,
          Seq(msg))(Some(au)))
      }
  }

  // TODO optimize
  def contacts = FUH { implicit au =>
    implicit errCollector => implicit request =>

    def name(we:model.WikiEntry) = {
      we.contentTags.get("name").filter(_ != we._id.toString).orElse(we.contentTags.get("email").map(_.replaceAll("@.*$", ""))).mkString
    }

    val contacts = Lockers.notesForTag(au._id, "contact").map(we=>name(we)).toList

    Ok("["+contacts.map(s=>s""" "$s" """).mkString(",")+"]").as("text/json")
  }

  // when configuration changes, call this to udpate mine
  Config callback { () =>
    inw = Wikis.find("Admin", "notes-tips").toList
  }

  private var inw = Wikis.find("Admin", "notes-tips").toList

  def notesWiki = inw

  def tips = {
    notesWiki.flatMap(_.sections.filter(_.name startsWith "tip")).map(_.content)
  }

  def sitehtml(code: String) = {
    val m = notesWiki.flatMap(_.sections.filter(_.name == code)).map(_.content).map(x => Wikis.format(WID("?", "?"), "md", x)).headOption.getOrElse(code)
    // todo why the heck does it put those?
    m.replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", "")
  }

  def xhtml(tags: String, au: Option[User]) = {
    au.flatMap { u =>
      val w = Lockers.notesForTags(u._id, tags.split(",").toSeq).take(1).find(x => true)
      w.map(x =>
        Wikis.format(x.wid, x.markup, x.content).replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", ""))
    }.getOrElse(sitehtml(tags))
  }
}

