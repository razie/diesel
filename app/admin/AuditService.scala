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
import razie.Logging
import com.mongodb.casbah.Imports._
import razie.Log
import db.RTable
import db.ROne
import db.RCreate
import db.REntity

/**
 * many operations are audited - this is strait in the log files, as well as the logdb() audit facility.
 *
 * in my RK, I simply log them in a database, for review - you could do the same or ignore them
 *
 * there's a Audit default implementation here somehwer
 */
trait AuditService extends Logging {

  /** log a db opreation - this method you need to provide */
  def logdb(what: String, details: Any*): String

  /** log a db operation with a link to the thing being audited */
  def logdbWithLink(what: String, link: String, details: Any*): String

  // -------------- utilities
  
  def many(m: Any*) = m.mkString(" ")

  def security(what: String, details: Any*) = logdb("SECURITY_ISSUE", what, details)

  def create[T](entity: T): T = { audit(logdb(ENTITY_CREATE, entity.toString)); entity }
  def createnoaudit[T](entity: T): T = { audit(ENTITY_CREATE + " " + entity.toString); entity }
  def update[T](entity: T): T = { audit(logdb(ENTITY_UPDATE, entity.toString)); entity }
  def updatenoaudit[T](entity: T): T = { audit(ENTITY_UPDATE + " " + entity.toString); entity }
  def delete[T](entity: T): T = { audit(logdb(ENTITY_DELETE, entity.toString)); entity }

  final val ENTITY_CREATE = "ENTITY_CREATE"
  final val ENTITY_UPDATE = "ENTITY_UPDATE"
  final val ENTITY_DELETE = "ENTITY_DELETE"

  def missingPage(url: String) { error(many("WIKI_MISSING_PAGE", url)) }

  def regdemail(email: String) { audit(logdb(REGD_EMAIL, email)) }
  final val REGD_EMAIL = "INFO_REGD_EMAIL"

  def wrongLogin(email: String, pwd: String) { audit(logdb(ERR_NO_SUCH_USER, many(email, pwd))) }
  final val ERR_NO_SUCH_USER = "ERR_NO_SUCH_USER"

  def wikiFailedEdit(user: String, cat: String, name: String) { audit(logdb(ERR_FAILEDWIKIEDIT, many(user, cat, name))) }
  final val ERR_FAILEDWIKIEDIT = "ERR_FAILEDWIKIEDIT"

  def auth(details: String) { audit(logdb(ERR_AUTHREQUIRED, many(details))) }
  final val ERR_AUTHREQUIRED = "ERR_AUTHREQUIRED"

  def unauthorized(details: String) { audit(logdb(ERR_UNAUTHORIZED, many(details))) }
  final val ERR_UNAUTHORIZED = "ERR_UNAUTHORIZED"
}

/** sample stub audit service */
object NoAuditService extends AuditService {
  /** log a db opreation - this method you need to provide */
  def logdb(what: String, details: Any*): String = {
    val d = details.mkString(",")
    val s = what + " " + d
    razie.Log.audit(s)
    s
  }

  /** log a db operation */
  def logdbWithLink(what: String, link: String, details: Any*) = {
    val d = details.mkString(",")
    val s = what + " " + d
    razie.Log.audit(s)
    s
  }

}
