package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log
import controllers.UserStuff
import scala.collection.mutable.ListBuffer

/** a series of comments on something - like a forum topic */
case class CommentStream(
  topic: ObjectId, // for wiki, this is the WID
  what: String = "Wiki", // "wiki" vs?
  crDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) {

  def id = _id.toString

  def create = {
    Mongo ("CommentStream") += grater[CommentStream].asDBObject(Audit.create(this))
    this
  }

  lazy val comments = new ListBuffer[Comment]() ++
    Mongo("Comment").find(Map("streamId" -> _id)).sort(Map("crDtm" -> 1)).map(grater[Comment].asObject(_)).toList

  def addComment(user: User, content: String, oid:String, parentId: Option[ObjectId] = None) = {
    val c = Comment (_id, user._id, parentId, content, DateTime.now(), DateTime.now(), new ObjectId(oid))
    comments += c
    c.create
  }

  def isDuplo (oid:String) = comments.exists(_.id equals oid)
}

/** a series of comments on something - like a forum topic */
case class Comment(
  streamId: ObjectId, // for wiki, this is the WID
  userId: ObjectId, // user that made the comment
  parentId: Option[ObjectId], // in reply to...
  content: String,
  crDtm: DateTime = DateTime.now(),
  updDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) {

  def id = _id.toString

  def create = Mongo ("Comment") += grater[Comment].asDBObject(Audit.create(this))

  def update(newContent: String) = {
    //    Mongo ("UserOld") += grater[User].asDBObject(Audit.create(this))
    val u = new Comment(streamId, userId, parentId, newContent, crDtm, DateTime.now, _id)
    Mongo("Comment").m.update(Map("_id" -> _id), grater[Comment].asDBObject(Audit.update(u)))
  }
}

/** user factory and utils */
object Comments {
  def findForWiki(id:ObjectId) = Mongo("CommentStream").findOne(Map("what" -> "Wiki", "topic" -> id)) map (grater[CommentStream].asObject(_))
  def findById(id: String) = Mongo("CommentStream").findOne(Map("_id" -> new ObjectId(id))) map (grater[CommentStream].asObject(_))
}
