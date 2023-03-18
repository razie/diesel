/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import akka.actor.{Actor, Stash}
import java.util.concurrent.TimeUnit
import razie.audit.Audit
import razie.clog
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.nodes.{EInfo, EMsg}
import razie.diesel.model.DieselTarget
import razie.diesel.samples.DomEngineUtils
import razie.wiki.admin.GlobalData
import razie.wiki.model.DCNode
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Try
import services.{DieselPubSub, TACB}
import services.TACB.Callback


/** ====================== Actor infrastructure =============== */

/** base class for engine internal message */
sealed trait DEMsg {
  def engineId: String
}

/** a message - request to decompose
  *
  * The engine will decompose the given node a and send to self a DERep
  */
case class DEReq (override val engineId: String, a: DomAst, recurse: Boolean, level: Int) extends DEMsg

/** a message - reply to decompose, basically asynchronous completion of a message
  *
  * The engine will stich the AST together and continue
  *
  * send this from async executors
  */
case class DERep (override val engineId: String, a: DomAst, recurse: Boolean, level: Int, results: List[DomAst]) extends DEMsg

/** complete a node that was waiting for async response */
case class DEComplete (override val engineId: String, targetId: String, recurse: Boolean, level: Int, results: List[DomAst])
    extends DEMsg

/**
  * like DEComplete but just expand, not done yet
  *
  * @param engineId engine this refers to
  * @param targetId target parent node id
  * @param recurse
  * @param level
  * @param results
  * @param mapper   - optional mapper to transform teh nodes before adding, **after** locking the engine
  */
case class DEAddChildren (override val engineId: String, targetId: String, recurse: Boolean, level: Int, results: List[DomAst],
                         mapper: Option[(DomAst, DomEngine) => DomAst] = None)
    extends DEMsg

/**
  * prune children later, leave X
  *
  * @param engineId
  * @param parentId
  * @param leave
  */
case class DEPruneChildren (override val engineId: String, parentId: String, keep: Int, level: Int) extends DEMsg


/** initialize and stop the engine */
case object DEInit extends DEMsg {override def engineId = ""}

/** stop the actor - engine sends this when done */
case object DEStop extends DEMsg {override def engineId = ""}

/** please cancel an engine */
case class DECancel (override val engineId:String, reason:String, senderId:String, senderDesc:String) extends DEMsg

/** suspend the engine for async messages - to continue you have to fire off a DERep yourself, later */
case object DESuspend extends DEMsg {override def engineId = ""}

/** send the next message later */
case class DELater (override val engineId: String, d: Int, next: DEMsg) extends DEMsg

case class DEStartTimer (override val engineId: String, d: Int, results: List[DomAst]) extends DEMsg

case class DETimer (override val engineId: String, results: List[DomAst]) extends DEMsg

/** continue and pause engine */
case class DEPause    (override val engineId: String) extends DEMsg
case class DEPlay     (override val engineId: String) extends DEMsg
case class DEPlayThis (override val engineId: String, m:DEMsg) extends DEMsg
case class DEContinue (override val engineId: String) extends DEMsg


/** remoting basys */
trait DERemoteMsg
case class DEPlayRemoteMsg (ref:DomAssetRef, msg:DEMsg) extends DERemoteMsg
case class DERemoteRunEngine (from:DCNode, msg: EMsg, correlationId: Option[DomAssetRef], settings: DomEngineSettings, id:String, desc:String) extends DERemoteMsg


/** error handling
  *
  * todo should not be part of normal processing DEMsg
  */
case class DEError (override val engineId: String, msg: String) extends DEMsg

/**
  * engine router - routes updates to proper engine actor
  *
  * todo can i drop activeActors and address actors directly?
  */
class DomEngineRouter () extends Actor with razie.Logging {

  override def receive = {
    case DEInit => {
      DieselPubSub.subscribe(TACB.withActor(self, classOf[DERemoteMsg]))
      DieselPubSub.subscribe(TACB.withActor(self, classOf[DEStreamRemoteMsg]))

      // todo is this fair?
      // in case other services don't start me - i will start myself
      DieselAppContext.getActorSystem.scheduler.scheduleOnce(
        Duration.create(7, TimeUnit.SECONDS),
        this.self,
        "startAll"
      )
    }

    // stop all engines
    case DEStop => {
      Audit.logdb("DEBUG", "DomEngineRouter STOP All")
      DieselAppContext.activeActors.values.map(_ ! DEStop)
   }

    case "startAll" => {
      Audit.logdb("DEBUG", "DomEngineRouter startAll")
      if (!DieselAppContext.serviceStarted) {
        Audit.logdb("DEBUG-BAD", "DomEngineRouter self init")
        DieselAppContext.start
      }
    }

    // all DE messages share routing
    case m: DEMsg => route(m.engineId, m)

    // remote message got here
    case m: DEPlayRemoteMsg => {
      debug ("DERemoteMsg received: " + m)
      route (m.msg.engineId, m.msg)
    }

    // remote execution request from other node
    case m@DERemoteRunEngine (from, msg, correlationId, settings, id, desc) => {
      debug ("DERemoteRunEngine received: " + m)

      val target = DieselTarget.ENV(settings.realm.mkString)
      val engine = DomEngineUtils
          .createEngine (Option(DomAst(msg)), None, target.specs, target.stories, settings, None, None, None)

      engine.root.prependAllNoEvents(List(
        DomAst(
          EInfo(s"From remote node: " + DomAssetRef(DomRefs.CAT_DIESEL_NODE, from.toString).href),
          AstKinds.DEBUG)
            .withStatus(DomState.SKIPPED)
      ))

//      engine.inheritFrom(this)
//      engine.ctx.root._hostname = ctx.root._hostname

      engine.process // start it up in the background
    }

    // message meant for remote stream, wrap and forward
    case m : DEStreamMsgWithRef if (m.streamRef.exists(_.isRemote)) => {

      log(s"DomEngineRouterActor: received ${m.getClass.getSimpleName}")
      DieselPubSub ! DEStreamRemoteMsg(m) // delegate via pubsub
    }

    // remote message meant for local stream
    case m @ DEStreamRemoteMsg (msg) => {

      log(s"DomEngineRouterActor: received ${m.getClass.getSimpleName}")
      if(msg.streamRef.exists(x=>DomRefs.isLocal(x.node))) {
        log(s"...forwarding to self $msg")
        self ! msg
      } else {
        log("... not for me. ignore it!")
      }
    }

    case m: DEStreamMsg => {

      log(s"DomEngineRouterActor: received ${m.getClass.getSimpleName}")

      DieselAppContext.activeStreamsByName
          .get(m.streamName)
          .map(x => {
            route(x.id, m);
            ""
          })
          // should end up in DomStreamActor
          .getOrElse(
            error("DomEngine Router DROP STREAM message " + m)
            // todo recover failed workflows
            // todo distributed routing
          )
    }
  }

