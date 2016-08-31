/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.novus.salat._
import org.joda.time.DateTime

import mod.diesel.controllers.{DieselControl, SFiddles}
import razie.diesel.RDOM._
import razie.diesel._
import razie.js

import scala.Option.option2Iterable
import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}

/** RDOM extensions */
object RDExt {

  /** check to match the arguments */
  def sketchAttrs(defs:MatchAttrs, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) : Attrs = {
    defs.map(p=> P(p.name, p.ttype, p.ref, p.multi, p.dflt))
  }

  /** test - expect a message m. optional guard */
  case class ExpectM(m: EMatch) extends CanHtml with HasPosition {
    var when : Option[EMatch] = None
    var pos : Option[EPos] = None
    def withPos(p:Option[EPos]) = {this.pos = p; this}

    override def toHtml = kspan("expect::") +" "+ m.toHtml

    override def toString = "expect:: " + m.toString

    def withGuard (guard:EMatch) = { this.when = Some(guard); this}
    def withGuard (guard:Option[EMatch]) = { this.when = guard; this}

    /** check to match the arguments */
    def sketch(cole: Option[MatchCollector] = None)(implicit ctx: ECtx) : List[EMsg] = {
      var e = EMsg("generated", m.cls, m.met, sketchAttrs(m.attrs, cole))
      e.pos = pos
//      e.spec = destSpec
//      count += 1
      List(e)
    }
  }

  // todo use a EMatch and combine with ExpectM - empty e/a
  /** test - expect a value or more. optional guard */
  case class ExpectV(pm:MatchAttrs) extends CanHtml with HasPosition {
    var when : Option[EMatch] = None
    var pos : Option[EPos] = None
    def withPos(p:Option[EPos]) = {this.pos = p; this}

    override def toHtml = kspan("expect::") +" "+ pm.mkString("(", ",", ")")

    override def toString = "expect:: " + pm.mkString("(", ",", ")")

    def withGuard (guard:EMatch) = { this.when = Some(guard); this}
    def withGuard (guard:Option[EMatch]) = { this.when = guard; this}

    /** check to match the arguments */
    def test(a:Attrs, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      testA(a, pm, cole)
    }

    /** check to match the arguments */
    def sketch(cole: Option[MatchCollector] = None)(implicit ctx: ECtx) : Attrs = {
      sketchAttrs(pm, cole)
    }

  }

  // reference where the item was defined, so we can scroll back to it
  case class EPos (wpath:String, line:Int, col:Int) {
    def toJmap = Map (
      "wpath" -> wpath,
      "line" -> line,
      "col" -> col
    )

    override def toString = s"""{wpath:"$wpath", line:$line, col:$col}"""
    def toRef = s"""weref('$wpath', $line, $col)"""
  }

  object DieselJsonFactory {
    def fromj (o:Map[String, Any]) : Any = {

     def sm(x:String, m:Map[String, Any]) : String = if(m.contains(x)) m(x).asInstanceOf[String] else ""
     def s(x:String)  : String = sm(x, o)
     def l(x:String) = if(o.contains(x)) o(x).asInstanceOf[List[_]] else Nil

      def parms (name:String) = l(name).collect {
        case m:Map[String, Any] => P(sm("name",m), "", "", "", sm("value",m))
      }

      if(o.contains("class")) s("class") match {
        case "EMsg" => EMsg(
          s("arch"),
          s("entity"),
          s("met"),
          parms("attrs"),
          parms("ret"),
          s("stype")
        )

        case "EVal" => EVal (P(
          s("name"), "", "", "", s("value")
        ))
        case "DomAst" => {
          val c = l("children").collect {
            case m:Map[String, Any] => fromj(m).asInstanceOf[DomAst]
          }

          val v = o("value") match {
            case s:String => s
            case m : Map[String,Any] => DieselJsonFactory.fromj(m)
            case x@_ => x.toString
          }

          val d = DomAst(
            v,
            s("kind"),
            (new ListBuffer[DomAst]()) ++ c,
            s("id"))
          d.moreDetails = s("details")
          d
        }
        case _ => o.toString
      } else o.toString
    }
  }

  // an instance at runtime
  case class EInstance(cls: String, attrs: List[RDOM.P]) {
  }

  // an instance at runtime
  case class EEvent(e: EInstance, met: String, attrs: List[RDOM.P]) {
  }

  type Attrs = List[RDOM.P]
  type MatchAttrs = List[RDOM.PM]

