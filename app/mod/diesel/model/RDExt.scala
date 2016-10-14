/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import mod.diesel.controllers.{SFiddles, DieselControl}
import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.novus.salat._
import org.joda.time.DateTime

import razie.diesel.dom._
import razie.diesel.dom.RDOM._
import razie.diesel.ext._
import razie.js

import scala.Option.option2Iterable
import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}

/** RDOM extensions */
object RDExt {

  EECtx :: EESnakk :: EEFunc :: EETest :: Nil map Executors.add

  /** parse from/to json utils */
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

  // a context - MAP, use this to test the speed of MAP
  class ECtxM() {
    val attrs = new mutable.HashMap[String, P]()

    def apply(name: String): String = get(name).getOrElse("")

    def get(name: String): Option[String] = attrs.get(name).map(_.dflt)

    def put(p: P) = attrs.put(p.name, p)

    def putAll(p: List[P]) = p.foreach(x => attrs.put(x.name, x))
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

    override def test(in: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      ctx.domain.exists(_.funcs.contains(in.entity + "." + in.met))
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      val res = ctx.domain.flatMap(_.funcs.get(in.entity + "." + in.met)).map { f =>
        val res = try {
          if (f.script != "") {
            val c = ctx.domain.get.mkCompiler("js")
            val x = c.compileAll ( c.not {case fx:RDOM.F if fx.name == f.name => true})
            val s = x + "\n" + f.script

            val q = in.attrs.map(t=>(t.name, t.dflt)).toMap

            SFiddles.isfiddleMap(s, "js", None, None, q, Some(qTyped(q,f)))._2
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

  /** snakk REST APIs */
  object EESnakk extends EExecutor("snakk") {
    /** can I execute this task? */
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.stype == "GET" || m.stype == "POST" ||
        spec(m).exists(m=> m.stype == "GET" || m.stype == "POST" ||
        ctx.findTemplate(m.entity+"."+m.met).isDefined)
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
      val template = ctx.findTemplate(in.entity+"."+in.met)
      val tcontent = template.map(_.content).map{content=>
        // todo either this or prepUrl not both
        val PAT = """\$\{([^\}]*)\}""".r
        val s1 = PAT.replaceAllIn(content, {m =>
          ctx(m.group(1))
        })
        s1
      }

      import razie.Snakk._

      tcontent.map(parseTemplate).map {sc=>
        val newurl = if(sc.url startsWith "/") "http://" + ctx.hostname+sc.url else sc.url
        // with templates
        val x = url(
          prepUrl(stripQuotes(newurl),
            P("subject", "", "", "", in.entity) ::
            P("verb", "", "", "", in.met) :: in.attrs),
          //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
        Map.empty[String,String],
        sc.method
        )
        try {
          val res = jsonParsed(body(x))
            // message specified return mappings
          val ret = if(in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)

          val strs = if(template.get.parms.length > 0) {
            val mapping = template.get.parms.split(",").map(s=>s.split("=")).map(a=> (a(0), a(1)))
            val strs = mapping.map { t =>
              (t._1, json(res) \@@ t._2)
            }
            strs.toList
          } else {
//          (
//            if(ret.nonEmpty) ret else List(new P("result", ""))
//            ).map(p => p.copy(dflt = res \ "values" \@@ p.name)).map(x => EVal(x))
            val a = res.getJSONObject("values")
            val strs = (
                for (k <- 0 until a.names.length())
                  yield (a.names.get(k).toString, a.getString(a.names.get(k).toString))
              )
            strs.toList
          }

          strs.toList.map(t=> new P(t._1, t._2)).map{x =>
            ctx.put(x)
            EVal(x).withPos(Some(template.get.pos))
          }
        } catch {
          case t: Throwable => {
            razie.Log.log("error snakking", t)
            EError("Error snakking: " + x + " :: " + t.toString) :: Nil
          }
        }
      } getOrElse {
        // old way without templates
        in.attrs.find(_.name == "url").orElse(
          spec(in).flatMap(_.attrs.find(_.name == "url"))
        ).map { u =>
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
            ).map(_.copy(dflt = res)).map(x => EVal(x)).toList
          } catch {
            case t: Throwable => {
              razie.Log.log("error snakking", t)
              EError("Error snakking: " + u + " :: " + t.toString) :: Nil
            }
          }
        } getOrElse EError("no url attribute for RESTification ") :: Nil
      }
    }

    case class SnakCall (method:String, url:String, headers:String, content:String)

    def parseTemplate (is:String) = {
//      val regex = """(?s)(GET|POST) ([^\n]+)\n(([^\n]+\n)*)?(.+)?""".r
//      val regex (method, url, headers, content) = s

      // templates start with a \n
      val s = if(is startsWith "\n") is.substring(1) else is
      val verb = s.split(" ").head
      val url = s.split(" ").tail.head.replaceFirst("\n.*", "")
      val headers = if(s contains "\n\n") s.replaceFirst("(?s)\n\n.*", "").lines.drop(1).mkString("\n") else ""
      val content = if(s contains "\n\n") s.replaceFirst("(?s).*\n\n", "") else ""
      new SnakCall (verb, url, "", content.replaceFirst("(?s)\n$", ""))
    }

    override def toString = "$executor::snakk "
  }


  // to collect msg def
  case class PCol(p:P, pos:Option[EPos])
  case class PMCol(pm:PM, pos:Option[EPos])
  case class MsgCol(e:String,
               a:String,
               pos:Option[EPos],
               in  : ListBuffer[PCol] = new ListBuffer[PCol],
               out : ListBuffer[PCol] = new ListBuffer[PCol],
               cons: ListBuffer[PMCol] = new ListBuffer[PMCol]
               ) {
    def toHtml = EMsg("", e, a ,Nil).withPos(pos).toHtmlInPage
  }

  /** simple json for content assist */
  def summarize(d: RDomain) = {
    val msgs  = new ListBuffer[MsgCol]
    val attrs = new ListBuffer[PCol]

    // todo collapse defs rather than select
    def collectMsg(n:EMsg, out:List[P] = Nil) = {
      if (!msgs.exists(x=> x.e == n.entity && x.a == n.met)) {
        msgs.append(new MsgCol(
          n.entity,
          n.met,
          n.pos,
          new ListBuffer[PCol]() ++= n.attrs.map(p=>PCol(p, n.pos)),
          new ListBuffer[PCol]() ++= (n.ret ::: out).map(p=>PCol(p, n.pos))
        ))
      } else msgs.find(x=> x.e == n.entity && x.a == n.met).foreach {m=>
        n.attrs.foreach {p=>
          if(m.in.exists(_.p.name == p.name)) {
            // todo collect types etc
          } else {
            m.in append PCol(p, n.pos)
          }
        }
      }
    }

//    def collectP(l:List[P]) = {
//      l.map(p=>collect(p.name, "", "attr"))
//    }
//    def collectPM(l:List[PM]) = {
//      l.map(p=>collect(p.name, "", "attr"))
//    }

        d.moreElements.collect({
          case n: EMsg => {
            if (!msgs.exists(x=> x.e == n.entity && x.a == n.met))
              collectMsg(n)
          }
          case n: ERule => {
            collectMsg(n.e.asMsg.withPos(n.pos))
            collectMsg(n.i.asMsg.withPos(n.pos))
          }
          case n: EMock => {
            collectMsg(n.rule.e.asMsg.withPos(n.pos), n.rule.i.attrs)
          }
          case n: ExpectM => collectMsg(n.m.asMsg.withPos(n.pos))
        })

//        d.moreElements.collect({
//          case n: EMsg => collectP(n.attrs)
//          case n: ERule => {
//            collectPM(n.e.attrs)
//            collectP(n.i.attrs)
//          }
//          case n: EMock => collectPM(n.rule.e.attrs)
//          case n: ExpectM => collectPM(n.m.attrs)
//        })
  msgs
  }
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


