package mod.diesel.controllers

import akka.actor.{Actor, Props, _}
import org.bson.types.ObjectId
import play.libs.Akka
import razie.wiki.admin.Autosave
import razie.wiki.model.WID

/** an autosave request */
case class AutosaveSet(what:String, realm:String, name:String, userId: ObjectId, c:Map[String,String])

/** speed up initial response - do backups and stuff in background */
object DomWorker {
  // should be lazy because of akka's bootstrap
  lazy val worker = Akka.system.actorOf(Props[Worker], name = "DomWorker")

  def later (autosaveSet: AutosaveSet) = {worker ! autosaveSet}

  /**
   * doing stuff later
    */
  private class Worker extends Actor {
    // todo persistency - not a big deal if an autosave is lost
    def receive = {
      case a: AutosaveSet => {
        Autosave.set(a.what, WID("", a.name).defaultRealmTo(a.realm), a.userId, a.c)
      }
    }
  }
}