  class SingleMatch(val x: Any) {
    var score = 0;
    val diffs = new HashMap[String, (Any, Any)]() // (found, expected)
    var highestMatching: String = ""
    var curTesting: String = ""

    def plus(s: String) = {
      score += 1
    }

    def minus(name: String, found: Any, expected:Any) = {
      diffs.put(name, (found.toString, expected.toString))
    }
  }

  class MatchCollector {
    var cur = new SingleMatch("")
    var highestScore = 0;
    var highestMatching: Option[SingleMatch] = None
    val old = new ListBuffer[SingleMatch]()

    def done = {
      if (cur.score >= highestScore) {
        highestScore = cur.score
        highestMatching = Some(cur)
      }
      old.append(cur)
    }

    def newMatch(x: Any) = {
      done
      cur = new SingleMatch(x)
    }

    def plus(s: String) = cur.plus(s)

    def minus(name: String, found: Any, expected:Any) = cur.minus(name, found, expected)
  }

  // a simple condition
  case class EIf(attrs: MatchAttrs) extends CanHtml {
    def test(e: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) =
      testA(e.attrs, attrs, cole)

    override def toHtml = span("$if::") + attrs.mkString

    override def toString = "$if " + attrs.mkString
  }

  private def check (p:P, pm:PM) =
    p.name == pm.name && {
      if("==" == pm.op) p.dflt == pm.dflt
      else if("!=" == pm.op) p.dflt != pm.dflt
      else if("~=" == pm.op) p.dflt matches pm.dflt
      else false
    }

  /**
   * matching attrs
   *
   * (a,b,c) they occur in whatever sequence
   *
   * (1,b,c) it occurs in position with value
   *
   * (a=1) it occurs with value
   */
  private def testA(in: Attrs, cond: MatchAttrs, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    cond.zipWithIndex.foldLeft(true)((a, b) => a && {
      var res = false

      if (b._1.dflt.size > 0) {
        if (b._1.name.size > 0) {
          res = in.exists(x => check(x, b._1)) || ctx.exists(x => check(x, b._1))
          if (res) cole.map(_.plus(b._1.name + b._1.op + b._1.dflt))
          else cole.map(_.minus(b._1.name, in.find(_.name == b._1.name).mkString, b._1))
        }
      } else {
        // check and record the name failure
        if (b._1.name.size > 0) {
          res = in.exists(_.name == b._1.name) || ctx.exists(_.name == b._1.name)
          if (res) cole.map(_.plus(b._1.name))
          else cole.map(_.minus(b._1.name, b._1.name, b._1))
        }
      }
      res
    })
  }

  // a match case
  case class EMatch(cls: String, met: String, attrs: MatchAttrs, cond: Option[EIf] = None) extends CanHtml {
    // check if it matches a regex
    def regexm(re:String, str:String) = {
      if((re startsWith "/") && (re endsWith "/")) {
        str.matches(re.substring(1,re.length-1))
      } else
        false
    }
    // todo match also the object parms if any and method parms if any
    def test(e: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      if ("*" == cls || e.entity == cls || regexm(cls, e.entity)) {
        cole.map(_.plus(e.entity))
        if ("*" == met || e.met == met || regexm(met, e.met)) {
          cole.map(_.plus(e.met))
          testA(e.attrs, attrs, cole) && cond.fold(true)(_.test(e, cole))
        } else false
      } else false
    }

    /** extract a message signature from the match */
    def asMsg = EMsg("", cls, met, attrs.map{p=>
        P (p.name, p.ttype, p.ref, p.multi, p.dflt)
      })

    override def toHtml = cls + "." + met + " " + attrs.map(_.toHtml).mkString("(", ",", ")")

    override def toString = cls + "." + met + " " + attrs.mkString("(", ",", ")")
  }

