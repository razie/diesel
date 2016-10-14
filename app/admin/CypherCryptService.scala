package admin

import razie.wiki.EncryptService

import scala.util.Try

object CypherEncryptService extends EncryptService {
  def enc (s:String) : String = Try {
    (new admin.CipherCrypt).encrypt(s)
  }.getOrElse("")

  def dec (s:String) : String = Try {
    (new admin.CipherCrypt).decrypt(s)
  }.getOrElse("")
}

