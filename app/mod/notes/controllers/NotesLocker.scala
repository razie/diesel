package mod.notes.controllers

import _root_.controllers._
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import mod.diesel.model.WG
import mod.notes.controllers
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc._
import play.twirl.api.Html
import razie.audit.Audit
import razie.db.RazSalatContext.ctx
import razie.db._
import razie.diesel.dom.WikiDomain
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.parser.ParserCommons
import razie.js
import razie.wiki.{Enc, Services, WikiConfig}
import razie.{Logging, cout}
import razie.hosting.Website
import scala.util.parsing.input.Positional

/** autosaved notes */
@RTable
case class AutosavedNote(
  uid: ObjectId,
  name: String,
  nid: ObjectId,
  ver:Int,
  content: String,
  tags: String,
  _id: ObjectId = new ObjectId()) extends REntity[AutosavedNote] {

  override def create(implicit txn: Txn) = RCreate.noAudit[AutosavedNote](this)
  override def update (implicit txn: Txn) = RUpdate.noAudit(Map("_id" -> _id), this)
  override def delete(implicit txn: Txn) = RDelete.noAudit[AutosavedNote](this)
}

/** special tags */
object NotesTags {
  final val ARCHIVE = "archive"
  final val ALL = "all"
  final val NONE = "none"
  final val RECENT = "recent"
  final val INBOX = "inbox"

  final val ENC = "encrypted"
  final val SHARED = "shared"
  final val NAME = "name"
  final val CONTACT = "contact"
  final val EMAIL = "email"
  final val SFIDDLE = "sfiddle"
  final val FIDDLE = "fiddle"
  final val CIRCLE = "circle"

  final val SPECIAL_TAGS = Array(ARCHIVE,ALL,NONE,RECENT,INBOX)
}

/** notes organized by books per realms */
case class Book (realm:String, name:String, pinTags:TagQuery=new TagQuery("")) {
  val public = pinTags.public
}

//todo Notes should be a module, working per realm
object Notes {
  import NotesTags._

  final val CAT = "Note"

  final val wiki = Wikis(WikiConfig.NOTES)

  def dec(au:User)(w:WikiEntry) = if(w.by == au._id && w.tags.contains(NotesTags.ENC))w.copy(content=w.content.dec) else w

  def notesById(id: ObjectId)(implicit request:Request[Any]) =
    wiki.weTable(CAT).findOne(Map("_id" -> id)) map (grater[WikiEntry].asObject(_))
  def notesForUser(book:Book, uid: ObjectId, archived: Boolean = false) =
    wiki.weTable(CAT).find(Map("by" -> uid, "realm" -> book.realm)) map (grater[WikiEntry].asObject(_)) filter {n =>
      !n.tags.contains(NotesTags.ARCHIVE) || archived
    } filter { n=>
      book.pinTags.matches(n.tags)
    }
  // todo optimize and protect better?
  def notesForPublic(book:Book, uid: ObjectId, archived: Boolean = false) =
    wiki.weTable(CAT).find(Map("realm" -> book.realm)) map (grater[WikiEntry].asObject(_)) filter {n =>
      !n.tags.contains(NotesTags.ARCHIVE) || archived
    } filter { n=>
      book.public && book.pinTags.matches(n.tags)
    }
  def tagsForUser(book:Book, uid: ObjectId) = {
    notesForUser(book, uid).toList.flatMap(_.tags).filter(_ != NotesTags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
    // TODO somehow i can get empty tags...
  }

  // todo optimize this and query with smart mongo query on tags
  def notesForTag(book:Book, uid: ObjectId, tag: String) =
    notesForUser(book, uid, ARCHIVE == tag).filter(n => n.tags.contains(tag) || (tag == ALL) || (tag == NONE && n.tags.isEmpty))

  def notesForNotesTags(book:Book, uid: ObjectId, tags: Seq[String]) =
    notesForUser(book, uid).filter(n => tags.foldLeft(true)((b, t) => b && n.tags.contains(t)))

  def isShared (we:WikiEntry, uid:ObjectId) = {
    ROne[NoteShare]("noteId"->we._id, "toId"->uid).isDefined
  }

  def sharedNotesByTag (uid:ObjectId, tag:String) = {
    RMany[NoteShare]("toId"->uid).flatMap(_.note).filter(n => n.tags.contains(tag) || (tag == ALL) || (tag == NONE && n.tags.isEmpty))
  }
}

/** controller for notes management */
object NotesLocker extends RazController with Logging {
  import Notes.CAT
  import NotesTags._
  import play.api.data.Forms._
  import play.api.data._

