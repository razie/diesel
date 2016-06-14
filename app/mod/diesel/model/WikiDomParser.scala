/**
 * ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import mod.diesel.controllers.SFiddles
import razie.diesel.RDOM._
import razie.diesel.{RDomain, RDOM}
import razie.wiki.{Enc, Dec}
import razie.wiki.dom.WikiDomain

import scala.Option.option2Iterable
import model._
import admin.Config
import org.bson.types.ObjectId
import play.api.mvc.Action
import razie.wiki.parser.WAST
import razie.wiki.model.WikiEntry
import razie.wiki.parser.WikiParserBase

import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** domain parser - for domain sections in a wiki */
trait WikiDomainParser extends WikiParserBase {

  import WAST._
  import RDOM._
  import RDExt._

  def ident: P = """\w+""".r

  def any: P = """.*""".r

  def value: P = ident | number | str

  def number: P = """\d+""".r

  def str: P = """"[^"]*"""".r

  def ws = whiteSpace

  def ows = opt(whiteSpace)

  def domainBlocks = pobject | pclass | passoc | pfunc | pwhen | pmsg | pexpectm | pexpectv | pprop | pmock

  // todo replace $ with . i.e. .class

  def optKinds: PS = opt(ows ~> "[" ~> ows ~> repsep(ident, ",") <~ "]") ^^ {
    case Some(tParm) => tParm.mkString
    case None => ""
  }

  /**
   * .class X [T] (a,b:String) extends A,B {}
   */
  def pclass: PS =
    """[.$]class""".r ~> ws ~> ident ~ opt(ows ~> "[" ~> ows ~> repsep(ident, ",") <~ "]") ~ optAttrs ~ opt(ws ~> "extends" ~> ws ~> repsep(ident, ",")) ~
      opt(ws ~> "<" ~> ows ~> repsep(ident, ",") <~ ">") ~ " *".r ~ optClassBody ^^ {
      case name ~ tParm ~ attrs ~ ext ~ stereo ~ _ ~ funcs => {
        val c = C(name, "", stereo.map(_.mkString).mkString,
          ext.toList.flatMap(identity),
          tParm.map(_.mkString).mkString,
          attrs,
          funcs)
        LazyState { (current, ctx) =>
          collectDom(c, ctx.we)

          def mkList = s"""<a href="/diesel/list2/${c.name}">list</a>"""
          def mkNew = if (ctx.we.exists(w => WikiDomain.canCreateNew(w.realm, name))) s""" | <a href="/doe/diesel/create/${c.name}">new</a>""" else ""

          SState(
            s"""
               |<div align="right"><small>$mkList $mkNew </small></div>
               |<div class="well">
               |$c
               |</div>""".stripMargin)
        }
      }
    }

  /** assoc : role */
  def assRole: Parser[(String, String)] = ident ~ " *: *".r ~ ident ^^ {
    case cls ~ _ ~ role => (cls, role)
  }

  /** assoc : role */
  def clsMet: Parser[(String, String, List[RDOM.P])] = (ident | "*") ~ " *. *".r ~ (ident | "*") ~ optAttrs ^^ {
    case cls ~ _ ~ role ~ a => (cls, role, a)
  }

  /**
   * add a domain element to the topic
   */
  def addToDom(c: Any) = {
    LazyState { (current, ctx) =>
      collectDom(c, ctx.we)
      SState(
        c match {
          case x: CanHtml => x.toHtml
          case s@_ =>
            //          """<span class="label label-primary">""" +
            """<p class="bg-info">""" +
              s.toString +
              """</p>"""
        }
      )
    }
  }

  /**
   * .when a.role (attrs) => z.role (attrs)
   */
  def pif: Parser[EIf] =
    """[.$]if""".r ~> ws ~> optAttrs ^^ {
      case aa => RDExt.EIf(aa)
    }

  /**
   * .when a.role (attrs) => z.role (attrs)
   */
  def pwhen: PS =
    """[.$]when""".r ~> ws ~> clsMet ~ opt(pif) ~ " *=> *".r ~ clsMet ^^ {
      case Tuple3(ac, am, aa) ~ cond ~ _ ~ Tuple3(zc, zm, za) => {
        val x = RDExt.EMatch(ac, am, aa, cond)
        val y = RDExt.EMap(zc, zm, za)
        val rule = RDExt.ERule(x, y)
        addToDom(rule)
      }
    }

