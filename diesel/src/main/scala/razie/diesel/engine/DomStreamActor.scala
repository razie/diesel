/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import akka.actor.{Actor, Stash}
import razie.diesel.dom.RDOM.P
import razie.wiki.admin.GlobalData
import scala.util.Try

/** each stream has its own actor
  *
  * it will serialize status udpates and execution
  *
  * an engine will parallelize as much as async is built-into their activities
  */
class DomStreamActor(stream: DomStream) extends Actor with Stash {

  def checkInit: Boolean = {
    if (!DieselAppContext.serviceStarted) {
      // if not started, stash the message for now
      stash()
      false
    } else
      true
  }

  def receive = {

    case DEInit => {
      //save refs for active engines
      // started, take all stashed messages
      unstashAll()
    }

    case req@DEStreamPut(name, l) if checkInit => {
      if (stream.name == name) Try {
        stream.put(l)
      }
      else DieselAppContext.router.map(_ ! req)
    }

    case req@DEStreamConsume(name) => {
      if (stream.name == name) {
        Try {
          stream.consume()
        }
      }
      else DieselAppContext.router.map(_ ! req)
    }

    case req@DEStreamDone(name) => {
      if (stream.name == name) {
        Try {
          stream.done()
        }
      }
      else DieselAppContext.router.map(_ ! req)
    }

    case req@DEStreamClean(name) => {
      if (stream.name == name) {
        //remove refs for active engines
        GlobalData.dieselStreamsActive.decrementAndGet()
        DieselAppContext.activeStreams.remove(stream.id)
        DieselAppContext.activeStreamsByName.remove(stream.name)
        DieselAppContext.activeActors.remove(stream.id)
        context stop self
      }
      else DieselAppContext.router.map(_ ! req)
    }

    case req@DEStreamDone(name) => {
      if (stream.name == name) {
        Try {
          stream.done()
        }
      }
      else DieselAppContext.router.map(_ ! req)
    }

    case req@DEStreamError(name, parms) => {
      if (stream.name == name) {
        Try {
          stream.error(parms)
        }
      }
      else DieselAppContext.router.map(_ ! req)
    }
  }

  override def postStop() = {
    // assert it's stopped
    // DieselAppContext.activeActors.remove(eng.id)
  }
}

/* *****************************
streaming messages
 */

/** base class for streams internal message */
trait DEStreamMsg {
  def streamName: String
}

/** put in stream */
case class DEStreamPut(streamName: String, l: List[Any]) extends DEStreamMsg

/** consume from stream */
case class DEStreamConsume(streamName: String) extends DEStreamMsg

/** stream is done */
case class DEStreamDone(streamName: String) extends DEStreamMsg

case class DEStreamClean(streamName: String) extends DEStreamMsg

case class DEStreamError(streamName: String, l: List[P]) extends DEStreamMsg