  var autosaved = 0L;
  lazy val HARRY = Users.findUserById("4fdb5d410cf247dd26c2a784")

  /** for active user or HArry Potter */
  def FUH(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    def auditIt =
      if(! isFromRobot)
        Audit.logdb("NOTES_AS_HARRY", request.toString, s"Referer=${request.headers.get("Referer")} - X-Forwarde-For: ${request.headers.get("X-Forwarded-For")}  User-Agent: ${request.headers.get("User-Agent")}")

    (for (
      au <- auth
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse {
      if ("no allow public" == "allow public" || request.queryString.contains("please")) {
        auditIt
        (for (u <- HARRY) yield {
          Redirect(request.path).withSession(Services.config.CONNECTED -> Enc.toSession(u.email), "css" -> "light")
        }) getOrElse {
          Audit.logdb("ERR_HARRY", "account is missing???")
          unauthorized("CAN'T SEE PROFILE ")
        }
      } else if ("invite page" == "invite page") {
        auditIt
        if(request.path == "/notes")
          NOK("", autags(request, HARRY.get), Seq.empty, false)(HARRY.get, request) { implicit stok=>
            views.html.notes.notesmaininvite(NEWNOTE)
          }
        else if(request.path startsWith "/notes/tag/")// && isFromRobot)
          f(HARRY.get)(errCollector)(request)
        else
          Redirect("/notes")
      } else {
        Ok("service status: n/a")
      }
    }
  }

  override def FAU(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
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
      "name" -> text,
      "id" -> text,
      "ver" -> number,
      "content" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)),
      "tags" -> text).verifying("Can't use spaces in tags yet, sorry!",
    { t : (String,String, String, Int, String, String) => !t._6.contains(" ") })
  }

  def NEWNOTENB = lform.fill("", "", IDOS, 0, "", "")
  def NEWNOTE(implicit request:Request[_]) = lform.fill("", "", IDOS, 0, "", book.pinTags.ltags.mkString(","))

  def index = FUH { implicit au =>
    implicit errCollector => implicit request =>

      ROne[AutosavedNote]("uid"->au._id) map {as=>
      NOK ("", autags, Seq.empty, false) apply {implicit stok=>
        views.html.notes.notesmain(lform.fill("", "", as.nid.toString, as.ver, as.content, as.tags), true)
      }
      } getOrElse
        OkNewNote()
  }

  /** discard current autosaved note */
  def discard(id:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      ROne[AutosavedNote]("uid"->au._id, "nid"->new ObjectId(id)) map (_.delete(tx.local("notes.create", au.userName)))
      Redirect(routes.NotesLocker.index())
  }

  def book(implicit request:Request[_]) =
  // if you want to keep separate notes in this reactor, set the prop below to something
    Book(Website.apply(request).filter(_.prop("separateNotes").isDefined).map(_.reactor).getOrElse("notes"),
      "main",
      new TagQuery(request.cookies.get("pinTags").map(_.value).mkString)
    )

  /** for active user */
  def saveFAU(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse
      Ok(views.html.notes.notesnouser(razRequest))
  }

