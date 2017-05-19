package admin

import controllers.{Admin, IgnoreErrors, VErrors}
import model._
import razie.db.{ROne, RazMongo}
import razie.wiki.{Base64, Enc}
import razie.wiki.model.{Perm, WikiUser}
import razie.wiki.util.AuthService
import com.mongodb.casbah.Imports._
import com.novus.salat.grater
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{Request, RequestHeader}
import razie.Logging
import razie.audit.AuditService
import razie.db.RazSalatContext.ctx
import razie.hosting.Website
import razie.wiki.Services

//import play.cache._

import play.api.cache._


/** statics and utilities for authentication cache */
class RazAuthService extends AuthService[User] with Logging {

  implicit def toU(wu: WikiUser): User = wu.asInstanceOf[User]

  /** clean the cache for current user - probably a profile change */
  def cleanAuth(u: Option[WikiUser] = None)(implicit request: RequestHeader) {
    import play.api.Play.current
    request.session.get(Services.config.CONNECTED).map { euid =>
      synchronized {
        val uid = Enc.fromSession(euid)
        (u orElse Cache.getAs[User](uid + ".connected") orElse Users.findUserByEmail(uid)).foreach { u =>
          debug("AUTH CLEAN =" + u._id)
          Cache.remove(u.email + ".connected")
          Cache.remove(u._id.toString + ".name")
        }
      }
    }
  }

  /** clean the cache for given user - probably a profile change, should reload profile.
    * needed this for cluster auth. State is evil!
    */
  def cleanAuth2(u: WikiUser) = {
    import play.api.Play.current
    synchronized {
      debug("AUTH CLEAN =" + u._id)
      Cache.remove(u.email + ".connected")
      Cache.remove(u._id.toString + ".name")
    }
  }

  /** authentication - find the user currently logged in, from either the session or http basic auth */
  def authUser(implicit request: RequestHeader): Option[User] = {
    val connected = request.session.get(Services.config.CONNECTED)
    val authorization = request.headers.get("Authorization")

    import play.api.Play.current
    debug("AUTH SESSION.connected=" + connected)

    synchronized {
      // from session
      var au = connected.flatMap { euid =>
        val uid = Enc.fromSession(euid)
        Cache.getAs[User](uid + ".connected").map(u => Some(u)
        ).getOrElse {
          debug("AUTH connecting=" + uid)
          Users.findUserByEmail(uid).map { u =>
            debug("AUTH connected=" + u)
//            debug("AUTH MEH =" + u.clubs.size)
            Cache.set(u.email + ".connected", u, 120)
            Cache.set(u._id.toString + ".name", u.userName, 120)
            u
          }
        }
      }

      // for testing it may be overriden in http header
      if (authorization.exists(_.startsWith("None")) && au.exists(_.isAdmin)) {
        log("AUTH OVERRIDE NONE")
        au = None
      } else if (authorization.exists(_.startsWith("Basic ")) && (au.isEmpty || au.exists(_.isAdmin)))
        au = authorization.flatMap { euid =>
          // from basic http auth headers, for testing and API
          val e2 = euid.replaceFirst("Basic ", "")
          val e3 = new String(Base64 dec e2) //new sun.misc.BASE64Decoder().decodeBuffer(e2)
        val EP =
          """H-([^:]*):H-(.*)""".r

          e3 match {
            case EP(em, pa) =>
              //            cdebug << "AUTH BASIC attempt "+e3
              Users.findUserByEmail(Enc(em)).flatMap { u =>
                // can su if admin, for testing
                if (Enc(pa) == u.pwd || (pa=="su" && au.exists(_.isAdmin))) {
                  u.auditLogin(Website.xrealm)
                  val uid = u.id
                  debug("AUTH BASIC connected=" + u)
                  Cache.set(u.email + ".connected", u, 120)
                  Cache.set(u._id.toString + ".name", u.userName, 120)
                  Some(u)
                } else None
              }

            case _ => println("ERR_AUTH wrong Basic auth encoding..."); None
          }
        }

      au
    }
  }

  /** check that the user is active and can thus use basic functionality */
  def checkActive(au: WikiUser)(implicit errCollector: VErrors = IgnoreErrors) =
    controllers.Admin.toON2(au.isActive) orCorr (
      if (au.userName == "HarryPotter")
        Admin.cDemoAccount
      else
        Admin.cAccountNotActive)

  /** sign this content, using a server-specific key */
  def sign(content: String): String = Enc apply Enc.hash(content)

  /** check that the signatures match - there's a trick here, heh */
  def checkSignature(sign: String, signature: String, au: Option[WikiUser]): Boolean =
    sign == signature ||
      ("ADMIN" == signature &&
        (
          au.map(_.asInstanceOf[User]).exists(_.hasPerm(Perm.adminDb)) ||
          Services.config.isLocalhost
        )
      )
}


