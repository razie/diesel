/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import com.mongodb.casbah.Imports._
import com.novus.salat.grater
import db.RazSalatContext.ctx
import db._
import org.joda.time.DateTime
import razie.Logging

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
  override def create (implicit txn: Txn = tx.auto) = RCreate.noAudit[Audit](this)
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
  def !?(a: Any) { Services.alli !? a }

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
      RazMongo("AuditCleared") += o
      RazMongo("Audit").remove(Map("_id" -> new ObjectId(id)))
    }
  }
}