  // mapping a message
  case class EMap(cls: String, met: String, attrs: Attrs) {
    var count = 0;

    def apply(in: EMsg, destSpec: Option[EMsg], pos:Option[EPos])(implicit ctx: ECtx): List[Any] = {
      var e = EMsg("generated", cls, met, sourceAttrs(in, attrs, destSpec.map(_.attrs)))
      e.pos = pos
      e.spec = destSpec
      count += 1
      List(e)
    }

    def sourceAttrs(in: EMsg, spec: Attrs, destSpec: Option[Attrs])(implicit ctx: ECtx) = {
      // current context, msg overrides
      val myCtx = new StaticECtx(in.attrs, Some(ctx))

      // solve an expression
      def expr(p: P) = {
          p.expr.map(_.apply("")(myCtx).toString).getOrElse{
          val s = p.dflt
          s
//          if (s.matches("[0-9]+")) s // num
//          else if (s.startsWith("\"")) s // string
//          else in.attrs.find(_.name == s).map(_.dflt).getOrElse("")
        }
      }

      if (spec.nonEmpty) spec.map { p =>
        // sourcing has expr, overrules
        val v =
          if(p.dflt.length > 0 || p.expr.nonEmpty) expr(p)
          else in.attrs.find(_.name == p.name).map(_.dflt).orElse(
          ctx.get(p.name)
        ).getOrElse(
          "" // todo or somehow mark a missing parm?
        )
        p.copy(dflt = v)
      } else if (destSpec.exists(_.nonEmpty)) destSpec.get.map { p =>
        // when defaulting to spec, order changes
        val v = in.attrs.find(_.name == p.name).map(_.dflt).orElse(
          ctx.get(p.name)
        ).getOrElse(
          expr(p)
        )
        p.copy(dflt = v, expr=None)
      } else Nil
    }

    override def toString = cls + "." + met + " " + attrs.mkString
  }

  // a wrapper
  case class EMock(rule: ERule) extends CanHtml with HasPosition {
    var pos : Option[EPos] = None
    override def toHtml = span(count.toString) + " " + rule.toHtml

    override def toString = count.toString + " " + rule.toString

    def count = rule.i.count
  }

  // a context - MAP, use this to test the speed of MAP
  class ECtxM() {
    val attrs = new mutable.HashMap[String, P]()

    def apply(name: String): String = get(name).getOrElse("")

    def get(name: String): Option[String] = attrs.get(name).map(_.dflt)

    def put(p: P) = attrs.put(p.name, p)

    def putAll(p: List[P]) = p.foreach(x => attrs.put(x.name, x))
  }

  trait EApplicable {
    def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) : Boolean

    def isMock : Boolean = false