  /**
   * .mock a.role (attrs) => z.role (attrs)
   */
  def pmock: PS =
    """[.$]mock""".r ~> ws ~> clsMet ~ opt(pif) ~ " *=> *".r ~ optAttrs ^^ {
      case Tuple3(ac, am, aa) ~ cond ~ _ ~ za => {
        val x = RDExt.EMatch(ac, am, aa, cond)
        val y = RDExt.EMap("", "", za)
        val rule = EMock(ERule(x, y))
        addToDom(rule)
      }
    }

  /**
   * .assoc name a:role -> z:role
   */
  def passoc: PS =
    """[.$]assoc""".r ~> ws ~> opt(ident <~ ws) ~ assRole ~ " *-> *".r ~ assRole ~ optAttrs ^^ {
      case n ~ Tuple2(a, arole) ~ _ ~ Tuple2(z, zrole) ~ p => {
        val c = A(n.mkString, a, z, arole, zrole, p)
        LazyState { (current, ctx) =>
          collectDom(c, ctx.we)
          SState(
            """<span class="label label-default">""" +
              c.toString +
              """</span>""")
        }
      }
    }

  def pobject: PS =
    """[.$]object """.r ~> ident ~ " *".r ~ ident ~ opt(CRLF2 ~> rep1sep(vattrline, CRLF2)) ^^ {
      case name ~ _ ~ c ~ l => {
        val o = O(name, c, l.toList.flatMap(identity))
        LazyState { (current, ctx) =>
          collectDom(o, ctx.we)
          SState(
            """<div class="well">""" +
              s"object $name (" + l.mkString(", ") +
              ")" +
              """</div>""")
        }
      }
    }

  /**
   * optional attributes
   */
  def optAttrs: Parser[List[RDOM.P]] = opt(" *\\(".r ~> rep1sep(pattr, ",") <~ ")") ^^ {
    case Some(a) => a
    case None => List.empty
  }

  // ?
  def attr: Parser[_ >: CM] = pattr

  /**
   * :<>type[kind]*
   * <> means it's a ref, not ownership
   * * means it's a list
   */
  def optType: Parser[String] = opt(" *: *".r ~> ident ~ optKinds ~ opt(" *\\* *".r)) ^^ {
    case Some(tt ~ k ~ multi) => tt + k.s + multi.mkString
    case None => ""
  }

