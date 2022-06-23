/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package mod.diesel.controllers

import com.google.inject.Singleton
import org.bson.types.ObjectId
import razie.Logging
import razie.audit.Audit
import razie.wiki.model._
import scala.collection.mutable

object DomSessions {
  val sessions = new mutable.HashMap[String,DieselSession]()
}

/** overwriting a section in a page, for an anon session */
case class OverSession (wid:WID, page:WikiEntry, newContent:String, sType:String, sName:String)

/** an anon session */
case class DieselSession (uid:String, id:String) {
  var time = System.currentTimeMillis()
  var overrides = mutable.ListBuffer[OverSession]()
}

/** controller for sessions
  *
  * anon sessions expire in 10 min
  * */
@Singleton
class DomSessions extends mod.diesel.controllers.SFiddleBase  with Logging {
  import DomSessions.sessions

  // todo replicate sessions in cluster, ehcache etc

  val MAX_SESSIONS=100
  val EXPIRY=5*1000 // 5 sec

  // todo distribute notifications in cluster or something when expiring sessions
  def cleanSessions = {
    sessions.values.filter(System.currentTimeMillis() - _.time > EXPIRY).toList.map {
      s => sessions.remove(s.id)
    }
  }

  /** get or make a new session given some user particulars */
  def getSession(uid:String) = RAction { implicit stok =>
    val s =
      (
        if (!uid.isEmpty) sessions.find(t => t._2.uid == uid).map(_._2)
        else None
        ).orElse {
        cleanSessions

        if (sessions.size < MAX_SESSIONS) {
          val s = DieselSession(uid, new ObjectId().toString)
          sessions.put(s.id, s)
          Some(s)
        } else None
      }

    Audit.logdb("DIESEL_FIDDLE_GET_SESSION", s.map(_.id).mkString, s" count=${sessions.size}")

    s.map { s =>
      Ok(s.id).as("application/text")
    }.getOrElse {
      Unauthorized("oops, too many sessions ???")
    }
  }
}

