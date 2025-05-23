/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDOM, WType, WTypes, XPathIdent}
import razie.diesel.engine.nodes.PAS
import scala.util.parsing.combinator.RegexParsers

/**
  * expressions parser. this is a trait you can mix in your other DSL parsers,
  * see SimpleExprParser for a concrete implementation
  *
  * See http://specs.razie.com/wiki/Story:expr_story for possible expressions and examples
  */
trait ExprParser extends RegexParsers {

  // mandatory whiteSpace
  def ws = whiteSpace

  // optional whiteSpace
  // todo include comments
  def ows = opt(whiteSpace) // | "/\*.*\*/".r

//  def pComment: Parser[String] = "//.*".r  | "(?m)/\\*(\\*(?!/)|[^*])*\\*/)".r ^^ {
//def pComment: Parser[String] = """(\s|//.*|(?m)/\*(\*(?!/)|[^*])*\*/)+""".r ^^ {
  def pComment: Parser[String] = """(//[^\n]*|(?m)/\*(\*(?!/)|[^*])*\*/)+""".r ^^ {
    case s => s
  }

  def optComment: Parser[String] = opt(ows ~> pComment) ^^ {
    case s => s.mkString
  }

  def optComment2: Parser[String] = opt(pComment) ^^ {
    case s => s.mkString
  }

  def optComment3: Parser[String] = opt(" *//.*".r) ^^ {
    case s => s.mkString
  }

  //
  //======================= MAIN: operator expressions and conditions ========================
  //

  private def opsAS: Parser[String] = "as"

  private def opsMAP: Parser[String] = "map" <~ ws |
      "fold" <~ ws |
      "foreach" <~ ws |
      "flatMap" <~ ws |
      "indexBy" <~ ws |
      "flatten" <~ ws |
      "filter" <~ ws |
      "exists" <~ ws |
      "split" <~ ws |
      "mkString" <~ ws |
      "catPath" <~ ws |
      "|c" <~ ws | "|>" <~ ws | "|" <~ ws |
  // streams:
      ">>>" <~ ws | ">>" <~ ws | "<<<" <~ ws | "<<" <~ ws

  private def opsOR: Parser[String] = "or" | "xor"

  private def opsAND: Parser[String] = "and"

  private def opsCMP: Parser[String] =
    ">=" | "<=" | ">" | "<" | "==" | "!=" |
        "~=" | "~path" <~ ws |
        "?=" | "is" <~ ws | "xNot" <~ ws | "in" <~ ws | "not in" <~ ws | "notIn" <~ ws | "not" <~ ws |
        "contains" <~ ws | "containsNot" <~ ws

  private def opsPLUS: Parser[String] = "+=" | "+" | "-" | "||" | "|"

  private def opsMULT: Parser[String] = "*" | "/(?!/)".r // negative lookahead to not match comment - it acts funny
  // with multiple lines of comment


  //--------------------------- expressions

  /** main entry point for an expression */
  def expr: Parser[Expr] = exprAS | pterm

  // a reduced expr, from boolean down, useful for lambdas for conditions
  def expr2: Parser[Expr] = exprOR | pterm

  private def faexpr2: (Expr, String, Expr) => Expr = { (a, b, c) =>
//    if (b == ">>") AExprFunc(
//      c.asInstanceOf[AExprIdent].start,
//      List(P("", "", WTypes.wt.UNKNOWN, Some(a.asInstanceOf[AExprIdent]))))
//    else
      AExpr2(a, b, c)
  }

  // "1" as number
  def exprAS: Parser[Expr] = exprMAP ~ opt(ows ~> opsAS ~ ows ~ pterm) ^^ {
    case a ~ None => a
    case a ~ Some(op ~ _ ~ p) => AExpr2(a, op, p)
  }

