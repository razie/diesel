package admin

import controllers.Admin
import model.Api
import model.Base64
import model.Enc
import model.User
import play.api.Play.current
import play.api.cache.Cache
import play.api.mvc.Request
import razie.Logging
import model.WikiUser
import model.Users
import org.bson.types.ObjectId
import model.Perm
import razie.cdebug
import controllers.DarkLight
import razie.clog

/** statics and utilities for authentication cache */
object RazAuthService extends AuthService[User] with Logging {
  import admin.M._

  implicit def toU(wu: WikiUser): User = wu.asInstanceOf[User]

  /** clean the cache for current user - probably a profile change */
  def cleanAuth(u: Option[WikiUser] = None)(implicit request: Request[_]) {
    import play.api.Play.current
    request.session.get(Config.CONNECTED).map { euid =>
      synchronized {
        val uid = Enc.fromSession(euid)
        (u orElse Api.findUser(uid)).foreach { u =>
          debug("AUTH CLEAN =" + u._id)
          Cache.remove(u.email + ".connected")
        }
      }
    }
  }
  
  /** authentication - find the user currently logged in, from either the session or http basic auth */
  def authUser(implicit request: Request[_]): Option[User] = {
    val connected = request.session.get(Config.CONNECTED)
    val authorization = request.headers.get("Authorization")

    import play.api.Play.current
    debug("AUTH SESSION.connected=" + connected)

    // this is set even if no users logged in
    // TODO must get rid of this stupid statics... why can't play do this?
    razie.NoStaticS.remove[DarkLight]
    request.session.get("css").fold {
    if(request.headers.get("X-FORWARDED-HOST").exists(x=> Config.BLACKS.exists(x contains _)))
      razie.NoStaticS.put(DarkLight("dark"))
    } { v =>
      razie.NoStaticS.put(DarkLight(v)) 
    }
    //todo configure per realm

    synchronized {
      // from session
      val au = connected.flatMap { euid =>
        val uid = Enc.fromSession(euid)
        Cache.getAs[User](uid + ".connected").map(u => Some(u)).getOrElse {
          debug("AUTH connecting=" + uid)
          Api.findUser(uid).map { u =>
            debug("AUTH connected=" + u)
            Cache.set(u.email + ".connected", u, 120)
            u
          }
        }
      } orElse authorization.flatMap { euid =>
        // from basic http auth headers, for testing and API
        val e2 = euid.replaceFirst("Basic ", "")
        val e3 = new String(Base64 dec e2) //new sun.misc.BASE64Decoder().decodeBuffer(e2)
        val EP = """H-([^:]*):H-(.*)""".r

        e3 match {
          case EP(em, pa) =>
//            cdebug << "AUTH BASIC attempt "+e3
            Api.findUser(Enc(em)).flatMap { u =>
              if (Enc(pa) == u.pwd) {
                u.auditLogin
                val uid = u.id
                debug("AUTH BASIC connected=" + u)
                Cache.set(u.email + ".connected", u, 120)
                Some(u)
              } else None
            }

          case _ => println("ERR_AUTH wrong Basic auth encoding..."); None
        }
      }

      // allow theme to be overriten per request / session
      request.session.get("css").foreach { v =>
        au.foreach(_.css = Some(v));
//        razie.NoStaticS.put(DarkLight(v))
      }

      razie.NoStaticS.put[WikiUser](au.getOrElse(null))
      au.foreach(u => razie.NoStaticS.put(DarkLight(u.css.getOrElse("dark"))))

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
  def checkSignature(sign: String, signature: String): Boolean =
    sign == signature ||
      ("ADMIN" == signature &&
        (razie.NoStaticS.get[WikiUser].map(_.asInstanceOf[User]).exists(_.hasPerm(Perm.adminDb)) || Services.config.isLocalhost))
}
