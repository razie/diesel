/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import com.mongodb.casbah.Imports.ObjectId
import java.net.URLEncoder
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.{Date, TimeZone}
import org.joda.time.DateTime
import org.joda.time.chrono.ISOChronology
import org.joda.time.format.ISODateTimeFormat
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.exec.EEFunc
import razie.diesel.engine.nodes.{EInfo, EMap, EMock, EMsg, ERule, EVal, HasPosition}
import razie.diesel.engine.{AstKinds, DieselAppContext, DomAst, EContent}
import razie.tconf.EPos
import razie.wiki.{Enc, EncUrl}
import scala.collection.mutable.{HashMap, ListBuffer}

/** a "function-like" call:
  * - built-in functions,
  * - msg functions (exec'd in same engine, sync)
  * - domain functions / class members
  */
case class AExprFunc(val expr: String, parms: List[RDOM.P]) extends Expr {

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).calculatedValue
  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {

    // calc first parm value
    def aParm(op: Option[P]) = {
      op
          .flatMap { p =>
            // maybe the first parm is the accessor expression (lambda parm like)
            val pv = if (p.expr.isEmpty && !p.hasCurrentValue) {
              if (p.name.contains(".") || p.name.contains("["))
                (new SimpleExprParser).parseIdent(p.name).flatMap(_.tryApplyTyped(v))
              else
                ctx.getp(p.name) // don't care about names, just get the first parm and evalueate
            } else {
              // nope - it's just a normal parm=expr
              Some(p)
            }
            pv
          }.map(_.calculatedP)
    }

    // calc first parm value
    def optParm = aParm(parms.headOption)

    def firstParm = aParm(parms.headOption)

    def secondParm = aParm(parms.drop(1).headOption)

    def thirdParm = aParm(parms.drop(2).headOption)

    def withArray (f:(Option[String],Seq[Any]) => P) = {
      val x = firstParm.map { p =>
        val av = p.calculatedP
        p.calculatedTypedValue.cType.name match {
          case WTypes.ARRAY => {
            val elementType = av.calculatedTypedValue.cType.wrappedType
            val arr = av.calculatedTypedValue.asArray
            if(arr.size <= 0) throw new DieselExprException(s"Empty array... for ${expr} ")
            val res= f.apply(elementType, arr)
            res
          }
        }
      }
      if(x.isEmpty) {
            throw new DieselExprException(s"No arguments for $expr")
          }
      x.get
    }


      // is it built-in or generic?
    expr match {

      case "uuid" => {
        // todo singleton
        val x = new ObjectId().toString

        P.fromTypedValue("", x, WTypes.wt.STRING)
      }

      case "now" => {
        // todo singleton
        val now = new DateTime(ISOChronology.getInstanceUTC()) //DateTime.now()
        val ts = now.toString(WTypes.DATE_FORMAT)

        // this gave local, not iso
//        val tsFmtr = DateTimeFormatter.ofPattern(WTypes.DATE_FORMAT)
//        val nw = LocalDateTime.now
//        val ts2 = tsFmtr.format(nw)

        P.fromTypedValue("", ts, WTypes.wt.DATE)
      }

      case "today" => {
        // todo singleton
        val now = new DateTime(ISOChronology.getInstanceUTC())
        val ts = now.toString(WTypes.DATE_ONLY_FORMAT)

        P.fromTypedValue("", ts, WTypes.wt.DATE)
      }

      case "hashcode" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need one argument.")
        }.calculatedValue

        P.fromTypedValue("", av.hashCode, WTypes.wt.NUMBER)
      }

      case "cmp" => {
        // cmp (op=">", a, b) : Boolean

        val av = firstParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }

        val cv = thirdParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }

        val res = BCMP2(bv.valExpr, av.trim, cv.valExpr).apply("")

        P.fromTypedValue("", res, WTypes.wt.BOOLEAN)
      }

      case "sprintf" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedTypedValue.asJavaObject

        val res = String.format(av, bv)
        P.fromSmartTypedValue("", res)
      }

      case "matches" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need two arguments.")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException("Need two arguments.")
        }.calculatedValue

        val groups = EContent.extractRegexParms(bv, av)
        groups.foreach(t => ctx.put(new P(t._1, t._2)))

        P.fromTypedValue("", av.matches(bv), WTypes.wt.BOOLEAN)
      }

      case "replaceAll" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val cv = thirdParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        P.fromTypedValue("", av.replaceAll(bv, cv), WTypes.wt.STRING)
      }

      case "replaceFirst" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        val cv = thirdParm.getOrElse {
          throw new DieselExprException("Need three arguments.")
        }.calculatedValue

        P.fromTypedValue("", av.replaceFirst(bv, cv), WTypes.wt.STRING)
      }

      case "enc" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need one arguments.")
        }.calculatedValue

        P.fromTypedValue("", Enc(av), WTypes.wt.STRING)
      }

      case "trim" => {
        val av = firstParm.getOrElse {
          throw new DieselExprException("Need one arguments.")
        }.calculatedValue

        P.fromTypedValue("", av.trim(), WTypes.wt.STRING)
      }

      case "rangeList" => {
        val f = firstParm
            .getOrElse {
              throw new DieselExprException(s"No first argument for $expr")
            }
        val s = secondParm
            .getOrElse {
              throw new DieselExprException(s"No second argument for $expr")
            }
        val av = f.calculatedTypedValue.asLong.toInt
        val bv = s.calculatedTypedValue.asLong.toInt

        P.of("", Range(av, bv).toList)
      }

      case "sizeOf" => {
        firstParm.map { p =>
          val pv = p.calculatedTypedValue

          if (pv.contentType == WTypes.ARRAY) {
            val sz = pv.asArray.size
            P.fromTypedValue("", sz, WTypes.wt.NUMBER)
          } else if (pv.contentType == WTypes.JSON) {
            val sz = pv.asJson.size
            P.fromTypedValue("", sz, WTypes.wt.NUMBER)
          } else if (pv.contentType == WTypes.STRING) {
            val sz = pv.asString.length
            P.fromTypedValue("", sz, WTypes.wt.NUMBER)
          } else if (pv.contentType == WTypes.UNKNOWN || pv.contentType == WTypes.UNDEFINED) {
            P.fromTypedValue("", 0, WTypes.wt.NUMBER)
          } else {
            throw new DieselExprException(
              "Not sizeable type: " + p.name + " is:" + pv.toString
            )
          }
        }
            .getOrElse(
              // more failure resistant
              P.fromTypedValue("", 0, WTypes.wt.NUMBER)
            )
      }

      case "toUpper" => {
        firstParm.map { p =>
          val pv = p.calculatedValue
          P.fromTypedValue("", pv.toUpperCase)
        }.getOrElse(
          // todo could be unknown?
          throw new DieselExprException(s"No arguments for $expr")
        )
      }

      case "toLower" => {
        firstParm.map { p =>
          val pv = p.calculatedValue
          P.fromTypedValue("", pv.toLowerCase)
        }.getOrElse(
          // todo could be unknown?
          throw new DieselExprException(s"No arguments for $expr")
        )
      }

      case "typeOf" => {
        firstParm.map { p =>
          val pv = p.calculatedTypedValue
          new P("", pv.cType.getClassName, WTypes.wt.STRING).withValue(pv.contentType, WTypes.wt.STRING)
        }.getOrElse(
          // todo could be unknown?
          throw new DieselExprException(s"No arguments for $expr")
        )
      }

      case "urlencode" => {
        firstParm.map { p =>
          val p1 = p.calculatedValue
          val pv = URLEncoder.encode(p1, "UTF8")
          new P("", pv, WTypes.wt.STRING).withValue(pv, WTypes.wt.STRING)
        }.getOrElse(
          throw new DieselExprException(s"No arguments for $expr")
        )
      }

      case "base64encode" => {
        firstParm.map { p =>
          val p1 = p.calculatedValue
          import org.apache.commons.codec.binary.Base64

          val res = new Base64(false).encode(p1.getBytes)
          val pv = new String(res).replaceAll("\n", "").replaceAll("\r", "")
          new P("", pv, WTypes.wt.STRING).withValue(pv, WTypes.wt.STRING)
        }.getOrElse(
          throw new DieselExprException(s"No arguments for $expr")
        )
      }

      case "split" => {
        val help = ": split(string, regex) argument order matters! "
        val av = firstParm.getOrElse {
          throw new DieselExprException(s"Need two arguments $help")
        }.calculatedValue

        val bv = secondParm.getOrElse {
          throw new DieselExprException(s"Need two arguments $help")
        }.calculatedValue

          P.fromTypedValue("", av.split(bv).toSeq, WTypes.wt.ARRAY)
      }

      case "accessor" => {
        firstParm.map { p =>
          val pStart = p.calculatedP

          // parse second parm as aexprident
          secondParm
              .orElse {
                throw new DieselExprException(s"No second argument for $expr")
              }
              .flatMap { p =>
                val pv = if (p.expr.isEmpty && !p.hasCurrentValue) {
                  new P("", p.name)
                } else {
                  // nope - it's just a normal parm=expr
                  p.calculatedP // need to do this to not affect the original with cached value
                }

                val p1 = pv.calculatedValue
                val pa =
                  (new SimpleExprParser).parseIdent(p1).flatMap(_.tryApplyTypedFrom(Some(pStart)))
                pa
              }.getOrElse(
            P.undefined(Diesel.PAYLOAD)
          )
        }.getOrElse(
          throw new DieselExprException(s"No first argument for $expr")
        )
      }

      case "flatten" => {

        // the flat part of flatmap

        firstParm.map { p =>
          val av = p.calculatedP
          p.calculatedTypedValue.cType.name match {
            case WTypes.ARRAY => {
              val elementType = av.calculatedTypedValue.cType.wrappedType

              val arr = av.calculatedTypedValue.asArray

              val resArr = arr.flatMap { x =>
                if (x.isInstanceOf[Seq[Any]])
                  x.asInstanceOf[Seq[Any]]
                else if (x.isInstanceOf[ListBuffer[Any]])
                  x.asInstanceOf[ListBuffer[Any]].toSeq
                else
                  throw new DieselExprException("Can't flatten element: " + x)
              }

              val finalArr = resArr
              P.fromTypedValue("", finalArr, WTypes.wt.ARRAY)
            }

            case _ => throw new DieselExprException("Can't do flatten on: " + av)
          }
        }
      }.getOrElse(
        throw new DieselExprException(s"No arguments for $expr")
      )

      case "math.max" => {
        withArray{(ctype, arr) => {
          var acc = arr.head
          var af = acc.toString.toFloat
          arr.fold(arr.head) { (a, b) =>
            val bf = b.toString.toFloat
            if(af < bf) {
              acc = b
              af = bf
            }
          }
          P.fromTypedValue("", acc, WTypes.wt.NUMBER)
        }}
      }

      case "math.min" => {
        withArray{(ctype, arr) => {
          var acc = arr.head
          var af = acc.toString.toFloat
          arr.tail.fold(arr.head) { (a, b) =>
            val bf = b.toString.toFloat
            if(af > bf) {
              acc = b
              af = bf
            }
          }
          P.fromTypedValue("", acc, WTypes.wt.NUMBER)
        }}
      }

      case "math.average" => {
        withArray{(ctype, arr) => {
          var as = arr.head.toString
          var af = as.toFloat
          arr.tail.fold(arr.head) { (a, b) =>
            val bs = b.toString
            val bf = bs.toFloat
            af = af + bf
          }
          P.fromTypedValue("", af / arr.size, WTypes.wt.NUMBER)
        }}
      }

      case "math.sum" => {
        withArray{(ctype, arr) => {
          var as = arr.head.toString
          var af = as.toFloat
          var isf = as contains "."
          arr.tail.fold(arr.head) { (a, b) =>
            val bs = b.toString
            val bf = bs.toFloat
            af = af + bf
            isf = isf || (bs contains ".")
          }
          P.fromTypedValue("", (if(isf) as else af.toLong), WTypes.wt.NUMBER)
        }}
      }

      case "toMillis" => {

        // todo when eliminating "dflt" from P we could just:
//        val ad = firstParm.get.calculatedTypedValue.asDate
        val as = firstParm.get.calculatedValue
        val tsFmtr = WTypes.ISO_DATE_PARSER
        val ad = LocalDateTime.from(tsFmtr.parse(as))

        P.fromTypedValue("", ad.toInstant(ZoneOffset.UTC).toEpochMilli, WTypes.wt.NUMBER)
      }

      case _ => {

        // must be in form x...y.func
        val PAT = """([\w.]+)[./](\w+)""".r
        val PAT(ee, aa) = expr
        val parent = EMsg(ee, aa, List())

        // is there a spec for it in current domain?
        val spec = ctx.root.domain.flatMap {
          _.moreElements.collectFirst {
            case s: EMsg if s.ea == expr => Some(s)
            case s: ERule if s.e.ea == expr => Some(s.e.asMsg)
            case s: EMock if s.rule.e.ea == expr => Some(s.rule.e.asMsg)
          }
        }

        // or is it defined as a func in domain?
        val func = ctx.root.domain.flatMap {
          _.funcs.get (expr)
        }

        // todo this is pos of parent - be more precise, get pos of expr
        val pos: Option[EPos] =
          ctx.asInstanceOf[SimpleECtx].curNode.flatMap { n =>
            if (n.value.isInstanceOf[HasPosition])
              n.value.asInstanceOf[HasPosition].pos
            else None
          } orElse (None)


        val msg = EMsg(ee, aa, EMap.sourceAttrs(parent, parms, spec.map(_.get.attrs)))
        val ast = DomAst(msg, AstKinds.RECEIVED)

        // root extra node with more debug info about what this is
        val root = DomAst("SYNC-"+expr, AstKinds.ROOT).withDetails("(inline func)")
        root.appendAllNoEvents(List(ast))

        spec.flatMap { msgSpec =>

          ctx.root.engine.flatMap{engine=>
            val newe = DieselAppContext.mkEngine(
              engine.dom,
              root,
              engine.settings,
              engine.pages,
              "SYNC-"+expr
            )

            // leave a marker
            if (ctx.isInstanceOf[SimpleECtx])
              ctx.asInstanceOf[SimpleECtx].curNode.foreach { n =>
                ctx.root.engine.foreach(_.evAppChildren(n, DomAst(EInfo(
                  s"""SYNC-engine ${newe.href} : ${msg.toString}""").withPos(pos)
                )))
              }

            val level =
              if (ctx.isInstanceOf[SimpleECtx])
                ctx.asInstanceOf[SimpleECtx].curNode.flatMap(n =>
                  ctx.root.engine.map(_.findLevel(n))
                ).getOrElse(0)
              else
                0

            // a message with this name found, call it sync

            // NOTE - need to use ctx to access values in context etc, i..e map (x => a.b(x))
            val res = newe.processSync(ast, level, ctx, initial = true)

            root.setKinds(AstKinds.TRACE)
            root.kind = AstKinds.SUBTRACE

            // todo maybe ensure all the nodes in subtrace are done so the big engine doesn't reprocess something??

            // save the trace in the main tree
            if (ctx.isInstanceOf[SimpleECtx])
              ctx.asInstanceOf[SimpleECtx].curNode.foreach(_.appendAllNoEvents(
                List(root)
              ))

            res
          }
        } orElse func.map {f=>
            // todo add more ast info?
          EEFunc.exec(msg, f)
        } getOrElse {
          throw new DieselExprException("Function/Message not found OR no payload resulted: " + expr)
        }
      }
    }

  }

  override def toDsl = expr + "(" + parms.mkString(",") + ")"
  override def toHtml = tokenExprValue(toDsl)
}


