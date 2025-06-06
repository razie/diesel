/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import org.apache.commons.codec.digest.DigestUtils
import razie.clog
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom.{RDOM, _}
import razie.diesel.engine.DomEngineSettings.DIESEL_USER_ID
import razie.diesel.engine._
import razie.diesel.engine.nodes._
import razie.diesel.expr.{AExprFunc, DieselExprException, ECtx, StaticECtx}
import razie.diesel.model.DieselMsg
import razie.tconf.DUsers
import razie.tconf.hosting.Reactors
import razie.wiki.Base64
import razie.wiki.parser.CsvParser
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.util.Try

object EECtx {
  final val CTX = "ctx"
}

/** executor for "ctx." messages - operations on the current context */
class EECtx extends EExecutor(EECtx.CTX) {

  import razie.diesel.engine.exec.EECtx.CTX

  /** map of active contexts per transaction */
  val contexts = new TrieMap[String, ECtx]()

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == CTX // todo why not && messages.exists(_.met == m.met)
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    // todo I don't think i need this - the context should be a static msg context with all those anyways

    in.met match {

      case "regex" => {
        val payload = in.attrs.find(_.name == Diesel.PAYLOAD).getOrElse(ctx.getRequiredp(Diesel.PAYLOAD))
        val re = ctx.getp("regex").orElse(in.attrs.headOption)
        if(re.isEmpty) {
          List(EError("Need at least a regex parameter"))
        } else {
          // for regex matches, use each capture group and set as parm in context
          // extract parms
          val groups = EContent.extractRegexParms(re.get.calculatedValue, payload.calculatedValue)

          groups.map(t => EVal(new P(t._1, t._2)))
        }
      }

      case "persisted" => {
        contexts.get(ctx("kind") + ctx("id")).map(x =>
          if (ctx != x)
            ctx.root.overwrite(x)
        ).getOrElse {
          contexts.put(ctx("kind") + ctx("id"), ctx.root) // should I save this one?
        }
        Nil
      }

      case "log" => {
        clog << "ctx.log " + ctx.toString
        Nil
      }

      case "info" => {
        in.attrs.headOption.toList.map(p =>
          EInfo(p.name + " - click me", p.calculatedValue)
        )
      }

      case "test" => {
        clog << "ctx.test " + ctx.toString

        Nil
      }

      case "clear" => {
        ctx.getScopeCtx.clear
        ctx.clear
        Nil
      }

      case "reset" => {
        ctx.getScopeCtx.clear
        ctx.clear
        Nil
      }

      case "map" => {
        // l can be a constant with another parm name OR the actual array
        val list = {
          val l = ctx.getRequiredp("list").calculatedP
          if(l.isOfType(WTypes.wt.ARRAY)) {
            l
          } else if(l.isOfType(WTypes.wt.STRING)) {
            ctx.getRequiredp(l.currentStringValue)
          } else {
            new P("", "", WTypes.wt.UNDEFINED) //throw new IllegalArgumentException(s"Can't source input list: $ctxList")
          }
        }

        val ea = ctx.getRequired("msg")
        val EMsg.REGEX(e, m) = ea

        val x =
          if(list.calculatedTypedValue.contentType == WTypes.ARRAY)
            list.calculatedTypedValue.asArray
        else if(list.calculatedTypedValue.contentType == WTypes.UNDEFINED)
            Nil
        else
            razie.js.parse(s"{ list : ${list.calculatedValue} }").apply("list")

        x match {
          case l: collection.Seq[Any] => {
            val nat = in.attrs.filter(e => !Array("list", "item", "msg").contains(e.name))
            val res = l.map { item: Any =>
              val itemP = P.fromTypedValue(ctx.getRequired("item"), item)
              val args = itemP :: nat
              val out = AExprFunc(ea, args).applyTyped("")
              out.calculatedTypedValue.value
            }
            List(EVal(P.fromTypedValue(Diesel.PAYLOAD, res)))
          }
          case x@_ => {
            List(EError("map works on lists/arrays, found a: " + x.getClass.getName))
          }
        }
      }

//      case "batch" => {
//        val b = ctx.getRequiredp("batches").calculatedP.value.get.asInt
//        val s = ctx.getp("start").map(_.calculatedP.value.get.asInt).getOrElse(0)
//
//        val EMsg.REGEX(e, m) = parm("msg").get.currentStringValue
//        val itemName = parm("item").get.currentStringValue
//
//        // passing any other parameters that were given to foreach
//        val nat = in.attrs.filter(e => !Array("list", "item", "msg").contains(e.name))
//
//        (s .. b).map { item: Any =>
//              // for each item in list, create message
//          val itemP = P.fromTypedValue(itemName, item)
//          EMsg(e, m, itemP :: nat)
//        }.toList ::: info
//      }

      case "foreach" => {
        var info: List[Any] = Nil

        // l can be a constant with another parm name OR the actual array
        val list = {
          val l = ctx.getRequiredp("list").calculatedP
          if (l.isOfType(WTypes.wt.ARRAY)) {
            l
          } else if (l.isOfType(WTypes.wt.STRING)) {
            ctx.getRequiredp(l.currentStringValue)
          } else {
            info = EWarning(s"Can't source input list - what type is it? ${l}") :: info
            new P("", "", WTypes.wt.UNDEFINED) //throw new IllegalArgumentException(s"Can't source input list: $ctxList")
          }
        }

        val EMsg.REGEX(e, m) = ctx.getRequired("msg")
        val itemName = ctx.getRequired("item")

        val kidz = try {
          razie.js.parse(s"{ list : ${list.currentStringValue} }").apply("list") match {
            case l: collection.Seq[Any] => {
              // passing any other parameters that were given to foreach
              val nat = in.attrs.filter(e => !Array("list", "item", "msg").contains(e.name))

              l.map { item: Any =>
                // for each item in list, create message
                val itemP = P.fromTypedValue(itemName, item)
                (new EMsg(e, m, itemP :: nat) with KeepOnlySomeSiblings {keepCount = 5}).withPos(in.pos)
              }.toList ::: info
            }
            case x@_ => {
              List(EError("value to iterate on was not a list", x.getClass.getName) :: info)
            }
          }
        } catch {
          case throwable: Throwable => throw new DieselExprException(
            s"Caught ${throwable.toString} while evaluating ctx.foreach for list: " + list.currentStringValue)
        }

        kidz
      }

        // nice print of either input parms of default payload
      case "echo" => {
        val toPrint = if (in.attrs.nonEmpty) in.attrs else ctx.getp(Diesel.PAYLOAD).toList

        val res = toPrint.map { p =>
          EInfo(p.toHtml, p.calculatedTypedValue.asNiceString)
        }

        if (res.isEmpty) List(EInfo("No arguments with values found..."))
        else res
      }

      case "setVal" => {
        // setVal takes the name in a variable
        val n = in.attrs.find(_.name == "name").map(_.currentStringValue)
        val v = in.attrs.find(_.name == "value")

        // at this point the
        val res = n.flatMap { name =>
          if (v.exists(_.hasCurrentValue))
            Some(new EVal(name, v.get.currentStringValue))
          else if (v.exists(_.expr.isDefined))
            Some(new EVal(v.get.expr.get.applyTyped("").copy(name = name)))
          else if (v.exists(_.ttype != WTypes.wt.UNDEFINED))
            Some(new EVal(name, v.get.currentStringValue)) // for set (x="")
          else {
            // clear it
            def clear(c: ECtx) {
              c.remove(name)
              c.base.foreach(clear)
            }

            clear(ctx)
            Some(new EInfo("removed " + name))
          }
        }.orElse {
          v.map(_.calculatedP) // just v - copy it
        }.toList

        // ctx.set goes to the enclosing scope
        res.collect {
          case ev: EVal => DomRoot.setValueInScopeContext(ctx, ev.p)
        }

        res
      }

      case "export" => {
        // special export to scope - gets extra parm "toExport"

        if (in.attrs.find(_.name == "toExport").isEmpty) throw new DieselExprException(
          "ctx.export requires argument *toExport*")

        val ex = in.attrs.find(_.name == "toExport").get

        val res = in.attrs.filter(_.name != "toExport").map { p =>
          if (p.hasCurrentValue) // calculated already
            Some(new EVal(p))
          else if (p.expr.isDefined) // calculate now
            Some(new EVal(p.expr.get.applyTyped("").copy(name = p.name)))
          else if (p.ttype != WTypes.wt.UNDEFINED)
            Some(new EVal(p)) // set(x="") is not undefined...
          else {
            // clear it
            ctx.getScopeCtx.remove(p.name)
            None
          }
        }.filter(_.isDefined).map(_.get)

        if (res.isEmpty) {
          // parm was UNDEFINED and filtered out of attrs, remove it
          ctx.getScopeCtx.remove(ex.currentStringValue)
        }
        // ctx.set goes to the enclosing scope
        res.foreach(v =>
          // not doing this for exports - that's just scope normal parms - see specs tests, they fail this way
          //ctx.root.engine.map(_.setoSmartValueInContext(None, ctx.getScopeCtx, v.p))
          // INSTEAD: setting normally
          DomRoot.setValueInScopeContext(ctx, v.p)
        )
        res
      }

      case "set" => {
        // set all parms passed in - return EVals and make sure they're set in context
        // important to know how the scope contexts work
        val res = in.attrs.map { p =>
          if (p.hasCurrentValue) // calculated already
            Some(new EVal(p))
          else if (p.expr.isDefined) // calculate now
            Some(new EVal(p.expr.get.applyTyped("").copy(name = p.name)))
          else if (p.ttype != WTypes.wt.UNDEFINED)
            Some(new EVal(p)) // set(x="") is not undefined...
          else {
            // clear it
            ctx.getScopeCtx.remove(p.name)
            None
          }
        }.filter(_.isDefined).map(_.get)

        // ctx.set goes to the enclosing scope
        res.foreach{v =>
//            if(v.p.name contains ".") {
          ctx.root.engine.foreach(_.setoSmartValueInContext(None, ctx.getScopeCtx, v.p))

          ctx.root.engine.foreach(_.setoSmartValueInContext(None, ctx, v.p))

//            } else {
//          DomRoot.setValueInScopeContext(ctx, v.p)
//            }
        }

        // todo If I return Nil here, there will be a regression issue with booleans in "specs"
        res
      }

      case "setAll" => {
        // input is json - set all fields as ctx vals
        val res = in.attrs.map(_.calculatedP).filter(_.ttype == WTypes.JSON).flatMap { p =>
          p.calculatedTypedValue.asJson.map { t =>
            new EVal(P.fromTypedValue(t._1, t._2))
          }
        }

        // ctx.set goes to the enclosing scope
        res.foreach(v => DomRoot.setValueInScopeContext(ctx, v.p))
        res
      }

      case "debug" => {
        EInfo("Local attrs:") ::
            cdebug(in.attrs)
      }

      // debug current context
      case "trace" => {
        EInfo("All flattened looking up:") ::
            ctrace(ctx) :::
            EInfo("-----------debug:") ::
            cdebug(in.attrs)
      }

        // url safe version
      case "urlbase64encode" => {
        val res = in.attrs.filter(_.name != Diesel.RESULT).map { a =>
          import org.apache.commons.codec.binary.Base64
          def enc(s: String) = new Base64(true).encode(s.getBytes)

          val res = enc(a.calculatedValue)
          new EVal(a.name, new String(res).replaceAll("\n", "").replaceAll("\r", ""))
        }

        res ::: res.headOption.map(x=> x.copy(p=x.p.copy(name=Diesel.PAYLOAD))).toList
      }

        // normal base64 encoder
      case "base64encode" => {
        val res = in.attrs.filter(_.name != Diesel.RESULT).map { a =>
          import org.apache.commons.codec.binary.Base64
          def enc(s: String) = new Base64(false).encode(s.getBytes)

          val res = enc(a.calculatedValue)
          new EVal(a.name, new String(res).replaceAll("\n", "").replaceAll("\r", ""))
        }

        res ::: res.headOption.map(x=> x.copy(p=x.p.copy(name=Diesel.PAYLOAD))).toList
      }

      // take all args and create a json doc with them
      case "mkString" => {
        val pre  = ctx.getp("pre").map(_.calculatedValue).getOrElse("")
        val sep  = ctx.getp("separator").map(_.calculatedValue).getOrElse(",")
        val post = ctx.getp("post").map(_.calculatedValue).getOrElse("")

        // l can be a constant with another parm name OR the actual array
        val list = {
          val l = ctx.getp("list").getOrElse(ctx.getRequiredp(Diesel.PAYLOAD)).calculatedP
          if(l.isOfType(WTypes.wt.ARRAY)) {
            val arr = l.calculatedTypedValue.asArray
            arr
          } else {
//            info = EWarning(s"Can't source input list - what type is it? ${l}") :: info
            throw new IllegalArgumentException(s"Can't source input list: $l")
          }
        }

        val rows = list.map { obj =>
          PValue(obj).asString
        }.mkString(pre, sep, post)

        new EVal(
          RDOM.P.fromTypedValue(Diesel.PAYLOAD, rows, WTypes.wt.STRING)
        ) :: Nil
      }

      // take all args and create a json doc with them
      case "csv" | "jsonToCsv" => { // ctx.csv
        val separator = ctx.getRequired("separator")
        val csvStream = ctx.getp("csvStream").map(_.calculatedTypedValue.asString).flatMap(DieselAppContext.activeStreamsByName.get)
        val useHeaders = ctx.get("useHeaders").getOrElse("true").toBoolean
        val csvHeaders = ctx.getp("csvHeaders").map(_.calculatedTypedValue.asArray.toList)

        // l can be a constant with another parm name OR the actual array
        val list = {
          val l = ctx.getp("list").getOrElse(ctx.getRequiredp(Diesel.PAYLOAD)).calculatedP
          if (l.isOfType(WTypes.wt.ARRAY)) {
            val arr = l.calculatedTypedValue.asArray
            arr
          } else {
//            info = EWarning(s"Can't source input list - what type is it? ${l}") :: info
            throw new IllegalArgumentException(s"Can't source input list: $l")
          }
        }

        // collecting field names here to avoid empty
        var inames = new collection.mutable.ListBuffer[String]()

        val objects = list.map { obj =>
          val m = PValue(obj).asJson
//          val m = p.calculatedTypedValue.asJson
          // collect new names
          if(csvHeaders.isEmpty) inames.appendAll(m.keys.filter(x => !inames.contains(x)))
          m
        }.toList

        val names = if(csvHeaders.isEmpty) {
          inames.toList
        } else {
          csvHeaders.get.asInstanceOf[List[String]]
        }

        // collect new names
        var rows = objects.map { m =>
          names.map { n =>
            m
                .get(n)
                // some are arrays of 0 or 1...
                .filter(x=> P.isSimpleType(x) || P.isArrayOfSimpleType(x))
                .map(x => {

                  if (P.isSimpleNonStringType(x)) {
                    P.asString(x)
                     .replaceAll(separator, " ") // sort of escape , in value field
                  } else if (P.isArrayOfSimpleType(x)) {
                    val s = P.asSimpleString(x)
                    s.replaceAll("\"", "\"\"")  // wtf
                     .replaceAll(separator, " ") // sort of escape , in value field
                  } else {
                    "\"" + {
                      val s = P.asSimpleString(x)
                      s.replaceAll("\"", "\"\"")  // wtf
                      .replaceAll(separator, " ") // sort of escape , in value field
                    } + "\""
                  }

                }
                )
                .getOrElse("")
          }.mkString(separator)
        }

        rows = (if (useHeaders) List(names.mkString(separator)) else Nil) ++ rows

        if(csvStream.isDefined) csvStream.get.put(rows)

        val payload = if(csvStream.isDefined) Nil else List(new EVal(RDOM.P.fromSmartTypedValue(Diesel.PAYLOAD, rows)))
        val headers = if(csvHeaders.isDefined) Nil else List(new EVal(RDOM.P.fromSmartTypedValue("csvHeaders", names)))

        payload :: headers
      }

      // incoming csv parsed into json, based on header field.
      // if no header, fields will be "col0"..."colN"
      case "csvToJson" => {
        val separator = ctx.get("separator").getOrElse(",")
        val hasHeaders = ctx.get("hasHeaders").getOrElse("true").toBoolean
        val payload = ctx.getRequired(Diesel.PAYLOAD)
        var headers = new Array[String](0)

        val result: ListBuffer[Any] = new ListBuffer[Any]()

        // 1. parse into lines
        val parser = new CsvParser() {

          def doit(s: String, delim: String) = {

            parseAll (csv (delim), s) match {
              case Success   (value, _) => value.filter(_.nonEmpty)
              case NoSuccess (msg, _) => {
                result.append (EError(msg, msg))
                Nil
              }
              //todo ? throw new DieselExprException("Parsing error: " + msg)
            }
          }

          def consts (s: String) = {
            parseAll (csvnumConst, s) match {
              case Success   (value, _) => value
              case NoSuccess (msg, _) => {
                s // not matched, keep it
              }
              //todo ? throw new DieselExprException("Parsing error: " + msg)
            }
          }
        }

        // todo ehh not parse all in mem maybe?
        var lines: List[List[String]] = parser.doit (payload, separator)

        if (hasHeaders) {
          headers = lines.head.toArray
          lines = lines.drop(1)
        }

        val res = lines.map(l => {
          val m = new HashMap[String, Any]()
          l.zipWithIndex.foreach(x => {
            val k = if (hasHeaders) headers(x._2) else "col" + x._2

            var v = x._1

            if (v.trim.startsWith("\"")) {
              val y = v.replaceFirst("^\"", "").replaceFirst("\"$", "")
              m.put(k, y)
            } else {
              val y = parser.consts(v)
              if (y != null) {
                m.put(k, y)
              }
            }

          })

          m
        })

        if (hasHeaders) result.append (
          new EVal(RDOM.P.fromSmartTypedValue("csvHeaders", headers.toList))
        )

        result.append (
          new EVal(RDOM.P.fromTypedValue(Diesel.PAYLOAD, res.toList, WTypes.wt.ARRAY))
        )

        result.toList
      }

      // take all args and create a json doc with them
      case "json" => {
        val res = in.attrs.map(a => (a.name, a.calculatedTypedValue.value)).toMap

        new EVal(
          RDOM.P.fromTypedValue(Diesel.PAYLOAD, res, WTypes.wt.JSON)
        ) :: Nil
      }

      case "base64decode" => {
        val res = in.attrs.filter(_.name != Diesel.RESULT).map { a =>
          val res = Base64.dec(a.calculatedValue)
          new EVal(new RDOM.P(a.name, "", WTypes.wt.BYTES, None, "",
            Some(PValue[Array[Byte]](res, WType("application/octet-stream")))))
        }

        val res2 = res ::: res.headOption.map(x => x.copy(p = x.p.copy(name = Diesel.PAYLOAD))).toList
        res2
      }

      case "sha1" => {
        val res = in.attrs.filter(_.name != Diesel.RESULT).map { a =>
          val md = java.security.MessageDigest.getInstance("SHA-1")
          val s = md.digest(a.currentStringValue.getBytes("UTF-8")).map("%02X".format(_)).mkString
//          val sb = DigestUtils.sha1Hex(a.dflt)
          new EVal(a.name + "_sha1", s) //:: new EVal(a.name+"_sha1j", sb) :: Nil
        }

        res :::
            in.attrs
                .find(_.name == Diesel.RESULT)
                .map(_.calculatedValue)
                .orElse(Some(Diesel.PAYLOAD))
                .map(p => new EVal(p, res.head.p.currentStringValue))
                .toList
      }

      case "sha256" => {
        val res = in.attrs.filter(_.name != Diesel.RESULT).map { a =>
          val md = java.security.MessageDigest.getInstance("SHA-256")
          val s = DigestUtils.sha256Hex(a.currentStringValue)
          new EVal(a.name + "_sha256", s)
        }

        res :::
            in.attrs
                .find(_.name == Diesel.RESULT)
                .map(_.calculatedValue)
                .orElse(Some(Diesel.PAYLOAD))
                .map(p => new EVal(p, res.head.p.currentStringValue))
                .toList
      }

      case "timer" => {
        val d = in.attrs.find(_.name == "duration").map(_.currentStringValue.toInt).getOrElse(1000)
        val m = in.attrs.find(_.name == "msg").map(_.currentStringValue).getOrElse(
          "$msg ctx.echo (msg=\"timer without message\")")

        DieselAppContext ! DEStartTimer("x", d, Nil)

        new EInfo("ctx.timer - start " + d) :: Nil
      }

      case "sleep" => {

        /*
        this is not just asynchronous - but also
        1. suspends the engine
        2. ask the engine to send itself a continuation later DELater
        3. continuation DEComplete
         */

        val d = in.attrs.find(_.name == "duration").map(_.calculatedTypedValue.asLong.toInt).getOrElse(1000)
        EInfo("ctx.sleep - sleeping " + d) ::
            EEngSuspend("ctx.sleep", "", Some((e, a, l) => {
              DieselAppContext ! DELater(e.id, d, DEComplete(e.id, a.id, recurse = true, l, Nil))
            })) ::
            Nil
      }

      case "authUser" => {
        val uid =
        // the engine got it from the session/cookie
          ctx.root.engine.flatMap(_.settings.userId) orElse
              // or some test set it
              ctx.get(DIESEL_USER_ID)

        // todo lookup the user - either in DB or wix
        if (uid.isDefined)
          new EInfo("User is auth ") :: Nil
        else
          new EVal(DieselMsg.HTTP.STATUS, "401") ::
          new EVal(Diesel.PAYLOAD, "Error: User not auth") :: // payload will be shown, needs reset
              new EError(s"ctx.authUser - User not auth") ::
              new EEngStop(s"User not auth") :: Nil
      }

      case "setAuthUser" => setAuthUser(ctx)

      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  def setAuthUser (ctx:ECtx) = {
    /** run tests in the context of a user, configurable per domain */

    // was this engine triggered for a user ? like in a fiddle? Use that one
    val root = ctx.root
    val uid = ctx.root.engine.flatMap(_.settings.userId)

    if (uid.isEmpty) {
      // if no auth user, use the default - same default used for xapikey auth
      val uid =
        root.settings.realm
          .map(Reactors.impl.getProperties)
          .flatMap(_.get("diesel.xapikeyUserEmail"))
          .flatMap(DUsers.impl.findUserByEmailDec)
          .map(_.id)
          .getOrElse(
            "4fdb5d410cf247dd26c2a784" // an inactive account: Harry
          )

      // put straight in context - bypass trace nodes visible to users...
      ctx.put(new P(DIESEL_USER_ID, uid))

      new EInfo("User is now auth ") :: Nil
    } else
      new EInfo("User was already auth ") :: Nil
  }

  // debug current context
  def cdebug(in: List[P])(implicit ctx:ECtx) : List[EInfo] = {
    in.map { p =>
      new EInfo(s"${p.name} = ${p.currentStringValue} expr=(${p.expr}) cv= ${p.calculatedValue}")
    } :::
        (new EInfo(s"Ctx.listAttrs: ${ctx.getClass.getName}") ::
            ctx.flattenAllAttrs.map { p =>
              Try {
                new EInfo(s"${p.name} = ${p.currentStringValue} expr=(${p.expr}) cv= ${p.calculatedValue}")
              }.recover {
                case ex => new EInfo(s"${p.name} = ${p.currentStringValue} expr=(${p.expr}) cv= EXCEPTION: $ex")
              }.get
            })
  }

  // trace all contexts looking up
  def ctrace(c:ECtx)(implicit ctx:ECtx) : List[Any] = {
    EInfo("--------") ::
        (c match {
      case c@_ => {
        EInfo(c.toString) :: Nil
      }
    }) :: c.base.toList.flatMap(ctrace)
  }


  override def toString = "$executor::ctx "

  override val messages: List[EMsg] =
    EMsg(CTX, "persisted") ::
        EMsg(CTX, "log") ::
        EMsg(CTX, "echo") ::
        EMsg(CTX, "test") ::
        EMsg(CTX, "storySync") :: // processed by the story teller
        EMsg(CTX, "storyAsync") :: // processed by the story teller
        EMsg(CTX, "clear") ::
        EMsg(CTX, "reset") ::
        EMsg(CTX, "timer") ::
        EMsg(CTX, "sleep") ::
        EMsg(CTX, "set") ::
        EMsg(CTX, "setVal") ::
        EMsg(CTX, "setAll") ::
        EMsg(CTX, "sha1") ::
        EMsg(CTX, "sha256") ::
        EMsg(CTX, "foreach") ::
        EMsg(CTX, "trace") ::
        EMsg(CTX, "debug") ::
        EMsg(CTX, "authUser") ::
        EMsg(CTX, "setAuthUser") ::
        EMsg(CTX, "json") ::
        EMsg(CTX, "csv") ::
        EMsg(CTX, "mkString") ::
        EMsg(CTX, "export") ::
        Nil
}
