/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import razie.diesel.dom.RDOM.P
import razie.diesel.dom._

/** a function */
case class CExprNull() extends Expr {
  override def getType: WType = WTypes.wt.UNDEFINED
  override def expr = "null"

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).calculatedValue

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = new P("", "", WTypes.wt.UNDEFINED)

  override def toDsl = expr
  override def toHtml = expr
}

/** just a marker for constant expressions */
abstract class ConstantExpr extends Expr {
  def ttype : WType
}

/**
  * constant expression - similar to PValue
  *
  * note strings are interpolated with ${} - escape for that is $${}
  */
case class CExpr[T](ee: T, override val ttype: WType = WTypes.wt.EMPTY) extends ConstantExpr {
  override val expr = ee.toString

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).currentStringValue

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    val es = P.asString(ee)

    if (ttype == WTypes.NUMBER) {
      if (es.contains("."))
        new P("", "", ttype).withCachedValue(es.toDouble, WTypes.wt.NUMBER, es)
      else
        new P("", "", ttype).withCachedValue(es.toLong, WTypes.wt.NUMBER, es)
    } else if (ttype == WTypes.BOOLEAN) {
      new P("", "", ttype).withCachedValue(es.toBoolean, WTypes.wt.BOOLEAN, es)
    } else {
      // expand templates by default
      // important: only for STRING or unknown (default is string)
      // binary or other representations MUST NOT GO THROUGH expansion, duh!

      var nes = es // loop expanding it here...

      if ((ttype.isEmpty || ttype.name == WTypes.STRING) && (nes contains "${")) {
//        while (nes contains "${") {
          var s1 = ""
          try {
            val PAT = """(?<!\$)\$\{([^\}]*)\}""".r
            val eeEscaped = nes
                .replaceAllLiterally("(", """\(""")
                .replaceAllLiterally(")", """\)""")
            s1 = PAT.replaceAllIn(nes, {
              m =>
                (new SimpleExprParser).parseExpr(m.group(1)).map { e =>
                  val res = new P("x", "", WTypes.wt.EMPTY, Some(e)).calculatedValue

                  // if the parm's value contains $ it would not be expanded - that's enough, eh?
                  // todo recursive expansions?
                  var e1 = res
                      .replaceAllLiterally("\\", "\\\\") // escape escaped
                      .replaceAll("\\$", "\\\\\\$")
                  // should not escape double quotes all the time !!
//                e1 = e1
//                    .replaceAll("^\"", "\\\\\"") // escape double quotes
//                e1 = e1
//                    .replaceAll("""([^\\])"""", "$1\\\\\"") // escape unescaped double quotes

                  e1
                } getOrElse
                    s"{ERROR: ${m.group(1)}"
            })
          } catch {
            case e: Exception =>
              val t = new DieselExprException(s"REGEX err for $es - " + e.getMessage)
                  .initCause(e)
              razie.Log.log(s"WHILE processing URL: $nes", t)
          }

          nes = s1
//        }

        new P("", "", ttype).withCachedValue(nes, ttype, nes)

      } else
        new P("", "", ttype).withCachedValue(ee, ttype, es)
    }
  }

  override def toDsl = if (ttype == "String") ("\"" + expr + "\"") else expr
  override def getType: WType = ttype
  override def toHtml = tokenExprValue(escapeHtml(toDsl))
}



