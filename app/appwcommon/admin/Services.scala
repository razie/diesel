/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import db.UpgradeDb
import play.api.mvc.Request
import razie.Logging
import model.WikiUser
import org.bson.types.ObjectId
import com.mongodb.casbah.MongoDB
import com.mongodb.casbah.MongoConnection

case class DarkLight(css: String)

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

/**
 * need to provide an authentication service
 *
 *  when authenticated / logged in, a user will store something in the session cookie
 */
trait AuthService[+U <: WikiUser] {
  /** clean the cache for current user - probably a profile change, should reload profile */
  def cleanAuth(u: Option[WikiUser] = None)(implicit request: Request[_])

  /** authentication - find the user currently logged in */
  def authUser(implicit request: Request[_]): Option[U]

  def checkActive(au: WikiUser)(implicit errCollector: VError = IgnoreErrors): Option[Boolean]

  /** sign this content - return the signature */
  def sign(content: String): String

  /** check that the signatures match - there's a trick here, heh */
  def checkSignature(sign: String, signature: String): Boolean
}

case class MyUser(userName: String, email: String, _id: ObjectId = new ObjectId()) extends WikiUser {
  def ename = userName
  def myPages(cat: String): List[Any] = List.empty
}

/** sample stub authentication */
object NoAuthService extends AuthService[MyUser] with Logging {
  val harry = MyUser("Harry", "harry@hogwarts.mag")

  /** clean the cache for current user - probably a profile change */
  def cleanAuth(u: Option[WikiUser] = None)(implicit request: Request[_]) {
  }

  /** authentication - find the user currently logged in */
  def authUser(implicit request: Request[_]): Option[MyUser] = {
    Some(harry)
  }

  def checkActive(au: WikiUser)(implicit errCollector: VError = IgnoreErrors) = Some(true)

  /** sign this content */
  def sign(content: String): String = "EVERYTHING_GOES" // you MUST IMPLEMENT THIS, heh

  /** check that the signatures match - there's a trick here, heh */
  def checkSignature(sign: String, signature: String): Boolean = sign == signature
}

/** don't ask... still decoupling this */
trait Alligator {
  def !(a: Any)
}

object NoAlligator extends Alligator {
  def !(a: Any) {}
}

