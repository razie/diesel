package admin

import org.joda.time.DateTime
import com.novus.salat.grater
import model.RazSalatContext.ctx
import model.Mongo
import razie.Logging
import com.mongodb.casbah.Imports._
import razie.Log

case class Audit(level: String, msg: String, details: String, when: DateTime = DateTime.now) {
  def create = {
    Mongo ("Audit") += grater[Audit].asDBObject(this)
    this
  }
}

/** all audit events - some of these may end up as emails or alerts.
 *
 *  TODO should have a configurable workflow for each of these - what's the pattern?
 */
object Audit extends Logging {

  def many(m: Any*) = m.mkString(" ")

  def security(what: String, details:Any*) =  logdb ("SECURITY_ISSUE", what, details)
  
  def logdb(what: String, details:Any*) = {
    Audit("a", what, details.mkString(",")).create
    val s = what + " " + details.mkString(",")
    razie.Log.audit(s)
    s
  }

  def clearAudit(id: String, userId: String) = {
    val a = Mongo ("Audit").findOne(Map("_id" -> new ObjectId(id))).map(grater[Audit].asObject(_))

    a.map { ae =>
      Mongo ("AuditCleared") += grater[Audit].asDBObject(ae)
      Mongo ("AuditCleared").m.update(Map("_id" -> new ObjectId(id)), Map("clearedBy" -> userId, "clearedDtm" -> DateTime.now))
      Mongo ("Audit").m.remove(Map("_id" -> new ObjectId(id)))
    }
  }
  
  def create[T](entity: T): T = { audit(logdb(ENTITY_CREATE, entity.toString)); entity }
  def createnoaudit[T](entity: T): T = { audit(ENTITY_CREATE + " " + entity.toString); entity }
  def update[T](entity: T): T = { audit(logdb(ENTITY_UPDATE, entity.toString)); entity }
  def updatenoaudit[T](entity: T): T = { audit(ENTITY_UPDATE+" "+ entity.toString); entity }
  def delete[T](entity: T): T = { audit(logdb(ENTITY_DELETE, entity.toString)); entity }
  final val ENTITY_CREATE = "ENTITY_CREATE"
  final val ENTITY_UPDATE = "ENTITY_UPDATE"
  final val ENTITY_DELETE = "ENTITY_DELETE"

  def missingPage(url: String) { error(many("WIKI_MISSING_PAGE", url)) }

  def regdemail(email: String) { audit(logdb(REGD_EMAIL, email)) }
  final val REGD_EMAIL = "INFO_REGD_EMAIL"

  def wrongLogin(email: String, pwd: String) { audit(logdb(ERR_NO_SUCH_USER, many(email, pwd))) }
  final val ERR_NO_SUCH_USER = "ERR_NO_SUCH_USER"

  def wikiFailedEdit(user:String, cat:String, name:String) { audit(logdb(ERR_FAILEDWIKIEDIT, many(user, cat, name))) }
  final val ERR_FAILEDWIKIEDIT = "ERR_FAILEDWIKIEDIT"

  def auth(details:String) { audit(logdb(ERR_AUTHREQUIRED, many(details))) }
  final val ERR_AUTHREQUIRED = "ERR_AUTHREQUIRED"

  def unauthorized(details:String) { audit(logdb(ERR_UNAUTHORIZED, many(details))) }
  final val ERR_UNAUTHORIZED = "ERR_UNAUTHORIZED"

}
