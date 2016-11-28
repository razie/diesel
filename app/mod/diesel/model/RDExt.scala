/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import java.net.URI
import java.util.regex.Pattern

import com.razie.pub.comms.Comms
import controllers.Wikil
import mod.diesel.controllers.{DieselMsgString, SFiddles, DieselControl}
import model.Users
import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.novus.salat._
import org.joda.time.DateTime

import razie.diesel.dom._
import razie.diesel.dom.RDOM._
import razie.diesel.ext._
import razie.wiki.{Enc, Services}
import razie.wiki.model.WID
import razie.{clog, js}

import scala.Option.option2Iterable
import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}

import razie.Snakk
import razie.SnakkUrl

import scala.util.Try

case class SnakCall (method:String, url:String, headers:Map[String,String], content:String) {
  def toJson = grater[SnakCall].toPrettyJSON(this)
  var isurl : SnakkUrl = null
  def setUrl(u:SnakkUrl) = {isurl = u}

  var ibody : Option[String] = None

  def body = ibody.getOrElse{
    val x = Snakk.body(isurl)
    ibody = Some(x)
    x
  }

  def root = {
    val conn = Snakk.conn(isurl)
    val is = conn.getInputStream()
    ibody = Option(Comms.readStream(is))

    val x = conn.getHeaderField("Content-Type")
    conn.getHeaderField("Content-Type") match {
      case "application/xml" => Snakk.xml(ibody.getOrElse(""))
      case "application/json" => Snakk.json(ibody.getOrElse(""))
      case x@_ => throw new IllegalStateException ("unknown content-type: "+x)
    }
  }
}

/** RDOM extensions */
object RDExt {

  def init = {
    EECtx :: EEWiki :: EESnakk :: EEFunc :: EETest :: Nil map Executors.add
  }

  /** parse from/to json utils */
  object DieselJsonFactory {
    private def sm(x:String, m:Map[String, Any]) : String = if(m.contains(x)) m(x).asInstanceOf[String] else ""

