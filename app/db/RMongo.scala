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
import admin.Audit
import razie.clog

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
    RazMongo(tbl(m)).findOne(Map("_id" -> id)) map (grater[A].asObject(_))

  def findOne[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    RazMongo(tbl(m)).findOne(t.toMap) map (grater[A].asObject(_))

  def find[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    RazMongo(tbl(m)).find(t.toMap).map(grater[A].asObject(_))

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
    RazMongo(tbl(m)).findOne(Map("_id" -> id))

  def raw[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    RazMongo(tbl(m)).findOne(t.toMap)
}

/* select/find many mongo items */
object RMany {
  import RMongo._

  def apply[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    RazMongo(tbl(m)).find(t.toMap).map(grater[A].asObject(_))

  def raw[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A]) =
    RazMongo(tbl(m)).find(t.toMap)
}

/** create a record */
object RCreate {
  import RMongo._

  def apply[A <: AnyRef](t: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)) += grater[A].asDBObject(Services.audit.create(t))

  def noAudit[A <: AnyRef](t: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)) += grater[A].asDBObject(Services.audit.createnoaudit(t))
}

/** update a record */
object RUpdate {
  import RMongo._

  /** update entity matched by map props */
  def apply[A <: AnyRef](t: Map[String, Any], a: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).update(t, grater[A].asDBObject(Services.audit.update(a)))

  /** update entity matched by map props */
  def noAudit[A <: AnyRef](t: Map[String, Any], a: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).update(t, grater[A].asDBObject(a))

  /** update entity matched by map props */
  def noAudit[A <: AnyRef](tblName:String, t: Map[String, Any], a: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tblName).update(t, grater[A].asDBObject(a))

  /** update matched on _id */
  def apply[A <: { def _id: ObjectId }](a: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).update(Map("_id" -> a._id), grater[A].asDBObject(Services.audit.update(a)))
}

/** delete a record */
object RDelete {
  import RMongo._

  def apply[A <: AnyRef](t: (String, Any)*)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).remove(t.toMap)

  def apply[A <: { def _id: ObjectId }](x: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).remove(Map("_id" -> Services.audit.delete(x)._id))

  def apply[A <: { def _id: ObjectId }] (tblName:String, x: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tblName).remove(Map("_id" -> Services.audit.delete(x)._id))

  def apply (tblName:String, x: Map[String, Any]) (implicit txn: Txn = tx.auto) =
    RazMongo(tblName).remove(Services.audit.delete(x))

  def noAudit[A <: { def _id: ObjectId }](x: A)(implicit m: Manifest[A], txn: Txn = tx.auto) =
    RazMongo(tbl(m)).remove(Map("_id" -> x._id))
}

/** base class for entities - provides most common DB ops automatically */
class REntity[T <: { def _id: ObjectId }](implicit m: Manifest[T]) { this: T =>
  // had to copy this
  implicit def toroa(id: ObjectId) = new RMongo.as(id)

  def toJson = grater[T].asDBObject(this).toString
  def create(implicit txn: Txn = tx.auto) = RCreate[T](this)
  def delete(implicit txn: Txn = tx.auto) = RDelete[T](this)
  def update(implicit txn: Txn = tx.auto) = RUpdate[T](this)
}

// TODO complete transactions
class Txn (val name: String) {
  clog << "DB.TX.START "+name
  
  def add(s: String, ss: String) {}
  
//  def map[B] (f:A=>B) : Txn[B]
//  def flatMap[B] (f:A=>Txn[B]) : Txn[B]
  
  def commit {
    clog << "DB.TX.COMMIT "+name
  }
}

object tx {
  def apply[A](f: Txn => A) : A = { apply("?")(f) }

  def apply[A] (name: String) (f: Txn => A) : A = {
    val txn = new Txn(name)
    val res : A = try {
      f(txn)
    } catch {
      case t: Throwable => {
        // can't really log in db while handling a db exception, can i?
//        Audit.logdb("ERR_TXN_FAILED", txn.name)
        clog << ("ERR_TXN_FAILED", txn.name)
        throw new RuntimeException(t)
      }
    }
    txn.commit
    res
  }

  def auto = local ("auto")
  def local (name:String="") : Txn = new Txn("local"+name)
}

/** wrap all db operation in this to get logging, timing and stats */
object dbop {
  def apply[A](f: => A): A = this.apply("?")(f)

  def apply[A](name: String)(f: => A): A = {
    val ba = new RBeforeAft(name)
    clog << s"dbop.BEFORE for ${ba.name}"
    val res = f
    val t2 = System.currentTimeMillis
    clog << s"dbop.AFTER ${t2 - ba.t1} millis for ${ba.name}"
    res
  }
  
  private class RBeforeAft(val name: String, val t1: Long = System.currentTimeMillis)
}

