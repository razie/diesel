package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import scala.util.matching.Regex.Match
import scala.util.matching.Regex
import razie.Log
import scala.util.parsing.combinator.RegexParsers
import razie.base.data.TripleIdx
import admin.Notif

/** a simple wiki-style entry: language (markdown, mediawiki wikidot etc) and the actual source */
case class Stage (
  what: String,
  content: DBObject,
  by: String,
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  def create = {
    Staged.table += grater[Stage].asDBObject(Audit.createnoaudit(this))
  }

  def delete = {
    Staged.table.m.remove (Map("_id"->_id))
  }

}

/** wiki factory and utils */
object Staged {
  def table = Mongo("Stage")

  def find(what: String) =
    table.m.find(Map("what" -> what)) map (grater[Stage].asObject(_))
}