  // x map (x => x+1)
  def exprMAP: Parser[Expr] = exprOR ~ rep(ows ~> opsMAP ~ ows ~ exprOR) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, faexpr2)
  }

  // x > y or ...
  def exprOR: Parser[Expr] = exprAND ~ rep(ows ~> (opsOR <~ ws) ~ ows ~ exprAND) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, bMkAndOr)
  }

  // x > y and ...
  def exprAND: Parser[Expr] = (exprNOTCMP | exprCMP) ~ rep(ows ~> (opsAND <~ ws) ~ ows ~ (exprNOTCMP | exprCMP)) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, bMkAndOr)
  }

  def exprNOTCMP: Parser[Expr] = (("not" | "NOT") <~ ws) ~> ows ~> exprCMP ^^ {
    case e if e.isInstanceOf[BoolExpr] => BCMPNot(e.asInstanceOf[BoolExpr])
    case x => BCMPNot(BCMPSingle(x))
  }

  // x > y
  def exprCMP: Parser[Expr] = exprPLUS ~ opt(ows ~> opsCMP ~ ows ~ exprPLUS) ^^ {
    case a ~ None => a
    case a ~ Some(op ~ _ ~ b) => BCMP2(a, op, b)
  }

  // x + y
  def exprPLUS: Parser[Expr] = exprMULT ~ rep(ows ~> opsPLUS ~ ows ~ exprMULT) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, AExpr2)
  }

  // x * y
  def exprMULT: Parser[Expr] = pterm ~ rep(ows ~> opsMULT ~ ows ~ pterm) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, AExpr2)
  }

  //   foldLeft associative expressions
  private def foldAssocAexpr2[EXP](a:EXP, l:List[String ~ Option[String] ~ EXP], f:(EXP, String, EXP) => EXP) : EXP = {
    l.foldLeft(a)((x, y) =>
      y match {
        case op ~ _ ~ p => f(x, op, p)
      }
    )
  }

  //
  //================== main expression rules
  //

  // a term in an expression
  def pterm: Parser[Expr] =
    numConst | boolConst | multilineStrConst | strConst | jnull |
        xpident |
        lambda | jsexpr2 | jsexpr1 |
        exregex | eblock | jarray1 | jarray2 | jobj |
        scalaexpr2 | scalaexpr1 |
        callFunc | aidentaccess | aident | jsexpr4