  /**
   * name:<>type[kind]*=default
   * <> means it's a ref, not ownership
   * * means it's a list
   */
  def pattr: Parser[RDOM.P] = " *".r ~> ident ~ opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds) ~ opt(" *\\* *".r) ~ opt(" *= *".r ~> value) ^^ {
    case name ~ t ~ multi ~ e => t match {
      case Some(Some(ref) ~ tt ~ k) => P(name, tt + k.s, ref, multi.mkString, e.mkString)
      case Some(None ~ tt ~ k) => P(name, tt + k.s, "", multi.mkString, e.mkString)
      case None => P(name, "", "", multi.mkString, e.mkString)
    }
  }


  // value assignment
  def vattrline: Parser[V] = " *".r ~> ident ~ " *= *".r ~ any ^^ {
    case name ~ _ ~ v => V(name, v)
  }

  def optClassBody: Parser[List[RDOM.F]] = opt(" *\\{".r ~> CRLF2 ~> rep1sep(fattrline, CRLF2) <~ CRLF2 <~ " *\\} *".r) ^^ {
    case Some(a) => a
    case None => List.empty
  }

  /**
   * .option name:type=value
   *
   * use them to set options
   */
  def pprop: PS = "[.$]attr *".r ~> pattr ^^ {
    case a => {
      LazyState { (current, ctx) =>
        collectDom(EAttr(a), ctx.we)
        SState("Option: " + a.toString)
      }
    }
  }

  /**
   * .msg object.func (a,b)
   *
   * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
   */
  def pmsg: PS = "[.$]msg *".r ~> opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
      LazyState { (current, ctx) =>
        val f = RDExt.EMsg(ent, ac, attrs, ret.toList.flatten(identity), stype.mkString.trim)
        collectDom(f, ctx.we)
        SState(f.toString)
      }
    }
  }

  /**
   * .expect object.func (a,b)
   */
  def pexpectm: PS = "[.$]expect * [$]msg *".r ~> ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(pif) ^^ {
    case ent ~ _ ~ ac ~ attrs ~ cond => {
      LazyState { (current, ctx) =>
        val f = RDExt.ExpectM(RDExt.EMatch(ent, ac, attrs, cond))
        collectDom(f, ctx.we)
        SState(f.toHtml)
      }
    }
  }

  /**
   * .expect object.func (a,b)
   */
  def pexpectv: PS = "[.$]expect * [$]val *".r ~> pattr ^^ {
    case a => {
      LazyState { (current, ctx) =>
        val f = RDExt.ExpectV(a)
        collectDom(f, ctx.we)
        SState("expect::" + f.toString)
      }
    }
  }

  private def collectDom(x: Any, we: Option[WikiEntry]) = {
    we.foreach { w =>
      val rest = w.cache.getOrElse(WikiDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
      w.cache.put(WikiDomain.DOM_LIST, x :: rest)
    }
  }

  /**
   * .func name (a,b) : String
   */
  def pfunc: PS = "[.$]def *".r ~> ident ~ optAttrs ~ opt(" *: *".r ~> ident) ~ optScript ~ optBlock ^^ {
    case name ~ a ~ t ~ s ~ b => {
      LazyState { (current, ctx) =>
        val f = F(name, a, t.mkString, s.fold(ctx).s, b)
        collectDom(f, ctx.we)

        def mkParms = f.parms.map { p => p.name + "=" + Enc.toUrl(p.dflt) }.mkString("&")
        def mksPlay = if (f.script.length > 0) s""" | <a href="/diesel/splay/${f.name}/${ctx.we.map(_.wid.wpath).mkString}?$mkParms">splay</a>""" else ""
        def mkjPlay = if (f.script.length > 0) s""" | <a href="/diesel/jplay/${f.name}/${ctx.we.map(_.wid.wpath).mkString}?$mkParms">jplay</a>""" else ""
        def mkCall =
          s"""<a href="/diesel/fcall/${f.name}/${ctx.we.map(_.wid.wpath).mkString}?$mkParms">fcall</a>$mkjPlay$mksPlay""".stripMargin

        SState(
          s"""
             |<div align="right"><small>$mkCall</small></div>
             |<div class="well">
             |$f
             |</div>""".stripMargin)
      }
    }
  }

  /** optional script body */
  def optScript: PS = opt(" *\\{\\{ *".r ~> lines <~ "}}") ^^ {
    case Some(lines) => lines
    case None => ""
  }

  /**
   * def name (a,b) : String
   */
  def fattrline: Parser[RDOM.F] = " *def *".r ~> ident ~ optAttrs ~ optType ~ " *".r ~ optBlock ^^ {
    case name ~ a ~ t ~ _ ~ b => F(name, a, t.mkString, "", b)
  }

  def optBlock: Parser[List[EXEC]] = opt(" *\\{".r ~> CRLF2 ~> rep1sep(statement, CRLF2) <~ CRLF2 <~ " *\\} *".r) ^^ {
    case Some(a) => a
    case None => List.empty
  }

  def statement: Parser[EXEC] = svalue | scall

  def svalue: Parser[EXEC] = valueDef ^^ { case p => new ExecValue(p) }

  // not used yet - class member val
  def valueDef: Parser[RDOM.P] = "val *".r ~> ident ~ opt(" *: *".r ~> opt("<>") ~ ident) ~ opt(" *\\* *".r) ~ opt(" *= *".r ~> value) ^^ {
    case name ~ t ~ multi ~ e => t match {
      case Some(Some(ref) ~ tt) => P(name, tt, ref, multi.mkString, e.mkString)
      case Some(None ~ tt) => P(name, tt.mkString, "", multi.mkString, e.mkString)
      case None => P(name, "", "", multi.mkString, e.mkString)
    }
  }

  // not used yet - class member val
  def scall: Parser[EXEC] = ows ~> ident ~ "." ~ ident ~ optAttrs ^^ {
    case cls ~ _ ~ func ~ attres =>
      new ExecCall(cls, func, attres)
  }

}

class ExecValue(p: RDOM.P) extends EXEC {
  def sForm = "val " + p.toString

