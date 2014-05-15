/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin._
import model._
import db._
import db.RazSalatContext.ctx
//import play.api.data.Form
//import play.api.data.Forms.mapping
//import play.api.libs.json.Json
//import play.api.mvc.Action
//import play.api.mvc.Request
//import razie.Logging
//import razie.cout
//import razie.clog
import model.Sec.EncryptedS
//import play.api.data.Forms.nonEmptyText
//import play.api.data.Forms.number
//import play.api.data.Forms.text
//import play.api.data.Forms.tuple
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.Request
import play.api.mvc.Result
import razie.{cout, Logging}
//import scala.Some
//import model.WikiAudit
//import model.User
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import model.WikiAudit
//import controllers.AutosavedNote
import model.User

/** a contact connection between two users */
@db.RTable
case class NotesContact (
  oId: ObjectId, // ownwer
  email: String, // email of other
  nick: String, // nickname for other
  uId: Option[ObjectId], // user id of other
  noteId: Option[ObjectId], // in case there are more notes about this contact
  rkId: Option[ObjectId]=None,
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId) extends REntity[NotesContact] {

  // optimize access to User object
  lazy val o = oId.as[User]
  lazy val u = uId.map(_.as[User])

  override def toString: String = toJson
}

/** autosaved notes */
@db.RTable
case class AutosavedNote(
  uid: ObjectId,
  nid: ObjectId,
  ver:Int,
  content: String,
  tags: String,
  _id: ObjectId = new ObjectId()) extends REntity[AutosavedNote] {

  override def create(implicit txn: db.Txn) = RCreate.noAudit[AutosavedNote](this)
  override def update (implicit txn: db.Txn) = RUpdate.noAudit(Map("_id" -> _id), this)
  override def delete(implicit txn: db.Txn) = RDelete.noAudit[AutosavedNote](this)
}

///** notes shared to a circle */
//@db.RTable
//case class CircleShare (
//                       noteId: ObjectId, // the note it's about
//                       to: String,
//                       ownerId: ObjectId,
//                       how: String="", // "" - read/write, "ro" - readonly
//                       crDtm:DateTime = DateTime.now,
//                       _id: ObjectId = new ObjectId()) extends REntity[NoteShare] {
//}

/** shared notes */
@db.RTable
case class NoteShare (
  noteId: ObjectId, // the note it's about
  toId: ObjectId,
  ownerId: ObjectId,
  how: String="", // "" - read/write, "ro" - readonly
  crDtm:DateTime = DateTime.now,
                  _id: ObjectId = new ObjectId()) extends REntity[NoteShare] {
  def note = Wikis.find(noteId)
}

/** inbox */
@db.RTable
case class Inbox(
  toId: ObjectId,
  fromId: ObjectId,
  what: String, // "Msg", "Action", "Share"
  noteId: Option[ObjectId], // the note it's about
  content: String,
  tags: String,
  state: String, // u-unread, r-read, d-deleted
  crDtm:DateTime = DateTime.now,
  updDtm:DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Inbox] {

  def from : String = ROne[NotesContact]("uid"->fromId).map(_.nick) orElse fromId.as[User].map(_.ename) getOrElse "?"
}

object Inbox {
  def count(uid: ObjectId) = {
    RCount[Inbox]("toId"->uid, "state"->"u")
  }
  def find(uid: ObjectId) =
    RMany[Inbox]("toId"->uid) filter (_.state != "d")
}

object Notes {
  //  def notesForUser (uid:ObjectId) = RMany[Locker]("by"->uid)
  //  def tagsForUser (uid:ObjectId) = notesForUser(uid).toList.flatMap(_.tags).distinct
  //  def notesForTag (uid:ObjectId, tag:String) = notesForUser(uid).filter(_.tags.contains(tag))

  final val cat = "Note"
  final val ENC = "encrypted"

  def dec(au:User)(w:WikiEntry) = if(w.by == au._id && w.tags.contains(ENC))w.copy(content=w.content.dec) else w

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

  def isShared (we:WikiEntry, uid:ObjectId) = {
    ROne[NoteShare]("noteId"->we._id, "toId"->uid).isDefined
  }

