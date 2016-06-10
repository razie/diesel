package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import razie.db.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log
import controllers.UserStuff
import controllers.Maps
import controllers.RazController
import controllers.Emailer
import razie.db._
import scala.annotation.StaticAnnotation
import razie.wiki.model.WID

/** registration set for a family */
@RTable
case class Autosave(
  name: String,
  userId: ObjectId,
  contents: Map[String,String],
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Autosave] {

  override def create(implicit txn: Txn) = RCreate.noAudit[Autosave](this)
  override def update (implicit txn: Txn) = RUpdate.noAudit(Map("_id" -> _id), this)
  override def delete(implicit txn: Txn) = RDelete.noAudit[Autosave](this)
}

object Autosave {

  def find(name:String, userId: ObjectId) = ROne[Autosave]("name" -> name, "userId" -> userId).map(_.contents)
  def OR(name:String, userId: ObjectId, c:Map[String,String]) = find(name, userId).getOrElse(c)
  def set(name:String, userId: ObjectId, c:Map[String,String]) =
    ROne[Autosave]("name" -> name, "userId" -> userId).map(_.copy(contents=c).update).getOrElse(Autosave(name, userId, c).create)
}
