/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDOM, WTypes}
import razie.diesel.expr.{BCMP2, ECtx, RuleScopeECtx}
import razie.wiki.Enc
import scala.collection.mutable.ListBuffer

/** utilities for nodes */
package object nodes {

  type Attrs = List[RDOM.P]
  type MatchAttrs = List[RDOM.PM]

  /** check if it matches a regex */
  def regexm(re:String, str:String) = {
    if((re startsWith "/") && (re endsWith "/")) {
      str.matches(re.substring(1,re.length-1))
    } else
      false
  }

  /** many places copy parent's attrs, but some may have been overwriten in the context, we need to reconcile */
  def reconcileParentAttrs (attrs:List[P], parentCtx:ECtx) : List[P] = {
    val res =
      if (parentCtx.isInstanceOf[RuleScopeECtx])
        attrs.filterNot(p=> parentCtx.isOverwritten(p.name))
      else
        attrs

    res
  }

  /** check to match the arguments */
  def sketchAttrs(defs:MatchAttrs, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) : Attrs = {
    defs.map{p=>
      val v = if(p.dflt.length > 0) p.dflt else p.expr.map(_.apply("")).mkString
      new P(p.name, v, p.ttype)
    }
  }

  /** prep for display */
  def htmlValue(s:String) = Enc.escapeHtml(s.take(100))

  /** p to array of p: each key-value becomes a p */
  def flattenJson (p: P)(implicit ctx: ECtx) : Attrs = {
    val pv = p.calculatedP
    val v = p.calculatedTypedValue

    assert(pv.ttype.name == WTypes.JSON ||
        pv.ttype.name == WTypes.OBJECT ||
        p.isUndefinedOrEmpty, "input needs to be JSON, but it's: " + pv
    )

    v.asJson.map { t =>
      P.fromTypedValue(t._1, t._2)
    }.toList
  }

  /**
   * matching attrs
    *
    * @param in
    * @param cond
    * @param cole collector for match/fail
    * @param foundName opt function to mark value in collector
    * @param positive - false if the condition is negative, like filterNot
   *
   * (a,b,c) they occur in whatever sequence
   *
   * (1,b,c) it occurs in position with value
   *
   * (a=1) it occurs with value
   */
  def testMatchAttrs(in: Attrs,
                     cond: MatchAttrs,
                     cole: Option[MatchCollector] = None,
                     foundName:Option[RDOM.P => Unit]=None,
                     positive:Boolean = true)(implicit ctx: ECtx): Boolean = {
    // for each match

    val calculated = new ListBuffer[P]()

      var result = cond.zipWithIndex.foldLeft(true)((a, b) => a && {
        var res = false

        val pm = b._1

        val inHasParm = in.exists(x => pm.name == x.name)

        if (pm.isMatch && pm.op == "?=") {
          // optionals with default
          res = inHasParm || ctx.exists(x => pm.name == x.name)
          if (!res) {
            // if not there, make it up!
            res = true
            calculated.append(new P(pm.name, pm.dflt, pm.ttype, pm.expr).calculatedP)
          }
        } else if (pm.isMatch && (pm.dflt.size > 0 || pm.expr.isDefined)) {
          // testing for name and value
          if (b._1.name.size > 0) {
            // inHasParm - if parm passed in with value, then don't check context for
            // some other possible values that higher up are good. Fail now, as someone sent in the wrong
            // value
            res = in.exists(x => pm.check(x)) || !inHasParm && ctx.exists(x => pm.check(x))

            if (!res && positive || res && !positive) in.find(_.name == pm.name).map { p =>
              // mark it in the cole
              foundName.map(_.apply(p))
            }

            if (!res) {
              // last try: any value in context
              val bres = new BCMP2(pm.ident, pm.op, pm.valExpr).bapply("")
              res = bres.value

              if (!res) { // failed to find any valid exprs, just evaluate the left side to get some info
//                if (positive) cole.map(_.missedValue(AExprIdent(pm.name).applyTyped("")))
                if (positive && bres.a.isDefined) cole.foreach(_.missedValue(bres.a.get))
              } else {
                // for regex matches, use each capture group and set as parm in context
                if (pm.op == "~=") {
                  // extract parms
                  val a = pm.ident.apply("")
                  val b = pm.valExpr.apply("")
                  val groups = EContent.extractRegexParms(b.toString, a.toString)

                  // as valid types i guess just strings and numbers?
                  groups.foreach(t => ctx.put(P.fromTypedValue(t._1, t._2)))
                }
              }
            }

            if (res && positive) cole.foreach(_.plus(pm.name + pm.op + pm.dflt))
            else cole.map(_.minus(pm.name, in.find(_.name == pm.name).mkString, pm))
          }
        } else if(pm.isMatch) {
          // test just the name (presence): check and record the name failure
          if (pm.name.size > 0) {
            // I don't include the context - this leads to side-effects, just use an IF after the match...
            res = in.exists(_.name == pm.name) || pm.isOptional // || ctx.exists(_.name == b._1.name)

            if (res && positive) cole.foreach(_.plus(pm.name))
            else cole.map(_.minus(pm.name, pm.name, pm))
          }
        } else {
          val bres = pm.checkAsCond()
          res = bres.value

          // todo nice to extract a parm that didn't match from the pm.BExpr and report it
          if (res && positive) cole.foreach(_.plus(""))
          else cole.map(_.minus(bres.a.map(_.name).mkString, bres.a.map(_.calculatedValue).mkString, pm))
        }
        res
      })

    if (!positive) result = !result

    if (result) {
      // populated the calculated only if all else matched and this rule will be used then...
      calculated.foreach(ctx.put)
    }

    result
  }

  def toHtmlAttrs(attrs: Attrs, short:Boolean = true, showExpr:Boolean=true) =
    if (attrs.nonEmpty) s"""${attrs.map(_.toHtml(short=short, showExpr)).mkString("(", ", ", ")")}"""
    else ""

  def toHtmlMAttrs(attrs: MatchAttrs) = if (attrs.nonEmpty)
    s"""${
      attrs.map(_.toHtml).mkString("(", ", ", ")")
    }""" else ""

  def toHtmlPAttrs(attrs: List[PAS]) = if (attrs.nonEmpty)
    s"""${
      attrs.map(_.toHtml).mkString("(", ", ", ")")
    }""" else ""

  //todo when types are supported, remove this method and all its uses
  def stripQuotes(s: String) =
    if (s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

}
