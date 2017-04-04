package mod.notes.controllers

import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import mod.notes.controllers
import model.{Perm, User, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc._
import razie.db.RazSalatContext.ctx
import razie.db._
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.parser.ParserCommons
import razie.js
import razie.wiki.{Enc, Services}
import razie.{Logging, cout}

import scala.util.parsing.input.Positional

/**
 * Created by raz on 2015-05-13.
 */
object Inbox {
  def count(uid: ObjectId) = {
    RCount[Inbox]("toId"->uid, "state"->"u")
  }
  def find(uid: ObjectId) =
    RMany[Inbox]("toId"->uid) filter (_.state != "d")
}

/** a contact connection between two users */
@RTable
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

/** share a note with another user */
@RTable
case class NoteShare (
                       noteId: ObjectId, // the note it's about
                       toId: ObjectId,
                       ownerId: ObjectId,
                       how: String="", // "" - read/write, "ro" - readonly
                       crDtm:DateTime = DateTime.now,
                       _id: ObjectId = new ObjectId()) extends REntity[NoteShare] {
  def note = Notes.wiki.find(noteId)
}

/** inbox */
@RTable
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


// -------------- circles

/** a circle of friends / team etc */
@RTable
case class FriendCircle (
                          name: String,
                          ownerId: ObjectId, // user
                          members:Seq[String] = Seq(), // users
                          role: String="", // work, ski, enduro etc
                          crDtm:DateTime = DateTime.now,
                          _id: ObjectId = new ObjectId()) extends REntity[FriendCircle] {

  def owner = ownerId.as[User]
}

/** notes shared to a circle */
@RTable
case class CircleShare (
                         noteId: ObjectId, // the note it's about
                         to: ObjectId,
                         ownerId: ObjectId,
                         how: String="", // "" - read/write, "ro" - readonly
                         crDtm:DateTime = DateTime.now,
                         _id: ObjectId = new ObjectId()) extends REntity[CircleShare] {

  def owner = ownerId.as[User]
}

object Circles {
  import NotesTags._

  def get (name:String)(implicit au:User) = ROne[FriendCircle]("ownerId"->au._id,"name"->name)
  def createOrFind (we:WikiEntry)(implicit au:User) = get(we.contentProps(NAME)) getOrElse {
    val ret = FriendCircle(we.contentProps(NAME), au._id)
    ret.create(tx.auto)
    ret
  }
}


