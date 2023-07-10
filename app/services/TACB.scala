/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

package services

import akka.actor.ActorRef
import razie.Logging


/**
  * Typed actor callback
  */
object TACB {

  def withFunc (sync: PartialFunction[Any,Unit], msgCls:Class[_]) = Callback(Option(sync), None, msgCls)

  def withActor (async:ActorRef, msgCls:Class[_]) = Callback(None, Option(async), msgCls)

  /** a single callback registered for a notification base class */
  case class Callback (sync: Option[PartialFunction[Any,Unit]], async:Option[ActorRef], msgCls:Class[_]) extends Logging {

    def canEat (msg:Any) : Boolean = msgCls.isInstance(msg)

    def eat (msg:Any): Unit = {
      try {
        if (sync.isDefined && canEat(msg)) sync.get.apply(msg)
        else if (async.isDefined) async.get ! msg
      } catch {
        case t:Throwable => {
          warn (s"NOTIF[${this.getClass.getSimpleName}] Exception while handling Msg of ${msg.getClass.getSimpleName}", t)
        }
      }
    }
  }

  /** implement this as a sender */
  trait Notifier extends Logging {
    var callbacks: List[Callback] = Nil

    def subscribe (callback: Callback) : Unit = {
      callbacks = callback :: callbacks
    }

    def eat (msg: Any): Unit = {
      val applicables = callbacks.filter(_.canEat(msg))
      info(s"NOTIF[${this.getClass.getSimpleName}] Msg of ${msg.getClass.getSimpleName} sent to ${applicables.size} eaters.")

      applicables.foreach(_.eat(msg))
    }
  }
}
