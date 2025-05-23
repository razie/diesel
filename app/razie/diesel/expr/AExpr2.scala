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

/** type-aware arithmetic expressions of the form "a OP b" - on various types, including json, strings, arrays etc */
case class AExpr2(a: Expr, op: String, b: Expr) extends Expr {
  override val expr = "("+a.toDsl + " " + op + " " + b.toDsl+")"

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).currentValueAsCExpr.ee

  /**  apply this function to an input value and a context */
  override def applyTyped(v: Any)(implicit ctx: ECtx): P = { //Try {

    // resolve an expression to P with value and type
    def top(x: Expr): Option[P] = x match {
      case CExpr(aa, tt) => Some(P.fromTypedValue("", aa, tt))
      case aei: AExprIdent => aei.tryApplyTyped(v)
      case _ => Some(new P("", P.asString(a(v))))
    }

    def isNum(p: P): Boolean = p.calculatedTypedValue.cType.name == WTypes.NUMBER

    def isDate(p: P): Boolean = p.calculatedTypedValue.cType.name == WTypes.DATE

    def isBool(p: P): Boolean = {
      val x = p.calculatedTypedValue
      x.cType.name == WTypes.BOOLEAN || {
        val y = x.value.toString.toLowerCase.trim
        y == "true" || y == "false" || y == "yes" || y == "no"
      }
    }

    val av = a.applyTyped(v)

    val res: PValue[_] = op match {

      case "/" => {
        val bv = b.applyTyped(v)
        val bs = bv.calculatedValue
        (a, b) match {
          case _ if isNum(av) && isNum(bv) => {
            val as = av.calculatedValue
            if (as.contains(".") || bs.contains(".")) {
              val ai = as.toFloat
              val bi = bs.toFloat
              PValue(ai / bi, WTypes.wt.NUMBER)
            } else {
              val ai = as.toLong
              val bi = bs.toLong
              PValue(ai / bi, WTypes.wt.NUMBER)
            }
            // todo float and type safe numb
          }

          case _ => {
            PValue("ERR:operator / on non numbers")
            throw new DieselExprException(
              s"ERR: multiply needs both numbers but got: ${a.getType} and ${b.getType} "
            )
          }
        }
      }

      case "*" => {
        val bv = b.applyTyped(v)
        val bs = bv.calculatedValue
        (a, b) match {
          case _ if isNum(av) && isNum(bv) => {
            val as = av.calculatedValue
            if (as.contains(".") || bs.contains(".")) {
              val ai = as.toFloat
              val bi = bs.toFloat
              PValue(ai * bi, WTypes.wt.NUMBER)
            } else {
              val ai = as.toLong
              val bi = bs.toLong
              PValue(ai * bi, WTypes.wt.NUMBER)
            }
            // todo float and type safe numb
          }

          case _ => {
            PValue("ERR:operator * on non numbers")
            throw new DieselExprException(
              s"ERR: multiply needs both numbers but got: ${a.getType} and ${b.getType} "
            )
          }
        }
      }

      case "+" | "+=" => {
        val bv = b.applyTyped(v)
        (a, b) match {
          // json exprs are different, like cart + { item:...}
          case (aei:AExprIdent, JBlockExpr(jb, _))
            if av.ttype == WTypes.JSON =>
            jsonExprMap(op, av.calculatedTypedValue, bv.calculatedTypedValue)

          // json exprs are different, like cart + xx
          case (aei:AExprIdent, bei:AExprIdent)
            if av.ttype == WTypes.JSON &&
                bv.ttype == WTypes.JSON =>
            jsonExprMap(op, av.calculatedTypedValue, bv.calculatedTypedValue)

          case _ if isDate(av) => { // date + duration

            val as = a(v).toString
            val bs = b(v).toString
            var dur: Option[scala.concurrent.duration.Duration] = None

            if (isNum(bv)) {
              try {
                // msec
                dur = Some(scala.concurrent.duration.Duration.fromNanos(bv.calculatedTypedValue.asLong * 1000.0))
              } catch {
                case e: Exception => throw new DieselExprException(
                  "Right side not a duration like 5s or 5 seconds: " + bs)
              }
            } else if (!bs.matches("[0-9]+ *[nsmhdMy]")) {
              try {
                // try with duration expressions
                dur = Some(scala.concurrent.duration.Duration(bs.toLowerCase))
              } catch {
                case e: Exception => throw new DieselExprException(
                  "Right side not a duration like 5s or 5 seconds: " + bs)
              }
            }

            val tsFmtr = WTypes.ISO_DATE_PARSER
            val ad = LocalDateTime.from(tsFmtr.parse(as))
            var res = ad

            if (dur.isDefined) {
              res = res.plusNanos(dur.get.toNanos)
            } else {
              val bi = bs.substring(0, bs.length - 1).trim.toLong
              val bt = bs.last

              bt match {
                case 'n' => res = res.plusNanos(bi)
                case 's' => res = res.plusSeconds(bi)
                case 'm' => res = res.plusMinutes(bi)
                case 'h' => res = res.plusHours(bi)
                case 'd' => res = res.plusDays(bi)
                case 'M' => res = res.plusMonths(bi)
                case 'y' => res = res.plusYears(bi)
                case _ =>
                  throw new DieselExprException("Unknown duration type [nsmhdMy] : " + bt)
              }
            }

            val ts = tsFmtr.format(res)
            PValue(ts, WTypes.wt.DATE).withStringCache(ts)
          }

          case _ if isNum(av) && isNum(bv) => {// if a is num, b will be converted to num

            val as = a(v).toString
            val bs = b(v).toString
            if (as.contains(".") || bs.contains(".")) {
              val ai = as.toFloat
              val bi = bs.toFloat
              PValue(ai + bi, WTypes.wt.NUMBER)
            } else {
              val ai = as.toLong
              val bi = bs.toLong
              PValue(ai + bi, WTypes.wt.NUMBER)
            }
          }

          case _ => {

            if (bv.ttype.name == WTypes.ARRAY ||
                av.ttype.name == WTypes.ARRAY) {   // if either is array, concat lists

              val al = if (av.ttype.name == WTypes.ARRAY) av.calculatedTypedValue.asArray else List(
                av.calculatedTypedValue.value)

              val bl = if (bv.ttype.name == WTypes.ARRAY) bv.calculatedTypedValue.asArray else List(
                bv.calculatedTypedValue.value)

              val res = new ListBuffer[Any]()
              res.appendAll(al)
              res.appendAll(bl)
              PValue(res, WTypes.wt.ARRAY)

            } else if (bv.ttype.name == WTypes.JSON &&
                av.ttype.name == WTypes.JSON) {
              // json exprs are different, like cart + { item:...}

              try {
                jsonExprMap(op, av.calculatedTypedValue, bv.calculatedTypedValue)
              } catch {
                case t: Throwable => throw new DieselExprException(
                  s"Parm ${av} or ${bv} can't be parse to JSON: " + t.toString).initCause(t)
              }
            } else {
              PValue(av.calculatedValue + bv.calculatedValue)
            }
          }
        }
      }

      case "-" => {
        val bv = b.applyTyped(v)
        (a, b) match {
          // json exprs are different, like cart + { item:...}
          case (aei: AExprIdent, JBlockExpr(jb, _))
            if aei.tryApplyTyped("").exists(_.ttype.name == WTypes.JSON) =>
            jsonExprMap(op, av.calculatedTypedValue, bv.calculatedTypedValue)

          // json exprs are different, like cart + { item:...}
          case (aei: AExprIdent, bei: AExprIdent)
            if aei.tryApplyTyped("").exists(_.ttype.name == WTypes.JSON) &&
                bei.tryApplyTyped("").exists(_.ttype.name == WTypes.JSON) =>
            jsonExprMap(op, av.calculatedTypedValue, bv.calculatedTypedValue)

          case _ if isDate(av) && isDate(bv) => {
            val ad = av.calculatedTypedValue.asDate
            val bd = bv.calculatedTypedValue.asDate
            val d = Duration.between(bd, ad).toMillis / 1000;
            PValue(d, WTypes.wt.NUMBER)
          }

          case _ if isNum(av) && isNum(bv) => {
            // if a is num, b will be converted to num
            val as = av.calculatedValue
            if (as.contains(".")) {
              val ai = as.toFloat
              val bi = bv.calculatedValue.toFloat
              PValue(ai - bi, WTypes.wt.NUMBER)
            } else {
              val ai = as.toLong
              val bi = bv.calculatedValue.toLong
              PValue(ai - bi, WTypes.wt.NUMBER)
            }
          }

          case _ if isDate(av) => {
            val as = a(v).toString
            val bs = b(v).toString
            var dur: Option[scala.concurrent.duration.Duration] = None

            if (isNum(bv)) {
              try {
                // msec
                dur = Some(scala.concurrent.duration.Duration.fromNanos(bv.calculatedTypedValue.asLong * 1000.0))
              } catch {
                case e: Exception => throw new DieselExprException(
                  "Right side not a duration like 5s or 5 seconds: " + bs)
              }
            } else if (!bs.matches("[0-9]+ *[nsmhdMy]")) {
              try {
                // try with duration expressions
                dur = Some(scala.concurrent.duration.Duration(bs.toLowerCase))
              } catch {
                case e: Exception => throw new DieselExprException(
                  "Right side not a duration like 5s or 5 seconds: " + bs)
              }
            }

            val tsFmtr = WTypes.ISO_DATE_PARSER
            val ad = LocalDateTime.from(tsFmtr.parse(as))

            var res = ad

            if (dur.isDefined) {
              res = res.minusNanos(dur.get.toNanos)
            } else {
              val bi = bs.substring(0, bs.length - 1).trim.toLong
              val bt = bs.last

              bt match {
                case 'n' => res = res.minusNanos(bi)
                case 's' => res = res.minusSeconds(bi)
                case 'm' => res = res.minusMinutes(bi)
                case 'h' => res = res.minusHours(bi)
                case 'd' => res = res.minusDays(bi)
                case 'M' => res = res.minusMonths(bi)
                case 'y' => res = res.minusYears(bi)
                case _ =>
                  throw new DieselExprException("Unknown duration type [nsmhdMy] : " + bt)
              }
            }

            val ts = tsFmtr.format(res)
            PValue(ts, WTypes.wt.DATE).withStringCache(ts)
          }

          case _ => {
            throw new DieselExprException(
              "[ERR can't apply operator " + op + s" to types ${av.ttype} and ${bv.ttype}] (" + av + " , " + bv + ")"
            )
          }
        }
      }

      case "or" if isBool(av) => {
        val bv = b.applyTyped(v)
        if(isBool(bv)) PValue(av.calculatedTypedValue.asBoolean || bv.calculatedTypedValue.asBoolean)
        else PValue(new DieselExprException("Need both bools for or - b is " + bv), WTypes.wt.EXCEPTION)
      }

      case "and" if isBool(av) => {
        val bv = b.applyTyped(v)
        if(isBool(bv)) PValue(av.calculatedTypedValue.asBoolean && bv.calculatedTypedValue.asBoolean)
        else PValue(new DieselExprException("Need both bools for and - b is " + bv), WTypes.wt.EXCEPTION)
      }

      // like in JS, if first not exist, use second
      case "||" if a.isInstanceOf[AExprIdent] => {
        a match {
          case aei:AExprIdent =>
            aei.tryApplyTyped("")
                .filter(x => !x.isUndefinedOrEmpty)
                .map(_.calculatedTypedValue)
                .getOrElse(
                  b.applyTyped(v).calculatedTypedValue
                )

          case _ => {
            PValue("")
          }
        }
      }

      case "as" => {

        import java.time.Instant
        import java.time.LocalDateTime
        import java.time.ZoneId

        def doAsWith(b: Expr, bu: String, bs: String, lastTime: Boolean): PValue[_] = {
          val ap = av.calculatedP
          val avv = av.calculatedTypedValue
          val as = avv.asString

          b match {

            // known types

            case _ if avv.cType.name == WTypes.SOURCE && (bs == "json" || bs == "object") => {
              avv.value.asInstanceOf[ParmSource].asP
            }.calculatedTypedValue

            case _ if bs == "boolean" || bs == "bool" =>
              P.fromTypedValue("", as.trim.toLowerCase, WTypes.wt.BOOLEAN).calculatedTypedValue

            case _ if bs == "number" =>
              P.fromTypedValue("", as, WTypes.wt.NUMBER).calculatedTypedValue

            case _ if bs == "float" =>
              P.fromTypedValue("", as.toFloat, WTypes.wt.NUMBER).calculatedTypedValue

            case _ if bs == "int" || bs == "long" =>
              P.fromTypedValue("", as.toFloat.toLong, WTypes.wt.NUMBER).calculatedTypedValue

            case _ if bs == "string" =>
              P.fromTypedValue("", as, WTypes.wt.STRING).calculatedTypedValue

            case _ if bs == "json" || bs == "object" =>
              P.fromTypedValue("", as, WTypes.wt.JSON).calculatedTypedValue

            case _ if bs == "date" && isNum(av) => {
              // date from millis
              val i = Instant.ofEpochMilli(avv.asLong)
              val d = LocalDateTime.ofEpochSecond(i.getEpochSecond, i.getNano, ZoneOffset.UTC);

              val tsFmtr = DateTimeFormatter.ofPattern(WTypes.DATE_FORMAT)
              val ts = tsFmtr.format(d)
//              PValue(ts, WTypes.wt.DATE).withStringCache(ts)

              P.fromTypedValue("", ts, WTypes.wt.DATE).calculatedTypedValue
            }

            case _ if bs == "date" =>
              P.fromTypedValue("", as, WTypes.wt.DATE).calculatedTypedValue

            case _ if bs == "array" => {
              if (avv.cType.name == WTypes.JSON || avv.cType.name == WTypes.OBJECT) {
                // map to array
                val arr = avv.asJson

                val resArr = arr.map { x =>
                  val xj = P.fromSmartTypedValue("x", Map("key" -> x._1, "value" -> x._2))
                  xj
                }
                P.fromTypedValue("", resArr.toList, WTypes.wt.ARRAY).calculatedTypedValue
              } else {
                // maybe string to array
                P.fromTypedValue("", as, WTypes.wt.ARRAY).calculatedTypedValue
              }
            }

            case _ if a.isInstanceOf[AExprIdent] && bs == "msg" =>
              P.fromTypedValue("", a.asInstanceOf[AExprIdent].exprDot, WTypes.wt.MSG).calculatedTypedValue

            case c if ctx.root.domain.exists(_.classes.contains(bu)) => {
              // domain class, base must be json
              if(! ap.isOfType(WTypes.wt.JSON)) {
                throw new DieselExprException("'as' can't typecast to: " + b.toString + " from: (" + ap + ") in expr: " + this.toDsl)
              }
              avv.copy(cType = avv.cType.copy(schema = bu))
            }

            case t: CExpr[String] => // x as "application/pdf"
              // todo smarter, like "asdf" as Student etc - implicit constructors, typecasts etc
              if (av.value.isDefined)
              // todo should we only change theP type not the PVAlue type? like pdf which is a bitstream?
                new P("", av.calculatedValue, WType(t.ee)).withValue(avv.value, WType(t.ee)).calculatedTypedValue
              else
                new P("", as, WType(t.ee)).calculatedTypedValue

            case _ if !lastTime => {
              val bp = b.applyTyped(v)
              doAsWith(bp.currentValue, bp.currentStringValue, b.toString.toLowerCase, lastTime = true)
            }

            case _ if lastTime => throw new DieselExprException("'as' can't typecast to: " + b.toString + " from: " + av + " in expr: " + this.toDsl)
          }
        }

        doAsWith(b, b.toString, b.toString.toLowerCase, lastTime = false)
      }

      case "take" => {
        val bv = b.applyTyped(v)
        val bs = bv.calculatedValue

        if (!isNum(bv)) {
          throw new DieselExprException("Right side must be numeric: " + bv)
        }

        av.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType

            val arr = av.calculatedTypedValue.asArray

            val finalArr = arr.take(bv.value.get.asLong.toInt)
            PValue(finalArr, WTypes.wt.ARRAY)
          }

          case WTypes.RANGE => {
            val elementType = WTypes.wt.RANGE

            val arr = av.calculatedTypedValue.asRange

            val finalArr = arr.toSeq.take(bv.value.get.asLong.toInt)
            PValue(finalArr, WTypes.wt.ARRAY)
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull

          case _ => throw new DieselExprException("Can't do take() on: " + av)
        }
      }

      case "fold" => {
        // fold uses payload to accumulate and the lambda to accumulate
        av.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {

            val arr = av.calculatedTypedValue.asArray

            arr.foreach { x =>
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
              // update accumulator
              ctx.put(res.copy(name = Diesel.PAYLOAD))
            }

            ctx.getRequiredp(Diesel.PAYLOAD).value.getOrElse(null)
          }

          case WTypes.RANGE => {

            val arr = av.calculatedTypedValue.asRange

            arr.foreach { x =>
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
              ctx.put(res.copy(name = Diesel.PAYLOAD))
            }

            ctx.getRequiredp(Diesel.PAYLOAD).value.getOrElse(null)
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull

          case _ => throw new DieselExprException("Can't do map on: " + av)
        }
      }

      case ">>>" | ">>" => {
        val bv = b.applyTyped(v) // evaluate the stream
        AExpr2Utils.streamOp (op, v, av, b, bv, bv, expr)
      }

      case "<<" | "<<<" => {
        val bv = b.applyTyped(v) // evaluate the stream
        AExpr2Utils.streamOp (op, v, av, b, bv, av, expr)
      }

      case "|c" | "|>" => {
        AExpr2Utils.pipeOp (op, v, av, b, expr)
      }

      // same as map but it doesn't affect payload
      case "foreach" => {
        AExpr2Utils.map (a, av, b, v, expr, collectResp = false)
      }

      case "map" => {
        AExpr2Utils.map (a, av, b, v, expr, collectResp = true)
      }


      // concatenate paths - make sure there is just one /
      case "split" => {

        val as = av.calculatedValue
        val bs = b.applyTyped("").currentStringValue

        PValue(as.split(bs).toSeq, WTypes.wt.ARRAY)
      }

      // concatenate paths - make sure there is just one /
      case "catPath" => {

        val as = av.calculatedTypedValue.asString
        val bs = b.applyTyped("").currentStringValue

        val ass = if(as.trim.endsWith("/")) as.trim else as.trim + "/"
        val bss = if(bs.trim.startsWith("/")) bs.trim.substring(1) else bs

        PValue(ass + bss, WTypes.wt.STRING)
      }

      case "mkString" => { // arr mkString "sep"

        av.calculatedTypedValue.cType.name match {

          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType

            val arr = av.calculatedTypedValue.asArray

            val resArr = arr.map { x =>
              if (x.isInstanceOf[P]) {
                x.asInstanceOf[P].currentStringValue
              } else if (x.isInstanceOf[Expr]) {
                x.asInstanceOf[Expr].applyTyped(v).currentStringValue
              } else if (x.isInstanceOf[PValue[_]]) {
                x.asInstanceOf[PValue[_]].asString
              } else {
                x.toString
              }
            }

            val res = resArr.mkString(b.applyTyped("").currentStringValue)

            PValue(res, WTypes.wt.STRING)
          }

          case _ => throw new DieselExprException("Can't do mkString on: " + av)
        }
      }

      case "flatMap" => {
        av.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType

            val arr = av.calculatedTypedValue.asArray

            // the map part - so we can collect type values, not loose them during flatten
            val pvArr = arr.map {x=>
              val res = if(b.isInstanceOf[LambdaFuncExpr] || b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
                // common case, no need to go through context, Block passes through to Lambda
                val respv = b.applyTyped(x).calculatedTypedValue
                respv
              } else {
                // we populate an "x" or should it be "elem" ?
                val sctx = new StaticECtx(List(P.fromTypedValue("x", x)), Some(ctx))
                val respv = b.applyTyped(x)(sctx).calculatedTypedValue
                respv
              }
              res
            }

            // the flatten part, collect types
            val resArr = pvArr.flatten {respv=>
                // todo this should be ignored / a warning ??
              if(!respv.cType.equals(WTypes.wt.ARRAY)) throw new DieselExprException(s"Result of right side not Array for flatMap! It is type: ${respv.cType} value: ${respv.asString.take(500)}...")
              respv.asArray
            }

            val le = pvArr
            val finalArr = resArr

            PValue(finalArr, P.inferArrayTypeFromPV(le))
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull
          case _ => throw new DieselExprException("Can't do flatMap on: " + av)
        }
      }

      case "indexBy" => {
        // index array of objects into an object by a field name (or lambda)

        av.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType

            val arr = av.calculatedTypedValue.asArray

            val map = new HashMap[String, Any]

            if(b.isInstanceOf[LambdaFuncExpr] || b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
              arr.foreach { x =>
                // common case, no need to go through context, Block passes through to Lambda
                val respv = b.applyTyped(x).calculatedTypedValue.asString
                map.put(respv, x)
              }
            } else {
              // by field name
              val sctx = new StaticECtx(Nil, Some(ctx))
              val field = b.applyTyped("")(sctx).calculatedTypedValue.asString

              arr.foreach {x=>
                if(x.isInstanceOf[HashMap[_,_]]) {
                  val k = x.asInstanceOf[HashMap[String,_]].get(field)
                  k.map {kv=>
                    map.put(kv.toString, x)
                  }
                } else {
                  throw new DieselExprException("indexBy only works on array of objects! Found: " + x)
                }
              }
            }


            PValue(map, WTypes.wt.JSON)
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull
          case _ => throw new DieselExprException("Can't do flatMap on: " + av)
        }
      }

      case "filter" => {
        val av = a.applyTyped(v).calculatedTypedValue

        av.cType.name match {
          case WTypes.ARRAY => {
            val arr = av.asArray

            val resArr = arr.filter {x=>
              val res = if(b.isInstanceOf[LambdaFuncExpr] || b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) {
                val res = b.applyTyped(x)
                res
              } else {
                // we populate an "x" or should it be "elem" ?
                val sctx = new StaticECtx(List(P.fromTypedValue("x", x)), Some(ctx))
                val res = b.applyTyped(x)(sctx)
                res
              }
              Try {
                res.calculatedTypedValue.asBoolean
              }.getOrElse {
                throw new DieselExprException("Not a boolean expression: " + res.calculatedTypedValue)
              }
            }

            val finalArr = resArr
            PValue(finalArr, WTypes.wt.ARRAY.withSchema(av.cType.schema))
          }

          case WTypes.JSON => {
            val arr = av.asJson

            val resArr = arr.filter { x =>
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
              res.calculatedTypedValue.asBoolean
            }

            val finalArr = resArr
            PValue(finalArr, WTypes.wt.JSON)
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull
          case _ => PValue(new DieselExprException("Can't do filter on: " + av), WTypes.wt.EXCEPTION)
        }
      }

      /**
      case "filterBy" => {

        av.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType

            val arr = av.calculatedTypedValue.asArray

            val resArr =
              if(b.isInstanceOf[LambdaFuncExpr] || b.isInstanceOf[BlockExpr] && b.asInstanceOf[BlockExpr].ex.isInstanceOf[LambdaFuncExpr]) arr.filter {x=>
                val res = b.applyTyped(x)
                Try {
                  res.calculatedTypedValue.asBoolean
                }.getOrElse {
                  throw new DieselExprException("Not a boolean expression: " + res.calculatedTypedValue)
                }
              }
              else {
                // by field name
                val sctx = new StaticECtx(Nil, Some(ctx))
                val field = b.applyTyped("")(sctx).calculatedTypedValue.asString

                arr.filter { x =>
                  if (x.isInstanceOf[HashMap[_, _]]) {
                    val k = x.asInstanceOf[HashMap[String, _]].get(field)
                    k.map { kv =>
                      map.put(kv.toString, x)
                    }
                  } else {
                    throw new DieselExprException("indexBy only works on array of objects! Found: " + x)
                  }
                }

                val finalArr = resArr
                PValue(finalArr, WTypes.wt.ARRAY.withSchema(av.cType.schema))
              }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.orNull
          case _ => throw new DieselExprException("Can't do flatMap on: " + av)
        }
      }
**/

      case "exists" => {
        val av = a.applyTyped(v).calculatedTypedValue

        av.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.cType.wrappedType

            val arr = av.asArray

            val resArr = arr.exists {x=>
                val res = if(b.isInstanceOf[LambdaFuncExpr]) {
                  val res = b.applyTyped(x)
                  res
                } else {
                  // we populate an "x" or should it be "elem" ?
                  val sctx = new StaticECtx(List(P.fromTypedValue("x", x)), Some(ctx))
                  val res = b.applyTyped(x)(sctx)
                  res
                }
              Try {
                res.calculatedTypedValue.asBoolean
              }.getOrElse {
                throw new DieselExprException("Not a boolean expression: " + res.calculatedTypedValue)
              }
            }

            PValue(resArr, WTypes.wt.BOOLEAN)
          }

          case WTypes.UNDEFINED if ctx.nonStrict => P.undefined(Diesel.PAYLOAD).value.getOrElse(null)
//          case _ => throw new DieselExprException("Can't do exists on: " + av)
          case _ => PValue(new DieselExprException("Can't do exists on: " + av), WTypes.wt.EXCEPTION)
        }
      }

      case _ => PValue(new DieselExprException(s"[ERR unknown operator $op] in expression $toDsl"), WTypes.wt.EXCEPTION)
    }

    // nothing matched... start yelling!!!

    // null comes from undefined nonStrict, normally - so if strict, then blow...
    if(res == null && ctx.nonStrict)
      P.undefined("")
    else if(res == null && ctx.isStrict)
      throw new DieselExprException(s"[ERR null result $op] in expression $toDsl")
    else