  def exec(ctx: Any, parms: Any*): Any = ""
}

class ExecCall(cls: String, func: String, args: List[P]) extends EXEC {
  def sForm = s"call $cls.$func (${args.mkString})"

  def exec(ctx: Any, parms: Any*): Any = ""
}

/** RDOM extensions */
object RDExt {

  // just a wrapper for type
  case class EVal(p: RDOM.P) extends CanHtml {
    override def toHtml = span("val::") + p.toString

    override def toString = "val: " + p.toString
  }

  // just a wrapper for type
  case class EAttr(p: RDOM.P) {
    override def toString = p.toString
  }

  def span(s: String, k: String = "default") = s"""<span class="label label-$k">$s</span>"""

  // wrapper
  case class ExpectM(m: EMatch) extends CanHtml {
    override def toHtml = span("expect::") + m.toString

    override def toString = "expect::" + m.toString
  }

  // wrapper
  case class ExpectV(p: P) extends CanHtml {
    override def toHtml = span("expect::") + p.toString

    override def toString = "expect::" + p.toString

    /** check to match the arguments */
    def test(a: P, cole: Option[MatchCollector] = None) = {
      var res = false
      if (p.name.size > 0) {
        res = a.name == p.name
      }
      if (p.dflt.size > 0) {
        if (p.name.size > 0) {
          res = a.name == p.name && a.dflt == p.dflt
        }
      }
      res
    }

  }

  // a nvp - can be a spec or an event, message, function etc
  case class EMsg(entity: String, met: String, attrs: List[RDOM.P], ret: List[RDOM.P] = Nil, stype: String = "") extends CanHtml {
    var spec: Option[EMsg] = None

    // if this was an instance and you know of a spec
    private def first: String = spec.map(_.first).getOrElse(
      span("msg:", resolved) + span(stype, "info")
    )

    private def resolved: String = spec.map(_.resolved).getOrElse(
      if (stype == "GET" || stype == "POST") "default" else "warning"
    )

    override def toHtml = first +
      s""" $entity.<b>$met</b> (${attrs.mkString(", ")})"""

    override def toString = toHtml
  }

  // an instance at runtime
  case class EInstance(cls: String, attrs: List[RDOM.P]) {
  }

  // an instance at runtime
  case class EEvent(e: EInstance, met: String, attrs: List[RDOM.P]) {
  }

  type Attrs = List[RDOM.P]

  class SingleMatch(val x: Any) {
    var score = 0;
    val diffs = new HashMap[String, Any]()
    var highestMatching: String = ""
    var curTesting: String = ""

    def plus(s: String) = {
      score += 1
    }

    def minus(name: String, diff: Any) = {
      diffs.put(name, diff.toString)
    }
  }

  class MatchCollector {
    var cur = new SingleMatch()
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

    def minus(name: String, diff: Any) = cur.minus(name, diff)
  }

