/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model.dom

import scala.Option.option2Iterable
import model._
import admin.Config
import org.bson.types.ObjectId
import play.api.mvc.Action
import controllers.WG
import razie.wiki.parser.WAST
import razie.wiki.model.WikiEntry
import razie.wiki.parser.WikiParserBase

/** domain parser - for domain sections in a wiki */
trait WikiDomainParser extends WikiParserBase {
  import WAST._
  import DOM._

  def ident: P = """\w+""".r
  def any: P = """.*""".r

  def domainBlocks = pobject | pclass

  /**
   * .class X [extends A,B]
   * @return
   */
  def pclass: PS = """.class """ ~> ident ~ opt(" extends " ~> repsep(ident, ",")) ~
    opt(" " ~> ident) ~ opt(CRLF2 ~> rep1sep(attrline, CRLF2)) ^^ {
    case name ~ e ~ a ~ l => {
      val c = C(name, a.mkString, e.toList.flatMap(identity),
        l.toList.flatMap(identity).collect{case x:DOM.P =>x},
        l.toList.flatMap(identity).collect{case x:DOM.F =>x} )
      SState(
        """<div class="well">""" +
          s"class $name  (" + l.mkString(", ") +
          ")" + e.mkString + a.map(" <" + _ + ">").mkString +
          """</div>""",
        Map(), List(), List({ w =>
          w.cache.put(DOM_LIST, c :: w.cache.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]])
          w
        }))
    }
  }

  def pobject: PS = """.object """ ~> ident ~ " *".r ~ ident ~ opt(CRLF2 ~> rep1sep(vattrline, CRLF2)) ^^ {
    case name ~ _ ~ c ~ l => {
      val o = O(name, c, l.toList.flatMap(identity))
      SState(
        """<div class="well">""" +
          s"object $name (" + l.mkString(", ") +
          ")" +
        """</div>""",
        Map(), List(), List({ w =>
          w.cache.put(DOM_LIST, o :: w.cache.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]])
          w
        }))
    }
  }

  /**
  //todo not used yet
    // simple x=y
  private def varg = "[^:=,}]*".r ~ "=" ~ "[^},]*".r ^^ { case n ~ _ ~ v => (n, v) }
  // if contains comma, use ""
  private def varg2 = "[^:=,}]*".r ~ "=\"" ~ "[^\"]*".r <~ "\"" ^^ { case n ~ _ ~ v => (n, v) }

  private def voptargs : Parser[List[(String,String)]] = opt("[: ]".r ~ rep((varg2 | varg) <~ opt(","))) ^^ {
    case Some(_ ~ l) => l
    case None => List()
  }
**/

//  private def identif:Parser[String] = "[^:=,}]*".r ^^ { case s => s }

  // simple x=y
  private def pspec: Parser[DOM.P] = ident ~ opt(" *: *".r ~ ident) ~ opt("*") ~ opt(" *= *".r ~> any) ^^ {
    case name ~ t ~ multi ~ e => P(name, t.map(_._2).mkString, multi.mkString, "", e.mkString)
  }

  private def optpspecs : Parser[List[DOM.P]] = opt("[: ]".r ~ rep((pspec) <~ opt(","))) ^^ {
    case Some(_ ~ l) => l
    case None => List()
  }

  def attrline: Parser[_ >: DOM.CM] = /*fattrline |*/ pattrline

//  def fattrline: Parser[DOM.F] = " +def +".r ~> ident ~ opt(" *: *".r ~ ident) ~ opt("*") ~ opt(" *= *".r ~> any) ^^ {
//    case name ~ parms ~ multi ~ e => F(name, t.map(_._2).mkString)
//  }

  def pattrline: Parser[DOM.P] = " +".r ~> ident ~ opt(" *: *".r ~ ident) ~ opt("*") ~ opt(" *= *".r ~> any) ^^ {
    case name ~ t ~ multi ~ e => P(name, t.map(_._2).mkString, multi.mkString, "", e.mkString)
  }

  def vattrline: Parser[DOM.V] = " +".r ~> ident ~ " *: *".r ~ any ^^ {
    case name ~ _ ~ v => V(name, v)
  }

}

object DOM {
  class CM // class member
  case class P (name:String, t:String, multi:String, dflt:String, expr:String)
  case class F (name:String, parms:List[P])
  case class V (name:String, value:String)
  case class C (name:String, archetype:String, base:List[String], parms:List[P], methods:List[F])
  case class O (name:String, base:String, parms:List[V]) {
    def toJson = parms.map{p=> p.name -> p.value}.toMap
  }

  case class D (name:String, classes:Map[String,C], objects:Map[String,O]) {
    def tojmap = {
      Map("name"->name,
        "classes" -> classes.values.toList.map{c=>
          Map(
            "name"->c.name,
            "parms" -> c.parms.map{p=>
              Map(
                "name"->p.name,
                "t" -> p.t
              )
            }
          )},
        "objects" -> objects.values.toList.map{c=>
          Map(
            "name"->c.name,
            "parms" -> c.parms.toList.map{p=>
              Map(
                "name"->p.name,
                "value" -> p.value
              )
            }
          )}
      )
    }
  }

  final val DOM_LIST = "dom.list"
  final val RDOM="r.domain"

  def apply (we:WikiEntry) : Option[D] = {
    we.preprocessed
    if(we.tags.contains(RDOM))
      Some(
        we.cache.getOrElseUpdate("dom",
        D("?",
          we.cache.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].collect {
            case c:C => (c.name, c)
          }.toMap,
          we.cache.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].collect {
            case o:O => (o.name, o)
          }.toMap)
      )) collect {case d:D => d}
    else None
  }
}