    def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any]
  }


  // a context
  case class ERule(e: EMatch, i: EMap) extends CanHtml with EApplicable with HasPosition {
    var pos : Option[EPos] = None
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) =
      e.test(m, cole)

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] =
      i.apply(in, destSpec, pos)

    override def toHtml = span("$when::") + " " + e + " => " + i

    override def toString = "$when:: " + e + " => " + i
  }

  // can execute messages -
  // todo can these add more decomosition or just proces leafs?
  abstract class EExecutor (val name:String) extends EApplicable {
  }

  // the context persistence commands
  object EECtx extends EExecutor("ctx") {

    /** map of active contexts per transaction */
    val contexts = new mutable.HashMap[String, ECtx]()

    override def isMock : Boolean = true
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "ctx"
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      in.met match {
        case "persisted" => {
          contexts.get(ctx("kind")+ctx("id")).map(x=>
            if(ctx != x)
              ctx.root.asInstanceOf[DomEngECtx].overwrite(x)
          ).getOrElse {
            contexts.put(ctx("kind") + ctx("id"), ctx.root) // should I save this one?
          }
          Nil
        }
        case "clear" => {
          //          contexts.put(ctx("kind") + ctx("id"), ctx.root) // should I save this one?
          Nil
        }
      }
    }

    override def toString = "$executor::ctx "
  }

  // the context persistence commands
  object EEFunc extends EExecutor("func") {

    // can execute even in mockMode
    override def isMock = true

    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "func"
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      val res = ctx.domain.flatMap(_.funcs.get(in.met)).map { f =>
        val res = try {
          if (f.script != "") {
            val c = ctx.domain.get.mkCompiler("js")
            val x = c.compileAll ( c.not {case fx:RDOM.F if fx.name == f.name => true})
            val s = x + "\n" + f.script

            val q = in.attrs.map(t=>(t.name, t.dflt)).toMap

            SFiddles.isfiddleMap(s, "js", None, None, q, Some(DieselControl.qTyped(q,f)))._2
          } else
            "ABSTRACT FUNC"
        } catch {
          case e:Throwable => e.getMessage
        }
        res.toString
      } getOrElse s"no func ${in.met} in domain"

      in.ret.headOption.orElse(spec(in).flatMap(_.ret.headOption)).orElse(
        Some(new P("result", ""))
      ).map(_.copy(dflt=res)).map(x=>EVal(x)).toList
    }

    override def toString = "$executor::func "
  }

  // execute tests
  object EETest extends EExecutor("test") {
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.stype.startsWith("TEST.")
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      in.ret.headOption.map(_.copy(dflt=in.stype.replaceFirst("TEST.", ""))).map(EVal).map(_.withPos(in.pos)).toList
    }

    override def toString = "$executor::test "
  }

  case class EError (msg:String) extends CanHtml {
    override def toHtml = span("$error::", "danger") + " " + msg
    override def toString = "$error::"+msg
  }

  // temp strip quotes from values
  //todo when types are supported, remove this method and all its uses
  def stripQuotes(s:String) =
    if(s.startsWith("\"") && s.endsWith("\"")) s.substring(1,s.length-1) else s

  // find the spec of the generated message, to ref
  private def spec(m:EMsg)(implicit ctx: ECtx) =
    ctx.domain.flatMap(_.moreElements.collect {
      case x: EMsg if x.entity == m.entity && x.met == m.met => x
    }.headOption)

  // a context
  object EESnakk extends EExecutor("snakk") {
    /** can I execute this task? */
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.stype == "GET" || m.stype == "POST" ||
        spec(m).exists(m=> m.stype == "GET" || m.stype == "POST")
    }

    private def prepUrl (url:String, attrs: Attrs) = {
      val PATT = """(\$\w+)*""".r
      val u = PATT.replaceSomeIn(url, { m =>
        val n = if(m.matched.length > 0) m.matched.substring(1) else ""
        attrs.find(_.name == n).map(x=>
          stripQuotes(x.dflt)
        )
      })
      u
    }

    /** execute the task then */
    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      in.attrs.find(_.name == "url").orElse(
        spec(in).flatMap(_.attrs.find(_.name == "url"))
      ).map {u=>
        import razie.Snakk._
        //          val stype = if(in.stype.length > 0) in.stype else spec(in).map(_.stype).mkString
        val x = url(
          prepUrl(stripQuotes(u.dflt),
            P("subject", "", "", "", in.entity) ::
              P("verb", "", "", "", in.met) :: in.attrs)
          //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
          //            stype))
        )
        try {
          val res = body(x)
          in.ret.headOption.orElse(spec(in).flatMap(_.ret.headOption)).orElse(
            Some(new P("result", ""))
          ).map(_.copy(dflt=res)).map(x=>EVal(x)).toList
        } catch {
          case t:Throwable => {
            razie.clog << t.toString
            EError("Error snakking: "+u+ " :: " +t.toString)::Nil
          }
        }
      } getOrElse EError("no url attribute for RESTification ")::Nil
    }

    override def toString = "$executor::snakk "
  }

  val executors = EECtx :: EESnakk :: EEFunc :: EETest :: Nil

  /** simple json for content assist */
  def toCAjmap(d: RDomain) = {
    val visited = new ListBuffer[(String, String, String)]()

    // todo collapse defs rather than select
    def collect(e: String, m: String, kind:String="msg") = {
      if (!visited.exists(_ ==(kind, e, m))) {
        visited.append((kind, e, m))
      }
    }

    def collectP(l:List[P]) = {
      l.map(p=>collect(p.name, "", "attr"))
    }
    def collectPM(l:List[PM]) = {
      l.map(p=>collect(p.name, "", "attr"))
    }

    Map(
      "msg" -> {
        d.moreElements.collect({
          case n: EMsg => collect(n.entity, n.met)
          case n: ERule => {
            collect(n.e.cls, n.e.met)
            collect(n.i.cls, n.i.met)
          }
          case n: EMock => collect(n.rule.e.cls, n.rule.e.met)
          case n: ExpectM => collect(n.m.cls, n.m.met)
        })
        visited.filter(_._1 == "msg").toList.map(t => t._2 + "." + t._3)
      },
      "attr" -> {
        d.moreElements.collect({
          case n: EMsg => collectP(n.attrs)
          case n: ERule => {
            collectPM(n.e.attrs)
            collectP(n.i.attrs)
          }
          case n: EMock => collectPM(n.rule.e.attrs)
          case n: ExpectM => collectPM(n.m.attrs)
        })
        visited.filter(_._1 == "attr").toList.map(t => t._2)
      }
    )
  }

  trait HasPosition {
    def pos : Option[EPos]

    def kspan(s: String, k: String = "default", specPos:Option[EPos] = None) = {
      def mkref: String = pos.orElse(specPos).map(_.toRef).mkString
      pos.map(p =>
        s"""<span onclick="$mkref" style="cursor:pointer" class="label label-$k">$s</span>"""
      ) getOrElse
        s"""<span class="label label-$k">$s</span>"""
    }
  }

  object CanHtml {
    def span(s: String, k: String = "default") = s"""<span class="label label-$k">$s</span>"""
  }

  trait CanHtml {
    def span(s: String, k: String = "default") = s"""<span class="label label-$k">$s</span>"""

    def toHtml: String
  }

  case class TestResult(value: String, more: String = "") extends CanHtml with HasPosition {
    var pos : Option[EPos] = None
    def withPos(p:Option[EPos]) = {this.pos = p; this}

    override def toHtml =
      if (value == "ok")
        span(value, "success") + s" $more"
      else if (value startsWith "fail")
        span(value, "danger") + s" $more"
      else
        span(value, "warning") + s" $more"

    override def toString =
      value + s" $more"
  }

  def label(value:String, color:String="default") =
    s"""<span class="label label-$color">$value</span>"""

}

