/*  ____    __    ____  ____  ____  ___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.DieselAssets
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.nodes.{EError, EMsg}
import razie.diesel.expr.ScopeECtx
import razie.diesel.model.DieselMsg
import razie.hosting.Website
import razie.wiki.Config
import razie.wiki.model.WID
import scala.collection.mutable.ListBuffer

object DomStream {

  /** get max size from properties */
  def getMaxSize = {
    val dflt = if (Config.isLocalhost) "10000" else "100"
    Config.prop("diesel.stream.maxSize", dflt).toInt
    // todo add realm setting and trusted realms ?
  }
}

/** simple streaming for inter-flow comms
  *
  * A stream needs only one consumer flow (the owner). When the owner dies, the stream dies too.
  * The stream has one actor that manages it - all access is via its actor.
  *
  * put elements on the stream from various flows and they'll be sent to the consumer flow.
  *
  * The stream creates new message for each batchf
  *
  * More needed: backpressure, persistence, queueing, timed slice window, distributed
  *
  * NOT - MT-safe, need to wrap in an actor
  *
  * @param correlationId is parentEngineID.parentSuspendID - if dot is missing, this was a fire/forget
  */
abstract class DomStream (
  val owner: DomEngine,
  val name: String,
  val description: String,
  val batch: Boolean = false,
  val batchSize: Int,
  val context: P = P.of("context", "{}"),
  val correlationId: Option[String] = None,
  val maxSize: Int = DomStream.getMaxSize,
  val id: String = new ObjectId().toString) extends Logging {

  assert(name.trim.length > 0, "streams need unique names")

  def wid = WID("DieselStream", name)

  def href = DieselAssets.mkAhref(WID("DieselStream", this.name))

  def href(format: String = "") = s"/diesel/engine/view/$id?format=$format"

  var synchronous = false

  /** a stream consumer is a pair of engine/node where consumption occurs */
  case class DomStreamConsumer(engine: String, node:String)
  private var targetId: Option[DomStreamConsumer] = None // target parent for consume nodes

  // todo support multiple consumers

  /** specify target parent for the consume nodes */
  def withTargetId(engine:String, node:String) = {
    this.targetId = Some(DomStreamConsumer(engine, node))
    this
  }

  // the actual elements
  private val list: ListBuffer[Any] = new ListBuffer[Any]()
  private val errors: ListBuffer[P] = new ListBuffer[P]()
  private var isDone = false
  private var isError = false
  private var isConsumed = false

  var totalPut = 0L
  var totalConsumed = 0L

  def getIsConsumed = isConsumed

  def streamIsDone = isDone

  def setCtx(ast: DomAst): DomAst = {
    // todo need to exec in separate scopes...
//    ast.replaceCtx(new ScopeECtx(Nil, targetId.map(owner.n).flatMap(_.getCtx)))
    ast
  }

  /** put some elements onto the stream
    *
    * if in single element mode - sent straight to consumers
    *
    * if in batch mode, they may be batched or not
    *
    * @param justConsume - no put, just trigger consumers
    */
  def put(l: List[Any], justConsume: Boolean = false) = {
    trace(s"($name) DStream.put: " + l.mkString(","))

    totalPut += l.size

    if (!justConsume) {
      assert(!isDone)

      var acceptable = Math.min(maxSize - list.size, l.size)
      if (l.size > acceptable) {
        val err = DomAst(new EError(s"Stream overflow $name capacity: $maxSize"))
        DieselAppContext ! DEAddChildren(owner.id, targetId.mkString, recurse = true, -1, List(err), None)

        // todo throw back to sender
        assert(false, s"Stream overflow $name capacity: $maxSize")

        // todo implement backpressure...
      } else {
        trace(" - DStream appended: " + l.mkString(","))
        list.appendAll(l)
      }
    }

    // todo I send right away - should batch up with a timeout

    if (isConsumed) targetId.map { tid =>

      if (batch && batchSize > 0) {

        var pickedUp = 0
        val asts = new ListBuffer[DomAst]()

        list.toList
            .grouped(batchSize)
            .toList
            .foreach { batch =>
              trace(" - DStream sending.slice: " + batch.mkString(","))
              pickedUp += batch.size

              val m = new EMsg(
                DieselMsg.STREAMS.STREAM_ONDATASLICE,
                P.of("stream", name) ::
                    P.of("data", batch) ::
                    P.of("context", context) ::
                    Nil
              ) with KeepOnlySomeSiblings

              val ast = DomAst(m)
                  .withPrereq(
                    // todo add a "parallel" setting on consume?
                    asts.map(_.id).toList
                  )

              setCtx(ast)

              asts.append(ast)
            }

        // todo each node should have its own scope or run in sequence...

        trace(s" - DStream size ${list.size} dropping: $pickedUp")
        list.remove(0, pickedUp)
        DieselAppContext ! DEAddChildren(tid.engine, tid.node, recurse = true, -1, asts.toList,
          Some((a, e) => a.withPrereq(getDepy)))
        trace(s" - DStream list size ${list.size} is: " + list.mkString)

      } else {

        // no batch

        list.toList.map { data =>
          val ast = DomAst(new EMsg(
            DieselMsg.STREAMS.STREAM_ONDATA,
            P.of("stream", name) ::
                P.of("data", data) ::
                P.of("context", context) ::
                Nil
          ) with KeepOnlySomeSiblings)

          setCtx(ast)

          DieselAppContext ! DEAddChildren(tid.engine, tid.node, recurse = true, -1, List(ast),
            Some((a, e) => a.withPrereq(getDepyOnDATA)))
        }

        trace(" - DStream clear: ")
        list.clear()
      }
    } else {
      // no consumers - accumulate to a point
    }
  }

  /** complete the stream: send onDone and DEComplete the target consume node */
  private def complete(): Unit = {
    if(!sentComplete) {
      sentComplete = true

      val ast = DomAst(EMsg(
        DieselMsg.STREAMS.STREAM_ONDONE,
        List(
          P.of("stream", name), P.of("context", context)
        )
      ))

      setCtx(ast)

      targetId.map { tid =>
        DieselAppContext ! DEAddChildren(tid.engine, tid.node, recurse = true, -1, List(ast),
          Some((a, e) => a.withPrereq(getDepy)))
        DieselAppContext ! DEComplete(tid.engine, tid.node, recurse = true, -1, Nil)
      }
    }
  }

  // todo don't remember why this is needed...
  val FORCE_DUPLO = Website.getRealmProp(owner.settings.realm.mkString, "diesel.streams.forceDuplo", Some("true")).mkString.toBoolean

  var sentComplete = false // to send just one

  /** like done but stops consumption as well and drops what's in the stream right now... */
  def abort(): Unit = {
    if(FORCE_DUPLO || !isDone) {
      isDone = true
      complete()
    } else {
      ???
    }
  }

  /** stream item production is done - complete consumption of what's in buffer and close it */
  def done(justConsume: Boolean = false): Unit = {
    if(FORCE_DUPLO || !isDone) {
      if (!justConsume) {
        isDone = true
        if (isConsumed) {
          consume()
        }
      } else {
        complete()
      }
    } else {
      // todo
    }
  }

  /** todo merge with the other - this is experimental */
  def getDepyOnDATA: List[String] = {
    val target = targetId.flatMap(tid=> DieselAppContext.activeEngines.get(tid.engine).flatMap(_.findNode(tid.node)))
    var res = target.toList.flatMap(
      _.children
          .filter(_.status != DomState.DONE)
          .filter(_.value.isInstanceOf[EMsg]))

    // for onData just depend on the last one
     res = res.lastOption.toList

    res.map(_.id)
  }

  /** get list of other generated nodes */
  def getDepy: List[String] = {
    val target = targetId.flatMap(tid=> DieselAppContext.activeEngines.get(tid.engine).flatMap(_.findNode(tid.node)))
    val res = target.toList.flatMap(
      _.children
          .filter(_.status != DomState.DONE)
          .filter(_.value.isInstanceOf[EMsg])
          .map(_.id))
    res
  }

  def error(l: List[P], justConsume: Boolean = false): Unit = {
    if(true || !isDone) {
      if (!justConsume) {
        isError = true
        isDone = true
        errors.appendAll(l)
        if (isConsumed) {
          consume()
        }
      } else {

        val ast = DomAst(EMsg(
          DieselMsg.STREAMS.STREAM_ONERROR,
          List(
            P.of("stream", name), P.of("context", context)
          ) ::: errors.toList
        ))

        setCtx(ast)

        targetId.map { tid =>
          DieselAppContext ! DEAddChildren(tid.engine, tid.node, recurse = true, -1, List(ast),
            Some((a, e) => a.withPrereq(getDepy)))
          DieselAppContext ! DEComplete(tid.engine, tid.node, recurse = true, -1, Nil)
        }
      }
    } else {
      // todo
    }
  }

  // owner starts consuming
  def consume(): Unit = {
    isConsumed = true
    // if something already, just trigger consumers
    if (list.size > 0) put(Nil, true)
    if (isDone && !isError) done(true)
    if (isDone && isError) error(Nil, true)

    // not done - will wait for done
//    targetId.map { tid =>
//      DieselAppContext ! DEComplete(owner.id, targetId.get, recurse = true, -1, Nil)
//    }
  }

  // it's cleanup by the owner engine
  def cleanup(): Unit = {
    // make sure it's done - don't accept anymore
    isDone = true

    // cleanup
    DieselAppContext ! DEStreamClean(name)
  }

  override def toString = s"DomStream($name, $id)"
}

class DomStreamV1(
  owner: DomEngine,
  name: String,
  description: String,
  batch: Boolean = false,
  batchSize: Int = -1,
  context: P = P.of("context", "{}"),
  correlationId: Option[String] = None) extends DomStream(owner, name, description, batch, batchSize, context,
  correlationId) {
}