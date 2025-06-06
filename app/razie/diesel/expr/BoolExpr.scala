/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import java.time.LocalDateTime
import razie.clog
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.EContent
import scala.util.Try

/** details about the result: what values were involved? */
case class BExprResult (value:Boolean, a:Option[P] = None, b:Option[P] = None) {
  implicit def toBool : Boolean = value

  def unary_! = this.copy(value = !value)
  def || (other:BExprResult) = copy(value = this.value || other.value)
  def && (other:BExprResult) = copy(value = this.value && other.value)
}

/** boolean expressions */
abstract class BoolExpr(e: String) extends Expr with HasDsl {
  override def getType = WTypes.wt.BOOLEAN

  /** specific typed apply */
  def bapply(v: Any)(implicit ctx: ECtx):BExprResult

  // generic glue as an expression
  override def expr = e
  override def apply(v: Any)(implicit ctx: ECtx) = bapply(v).value
  override def applyTyped(v: Any)(implicit ctx: ECtx) = P.fromTypedValue("", bapply(v).value, WTypes.wt.BOOLEAN)


  override def toDsl = e
}

/** boolean expression block - show the () when printing */
case class BExprBlock(a: BoolExpr) extends BoolExpr("") {
  override def bapply(e: Any)(implicit ctx: ECtx) = a.bapply(e)
  override def toDsl = "(" + a.toDsl + ")"
}

/** negated boolean expression */
case class BCMPNot(a: BoolExpr) extends BoolExpr("") {
  override def bapply(e: Any)(implicit ctx: ECtx) = !a.bapply(e)
  override def toDsl = "NOT (" + a.toDsl + ")"
}

/** const boolean expression */
case class BCMPConst(a: String) extends BoolExpr(a) {
  override def bapply(e: Any)(implicit ctx: ECtx) = BExprResult(a == "true")
}

/** and/or - composed boolean expression */
case class BCMPAndOr(a: BoolExpr, op: String, b: BoolExpr)
    extends BoolExpr("(" + a.toDsl + " " + op + " " + b.toDsl + ")") {
  override def bapply(in: Any)(implicit ctx: ECtx) = {
    lazy val av = a.bapply(in)
    lazy val bv = b.bapply(in)

    op match {
      case "xor" => BExprResult((av.value || bv.value) && !(av.value && bv.value))
      case "||" | "or" => BExprResult(av.value || bv.value)   // (av xor bv) xor ((av xor true) xor bv)
      case "&&" | "and" => BExprResult(av.value && bv.value)
      case _ => {
        clog << s"[ERR BoolOperator $op UNKNOWN!!!] as in $a $op $b"
        BExprResult(false)
      }
    }
  }

  override def toString = a.toString + " " + op + " " + b.toString
}

