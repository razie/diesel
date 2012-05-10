package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import model.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log

/** utility to encrypt/decrypt stuff*/
object Enc {
  def apply(orig: String) = new CipherCrypt().encrypt(orig)
  def unapply(encoded: String): Option[String] = Some(new CipherCrypt().decrypt(encoded))

  def toUrl(orig: String) = URLEncoder.encode(orig, "UTF8")
}

object EncUrl {
  def apply(orig: String) = URLEncoder.encode(Enc(orig), "UTF8")
  def unapply(encoded: String): Option[String] = Some(new CipherCrypt().decrypt(encoded))
}


case class DoSec(
    link: String, 
    expiry: DateTime = DateTime.now.plusHours(1), 
    _id:ObjectId=new ObjectId()) {
  
  def id = _id.toString
  
  def create = Mongo ("DoSec") += grater[DoSec].asDBObject(Audit.create(this))
  def delete = Mongo ("DoSec").m.remove(Map("_id" -> _id))
}

object DoSec {
  def find(id:String) = Mongo("DoSec").findOne(Map("_id" -> new ObjectId(id))) map (grater[DoSec].asObject(_))
}