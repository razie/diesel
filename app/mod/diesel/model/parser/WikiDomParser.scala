/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model.parser

import mod.diesel.model.{EMsg, RDExt, EVal}
import razie.clog
import razie.diesel._
import razie.diesel.RDOM._
import razie.wiki.Enc
import razie.wiki.dom.WikiDomain
import razie.wiki.model.WikiEntry
import razie.wiki.parser.{WAST, WikiParserBase}

import scala.Option.option2Iterable
import scala.util.parsing.input.Positional

/** domain parser - for domain sections in a wiki */
trait WikiDomainParser extends WikiParserBase {

  import RDExt._
  import RDOM._
  import WAST._

  def ident: P = """\w+""".r
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

  def ws = whiteSpace

  def ows = opt(whiteSpace)

  def domainBlocks = pobject | pclass | passoc | pfunc | pdfiddle | pwhen | pmatch | preceive | pmsg | pval | pexpectm | pexpectv | pmock

  // todo replace $ with . i.e. .class

  //------------ expressions and conditions

  def expr : Parser[Expr] = ppexpr | pterm1
  def ppexpr : Parser[Expr] = pterm1 ~ rep("+" ~> pterm1)  ^^ {
    case a ~ l if l.isEmpty => a
    case a ~ l => l.foldLeft(a)((a,b) => AExpr2(a, "+", b))
  }
  def pterm1 : Parser[Expr] = numexpr | cexpr | aident //| moreexpr

  def numexpr : Parser[Expr] = number ^^ { case i => new CExpr(i, "Number") }
  def cexpr : Parser[Expr] = "\"" ~> """[^"]*""".r <~ "\"" ^^ { case e => new CExpr (e, "String") }
  def aident : Parser[Expr] = ident ^^ { case i => new AExprIdent(i) }

  //------------ conditions

  def cond : Parser[BExpr] = boolexpr

  def boolexpr: Parser[BExpr] = bterm1|bterm1~"||"~bterm1 ^^ { case a~s~b => bcmp(a,s,b) }
  def bterm1: Parser[BExpr] = bfactor1|bfactor1~"&&"~bfactor1 ^^ { case a~s~b => bcmp(a,s,b) }
  def bfactor1: Parser[BExpr] = eq | neq | lte | gte | lt | gt
  def eq : Parser[BExpr] = expr ~ "==" ~ expr ^^ { case a~s~b => cmp(a,s,b) }
  def neq: Parser[BExpr] = expr ~ "!=" ~ expr ^^ { case a~s~b => cmp(a,s,b) }
  def lte: Parser[BExpr] = expr ~ "<=" ~ expr ^^ { case a~s~b => cmp(a,s,b) }
  def gte: Parser[BExpr] = expr ~ ">=" ~ expr ^^ { case a~s~b => cmp(a,s,b) }
  def lt : Parser[BExpr] = expr ~ "<"  ~ expr ^^ { case a~s~b => cmp(a,s,b) }
  def gt : Parser[BExpr] = expr ~ ">"  ~ expr ^^ { case a~s~b => cmp(a,s,b) }

  def bcmp (a:BExpr, s:String, b:BExpr) = new BCMP1 (a,s,b)
  def cmp  (a:Expr, s:String, b:Expr) = new BCMP2 (a,s,b)

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

  /** assoc : role */
  def clsMet: Parser[(String, String, List[RDOM.P])] = (ident | "*" | jsregex) ~ " *. *".r ~ (ident | "*" | jsregex) ~ optAttrs ^^ {
    case cls ~ _ ~ role ~ a => (cls, role, a)
  }

  def jsregex: P = """/[^/]*/""".r

  /** assoc : role */
  def clsMatch: Parser[(String, String, List[RDOM.PM])] = (ident | "*" | jsregex) ~ " *. *".r ~ (ident | "*" | jsregex) ~ optMatchAttrs ^^ {
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
   */
  def pif: Parser[EIf] = """[.$]if""".r ~> ws ~> optMatchAttrs ^^ {
      case aa => RDExt.EIf(aa)
    }

  /**
   * .match a.role (attrs)  // not used
   */
  def pmatch: PS =
    keyw("""[.$]match""".r) ~ ws ~ clsMatch ~ opt(pif) ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond => {
        LazyState { (current, ctx) =>
          val x = RDExt.EMatch(ac, am, aa, cond)
//          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(x).ifold(current, ctx)
        }
      }
    }

