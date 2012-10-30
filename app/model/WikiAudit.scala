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
case class WikiAudit (
  event: String,
  wpath: String,
  userId: Option[ObjectId],
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()
  ) {

  def create = {
    WikiAudit.table += grater[WikiAudit].asDBObject(Audit.createnoaudit(this))
  }
}

/** wiki factory and utils */
object WikiAudit {
  def table = Mongo("WikiAudit")

  def find(event: String) =
    table.m.find(Map("event" -> event)) map (grater[WikiAudit].asObject(_))
  def find(event: String, wpath:String) =
    table.m.find(Map("event" -> event, "wpath"->wpath)) map (grater[WikiAudit].asObject(_))
}