    def fromj (o:Map[String, Any]) : Any = {
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

    // parse a DieselTrace
    def trace (o:Map[String, Any]) : DieselTrace = {
      DieselTrace (
        fromj (o("root").asInstanceOf[Map[String,Any]]).asInstanceOf[DomAst],
        o.getOrElse("node", "").toString,
        o.getOrElse("engineId", "").toString,
        o.getOrElse("app", "").toString,
        o.getOrElse("details", "").toString,
        o.get("parentNodeId").map(_.toString)
      )
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
  object EEWiki extends EExecutor("wiki") {

    override def isMock : Boolean = true
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "wiki"
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      in.met match {
        case "follow" => {
          //todo auth
          clog << "DIESEL.wiki.follow"
          val res = Wikil.wikiFollow(
            ctx("userName"),
            ctx("wpath"),
            ctx("how")
          )
        List(new EVal("result", res))
        }
        case _ => {Nil}
      }
    }

    override def toString = "$executor::wiki "
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
        case "log" => {
          clog << "DIESEL.log " + ctx.toString
          Nil
        }
        case "test" => {
          clog << "DIESEL.test " + ctx.toString

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

  /** some error, with a message and details */
  case class EError (msg:String, details:String="") extends CanHtml {
    override def toHtml = span("$error::", "danger", details) + " " + msg
    override def toString = "$error::"+msg
  }

  /** a simple info node with a message and details - details are displayed as a popup */
  case class EInfo (msg:String, details:String="") extends CanHtml with HasPosition {
    var pos : Option[EPos] = None
    def withPos(p:Option[EPos]) = {this.pos = p; this}

    override def toHtml = span("info::", "info", details) + " " + msg
    override def toString = "info::"+msg
  }

  // temp strip quotes from values
  //todo when types are supported, remove this method and all its uses
  def stripQuotes(s:String) =
      if(s.startsWith("\"") && s.endsWith("\"")) s.substring(1,s.length-1) else s

  // find the spec of the generated message, to ref
  private def spec(m:EMsg)(implicit ctx: ECtx) =
    ctx.domain.flatMap(_.moreElements.collect {
      case x: EMsg
        if
        ("*" == x.entity || x.entity == m.entity || regexm(x.entity, m.entity)) &&
        ("*" == x.met || x.met == m.met || regexm(x.met, m.met))
         => x
    }.headOption)

  /** snakk REST APIs */
  object EESnakk extends EExecutor("snakk") {
    /** can I execute this task? */
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.stype == "GET" || m.stype == "POST" || m.stype=="TELNET" ||
        spec(m).exists(m=> m.stype == "GET" || m.stype == "POST" || m.stype=="TELNET" ||
        ctx.findTemplate(m.entity+"."+m.met).isDefined)
    }

    private def prepUrl (url:String, attrs: Attrs) = {
      val PATT = """(\$\w+)*""".r
      val u = PATT.replaceSomeIn(url, { m =>
        val n = if(m.matched.length > 0) m.matched.substring(1) else ""
        attrs.find(_.name == n).map(x=>
          Enc.toUrl(stripQuotes(x.dflt))
        )
      })
      val res = new URI(u).toString()
      res
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

      def extract (response:String, kind:String, root:Snakk.Wrapper[_], temp:Seq[(String,String)], spec:Seq[(String,String, String)], regex:Option[String]) = {
        val hasValues = root \ "values"
        val r = if(hasValues.size > 0) hasValues else root

        def ex(n:String, d:String, e:String)  = {
          if (e.isEmpty)
            (n, (r \@@ n OR d).toString)
          else if (e.startsWith("/") && e.endsWith("/")) // regex
            (n, e.substring(1, e.length - 1).r.findFirstIn(response).mkString)
          else
            (n, (r \@@ e OR d).toString)
        }

        if(regex.isDefined) {
          val rex =
            if (regex.get.startsWith("/") && regex.get.endsWith("/"))
              regex.get.substring(1, regex.get.length - 1)
          else regex.get

          val jrex = Pattern.compile(rex).matcher(response)
//          val hasit = jrex.find()
          if(jrex.find())
          (
            for(g <- spec)
              yield (
                g._1,
                Try {
                  jrex.group(g._1)
                }.getOrElse(g._2)
                )
          ).toList
          else Nil
        } else if(temp.nonEmpty) {
          val strs = temp.map { t =>
            ex(t._1, "", t._2)
          }
          strs.toList
        } else if(spec.nonEmpty) {
          spec.map(t=>ex(t._1, t._2, t._3)).toList
        } else {
          //          (
          //            if(ret.nonEmpty) ret else List(new P("result", ""))
          //            ).map(p => p.copy(dflt = res \ "values" \@@ p.name)).map(x => EVal(x))
          val res = jsonParsed(response)
          val a = res.getJSONObject("values")
          val strs = (
            for (k <- 0 until a.names.length())
              yield (a.names.get(k).toString, a.getString(a.names.get(k).toString))
            )
          strs.toList
        }
      }

      // 1. prepare the request
      tcontent.map(parseTemplate).map {sc=>
        val newurl = if(sc.url startsWith "/") "http://" + ctx.hostname.mkString+sc.url else sc.url
        // with templates
        val x = url(
          prepUrl(stripQuotes(newurl),
            P("subject", "", "", "", in.entity) ::
            P("verb", "", "", "", in.met) :: in.attrs),
          //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
          (
            if(ctx.curNode.isDefined) sc.headers + ("dieselNodeId" -> ctx.curNode.get.id)
            else sc.headers
          ),
        sc.method
        )

        sc.setUrl(x)

        var eres : List[Any] = Nil
        eres = eres ::: EInfo("Snakking "+x.url, Enc.escapeHtml(sc.toJson)).withPos(Some(template.get.pos)) :: Nil
        var response = ""

        try {
          val root = sc.root
          response = sc.body //body(x)
          eres = eres ::: EInfo("Response" , Enc.escapeHtml(response)) :: Nil

            // message specified return mappings
          val ret = if(in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)

          // 2. find ret spec
          val temp = template.get.parms.split(",").map(s=>s.split("=")).map(a=> (a(0), a(1)))
          val x = if(in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)
          val specs = x.map(p=>(p.name, p.dflt, p.expr.mkString))
          val regex = temp.find(_._1 == "regex").map(_._2).orElse(specs.find(_._1 == "regex").map(_._3))

          // 2. extract values
          val strs = extract(response, "json", root, temp, specs, regex)

          val pos = (template.map(_.pos)).orElse (spec(in).flatMap(_.pos))
          // add the resulting values
          eres = eres ::: strs.map(t=> new P(t._1, t._2)).map{x =>
            ctx.put(x)
            EVal(x).withPos(pos)
          }

          // 3. look for stiching dieselTrace
          val mres = js.parse(response)
          if(mres.contains("dieselTrace")) {
            val trace = DieselJsonFactory.trace(mres("dieselTrace").asInstanceOf[Map[String,Any]])
            eres = eres ::: trace :: Nil
          }

          eres
        } catch {
          case t: Throwable => {
            razie.Log.log("error snakking", t)
            eres ::: EError("Error snakking: " + x, t.toString) ::
            EError("Exception : " + t.toString) ::
            EError("Response: ", response) :: Nil
          }
        }
      } getOrElse {
        // old way without templates
        in.attrs.find(_.name == "url").orElse(
          spec(in).flatMap(_.attrs.find(_.name == "url"))
        ).map { u =>
          val sc = new SnakCall(in.arch, u.dflt, Map.empty, "")
//          case class SnakCall (method:String, url:String, headers:Map[String,String], content:String) {
          val x = url(
            prepUrl(stripQuotes(u.dflt),
              P("subject", "", "", "", in.entity) ::
                P("verb", "", "", "", in.met) :: in.attrs)
            //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
            //            stype))
          )

          sc.setUrl(x)

          var eres : List[Any] = Nil
          eres = eres ::: EInfo("Snakking "+x.url, Enc.escapeHtml(sc.toJson)).withPos(spec(in).flatMap(_.pos)) :: Nil

          try {
            val root = sc.root
            val response = sc.body //body(x)
            eres = eres ::: EInfo("Response" , Enc.escapeHtml(response)) :: Nil

            // 2. extract values
            val specxxx = spec(in)
            val x = if(in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)
            val specs = x.map(p=>(p.name, p.dflt, p.expr.mkString))
            val regex = specs.find(_._1 == "regex") map (_._2)
            val strs = extract(response, "json", root, Seq.empty, specs, regex)

            val pos = (template.map(_.pos)).orElse (spec(in).flatMap(_.pos))
            // add the resulting values
            strs.map(t=> new P(t._1, t._2)).map{x =>
              ctx.put(x)
              EVal(x).withPos(pos)
            }
          } catch {
            case t: Throwable => {
              razie.Log.log("error snakking", t)
              eres ::: EError("Error snakking: " + u, t.toString) ::
              EError("Exception : " + t.toString) :: Nil
            }
          }
        } getOrElse EError("no url attribute for RESTification ") :: Nil
      }
    }

    /**
     * {{template rest.create}}
POST /diesel/fiddle/react/rest/mcreate?name=$name&address=$address&resultMode=json
sso_token : joe 123456
content-type: media

{
 "p1" : 1,
 "p2" : 2
}
{{/template}}

     * @param is
     * @return
     */
    def parseTemplate (is:String) = {
//      val regex = """(?s)(GET|POST) ([^\n]+)\n(([^\n]+\n)*)?(.+)?""".r
//      val regex (method, url, headers, content) = s

      // templates start with a \n
      val s = if(is startsWith "\n") is.substring(1) else is
      val verb = s.split(" ").head
      val url = s.split(" ").tail.head.replaceFirst("\n.*", "")
      val headers = if(s contains "\n\n") s.replaceFirst("(?s)\n\n.*", "").lines.drop(1).mkString("\n") else ""
      val content = if(s contains "\n\n") s.replaceFirst("(?s).*\n\n", "") else ""
      val hattr = headers.lines.map(_.split(":", 2)).collect{
        case a if a.size == 2 => (a(0).trim, a(1).trim)
      }.toSeq.toMap
      new SnakCall (verb, url, hattr, content.replaceFirst("(?s)\n$", ""))
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