import RDExt._

// just a wrapper for type
case class EVal(p: RDOM.P) extends CanHtml with HasPosition {
  var pos : Option[EPos] = None
  def withPos(p:Option[EPos]) = {this.pos = p; this}

  def toj =
    Map (
    "class" -> "EVal",
    "name" -> p.name,
    "value" -> p.dflt
  )

  override def toHtml = kspan("val::") + p.toString

  override def toString = "val: " + p.toString
}

// a nvp - can be a spec or an event, message, function etc
case class EMsg(arch:String, entity: String, met: String, attrs: List[RDOM.P], ret: List[RDOM.P] = Nil, stype: String = "") extends CanHtml with HasPosition {
  var spec: Option[EMsg] = None
  var pos : Option[EPos] = None
  def withPos(p:Option[EPos]) = {this.pos = p; this}

  def toj =
    Map (
      "class" -> "EMsg",
      "arch" -> arch,
      "entity" -> entity,
      "met" -> met,
      "attrs" -> attrs.map { p =>
        Map(
          "name" -> p.name,
          "value" -> p.dflt
        )
      },
      "ret" -> ret.map { p =>
        Map(
          "name" -> p.name,
          "value" -> p.dflt
        )
      },
      "stype" -> stype
    )

  // if this was an instance and you know of a spec
  private def first: String = spec.map(_.first).getOrElse(
    kspan("msg:", resolved, spec.flatMap(_.pos)) + span(stype, "info")
  )

  private def resolved: String = spec.map(_.resolved).getOrElse(
    if (stype == "GET" || stype == "POST" ||
      executors.exists(_.test(this)(ECtx.empty))
    ) "default"
    else "warning"
  )

  /** extract a match from this message signature */
  def asMatch = EMatch(entity, met, attrs.filter(_.dflt != "").map {p=>
    PM (p.name, p.ttype, p.ref, p.multi, "==", p.dflt)
  })

  override def toHtml =
  /*span(arch+"::")+*/first + s""" $entity.<b>$met</b> (${attrs.map(_.toHtml).mkString(", ")})"""

  def toHtmlInPage = hrefBtn2+hrefBtn1 + toHtml.replaceAllLiterally("weref", "wefiddle")

  def hrefBtn2 = s"""<a href="${url2("")}" class="btn btn-xs btn-primary"><span class="glyphicon glyphicon-link"></span></a>"""
  def hrefBtn1 = s"""<a href="${url1("")}" class="btn btn-xs btn-info"><span class="glyphicon glyphicon-th-list"></span></a>"""

  override def toString =
    s""" $entity.$met (${attrs.mkString(", ")})"""

  def attrsToHtml (attrs: Attrs) = {
    attrs.map{p=>
      s"""${p.name}=${p.dflt}"""
    }.mkString("&")
  }

  def url1 (section:String="") = {
    var x = s"""/diesel/wiki/${pos.map(_.wpath).mkString}/react/$entity/$met?${attrsToHtml(attrs)}"""
    if (x.endsWith("&") || x.endsWith("?")) ""
    else if (x contains "?") x = x + "&"
    else x = x + "?"
    x = x + "resultMode=debug"

    if(section != "") {
      x = x + "&dfiddle=" + section
    }
    x
  }

  def url2 (section:String="") = {
    var x = s"""/diesel/fiddle/react/$entity/$met?${attrsToHtml(attrs)}"""
    if (x.endsWith("&") || x.endsWith("?")) ""
    else if (x contains "?") x = x + "&"
    else x = x + "?"
    x = x + "resultMode=debug"

    if(section != "") {
      x = x + "&dfiddle=" + section
    }
    x
  }

  def toHref (section:String="") = {
    var u = url1(section)
    s"""<a href="$u">$entity.$met (${attrs.mkString(", ")})</a>"""
  }
}