/** bool cmp operator simple boolean expression */
case class BCMP2(a: Expr, op: String, b: Expr)
    extends BoolExpr("("+a.toDsl + " " + op + " " + b.toDsl+")") {

  override def bapply(in: Any)(implicit ctx: ECtx): BExprResult = {
    var oap : Option[P] = None
    var obp : Option[P] = None

    def ap = {
      if(oap.isEmpty) {
        oap = Some(a.applyTyped(in))
      }
      oap.get
    }

    def bp = {
      if(obp.isEmpty) {
        obp = Some(b.applyTyped(in))
      }
      obp.get
    }

    def as = ap.calculatedValue

    def arrayOf(ap:P):Seq[Any] = {
        val av = ap.calculatedTypedValue
        val al = try {
          av.asArray
        } catch {
          case t: Throwable => throw new DieselExprException(
            s"Parm ${ap} can't be typecast to Array: " + t.toString).initCause(t)
        }
      al
      }

    /** ap is in bp, bp is an array */
    def isin(ap:P, bp:P) = {
      val av = ap.calculatedTypedValue
      val bl = arrayOf(bp)

      bl.contains(av.value)
    }


    try {
      val ops = if(op.trim.startsWith("""${""")) CExpr(op.trim).apply(in) else op.trim

      val resBool = (a, b) match {
        case (CExpr(aa, WTypes.wt.NUMBER), CExpr(bb, WTypes.wt.NUMBER)) => {
          val as = aa.toString
          val bs = bb.toString

          cmpNums(as, bs, ops)
        }

        case _ => {
          def expr_id(ex: Expr) =
            if (ex.isInstanceOf[AExprIdent]) ex.asInstanceOf[AExprIdent].expr
            else ""

          def expr_is(ex: Expr, s: String) =
            ex.isInstanceOf[AExprIdent] && s == ex
                .asInstanceOf[AExprIdent]
                .expr
                .toLowerCase

          def b_is(s: String) = expr_is(b, s)

          def isNum(p: P) = {
            p.ttype.name == WTypes.NUMBER || p.value.exists(_.cType.name == WTypes.NUMBER)
          }

          def isDate(p: P) = {
            p.ttype.name == WTypes.DATE || p.value.exists(_.cType.name == WTypes.DATE)
          }

          def checkIS = { // is number or is date or is string etc
            /* x is TYPE (single id, not a value) */
            if (b.isInstanceOf[AExprIdent] &&
                b.asInstanceOf[AExprIdent].rest.isEmpty &&
                ctx.get(b.asInstanceOf[AExprIdent].start).isEmpty) /* not a known value */ {
              val bs = b.asInstanceOf[AExprIdent].expr

              // WTypes can check static primitive types - the domain will check classes
              WTypes.isSubtypeOf(a.getType, bs) ||
                  WTypes.isSubtypeOf(ap.calculatedTypedValue.contentType, bs) ||
                  WTypes.isSubtypeOf(as, bp.calculatedValue) ||
                  (a.getType.name == WTypes.JSON || a.getType.name == WTypes.OBJECT) &&
                      ctx.root.domain.exists(_.isA(bs, a.getType.schema))
            }
            else {
              /* if type expr not known, then behave like equals */
              val bs = b.applyTyped(in).calculatedValue
              as == bs
            }
          }

          val cmpop = ops match {
            case "?=" | "==" | "!=" | "~=" | "~path" | "like" | "<=" | ">=" | "<" | ">" => true
            case _ => false
          }

          // if one of them is number, don't care about the other... could be a string containing a num...
          if (cmpop && (isNum(ap) || isNum(bp))) {
            return BExprResult(
              cmpNums(ap.calculatedValue, bp.calculatedValue, ops),
              oap,
              obp
            )
          }

          if (cmpop && (isDate(ap) && isDate(bp))) {
            return BExprResult(
              cmpDates(ap.calculatedTypedValue.asDate, bp.calculatedTypedValue.asDate, ops),
              oap,
              obp
            )
          }

          ops match {
            case "?=" => a(in).toString.length >= 0 // anything with a default
            case "!=" => a(in) != b(in)
            case "~=" | "matches" => a(in).toString matches b(in).toString
            case "~path" => EContent.extractPathParms(a(in).toString, b(in).toString)._1

            case "<=" => a(in).toString <= b(in).toString
            case ">=" => a(in).toString >= b(in).toString
            case "<" => a(in).toString < b(in).toString
            case ">" => {
              a(in).toString > b(in).toString
            }

            case "contains" if ap.ttype == WTypes.wt.ARRAY => isin(bp, ap)
            case "containsNot" if ap.ttype == WTypes.wt.ARRAY => !isin(bp, ap)
            case "contains" if ap.ttype == WTypes.wt.JSON => ap.calculatedTypedValue.asJson.contains(bp.calculatedValue)
            case "containsNot" if ap.ttype == WTypes.wt.JSON => ! ap.calculatedTypedValue.asJson.contains(bp.calculatedValue)
            case "contains" => a(in).toString contains b(in).toString
            case "containsNot" => !(a(in).toString contains b(in).toString) // todo deprecate

            //todo does this even work?
            case "not" if b.toString == "contains" => !(a(in).toString contains b(in).toString)

            // THESE CANNOT CHANGE...

            case "is" if b.toString == "null" => ap.ttype == WTypes.wt.UNDEFINED
            case "not" if b.toString == "null" => ap.ttype != WTypes.wt.UNDEFINED
            case "is" if b.toString == "defined" => ap.ttype != WTypes.wt.UNDEFINED
            case "not" if b.toString == "defined" => ap.ttype == WTypes.wt.UNDEFINED //as.length <= 0

            case "is" if b.toString == "nzlen" => ap.ttype != WTypes.wt.UNDEFINED && as.length > 0 && as.trim != "null"
            case "not" if b.toString == "nzlen" => ap.ttype == WTypes.wt.UNDEFINED || as.length <= 0 || as.trim ==
                "null"

            case "is" if b.toString == "empty" =>
              if (ap.calculatedTypedValue.contentType == WTypes.JSON)
                ap.calculatedTypedValue.asJson.isEmpty
              else if (ap.calculatedTypedValue.contentType == WTypes.ARRAY)
                ap.calculatedTypedValue.asArray.isEmpty
              else
              /*ap.ttype != WTypes.UNDEFINED &&*/ as.length == 0 // undefined is empty !

            case "not" if b.toString == "empty" =>
              if (ap.calculatedTypedValue.contentType == WTypes.JSON)
                ap.calculatedTypedValue.asJson.nonEmpty
              else if (ap.calculatedTypedValue.contentType == WTypes.ARRAY)
                ap.calculatedTypedValue.asArray.nonEmpty
              else
              /*ap.ttype != WTypes.UNDEFINED &&*/ as.length != 0 // undefined is not empty !

            case "is" if b.toString == "undefined" => ap.ttype == WTypes.wt.UNDEFINED
            case "not" if b.toString == "undefined" => ap.ttype != WTypes.wt.UNDEFINED


            case "is" if b_is("number") => as.matches("[0-9.]+")
            case "not" if b_is("number") => as.matches("[0-9.]+")

            case "is" if b_is("boolean") =>
              a.getType == WTypes.wt.BOOLEAN || ap.calculatedTypedValue.contentType == WTypes.BOOLEAN

            // string is special, it's the default when not known but some value present
            case "is" if b_is("string") =>
              ap.calculatedTypedValue.contentType == WTypes.STRING ||
                  ap.calculatedTypedValue.contentType == "" &&
                      ap.calculatedTypedValue.value != null
            case "not" if b_is("string") =>
              !(
                  ap.calculatedTypedValue.contentType == WTypes.STRING ||
                      ap.calculatedTypedValue.contentType == "" &&
                          ap.calculatedTypedValue.value != null
                  )

            case "is" if b_is("bytes") =>
              ap.calculatedTypedValue.contentType == WTypes.BYTES
            case "not" if b_is("bytes") =>
              !(ap.calculatedTypedValue.contentType == WTypes.BYTES)

            case "is" if b_is("json") || b_is("object") =>
              ap.calculatedTypedValue.contentType == WTypes.JSON
            case "not" if b_is("json") || b_is("object") =>
              !(ap.calculatedTypedValue.contentType == WTypes.JSON)

            case "is" if b_is("array") => {
              val av = ap.calculatedTypedValue
              av.contentType == WTypes.ARRAY
            }
            case "not" if b_is("array") => {
              val av = ap.calculatedTypedValue
              !(av.contentType == WTypes.ARRAY)
            }

            case "is" if b_is("class") => {
              val cname = expr_id(a)
              ctx.domain.exists(_.classes.contains(cname))
              // nice to have class values
//              val av = ap.calculatedTypedValue
//              av.contentType == WTypes.CLASS
            }
            case "not" if b_is("class") => {
              val cname = expr_id(a)
              !(ctx.domain.exists(_.classes.contains(cname)))
              // nice to have class values
//              val av = ap.calculatedTypedValue
//              av.contentType == WTypes.CLASS
            }

//            case "is" if b_is(WTypes.MSG) => {
//              val cname = expr_id(a)
//              ctx.domain.exists(_.moreElements.contains(x => x.isInstanceOf[EMsg] && x.asInstanceOf[EMsg].ea ==
//              cname))
            // nice to have class values
//              val av = ap.calculatedTypedValue
//              av.contentType == WTypes.CLASS
//            }

            case "is" if b_is(WTypes.FUNC) => {
              val cname = expr_id(a)
              ctx.domain.exists(_.funcs.contains(cname))
              // nice to have class values
//              val av = ap.calculatedTypedValue
//              av.contentType == WTypes.CLASS
            }
            case "not" if b_is(WTypes.FUNC) => {
              val cname = expr_id(a)
              !(ctx.domain.exists(_.funcs.contains(cname)))
              // nice to have class values
//              val av = ap.calculatedTypedValue
//              av.contentType == WTypes.CLASS
            }

            // yeah only if both are known to be arrays - otherwise can't compare as arrays anyways, eh?
            case "is" | "==" if bp.ttype == WTypes.wt.ARRAY && ap.ttype == WTypes.wt.ARRAY => {
              val al = arrayOf(ap)
              val bl = arrayOf(bp)

              if (al.size != bl.size) {
                false
              } else {
                al.zip(bl).foldLeft(true)((a, b) => a && (b._1 == b._2))
              }
            }

//            case "is" | "==" if bp.ttype == WTypes.wt.BOOLEAN || ap.ttype == WTypes.wt.BOOLEAN => {
//              ap.calculatedTypedValue.asBoolean == bp.calculatedTypedValue.asBoolean
//            }

            case "in" if bp.ttype == WTypes.wt.ARRAY => isin(ap, bp)

            case "notIn" if bp.ttype == WTypes.wt.ARRAY => !isin(ap, bp)
            case "not in" if bp.ttype == WTypes.wt.ARRAY => !isin(ap, bp)

            case "is" | "ofType" => checkIS
            case "xNot" => !checkIS

            case "not" => a(in).toString.length > 0 && a(in) != b(in)

            case "==" => a(in) == b(in)

            case _ if ops.trim == "" => {
              // no op - look for boolean parms?
              ap.ttype == WTypes.wt.BOOLEAN && "true" == a(in).toString
            }

            case _ => {
              clog << s"[ERR (a) Operator $ops UNKNOWN!!!] as in $a $op $b";
              false
            }
          }
        }
      }

      BExprResult(resBool, oap, obp)

    } catch {
      case t: Throwable if !t.isInstanceOf[DieselExprException] => throw new DieselExprException("BoolExpr exception: " + t.toString).initCause(t)
    }
  }

  private def cmpNums(as: String, bs: String, op: String): Boolean = {
    Try {
      val ai = {
        if (as.contains(".")) as.toDouble
        else as.toLong
      }
      val bi = {
        if (bs.contains(".")) bs.toDouble
        else bs.toLong
      }

      op match {
        case "?=" => true
        case "==" => ai == bi
        case "~=" => ai == bi
        case "!=" => ai != bi
        case "<=" => ai <= bi
        case ">=" => ai >= bi
        case "<" => ai < bi
        case ">" => ai > bi
        case "is" => ai == bi
        case "not" => ai != bi
        case _ => {
          clog << s"[ERR Operator $op UNKNOWN!!!] as in $ai $op $bi";
          false
        }
      }
    } getOrElse (false)
  }

  private def cmpDates(ai: LocalDateTime, bi: LocalDateTime, op: String): Boolean = {
    Try {
      val cmp = (ai compareTo bi)
      op match {
        case "?=" => true
        case "==" => (cmp) == 0
        case "~=" => (cmp) == 0
        case "!=" => (cmp) != 0
        case "<=" => (cmp) <= 0
        case ">=" => (cmp) >= 0
        case "<" => (cmp) < 0
        case ">" => (cmp) > 0
        case "is" => (cmp) == 0
        case "not" => (cmp) != 0
        case _ => {
          clog << s"[ERR Operator $op UNKNOWN!!!] as in $ai $op $bi";
          false
        }
      }
    } getOrElse (false)
  }
}

