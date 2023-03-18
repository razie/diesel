/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import org.json.JSONObject
import razie.diesel.dom.RDOM.{P, PValue}
import razie.diesel.dom._
import razie.diesel.engine.exec.EESnakk
import scala.collection.mutable.HashMap

/** a json document block:
  * - you may or may not use quotes for names
  * -
  */
case class JBlockExpr(ex: List[(String, Expr)], schema:Option[String]=None) extends Expr {
  override val expr = "{" + ex.map(t=>t._1 + ":" + t._2.toString).mkString(",") + "}"

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).currentStringValue

  override def applyTyped(v: Any)(implicit ctx: ECtx) = {
    // todo this can be way faster for a few types, like Array - $send ctx.set (state281 = {source: [0,1,2,3,4,5],
    //  dest: [], aux: []})
//    val orig = template(expr)

    val origMap = ex
        .map(t => (DomInventories.exname(t._1), t._2)) // expand interpolated string
        .map(t => (t._1, t._2.applyTyped(v)))


   // if there's a class definition and it has defaults, add them:
   // todo could speed up by caching if has defaults in the class spec, from parsing
   val c = schema
       .flatMap(cls=> ctx.domain.flatMap(_.classes.get(cls)))

    val newMap = DomInventories.defaultClassAttributes(origMap, c)

    // todo why am i building the string and then parse it when I have the list of P already ????

    val orig = (origMap ::: newMap)
        .map(t => (t._1, t._2 match {
          case p@P(n, d, WTypes.wt.NUMBER, _, _, Some(PValue(i: Int, _)), _) => i
          case p@P(n, d, WTypes.wt.NUMBER, _, _, Some(PValue(i: Long, _)), _) => i
          case p@P(n, d, WTypes.wt.NUMBER, _, _, Some(PValue(i: Double, _)), _) => i

          case p@P(n, d, WTypes.wt.BOOLEAN, _, _, Some(PValue(b: Boolean, _)), _) => b

          case p: P => p.currentStringValue match {
            // parts of json stay as json
            case i: String if i.trim.startsWith("[") && i.trim.endsWith("]") => i
            case i: String if i.trim.startsWith("{") && i.trim.endsWith("}") => i

            // any other string must be escaped
            case i => "\"" + escapeJson(i) + "\""
          }

        }))
        .map(t => s""" "${t._1}" : ${t._2} """)
        .mkString(",")

    // parse and clean it up so it blows up right here if invalid
    val j = new JSONObject(s"{$orig}")
    P.fromTypedValue("", j, getType)
  }

  private def escapeJson(raw: String) = {
    var escaped = raw;
    escaped = escaped.replace("\\", "\\\\");
    escaped = escaped.replace("\"", "\\\"");
    escaped = escaped.replace("\b", "\\b");
    escaped = escaped.replace("\f", "\\f");
    escaped = escaped.replace("\n", "\\n");
    escaped = escaped.replace("\r", "\\r");
    escaped = escaped.replace("\t", "\\t");
    escaped;
  }

  override val getType: WType = schema.fold(WTypes.wt.JSON)(WTypes.wt.JSON.withSchema)

  // replace ${e} with value
  def template(s: String)(implicit ctx: ECtx) = {

    EESnakk.prepStr2(s, Nil)
  }

  // big nice html
  override def toHtmlFull = codeValue(new JSONObject(expr).toString(2))
}

/** a static json array [a,b,c] */
case class JArrCExpr(ex: List[Expr]) extends ConstantExpr {
  override def ttype = WTypes.wt.ARRAY
  override val expr = "[" + ex.mkString(",") + "]"

  private def calculate(v: Any)(implicit ctx: ECtx) = {
    //    val orig = template(expr)
    val origp = ex.map { e=>
      val p = e.applyTyped(v)
      p.calculatedTypedValue
    }

    val orig = origp.map(_.asEscapedJSString).mkString(",")

    // parse and clean it up so it blows up right here if invalid
    (origp, new org.json.JSONArray(s"[$orig]"))
  }

  override def apply(v: Any)(implicit ctx: ECtx) = {
    calculate(v)._2.toString
  }

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    val (le, ja) = calculate(v)
    val t = P.inferArrayTypeFromPV(le)
    P.fromTypedValue("", ja, t)
  }

  override def getType: WType = WTypes.wt.ARRAY

  // replace ${e} with value
  def template(s: String)(implicit ctx: ECtx) = {

    EESnakk.prepStr2(s, Nil)
  }
}

/** a static json array [a,b,c] */
case class JArrExpr(ex: List[Expr]) extends Expr {
  override val expr = "[" + ex.mkString(",") + "]"

  private def calculate(v: Any)(implicit ctx: ECtx) = {
    //    val orig = template(expr)
    val origp = ex.map { e=>
      val p = e.applyTyped(v)
      p.calculatedTypedValue
    }

    val orig = origp.map(_.asEscapedJSString).mkString(",")

    // parse and clean it up so it blows up right here if invalid
    (origp, new org.json.JSONArray(s"[$orig]"))
  }


  override def apply(v: Any)(implicit ctx: ECtx) = {
    calculate(v)._2.toString
  }

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    val (le, ja) = calculate(v)
    val t = P.inferArrayTypeFromPV(le)
    P.fromTypedValue("", ja, t)
  }

  override def getType: WType = WTypes.wt.ARRAY

  // replace ${e} with value
  def template(s: String)(implicit ctx: ECtx) = {

    EESnakk.prepStr2(s, Nil)
  }
}

/** a generator for a json array */
case class JArrExprGen(start:Expr, end:Expr) extends Expr {
  override val expr = "[" + start + " .. " + end + "]"

  // todo make it lazy, not concrete

  private def calculate(v: Any)(implicit ctx: ECtx) = {
    val a = new org.json.JSONArray()

     (
      start.applyTyped(v).calculatedTypedValue.asLong.toInt to
      end.applyTyped(v).calculatedTypedValue.asLong.toInt
    ).foreach{i=>
       a.put(i)
     }

    a
  }

  override def apply(v: Any)(implicit ctx: ECtx) = {
    calculate(v).toString
  }

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    val ja = calculate(v)
    P.fromTypedValue("", ja)
  }

  override def getType: WType = WTypes.wt.ARRAY
}


