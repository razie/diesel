/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import razie.diesel.RDOM._
import razie.diesel.{RDomain, RDOM}
import razie.wiki.dom.WikiDomain

import scala.Option.option2Iterable
import model._
import admin.Config
import org.bson.types.ObjectId
import play.api.mvc.Action
import razie.wiki.parser.WAST
import razie.wiki.model.WikiEntry
import razie.wiki.parser.WikiParserBase

/** domain parser - for domain sections in a wiki */
trait WikiDomainParser extends WikiParserBase {
  import WAST._
  import RDOM._

  def ident: P = """\w+""".r
  def any: P = """.*""".r
  def value: P = ident | number | str
  def number: P = """\d+""".r
  def str: P = """"\w*"""".r
  def ws = whiteSpace
  def ows = opt(whiteSpace)

  def domainBlocks = pobject | pclass | passoc

  /**
   * .class X [extends A,B]
   * @return
   */
  def pclass: PS = """$class""" ~> ws ~> ident ~ opt(ws ~> "[" ~> ows ~> repsep(ident, ",") <~ "]") ~ opt(ws ~> "extends" ~> ws ~> repsep(ident, ",")) ~
    opt(ws ~> "<" ~> ows ~> repsep(ident, ",") <~ ">") ~ optAttrs ~ optClassBody ^^ {
    case name ~ tParm ~ ext ~ stereo ~ attrs ~ funcs => {
      val c = C(name, "", stereo.map(_.mkString).mkString,
        ext.toList.flatMap(identity),
        tParm.map(_.mkString).mkString,
        attrs,
        funcs )
      LazyState {(current, ctx)=>
        ctx.we.foreach {w=>
         val rest = w.cache.getOrElse(WikiDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
         w.cache.put(WikiDomain.DOM_LIST, c :: rest)
         w
        }
        SState(
          s"""
            |<div align="right"><small><a href="/diesel/list2/${c.name}">list</a> | <a href="/doe/diesel/create/${c.name}">new</a>
            |</small></div><div class="well">
            |$c
            |</div>""".stripMargin)
      }
    }
  }

  /**
   * .class X [extends A,B]
   * @return
   */
  def assRole: Parser[(String,String)] = ident ~ " *: *".r ~ ident ^^ {
    case cls ~ _ ~ role => (cls, role)
    }

//  def passoc: PS = """$assoc""" ~> ws ~> opt("(" ~> ows ~> ident <~ ows <~")" <~ ws) ~ assRole ~ " *-> *".r ~ assRole ~ optAttrs ^^ {
  def passoc: PS = """$assoc""" ~> ws ~> opt(ident <~ ws) ~ assRole ~ " *-> *".r ~ assRole ~ optAttrs ^^ {
    case n ~ Tuple2(a, arole) ~ _ ~ Tuple2(z, zrole) ~ p => {
      val c = A(n.mkString, a, z, arole, zrole, p)
      LazyState { (current, ctx) =>
        ctx.we.foreach { w =>
          w.cache.put(WikiDomain.DOM_LIST, c :: w.cache.getOrElse(WikiDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]])
          w
        }
            SState(
              """<div class="well">""" +
                c.toString +
                """</div>""")
      }
    }
  }

  def pobject: PS = """$object """ ~> ident ~ " *".r ~ ident ~ opt(CRLF2 ~> rep1sep(vattrline, CRLF2)) ^^ {
    case name ~ _ ~ c ~ l => {
      val o = O(name, c, l.toList.flatMap(identity))
      LazyState { (current, ctx) =>
        ctx.we.foreach { w =>
          w.cache.put(WikiDomain.DOM_LIST, o :: w.cache.getOrElse(WikiDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]])
          w
        }
      SState(
        """<div class="well">""" +
          s"object $name (" + l.mkString(", ") +
          ")" +
        """</div>""")
        }
    }
  }

  // attrs
  def optAttrs: Parser[List[RDOM.P]] = opt(" *\\(".r ~> rep1sep(pattrline, ",") <~ ")") ^^ {
    case Some(a) =>  a
    case None =>  List.empty
  }

  def attrline: Parser[_ >: CM] = pattrline

  def pattrline: Parser[RDOM.P] = " *".r ~> ident ~ opt(" *: *".r ~> opt("<>") ~ ident) ~ opt(" *\\* *".r) ~ opt(" *= *".r ~> value) ^^ {
    case name ~ t ~ multi ~ e => t match {
      case Some(Some(ref) ~ tt) => P(name, tt, ref, multi.mkString, e.mkString)
      case Some(None ~ tt) => P(name, tt.mkString, "", multi.mkString, e.mkString)
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

  def fattrline: Parser[RDOM.F] = " *def *".r ~> ident ~ optAttrs ~ opt(" *: *".r ~> ident) ~ optBlock ^^ {
    case name ~ a ~ t ~ b => F(name, a, t.mkString, b)
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

