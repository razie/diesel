/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package db

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import com.mongodb.util.JSON
import razie.Log
import admin.Services

/**
 * Razie's simple mongo entity persistence framework 
 *
 * just playing with this to simplify my code
 */

/** wrap simple mongo ops - less code to write elsewhere */
object RMongo {
  def tbl(m: Manifest[_]): String =
    Option(m.runtimeClass.getAnnotation(classOf[db.RTable])).map(_.value()) match {
      case Some(s) => if (s.length > 0) s else m.runtimeClass.getSimpleName()
      case None => m.runtimeClass.getSimpleName()
    }

  def findById[A <: AnyRef](id: ObjectId)(implicit m: Manifest[A]): Option[A] =
    Mongo(tbl(m)).findOne(Map("_id" -> id)) map (grater[A].asObject(_))

  def findOne[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).findOne(t.toMap) map (grater[A].asObject(_))

  def find[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).find(t.toMap).map(grater[A].asObject(_))

  //def grater[Y <: AnyRef](implicit ctx: Context, m: Manifest[Y]): Grater[Y] = ctx.lookup(m.erasure.getName).asInstanceOf[Grater[Y]]

  /** adds the method as[] to an object id */
  implicit class as(id: ObjectId) {
    def as[A <: AnyRef](implicit m: Manifest[A]) = ROne[A](id)
  }
}

//object R {
//  implicit class as(id: ObjectId) {
//    def as[A <: AnyRef](implicit m: Manifest[A]) = ROne[A](id)
//  }
//}

/* select/find one mongo item */
object ROne {
  import RMongo._

  def apply[A <: AnyRef](id: ObjectId)(implicit m: Manifest[A]): Option[A] =
    raw(id) map (grater[A].asObject(_))

  def apply[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    raw(t: _*) map (grater[A].asObject(_))

  def raw[A <: AnyRef](id: ObjectId)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).findOne(Map("_id" -> id))

  def raw[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).findOne(t.toMap)
}

/* select/find many mongo items */
object RMany {
  import RMongo._

  def apply[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).find(t.toMap).map(grater[A].asObject(_))
}

/** create a record */
object RCreate {
  import RMongo._

  def apply[A <: AnyRef](t: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)) += grater[A].asDBObject(Services.audit.create(t))

  def noAudit[A <: AnyRef](t: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)) += grater[A].asDBObject(Services.audit.createnoaudit(t))
}

/** update a record */
object RUpdate {
  import RMongo._

  /** update entity matched by map props */
  def apply[A <: AnyRef](t: Map[String, Any], a: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.update(t, grater[A].asDBObject(Services.audit.update(a)))

  /** update entity matched by map props */
  def noAudit[A <: AnyRef](t: Map[String, Any], a: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.update(t, grater[A].asDBObject(a))

  /** update matched on _id */
  def apply[A <: { def _id: ObjectId }](a: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.update(Map("_id" -> a._id), grater[A].asDBObject(Services.audit.update(a)))
}

/** delete a record */
object RDelete {
  import RMongo._

  def apply[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.remove(t.toMap)

  def apply[A <: { def _id: ObjectId }](x: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.remove(Map("_id" -> Services.audit.delete(x)._id))

  def noAudit[A <: { def _id: ObjectId }](x: A)(implicit m: Manifest[A]) =
    Mongo(tbl(m)).m.remove(Map("_id" -> x._id))
}

/** base class for entities - provides most common DB ops automatically */
class REntity[T <: { def _id: ObjectId }](implicit m: Manifest[T]) { this: T =>
  // had to copy this
  implicit def toroa(id: ObjectId) = new RMongo.as(id)

  def toJson = grater[T].asDBObject(this).toString
  def create = RCreate[T](this)
  def delete = RDelete[T](this)
  def update = RUpdate[T](this)
}
