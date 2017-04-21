/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model.parser

import mod.diesel.model.RDExt
import razie.diesel.ext._
import razie.clog
import razie.diesel._
import razie.diesel.dom._
import razie.diesel.dom.RDOM
import RDOM._
import razie.wiki.{Enc, Services}
import razie.wiki.dom.{WikiDTemplate, WikiDomain}
import razie.wiki.model.WikiEntry
import razie.wiki.parser.{WAST, WikiParserBase}

import scala.Option.option2Iterable
import scala.util.Try
import scala.util.parsing.input.Positional

class FlowExpr()

case class SeqExpr(op: String, l: Seq[FlowExpr]) extends FlowExpr {
  override def toString = l.mkString(op)
}

case class MsgExpr(ea: String) extends FlowExpr {
  override def toString = ea
}

case class BFlowExpr(b: FlowExpr) extends FlowExpr {
  override def toString = s"( $b )"
}

/** domain parser - for domain sections in a wiki */
trait WikiDomainParser extends WikiParserBase {

  import RDExt._
  import RDOM._
  import WAST._

  def ident: P = """\w+""".r

  def qqident: P =
    """[\w.]+""" ~ rep("." ~> ident) ^^ {
      case i ~ l => (i :: l).mkString(".")
    }

  def qident: P = ident ~ rep("." ~> ident) ^^ {
    case i ~ l => (i :: l).mkString(".")
  }

  def any: P = """.*""".r

  //todo full expr with +-/* and XP
  def value: P = qident | number | str

  def number: P = """\d+""".r

  // todo commented - if " not included in string, evaluation has trouble - see expr(s)
  // todo see stripq and remove it everywhere when quotes die and proper type inference is used
  def str: P = "\"" ~> """[^"]*""".r <~ "\""

  //  def str: P = """"[^"]*"""".r

  def domainBlocks = pobject | pclass | passoc | pfunc | pdfiddle | pwhen | pflow | pmatch | preceive | pmsg | pval | pexpectm | pexpectv | pexpect | pmock

  // todo replace $ with . i.e. .class

  //------------ expressions and conditions

  def expr: Parser[Expr] = ppexpr | pterm1

  def ppexpr: Parser[Expr] = pterm1 ~ rep(ows ~> ("+" | "-" | "|") ~ ows ~ pterm1) ^^ {
    case a ~ l if l.isEmpty => a
    case a ~ l => l.foldLeft(a)((a, b) =>
      b match {
        case op ~ _ ~ p => AExpr2(a, op, p)
      }
    )
  }

  def pterm1: Parser[Expr] = numexpr | cexpr | aident | exregex | eblock | js

  def eblock: Parser[Expr] = "(" ~ ows ~> expr <~ ows ~ ")" ^^ { case ex => BlockExpr(ex) }

  def js: Parser[Expr] = "{" ~ ows ~> jexpr <~ ows ~ "}" ^^ { case ex => JBlockExpr(ex) }

  def jblock: Parser[String] = "{" ~ ows ~> jexpr <~ ows ~ "}" ^^ { case ex => ex }

  def jexpr1: Parser[String] = jother ~ jblock ~ jother ^^ { case a ~ b ~ c => a + b.toString + c }

  def jexpr: Parser[String] = jblock | jexpr1 | jother ^^ { case ex => ex.toString }

  def jother: Parser[String] = "[^{}]+".r ^^ { case ex => ex }

  // a number
  def numexpr: Parser[Expr] = number ^^ { case i => new CExpr(i, "Number") }

  // string const
  def cexpr: Parser[Expr] = "\"" ~> """[^"]*""".r <~ "\"" ^^ { case e => new CExpr(e, "String") }

  // qualified identifier
  def aident: Parser[Expr] = qident ^^ { case i => new AExprIdent(i) }

  // regular expression, JS style
  def exregex: Parser[Expr] =
    """/[^/]*/""".r ^^ { case x => new CExpr(x, "Regex") }

  //------------ conditions

  def cond: Parser[BExpr] = boolexpr

  def boolexpr: Parser[BExpr] = bterm1 | bterm1 ~ "||" ~ bterm1 ^^ { case a ~ s ~ b => bcmp(a, s.trim, b) }

  def bterm1: Parser[BExpr] = bfactor1 | bfactor1 ~ "&&" ~ bfactor1 ^^ { case a ~ s ~ b => bcmp(a, s.trim, b) }

  def bfactor1: Parser[BExpr] = eq | neq | lte | gte | lt | gt