  def save = saveFAU { implicit au => implicit errCollector => implicit request =>
      lform.bindFromRequest.fold(
      formWithErrors => {
        NOK ("", autags, Seq("err" -> "[Errors...]"), false) badRequest { implicit stok =>
          views.html.notes.notesmain(formWithErrors)
        }
      },
      {
        case (next, name, ids, ver, content, tags) =>
          import Visibility._

          val id = if (ids.length > 0) new ObjectId(ids) else new ObjectId()
          val wid = WID(CAT, (if(name.trim.length == 0) id.toString else name) )

          var msg = ("msg" -> "[Saved in locker]")

          // delete here no matter what -
          ROne[AutosavedNote]("uid"->au._id, "nid"->id) foreach (_.delete(tx.local("notes.autosave.clear", au.userName)))
          //        RMany[AutosavedNote]("uid" -> au._id) foreach (_.delete)

          def alltags (more:String) = (tags + "," + more).split("[, ]").map(_.trim.toLowerCase).filter(!_.isEmpty).distinct.toSeq

          def preprocess(iwe:WikiEntry, isNew:Boolean):WikiEntry = {
            var we = iwe
            val wep = we.preprocess(Some(au))
            var moreNotesTags = ""

            // apply content tags
            if(wep.props.contains(SHARED)) moreNotesTags = moreNotesTags + ","+SHARED
            //todo if i do this then two users can't have a note wiht teh same name
            //            if(wep.tags(NAME) != we.name) we = we.copy(name=wep.tags(NAME))

            val atags = alltags(moreNotesTags)
            if (we.tags != atags)
              we = we.withTags(atags, au._id)

            if(we.tags.contains(ENC))
              we = we.copy(content=we.content.enc)

            we
          }

          Notes.wiki.find(id) match {
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
                upd <- Wikie.before(newVer, WikiAudit.UPD_CONTENT) orErr { msg = ("err" -> "[Not allowed...]"); "Not allowed" }
              ) {
                var we = preprocess(newVer, true)

                razie.db.tx("notes.Save", au.userName) { implicit txn =>
                  ROne[AutosavedNote]("nid"->id) foreach (_.delete)
                  we.update(we)
                  Wikie.after(we, WikiAudit.UPD_CONTENT, Some(au))
                  //            Emailer.laterSession { implicit mailSession =>
                  //              au.quota.incUpdates
                  //              if (shouldPublish) notifyFollowersCreate(we, au)
                  //              au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))
                  //            }
                }
                Services ! WikiAudit("EDIT_NOTE", w.wid.wpath, Some(au._id))
                process(we)
              }
            }

            case None => {
              if (content.trim.isEmpty)
                msg = ("err" -> "[Empty note...]")
              else {
                val realm = book.realm
                var we = WikiEntry(CAT, wid.name, "", "md", content, au._id, Seq(), realm, 1, wid.parent)
                we = we.copy(_id = id)
                we = preprocess(we, false)

                razie.db.tx("notes.create", au.userName) { implicit txn =>

                  we = we.cloneProps(we.props ++ Map("owner" -> au.id), au._id)
                  //cleanAuth()

                  // visibility?
                  //if (vis != PUBLIC)
                  //                    we = we.cloneProps(we.props ++ Map("visibility" -> vis), au._id)
                  we = we.cloneProps(we.props ++ Map("visibility" -> PRIVATE), au._id)
                  we = we.cloneProps(we.props ++ Map("wvis" -> PRIVATE), au._id)

                  we.create
                  Services ! WikiAudit("CREATE_NOTE", we.wid.wpath, Some(au._id))

                  process(we)
                  SendEmail.withSession(("notes")) { implicit mailSession =>
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
            OkNewNote(Seq(msg))
      })
  }

  private def process (we:WikiEntry)(implicit request:Request[_], au:User) {
    object NotesParser extends ParserCommons {
      case class State (s:String) extends Positional

      def apply(input: String) = parseAll(line, new scala.util.parsing.input.CharArrayReader(input.toArray)) match {
        case Success(value, _) => value
        // don't change this format
        case NoSuccess(msg, next) => (s"[[CANNOT PARSE]] [${next.pos.toString}] : ${msg}")
      }

      type PS = Parser[State]

      def line: PS = dpShare | pdpAct

      //todo optimize - this loads all contacts to find, flatmapped per circle, cache per user somehow
      def wecs = Notes.notesForTag(book, au._id, "contact")

      /** find contact for MY name
        *
        * contacts may have either his prefered nick name or whatever i call him/her */
      def mycontact(n:String) =
        wecs.filter(we=>name(we) == n).toList.headOption.flatMap(
          we=>ROne[NotesContact]("oId"->au._id, "email" -> we.contentProps.get("email").getOrElse("")))

      /** find contact for name */
      private def nco(s: String) =
        ROne[NotesContact]("oId"->au._id, "nick" -> s) orElse mycontact(s)

      /** find members of circle with name */
      private def cco(s: String) =
        ROne[FriendCircle]("ownerId"->au._id, "name" -> s).toList flatMap (_.members) flatMap nco

      private def name(we:WikiEntry) = {
        we.contentProps.get(NAME).filter(_ != we._id.toString).orElse(we.contentProps.get("email").map(_.replaceAll("@.*$", ""))).mkString
      }

      private val c2 = RMany[NotesContact]("oId"->au._id).map(_.nick).toList

      def dpShare: PS = positioned("""^\.shared """.r ~> rep("to:" ~ "[^ \n\r]+".r <~ opt(" ")) ^^ {
        case tos => {
          if(we.tags contains CIRCLE) {
            val circle = Circles.createOrFind(we)
            val members = tos.collect {
              case _ ~ to => {
                //todo handle when the NotesContact was not accepted or invited even, so you can have circles of friends that did
                //not join yet
                nco(to).toList
              }
            }.flatten

            // notify new members
            members.foreach {nc =>
              if(! circle.members.contains(nc.nick)) {
                nc.uId.map {uid=>
                  Inbox(uid, au._id, "Circle", Some(we._id), "Added to a circle", "", "u").create(tx.local("notes.inbox", "?"))
                }
                SendEmail.withSession(("notes")) { implicit mailSession =>
                  Emailer.circled(au.ename, nc.email, nc.nick, s"/note/id/${we._id}")
                }
              }
            }

            circle.copy(members=members.map(_.nick).toSeq).update(tx.local("notes.inbox", "?"))

          } else tos.collect {
            case _ ~ to => {
              nco(to).toList ::: cco(to) filter (_.uId.isDefined) foreach {nc=>
                if(ROne[NoteShare]("noteId"->we._id, "toId"->nc.uId.get).isEmpty) tx("notes.inbox", au.userName) {implicit txn=>
                  NoteShare(we._id, nc.uId.get, au._id).create
                  Inbox(nc.uId.get, au._id, "Share", Some(we._id), "Note shared with you", "", "u").create(tx.local("notes.inbox", "?"))
                  SendEmail.withSession(("notes")) { implicit mailSession =>
                    Emailer.noteShared(au.ename, nc.email, nc.nick, s"/note/id/${we._id}")
                  }
                }
              }
            }
          }
          State("")
        }
      })


      def posline = positioned("""[^\n\r]*""".r  ^^ {
        case x => State(x)
      })

      def dpAct: PS = positioned("""^\.a """.r ~> rep("to:" ~ "[^ \n\r]+".r) ~ """[^\n\r]*""".r ^^ {
        case tos ~ value => {
          tos.collect {
            case _ ~ to => {
              nco(to).filter(_.uId.isDefined).foreach { nc =>
                Inbox(nc.uId.get, au._id, "Action", Some(we._id), value, "", "u").create(tx.local("notes.inbox", "?"))
              }
            }
          }
          State("")
        }
      })

      def pdpAct: PS = dpAct ^^ {
        case a => {
          cout << ".............................. POS " + a.pos
          a
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
      formWithErrors => Ok(("err" -> ("[Bad content] "+formWithErrors.errors.map(_.message).mkString+formWithErrors.globalErrors.mkString)).toString),
      {
        case (_, name, ids, ver, content, tags) =>
          val nid = if (ids.length > 0) new ObjectId(ids) else new ObjectId()

          ROne[AutosavedNote]("uid"->au._id, "nid"->nid) match {
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
                  tx("notes.autosave.u", au.userName) { implicit txn =>
                    as.copy(name=name, content=content, tags=tags).update
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
                tx("notes.autosave.c", au.userName) { implicit txn =>
                  val as = AutosavedNote(au._id, name, nid, ver, content, tags)
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
        NOK ("", autags, "msg" -> s"[view]") apply {implicit stok=>
          views.html.notes.notesview(n)
        }
      } getOrElse {
        OkNewNote(Seq("err" -> "[Note not found]"))
      }
  }

  def next (implicit request : Request[AnyContent]) = {
    import razie.|>._
    request.headers.get("Referer").mkString |> {x => if(x.contains(Services.config.hostport) || x.contains("/notes"))x else ""}
  }

  def viewNoteById(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      if(ObjectId.isValid(nid)) Notes.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id || Notes.isShared(n, au._id))
          NOK ("", autags, "msg" -> s"[view]") apply {implicit stok=>
            views.html.notes.notesview (Notes.dec(au)(n))
          }
        else
          OkNewNote(Seq("err" -> "[Not your note...]"))
      } getOrElse {
        OkNewNote(Seq("err" -> "[Note not found]"))
      } else
      NotFound("Note with id not found")
  }

  def domPlay(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      import razie.|>._
      val next = request.headers.get("Referer").mkString |> {x => if(x.contains(Services.config.hostport) || x.contains("/notes")) x else ""}
      Notes.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id || Notes.isShared(n, au._id)) {
          NOK ("", autags, "msg" -> s"[view]") apply {implicit stok=>
            views.html.notes.domPlay(Notes.dec(au)(n))
          }
        } else
          OkNewNote(Seq("err" -> "[Not your note...]"))
      } getOrElse {
        OkNewNote(Seq("err" -> "[Note not found]"))
      }
  }

  /** return back to the main screen, with the message */
  private def OkNewNote(msg:Seq[(String,String)]=Seq())(implicit request:Request[AnyContent], au:User) = {
    NOK ("", autags, msg, false) apply { implicit stok =>
      views.html.notes.notesmain(NEWNOTE)
    }
  }

  /** edit a note */
  def noteById(nid: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val onid = new ObjectId(nid)

      ROne[AutosavedNote]("uid"->au._id, "nid"->onid) map {as=>
        NOK ("", autags, Seq("msg" -> "[Autosaved note found]"), false) apply { implicit stok =>
          views.html.notes.notesmain(lform.fill("", "", as.nid.toString, as.ver, as.content, as.tags))
        }
      } orElse
        Notes.notesById(onid).map(Notes.dec(au)).map { n =>
          if (n.by == au._id)
            NOK ("", autags, Seq.empty, false) apply { implicit stok =>
              views.html.notes.notesmain(lform.fill(next, n.name, n._id.toString, n.ver, n.content, n.tags.mkString(",")))
            }
          else if(Notes.isShared(n, au._id))
                NOK ("", autags, Seq("msg" -> "Shared note..."), false) apply { implicit stok =>
                  views.html.notes.notesmain(lform.fill(next, n.name, n._id.toString, n.ver, n.content, n.tags.mkString(",")), false, true)
                }
          else
            OkNewNote(Seq("err" -> "[Not your note...]"))
        } getOrElse {
        OkNewNote(Seq("err" -> "[Note not found]"))
      }
  }

  def test = Action { implicit request =>
      Ok(Website.realm)
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
      OkNewNote(Seq("err" -> "[Note not found]"))
    //      }
  }

  def alltips = FAU { implicit au =>
    implicit errCollector => implicit request =>
//      Ok(views.html.notes.notesalltips(autags, Seq("msg" -> s"[??]"), auth))
      NOK ("", autags, "msg" -> s"[??]") apply {implicit stok=>
        views.html.notes.notesalltips()
      }
  }

  // helper so NOK is colored like Ok or others
  val NOK = new {
    def apply (curTag: String, tags: model.Tags.Tags, msg: Seq[(String, String)], isSearch:Boolean)(implicit au: model.User, request: Request[_]) =
      new NotesOk(curTag, tags, msg, isSearch, au, request)
    def apply (curTag: String, tags: model.Tags.Tags, msg: (String, String), isSearch:Boolean=false)(implicit au: model.User, request: Request[_]) =
      new NotesOk(curTag, tags, Seq(msg), isSearch, au, request)
  }

  def alltags = FUH { implicit au =>
    implicit errCollector => implicit request =>
      NOK ("", autags, Seq.empty, false) apply { implicit stok =>
        views.html.notes.notesalltags()
      }
  }

  // todo optimize big time looser here
  def autags(implicit request:Request[_], au: User) =
    Notes.tagsForUser(book, au._id)

  def tag(tag: String) = FUH { implicit au => implicit errCollector => implicit request =>
    val ltag = tag.split("/").map(_.trim)
    val pin = request.cookies.get("pinTags").map(_.value)

    if(pin.exists(pin=> !tag.startsWith(pin)) && !SPECIAL_TAGS.contains(tag)) {
      // if pinned tags, just re-scope this one
      val newt = if(book.pinTags.atags.contains(tag)) "" else ("/"+tag)
      Redirect(routes.NotesLocker.tag(pin.mkString + newt))
    } else {
      //todo this loads everything...
      val res = tag match {
        case RECENT => Notes.notesForTag(book, au._id, ALL).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case NONE => Notes.notesForTag(book, au._id, NONE).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case ARCHIVE => Notes.notesForTag(book, au._id, ARCHIVE).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
        case _ => Notes.notesForNotesTags(book, au._id, ltag).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }
      }

      //todo this is stupid
      val outNotesTags = res.flatMap(_.tags).distinct.filterNot(ltag contains _)
      val counted = outNotesTags.map(t => (t, res.count(_.tags contains t))).sortBy(_._2).reverse

      // last chance filtering - any moron can come through here and the Notes may screw up
      val notes = res.filter(_.by == au._id).take(20).toList // TOOD optimize - inbox doesn't need etc

      val nok = NOK (tag, counted, "msg" -> s"[Found ${notes.size} notes]")

      tag match {
        case INBOX => {
          val inb = Inbox.find(au._id).toList

          nok {implicit stok=>
            views.html.notes.notestaginbox(inb)
          }

          //          OkNotes(tag, counted, Seq("msg" -> s"[Found ${notes.size} notes]")) {
          //            views.html.notes.notestaginbox(inb)
          //          }
        }
        case CONTACT =>
          nok apply {implicit stok=>
            views.html.notes.notestagcontact(notes, RMany[NotesContact]("oId"->au._id).toList)
          }
        case _ if(!res.isEmpty) =>
          nok apply {implicit stok=>
            views.html.notes.noteslist(notes)
          }
        case _ if(res.isEmpty) =>
          // TODO redirect
          OkNewNote(Seq("msg" -> "[No notes found]"))
      }
    }
  }

  /** the embedded version of new note creation
    *
    * @param asap will cause it to create the note on the spot, no editing required
    */
  def embed(title:String, miniTitle:String, tags:String, context:String, baseId:String, asap:Boolean=false, justCapture:Boolean=false, submit:String="") = FAU { implicit au=> implicit errCollector=> implicit request=>
    val initial = if(ObjectId.isValid(baseId)) Notes.notesById(new ObjectId(baseId)) else None;
    var content = initial.map(_.content).getOrElse("")

    initial.map(_.preprocess(Some(au)))

    if(initial.exists(_.fields.nonEmpty)) {
      content = content + "\n" + _root_.controllers.Forms.mkFormData(initial.get.wid.page.get)
    }

    NOK ("", autags, Seq.empty, false) noLayout {implicit stok=>
      views.html.notes.notes_embed(lform.fill("", "", IDOS, 0,
        content,
        tags), title, miniTitle, context, false, false, asap, justCapture, submit)
    }
  }

  /** present a selection browser starting with the given tags */
  def selectFrom(tag: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      val tagq = new TagQuery(tag)

      //todo this loads everything...
      val b = book.copy(pinTags = tagq) // disregard users' pinned tags for selecting for selecting...?
      val res =
      (if(b.public)
          Notes.notesForPublic(b, au._id)
        else
          Notes.notesForNotesTags(b, au._id, Seq.empty)
      ).toList.sortWith { (a, b) => a.updDtm isAfter b.updDtm }

      //todo this is stupid
      // counting tag occurences of tags not in tag query
      val outNotesTags = res.flatMap(_.tags).distinct.filterNot(tagq contains _)
      val counted = outNotesTags.map(t => (t, res.count(_.tags contains t))).sortBy(_._2).reverse

      // last chance filtering - any moron can come through here and the Notes may screw up
      val notes = res.filter(_.by == au._id || b.public).take(50).toList // TOOD optimize - inbox doesn't need etc

      val nok = NOK (tag, counted, "msg" -> s"[Found ${notes.size} notes]")

      // perhaps use the layout to get the selection browsing by tags
      nok noLayout {implicit stok=>
        views.html.notes.noteslist(notes, true)
      }
  }

  def invite(e: String, n:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      NOK ("", Seq.empty, "msg" -> s"[view]") apply {implicit stok=>
        views.html.notes.notesinvitenow(e, n)
      }
  }

  /** friend accepts connection */
  def accept(e: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      val other = Users.findUserById(e)
      other match {
        case Some(o) => {
          razie.db.tx("notes.connect.accept", au.userName) {implicit txn=>
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

  // format a note into html - customized to manage sfiddles
  def format(wid: WID, markup: String, icontent: String, iwe: Option[WikiEntry] = None, au:Option[model.User]) = {
    iwe.filter(x=>
      (
        (au exists (_ hasPerm Perm.codeMaster)) ||
        (au exists (_ hasPerm Perm.adminDb))
      ) &&
      icontent.lines.find(_ startsWith ".sfiddle").isDefined
    ).fold(
        (
          if(icontent.lines.find(_ startsWith ".sfiddle").isDefined)
            "[[No permission for sfiddles]]"
          else "") +
        Wikis.format(wid, markup, icontent, iwe, au)
      ) {we=>
      val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
      try {
        if(we.tags contains "js") {
          views.html.fiddle.inlineServerFiddle("js", script, None).body
        } else if(we.tags contains "scala") {
          views.html.fiddle.inlineServerFiddle("scala", script, None).body
        } else {
          "unknown language"
        }
      } catch  {
        case t : Throwable =>
          (s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>""")
      }
    }
  }

  // TODO optimize
  def search(q: String) = FUH { implicit au =>
    implicit errCollector => implicit request =>
      // TODO limit the number of searches - is this performance critical?
      val qi = q.toLowerCase
      var msg = "msg" -> "[No notes found]"

      val wikis = {
        for (
          short <- ((q.length() > 2) orErr { msg = "err" -> "[Query too short]"; "too short" }).toList;
          u <- RazMongo("weNote").find(Map("by" -> au._id)) if (
          (q.length > 1 && u.getAs[String]("name").exists(_.toLowerCase.contains(qi))) ||
            (q.length > 1 && u.getAs[String]("label").exists(_.toLowerCase.contains(qi)) ||
            (q.length() > 2 && (
                if(u.getAs[Seq[String]]("tags").exists(_ contains ENC))
                  u.as[String]("content").dec else u.as[String]("content")
                ).toLowerCase.contains(qi))
              )
          )
        ) yield  u
      }.toList

      Audit.logdb("QUERY", q, "Results: " + wikis.size)

      if (!wikis.isEmpty) {
        val res = wikis.map(WikiEntry.grated _)
        // last chance filtering - any moron can come through here and the Notes may screw up
        val notes = res.filter(_.by == au._id).take(20).toList
        NOK (q, autags, Seq("msg" -> s"[Found ${notes.size} notes]"), true) apply {implicit stok=>
          views.html.notes.noteslist(notes)
        }
      } else {
        // TODO redirect
        OkNewNote(Seq(msg))
      }
  }

  // TODO optimize
  /** called for content asist when assigning to contacts */
  def contacts = FUH { implicit au =>
    implicit errCollector => implicit request =>

      def name(we:WikiEntry) = {
        we.contentProps.get(NAME).filter(_ != we._id.toString).orElse(we.contentProps.get(EMAIL).map(_.replaceAll("@.*$", ""))).mkString
      }

      val c1 = Notes.notesForTag(book, au._id, CONTACT).map(we=>name(we)).toList
      val c2 = RMany[NotesContact]("oId"->au._id).map(_.nick).toList
      val c3 = RMany[FriendCircle]("ownerId"->au._id).map(_.name).toList

      Ok("["+(c1 ::: c2 ::: c3).map(s=>s""" "$s" """).mkString(",")+"]").as("text/json")
  }

}

object NotesTips {
  def book= Book("rk", "")

  // when configuration changes, call this to udpate mine
  WikiObservers mini {
    case x:WikiConfigChanged =>
      inw = Wikis.rk.find("Admin", "notes-tips").toList
  }

  private var inw = Wikis.rk.find("Admin", "notes-tips").toList

  def notesWiki = inw

  def tips = {
    notesWiki.flatMap(_.sections.filter(_.name startsWith "tip")).map(_.content)
  }

  def sitehtml(code: String) = {
    val m = notesWiki.flatMap(_.sections.filter(_.name == code)).map(_.content).map(x => Wikis.format(WID.empty, "md", x, None,None)).headOption.getOrElse(code)
    // todo why the heck does it put those?
    m.replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", "")
  }

  def xhtml(tags: String, au: Option[User]) = {
    au.flatMap { u =>
      val w = Notes.notesForNotesTags(book, u._id, tags.split(",").toSeq).take(1).find(x => true)
      w.map(x =>
        Wikis.format(x.wid, x.markup, x.content, None, None).replaceFirst("^\\s*<p>", "").replaceFirst("</p>\\s*$", ""))
    }.getOrElse(sitehtml(tags))
  }
}

/** dom graph controller */
object DomC extends RazController with Logging {

  object retj {
    def <<(x: List[Any]) = Ok(js.tojson(x).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  def domj(nid: String) = NotesLocker.FAU { implicit au =>
    implicit errCollector => implicit request =>
      Notes.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id || Notes.isShared(n, au._id))
          retj << WikiDomain.domFrom(n).get.tojmap
        else
          NotFound("[Not your note...]")
      } getOrElse {
        NotFound("[Note not found...]")
      }
  }

  def domcat(cat: String) = Action { implicit request =>
    retj << WG.fromCat(cat, "rk").tojmap
  }

  def domPlay(nid: String) = NotesLocker.FAU { implicit au =>
    implicit errCollector => implicit request =>
      Notes.notesById(new ObjectId(nid)).map { n =>
        if (n.by == au._id || Notes.isShared(n, au._id)) {
          NotesLocker.NOK ("", NotesLocker.autags, "msg" -> s"[view]") apply {implicit stok=>
            views.html.notes.domPlay(Notes.dec(au)(n))
          }
        } else
          NotFound("[Not your note...]")
      } getOrElse {
        NotFound("[Note not found...]")
      }
  }

}

/** captures the current state of what to display - passed to all views */
case class NotesOk(curTag: String, tags: model.Tags.Tags, msg: Seq[(String, String)], isSearch:Boolean, au: model.User, request: Request[_]) {
  var _title : String = "No Folders" // this is set by the body as it builds itself and used by the header, heh

  val realm = Website.realm(request)

  def stok = new StateOk(realm, Option(au), Option(request))

  def css = {
    val ret =
      // session settings override everything
      request.session.get("css") orElse (
        // then user
        au.css
        ) orElse (
        // or website settings
        Website(request).flatMap(_.css)
        ) getOrElse ("light")

    if(ret == null || ret.length <=0) "light" else ret
  }
  def isLight = css contains "light"

  /** set the title of this page */
  def title(s:String) = {this._title = s; ""}

  def apply(content: NotesOk => Html) = {
    NotesLocker.Ok (views.html.notes.notesLayout(content(this), curTag, tags, msg)(this))
  }

  def badRequest (content: NotesOk => Html) = {
    RkViewService.BadRequest (views.html.notes.notesLayout(content(this), curTag, tags, msg)(this))
  }

  def noLayout(content: NotesOk => Html) = {
    NotesLocker.Ok (content(this))
  }

  /** format a tag path */
  def tagPath (s:String) = {
    if(curTag=="" || isSearch || Seq("none", "recent", "all").contains(curTag)) s else (curTag+"/"+s)
  }

}


