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
import admin.Config
import java.net.URLDecoder
import admin.Base64Codec

object Base64 {
  def --> (s:String) = Base64Codec.encode(s)
  def <-- (s:String) = Base64Codec.decode(s)
}

/** utility to encrypt/decrypt stuff*/
object Enc {
  def apply(orig: String) = new CipherCrypt().encrypt(orig)
  def unapply(encoded: String): Option[String] = Some(new CipherCrypt().decrypt(encoded))

  def toUrl(orig: String) = URLEncoder.encode(orig, "UTF8")
  def fromUrl(orig: String) = URLDecoder.decode(orig, "UTF8")
}

object EncUrl {
  def apply(orig: String) = URLEncoder.encode(Enc(orig), "UTF8")
  def unapply(encoded: String): Option[String] = Some(new CipherCrypt().decrypt(encoded))
}

/** secured link to be emailed for instance 
 * 
 * Note that this is persisted only if the secUrl is requested
 */
case class DoSec(
  link: String,
  expiry: DateTime = DateTime.now.plusHours(1),
  _id: ObjectId = new ObjectId()) {

  def id = _id.toString

  private def create = Mongo ("DoSec") += grater[DoSec].asDBObject(Audit.create(this))
  def delete = { Audit.delete(this); Mongo ("DoSec").m.remove(Map("_id" -> _id)) }

  val secUrl = {
    val res = create.getError
    razie.Log.log("DB_RESULT (DoSec.create): " + res)

    "http://" + Config.hostport + "/doe/sec/" + id
  }
}

object DoSec {
  def find(id: String) = {
   // play 20 workaround - remove this in play 2.1
    val iid = id.replaceAll(" ", "+")
    
    Mongo("DoSec").findOne(Map("_id" -> new ObjectId(iid))) map (grater[DoSec].asObject(_))
  }
}