//      new P("", res.asString, res.cType).copy(value = Option(res))
      new P("", "", res.cType).copy(value = Option(res))

  }

  /** process a js operation like obja + objb */
  // todo decide if we should do this
  // todo this is not tested
  def jsonExprNEW(op: String, aa: String, bb: String) = {
//    val ai = new JSONObject (aa)
    val bi = new JSONObject(bb)
    val res = new JSONObject (aa)

//    ai.foreach { t =>
//      res.put(t._1, t._2)
//    }

    bi.keySet.toArray.foreach { kk =>
      val k = kk.toString
      val bv = bi.get(k)
      if (res.has(k)) {
        val ax = res.get(k)
        ax match {
          case al: JSONArray => {
            bv match {
              case bll:JSONArray =>
                res.put(k, JSONArray(al.list ::: bll.list))
              case _            =>
                res.put(k, JSONArray(al.list ::: bv :: Nil))
            }
          }
          case m: JSONObject => {
            val mres = new JSONObject(m, m.keySet.toArray(Array[String]()))
//            m.foreach { t =>
//              mres.put(t._1.toString, t._2)
//            }
            res.put(k, mres)
          }
          case y @ _ => res.put(k, y.toString + bv.toString)
        }
      } else res.put(k, bv)
    }
//    razie.js.tojsons(res.toMap)
    res.toString
//todo
//     // preserve type of first object if second is same or untyped
//    val t = if (!bb.cType.hasSchema || bb.cType.schema == aa.cType.schema) aa.cType else WTypes.wt.JSON

  }

  def jsonExprMap(op: String, aa: PValue[_], bb: PValue[_]) = {
    val ai = aa.asJson
    val bi = bb.asJson

    // don't copy for +=
    val res =
      if ("+=" == op.trim) {
      ai.asInstanceOf[HashMap[String, Any]]
    } else {
        val res = new mutable.HashMap[String, Any]()
        ai.foreach { t =>
          res.put(t._1, t._2)
        }
        res
      }

    if ("+" == op.trim || "+=" == op.trim) {
      bi.foreach { t =>
        val k = t._1
        val bv = t._2
        if (res.contains(k)) {
          val ax = res(k)
          ax match {

            case al: collection.Seq[_] => {
              bv match {
                // add lists
                case bll: collection.Seq[_] => {
                  val l = new ListBuffer[Any]
                  l.appendAll(al)
                  l.appendAll(bll)
                  res.put(k, l)
                }
                case _ => {
                  val l = new ListBuffer[Any]
                  l.appendAll(al)
                  l.append(bv)
                  res.put(k, l)
                }
              }
            }

            case m: collection.Map[_, _] if bv.isInstanceOf[collection.Map[_, _]] => {
              // merge maps
              val mres = new mutable.HashMap[String, Any]()
              m.foreach { t =>
                mres.put(t._1.toString, t._2)
              }
              res.put(k, mres)
            }

            case y@_ => {
              (y, bv) match {
//              case (a:Int, b:Int) => res.put(k, a+b)
                // todo this will concatenate strings instead of merging maps
                case _ => res.put(k, bv)
//              case _ => res.put(k, y.toString + bv.toString)
                // todo this will concatenate strings instead of merging maps
              }
            }
          }
        } else res.put(k, bv)
      }
    } else if ("-" == op.trim) {
      bi.foreach { t =>
        val k = t._1
        res.remove(k)
      }
    }

//    val s = razie.js.tojsons(res.toMap)
    // preserve type of first object if second is same or untyped
    val t = if (!bb.cType.hasSchema || bb.cType.schema == aa.cType.schema) aa.cType else WTypes.wt.JSON
    PValue(res, t)//.withStringCache(s)
  }

  def jsonExpr(op: String, aa: String, bb: String) = {
    val ai = try {
      razie.js.parse(aa)
    } catch {
      case t:Throwable => throw new DieselExprException(s"Parm ${aa} can't be parsed to JSON: " + t.toString).initCause(t)
    }
    val bi = try {
      razie.js.parse(bb)
    } catch {
      case t: Throwable => throw new DieselExprException(
        s"Parm ${bb} can't be parsed to JSON: " + t.toString).initCause(t)
    }
    val res = new mutable.HashMap[String, Any]()

    ai.foreach { t =>
      res.put(t._1, t._2)
    }

    if ("+" == op.trim) {
      bi.foreach { t =>
        val k = t._1
        val bv = t._2
        if (res.contains(k)) {
          val ax = res(k)
          ax match {

            case al: collection.Seq[_] => {
              bv match {
                // add lists
                case bll: collection.Seq[_] => {
                  val l = new ListBuffer[Any]
                  l.appendAll(al)
                  l.appendAll(bll)
                  res.put(k, l)
                }
                case _ => {
                  val l = new ListBuffer[Any]
                  l.appendAll(al)
                  l.append(bv)
                  res.put(k, l)
                }
              }
            }

            case m: collection.Map[_, _] if bv.isInstanceOf[collection.Map[_, _]] => {
              // merge maps
              val mres = new mutable.HashMap[String, Any]()
              m.foreach { t =>
                mres.put(t._1.toString, t._2)
              }
              res.put(k, mres)
            }

            case y@_ => {
              (y, bv) match {
//              case (a:Int, b:Int) => res.put(k, a+b)
                // todo this will concatenate strings instead of merging maps
                case _ => res.put(k, bv)
//              case _ => res.put(k, y.toString + bv.toString)
                // todo this will concatenate strings instead of merging maps
              }
            }
          }
        } else res.put(k, bv)
      }
    } else if ("-" == op.trim) {
      bi.foreach { t =>
        val k = t._1
        res.remove(k)
      }
    }

    val s = razie.js.tojsons(res.toMap)
    PValue(res, WTypes.wt.JSON).withStringCache(s)
  }

  override def getType = a.getType
}

