/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model.parser

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
import razie.diesel.ext.{MatchCollector, _}
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
import mod.diesel.model.RDExt.{DieselJsonFactory, EEContent, spec}
import mod.diesel.model.{DEStartTimer, DieselAppContext, InfoAccumulator}

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

  object EECtx extends EExecutor("ctx") {

    /** map of active contexts per transaction */
    val contexts = new mutable.HashMap[String, ECtx]()

    override def isMock : Boolean = true
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "ctx"
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
      def parm(s:String) : Option[P] = in.attrs.find(_.name == s).orElse(ctx.getp(s))

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
        case "foreach" => {
          val list = ctx.getp(parm("list").get.dflt).get
          val RE = """(\w+)\.(\w+)""".r
          val RE(e,m) = parm("msg").get.dflt

          razie.js.parse(s"{ list : ${list.dflt} }").apply("list") match {
            case l:List[Any] => {
              val nat = in.attrs.filter(e=> !Array("list", "item", "msg").contains(e.name))
              l.map { item:Any =>
                val is = razie.js.anytojsons(item)
                EMsg("", e, m, RDOM.typified(parm("item").get.dflt, is) :: nat)
              }
            }
            case x @ _ => {
              List(EError("list was not a list", x.getClass.getName))
            }
          }
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

// same as memdb, but it is shared across all users, like a real micro-service would behave
object EEDieselSharedDb extends EExecutor("diesel.shareddb") {

  /** map of active contexts per transaction */
  val tables = new mutable.HashMap[String, mutable.HashMap[String, Any]]()

  override def isMock : Boolean = true
  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "diesel.shareddb"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    val col = ctx("collection")
    in.met match {
      case "get" => {
        tables.get(col).flatMap(_.get(ctx("id"))).map(x=>EVal(P("document", x.toString))).toList
      }
      case cmd @ ("upsert") => {
        if(!tables.contains(col))
          tables.put(col, new mutable.HashMap[String, Any]())

        val id = if(ctx("id").length > 0) ctx("id") else new ObjectId().toString

        tables(col).put(id, ctx("document"))
        EVal(P("id", id)) :: Nil
      }
      case "log" => {
        val res = tables.keySet.map { k =>
          "Collection: " + k + "\n" +
            tables(k).keySet.map { id =>
              "  " + id + " -> " + tables(k)(id).toString
            }.mkString("  ", "\n", "")
        }.mkString("\n")
        EVal(P("result", res)) :: Nil
      }
      case "clear" => {
        tables.clear()
        Nil
      }
      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::shareddb "

  override val messages : List[EMsg] =
    EMsg("", "diesel.shareddb", "upsert", Nil) ::
      EMsg("", "diesel.shareddb", "get", Nil) ::
      EMsg("", "diesel.shareddb", "log", Nil) ::
      EMsg("", "diesel.shareddb", "clear", Nil) :: Nil
}

  // the context persistence commands - isolated per user and/or anon session
  object EEDieselMemDb extends EExecutor("diesel.memdb") {

    /** map of active contexts per transaction */
    case class Col (name:String, entries : mutable.HashMap[String,Any] = new mutable.HashMap[String, Any]())
    case class Session (name:String, var time:Long = System.currentTimeMillis(), tables:mutable.HashMap[String, Col] = new mutable.HashMap[String, Col]())
    val sessions = new mutable.HashMap[String, Session]()

    override def isMock : Boolean = true
    override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
      m.entity == "diesel.memdb"
    }

    override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
      cleanup
      // user id or anon session (one workflow)
      val sessionId = ctx.credentials.getOrElse(ctx.root.asInstanceOf[DomEngECtx].engine.get.id)
      val session = sessions.get(sessionId).getOrElse {
        if(sessions.size > 400)
          throw new IllegalStateException("Too many in-mem db sessions")

        val x = Session(sessionId)
        sessions.put(sessionId, x)
        x
      }
      session.time = System.currentTimeMillis() // should I or not?
      val tables = session.tables

      val col = ctx("collection")

      def log = {
        tables.keySet.map { k =>
          "Collection: " + k + "\n" +
            tables(k).entries.keySet.map { id =>
              "  " + id + " -> " + tables(k).entries(id).toString
            }.mkString("  ", "\n", "")
        }.mkString("\n")
      }

      in.met match {
        case "get" => {
          tables.get(col).flatMap(_.entries.get(ctx("id"))).map(x=>EVal(P("document", x.toString))).toList
        }
        case cmd @ ("upsert") => {
          if(tables.size > 5)
            throw new IllegalStateException("Too many collections (5)")

          if(!tables.contains(col))
            tables.put(col, Col(col))

          val id = if(ctx("id").length > 0) ctx("id") else new ObjectId().toString

          if(tables(col).entries.size > 15)
            throw new IllegalStateException("Too many entries in collection (15)")

          tables(col).entries.put(id, ctx("document"))
          EVal(P("id", id)) :: Nil
        }
        case "logAll" => {
          val res = s"Sessions: ${sessions.size}\n" + log
          EVal(P("result", res)) :: Nil
        }
        case "log" => {
          EVal(P("result", log)) :: Nil
        }
        case "clear" => {
          tables.clear()
          Nil
        }
        case s@_ => {
          new EError(s"ctx.$s - unknown activity ") :: Nil
        }
      }
    }

    def cleanup = {
      val oldies = sessions.filter(System.currentTimeMillis() - _._2.time > 10*60*1000)
      for (elem <- oldies) sessions.remove(elem._1)
    }

    override def toString = "$executor::memdb "

    override val messages : List[EMsg] =
      EMsg("", "diesel.memdb", "upsert", Nil) ::
        EMsg("", "diesel.memdb", "get", Nil) ::
        EMsg("", "diesel.memdb", "log", Nil) ::
//        EMsg("", "diesel.memdb", "logAll", Nil) :: // undocumented
        EMsg("", "diesel.memdb", "clear", Nil) :: Nil
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
                P("subject", in.entity) ::
                  P("verb", in.met) :: in.attrs),
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
                P("subject", in.entity) ::
                  P("verb", in.met) :: in.attrs)
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