  /**
   * .when a.role (attrs) => z.role (attrs)
   */
  def pwhen: PS =
    keyw("""[.$]when""".r) ~ ws ~ clsMatch ~ ws ~ opt(pif) ~ " *=>".r ~ ows ~ (clsMet | justAttrs) ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ _ ~ _ ~ Tuple3(zc, zm, za) => {
        LazyState { (current, ctx) =>
          val x = RDExt.EMatch(ac, am, aa, cond)
          val y = RDExt.EMap(zc, zm, za)
          val f = RDExt.ERule(x, y)
          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(f).ifold(current, ctx)
        }
      }
    }

  /**
   * .mock a.role (attrs) => z.role (attrs)
   */
  def pmock: PS =
    keyw("""[.$]mock""".r) ~ ws ~ clsMatch ~ opt(pif) ~ " *=>".r ~ ows ~ optAttrs ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond ~ _ ~ _ ~ za => {
        LazyState { (current, ctx) =>
          val x = RDExt.EMatch(ac, am, aa, cond)
          val y = RDExt.EMap("", "", za)
          val f = EMock(ERule(x, y))
          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          f.rule.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
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

  /**
   * name:<>type[kind]*=default
   * <> means it's a ref, not ownership
   * * means it's a list
   */
  def pmatchattr: Parser[RDOM.PM] = " *".r ~> qident ~ opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds) ~ opt(" *\\* *".r) ~ opt(" *".r ~> ("==|~=|!=".r) ~ " *".r ~ value) ^^ {
    case name ~ t ~ multi ~ e => {
      val exp = e match {
        case Some(op ~ _ ~ v) => (op, v)
        case None => ("", "")
      }
      t match {
        case Some(Some(ref) ~ tt ~ k) => PM(name, tt + k.s, ref, multi.mkString, exp._1, exp._2)
        case Some(None ~ tt ~ k) => PM(name, tt + k.s, "", multi.mkString, exp._1, exp._2)
        case None => PM(name, "", "", multi.mkString, exp._1, exp._2)
      }
    }
  }

  /**
   * name:<>type[kind]*=default
   * <> means it's a ref, not ownership
   * * means it's a list
   */
  def pattr: Parser[RDOM.P] = " *".r ~> qident ~ opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds) ~ opt(" *\\* *".r) ~ opt(" *= *".r ~> expr) ^^ {
    case name ~ t ~ multi ~ e => {
      val (dflt, ex) = e match {
        case Some(CExpr(ee, "String")) => (ee, None)
        case Some(expr) => ("", Some(expr))
        case None => ("", None)
      }
      t match {
        case Some(Some(ref) ~ tt ~ k) => P(name, tt + k.s, ref, multi.mkString, dflt, ex)
        case Some(None ~ tt ~ k) => P(name, tt + k.s, "", multi.mkString, dflt, ex)
        case None => P(name, "", "", multi.mkString, dflt, ex)
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

  case class Keyw(s:String) extends Positional

  private def keyw (r:scala.util.matching.Regex) = positioned(pkeyw(r))
  private def pkeyw (r:scala.util.matching.Regex) : Parser[Keyw] = r ^^ {
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
        SState(CanHtml.span("receive::")+f.toHtmlInPage)
      }
    }
  }

  /**
   * .msg object.func (a,b)
   *
   * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
   */
  def pmsg: PS = keyw("[.$]msg *".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case k ~ stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
      LazyState { (current, ctx) =>

        val ea = ent+"."+ac
        val archn =
          if(stype.exists(_.length > 0)) stype.mkString.trim
          else {
            val sc = ctx.we.flatMap(_.templateSections.find(_.name == ea)).map(_.content).mkString
            if("" != sc) EESnakk.parseTemplate(sc).method else ""
          }

        val f = EMsg("def", ent, ac, attrs, ret.toList.flatten(identity), archn)


        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtmlInPage+"<br>")
      }
    }
  }

  /**
   * .msg object.func (a,b)
   *
   * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
   */
  def linemsg (wpath:String) = keyw("[.$]msg *".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ ident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case k ~ stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
        val f = EMsg("def", ent, ac, attrs, ret.toList.flatten(identity), stype.mkString.trim)
        f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f
    }
  }

  /**
   * .mock a.role (attrs) => z.role (attrs)
   */
  def linemock (wpath:String) =
    keyw("""[.$]mock""".r) ~ ws ~ clsMatch ~ opt(pif) ~ " *=> *".r ~ optAttrs ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond ~ _ ~ za => {
          val x = RDExt.EMatch(ac, am, aa, cond)
          val y = RDExt.EMap("", "", za)
          val f = EMock(ERule(x, y))
          f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
          f.rule.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f
      }
    }

  /**
   * .expect object.func (a,b)
   */
  def pexpectm: PS = keyw("[.$]expect *[$]msg *".r) ~ ident ~ " *\\. *".r ~ ident ~ optMatchAttrs ~ opt(pif) ^^ {
    case k ~ ent ~ _ ~ ac ~ attrs ~ cond => {
      LazyState { (current, ctx) =>
        val f = RDExt.ExpectM(RDExt.EMatch(ent, ac, attrs, cond))
        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtml+"<br>")
      }
    }
  }

  /**
   * .expect object.func (a,b)
   */
  def pexpectv: PS = keyw("[.$]expect * [$]val *".r) ~ optMatchAttrs ^^ {
    case k ~ a => {
      LazyState { (current, ctx) =>
        val f = RDExt.ExpectV(a)
        f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        SState(f.toHtml+"<br>")
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

  private def trim (s:String) = s.replaceAll("\r", "").replaceAll("^\n|\n$","")//.replaceAll("\n", "\\\\n'\n+'")

  // {{diesel name:type
  def pdfiddle: PS = "{{" ~> """dfiddle""".r ~ "[: ]".r ~ """[^:}]*""".r ~ "[: ]".r ~ """[^:}]*""".r ~ optargs ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ "{{/dfiddle}}" ^^ {
    case d ~ _ ~ name ~ _ ~ kind ~ xargs ~ _ ~ _ ~ lines =>
      var args = xargs.toMap
//      val name = args.getOrElse("name", "")

      try {
        LazyState { (current, ctx) =>
//          if (!(args contains "tab"))
//            args = args + ("tab" -> lang)

          val links = lines.s.lines.collect {
            case l if l.startsWith("$msg") =>
              parseAll(linemsg(ctx.we.get.wid.wpath), l).map {st=>
                st.toHref(name)
              }.getOrElse("???")
            case l if l.startsWith("$mock") =>
              parseAll(linemock(ctx.we.get.wid.wpath), l).map {st=>
                st.rule.e.asMsg.withPos(st.pos).toHref(name)
              }.getOrElse("???")
            }.mkString("\n")
          SState(views.html.fiddle.inlineDomFiddle(ctx.we.get.wid, ctx.we, name, args, trim(lines.s), links).body)
        }
      }
      catch  {
        case t : Throwable =>
          if(admin.Config.isLocalhost) throw t // debugging
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
abstract class BExpr(e:String) extends HasDsl {
  def apply (e:Any)(implicit ctx:ECtx) : Boolean
  override def toDsl = e
}

/** negated boolean expression */
case class BCMPNot(a: BExpr) extends BExpr("") {
  override def apply (e:Any)(implicit ctx:ECtx) = !a.apply(e)
}

/** composed boolean expression */
case class BCMP1 (a:BExpr, op:String, b:BExpr) extends BExpr (a.toDsl+" "+op+" "+b.toDsl) {
  override def apply (in:Any)(implicit ctx:ECtx) = op match {
    case "||" => a.apply(in) || b.apply(in)
    case "&&" => a.apply(in) && b.apply(in)
    case _ => { clog << "[ERR Operator " + op + " UNKNOWN!!!]"; false}
  }
  override def toString = a.toString+" "+op+" "+b.toString
}

/** simple boolean expression */
case class BCMP2  (a:Expr, op:String, b:Expr) extends BExpr (a.toDsl+" "+op+" "+b.toDsl) {
  override def apply (in:Any)(implicit ctx:ECtx) = op match {
    case "==" => a(in) == b(in)
    case "!=" => a(in) != b(in)
    case "~=" => a(in).toString matches b(in).toString
    case "<=" => a(in).toString <= b(in).toString
    case ">=" => a(in).toString >= b(in).toString
    case "<" => a(in).toString < b(in).toString
    case ">" => a(in).toString > b(in).toString
    case _ => { clog << "[ERR Operator " + op + " UNKNOWN!!!]"; false}
  }
}


