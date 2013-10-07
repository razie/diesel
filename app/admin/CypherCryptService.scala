package admin

import model.EncryptService

object CypherEncryptService extends EncryptService {
  def enc (s:String) : String = (new admin.CipherCrypt).encrypt(s)
  def dec (s:String) : String = (new admin.CipherCrypt).decrypt(s)
}

