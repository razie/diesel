/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
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
  def pclass: PS = """[.$]class""".r ~> ws ~> ident ~ opt(ows ~> "[" ~> ows ~> repsep(ident, ",") <~ "]") ~ optAttrs ~ opt(ws ~> "extends" ~> ws ~> repsep(ident, ",")) ~
    opt(ws ~> "<" ~> ows ~> repsep(ident, ",") <~ ">") ~ " *".r ~ optClassBody ^^ {
    case name ~ tParm ~ attrs ~ ext ~ stereo ~ _ ~ funcs => {
      val c = C(name, "", stereo.map(_.mkString).mkString,
        ext.toList.flatMap(identity),
        tParm.map(_.mkString).mkString,
        attrs,
        funcs )
      LazyState {(current, ctx)=>
        collectDom(c, ctx.we)

        def mkList = s"""<a href="/diesel/list2/${c.name}">list</a>"""
        def mkNew  = if(ctx.we.exists(w=> WikiDomain.canCreateNew(w.realm, name))) s""" | <a href="/doe/diesel/create/${c.name}">new</a>""" else ""

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
  def assRole: Parser[(String,String)] = ident ~ " *: *".r ~ ident ^^ {
    case cls ~ _ ~ role => (cls, role)
  }

  /** assoc : role */
  def clsMet: Parser[(String,String, List[RDOM.P])] = ident ~ " *. *".r ~ ident ~ optAttrs ^^ {
    case cls ~ _ ~ role ~ a => (cls, role, a)
    }

  /**
   * add a domain element to the topic
   */
  def addToDom (c:Any) = {
      LazyState { (current, ctx) =>
        collectDom(c, ctx.we)
        SState(
          c match {
            case x:CanHtml => x.toHtml
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
  def pwhen: PS = """[.$]when""".r ~> ws ~> clsMet ~ " *=> *".r ~ clsMet ^^ {
    case Tuple3(ac, am, aa) ~ _ ~ Tuple3(zc, zm, za) => {
      val x = RDExt.EMatch(ac, am, aa)
      val y = RDExt.EMap(zc, zm, za)
      val rule = RDExt.ERule(x, y)
      addToDom(rule)
    }
  }

  /**
   * .mock a.role (attrs) => z.role (attrs)
   */
  def pmock: PS = """[.$]mock""".r ~> ws ~> clsMet ~ " *=> *".r ~ optAttrs ^^ {
    case Tuple3(ac, am, aa) ~ _ ~ za => {
      val x = RDExt.EMatch(ac, am, aa)
      val y = RDExt.EMap("", "", za)
      val rule = EMock(ERule(x, y))
      addToDom(rule)
    }
  }

  /**
   * .assoc name a:role -> z:role
   */
  def passoc: PS = """[.$]assoc""".r ~> ws ~> opt(ident <~ ws) ~ assRole ~ " *-> *".r ~ assRole ~ optAttrs ^^ {
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

  def pobject: PS = """[.$]object """.r ~> ident ~ " *".r ~ ident ~ opt(CRLF2 ~> rep1sep(vattrline, CRLF2)) ^^ {
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
    case Some(a) =>  a
    case None =>  List.empty
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
      case Some(Some(ref) ~ tt ~ k) => P(name, tt+k.s, ref, multi.mkString, e.mkString)
      case Some(None ~ tt ~ k) => P(name, tt+k.s, "", multi.mkString, e.mkString)
      case None => P(name, "", "", multi.mkString, e.mkString)
    }
  }


  // value assignment
  def vattrline: Parser[V] = " *".r ~> ident ~ " *= *".r ~ any ^^ {
    case name ~ _ ~ v => V(name, v)
  }

  def optClassBody: Parser[List[RDOM.F]] = opt(" *\\{".r ~> CRLF2 ~> rep1sep(fattrline, CRLF2) <~ CRLF2 <~ " *\\} *".r ) ^^ {
    case Some(a) =>  a
    case None =>  List.empty
  }

  /**
   * .option name:type=value
   *
   * use them to set options
   */
  def pprop: PS = "[.$]attr *".r ~> pattr ^^ {
    case a => {
      LazyState {(current, ctx)=>
        collectDom(EAttr(a), ctx.we)
        SState("Option: "+a.toString)
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
      LazyState {(current, ctx)=>
        val f = RDExt.EMsg(ent, ac, attrs, ret.toList.flatten(identity), stype.mkString.trim)
        collectDom(f, ctx.we)
        SState(f.toString)
      }
    }
  }

  /**
   * .expect object.func (a,b)
   */
  def pexpectm: PS = "[.$]expect * [$]msg *".r ~> ident ~ " *\\. *".r ~ ident ~ optAttrs ^^ {
    case ent ~ _ ~ ac ~ attrs => {
      LazyState {(current, ctx)=>
        val f = RDExt.ExpectM(RDExt.EMatch(ent, ac, attrs))
        collectDom(f, ctx.we)
        SState("expect::" + f.toString)
      }
    }
  }

  /**
   * .expect object.func (a,b)
   */
  def pexpectv: PS = "[.$]expect * [$]val *".r ~> pattr ^^ {
    case a => {
      LazyState {(current, ctx)=>
        val f = RDExt.ExpectV(a)
        collectDom(f, ctx.we)
        SState("expect::" + f.toString)
      }
    }
  }

  private def collectDom(x:Any, we:Option[WikiEntry]) = {
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
      LazyState {(current, ctx)=>
        val f = F(name, a, t.mkString, s.fold(ctx).s, b)
        collectDom(f, ctx.we)

        def mkParms = f.parms.map{p=>p.name+"="+Enc.toUrl(p.dflt)}.mkString("&")
        def mksPlay = if(f.script.length > 0) s""" | <a href="/diesel/splay/${f.name}/${ctx.we.map(_.wid.wpath).mkString}?$mkParms">splay</a>""" else ""
        def mkjPlay = if(f.script.length > 0) s""" | <a href="/diesel/jplay/${f.name}/${ctx.we.map(_.wid.wpath).mkString}?$mkParms">jplay</a>""" else ""
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
  def optScript: PS = opt(" *\\{\\{ *".r ~> lines <~ "}}" ) ^^ {
    case Some(lines) => lines
    case None => ""
  }

  /**
   * def name (a,b) : String
   */
  def fattrline: Parser[RDOM.F] = " *def *".r ~> ident ~ optAttrs ~ optType ~ " *".r ~ optBlock ^^ {
    case name ~ a ~ t ~ _ ~ b => F(name, a, t.mkString, "", b)
  }

  def optBlock: Parser[List[EXEC]] = opt(" *\\{".r ~> CRLF2 ~> rep1sep(statement, CRLF2) <~ CRLF2 <~ " *\\} *".r ) ^^ {
    case Some(a) =>  a
    case None =>  List.empty
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
      new ExecCall (cls, func, attres)
  }

}

class ExecValue (p:RDOM.P) extends EXEC {
  def sForm = "val " + p.toString
  def exec(ctx:Any, parms:Any*):Any = ""
}

class ExecCall (cls:String, func:String, args:List[P]) extends EXEC {
  def sForm = s"call $cls.$func (${args.mkString})"
  def exec(ctx:Any, parms:Any*):Any = ""
}

/** RDOM extensions */
object RDExt {

  // just a wrapper for type
  case class EVal(p:RDOM.P) extends CanHtml {
    override def toHtml = span("val::")+p.toString
    override def toString = "val: "+p.toString
  }

  // just a wrapper for type
  case class EAttr(p:RDOM.P) {
    override def toString = p.toString
  }

  def span(s:String, k:String="default") = s"""<span class="label label-$k">$s</span>"""

  // wrapper
  case class ExpectM(m:EMatch) extends CanHtml {
    override def toHtml = span("expect::")+m.toString
    override def toString = "expect::"+m.toString
  }

  // wrapper
  case class ExpectV(p:P) extends CanHtml {
    override def toHtml = span("expect::")+p.toString
    override def toString = "expect::"+p.toString

    /** check to match the arguments */
    def test (a:P) = {
        var res = false
        if(p.name.size > 0) {
          res = a.name == p.name
        }
        if(p.dflt.size > 0) {
          if(p.name.size > 0) {
            res = a.name == p.name && a.dflt == p.dflt
          }
        }
        res
    }

  }

  // a nvp - can be a spec or an event, message, function etc
  case class EMsg(entity:String, met:String, attrs:List[RDOM.P], ret:List[RDOM.P]=Nil, stype:String="") extends CanHtml {
    var spec:Option[EMsg] = None // if this was an instance and you know of a spec
    private def first:String = spec.map(_.first).getOrElse(
        span("msg:",resolved)+span(stype, "info")
      )
    private def resolved:String = spec.map(_.resolved).getOrElse(
      if(stype=="GET" || stype=="POST") "default" else "warning"
    )
    override def toHtml = first+
      s""" $entity.<b>$met</b> (${attrs.mkString(", ")})"""
    override def toString = toHtml
  }

// an instance at runtime
case class EInstance(cls:String, attrs:List[RDOM.P]) {
}

// an instance at runtime
case class EEvent(e:EInstance, met:String, attrs:List[RDOM.P]) {
}

type Attrs = List[RDOM.P]

// a match case
case class EMatch(cls:String, met:String, attrs:Attrs) {
  // todo match also the object parms if any and method parms if any
  def test (e:EEvent) =
    ("*" == cls || e.e.cls == cls) && ("*" == met || e.met == met) && testA(e.attrs, attrs)
  def test (e:EMsg) =
    ("*" == cls || e.entity == cls) && ("*" == met || e.met == met) && testA(e.attrs, attrs)

  /**
   * matching attrs
   *
   * (a,b,c) they occur in whatever sequence
   *
   * (1,b,c) it occurs in position with value
   *
   * (a=1) it occurs with value
   */

  /** check to match the arguments */
  private def testA (in:Attrs, cond:Attrs) = {
    cond.zipWithIndex.foldLeft(true)((a,b) => a && {
      var res = false
      if(b._1.name.size > 0) {
        res = in.exists(_.name == b._1.name)
      }
      if(b._1.dflt.size > 0) {
        res = if(b._1.name.size > 0) {
          in.exists(x=> x.name == b._1.name && x.dflt == b._1.dflt)
        } else {
          in(b._2).dflt == b._1.dflt
        }
      }
      res
    })
  }

  override def toString = cls+"."+met+" " +attrs.mkString
}

// a match case
case class EMap(cls:String, met:String, attrs:Attrs) {
  // todo match also the object parms if any and method parms if any
  def apply (e:EEvent) =
    "?event"
  def apply (in:EMsg, destSpec:Option[EMsg])(implicit ctx: ECtx) : List[Any] = {
    var e = EMsg(cls, met, sourceAttrs(in,attrs, destSpec.map(_.attrs)))
    e.spec = destSpec
    List(e)
  }

  def sourceAttrs (in:EMsg, spec: Attrs, destSpec:Option[Attrs])(implicit ctx: ECtx) = {
    def expr(s:String) = {
      if(s.startsWith("\"")) s
      else if(s.matches("[0-9]+")) s
      else in.attrs.find(_.name == s).map(_.dflt).getOrElse("")
    }

    if(spec.nonEmpty) spec.map{p=>
      //todo use expr
      val v = in.attrs.find(_.name == p.name).map(_.dflt).orElse(
        ctx.attrs.find(_.name == p.name).map(_.dflt)
      ).getOrElse(
        expr(p.dflt)
      )
      p.copy(dflt = v)
    } else if(destSpec.exists(_.nonEmpty)) destSpec.get.map{p=>
      //todo use expr
      val v = in.attrs.find(_.name == p.name).map(_.dflt).orElse(
        ctx.attrs.find(_.name == p.name).map(_.dflt)
      ).getOrElse(
        expr(p.dflt)
      )
      p.copy(dflt = v)
    } else Nil
  }

  override def toString = cls+"."+met+" " +attrs.mkString
}

  // a wrapper
  case class EMock(rule: ERule) {
  }

// a context
case class ECtx(attrs:List[RDOM.P]=Nil) {
}

// a context
case class ERule(e:EMatch, i:EMap) extends CanHtml {
  override def toHtml = span("$when::")+" "+e + " => " + i
  override def toString = "$when:: "+e + " => " + i
}

  /** simple json for content assist */
  def toCAjmap (d:RDomain) = {
    val visited = new ListBuffer[(String,String)]()

    // todo collapse defs rather than select
    def collect (e:String, m:String) = {
      if (!visited.exists(_ == (e,m))) {
        visited.append((e,m))
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
        visited.toList.map(t=> t._1+"."+t._2)
      }
    )
  }

}

trait CanHtml {
  def toHtml:String
}

