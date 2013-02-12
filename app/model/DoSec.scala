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
import java.security.MessageDigest

object Sec {
  //================ encription
  case class EncryptedS(s: String) {
    def enc = (new admin.CipherCrypt).encrypt(s)
    def dec = (new admin.CipherCrypt).decrypt(s)
    def encBase64 = model.Base64 enc s
    def decBase64 = model.Base64 dec s
  }
  implicit def toENCR(o: String) = { EncryptedS(o) }
}

object Base64 {
  import org.apache.commons.codec.binary.Base64
  def enc(s: String) = new Base64(true).encode(s)
  def dec(s: String) = new Base64(true).decode(s)
}

/** utility to encrypt/decrypt stuff*/
object Enc {
  def apply(orig: String) = new CipherCrypt().encrypt(orig)
  def unapply(encoded: String): Option[String] = Some(new CipherCrypt().decrypt(encoded))

  /** url encode */
  def toUrl(orig: String) = URLEncoder.encode(orig, "UTF8")
  /** url decode */
  def fromUrl(orig: String) = URLDecoder.decode(orig, "UTF8")
  
  def toSession(orig: String) = orig.replaceAll("-", "RAZIEDASH")
  def fromSession(orig: String) = orig.replaceAll("RAZIEDASH", "-")

       /** hash a string */
   def hash (input:String ) : String = {
      md() update input.getBytes()
      val arr = md().digest
      new org.apache.commons.codec.binary.Base64(true).encodeToString(arr)
   }

   // I hope this initializes later, when needed...heh
   private object md {
     val i = MessageDigest.getInstance("SHA")
     def apply () = { i } 
   }

}

object EncUrl {
  def apply(orig: String) = URLEncoder.encode(Enc(orig), "UTF8")
  def unapply(encoded: String): Option[String] = Some(URLDecoder.decode(encoded, "UTF8"))
}

/** secured link to be emailed for instance
 *
 *  Note that this is persisted only if the secUrl is requested
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