  def like: Parser[BExpr] = expr ~ "~=" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def df: Parser[BExpr] = expr ~ "?=" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def eq: Parser[BExpr] = expr ~ "==" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def neq: Parser[BExpr] = expr ~ "!=" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def lte: Parser[BExpr] = expr ~ "<=" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def gte: Parser[BExpr] = expr ~ ">=" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def lt: Parser[BExpr] = expr ~ "<" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def gt: Parser[BExpr] = expr ~ ">" ~ expr ^^ { case a ~ s ~ b => cmp(a, s.trim, b) }

  def bcmp(a: BExpr, s: String, b: BExpr) = new BCMP1(a, s, b)

  def cmp(a: Expr, s: String, b: Expr) = new BCMP2(a, s, b)

  // ---------------------- flow expressions

  def flowexpr: Parser[FlowExpr] = seqexpr

  def seqexpr: Parser[FlowExpr] = parexpr ~ rep(ows ~> ("+" | "-") ~ ows ~ parexpr) ^^ {
    case a ~ l =>
      SeqExpr("+", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parexpr: Parser[FlowExpr] = parterm1 ~ rep(ows ~> ("|" | "||") ~ ows ~ parterm1) ^^ {
    case a ~ l =>
      SeqExpr("|", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parterm1: Parser[FlowExpr] = parblock | msgterm1

  def parblock: Parser[FlowExpr] = "(" ~ ows ~> seqexpr <~ ows ~ ")" ^^ {
    case ex => BFlowExpr(ex)
  }

  def msgterm1: Parser[FlowExpr] = qident ^^ { case i => new MsgExpr(i) }

  // ----------------------

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
  def justAttrs: Parser[(String, String, List[RDOM.P])] = attrs ^^ {
    case a => ("", "", a)
  }

  /** p.a.ent.met - qualified ent so at least two elements
    *
    * @return (ent, act, fullString) */
  def qualified(qcm: List[String]): (String, String, String) = {
    val ea = qcm.mkString(".") //ent+"."+ac
    val ent = qcm.dropRight(1).mkString(".")
    val act = qcm.takeRight(1).mkString //ent+"."+ac
    (ent, act, ea)
  }

  /** ent.met - qualified ent so at least two elements
    *
    * @return (ent, act, fullString) */
  def qclsMet: Parser[(String, String, String)] = (ident | "*" | jsregex) ~ "." ~ (ident | "*" | jsregex) ~ rep("." ~> (ident | "*" | jsregex)) ^^ {
    case i ~ _ ~ j ~ l => {
      qualified(i :: j :: l)
    }
  }

  /** ent.met */
  def clsMet: Parser[(String, String, List[RDOM.P])] = (ident | "*" | jsregex) ~ " *. *".r ~ (ident | "*" | jsregex) ~ rep("." ~> (ident | "*" | jsregex)) ~ optAttrs ^^ {
    case i ~ _ ~ j ~ l ~ a => {
      val qcm = qualified(i :: j :: l)
      (qcm._1, qcm._2, a)
    }
  }

  def jsregex: P = """/[^/]*/""".r

  /** pattern match for ent.met */
  def clsMatch: Parser[(String, String, List[RDOM.PM])] = (ident | "*" | jsregex) ~ " *. *".r ~ (ident | "*" | jsregex) ~ rep("." ~> (ident | "*" | jsregex)) ~ optMatchAttrs ^^ {
    case i ~ _ ~ j ~ l ~ a => {
      val qcm = qualified(i :: j :: l)
      (qcm._1, qcm._2, a)
    }
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
    */
  def pif: Parser[EIf] =
    """[.$]if""".r ~> ws ~> optMatchAttrs ^^ {
      case aa => EIf(aa)
    }

  /**
    * .match a.role (attrs)  // not used
    */
  def pmatch: PS =
    keyw("""[.$]match""".r) ~ ws ~ clsMatch ~ opt(pif) ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond => {
        LazyState { (current, ctx) =>
          val x = EMatch(ac, am, aa, cond)
          //          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(x).ifold(current, ctx)
        }
      }
    }

  def pArrow: Parser[String] = "=>" | "==>" ^^ {
    case s => s
  }

  /**
    * => z.role (attrs)
    **/
  def pgen: Parser[EMap] =
    ows ~> pArrow ~ ows ~ opt(pif) ~ ows ~ (clsMet | justAttrs) ^^ {
      case arrow ~ _ ~ cond ~ _ ~ Tuple3(zc, zm, za) => {
        EMap(zc, zm, za, arrow, cond)
      }
    }

  /**
    * .when a.role (attrs) => z.role (attrs)
    */
  def pwhen: PS =
    keyw("""[.$]when|[.$]mock""".r) ~ ws ~ clsMatch ~ ws ~ opt(pif) ~ rep(pgen) ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ gens => {
        LazyState { (current, ctx) =>
          val x = EMatch(ac, am, aa, cond)
          val r = ERule(x, gens)
          r.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          val f = if (k.s contains "when") r else EMock(r)
          addToDom(f).ifold(current, ctx)
        }
      }
    }

  /**
    * .flow e.a => expr
    */
  def pflow: PS =
    keyw("""[.$]flow""".r) ~ ws ~ clsMatch ~ ws ~ opt(pif) ~ " *=>".r ~ ows ~ flowexpr ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ _ ~ _ ~ ex => {
        LazyState { (current, ctx) =>
          val x = EMatch(ac, am, aa, cond)
          val f = EFlow(x, ex)
          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(f).ifold(current, ctx)
        }
      }
    }

  /**
    * .mock a.role (attrs) => z.role (attrs)
    */
  def pmock: PS =
    keyw("""[.$]xmock""".r) ~ ws ~ clsMatch ~ opt(pif) ~ " *=>".r ~ ows ~ optAttrs ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond ~ _ ~ _ ~ za => {
        LazyState { (current, ctx) =>
          val x = EMatch(ac, am, aa, cond)
          val y = EMap("", "", za)
          val f = EMock(ERule(x, List(y)))
          f.rule.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(f).ifold(current, ctx)
        }
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
  def attrs: Parser[List[RDOM.P]] = " *\\(".r ~> ows ~> repsep(pattr, "," ~ ows) <~ ows <~ ")"

  /**
    * optional attributes
    */
  def optAttrs: Parser[List[RDOM.P]] = opt(attrs) ^^ {
    case Some(a) => a
    case None => List.empty
  }

  /**
    * optional attributes
    */
  def optMatchAttrs: Parser[List[RDOM.PM]] = opt(" *\\(".r ~> ows ~> repsep(pmatchattr, "," ~ ows) <~ ows <~ ")") ^^ {
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

  def OPS1: Parser[String] = "==|~=|!=|\\?=|>=|<=|>|<|contains|is".r

  /**
    * name:<>type[kind]*=default
    * <> means it's a ref, not ownership
    * means it's a list
    *
    * pmatch is more than just a simple conditional expression
    */
  def pmatchattr: Parser[RDOM.PM] = " *".r ~> qident ~ opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds) ~ opt(" *\\* *".r) ~ opt(" *".r ~> OPS1 ~ " *".r ~ expr) ^^ {
    case name ~ t ~ multi ~ e => {
      var ttype = ""
      var dflt = ""
      val exp = e match {
        case Some(op ~ _ ~ v) => {
          if(v.isInstanceOf[CExpr]) ttype = v.asInstanceOf[CExpr].ttype
          (op, Some(v))
        }
        case None => ("", None)
      }
      t match {
        case Some(Some(ref) ~ tt ~ k) => PM(name, tt + k.s, ref, multi.mkString, exp._1, dflt, exp._2)
        case Some(None ~ tt ~ k) => PM(name, tt + k.s, "", multi.mkString, exp._1, dflt, exp._2)
        case None => PM(name, ttype, "", multi.mkString, exp._1, dflt, exp._2)
      }
    }
  }

  /**
    * name:<>type[kind]*=default
    * <> means it's a ref, not ownership
    * * means it's a list
    */
  def pattr: Parser[RDOM.P] = " *".r ~> qident ~ opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds) ~ opt(" *\\* *".r) ~ opt(" *~?= *".r ~> expr) ^^ {
    case name ~ t ~ multi ~ e => {
      val (dflt, ex) = e match {
        case Some(CExpr(ee, "String")) => (ee, None)
        case Some(expr) => ("", Some(expr))
        case None => ("", None)
      }
      t match {
        case Some(Some(ref) ~ tt ~ k) => P(name, tt + k.s, ref, multi.mkString, dflt, ex)
        case Some(None ~ tt ~ k) => P(name, dflt, tt + k.s, "", multi.mkString, ex)
        case None => P(name, dflt, "", "", multi.mkString, ex)
      }
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
  def pval: PS = "[.$]val *".r ~> pattr ^^ {
    case a => {
      LazyState { (current, ctx) =>
        val v = EVal(a)
        collectDom(v, ctx.we)
        SState(v.toHtml)
      }
    }
  }

  case class Keyw(s: String) extends Positional

  private def keyw(r: Parser[String]) = positioned(r.map(s => Keyw(s)))

  private def keyw(r: scala.util.matching.Regex) = positioned(pkeyw(r))

  private def pkeyw(r: scala.util.matching.Regex): Parser[Keyw] = r ^^ {
    case s => Keyw(s)
  }

  /**
    * .receive object.func (a,b)
    *
    * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
    */
  def preceive: PS = keyw("[.$]receive *".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case k ~ stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
      LazyState { (current, ctx) =>
        val f = EMsg("receive", ent, ac, attrs, ret.toList.flatten(identity), stype.mkString.trim)
        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(CanHtml.span("receive::") + f.toHtmlInPage + "<br>")
      }
    }
  }

  /**
    * .msg object.func (a,b)
    *
    * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
    */
  def pmsg: PS = keyw("[.$]msg *".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ qclsMet ~ optAttrs ~ opt(" *(:|=>) *".r ~> optAttrs) ^^ {
    case k ~ stype ~ qcm ~ attrs ~ ret => {
      LazyState { (current, ctx) =>

        val archn =
          if (stype.exists(_.length > 0)) stype.mkString.trim
          else {
            // todo snakkers need to be plugged in and insulated better
            // find snakker and import stype
            val t = ctx.we.flatMap(_.templateSections.find(_.name == qcm._3))
            val sc = t.map(_.content).mkString
            if ("" != sc) Try {
              EESnakk.parseTemplate(t.map(new WikiDTemplate(_)), sc, attrs).method
            }.getOrElse("") else ""
          }

        val f = EMsg("def", qcm._1, qcm._2, attrs, ret.toList.flatten(identity), archn)

        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtmlInPage + "<br>")
      }
    }
  }

  /**
    * .msg object.func (a,b)
    *
    * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
    */
  def linemsg(wpath: String) = keyw("[.$]msg *".r | "[.$]receive\\s*".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case k ~ stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
      val f = EMsg("def", ent, ac, attrs, ret.toList.flatten(identity), stype.mkString.trim)
      f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
      f
    }
  }

  /**
    * .mock a.role (attrs) => z.role (attrs)
    */
  def linemock(wpath: String) =
    keyw("""[.$]mock""".r) ~ ws ~ clsMatch ~ opt(pif) ~ " *=> *".r ~ optAttrs ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond ~ _ ~ za => {
        val x = EMatch(ac, am, aa, cond)
        val y = EMap("", "", za)
        val f = EMock(ERule(x, List(y)))
        f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f.rule.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f
      }
    }

  /**
    * .expect object.func (a,b)
    */
  def pexpect: PS = keyw("[.$]expect".r <~ ws) ~ opt(qclsMet) ~ optMatchAttrs ~ opt(pif) ^^ {
    case k ~ qcm ~ attrs ~ cond => {
      LazyState { (current, ctx) =>
        val pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        val f = qcm.map(qcm => ExpectM(EMatch(qcm._1, qcm._2, attrs, cond)).withPos(pos)).getOrElse(ExpectV(attrs).withPos(pos))
        collectDom(f, ctx.we)
        SState(f.toHtml + "<br>")
      }
    }
  }

  /**
    * todo obsolete
    */
  def pexpectm: PS = keyw("[.$]expect\\s+[$]msg\\s+".r) ~ qclsMet ~ optMatchAttrs ~ opt(pif) ^^ {
    case k ~ qcm ~ attrs ~ cond => {
      LazyState { (current, ctx) =>
        val f = ExpectM(EMatch(qcm._1, qcm._2, attrs, cond))
        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtml + "<br>")
      }
    }
  }

