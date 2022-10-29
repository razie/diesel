/*  ____    __    ____  ____  ____  ___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

//import akka.stream.scaladsl.Source
//import akka.util.ByteString
import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.DieselAssets
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.nodes.{EError, EMsg}
import razie.diesel.expr.ScopeECtx
import razie.diesel.model.DieselMsg
import razie.hosting.Website
import razie.wiki.{Config, Services}
import razie.wiki.model.WID
import scala.collection.mutable.ListBuffer

object DomStream {

  /** get max size from properties */
  def getMaxSize = {
    val dflt = if (Services.config.isLocalhost) "11000" else "100"
    Services.config.prop("diesel.stream.maxSize", dflt).toInt
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
  val batchWaitMillis:Int,
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
  trait DomStreamConsumer {
    def consumeDataBatch(batches:List[List[Any]]): Unit
    def consumeData(dataAsList:List[Any]): Unit
    def error(): Unit
    def complete(): Unit
  }

  /** an engine is client - so consumption becomes messages created in context of that engine */
  case class EngDomStreamConsumer(engine: String, node:String) extends DomStreamConsumer {
    override def consumeDataBatch(batches: List[List[Any]]): Unit = {
      val asts = new ListBuffer[DomAst]()
      val batchAccumulator = new ListBuffer[List[Any]]()

      batches.foreach { batch =>

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

      DieselAppContext ! DEAddChildren(
        engine, node, recurse = true, -1, asts.toList,
        Option((a, e) => a.withPrereq(getDepy)))
    }

    override def consumeData(dataAsList: List[Any]): Unit = {
      dataAsList.toList.foreach { data =>

        val ast = DomAst(new EMsg(
          DieselMsg.STREAMS.STREAM_ONDATA,
          P.of("stream", name) ::
              P.of("data", data) ::
              P.of("context", context) ::
              Nil
        ) with KeepOnlySomeSiblings)

        setCtx(ast)

        DieselAppContext ! DEAddChildren(
          engine,
          node,
          recurse = true, -1,
          List(ast),
          Option((a, e) => a.withPrereq(getDepyOnDATA)))
      }

    }

    override def error(): Unit = {
      val ast = DomAst(EMsg(
        DieselMsg.STREAMS.STREAM_ONERROR,
        List(
          P.of("stream", name), P.of("context", context)
        ) ::: errors.toList
      ))

      setCtx(ast)

          DieselAppContext ! DEAddChildren(engine, node, recurse = true, -1, List(ast),
            Option((a, e) => a.withPrereq(getDepy)))
          DieselAppContext ! DEComplete(engine, node, recurse = true, -1, Nil)

    }

    override def complete(): Unit = {
      val ast = DomAst(EMsg(
        DieselMsg.STREAMS.STREAM_ONDONE,
        List(
          P.of("stream", name), P.of("context", context)
        )
      ))

      setCtx(ast)

        DieselAppContext ! DEAddChildren(engine, node, recurse = true, -1, List(ast),
          Option((a, e) => a.withPrereq(getDepy)))
        DieselAppContext ! DEComplete(engine, node, recurse = true, -1, Nil)

    }

    /** todo merge with the other - this is experimental */
    private def getDepyOnDATA: List[String] = {
      val target = DieselAppContext.activeEngines.get(engine).flatMap(_.findNode(node))
      var res = target.toList.flatMap(
        _.children
            .filter(_.status != DomState.DONE)
            .filter(_.value.isInstanceOf[EMsg]))

      // for onData just depend on the last one
      res = res.lastOption.toList

      res.map(_.id)
    }

    /** get values of other generated nodes */
    def getDepy: List[String] = {
      val target = sink.flatMap(tid=> DieselAppContext.activeEngines.get(tid.engine).flatMap(_.findNode(tid.node)))
      val res = target.toList.flatMap(
        _.children
            .filter(_.status != DomState.DONE)
            .filter(_.value.isInstanceOf[EMsg])
            .map(_.id))
      res
    }

  }

//  case class SourceDomStreamConsumer(source: Source[ByteString, +], node:String) extends DomStreamConsumer

  private var sink: Option[EngDomStreamConsumer] = None // target parent for consume nodes

  // todo support multiple consumers

  /** specify target parent for the consume nodes */
  def withEngineSink(engine:String, node:String) = {
    this.sink = Option(EngDomStreamConsumer(engine, node))
    this
  }

  // the actual elements
  private val values: ListBuffer[Any] = new ListBuffer[Any]()
  private val errors: ListBuffer[P] = new ListBuffer[P]()
  private var isDone = false
  private var isError = false
  private var isConsumed = false

  var totalPut = 0L
  var totalConsumed = 0L

  var lastBatchWaitingAt = 0L
  var lastBatchConsumedAt = 0L

  def getValues = values.toList

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
    * @param justConsume - no put, just trigger consumers, used when clearing the buffer
    * @param forceBatch - no put, just consume and force the batch even if smaller (batch timer kicked in)
    */
  def put(l: List[Any], justConsume: Boolean = false, forceBatch:Boolean = false) = {
    trace(s"($name) DStream.put: " + l.mkString(","))

    totalPut += l.size

    if (!justConsume) {
      assert(!isDone)

      var acceptable = Math.min(maxSize - values.size, l.size)
      if (l.size > acceptable + 1) {
        val err = DomAst(new EError(s"Stream overflow $name capacity: $maxSize"))
        DieselAppContext ! DEAddChildren(owner.id, sink.mkString, recurse = true, -1, List(err), None)
        // todo also put it in the consumer flow?

        // todo throw back to sender
        assert(assertion = false, s"Stream overflow $name capacity: $maxSize")

        // todo implement backpressure...
      } else {
        trace(" - DStream appended: " + l.mkString(","))
        values.appendAll(l)
      }
    }

    // todo I send right away - should batch up with a timeout

    if (isConsumed) sink.map { tid =>

      if (batch && batchSize > 0 && values.size < batchSize && !forceBatch && batchWaitMillis > 0) { // batched but not enough items...
        // set batch timer
        DieselAppContext ! DEStreamWaitMaybe(name, batchWaitMillis) // start timer

      } else if (batch && (batchSize > 0 || forceBatch)) { //consume all batches now!

        lastBatchConsumedAt = System.currentTimeMillis()
        lastBatchWaitingAt = 0

        var pickedUp = 0
        val batchAccumulator = new ListBuffer[List[Any]]()

        values.toList
            .grouped(batchSize)
            .toList
            .foreach { batch =>
              trace(" - DStream sending.slice: " + batch.mkString(","))
              pickedUp += batch.size

              batchAccumulator.append(batch)
            }

        // todo each node should have its own scope or run in sequence...

        trace(s" - DStream size ${values.size} dropping: $pickedUp")
        values.remove(0, pickedUp)

        sink.foreach(_.consumeDataBatch(batchAccumulator.toList))

        trace(s" - DStream values size ${values.size} is: " + values.mkString)

      } else {

        // no batch

        sink.foreach(_.consumeData(values.toList))

        trace(" - DStream clear: ")
        values.clear()
      }
    } else {
      // no consumers - accumulate to a point
    }
  }

  /** force batch - the waiting is over */
  def forceBatchNow(): Unit = {
    put(Nil, justConsume = true, forceBatch = true)
  }

  /** complete the stream: send onDone and DEComplete the target consume node */
  private def complete(): Unit = {
    if(!sentComplete) {
      sentComplete = true

      sink.foreach(_.complete())
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
        sink.foreach(_.error())
      }
    } else {
      // todo
    }
  }

  // owner starts consuming
  def consume(): Unit = {
    isConsumed = true
    // if something already, just trigger consumers
    if (values.size > 0) put(Nil, justConsume = true)
    if (isDone && !isError) done(true)
    if (isDone && isError) error(Nil, justConsume = true)

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
  batchWaitMillis:Int = 0,
  context: P = P.of("context", "{}"),
  correlationId: Option[String] = None) extends DomStream(owner, name, description, batch, batchSize, batchWaitMillis, context,
  correlationId) {
}