  def sharedNotesByTag (uid:ObjectId, tag:String) = {
    RMany[NoteShare]("toId"->uid).flatMap(_.note).filter(n => n.tags.contains(tag) || (tag == Notes.ALL) || (tag == Notes.NONE && n.tags.isEmpty))
  }
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
        if(! isFromRobot) Audit.logdb("NOTES_AS_HARRY", request.toString, s"Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
        ((for (u <- harry) yield {
          Redirect(request.path).withSession(Config.CONNECTED -> Enc.toSession(u.email), "css" -> "light")
        }) getOrElse {
          Audit.logdb("ERR_HARRY", "account is missing???")
          unauthorized("CAN'T SEE PROFILE ")
        })
      } else if ("invite page" == "invite page") {
        if(! isFromRobot) Audit.logdb("NOTES_AS_HARRY", request.toString, s"Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}")
        if(request.path == "/notes")
          Ok(views.html.notes.notesmaininvite(NEWNOTE, autags(harry.get), Seq())(harry))
        else if(request.path startsWith "/notes/tag/")// && isFromRobot)
          f(harry.get)(errCollector)(request)
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
    }) getOrElse {
      Redirect("/notes")
      //    unauthorized("Account is either locked or suspended... please contact support.")
    }
  }

  def IDOS = (new ObjectId()).toString

  def lform = Form {
    tuple(
      "next" -> text,
      "id" -> text,
      "ver" -> number,
      "content" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)),
      "tags" -> text).verifying("Can't use spaces in tags yet, sorry!",
    { t: (String, String, Int, String, String) => !t._5.contains(" ") })
  }

  def NEWNOTE = lform.fill("", IDOS, 0, "", "")

  def index = FUH { implicit au =>
    implicit errCollector => implicit request =>

      ROne[AutosavedNote]("uid"->au._id) map {as=>
        Ok(views.html.notes.notesmain(lform.fill("", as.nid.toString, as.ver, as.content, as.tags), autags, Seq(), true)(Some(au)))
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
        case (next, ids, ver, content, tags) =>
          import Visibility._

          val id = if (ids.length > 0) new ObjectId(ids) else new ObjectId()
          val wid = WID("Note", id.toString)

          var msg = ("msg" -> "[Saved in locker]")

          // delete here no matter what -
          ROne[AutosavedNote]("uid"->au._id, "nid"->id) foreach (_.delete(db.tx.local("notes.autosave.clear")))
          //        RMany[AutosavedNote]("uid" -> au._id) foreach (_.delete)

          def alltags (more:String) = (tags + "," + more).split("[, ]").map(_.trim.toLowerCase).filter(!_.isEmpty).distinct.toSeq

          def preprocess(iwe:WikiEntry, isNew:Boolean):WikiEntry = {
            var we = iwe
            val wep = we.preprocessed
            var moreTags = ""

            // apply content tags
            if(wep.tags("name") != we.name) we = we.copy(name=wep.tags("name"))
            if(wep.tags.contains("share")) moreTags = moreTags + ",shared"

            val atags = alltags(moreTags)
            if (we.tags != atags)
              we = we.withTags(atags, au._id)

            if(we.tags.contains(Notes.ENC))
              we = we.copy(content=we.content.enc)

            we
          }

          Wikis.find(id) match {
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
                var we = preprocess(newVer, true)

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
                Audit ! WikiAudit("EDIT_NOTE", w.wid.wpath, Some(au._id))
                process(we, au)
              }
            }

            case None => {
              if (content.trim.isEmpty)
                msg = ("err" -> "[Empty note...]")
              else {
                var we = model.WikiEntry("Note", wid.name, "", "md", content, au._id, Seq(), "notes", 1, wid.parent)
                we = we.copy(_id = id)
                we = preprocess(we, false)

                db.tx("notes.create") { implicit txn =>

                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                  //cleanAuth()

                  // visibility?
                  //if (vis != PUBLIC)
                  //                    we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                  we = we.cloneProps(we.props ++ Map("visibility" -> PRIVATE), au._id)
                  we = we.cloneProps(we.props ++ Map("wvis" -> PRIVATE), au._id)

                  we.create
                  Audit ! WikiAudit("CREATE_NOTE", we.wid.wpath, Some(au._id))

                  process(we, au)
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

          // redirect if it was an edited note, back to where it was
          if (next != "")
            Redirect(next)
          else
            Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq(msg))(Some(au)))
      })
  }

  private def process (we:WikiEntry, au:User) {
    object NotesParser extends ParserCommons {
      case class State(s: String, tags: Map[String, String] = Map(), ilinks: List[ILink] = List(), decs: List[WikiEntry => WikiEntry] = List()) {
        def this(s: String, ilinks: List[ILink]) = this(s, Map(), ilinks)
        def +(other: State) = State(this.s + other.s, this.tags ++ other.tags, this.ilinks ++ other.ilinks, this.decs ++ other.decs)
      }
      implicit def toState(s: String) = State(s)

      def apply(input: String) = parseAll(line, input) match {
        case Success(value, _) => value
        // don't change this format
        case NoSuccess(msg, next) => (s"[[CANNOT PARSE]] [${next.pos.toString}] : ${msg}")
      }

      type PS = Parser[State]

      def line: P = dpShare | dpAct

      def nco(s: String) =
        (ROne[NotesContact]("oId"->au._id, "nick" -> s) orElse Notes.notesForTag(au._id, "contact").filter(we=>name(we) == s).toList.headOption.flatMap(we=>ROne[NotesContact]("oId"->au._id, "email" -> we.contentTags.get("email").getOrElse(""))))

      def name(we:model.WikiEntry) = {
        we.contentTags.get("name").filter(_ != we._id.toString).orElse(we.contentTags.get("email").map(_.replaceAll("@.*$", ""))).mkString
      }

      val c2 = RMany[NotesContact]("oId"->au._id).map(_.nick).toList

      def dpShare: P = """^\.share """.r ~> rep("to:" ~ "[^ \n\r]+".r <~ opt(" ")) ^^ {
        case tos => {
          tos.collect {
            case _ ~ to => {
              nco(to).filter(_.uId.isDefined).foreach {nc=>
                if(ROne[NoteShare]("noteId"->we._id, "toId"->nc.uId.get).isEmpty) db.tx("notes.inbox") {implicit txn=>
                  NoteShare(we._id, nc.uId.get, au._id).create
                  Inbox(nc.uId.get, au._id, "Share", Some(we._id), "", "", "u").create(db.tx.local("notes.inbox"))
                  admin.SendEmail.withSession { implicit mailSession =>
                    Emailer.noteShared(au.ename, nc.email, nc.nick, s"/note/id/${we._id}")
                  }
                }
              }
            }
          }
          ""
        }
      }

      def dpAct: P = """^\.a """.r ~> rep("to:" ~ "[^ \n\r]+".r) ~ """[^\n\r]*""".r  ^^ {
        case tos ~ value=> {
          tos.collect {
            case _ ~ to => {
              def uid(s:String) = new ObjectId()
              Inbox(uid(to), au._id, "Action", Some(we._id), "", "", "u" ).create(db.tx.local("notes.inbox"))
              ""
            }
          }
          ""
        }
      }
    }

    we.content.lines.filter(l=>l.length > 0 && l.charAt(0) == '.') foreach {line:String=>
      NotesParser (line)
    }
  }

  def autosave = saveFAU { implicit au =>
    implicit errCollector => implicit request =>
      var msg = ("msg" -> "[Auto Saved]")

      autosaved += 1;

      lform.bindFromRequest.fold(
      formWithErrors => Ok(("err" -> ("[Bad content] "+formWithErrors.errors.map(_.message).mkString)).toString),
      {
        case (_, ids, ver, content, tags) =>
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
              val onote = Notes.notesById(nid).toList.headOption
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

  /** simplify showing simple topics like privacy, TOS etc */
  def showWid(wpath:model.CMDWID, count:Int) = FUH { implicit au =>
    implicit errCollector => implicit request =>

      val wl = wpath.wid.flatMap(_.page).filter(page => Wiki.canSee(page.wid, Option(au), Some(page)).getOrElse(false))

      wl.map { n =>
        Ok(views.html.notes.notesview("", n, autags, Seq("msg" -> s"[view]"), Some(au)))
      } getOrElse {
        Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Note not found]"))(Some(au)))
      }
  }

  def viewNoteById(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      import razie.|>._
      val next = request.headers.get("Referer").mkString |> {x => if(x.contains(Config.hostport) || x.contains("/notes"))x else ""}
      Notes.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id || Notes.isShared(n, au._id))
          Ok(views.html.notes.notesview("", Notes.dec(au)(n), autags, Seq("msg" -> s"[view]"), Some(au)))
        else
          Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Not your note...]"))(Some(au)))
      } getOrElse {
        Ok(views.html.notes.notesmain(NEWNOTE, autags, Seq("err" -> "[Note not found]"))(Some(au)))
      }
  }

  /** edit a note */
  def noteById(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      import razie.|>._
      val next = request.headers.get("Referer").mkString |> {x => if(x.contains(Config.hostport) || x.contains("/notes"))x else ""}
      Notes.notesById(new ObjectId(nid)).map(Notes.dec(au)).map { n =>
        if (n.by == au._id)
          Ok(views.html.notes.notesmain(lform.fill(next, n._id.toString, n.ver, n.content, n.tags.mkString(",")), autags, Seq())(Some(au)))
        else if(Notes.isShared(n, au._id))
          Ok(views.html.notes.notesmain(lform.fill(next, n._id.toString, n.ver, n.content, n.tags.mkString(",")), autags, Seq("msg"->"Shared note..."), false, true)(Some(au)))
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
      //          Ok(views.html.notes.notesmain(lform.fill("", n._id.toString, n.content, n.tags.mkString(",")), autags, Seq(), auth))
      //        else
      //          Ok(views.html.notes.notesmain(lform.fill("", IDOS, "", ""), autags, Seq("err" -> "[Not your note...]"), auth))
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

  private def autags(implicit au: User) = Notes.tagsForUser(au._id)

  def tag(tag: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      val ltag = tag.split("/").map(_.trim)

      //todo this loads everything...
      val res = tag match {
        case "recent" => Notes.notesForTag(au._id, "all").toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case "none" => Notes.notesForTag(au._id, "none").toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case "archive" => Notes.notesForTag(au._id, "archive").toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case _ => Notes.notesForTags(au._id, ltag).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
      }

      //todo this is stupid
      val outTags = res.flatMap(_.tags).distinct.filterNot(ltag contains _)
      val counted = outTags.map(t => (t, res.count(_.tags contains t))).sortBy(_._2).reverse

      // last chance filtering - any moron can come through here and the Notes may fuck up
      val notes = res.filter(_.by == au._id).take(20).toList // TOOD optimize - inbox doesn't need etc

      tag match {
        case "inbox" => {
          val inb = Inbox.find(au._id).toList
          Ok(views.html.notes.notestaginbox(tag, notes, counted,
            Seq("msg" -> s"[Found ${notes.size} notes]"), Some(au))(inb))
        }
        case "contact" => Ok(views.html.notes.notestagcontact(tag, notes, counted,
          Seq("msg" -> s"[Found ${notes.size} notes]"), Some(au))(RMany[NotesContact]("oId"->au._id).toList))
        case _ if(!res.isEmpty) => Ok(views.html.notes.noteslist(tag, notes, counted,
          Seq("msg" -> s"[Found ${notes.size} notes]"), Some(au)))
        case _ if(res.isEmpty) =>
          // TODO redirect
          Ok(views.html.notes.notesmain(NEWNOTE, autags,
            Seq("msg" -> "[No notes found]"))(Some(au)))
      }
  }

  def invite(e: String, n:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Ok(views.html.notes.notesinvitenow(e, n, Seq("msg" -> "[]")))
  }

  /** friend accepts connection */
  def accept(e: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      val other = Users.findUserById(e)
      other match {
        case Some(o) => {
          db.tx("notes.connect.accept") {implicit txn=>
            // first my end of it:
            if(ROne[NotesContact]("oId"->au._id, "uId"->o._id).isEmpty)
            //todo find the note fo this contact
              NotesContact(au._id, o.email.dec, o.ename, Some(o._id), None).create
            ROne[NotesContact]("oId"->o._id, "uId"->au._id) match {
              case Some(_) => Msg2("Already connected")
              case None =>
                ROne[NotesContact]("oId"->o._id, "email"->au.email.dec) match {
                  case Some(c) => {
                    c.copy(uId=Some(au._id)).update
                    Msg2("Contact updated")
                  }
                  case None => {
                    //todo find the note fo this contact
                    NotesContact(o._id, au.email.dec, au.ename, Some(au._id), None).create
                    Msg2("Ok, connected - now he/she can share notes with you and you can <strong>.share to:"+au.ename+"</strong")
                  }
                }
            }
          }
        }
        case _ =>
          Msg2("Can't find other user...")
      }
  }

  // format a note into html
  def format(wid: WID, markup: String, icontent: String, iwe: Option[WikiEntry] = None, au:Option[model.User])(implicit request:Request[_]) = {
    iwe.filter(x=>
      ((au exists (_ hasPerm Perm.codeMaster)) || (au exists (_ hasPerm Perm.adminDb))) &&
        (icontent.lines.find(_ startsWith ".sfiddle").isDefined)).fold(
        (if(icontent.lines.find(_ startsWith ".sfiddle").isDefined) "[[No permission for sfiddles]]" else "") +
        model.Wikis.format(wid, markup, icontent, iwe)
      ) {we=>
      val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
      try {
        if(we.tags contains "js") {
          views.html.fiddle.jsfiddle2("js", script, None).body
        } else if(we.tags contains "scala") {
          views.html.fiddle.jsfiddle2("scala", script, None).body
        } else {
          "unknown language"
        }
      } catch  {
        case _ : Throwable =>
          ("""<font style="color:red">[[BAD FIDDLE - check syntax]]</font>""")
      }
    }
  }

  // TODO optimize
  def search(q: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      // TODO limit the number of searches - is this performance critical?
      val qi = q.toLowerCase
      var msg = "msg" -> "[No notes found]"

      val wikis = RazMongo.withDb(RazMongo("weNote").m, "search") { t =>
        for (
          short <- ((q.length() > 2) orErr { msg = "err" -> "[Query too short]"; "too short" }).toList;
          u <- t.find(Map("by" -> au._id)) if (
          (q.length > 1 && u.getAs[String]("name").exists(_.toLowerCase.contains(qi))) ||
            (q.length > 1 && u.getAs[String]("label").exists(_.toLowerCase.contains(qi)) ||
            (q.length() > 2 && (
                if(u.getAs[Seq[String]]("tags").exists(_ contains Notes.ENC))
                  u.as[String]("content").dec else u.as[String]("content")
                ).toLowerCase.contains(qi))
              )
          )
        ) yield  u
      }.toList

      Audit.logdb("QUERY", q, "Results: " + wikis.size)

      if (!wikis.isEmpty) {
        val res = wikis.map(WikiEntry.grated _)
        // last chance filtering - any moron can come through here and the Notes may fuck up
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

      val c1 = Notes.notesForTag(au._id, "contact").map(we=>name(we)).toList
      val c2 = RMany[NotesContact]("oId"->au._id).map(_.nick).toList

      Ok("["+(c1 ::: c2).map(s=>s""" "$s" """).mkString(",")+"]").as("text/json")
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
      val w = Notes.notesForTags(u._id, tags.split(",").toSeq).take(1).find(x => true)
      w.map(x =>
        Wikis.format(x.wid, x.markup, x.content).replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", ""))
    }.getOrElse(sitehtml(tags))
  }
}

/** controller for server side fiddles / services */
object SFiddles extends RazController with Logging {

  import NotesLocker.FAU

  def qtojson (q:Map[String,String]) = "{" + q.map(t=>s"""${t._1} : "${t._2}" """).mkString(",") + "}"
  def qtourl (q:Map[String,String]) = q.map(t=>s"""${t._1}=${t._2}""").mkString("&")

  final val SFIDDLE = "sfiddle"

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(au._id,SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id,SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      notes.headOption.filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for $path")
      ) {we=>
        val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
        val lang = if(we.tags contains "js") "js" else if(we.tags contains "scala") "scala" else ""
        val (_,res) = isfiddle(script, lang, Some(we))
        Ok(res)
      }
  }

  /** run a fiddle for testing */
  def sfiddle2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for id $id")
      ) {we=>
        val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
        val j = ModTma.razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
        val (_,res) = isfiddle(j, lang)
        Ok(res)
      }
  }

  /** run a fiddle */
  private def isfiddle(script: String, lang:String, we:Option[WikiEntry] = None)(implicit request:Request[_], au:User) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    if(lang == "js") {
      val qj = qtojson(q)
      val jscript = s"""var queryParms = $qj;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("JavaScript")
        val res = engine.eval(jscript)
        Audit.logdb("SFIDDLE_EXEC", "JS", jscript)
        (true, res.toString)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript",t)
          (false, jscript + "\n\n" + t)
        }
      }
    } else if(lang == "scala") {
      try {
        val res = WikiScripster.impl.runScript(script, we, Some(au), q)
        Audit.logdb("SFIDDLE_EXEC", "scala", script)
        (true, res.toString)
      } catch {
        case t:Throwable => {
          log(s"while executing script\n$script",t)
          (false, script+ "\n\n" + t)
        }
      }
    } else (false, script)
  }

  /** display the play sfiddle screen */
  def play2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = ModTma.razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        Ok(views.html.fiddle.play2(lang, j, q, Some(au)))
      }
  }
}

