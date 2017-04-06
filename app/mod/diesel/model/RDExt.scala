/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import java.io.{BufferedReader, IOException, InputStreamReader, PrintWriter}
import java.net.{Socket, URI}
import java.util.regex.Pattern

import com.razie.pub.comms.Comms
import controllers.Wikil
import mod.diesel.controllers.{DieselControl, SFiddles}
import model.Users
import razie.db.RazSalatContext._
import razie.db._
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.novus.salat._
import mod.diesel.model.RDExt.{EError, EInfo}
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
import razie.xp.{JsonOWrapper, JsonWrapper}
import sun.misc.Regexp

import scala.util.Try

/** accumulate results and infos and errors */
class InfoAccumulator (var eres : List[Any] = Nil) {
  def += (x:Any) = append(x)
  def append (x:Any) = {
    x match {
      case l : List[_] => eres = eres ::: l
      case _ => eres = eres ::: x :: Nil
    }
    eres
  }
}

/** a single snakk call to make
  * @param protocol http, telnet
  * */
case class SnakCall (protocol:String, method:String, url:String, headers:Map[String,String], content:String) {
  def toJson = grater[SnakCall].toPrettyJSON(this)
  var isurl : SnakkUrl = null
  def setUrl(u:SnakkUrl) = {isurl = u}

  def postContent = if(content != null && content.length > 0) Some(content) else None

  var ibody : Option[String] = None
  var iContentType : Option[String] = None

  def body = ibody.getOrElse {
    val conn = Snakk.conn(isurl, postContent)
    val is = conn.getInputStream()
    iContentType = Some(conn.getHeaderField("Content-Type"))
    ibody = Some(Comms.readStream(is))
    ibody.get
  }

  /** xp root for either xml or json body */
  def root : Option[Snakk.Wrapper[_]]  = {
    val b = body
    iContentType match {
      case Some("application/xml") => Some(Snakk.xml(b))
      case Some("application/json") => Some(Snakk.json(b))
      case x@_ => {
//        throw new IllegalStateException ("unknown content-type: "+x)
        None
      }
    }
  }

  def telnet (hostname:String, port:String, send:Option[String], info:Option[InfoAccumulator]) : String = {
    var res = ""
    try {
      val pingSocket = new Socket(hostname, port.toInt);
      pingSocket.setSoTimeout(3000) // 1 sec
      val out = new PrintWriter(pingSocket.getOutputStream(), true);
      val ins = pingSocket.getInputStream();

//      out println "GET / HTTP/1.1"
//      out println "GET /diesel/test/plus/a/b HTTP/1.1"
//      out println ""
      send.toList.flatMap(_.lines.toList) map { outs=>
        out.println(outs)
        info map(_ += EInfo("telnet Sent line: "+outs))
      }

      val inp = new BufferedReader(new InputStreamReader(pingSocket.getInputStream()));

      import scala.collection.JavaConverters._
//      for(x <- inp.lines.iterator().asScala)
//        res = res + x
//      val res = inp.readLine()

//      res = inp.readLine()
//      res = res + Comms.readStream(ins)

      try
      {
        val buff = new Array[Byte](1024);
      var ret_read = 0;

      do
      {
        ret_read = ins.read(buff);
        if(ret_read > 0)
        {
          res = res + new String(buff, 0, ret_read)
//          System.out.print(new String(buff, 0, ret_read));
        }
      }
      while (ret_read >= 0);
      }

      ibody = Some(res)
      iContentType = Some("application/text")

      out.close();
      ins.close();
      pingSocket.close();

      res
    } catch {
      case e : Throwable => {
        info map(_ += EError("telnet Exception:"+ e.toString))
        ibody = Some(res)
        return res;
      }
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
        ).withPos(if(o.contains("pos")) Some(new EPos(o("pos").asInstanceOf[Map[String, Any]])) else None)

        case "EVal" => EVal (P(
          s("name"), "", "", "", s("value")
        )).withPos(if(o.contains("pos")) Some(new EPos(o("pos").asInstanceOf[Map[String, Any]])) else None)

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
  object EEWiki extends EExecutor("rk.wiki") {

    override def isMock : Boolean = true
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "rk.wiki" || m.entity == "wiki"
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

    override def toString = "$executor::rk.wiki "
    override val messages : List[EMsg] =
      EMsg("", "wiki", "follow", Nil) :: Nil
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
        case "engineSync" => {
          // turn the engine sync
          ctx.root.asInstanceOf[DomEngECtx].engine.map(_.synchronous = true)
          Nil
        }
        case "reset" => {
//          ctx.root.asInstanceOf[DomEngECtx].reset
          Nil
        }
        case "echo" => {
          in.attrs.map {a=>
            if(a.dflt != "")
              new EVal(a.name, a.dflt) // todo calc exprs
            else
              new EVal(a.name, ctx(a.name)) // if not given, then find it
          }
        }
        case "sleep" => {
          val d = in.attrs.find(_.name == "duration").map(_.dflt.toInt).getOrElse(1000)
          Thread.sleep(d)
          new EInfo("ctx.sleep - slept "+d) :: Nil
        }
        case "timer" => {
          val d = in.attrs.find(_.name == "duration").map(_.dflt.toInt).getOrElse(1000)
          val m = in.attrs.find(_.name == "msg").map(_.dflt).getOrElse("$msg ctx.echo (msg=\"timer without message\")")
          DieselAppContext.router.map(_ ! DEStartTimer("x",d,Nil))
          new EInfo("ctx.timer - start "+d) :: Nil
        }
        case s@_ => {
          new EError(s"ctx.$s - unknown activity ") :: Nil
        }
      }
    }

    override def toString = "$executor::ctx "

    override val messages : List[EMsg] =
      EMsg("", "ctx", "persisted", Nil) ::
      EMsg("", "ctx", "log", Nil) ::
      EMsg("", "ctx", "echo", Nil) ::
      EMsg("", "ctx", "test", Nil) ::
      EMsg("", "ctx", "engineSync", Nil) ::
      EMsg("", "ctx", "storySync", Nil) ::    // processed by the story teller
      EMsg("", "ctx", "storyAsync", Nil) ::    // processed by the story teller
      EMsg("", "ctx", "clear", Nil) :: Nil
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

            SFiddles.isfiddleMap(s, "js", None, None, q+("diesel" -> ""), Some(qTyped(q,f) + ("diesel" -> new api.diesel.DieselJs(ctx))))._2
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
    override def toHtml =
    if(details.length > 0)
      span("error::", "danger", details, "style=\"cursor:help\"") + " " + msg
    else
      span("error::", "danger", details) + " " + msg
    override def toString = "error::"+msg
  }

  /** a simple info node with a message and details - details are displayed as a popup */
  case class EInfo (msg:String, details:String="") extends CanHtml with HasPosition {
    var pos : Option[EPos] = None
    def withPos(p:Option[EPos]) = {this.pos = p; this}

    override def toHtml =
      if(details.length > 0)
        span("info::", "info", details, "style=\"cursor:help\"") + " " + msg
      else
        span("info::", "info", details) + " " + msg
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

  /** a REST request or response: content and type */
  class EEContent (val body:String, val ctype:String, val iroot:Option[Snakk.Wrapper[_]] = None) {
    def isXml = "application/xml" == ctype
    def isJson = "application/json" == ctype

    import razie.Snakk._

    /** xp root for either xml or json body */
    lazy val root:Snakk.Wrapper[_] = iroot getOrElse {
      ctype match {
        case "application/xml" => Snakk.xml(body)
        case "application/json" => Snakk.json(body)
        case x@_ => Snakk.json("")
        //throw new IllegalStateException ("unknown content-type: "+x)
      }
    }

    lazy val hasValues = root \ "values"
    lazy val r = if(hasValues.size > 0) hasValues else root

    // todo not optimal
    def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean = {
      val x = (r \ "*").nodes collect {
         case j : JsonOWrapper => {
           razie.MOLD(j.j.keys).map(_.toString).map {n=>
             P(n, "", "", "", r \ "*" \@@ n)
           }
        }
      }
      x.flatten.exists(f)
    }

    // todo don't like this
    def get(name:String) : Option[String]  = {
      (r \@@ name).toOption
    }

    /** name, default, expr */
    def ex(n:String, d:String, e:String)  = {
      if (e.isEmpty)
        (n, (r \@@ n OR d).toString)
      else if (e.startsWith("/") && e.endsWith("/")) // regex
        (n, e.substring(1, e.length - 1).r.findFirstIn(body).mkString)
      else
        (n, (r \@@ e OR d).toString)
    }

    /** extract the values and expressions from a response
      *
      * @param temp a list of name/expression
      * @param spec a list of name/default/expression
      * @param regex an optional regex with named groups
      */
    def extract (temp:Map[String,String], spec:Seq[(String,String, String)], regex:Option[String]) = {

      if(regex.isDefined) {
        val rex =
          if (regex.get.startsWith("/") && regex.get.endsWith("/"))
            regex.get.substring(1, regex.get.length - 1)
          else regex.get

        val jrex = Pattern.compile(rex).matcher(body)
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
        // main case
        spec.map(t=>ex(t._1, t._2, t._3)).toList
      } else {
        // last ditch attempt to discover some values

        //          (
        //            if(ret.nonEmpty) ret else List(new P("result", ""))
        //            ).map(p => p.copy(dflt = res \ "values" \@@ p.name)).map(x => EVal(x))
        val res = jsonParsed(body)
        val a = res.getJSONObject("values")
        val strs = if(a.names == null) List.empty else (
          for (k <- 0 until a.names.length())
            yield (a.names.get(k).toString, a.getString(a.names.get(k).toString))
          ).toList
        strs.toList
      }
    }

  }

  /** snakk REST APIs */
  object EESnakk extends EExecutor("snakk") {

    /** can I execute this task? */
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      (m.stype contains "GET") || (m.stype contains "POST") || (m.stype contains "TELNET") ||
        spec(m).exists(m=>
          (m.stype contains "GET") || (m.stype contains "POST") || (m.stype contains "TELNET") ||
        ctx.findTemplate(m.entity+"."+m.met).isDefined)
    }

    /** prepare the template content - expand $parm expressions */
    private def prepStr (url:String, attrs: Attrs) = {
      //todo add expressions like ${...}
      val PATT = """(\$\w+)*""".r
      val u = PATT.replaceSomeIn(url, { m =>
        val n = if(m.matched.length > 0) m.matched.substring(1) else ""
        attrs.find(_.name == n).map(x=>
          stripQuotes(x.dflt)
        )
      })
      u
    }

    /** prepare the URL - expand $parm expressions */
    private def prepUrl (url:String, attrs: Attrs) = {
      //todo add expressions like ${...}
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

      var eres = new InfoAccumulator()
      var response = "" // actual textual response
      var urlx = "?" // url, filled later for error rep

      try {

        // 1. prepare the request
        tcontent.map(parseTemplate(template, _, in.attrs)).map { sc =>
          val newurl = if (sc.url startsWith "/") "http://" + ctx.hostname.mkString + sc.url else sc.url
          // with templates

          val content =
          if(template.flatMap(_.parms.get("protocol")).exists(_ == "telnet")) {
            // telnet
            eres += EInfo("Snakking TELNET ", Enc.escapeHtml(sc.toJson)).withPos(Some(template.get.pos))
//            response = sc.body // make the call
            val REX = """([.\w]+)[:/ ](\w+).*""".r
            val REX(host,port) = newurl
            response = sc.telnet(host, port, sc.postContent, Some(eres))
            eres += EInfo("Response", Enc.escapeHtml(response))
            val content = new EEContent(response, sc.iContentType.getOrElse(""), None)
            content
          } else {
            // http
            val x = url(
              prepUrl(stripQuotes(newurl),
                P("subject", "", "", "", in.entity) ::
                  P("verb", "", "", "", in.met) :: in.attrs),
              //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
              (
                sc.headers ++ {
                  ctx.curNode.map(node =>
                    ("dieselNodeId" -> node.id)).toList ++
                    ctx.root.asInstanceOf[DomEngECtx].settings.userId.map(x =>
                      ("dieselUserId" -> x)).toList ++
                    ctx.root.asInstanceOf[DomEngECtx].settings.configTag.map(x =>
                      ("dieselConfigTag" -> x)).toList
                }.toMap
                ),
              sc.method
            )

            urlx = x.toString
            sc.setUrl(x)

            eres += EInfo("Snakking " + x.url, Enc.escapeHtml(sc.toJson)).withPos(Some(template.get.pos))
            response = sc.body // make the call
            eres += EInfo("Response", Enc.escapeHtml(response))
            val content = new EEContent(response, sc.iContentType.getOrElse(""), sc.root)
            content
          }

          // does the template have return parm specs?
          val temp = template.get.parms

          // message specified return mappings, if any
          val retSpec = if (in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)
          // collect any parm specs
          val specs = retSpec.map(p => (p.name, p.dflt, p.expr.mkString))
          val regex = temp.find(_._1 == "regex").map(_._2).orElse(specs.find(_._1 == "regex").map(_._3))

          // 2. extract values
          val strs = content.extract(temp, specs, regex)

          val pos = (template.map(_.pos)).orElse(spec(in).flatMap(_.pos))
          // add the resulting values
          eres += strs.map(t => new P(t._1, t._2)).map { x =>
            ctx.put(x)
            EVal(x).withPos(pos)
          }

          // 3. look for stiching dieselTrace
          if (content.isJson) {
            val mres = js.parse(response)
            if (mres.contains("dieselTrace")) {
              val trace = DieselJsonFactory.trace(mres("dieselTrace").asInstanceOf[Map[String, Any]])
              eres += trace
            }
          }

          eres.eres
        } getOrElse {
          // no template or tcontent => old way without templates
          in.attrs.find(_.name == "url").orElse(
            spec(in).flatMap(_.attrs.find(_.name == "url"))
          ).map { u =>
            val sc = new SnakCall("http", in.arch, u.dflt, Map.empty, "")
            //          case class SnakCall (method:String, url:String, headers:Map[String,String], content:String) {
            val ux = url(
              prepUrl(stripQuotes(u.dflt),
                P("subject", "", "", "", in.entity) ::
                  P("verb", "", "", "", in.met) :: in.attrs)
              //            in.attrs.filter(_.name != "url").map(p=>(p.name -> p.dflt)).toMap,
              //            stype))
            )

            urlx = ux.toString
            sc.setUrl(ux)

            eres += EInfo("Snakking " + ux.url, Enc.escapeHtml(sc.toJson)).withPos(spec(in).flatMap(_.pos))

            val content = {
              if (sc.method == "open") {
                val response = sc.telnet("localhost", "9000", sc.postContent, Some(eres))
                new EEContent(sc.body, "application/text", None)
              } else {
                val response = sc.body
                eres += EInfo("Response", Enc.escapeHtml(response))

                new EEContent(sc.body, sc.iContentType.getOrElse(""), sc.root)
              }
            }

            // 2. extract values
            val specxxx = spec(in)
            val x = if (in.ret.nonEmpty) in.ret else spec(in).toList.flatMap(_.ret)
            val specs = x.map(p => (p.name, p.dflt, p.expr.mkString))
            val regex = specs.find(_._1 == "regex") map (_._2)
            val strs = content.extract(Map.empty, specs, regex)

            val pos = (template.map(_.pos)).orElse(spec(in).flatMap(_.pos))
            // add the resulting values
            strs.map(t => new P(t._1, t._2)).map { x =>
              ctx.put(x)
              EVal(x).withPos(pos)
            }
          } getOrElse
            EError("no url attribute for RESTification ") :: Nil
        }
      } catch {
        case t: Throwable => {
          razie.Log.log("error snakking", t)
          eres += EError("Error snakking: " + urlx, t.toString) ::
            EError("Exception : " + t.toString) ::
            EError("Response: ", response) :: Nil
        }
      }
    }

    /** parse a template into a SNakkCall
      *
{{template rest.create}}
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
    def parseTemplate (t:Option[DTemplate], is:String, attrs: Attrs) = {
      if(t.flatMap(_.parms.get("protocol")).exists(_ == "telnet"))
        parseTelnetTemplate(t, is, attrs)
      else parseHttpTemplate(t, is, attrs)
    }

    def parseTelnetTemplate (t:Option[DTemplate], is:String, attrs: Attrs) = {
      // templates start with a \n
      val s = if(is startsWith "\n") is.substring(1) else is
      val lines = s.lines
      val REX = """(\w+) ([.\w]+)[:/ ](\w+).*""".r
      val REX(verb,host,port) = lines.take(1).next
      val content = lines.mkString("\n")
      new SnakCall ("telnet", "telnet", s"$host:$port", Map.empty, prepStr(content, attrs).replaceFirst("(?s)\n\r?$", ""))
    }

    def parseHttpTemplate (template:Option[DTemplate], is:String, attrs: Attrs) = {
//      val regex = """(?s)(GET|POST) ([^\n]+)\n(([^\n]+\n)*)?(.+)?""".r
//      val regex (method, url, headers, content) = s

      // templates start with a \n
      val s = if(is startsWith "\n") is.substring(1) else is
      val verb = s.split(" ").head
      val url = s.split(" ").tail.head.replaceFirst("\n.*", "")
      val headers = if(s matches "(?s).*\n\r?\n\r?.*") s.replaceFirst("(?s)\n\r?\n\r?.*", "").lines.drop(1).mkString("\n") else ""
      var content = if(s matches "(?s).*\n\r?\n\r?.*") s.replaceFirst("(?s).*\n\r?\n\r?", "") else ""
      if(content endsWith("\n")) content = content.substring(0, content.length-1)
      val hattr = prepStr(headers, attrs).lines.map(_.split(":", 2)).collect{
        case a if a.size == 2 => (a(0).trim, a(1).trim)
      }.toSeq.toMap
      new SnakCall ("http", verb, url, hattr, prepStr(content, attrs).replaceFirst("(?s)\n\r?$", ""))
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
            n.i.map {x=>
              collectMsg(x.asMsg.withPos(n.pos))
            }
          }
          case n: EMock => {
            collectMsg(n.rule.e.asMsg.withPos(n.pos), n.rule.i.flatMap(_.attrs))
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

    // add known executors
    Executors.all.flatMap(_.messages).map(m=> collect(m.entity, m.met))

    Map(
      "msg" -> {
        d.moreElements.collect({
          case n: EMsg => collect(n.entity, n.met)
          case n: ERule => {
            collect(n.e.cls, n.e.met)
            n.i.map {x=>
              collect(x.cls, x.met)
            }
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
            n.i.map { x =>
              collectP(x.attrs)
            }
          }
          case n: EMock => collectPM(n.rule.e.attrs)
          case n: ExpectM => collectPM(n.m.attrs)
        })

        // return collected
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


