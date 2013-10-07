package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import scala.util.matching.Regex.Match
import scala.util.matching.Regex
import razie.Log
import scala.util.parsing.combinator.RegexParsers
import razie.base.data.TripleIdx
import admin.Notif
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