  // a simple condition
  case class EIf(attrs: Attrs) extends CanHtml {
    def test(e: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = testA(e.attrs, attrs, cole)

    override def toHtml = span("$if::") + attrs.mkString

    override def toString = "$if " + attrs.mkString
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
  private def testA(in: Attrs, cond: Attrs, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    cond.zipWithIndex.foldLeft(true)((a, b) => a && {
      var res = false
      if (b._1.dflt.size > 0) {
        if (b._1.name.size > 0) {
          res = in.exists(x => x.name == b._1.name && x.dflt == b._1.dflt)
          if (res) cole.map(_.plus(b._1.name + "=" + b._1.dflt))
          else cole.map(_.minus(b._1.name, in.find(_.name == b._1.name).getOrElse(b._1)))
        } else {
          res = in(b._2).dflt == b._1.dflt
          if (res) cole.map(_.plus(b._1.name + "=" + b._1.dflt))
          else cole.map(_.minus(b._1.name, b._1))
        }
      } else {
        // check and record the name failure
        if (b._1.name.size > 0) {
          res = in.exists(_.name == b._1.name)
          if (res) cole.map(_.plus(b._1.name))
          else cole.map(_.minus(b._1.name, b._1.name))
        }
      }
      res
    })
  }

  // a match case
  case class EMatch(cls: String, met: String, attrs: Attrs, cond: Option[EIf] = None) {
    // todo match also the object parms if any and method parms if any
    def test(e: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      if ("*" == cls || e.entity == cls) {
        cole.map(_.plus(e.entity))
        if ("*" == met || e.met == met) {
          cole.map(_.plus(e.met))
          testA(e.attrs, attrs, cole)
        } else false
      } else false
    }

    override def toString = cls + "." + met + " " + attrs.mkString
  }

  // a match case
  case class EMap(cls: String, met: String, attrs: Attrs) {
    var count = 0;

    // todo match also the object parms if any and method parms if any
    def apply(e: EEvent) =
      "?event"

    def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      var e = EMsg(cls, met, sourceAttrs(in, attrs, destSpec.map(_.attrs)))
      e.spec = destSpec
      count += 1
      List(e)
    }

    def sourceAttrs(in: EMsg, spec: Attrs, destSpec: Option[Attrs])(implicit ctx: ECtx) = {

      // solve an expression
      def expr(s: String) = {
        if (s.startsWith("\"")) s // string
        else if (s.matches("[0-9]+")) s // num
        else in.attrs.find(_.name == s).map(_.dflt).getOrElse("")
      }

      if (spec.nonEmpty) spec.map { p =>
        //todo use expr
        val v = in.attrs.find(_.name == p.name).map(_.dflt).orElse(
          ctx.get(p.name)
        ).getOrElse(
          expr(p.dflt)
        )
        p.copy(dflt = v)
      } else if (destSpec.exists(_.nonEmpty)) destSpec.get.map { p =>
        //todo use expr
        val v = in.attrs.find(_.name == p.name).map(_.dflt).orElse(
          ctx.get(p.name)
        ).getOrElse(
          expr(p.dflt)
        )
        p.copy(dflt = v)
      } else Nil
    }

    override def toString = cls + "." + met + " " + attrs.mkString
  }

  // a wrapper
  case class EMock(rule: ERule) extends CanHtml {
    override def toHtml = span(count.toString) + " " + rule.toHtml

    override def toString = toHtml

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

  // a context - LIST, use to see speed of list
  class ECtx() {
    var attrs: List[P] = Nil

    def apply(name: String): String = attrs.find(_.name == name).map(_.dflt).getOrElse("")

    def get(name: String): Option[String] = attrs.find(_.name == name).map(_.dflt)

    def put(p: P) = attrs = p :: attrs

    def putAll(p: List[P]) = attrs = p ::: attrs
  }


  // a context
  case class ERule(e: EMatch, i: EMap) extends CanHtml {
    override def toHtml = span("$when::") + " " + e + " => " + i

    override def toString = "$when:: " + e + " => " + i
  }

  /** simple json for content assist */
  def toCAjmap(d: RDomain) = {
    val visited = new ListBuffer[(String, String)]()

    // todo collapse defs rather than select
    def collect(e: String, m: String) = {
      if (!visited.exists(_ ==(e, m))) {
        visited.append((e, m))
      }
    }

    Map(
      "$when" -> {
        d.moreElements.collect({
          case n: EMsg => collect(n.entity, n.met)
          case n: ERule => {
            collect(n.e.cls, n.e.met)
            collect(n.i.cls, n.i.met)
          }
        })
        visited.toList.map(t => t._1 + "." + t._2)
      }
    )
  }


  trait CanHtml {
    def toHtml: String
  }

  case class TestResult(value: String, more: String = "") {
    override def toString =
      if (value == "ok")
        s"""<span class="label label-success">$value</span> $more"""
      else if (value startsWith "fail")
        s"""<span class="label label-danger">$value</span> $more"""
      else
        s"""<span class="label label-warning">$value</span> $more"""
  }

  def label(value:String, color:String="default") =
    s"""<span class="label label-$color">$value</span>"""


  case class DomAst(value: Any, kind: String, children: ListBuffer[DomAst] = new ListBuffer[DomAst]()) {
    var moreDetails = " "

    def tos(level: Int): String = ("  " * level) + kind + "::" + value.toString + moreDetails + "\n" + children.map(_.tos(level + 1)).mkString

    override def toString = tos(0)

    // visit/recurse with filter
    def collect[T](f: PartialFunction[DomAst, T]) = {
      val res = new ListBuffer[T]()
      def inspect(d: DomAst, level: Int): Unit = {
        if (f.isDefinedAt(d)) res append f(d)
        d.children.map(inspect(_, level + 1))
      }
      inspect(this, 0)
      res
    }
  }

  class DomEngine(
                   val dom: RDomain,
                   val root: DomAst,
                   val mockMode: Boolean,
                   val sketchMode: Boolean) {

    val maxLevels = 6

    // setup the context for this eval
    implicit val ctx = new ECtx()

    val rules = dom.moreElements.collect {
      case e:ERule => e
    }

    // transform one element / one step
    def expand(a: DomAst, recurse: Boolean = true, level: Int): Unit =
      if (level >= maxLevels) a.children append DomAst(TestResult("fail: Max-Level!", "You have a recursive rule generating this branch..."), "error")
      else a.value match {
        case n: EMsg => {
          // look for mocks
          var mocked = false

          if (mockMode) {
            root.collect {
              case d@DomAst(m: EMock, _, _) if m.rule.e.test(n) && a.children.isEmpty => {
                mocked = true
                // run the mock
                val values = m.rule.i.apply(n, None).collect {
                  // collect resulting values
                  case x: EMsg => {
                    a.children appendAll x.attrs.map(x => DomAst(EVal(x), "generated"))
                    ctx putAll x.attrs
                  }
                }
              }
            }
          }

          // no mocks fit, so let's find rules
          if (!mocked) {
            //} && rules.exists(_.e.test(n))) {
            rules.filter(_.e.test(n)).map { r =>
              mocked = true

              // find the spec of the generated message, to ref
              val spec = dom.moreElements.collect {
                case x: EMsg if x.entity == r.i.cls && x.met == r.i.met => x
              }.headOption

              val news = r.i.apply(n, spec).map(x => DomAst(x, "generated"))
              if (recurse) news.foreach { n =>
                a.children append n
                expand(n, recurse, level + 1)
              }
              true
            }
          }

          /* NEED expects to have a match in and a match out...?

          // last ditch attempt, in sketch mode: if no mocks or rules, run the expects
          if (!mocked && sketchMode) {
            (dom.moreElements.collect {
              case x:ExpectM if x.m.test(n) => x
            }).map{r=>
              mocked=true

              val spec = dom.moreElements.collect {
                case x:EMsg if x.entity == r.i.cls && x.met == r.i.met => x
              }.headOption

              // each rule may recurse and add stuff
              implicit val ctx = ECtx((root.collect {
                case d@DomAst(v:EVal, _, _) => v.p
              }).toList)

              val news = r.i.apply(n, spec).map(x=>DomAst(x, "generated"))
              if(recurse) news.foreach{n=>
                a.children append n
                expand(n, recurse, level+1)
              }
              true
            }
          }

          */
        }

        case e: ExpectM => {
          val cole = new MatchCollector()
          root.collect {
            case d@DomAst(n: EMsg, "generated", _) =>
              cole.newMatch(d)
              if (e.m.test(n, Some(cole)))
                a.children append DomAst(TestResult("ok"), "test")
          }
          cole.done
          // if it matched some Msg then highlight it there
          if (cole.highestMatching.exists(c =>
            c.score >= 2 &&
              c.diffs.values.nonEmpty &&
              c.x.isInstanceOf[DomAst] &&
              c.x.asInstanceOf[DomAst].value.isInstanceOf[EMsg]
          )) {
            val c = cole.highestMatching.get
            val d = c.x.asInstanceOf[DomAst]
            val m = d.value.asInstanceOf[EMsg]
            val s = c.diffs.values.toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")
            d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s
          }
          // add test failure
          if (a.children.isEmpty) a.children append DomAst(
            TestResult(
              "fail",
              cole.highestMatching.map(_.diffs.values.toList.map(x => s"""<span style="color:red">$x</span>""")).mkString),
            "test"
          )
        }

        case e: ExpectV => {
          val cole = new MatchCollector()
          root.collect {
            case d@DomAst(n: EVal, "generated", _) =>
              if (e.test(n.p, Some(cole)))
                a.children append DomAst(TestResult("ok"), "test")
          }
          if (a.children.isEmpty) a.children append DomAst(TestResult("fail"), "test")
        }
        case _ => false
      }
  }

}

