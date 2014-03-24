/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.ObjectId
import com.novus.salat.grater
import admin.Audit
import db.ROne
import db.RazSalatContext.ctx
import admin.Services
import db.RCreate
import db.RDelete
import db.REntity

/**
 * secured link to be emailed for instance
 *
 *  Note that this is persisted only if the secUrl is requested
 */
@db.RTable
case class DoSec (
  link: String,
  onlyOnce:Boolean = true, // TODO use this somehow - not certain they're done though...
  expiry: DateTime = DateTime.now.plusHours(8),
  isDone:Boolean = false,
  lastDoneDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[DoSec] {

  def id = _id.toString

  private def create = RCreate[DoSec] (this)

  def done = this.copy (isDone=true, lastDoneDtm=DateTime.now).update
  
  // this must be a def - otherwise it keeps creating it, eh?
  def secUrl = {
    create
    "http://" + Services.config.hostport + "/doe/sec/" + id
  }
}

object DoSec {
  def find(id: String) = {
    // TODO play 20 workaround - remove this in play 2.1
    val iid = id.replaceAll(" ", "+")

    ROne[DoSec](new ObjectId(iid))
  }
}

/**
 * simple encryption service - provide your own implementation DES with a per app
 *  configured key should do fine
 */
trait EncryptService {
  def enc(s: String): String
  def dec(s: String): String
}

/** sample stub clear text - no encryption */
object NoEncryptService extends EncryptService {
  def enc(s: String): String = s
  def dec(s: String): String = s
}

/** set this in your Global::beforeStart() */
object EncryptService {
  var impl : EncryptService = NoEncryptService
}

object Sec {
  //================ Encryption
  implicit class EncryptedS(s: String) {
    def enc = EncryptService.impl.enc(s)
    def dec = EncryptService.impl.dec(s)
    def encBase64 = model.Base64 enc s
    def decBase64 = model.Base64 dec s
    def encUrl = Enc toUrl s
    def decUrl = Enc fromUrl s
  }
}

object Base64 {
  import org.apache.commons.codec.binary.Base64
  def enc(s: String) = new Base64(true).encode(s)
  def dec(s: String) = new Base64(true).decode(s)
}

/** utility to encrypt/decrypt stuff*/
object Enc {
  def apply(orig: String) = EncryptService.impl.enc(orig)
  def unapply(encoded: String): Option[String] = Some(EncryptService.impl.dec(encoded))

  /** url encode */
  def toUrl(orig: String) = URLEncoder.encode(orig, "UTF8")
  /** url decode */
  def fromUrl(orig: String) = URLDecoder.decode(orig, "UTF8")

  // TODO remove this play 2.0 workaround
  def toSession(orig: String) = orig.replaceAll("-", "RAZIEDASH")
  def fromSession(orig: String) = orig.replaceAll("RAZIEDASH", "-")

  /** hash a string */
  def hash(input: String): String = {
    md() update input.getBytes()
    val arr = md().digest
    new org.apache.commons.codec.binary.Base64(true).encodeToString(arr)
  }

  // I hope this initializes later, when needed...heh, that's pray programming, damn it!
  private object md {
    val i = MessageDigest.getInstance("SHA")
    def apply() = { i }
  }

}

object Dec {
  def apply(encoded: String): String = EncryptService.impl.dec(encoded)
}

object EncUrl {
  def apply(orig: String) = URLEncoder.encode(Enc(orig), "UTF8")
  def unapply(encoded: String): Option[String] = Some(URLDecoder.decode(encoded, "UTF8"))
}
