/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import org.joda.time.DateTime
import com.novus.salat.grater
import db.RazSalatContext.ctx
import db.Mongo
import org.bson.types.ObjectId
import db.RCreate
import akka.actor.Actor._
import play.libs.Akka
import db.ROne
import razie.Logging
import db.RTable
import db.REntity
import org.joda.time.DateTime
import com.novus.salat.grater
import db.RazSalatContext.ctx
import db.Mongo
import razie.Logging
import com.mongodb.casbah.Imports._
import db.RTable
import db.ROne
import db.RCreate
import db.REntity
import akka.actor.actorRef2Scala
import razie.cout

/**
 *  all audit events - stored in db table. Some of these may end up as emails or alerts.
 *
 *  there are two tables: Audit for events to be reviewed and AuditCleared for reviewed events. You need your own purging
 *  of the AuditCleared table.
 *
 *  TODO should have a configurable workflow for each of these - what's the pattern?
 */
@RTable
case class Audit(
  level: String,
  msg: String,
  details: String,
  link: Option[String] = None,
  when: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId) extends REntity[Audit] {
  override def create = RCreate.noAudit[Audit](this)
}

/**
 * just a proxy to 
 */
object Audit extends AuditService with Logging {

  /**
   * process some audit objects persisence asynchronously...
   *
   *   TODO I really ended up using this as a generic persistence deferring service...
   */
  def !(a: Any) { Services.alli ! a }

  def logdb(what: String, details: Any*) = 
    Services.audit.logdb(what, details:_*)

  /** log a db operation */
  def logdbWithLink(what: String, link: String, details: Any*) = 
    Services.audit.logdbWithLink(what, link, details:_*)
}

/**
 * razie's default Audit implementation - stores them events in a Mongo table. Use this as an example to write your own auditing service.
 *
 *  Upon review, move them to the cleared/history table and purge them sometimes
 */
object RazAuditService extends AuditService with Logging {

  /** log a db operation */
  def logdb(what: String, details: Any*) = {
    val d = details.mkString(",")
    Services.alli ! Audit("a", what, d)
    val s = what + " " + d
    razie.Log.audit(s)
    s
  }

  /** log a db operation */
  def logdbWithLink(what: String, link: String, details: Any*) = {
    val d = details.mkString(",")
    Services.alli ! Audit("a", what, d, Some(link))
    val s = what + " " + d
    razie.Log.audit(s)
    s
  }

  /** move from review to archive. archive is purged separately. */
  def clearAudit(id: String, userId: String) = {
    ROne[Audit](new ObjectId(id)) map { ae =>
      val o = grater[Audit].asDBObject(ae)
      o.putAll(Map("clearedBy" -> userId, "clearedDtm" -> DateTime.now))
      Mongo("AuditCleared") += o
      Mongo("Audit").m.remove(Map("_id" -> new ObjectId(id)))
    }
  }
}