  def route(id: String, msg: Any): Unit = {
    log(s"DomEngineRouterActor: routing ${msg.getClass.getSimpleName} to $id")
    DieselAppContext.activeActors.get(id).map(_ ! msg).getOrElse(
      // todo if the engine is still around, collected, alert these in the there - add warn nodes
      // otherwise we need to alert somehow... anyways, something's off?
      clog << "DomEngine Router DROP message " + msg
      // todo recover failed workflows
      // todo distributed routing
    )
  }

}

/** each engine has its own actor
  *
  * it will serialize status udpates and execution
  *
  * an engine will parallelize as much as async is built-into their activities
  */
class DomEngineActor(eng: DomEngine) extends Actor with Stash {

  def checkInit: Boolean = {
    if (!DieselAppContext.serviceStarted) {
      // if not started, stash the message for now
      stash()
      false
    } else
      true
  }

  def withEng(m:DEMsg)(f:DEMsg => Unit) = {
    if (eng.id == m.engineId) Try {
      f.apply(m)
    }
    else DieselAppContext.router.map(_ ! m)
  }

  override def receive = receiveNormal orElse receiveOthers

  /** receive when normal / behaviour */
  def receiveNormal: Receive = {

    case pause@DEPause(eid) if checkInit => {
      withEng(pause) {m =>
        eng.pause
        context.become(receivePaused orElse receiveOthers)
      }
    }

    case req@DEReq(eid, _, _, _) if checkInit => {
      withEng(req) {m =>
        eng.processDEMsg(req)
      }
    }
  }

  /** receive when paused / behaviour */
  def receivePaused: Receive = {

    case play:DEPlay if checkInit => {
      withEng(play) {m =>
        eng.play
      }
    }

    case play@DEPlayThis(eid, msg) if checkInit => {
      withEng(play) {m =>
        eng.processDEMsg(msg)
      }
    }

    case cont@DEContinue(eid) if checkInit => {
      withEng(cont) {m =>
        eng.continueFromPaused
        context.unbecome()
      }
    }

    case req@DEReq(eid, _, _, _) if checkInit => {
      withEng(req) {m =>
        if (eng.id == eid) Try {
        eng.stashedMsg.append(req)
      }
      }
    }
  }

  /** receive normal messages */
  def receiveOthers: Receive = {

    case DEInit => {
      //save refs for active engines
      // started, take all stashed messages
      unstashAll()
    }

    case rep:DECancel if checkInit => {
      proc(rep)
    }

    case rep:DERep if checkInit => {
      proc(rep)
    }

    case rep:DEComplete if checkInit => {
      proc(rep)
    }

    case rep:DEAddChildren if checkInit => {
      proc(rep)
    }

    case rep:DEPruneChildren if checkInit => {
      proc(rep)
    }

    case DEStop => {
      context stop self //it will remove refs for active engines in postStop
    }

    // used when engines schedule stuff
    case timer@DELater(id, d, m) => {
      val dur = Duration.create(d, TimeUnit.MILLISECONDS)

      if (eng.id == id) {
        DieselAppContext.getActorSystem.scheduler.scheduleOnce(
          dur,
          this.self,
          m
        )
      }
      else DieselAppContext.router.map(_ ! timer)
    }

    case timer@DEStartTimer(id, d, m) => {
      // used when engines schedule stuff
      if (eng.id == id) {
        DieselAppContext.getActorSystem.scheduler.scheduleOnce(
          Duration.create(d, TimeUnit.MILLISECONDS),
          this.self,
          DETimer(id, m)
        )
      }
      else DieselAppContext.router.map(_ ! timer)
    }

    case timer@DETimer(id, _) => {
      // used when engines schedule stuff
      if (eng.id == id) {
      }
      else DieselAppContext.router.map(_ ! timer)
    }
  }

  /** process one regular message */
  def proc(m:DEMsg) {
    checkInit
    if (eng.id == m.engineId) {
      try {
        eng.processDEMsg(m)
      } catch {
        case throwable: Throwable => {
          eng.addError(throwable)
          razie.clog << throwable
        }
      }
    } else {
      DieselAppContext.router.foreach(_ ! m)
    }
  }

  override def postStop() = {
    DieselAppContext.activeEngines.remove(eng.id)
    DieselAppContext.activeActors.remove(eng.id)
  }
}
