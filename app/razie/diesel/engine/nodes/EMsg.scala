/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.nodes

import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.{AstKinds, DomAstInfo, EGenerated}
import razie.diesel.engine.exec.Executors
import razie.diesel.expr.{AExprIdent, CExpr, ECtx, StaticECtx}
import razie.diesel.model.DieselMsg
import razie.diesel.model.DieselMsg.ENGINE.{DIESEL_FLOW_RETURN, DIESEL_RETURN, DIESEL_RULE_RETURN, DIESEL_SCOPE_RETURN}
import razie.tconf.EPos
import razie.wiki.Enc
import scala.Option.option2Iterable

/** simple assignment - needed because the left side is more than just a val
  */
case class EMsgPas(attrs: List[PAS] = Nil)
    extends CanHtml with HasPosition with EGenerated with DomAstInfo {

  /** the pos of the rule that decomposes me, as a spec */
  var rulePos: Option[EPos] = None

  /** the pos of the rule/map that generated me as an instance */
  var pos: Option[EPos] = None

  def withRulePos(p: Option[EPos]) = {
    this.rulePos = p;
    this
  }

  def withPos(p: Option[EPos]) = {
    this.pos = p;
    this
  }

  private def msgLabelColor: String = "primary"

  // if this was an instance and you know of a spec
  private def first(instPos: Option[EPos]): String = {
    // clean visual stypes annotations
    val stypeStr = "".replaceAllLiterally(",prune", "").replaceAllLiterally(",warn", "")
    kspan("msg", msgLabelColor, instPos) + span(stypeStr, "info") + (if (stypeStr.trim.length > 0) " " else "")
    //    kspan("msg", msgLabelColor, spec.flatMap(_.pos)) + span(stypeStr, "info") + (if(stypeStr.trim.length > 0) "
    //    " else "")
  }

  /** this html works well in a diesel fiddle, use toHtmlInPage elsewhere */
  override def toHtml = {
    /*span(arch+"::")+*/first(pos) + " " + toHtmlPAttrs(attrs)
  }

  override def toString =
    s""" (${attrs.mkString(", ")})"""

  override def shouldPrune = false
  override def shouldIgnore = false
  override def shouldSkip = false
  override def shouldRollup = true

}

/** a message
  *
  * @param entity
  * @param met
  * @param attrs
  * @param arch
  * @param ret
  */
