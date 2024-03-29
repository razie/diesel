/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.utils

import akka.actor.{Actor, Props, _}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model.WID

/** an autosave wrapper/request
  *
  * @param what
  * @param realm
  * @param name
  * @param userId
  * @param c
  * @param editorMsec if passed, it will be used as the updDtm - use it to detect stale - see uses
  */
case class AutosaveSet(what:String, realm:String, name:String, userId: ObjectId, c:Map[String,String], editorMsec:Option[DateTime] = None) {

  /** set the contents */
  def set() = {
    Autosave.set(what, WID("", name).defaultRealmTo(realm), userId, c, editorMsec)
  }

  /** retrieve the contents from storage, regardless of this having something or not... */
  def rec = {
    val wid = WID("", name).defaultRealmTo(realm)
    Autosave.rec(what, wid.getRealm, wid.wpath, userId)
  }

  /** find the contents, if any */
  def find = {
    Autosave.find(what, WID("", name).defaultRealmTo(realm), userId)
  }

}

/** speed up initial response - do backups and stuff in background */
object DomWorker {
  // should be lazy because of akka's bootstrap
  lazy val worker = Services.system.actorOf(Props[Worker], name = "DomWorker")

  def later (autosaveSet: AutosaveSet) = {worker ! autosaveSet}

  /**
   * doing stuff later
    */
  private class Worker extends Actor {
    // todo persistency - not a big deal if an autosave is lost
    def receive = {
      case a: AutosaveSet => {
        a.set()
      }
    }
  }
}