//    exregex | eblock | jarray | jobj

  //
  //============================== idents
  //

  /** a regular ident but also something in single quotes 'a@habibi.34 and - is a good ident eh' */
  def ident: Parser[String] = """[a-zA-Z_][\w]*""".r | """'[\w@. -]+'""".r ^^ {
    case s =>
      if(s.startsWith("'") && s.endsWith("'"))
        s.substring(1, s.length-1)
      else
        s
  }

  /** allow JSON ids (in inline-json docs left side) with double quotes, single quotes or no quotes */
  def jsonIdent: Parser[String] = """[a-zA-Z_][\w]*""".r | """'[^']+'""".r | """"[^"]+"""".r ^^ {
    case s => unquote(s)
  }

  /** qualified idents, . notation, parsed as a single string */
  def qident: Parser[String] = ident ~ rep("." ~> ident) ^^ {
    case i ~ l => (i :: l).mkString(".")
  }

  /** generic qualified ident including diesel exprs */
  def qlident: Parser[List[String]] = qidentDiesel | qualifiedIdent

  // fix this somehow - these need to be accessed as this - they should be part of a "diesel" object with callbacks
  def qidentDiesel: Parser[List[String]] = "diesel" ~ "." ~ qualifiedIdent ^^ {
    case d ~ dot ~ i => d :: i
  }

  /** qualified idents, . notation, parsed as a list */
  def qualifiedIdent: Parser[List[String]] = ident ~ rep("." ~> ident) ^^ {
    case i ~ l => i :: l
  }

  def xpath: Parser[String] = ("*" | "**" | xpathElem) ~ rep(" */ *".r ~ xpathElem) ^^ {
    case i ~ l => (i :: l.map { x => x._1 + x._2}).mkString("")
  }

  // /{attr}@gigi:$name[cond]/
  def xpathElem: Parser[String] = """(\{.*\})*([@])*([\w]+\:)*([\$|\w\.-]+|\**) *(\[.*\])*""".r ^^ {
    case e => e.toString
  }

  def jpath: Parser[String] = ident ~ rep("[/@.]+".r ~ ident) ^^ {
    case i ~ l => (i :: l.map { x => x._1.replace(".", "/") + x._2 }).mkString("")
  }

  //
  //==================== lambdas
  //

  // x => x + 4
  def lambda: Parser[Expr] = ident ~ ows ~ "=>" ~ ows ~ (expr2 | "(" ~> expr <~ ")") ^^ {
    case arg ~ _ ~ a ~ _ ~ ex => LambdaFuncExpr(arg, ex)
  }

  //
  //================================== constants - CExpr
  //

  // a number
  def numConst: Parser[Expr] = (afloat | aint) ^^ { case i => new CExpr(i, WTypes.wt.NUMBER) }

  def aint: Parser[String] = """-?\d+""".r

  def afloat: Parser[String] = """-?\d+[.]\d+""".r

  /** prepare a parsed string const */
  private def prepStrConst(e: String): CExpr[String] = {
    // string const with escaped chars
    var s = e

    // replace standard escapes like java does
    s = s
        .replaceAll("(?<!\\\\)\\\\b", "\b")
        .replaceAll("(?<!\\\\)\\\\n", "\n")
        .replaceAll("(?<!\\\\)\\\\t", "\t")
        .replaceAll("(?<!\\\\)\\\\r", "\r")
        .replaceAll("(?<!\\\\)\\\\f", "\f")

    // kind of like java, now replace anything escaped
    // note that Java only replaces a few things, others generate errors
    // we replace anything
    s = s.replaceAll("\\\\(.)", "$1")

    new CExpr(s, WTypes.wt.STRING)
  }

  // string const with escaped chars
  def strConst: Parser[Expr] = "\"" ~> """(\\.|[^\"])*""".r <~ "\"" ^^ {
    e => prepStrConst(e)
  }

  // string const with escaped chars
  def strConstSingleQuote: Parser[Expr] = "\'" ~> """(\\.|[^\'])*""".r <~ "\'" ^^ {
    e => prepStrConst(e)
  }


  // escaped multiline string const with escaped chars
  // we're removing the first \n
  def multilineStrConst: Parser[Expr] = "\"\"\"" ~ opt("\n") ~> """(?s)((?!\"\"\").)*""".r <~ "\"\"\"" ^^ {
    e => prepStrConst(e)
  }

  // xp or jp
  def xpident: Parser[Expr] = xpp //| jpp

  // XP identifier (either json or xml)
  def xpp: Parser[Expr] = (("xp:" | "xpl:" | "xpe:" | "xpa:" | "xpla:") ~ xpath) ^^ {
    case prefix ~ i => new XPathIdent(prefix.replaceAllLiterally(":","" ), i)
  }

  // XP identifier (either json or xml)
//  def jpp: Parser[Expr] = ("jp:" ~ jpath) ^^ { case x ~ i => new XPathIdent(i) }

  // regular expression, JS style
  def exregex: Parser[Expr] = """/[^/]+/""".r ^^ { case x => new CExpr(x, WTypes.wt.REGEX) }


  //
  //==================================== ACCESSORS
  //

  // qualified identifier
  def aident: Parser[AExprIdent] = qlident ^^ { case i => new AExprIdent(i.head, i.tail.map(P("", _))) }

  // simple qident or complex one
  def aidentExpr: Parser[AExprIdent] = aidentaccess | aident

  // full accessor to value: a.b[4].c.r["field1"]["subfield2"][4].g
  // note this kicks in at the first use of [] and continues... so that aident above catches all other

  def aidentaccess: Parser[AExprIdent] = qlident ~ (sqbraccess | sqbraccessRange | accessorNum) ~ accessors ^^ {
    case i ~ sa ~ a => new AExprIdent(i.head, i.tail.map(P("", _)) ::: sa :: a)
  }

  def accessors: Parser[List[RDOM.P]] = rep(sqbraccess | sqbraccessRange | accessorIdent | accessorNum) ^^ {
    case p => p//.flatMap(prepAccessor)
  }

  /** if a single accessor is a path, then flatten it */
//  def prepAccessor(p: P): List[RDOM.P] = {
//    if (p.expr.exists(_.isInstanceOf[CExpr[String]])) {
//      p.expr.get.asInstanceOf[CExpr[String]].ee
//          .split("\\.")
//          .map(e => P("", "").copy(expr = Some(new CExpr(e))))
//          .toList
//    } else
//      List(p)
//  }

