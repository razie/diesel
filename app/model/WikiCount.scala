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
case class WikiCount (
  pid: ObjectId, 
  count: Long = 1
  ) {
  def inc = {
    WikiCount.findOne (pid) map (p=> WikiCount.table.m.update (Map("pid" -> pid), p.copy(count=p.count+1).grated)) orElse {
      WikiCount.table += WikiCount.this.grated
      None
    }
  }
  def grated = grater[WikiCount].asDBObject(WikiCount.this)
}

/** wiki factory and utils */
object WikiCount {
  def table = Mongo("WikiCount")

  def findOne(pid: ObjectId) =
    table.m.findOne(Map("pid" -> pid)) map (grater[WikiCount].asObject(_))
}