  /**
    * todo obsolete
    */
  def pexpectv: PS = keyw("[.$]expect\\s+[$]val\\s+".r) ~ optMatchAttrs ^^ {
    case k ~ a => {
      LazyState { (current, ctx) =>
        val f = ExpectV(a)
        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtml + "<br>")
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
  def pfunc: PS = "[.$]def *".r ~> qident ~ optAttrs ~ opt(" *: *".r ~> ident) ~ optScript ~ optBlock ^^ {
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
      case Some(Some(ref) ~ tt) => P(name, e.mkString, tt, ref, multi.mkString)
      case Some(None ~ tt) => P(name, e.mkString, tt.mkString, "", multi.mkString)
      case None => P(name, e.mkString, "", "", multi.mkString)
    }
  }

  // not used yet - class member val
  def scall: Parser[EXEC] = ows ~> ident ~ "." ~ ident ~ optAttrs ^^ {
    case cls ~ _ ~ func ~ attres =>
      new ExecCall(cls, func, attres)
  }

  private def trim(s: String) = s.replaceAll("\r", "").replaceAll("^\n|\n$", "") //.replaceAll("\n", "\\\\n'\n+'")

  // {{diesel name:type
  def pdfiddle: PS = "{{" ~> """dfiddle""".r ~ "[: ]+".r ~ """[^:}]*""".r ~ "[: ]*".r ~ """[^ :}]*""".r ~ optargs ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ "{{/dfiddle}}" ^^ {
    case d ~ _ ~ name ~ _ ~ kind ~ xargs ~ _ ~ _ ~ lines =>
      var args = xargs.toMap
      //      val name = args.getOrElse("name", "")

      try {
        LazyState { (current, ctx) =>
          //          if (!(args contains "tab"))
          //            args = args + ("tab" -> lang)

          var links = lines.s.lines.collect {
            case l if l.startsWith("$msg") || l.startsWith("$receive") =>
              parseAll(linemsg(ctx.we.get.wid.wpath), l).map { st =>
                st.toHref(name)
              }.getOrElse("???")
            case l if l.startsWith("$mock") =>
              parseAll(linemock(ctx.we.get.wid.wpath), l).map { st =>
                st.rule.e.asMsg.withPos(st.pos).toHref(name, "value") +
                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", name, "json") + ") " +
                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("trace", name, "debug") + ") "
              }.getOrElse("???")
          }.mkString("\n")

          if (links == "") links = "no recognized messages"

          SState(views.html.fiddle.inlineDomFiddle(ctx.we.get.wid, ctx.we, name, kind, args, trim(lines.s), links, args.contains("anon"), ctx.au).body)
        }
      }
      catch {
        case t: Throwable =>
          if (Services.config.isLocalhost) throw t // debugging
          SState(s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>""")
      }
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


// exprs

/** boolean expressions */
abstract class BExpr(e: String) extends HasDsl {
  def apply(e: Any)(implicit ctx: ECtx): Boolean

  override def toDsl = e
}

/** negated boolean expression */
case class BCMPNot(a: BExpr) extends BExpr("") {
  override def apply(e: Any)(implicit ctx: ECtx) = !a.apply(e)
}

/** composed boolean expression */
case class BCMP1(a: BExpr, op: String, b: BExpr) extends BExpr(a.toDsl + " " + op + " " + b.toDsl) {
  override def apply(in: Any)(implicit ctx: ECtx) = op match {
    case "||" => a.apply(in) || b.apply(in)
    case "&&" => a.apply(in) && b.apply(in)
    case _ => {
      clog << "[ERR Operator " + op + " UNKNOWN!!!]"; false
    }
  }

  override def toString = a.toString + " " + op + " " + b.toString
}

/** simple boolean expression */
case class BCMP2(a: Expr, op: String, b: Expr) extends BExpr(a.toDsl + " " + op + " " + b.toDsl) {
  override def apply(in: Any)(implicit ctx: ECtx) = {
    (a, b) match {
      case (CExpr(aa, "Number"), CExpr(bb, "Number")) => {
        val ai = aa.toInt
        val bi = bb.toInt
        op match {
          case "?=" => true
          case "==" => ai == bi
          case "!=" => ai != bi
          case "<=" => ai <= bi
          case ">=" => ai >= bi
          case "<" => ai < bi
          case ">" => ai > bi
          case _ => {
            clog << "[ERR Operator " + op + " UNKNOWN!!!]";
            false
          }
        }
      }
      case _ => {
        val as = a(in).toString
        val bs = b(in).toString
        val x = as matches bs
        op match {
          case "?=" => a(in).toString.length >= 0 // anything with a default
          case "==" => a(in) == b(in)
          case "!=" => a(in) != b(in)
          case "~=" => a(in).toString matches b(in).toString
          case "<=" => a(in).toString <= b(in).toString
          case ">=" => a(in).toString >= b(in).toString
          case "<" => a(in).toString < b(in).toString
          case ">" => a(in).toString > b(in).toString
          case "contains" => a(in).toString contains b(in).toString
          case "is" => {
            // is nuber or is date or is string etc
            a.isInstanceOf[CExpr] && b.isInstanceOf[AExprIdent] &&
              a.asInstanceOf[CExpr].ttype.toLowerCase == b.asInstanceOf[AExprIdent].expr.toLowerCase
          }
          case _ => {
            clog << "[ERR Operator " + op + " UNKNOWN!!!]";
            false
          }
        }
      }
    }
  }
}


