package admin

import razie.cout
import razie.wiki.EncryptService

import scala.util.Try

/** use Crypt */
class CypherEncryptService (
  private val enK:String,
  private val deK:String
                           ) extends EncryptService {

  def crypt(k:String) =
    if(k.length > 0) new admin.CipherCrypt(k.substring(0, 8).getBytes)
    else
      new admin.CipherCrypt

  def enc (s:String) : String =
    crypt(enK).encrypt(s)

  def dec (s:String) : String =
    crypt(deK).decrypt(s)

//  def enc (s:String) : String = Try {
//    (new admin.CipherCrypt).encrypt(s)
//  }.getOrElse("")
//
//  def dec (s:String) : String = Try {
//    (new admin.CipherCrypt).decrypt(s)
//  }.getOrElse("")
}