/** single term bool expression */
case class BCMPSingle(a: Expr) extends BoolExpr(a.toDsl) {

  def toBoolean(in: P)(implicit ctx: ECtx) = {
    a.getType.name match {

      case WTypes.NUMBER =>
        throw new DieselExprException("Found :number expected :boolean")

      case WTypes.BOOLEAN => "true" == in.currentStringValue

      case _ if "true" == in.currentStringValue => true

      case _ if "false" == in.currentStringValue => false

      case s @ _ => {
        // it was some parameter that apply() evaluated

        in.ttype.name match {

          case WTypes.NUMBER =>
            throw new DieselExprException(
              "Found Number expected Boolean"
            )

          case WTypes.BOOLEAN => "true" == in.currentStringValue

          case _ if "true" == in.currentStringValue => true

          case _ if "false" == in.currentStringValue => false

          case WTypes.UNDEFINED => false // todo is this kosher?

          case s @ _ => {
            val t = if (s.length > 0) s else ":unknown"
            clog << (s"Found $t expected Boolean")
            throw new DieselExprException(
              s"Found $t expected Boolean in expr: (${a.toDsl} for input: $in)"
            )
          }
        }
      }
    }
  }

  override def bapply(in: Any)(implicit ctx: ECtx) = {
    val ap = a.applyTyped(in)
    BExprResult(toBoolean(ap), Some(ap), None)
  }
}

/** just a constant expr */
object BExprFALSE extends BoolExpr("FALSE") {
  override def bapply(e: Any)(implicit ctx: ECtx): BExprResult = BExprResult(false)

  override def toDsl = "FALSE"
}
