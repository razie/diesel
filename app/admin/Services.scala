/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import com.mongodb.casbah.{MongoConnection, MongoDB}
import db.UpgradeDb
import model.{WikiConfig, WikiUser}

// yeah, I hate myself...
object Services {
  var auth: AuthService[WikiUser] = NoAuthService
  var audit: AuditService = NoAuditService
  var config: WikiConfig = null

  var mongoDbVer = 0
  var mongoUpgrades: Map[Int, UpgradeDb] = Map()

  var alli: Alligator = NoAlligator

  /** connect to your database, with your connection properties, clustered or not etc */
  var mkDb: () => MongoDB = () => {
    MongoConnection("") apply ("")
  }
}

/** don't ask... still decoupling this */
trait Alligator {
  /** execute work request later */
  def ! (a: Any)

  /** execute work request now */
  def !?(a: Any)
}

object NoAlligator extends Alligator {
  def !(a: Any) {}
  def !?(a: Any) {}
}

