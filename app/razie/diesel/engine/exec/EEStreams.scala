/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import akka.util.Timeout
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDOM, WTypes}
import razie.diesel.engine._
import razie.diesel.engine.nodes._
import razie.diesel.expr.{DieselExprException, ECtx, SimpleExprParser}
import razie.diesel.model.DieselMsg
import razie.wiki.model.DCNode
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** executor for "ctx." messages - operations on the current context
  *
  * See
  * http://specs.dieselapps.com/Topic/Concurrency,_asynchronous_and_distributed
  */
class EEStreams extends EExecutor(DieselMsg.STREAMS.PREFIX) {

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DieselMsg.STREAMS.PREFIX && messages.exists(_.met == m.met)
    // don't eat .onDone...
  }

  /**
    * this is client-side: these operations go through the stream's actor
    */
  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    in.met match {

      case "new" => {
        val name = ctx.getRequired("stream")
        val batch = ctx.getp("batch").map(_.calculatedTypedValue.asBoolean).getOrElse(false)
        val batchSize = ctx.getp("batchSize").map(_.calculatedTypedValue.asLong.toInt).getOrElse(100)
        val batchWait = ctx.getp("batchWaitMillis").map(_.calculatedTypedValue.asLong.toInt).getOrElse(0)
        val timeout = ctx.getp("timeoutMillis").map(_.calculatedTypedValue.asLong.toInt).getOrElse(-1)
        val par = ctx.getp("consumeParallel").map(_.calculatedTypedValue.asBoolean).getOrElse(false)

        val others = in.attrs
           // no need to remove these...
//            .filter(_.name != "stream")
//            .filter(_.name != "batch")
//            .filter(_.name != "batchSize")
//            .filter(_.name != "batchWaitMillis")
            .map(_.calculatedP)

        val context = P.of("context", others.map(p => (p.name, p)).toMap)

        // todo factory for V1
        val warn = DieselAppContext.findStream(name).map { s =>
          val cnt = s.getValues.size

          // clean and remove the old one, sync
          DieselAppContext.stopStream(name)

          if (cnt > 0) EError(s"Stream $name was open and with $cnt elements inside!! Closing, but some generator or consumer may still use it! [new with same name]")
          else EWarning(s"Stream $name was open (altough empty) ! Closing, but some generator or consumer may still use it! [new with same name]")
        }.toList

        val s = DieselAppContext.mkStream(
          new DomStreamV1(
            ctx.root.engine.get,
            name,
            name,
            batch,
            batchSize,
            batchWait,
            timeout,
            context,
            consumeParallel=par))
        ctx.root.engine.get.evAppStream(s)

        warn ::: EInfo(s"stream - creating $name") ::
        // this results in some exceptions for assignment when name is not ident
//            EVal(P.fromTypedValue(name, s, WTypes.wt.OBJECT.withSchema("DieselStream"))) ::
            EVal(P.fromTypedValue("dieselStream", s, WTypes.wt.OBJECT.withSchema("DieselStream"))) ::
            EVal(P.fromTypedValue("dieselStreamRef", s.ref.toj, WTypes.wt.JSON.withSchema("DieselStream"))) ::
            Nil
      }

      case "put" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))

        val parms = in.attrs.filter(x=> x.name != "stream" && x.name != "streamRef").map(_.calculatedP)
        var errors:List[_] = Nil
        val list =
          parms.map{x=>
            Try {
              x.calculatedTypedValue.value
            }.getOrElse {
              if (ctx.isStrict) throw new DieselExprException("Parameters are not all Array(s): " + x)
              else errors = EWarning("Parameter not Array: " + x) :: errors
              Nil
            }
          }

        DieselAppContext ! DEStreamPut(r, list)
        val rem = if(r.isRemote) "remote" else "local"
        EInfo(s"stream.put DEStreamPut $rem $r - put ${list.size} elements") :: errors
      }

      case "putAll" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))
        val parms = in.attrs.filter(x=> x.name != "stream" && x.name != "streamRef").map(_.calculatedP)

        var errors:List[_] = Nil
        val list =
          parms.flatMap{x=>
              Try {
                x.calculatedTypedValue.asArray.toList
              }.getOrElse {
                if (ctx.isStrict) throw new DieselExprException("Parameters are not all Array(s): " + x)
                else errors = EWarning("Parameter not Array: " + x) :: errors
                Nil
              }
          }
        DieselAppContext ! DEStreamPut(r, list)
        val rem = if(r.isRemote) "remote" else "local"
        EInfo(s"stream.put DEStreamPut $rem $r - put ${list.size} elements") :: errors
      }

      case "generate" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))

        val start = ctx.getRequired("start").toInt
        val end = ctx.getRequired("end").toInt
        val map = ctx.get("mapper")

        val list = (start to end).toList

        DieselAppContext ! DEStreamPut(r, list)
        EInfo(s"stream.generate $name - put ${list.size} elements") :: Nil
      }

      case "error" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))
        val name = r.id
        val parms = in.attrs.filter(x=> x.name != "stream" && x.name != "streamRef").map(_.calculatedP)

        DieselAppContext ! DEStreamError(r, parms)

        EInfo(s"stream.done $name") :: Nil
      }

      case "done" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))
        val name = r.id

        val stream = DieselAppContext.findStream(r.id)
        val found = stream.map(_.name).mkString

        if(stream.isDefined || r.isRemote)
         DieselAppContext ! DEStreamDone(r)

        if(r.isLocal) {
          val consumed = stream.map(_.getIsConsumed).mkString
          val done = stream.map(_.streamIsDone).mkString
          val sz = stream.map(_.getValues.size).mkString
          val msg = s"stream.done $r: found:$found consumed:$consumed done:$done values:$sz"

          if (stream.isDefined) EInfo(msg) :: Nil
          else EError(s"stream.done - stream not found: ${name}", msg) :: Nil
        } else {
          EInfo(s"stream.done sent to remote node $r") :: Nil
        }
      }

      case "consume" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))
        val name = r.id
        assert(r.isLocal)

        val timeout = ctx.get("timeout").orElse(ctx.get("timeoutMillis"))

        if (DieselAppContext.activeStreamsByName.contains(name)) {
          new EEngSuspend("stream.consume", "", Option((e, a, l) => {
            val stream = DieselAppContext.activeStreamsByName(name)
            stream.withEngineSink(ctx.root.engine.get.id, a.id)

            DieselAppContext ! DEStreamConsume(stream.name)

            timeout.foreach(d => {
              DieselAppContext ! DELater(e.id, d.toInt, DEComplete(e.id, a.id, recurse = true, l, Nil))
            })
          })) :: //with KeepOnlySomeChildren ::
              Nil
        } else {
          EError(s"stream.consume - stream not found: " + name) :: Nil
        }
      }

      case "mkString" => {
        val r = ctx.getp("streamRef").map(ref(_)).getOrElse(ref(ctx.getRequired("stream")))
        val name = r.id
        assert(r.isLocal)

        val timeout = ctx.get("timeout")
        val separator = ctx.get("separator").getOrElse(",")

        if (DieselAppContext.activeStreamsByName.contains(name)) {
          val s = DieselAppContext.activeStreamsByName(name)
          val warn = if(! s.streamIsDone) List(EWarning("Stream is not done...")) :: Nil else Nil
          val res = s.getValues.fold("")((a,b) => a.toString + separator + b.toString)
          EVal(RDOM.P.fromSmartTypedValue(Diesel.PAYLOAD, res)) :: warn
        } else {
          EError(s"stream.consume - stream not found: " + name) :: Nil
        }
      }

      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  def ref (name:String) = DomAssetRef("DieselStream", name)

  def ref (p:P)(implicit ctx:ECtx) = {
    val m = p.calculatedTypedValue.asJson
    DomAssetRef(
      m("cat").toString,
      m("id").toString,
      m.get("realm").map(_.toString),
      m.get("section").map(_.toString),
      m.get("node").map(x => DCNode(x.toString))
    )
  }

  override def toString = "$executor::ctx "

  override val messages: List[EMsg] =
    EMsg(DieselMsg.STREAMS.PREFIX, "put") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "new") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "done") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "consume") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "putAll") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "generate") ::
        EMsg(DieselMsg.STREAMS.PREFIX, "mkString") ::
        Nil
}
