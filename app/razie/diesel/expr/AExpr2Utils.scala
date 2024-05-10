/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import java.time.{Duration, LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import razie.diesel.dom.RDOM.P.{asString, isSimpleType}
import razie.diesel.{Diesel, dom}
import razie.diesel.dom.RDOM.{P, PValue}
import razie.diesel.dom._
import razie.diesel.engine.{DieselAppContext, DomAssetRef, DomRefs, DomStream, DomStreamArrayProducer, DomStreamEngProducer, DomStreamStreamProducer, DomStreamV2}
import razie.wiki.model.CATS
import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.util.Try
import scala.util.parsing.json.JSONArray

object AExpr2Utils {

  def findStreamRef (p: RDOM.P, expr:String) (implicit ctx:ECtx) :DomAssetRef = {
    val pv = p.calculatedTypedValue
    if(isSimpleType(p) && !pv.asString.contains(":")) DomAssetRef(CATS.DIESEL_STREAM, p.calculatedValue)
    else if(isSimpleType(p)) DomRefs.parseDomAssetRef(pv.asString).getOrElse {
      throw new DieselExprException(s"Can't parse stream ref from (p=${p}) in (expr=${expr})")
    }
    else if(p.ttype.isJson) pv.value.asInstanceOf[DomStream].ref
    else {
      throw new DieselExprException(s"Stream ref not found from (p=${p}) in (expr=${expr})")
    }
  }

  /** a stream related operator
    *
    * @param op operator
    * @param v whatever value
    * @param av left side
    * @param b right side
    * @param bv right side
    * @param streamP the stream parameter of the op - either left or right depending on the op
    * @param expr
    * @param ctx
    * @return
    */
  def streamOp (op: String, v: Any, av:P, b:Expr, bv:P, streamP:P, expr:String)(implicit ctx: ECtx) = {
    val avv = av.calculatedTypedValue
    val bvv = bv.calculatedTypedValue

    val streamRef = findStreamRef(streamP, expr)

    // todo what if remote ref. I guess these stream ops only work with local streams???
    val streamO =
      if (streamP.value.get.value.isInstanceOf[DomStream]) streamP.value.get.value.asInstanceOf[DomStream]
      else DieselAppContext.activeStreamsByName.get(streamRef.id).get

    val res = op match {

      case ">>" if avv.cType.isArray => {

        // todo don't throw - just add warning to flow if it already had a producer
        // or make a list of producers, which produce until the end of the last one?
        if(streamO.getProducer.isDefined) throw new DieselExprException(s"Stream already has a producer: ${streamO.getProducer.mkString}")
        streamO.withProducer (new DomStreamArrayProducer(streamO, streamO.owner.id, "", avv.asArray.toList))
        streamP
      }

      case ">>" if avv.cType.isSubtypeOf (WTypes.wt.MSG) => {

        // todo don't throw - just add warning to flow if it already had a producer
        // or make a list of producers, which produce until the end of the last one?
        if(streamO.getProducer.isDefined) throw new DieselExprException(s"Stream already has a producer: ${streamO.getProducer.mkString}")
        streamO.withProducer (new DomStreamEngProducer(streamO, streamO.owner.id, "", avv.asString))
        streamP
      }

      case ">>" if avv.cType.isStream => {

        // sink for stream
////        if (streamO.get.isDefined) throw new DieselExprException(s"Stream already has a producer: ${streamO.getProducer.mkString}")
//        streamO.withEngineSink (streamO.owner.id)
        streamP
      }

      case ">>>" => {
        // stream op / map
        streamP
      }

      case "<<" => {
        // stream op / map

        streamP
      }

      case "<<<" => {
        // setup a generator

        streamP
      }

      // stream map - ads a stage to sink
      case "map" if avv.cType.isStream => {
        // can't use b.apply because the context is lazy...
        if (! streamP.value.get.value.isInstanceOf[DomStreamV2])
          throw new DieselExprException("Only V2 streams support ops... use diesel.stream.new2")

        val s2 = streamP.value.get.value.asInstanceOf[DomStreamV2]

        s2.withMap (b, ctx)
        streamP
      }

    }

    res.value.get
  }

  // ordinary map between two expressions
  def map (a:Expr, av:P, b:Expr, v:Any, expr:String)(implicit ctx: ECtx) = {
    av.calculatedTypedValue.cType.name match {

      case WTypes.ARRAY => {
        val elementType = av.calculatedTypedValue.cType.wrappedType

        val arr = av.calculatedTypedValue.asArray

        val resArr = arr.map { x =>
          mapOne (b, x, elementType)
        }

        //val le = resArr.map(_.calculatedTypedValue)
        val le = resArr
        val finalArr = le.map(_.value)

        PValue(finalArr, P.inferArrayTypeFromPV(le))
      }

      case WTypes.RANGE => {
        val elementType = WTypes.wt.RANGE

        val arr = av.calculatedTypedValue.asRange

        val resArr = arr.map { x =>
          val res = if (b.isInstanceOf[LambdaFuncExpr]) {
            val res = b.applyTyped(x)
            res
          } else if (b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
            // common case, no need to go through context, Block passes through to Lambda
            val res = b.applyTyped(x)
            res
          } else {
            // todo we populate an "x" or should it be "elem" ?
            val sctx = new StaticECtx(List(P.fromTypedValue("x", x)), Some(ctx))
            val res = b.applyTyped(x)(sctx)
            res
          }
          res
        }

        val le = resArr.map(_.calculatedTypedValue)
        val finalArr = le.map(_.value)

        PValue(finalArr, P.inferArrayTypeFromPV(le))
      }

      case WTypes.JSON => {
        val elementType = av.calculatedTypedValue.cType.getClassName

        val arr = av.calculatedTypedValue.asJson

        val resArr = arr.map { x =>
          val xj = P.fromSmartTypedValue("x", Map("key" -> x._1, "value" -> x._2))

          val res = if (b.isInstanceOf[LambdaFuncExpr]) {
            // arr map x => f
            val res = b.applyTyped(xj)
            res
          } else if (b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
            // arr map (x => f)
            // common case, no need to go through context, Block passes through to Lambda
            val res = b.applyTyped(xj)
            res
          } else {
            // we populate an "x" or should it be "elem" ?
            val sctx = new StaticECtx(List(P.fromTypedValue("x", x)), Some(ctx))
            val res = b.applyTyped(xj)(sctx)
            res
          }
          res
        }

        val le = resArr.map(_.calculatedTypedValue)
        val finalArr = le.map(_.value)

        PValue(finalArr, P.inferArrayTypeFromPV(le))
      }

      case _ if av.ttype.isStream => {
        // passing av second time to avoid evaluating b outside of context - it's a lazy func
        streamOp (op="map", v, av, b, av, av, expr)
      }

      case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.getOrElse(null)

      case _ => throw new DieselExprException("Can't do map on: " + av)
    }
  }

  /** map a single element */
  def mapOne (b: Expr, x: Any, elementType:Option[String])(implicit ctx: ECtx) = {

    val res = if (b.isInstanceOf[LambdaFuncExpr]) {
      val res = b.applyTyped(P.fromTypedValue("x", x).withSchema(elementType)) // todo optimize - making two P's
      res
    } else if (b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
      // common case, no need to go through context, Block passes through to Lambda
      val res = b.applyTyped(P.fromTypedValue("x", x).withSchema(elementType)) // todo optimize - making two P's
//                val res = b.applyTyped(x)
      res
    } else {
      // todo we populate an "x" or should it be "elem" ?
      val sctx = new StaticECtx(List(P.fromTypedValue("x", x).withSchema(elementType)), Some(ctx))
      val res = b.applyTyped(x)(sctx)
      res
    }
    res.calculatedTypedValue
  }

  /** pipeline expressions - expression composition via payload
    *
    * @param op operator
    * @param v whatever value
    * @param av left side
    * @param b right side
    * @param bv right side
    * @param streamP the stream parameter of the op - either left or right depending on the op
    * @param expr
    * @param ctx
    * @return
    */
  def pipeOp (op: String, v: Any, av:P, b:Expr, expr:String)(implicit ctx: ECtx) = {
    val avv = av.calculatedTypedValue
    val elementType = avv.cType.wrappedType

    val sctx = new StaticECtx(List(P.fromTypedValue("payload", avv).withSchema(elementType)), Some(ctx))
    val bvv = b.applyTyped(avv)(sctx)
    val res = bvv.calculatedTypedValue

    res
  }

}