case class EMsg(
  entity: String,
  met: String,
  attrs: List[RDOM.P] = Nil,
  arch: String = "",
  ret: List[RDOM.P] = Nil,
  stype: String = "")
    extends CanHtml with HasPosition with EGenerated with DomAstInfo {

  import EMsg._

  def this(ea: String, attrs: Attrs) = this(EMsg.getEA(ea)._1, EMsg.getEA(ea)._2, attrs)

  /** my specification - has attributes like public etc */
  var spec: Option[EMsg] = None

  /** the pos of the rule that decomposes me, as a spec */
  var rulePos: Option[EPos] = None

  /** the pos of the rule/map that generated me as an instance */
  var pos: Option[EPos] = None

  def withSpec(p: Option[EMsg]) = {
    this.spec = p;
    this
  }

  def withRulePos(p: Option[EPos]) = {
    this.rulePos = p;
    this
  }

  def withPos(p: Option[EPos]) = {
    this.pos = p;
    this
  }

  def withArch(s: String) = this.copy(arch = s).copiedFrom(this)

  def asCtx(implicit ctx: ECtx): ECtx = new StaticECtx(this.attrs, Some(ctx))

  /** copy doesn't copy over the vars */
  def copiedFrom(from: EMsg) = {
    this.spec = from.spec
    this.pos = from.pos
    this.rulePos = from.rulePos
    this
  }

  def toj : Map[String,Any] =
    Map (
      "class" -> "EMsg",
      "arch" -> arch,
      "entity" -> entity,
      "met" -> met,
      "ea" -> ea,
      "attrs" -> attrs.map { p =>
        Map(
          "name" -> p.name,
//          "ttype" -> p.ttype.toString,
          "value" -> p.currentStringValue
        )
      },
      "ret" -> ret.map { p =>
        Map(
          "name" -> p.name,
//          "ttype" -> p.ttype.toString,
          "value" -> p.currentStringValue
        )
      },
      "stype" -> stype
    ) ++
      pos.map{p=>
        Map ("ref" -> p.toRef,
          "pos" -> p.toJmap
        )
      }.getOrElse(Map.empty) ++
      rulePos.map{p=>
        Map ("ref" -> p.toRef,
          "pos" -> p.toJmap
        )
      }.getOrElse(Map.empty)

  // if this was an instance and you know of a spec
  private def first(instPos: Option[EPos], kind: String): String =
    spec
        .filter(x => !x.equals(this)) // avoid stackoverflow if self is spec
        .map(_.first(instPos, kind))
        .getOrElse {
          // clean visual stypes annotations
          val stypeStr = stype.replaceAllLiterally(",prune", "").replaceAllLiterally(",warn", "")
          kspan("msg", msgLabelColor, instPos) + span(stypeStr, "info") + (if (stypeStr.trim.length > 0) " " else "")
          //    kspan("msg", msgLabelColor, spec.flatMap(_.pos)) + span(stypeStr, "info") + (if(stypeStr.trim.length
          //    > 0) " " else "")
        }

  /** find the spec and get its pos */
  def specPos: Option[EPos] = {
    // here is where you decide what to show: $msg (spec) or $when (rulePos)
    rulePos orElse spec.flatMap(_.pos)
  }

  /** if has executor */
  // todo the null
  def hasExecutor: Boolean = Executors.withAll(_.exists(t => t._2.test(null, this)(ECtx.empty)))

  val ea:String = (entity + "." + met).trim

  def isResolved:Boolean =
    if(spec.filter(x=> !x.equals(this)).exists(_.isResolved)) true
    else (
      if ((stype contains "GET") || (stype contains "POST") || hasExecutor || entity == "diesel") true
      else false
  )

  /** color - if has executor */
  private def msgLabelColor: String =
    if (stype contains WARNING) "warning"
    else if(isResolved) "default"
    else "primary"

  /** extract a match from this message signature */
  def asMatch = EMatch(entity, met, attrs.filter(p => p.hasCurrentValue || p.expr.isDefined).map { p =>
    PM(AExprIdent(p.name), p.ttype, "==", p.dflt, p.expr)
  })

  /** message name as a nice link to spec as well */
  private def eaHtml(kind: String, labelClass:String="default") =
    kind match {
      case AstKinds.TRACE | AstKinds.VERBOSE =>
        kspan(ea(entity, met, "", false, kind), "", specPos, spec.orElse(Some("no spec")).map(_.toString))
      case _ =>
        kspan(ea(entity, met, "", false, kind), labelClass, specPos, spec.orElse(Some("no spec")).map(_.toString))
    }

  /** this html works well in a diesel fiddle, use toHtmlInPage elsewhere */
  override def toHtml = toHtml(AstKinds.GENERATED)

  /** to html in fiddle, using a kind: trace/debug etc */
  override def toHtml(kind: String): String = {
    def m = attrs.headOption.map(_.currentStringValue).mkString

    val labelClass = ea match {
      case "ctx.foreach" |
          DIESEL_RETURN |
           DIESEL_RULE_RETURN | DIESEL_SCOPE_RETURN | DIESEL_FLOW_RETURN => "warning"
      case _ => "default"
    }

    if (DieselMsg.ENGINE.DIESEL_HEADING == ea) {
      val pad=80
      val p2 = (pad - m.length)/2
      val mm = if (p2 <= 0) m else "".view.padTo(p2, "&nbsp;").mkString + m + "".view.padTo(p2, "&nbsp;").mkString
      val bkg = this.attrs.find(p=>p.name == "background" && p.value.exists(_.asString.length > 0)).map (p=>
        "background-color:" + p.value.get.asString
      ).getOrElse ("")
      span("<br>"+mm+"<br>", "primary", "", "style=\"color:yellow;font-size:1em;" + bkg + "\"")
    } else if (DieselMsg.ENGINE.DIESEL_STEP == ea) {
      // step is just one comment
      /*span(arch+"::")+*/ first(pos, kind) + eaHtml(kind, "primary") + " " + span(m, "primary")
    } else if (DieselMsg.ENGINE.DO_THIS == ea || DieselMsg.ENGINE.DO_THAT == ea) {
      /*span(arch+"::")+*/ first(pos, kind) + eaHtml(kind, "default") + " " + span(m, "default")
    } else if (DieselMsg.ENGINE.DIESEL_SUMMARY == ea) {
      // summary may have list of long values
      /*span(arch+"::")+*/
      first(pos, kind) +
          eaHtml(kind, "primary") + " " +
          span(m, "default") +
          toHtmlAttrs(if(attrs.isEmpty) Nil else attrs.tail, short=true, showExpr = false)
    } else if (DieselMsg.ENGINE.DIESEL_TODO == ea) {
//      val color = if (m contains "!") "danger" else "warning"
      /*span(arch+"::")+*/ first(pos, kind) + eaHtml(kind) + " " + span(m, "warning")
    } else {
      /*span(arch+"::")+*/ first(pos, kind) + eaHtml(kind, labelClass) + " " + toHtmlAttrs(attrs, short=true, showExpr=false)
    }
  }

  /** html anchor */
  def anchor = s"""<a name="$ea"></a>"""

  /** as opposed to toHtml, this will produce an html that can be displayed in any page, not just the fiddle */
  def toHtmlInPage = anchor + hrefBtnGlobal /*+hrefBtn1*/ + toHtml.replaceAllLiterally("weref", "wefiddle") + "<br>"

  def hrefBtnGlobal =
    s"""<a href="${url2("")}" class="btn btn-xs btn-primary" title="global link">
       |<span class="glyphicon glyphicon glyphicon-th-list"></span></a>""".stripMargin

  def hrefBtnLocal =
    s"""<a href="${url1("")}" class="btn btn-xs btn-info"    title="local link in this topic">
       |<span class="glyphicon glyphicon-list-alt"></span></a>""".stripMargin

  /** simple signature */
  def toCAString = entity + "." + met + " " + attrs.map(_.name).mkString("(", ",", ")")

  override def toString =
    s""" $entity.$met (${attrsToUrl(attrs, ", ", false)})"""

  private def attrsToUrl(attrs: Attrs, ch: String = "&", encode: Boolean = true) = {
    attrs.map { p =>
      var v = p.currentStringValue
      if (encode) v = Enc.toUrl(v) // escape special chars
      s"""${p.name}=$v"""
    }.mkString(ch)
  }

  // local invocation url
  private def url1(section: String = "", resultMode: String = "value") = {
    var x = s"""/diesel/wreact/${pos.map(_.wpath).mkString}/react/$entity/$met?${attrsToUrl(attrs)}"""
    if (x.endsWith("&") || x.endsWith("?")) ""
    else if (x contains "?") x = x + "&"
    else x = x + "?"
    if ("value" != resultMode) x = x + "resultMode=" + resultMode

    if (section != "") {
      x = x + "&dfiddle=" + section
    }
    x
  }

  // reactor invocation url
  def urlPath (section:String="", resultMode:String="value") = {
    var x = if (DieselMsg.ENGINE.DIESEL_REST == ea) {
      s"""/diesel/rest""" + attrs.find(_.name == "path").map(_.currentStringValue).mkString
    } else {
      s"""/diesel/react/$entity/$met?${attrsToUrl(attrs)}"""
    }

    x
  }

  // reactor invocation url
  private def url2 (section:String="", resultMode:String="value") = {
    var x = if (DieselMsg.ENGINE.DIESEL_REST == ea) {
      s"""/diesel/rest""" + attrs.find(_.name == "path").map(_.currentStringValue).mkString
    } else {
      s"""/diesel/react/$entity/$met?${attrsToUrl(attrs)}"""
    }

    if (x.endsWith("&") || x.endsWith("?")) ""
    else if (x contains "?") x = x + "&"
    else x = x + "?"
    if("value" != resultMode) x = x + "resultMode="+resultMode

    if(section != "") {
      x = x + "&dfiddle=" + section
    }
    x
  }

  /** make a href
    *
    * @param section
    * @param resultMode
    * @param mapUrl use to add args to url
    * @return
    */
  def toHref (section:String="", resultMode:String="debug", mapUrl:String=>String=identity) = {
    var u = url1(section, resultMode)
    s"""<a target="_blank" href="${mapUrl(u)}">$entity.$met(${attrs.mkString(", ")})</a>"""
  }

  /** extract a message signature from the match */

  /** make a href
    *
    * @param text
    * @param section
    * @param resultMode
    * @param mapUrl use to add args to url
    * @return
    */
  def toHrefWith (text:String, section:String="", resultMode:String="debug", mapUrl:String=>String=identity) = {
    var u = url1(section, resultMode)
    s"""<a target="_blank" href="${mapUrl(u)}">$text</a>"""
  }

  override def shouldPrune = this.stype contains PRUNE
  override def shouldIgnore = this.stype contains IGNORE
  override def shouldSkip = this.stype contains SKIP
  override def shouldRollup =
    // rollup simple assignments - leave only the EVals
    entity == "" && met == "" ||
      (this.stype contains ROLLUP)

  /** is it public visilibity */
  def isPublic =
    spec.map(_.arch).exists(_ contains PUBLIC) ||
        spec.map(_.stype).exists(_ contains PUBLIC)
        // we do NOT check this.stype because this may be coming from unsecured user

  // todo not used right now - hassle since you have a trust list anyways...
  def isProtected =
    spec.map(_.arch).exists(_ contains PROTECTED) ||
    spec.map(_.stype).exists(_ contains PROTECTED)
  // we do NOT check this.stype because this may be coming from unsecured user

  /** use the right parm types **/
  def typecastParms(spec: Option[EMsg]) : EMsg = {
    spec.map { spec =>
      val newParms = attrs.map { p =>
        spec.attrs.find(_.name == p.name).map { s =>
          if (s.ttype != "" && p.ttype == "") {
            if (s.ttype == "Number") {
              if (p.currentStringValue.contains("."))
                p.copy(ttype = s.ttype).withValue(p.currentStringValue.toDouble)
              else
                p.copy(ttype = s.ttype).withValue(p.currentStringValue.toInt)
            } else {
              p.copy(ttype = s.ttype)
            }
          } else {
            p
          }
        }.getOrElse {
          p
        }
      }

      this.copy(attrs = newParms)
    }.getOrElse {
      this
    }
  }
}

object EMsg {
  val PRUNE = "prune"
  val ROLLUP = "rollup"
  val IGNORE = "ignore"
  val SKIP = "skip"

  val PUBLIC = "public"
  val PROTECTED = "protected"

  val WARNING = "warn"

  /** regex to match (e,a) */
  val REGEX = """([\w.]+)[./](\w+)""".r

  def apply(ea: String): EMsg = {
    val REGEX(ee, aa) = ea
    EMsg(ee, aa)
  }

  def apply(ea: String, attrs: Attrs): EMsg = {
    val REGEX(ee, aa) = ea
    EMsg(ee, aa, attrs)
  }

  def getEA(ea: String): (String, String) = {
    val REGEX(ee, aa) = ea
    (ee, aa)
  }

}
