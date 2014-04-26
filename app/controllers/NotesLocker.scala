/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
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
import play.api.mvc.Action
import play.api.mvc.Request
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
import admin.Config
import admin.SendEmail
import admin.VError
import db.REntity
import db.RMany
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
import db.RCreate

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

object Lockers {
  //  def notesForUser (uid:ObjectId) = RMany[Locker]("by"->uid)
  //  def tagsForUser (uid:ObjectId) = notesForUser(uid).toList.flatMap(_.tags).distinct
  //  def notesForTag (uid:ObjectId, tag:String) = notesForUser(uid).filter(_.tags.contains(tag))

  val cat = "Locker"

  def notesById(id: ObjectId) = Wikis.weTable(cat).findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))
  def notesForUser(uid: ObjectId) = Wikis.weTable(cat).find(Map("by" -> uid)) map (grater[WikiEntry].asObject(_))
  def tagsForUser(uid: ObjectId) = {
    notesForUser(uid).toList.flatMap(_.tags).groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
  }
  def notesForTag(uid: ObjectId, tag: String) =
    notesForUser(uid).filter(n => n.tags.contains(tag) || (tag == "all") || (tag == "none" && n.tags.isEmpty))
  def notesForTags(uid: ObjectId, tags: Seq[String]) =
    notesForUser(uid).filter(n => tags.foldLeft(true)((b, t) => b && n.tags.contains(t)))
}

/** controller for club management */
object NotesLocker extends RazController with Logging {

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  /** for active user */
  def FU(f: User => VError => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T SEE PROFILE ")
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

  def index = Action { implicit request =>
    implicit val errCollector = new VError()
    val tags = auth map { x => Lockers.tagsForUser(x._id) } getOrElse Seq() //[(String,Int)]()
    // this works wihtout a user too
    Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), tags, Seq(), auth))
  }

  def IDOS = (new ObjectId()).toString

  def lform = Form {
    tuple(
      "id" -> text,
      "content" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)),
      "tags" -> text
      ).verifying("Can't use spaces in tags yet, sorry!", 
          {t:(String,String,String) => !t._3.contains(" ")})
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
        formWithErrors => BadRequest(views.html.notes.notesmain(formWithErrors, autags, Seq("err" -> "[Errors...]"), auth)),
        {
          case (ids, content, tags) =>
            import Visibility._

            val id = if (ids.length > 0) new ObjectId(ids) else new ObjectId()
            val wid = WID("Locker", id.toString)

            var msg = ("msg" -> "[Saved in locker]")

            def tagsfromc = {
              content.lines.toList.headOption.filter(_.startsWith("#")).map { t =>
                t.substring(1)
              } getOrElse ("")
            }

            def alltags = (tags + "," + tagsfromc).split(",").map(_.trim).filter(!_.isEmpty).distinct.toSeq

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
                  newVer <- Some(w.cloneNewVer(w.label, "md", content, au._id));
                  upd <- Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr { msg = ("err" -> "[Not allowed...]"); "Not allowed" }
                ) {
                  var we = newVer

                  if (we.tags.mkString(",") != tags)
                    we = we.withTags(alltags, au._id)

                  db.tx("Wiki.Save") { implicit txn =>
                    // can only change label of links OR if the formatted name doesn't change
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

                  db.tx("wiki.create") { implicit txn =>
                    val allt = alltags
                    if (!allt.isEmpty)
                      we = we.withTags(allt, au._id)

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

            Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq(msg), auth))
          //            Redirect(routes.notesocker.index())
        })
  }

  def noteById(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Lockers.notesById(new ObjectId(id)).map { n =>
        if (n.by == au._id)
          Ok(views.html.notes.notesmain(lform.fill(n._id.toString, n.content, n.tags.mkString(",")), autags, Seq(), auth))
        else
          Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Not your note...]"), auth))
      } getOrElse {
        Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Note not found]"), auth))
      }
  }

  def note(wpath: model.CMDWID) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      //        if (n.by == au._id)
      //          Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Note not found]"), auth))
      //        else
      //          Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Not your note...]"), auth))
      Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags, Seq("err" -> "[Note not found]"), auth))
    //      }
  }

  def alltips = FU { implicit au =>
    implicit errCollector => implicit request =>

      Ok(views.html.notes.notesalltips(autags, Seq("msg" -> s"[??]"), auth))
  }

  def alltags = FAU { implicit au =>
    implicit errCollector => implicit request =>

      Ok(views.html.notes.notesalltags(
        Seq("msg" -> s"[??]"), auth))
  }

  type Tags = Seq[(String, Int)]

  def autags(implicit au: User) = Lockers.tagsForUser(au._id)

  def tag(tag: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val ltag = tag.split("/").map(_.trim)

      //     val res = ltag.foldLeft(Lockers.notesForTag(au._id, tag))((iter,t)=>iter.filter(_.tags contains t))
      val res = Lockers.notesForTags(au._id, ltag).toList

      val outTags = res.flatMap(_.tags).distinct.filterNot(ltag contains _)
      val counted = outTags.map(t => (t, res.count(_.tags contains t)))

      if (!res.isEmpty) {
        val notes = res.take(40).toList
        Ok(views.html.notes.noteslist(tag, notes, counted,
          Seq("msg" -> s"[Found ${notes.size} notes]"), auth))
      } else {
        // TODO redirect
        Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags,
          Seq("msg" -> "[No notes found]"), auth))
      }
  }

  // TODO optimize
  def search(q: String) = FU {
    implicit au =>
      implicit errCollector => implicit request =>
        // TODO limit the number of searches - is this performance critical?
        val qi = q.toLowerCase()
        var msg = ("msg" -> "[No notes found]")

        val wikis = RazMongo.withDb(RazMongo("weLocker").m) { t =>
          for (
            short <- ((q.length() > 2) orErr { msg = ("err" -> "[Query too short]"); "too short" }).toList;
            u <- t.find(Map("by" -> au._id)) if (
              (q.length > 1 && u.get("name").asInstanceOf[String].toLowerCase.contains(qi)) ||
              (q.length > 1 && u.get("label").asInstanceOf[String].toLowerCase.contains(qi)) ||
              (q.length() > 2 && u.get("content").asInstanceOf[String].toLowerCase.contains(qi)))
          ) yield u
        }.toList

        Audit.logdb("QUERY", q, "Results: " + wikis.size)

        if (!wikis.isEmpty) {
          val notes = wikis.map(WikiEntry.grated _).take(40).toList
          Ok(views.html.notes.noteslist(q, notes, autags,
            Seq("msg" -> s"[Found ${notes.size} notes]"), Some(au), true))
        } else {
          // TODO redirect
          Ok(views.html.notes.notesmain(lform.fill(IDOS, "", ""), autags,
            Seq(msg), auth))
        }

  }

  // todo cache this or something
  def notesWiki = Wikis.find("Admin", "notes-tips").toList
  def tips = {
    notesWiki.flatMap(_.sections.filter(_.name startsWith "tip")).map(_.content)
  }

  def html(code: String) = {
    val m = notesWiki.flatMap(_.sections.filter(_.name == code)).map(_.content).map(x => Wikis.format(WID("?", "?"), "md", x)).headOption.getOrElse(code)
    // todo why the heck does it put those?
    m.replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", "")
  }
}