private def accessorIdent: Parser[RDOM.P] = "." ~> ident ^^ { case id => P("", id, WTypes.wt.STRING) }

  private def accessorNum: Parser[RDOM.P] = "." ~> "[0-9]+".r ^^ { case id => P("", id, WTypes.wt.NUMBER) }

  // need to check single quotes first to force them strings - otherwise they end up IDs
  private def sqbraccess: Parser[RDOM.P] = "\\[".r ~> ows ~> (strConstSingleQuote | expr) <~ ows <~ "]" ^^ {
    case e => P("", "").copy(expr = Some(e))
  }

  // for now the range is only numeric
  private def sqbraccessRange: Parser[RDOM.P] = "\\[".r ~> ows ~> (numConst) ~ ows ~ ".." ~ ows ~ opt(
    numConst | aidentExpr) <~ ows <~ "]" ^^ {
    case e1 ~ _ ~ _ ~ _ ~ e2 => P("", "", WTypes.wt.RANGE).copy(
      expr = Some(ExprRange(e1, e2))
    )
  }


  //
  //==================================== F U N C T I O N S
  //

  // calling a function, this is not defining it, so no type annotations etc
  // named parameters need to be mentioned, unless it's just one
  // a.b.c(
  def callFunc: Parser[Expr] = qident ~ pcallattrs ^^ { case i ~ a => AExprFunc(i, a) }

  // todo have a pcallattrsSimple which allows an expression and use that in callFunc

  /**
    * simple ident = expr assignemtn when calling
    */
  def pcallattrs: Parser[List[RDOM.P]] = " *\\(".r ~> ows ~> repsep(pcallattrIdent, ows ~ "," ~ ows) <~ opt(",") ~ ows <~ ")"

  def pcallattrIdent: Parser[P] = " *".r ~> qident ~ opt(" *= *".r ~> expr) ^^ {
    case ident ~ ex => {
      P(ident, "", ex.map(_.getType).getOrElse(WTypes.wt.EMPTY), ex)
    }
  }

  def pcallattrExpr: Parser[P] = " *".r ~> expr ^^ {
    case ex => {
      P("lambda", "", ex.getType, Some(ex))
    }
  }


  // param assignment (x = expr, ...)
  // allows comma after last
  def pasattrs: Parser[List[PAS]] = " *\\(".r ~> ows ~> repsep(pasattr, ows ~ "," ~ ows) <~ opt(",") ~ ows <~ ")" ~ " *".r

  /**
    * parm assignment, left side can be a[5].name, useful in a $val
    */
  def pasattr: Parser[PAS] = " *".r ~> (aidentaccess | aident) ~ opt(" *= *".r ~> expr) ^^ {
    case ident ~ e => {
      e match {
        case Some(ex) => PAS(ident, ex)
        case None => PAS(ident, ident) // compatible for a being a=a
      }
    }
  }

  /**
    * :<>type[kind]*
    * <> means it's a ref, not ownership
    * * means it's a list
    */
  def optType: Parser[WType] = opt(
    (" *: *<> *".r | " *: *".r) ~
      // todo make it work better with the opt below - it stopped working at some point
//      opt(" *<> *") ~
      qident ~
      optKinds ~
      opt(" *\\*".r)) ^^ {
    case Some(ref ~ tt ~ k ~ None) => {
      WType(tt, "", k).withRef(ref.contains("<>"))
    }
    case Some(ref ~ tt ~ _ ~ Some(_)) => {
      WType(WTypes.ARRAY, tt, None).withRef(ref.contains("<>"))
    }
    case None => WTypes.wt.EMPTY
  }

  // A [ KIND, KIND ]
  def optKinds: Parser[Option[String]] = opt(ows ~> "[" ~> ows ~> repsep(qident, ",") <~ "]") ^^ {
    case Some(tParm) => Some(tParm.mkString)
    case None => None
  }

  val msfDefOperators = "~=|\\?=|=".r

  def OPSM1: Parser[String] = msfDefOperators

  /**
    * parm definition / assignment
    *
    * @stereotypes name:<>type[kind]*~=default
    *
    * <> means it's a ref, not ownership
    * * means it's a list
    */
  def pattr: Parser[RDOM.P] = " *".r ~>
      opt(repsep("@" ~> qident, " *".r) <~ ws) ~  //optional stereotypes
      qident ~
      optType ~
      opt(" *\\?(?!=) *".r) ~  // negative lookahead to not match optional ? with default value ?=
      opt(ows ~> OPSM1 ~ ows ~ expr) <~
      optComment ^^ {
    case stereo ~ name ~ ttype ~ oper ~ e => {
      var optional = oper.mkString.trim

      val (dflt, ex) = e match {
        // we don't use dflt at all now, some parms are interpolated etc
        case Some(op ~ _ ~ expr) => {
          optional = if (op.contains("?=")) "?" else ""
          ("", Some(expr))
        }
        case None => ("", None)
      }

      val stereotypes = stereo.map(_.mkString(",")).mkString

      ttype match {
        // k - kind is [String] etc
        case WTypes.wt.EMPTY => // infer type from expr
          P(name, dflt, ex.map(_.getType).getOrElse(WTypes.wt.EMPTY), ex, optional, None, stereotypes)
        case tt => // ref or no archetype
          P(name, dflt, tt, ex, optional, None, stereotypes)
      }
    }
  }

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
  def attrs: Parser[List[RDOM.P]] =
    " *\\(".r ~> ows ~> repsep(pattr, "\\s*,\\s*".r ~ optComment) <~ opt(ows ~ ",") <~ ows <~ ")"


  //
  //=================== js and JSON
  //

  def jsexpr1: Parser[Expr] = "js:" ~> ".*(?=[,)])".r ^^ (li => JSSExpr(li))

  def jsexpr2: Parser[Expr] = "js:{" ~> ".*(?=})".r <~ "}" ^^ (li => JSSExpr(li))

  //  def jsexpr3: Parser[Expr] = "js:{{ " ~> ".*(?=})".r <~ "}}" ^^ { case li => JSSExpr(li) }
  def scalaexpr1: Parser[Expr] = "sc:" ~> ".*(?=[,)])".r ^^ (li => SCExpr(li))

  def scalaexpr2: Parser[Expr] = "sc:{" ~> ".*(?=})".r <~ "}" ^^ (li => SCExpr(li))

  def eblock: Parser[Expr] = "(" ~ ows ~> expr <~ ows ~ ")" ^^ (ex =>
    if (ex.isInstanceOf[BoolExpr]) BExprBlock(ex.asInstanceOf[BoolExpr]) else BlockExpr(ex)
      )

  // inline js expr: //1+2//
  def jsexpr4: Parser[Expr] = "//" ~> ".*(?=//)".r <~ "//" ^^ (li => JSSExpr(li))

  // remove single or double quotes if any, from ID matched with them
  def unquote(s: String) = {
    if (s.startsWith("'") && s.endsWith("\'") || s.startsWith("\"") && s
        .endsWith("\""))
      s.substring(1, s.length - 1)
    else
      s
  }

  def jnull: Parser[Expr] = "null" ^^ {
    _ => new CExprNull
  }

  // json object - sequence of nvp assignemnts separated with commas
  def jobj: Parser[Expr] = opt("new" ~ whiteSpace ~ qident) ~ ows ~
      "{" ~ ows ~ repsep(ojnvp <~ ows, ",\\s*(//.*)?".r) <~ ows ~ "}" ^^ {
    case None ~ _ ~ _ ~ _ ~ li => JBlockExpr(li.flatten)
    case Some(a ~ _ ~ b) ~ _ ~ _ ~ _ ~ li => JBlockExpr(li.flatten, Option(b))
  }

  // comment line or jnvp
  def ojnvp: Parser[List[(String, Expr)]] = ows ~> ( "//.*".r | jnvp) ^^ {
    case _ : String => Nil
    case jn: (String, Expr) => List(unquote(jn._1) -> jn._2)
  }

  // one json block nvp pair
  def jnvp: Parser[(String, Expr)] = ows ~> jsonIdent ~ " *[:=] *".r ~ jexpr ^^ {
    case name ~ _ ~ ex => (unquote(name.toString) -> ex)
  }

  // array generator with numbers (like a materialized range) [ 1 .. 2 ]
  def jarray1: Parser[Expr] = "[" ~ ows ~> jexpr ~ " *\\.\\. *".r ~ jexpr <~ ows ~ "]" ^^ {
    case start ~ _ ~ end => JArrExprGen(start,end) //CExpr("[ " + li.mkString(",") + " ]")
  }

  // array [a,b,c] - elements are expressions
  def jarray2: Parser[Expr] = "[" ~ ows ~> repsep(ows ~> jexpr <~ ows, ",") <~ ows ~ "]" ^^ {
    li =>
      if(li.exists(x => ! x.isInstanceOf[CExpr[_]])) JArrExpr(li)
      else JArrCExpr(li)
  }

  def jexpr: Parser[Expr] = jobj | jarray1 | jarray2 | boolConst | jother ^^ (ex => ex) //ex.toString }

  //  def jother: Parser[String] = "[^{}\\[\\],]+".r ^^ { case ex => ex }
  def jother: Parser[Expr] = expr ^^ (ex => ex)


  //
  //==================================== C O N D I T I O N S
  //

  // todo why need space after not? this no parse: => $if(not(false))

  private def opsBool: Parser[String] = "==" | "xNot" | "is" | "in" | "not in" | "notIn" |
      "!=" | "not" <~ ws | "~=" | "matches" <~ ws | "<=" | ">=" | "<" | ">" |
      "containsNot" <~ ws | "contains" <~ ws | dolcmp

  def cond: Parser[BoolExpr] = orexpr

  def dolcmp: Parser[String] = """${""" ~ """[^}]+""".r ~ "}" ^^ {
    case a ~ b ~ c => s"$a$b$c"
  }

  def orexpr: Parser[BoolExpr] = bterm1 ~ rep(ows ~> ("or" <~ ws) ~ ows ~ bterm1) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, bcmp)
  }

  def bterm1: Parser[BoolExpr] = bfactor1 ~ rep(ows ~> ("and" <~ ws) ~ ows ~ bfactor1) ^^ {
    case a ~ l => foldAssocAexpr2(a, l, bcmp)
  }

  def bfactor1: Parser[BoolExpr] = notbfactor2 | bfactor2

  def notbfactor2: Parser[BoolExpr] = ows ~> (("not" | "NOT") <~ ws) ~> ows ~> bfactor2 ^^ {BCMPNot}

  def bfactor2: Parser[BoolExpr] = boolConst | ibex(opsBool) | bvalue | condBlock

  private def condBlock: Parser[BoolExpr] = ows ~> "(" ~> ows ~> cond <~ ows <~ ")" ^^ {BExprBlock}

  private def ibex(op: => Parser[String]): Parser[BoolExpr] = expr ~ (ows ~> op <~ ows) ~ expr ^^ {
    case a ~ s ~ b => BCMP2(a, s.trim, b)
  }

  /** true or false constants */
  def boolConst: Parser[BoolExpr] = ("true" | "false") ^^ {BCMPConst}


  /** single value expressions, where != 0 is true and != null is true */
  def bvalue: Parser[BoolExpr] = expr ^^ {
    a => BCMPSingle(a)
  }

  private def bcmp(a: BoolExpr, s: String, b: BoolExpr) = BCMPAndOr(a, s, b)

  /** we can only combine: 2 bools with and/or ||| 2 non-bool exprs with non-bool operator ||| blow up */
  private def bMkAndOr(a: Expr, s: String, b: Expr): Expr = (a, b) match {
    case (a: BoolExpr, b: BoolExpr) => BCMPAndOr(a, s, b)

    // todo build this into the parser - see exprMAP comments
    case (a: Expr, b: Expr) if !a.isInstanceOf[BoolExpr] && !b.isInstanceOf[BoolExpr]
    => AExpr2(a, s, b)

    // need to allow simple idents - they mean "exists"
    case (a: Expr, b: Expr) if a.isInstanceOf[AExprIdent] && b.isInstanceOf[BoolExpr]
    => BCMPAndOr(BCMPSingle(a), s, b.asInstanceOf[BoolExpr])
    case (a: Expr, b: Expr) if a.isInstanceOf[BoolExpr] || b.isInstanceOf[AExprIdent]
    => BCMPAndOr(a.asInstanceOf[BoolExpr], s, BCMPSingle(b))

    case (a, b) => throw new DieselExprException(
      "bMkAndOr - can't combine non-logical expressions with or/and: " + a + " WITH " + b)
  }

}
