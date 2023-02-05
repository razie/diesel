/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.db

import org.bson.types.ObjectId
import com.mongodb.casbah.Imports._
import salat._
import salat.annotations._
import org.joda.time.DateTime
import razie.audit.Auditor
import razie.db.RazSalatContext._

/** base class for entities - provides most common DB ops automatically
  *
  * case class T(a,b) extends REntity[T]
  * new T (a,b).create
  *
  */
class REntity[T <: { def _id: ObjectId }](implicit m: Manifest[T]) { this: T =>
  // had to copy this
  implicit def toroa(id: ObjectId) = new RMongo.as(id)

  def toJsonNice = razie.js.tojsons(razie.js.parse(toJson), 1)
  def toJson = grater[T].asDBObject(this).toString
  def grated = grater[T].asDBObject(this)

  def create(implicit txn: Txn) = RCreate[T](this)
  def update(implicit txn: Txn) = RUpdate[T](this)
  def delete(implicit txn: Txn) = RDelete[T](this)
  def createNoAudit(implicit txn: Txn) = RCreate.noAudit[T](this)
  def updateNoAudit(implicit txn: Txn) = RUpdate.noAudit[T](this)
  def deleteNoAudit(implicit txn: Txn) = RDelete.noAudit[T](this)

  def trash(by:String)(implicit txn: Txn) = {
    WikiTrash(RMongo.tbl(m), grated, by, txn.id).create
    RDelete.noAudit[T](this)
  }
}

/** entities that don't need auditing
  */
class REntityNoAudit[T <: { def _id: ObjectId }](implicit m: Manifest[T]) extends REntity [T] { this: T =>
  override def create(implicit txn: Txn) = createNoAudit
  override def update(implicit txn: Txn) = updateNoAudit
  override def delete(implicit txn: Txn) = deleteNoAudit
}

/** wiki entries are trashed when deleted - a copy of each older version when udpated or deleted
  *
  * todo some ways to recover them
  * */
@RTable
case class WikiTrash(table:String, entry: DBObject, by:String, txnId:String, date:DateTime=DateTime.now, _id: ObjectId = new ObjectId()) {
  def create (implicit txn:Txn) = RCreate.noAudit[WikiTrash](this)
}

