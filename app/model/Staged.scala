package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import db.RTable
import db.RMany
import db.RCreate
import db.RDelete

/** staged stuff */
@RTable
case class Stage (
  what: String,
  content: DBObject,
  by: String,
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  def create = RCreate.noAudit[Stage] (this)
  def delete = RDelete[Stage]("_id"->_id)
}

object Staged {
  def find(what: String) = RMany[Stage]("what" -> what)
}
