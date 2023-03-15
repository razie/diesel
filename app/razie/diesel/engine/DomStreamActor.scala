/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import akka.actor.{Actor, Stash, Timers}
import java.util.concurrent.TimeUnit
import razie.diesel.dom.RDOM.P
import razie.wiki.admin.GlobalData
import scala.concurrent.duration.Duration
import scala.util.Try

/** each stream has its own actor
  *
  * it will serialize status udpates and execution
  *
  * an engine will parallelize as much as async is built-into their activities, not at the stream level
  */
class DomStreamActor(stream: DomStream) extends Actor with Stash with Timers {

  def checkInit: Boolean = {
    if (!DieselAppContext.serviceStarted) {
      // if not started, stash the message for now
      stash()
      false
    } else
      true
  }

  override def receive = {

    case DEInit => {
      //save refs for active engines
      // started, take all stashed messages
      unstashAll()
      stream.actorStarted(self)
    }

    case req@DEStreamPut(name, l) if checkInit =>
      withMyStream(req) {
        stream.put(l)
      }

    case req@DEStreamConsume(name) =>
      withMyStream(req) {
        stream.consume()
      }

    case req@DEStreamDone(name) =>
      withMyStream(req) {
        stream.done()
      }

    case req@DEStreamClean(name) =>
      withMyStream(req) {
        //remove refs for active engines
        GlobalData.dieselStreamsActive.decrementAndGet()
        DieselAppContext.activeStreams.remove(stream.id)
        DieselAppContext.activeStreamsByName.remove(stream.name)
        DieselAppContext.activeActors.remove(stream.id)
        context stop self
      }

    case req@DEStreamDone(name) =>
      withMyStream(req) {
        stream.done()
      }

    case req@DEStreamError(name, parms) =>
      withMyStream(req) {
        stream.error(parms)
      }

    case req@DEStreamWaitMaybe(name, millis) => {
      withMyStream(req) {
        if(stream.lastBatchWaitingAt == 0) {
          // start timer
          stream.lastBatchWaitingAt = System.currentTimeMillis()

          timers.startSingleTimer(
            "DEStreamWaitingOver",      // Name for the timer
            DEStreamWaitingOver(stream.name),
            Duration.fromNanos(millis*1000)
          )

//          import scala.concurrent.ExecutionContext.Implicits.global
//          context.system.scheduler.scheduleOnce(
//            Duration.fromNanos(millis*1000),
//            self,
//            DEStreamWaitingOver(name))
        }
      }
    }

    case req@DEStreamWaitingOver(name) => {
      withMyStream(req) {
        stream.forceBatchNow()
      }
    }

    case req@DEStreamTimeoutReset(name) => {
      withMyStream(req) {
        if(stream.timeoutMillis > 0) {
          timers.startSingleTimer(
            "TimeoutCheck",      // Name for the timer
            DEStreamTimeoutCheck(stream.name),
            Duration.create(stream.timeoutMillis, TimeUnit.MILLISECONDS)
          )
        } else {
          // todo log error
          razie.Log.error("StreamTimeoutReset but no timeoutMillis set !")
        }
      }
    }

    case req@DEStreamTimeoutCheck(name) => {
      withMyStream(req) {
        if(stream.timeoutCheckSaysClose()) {
          stream.abort("TIMEOUT - it's been empty for " + (System.currentTimeMillis() - stream.emptySince) + " ms")
        }
      }
    }
  }

  def withMyStream (req:DEStreamMsg)(f: => Unit) = {
    if (stream.name == req.streamName) {
      Try {
        f
      }
    }
    else DieselAppContext.router.map(_ ! req)
  }

  override def postStop(): Unit = {
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
case class DEStreamPut(override val streamName: String, l: List[Any]) extends DEStreamMsg

/** consume from stream */
case class DEStreamConsume(override val streamName: String) extends DEStreamMsg

/** stream is done */
case class DEStreamDone(override val streamName: String) extends DEStreamMsg

case class DEStreamClean(override val streamName: String) extends DEStreamMsg

case class DEStreamError(override val streamName: String, l: List[P]) extends DEStreamMsg

/** start or restart wait timer */
case class DEStreamWaitMaybe(override val streamName: String, timeoutMillis:Long) extends DEStreamMsg
case class DEStreamWaitingOver(override val streamName: String) extends DEStreamMsg

case class DEStreamTimeoutReset (override val streamName: String) extends DEStreamMsg
case class DEStreamTimeoutCheck (override val streamName: String) extends DEStreamMsg

