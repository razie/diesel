package model

import scala.collection.mutable.ListBuffer

import org.joda.time.DateTime

import com.mongodb.casbah.Imports.ObjectId
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater

import admin.Audit
import model.RazSalatContext.ctx

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

  def addComment(user: User, content: String, oid:String, link:Option[String], kind:Option[String], parentId: Option[ObjectId] = None) = {
    val c = Comment (_id, user._id, parentId, content, link, kind, DateTime.now(), DateTime.now(), new ObjectId(oid))
    comments += c
    c create user
  }

  def isDuplo (oid:String) = comments.exists(_.id equals oid)
}

/** a series of comments on something - like a forum topic */
case class Comment(
  streamId: ObjectId, // for wiki, this is the WID
  userId: ObjectId, // user that made the comment
  parentId: Option[ObjectId], // in reply to...
  content: String,
  link: Option[String],
  kind: Option[String], // text/video/photo
  crDtm: DateTime = DateTime.now(),
  updDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) {

  def id = _id.toString

  def create (user:User) = {
    Audit.logdb(Comments.AUDIT_COMMENT_CREATED, "BY " + user.userName + " " + userId + " parent:" + parentId, "\nCONTENT:\n" + this)
    Mongo ("Comment") += grater[Comment].asDBObject(this)
  }

  // TODO keep track of older versions and who modifies them
  def update(newContent: String, newLink:Option[String], user:User) = {
    val u = new Comment(streamId, userId, parentId, newContent, newLink, this.kind, crDtm, DateTime.now, _id)
    Audit.logdb(Comments.AUDIT_COMMENT_UPDATED, "BY " + user.userName + " " + userId + " parent:" + parentId, "\nCONTENT:\n" + u)
    Mongo("Comment").m.update(Map("_id" -> _id), grater[Comment].asDBObject(u))
  }
}

/** user factory and utils */
object Comments {
  def findForWiki(id:ObjectId) = Mongo("CommentStream").findOne(Map("what" -> "Wiki", "topic" -> id)) map (grater[CommentStream].asObject(_))
  def findById(id: String) = Mongo("CommentStream").findOne(Map("_id" -> new ObjectId(id))) map (grater[CommentStream].asObject(_))
  def findCommentById(id: String) = Mongo("Comment").findOne(Map("_id" -> new ObjectId(id))) map (grater[Comment].asObject(_))

  final val AUDIT_COMMENT_CREATED = "COMMENT_CREATED "
  final val AUDIT_COMMENT_UPDATED = "COMMENT_UPDATED "
}
