/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import mod.diesel.model.parser.{BFlowExpr, FlowExpr, MsgExpr, SeqExpr}
import play.libs.Akka
import razie.diesel.ext._
import mod.diesel.model.RDExt._
import org.bson.types.ObjectId
import play.api.mvc.{AnyContent, Request}
import razie.clog
import razie.diesel.dom._
import RDOM._
import razie.wiki.Services
import razie.wiki.dom.WikiDomain
import razie.wiki.model.WikiEntry

import scala.Option.option2Iterable
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global
import DomState._
import controllers.RazRequest

import scala.concurrent.duration.Duration

/** the kinds of nodes we understand */
object AstKinds {
  final val ROOT = "root"
  final val RECEIVED = "received"
  final val SAMPLED = "sampled"
  final val GENERATED = "generated"
  final val SUBTRACE = "subtrace"
  final val RULE = "rule"
  final val SKETCHED = "sketched"
  final val MOCKED = "mocked"
  final val TEST = "test"

  def isGenerated  (k:String) = GENERATED==k || SKETCHED==k || MOCKED==k
  def shouldIgnore (k:String) = RULE==k
}

object DomState {
  final val INIT="final.init" // new node
  final val STARTED="exec.started" // is executing now
  final val DONE="final.done" // done
  final val LATER="exec.later" // queued up somewhere for later
  final val DEPENDENT="exec.depy" // waiting on another task

  def inProgress(s:String) = s startsWith "exec."
}

/** an application static - engine factory and cache */
object DieselAppContext {
  private var appCtx : Option[DieselAppContext] = None
  var engMap = new mutable.HashMap[String,DomEngine]()
  var refMap = new mutable.HashMap[String,ActorRef]()
  var router : Option[ActorRef] = None

  /** initialize the engine cache and actor infrastructure */
  def init (node:String="", app:String="") = {
    if(appCtx.isEmpty) appCtx = Some(new DieselAppContext(
      if(node.length > 0) node else Services.config.node,
      if(app.length > 0) app else "default"
    ))

    val p = Props(new DomEngineRouter())
    val a = Akka.system.actorOf(p)
    router = Some(a)
    a ! DEInit

    appCtx.get
  }

  /** the static version - delegates to factory */
  def mkEngine(dom: RDomain, root: DomAst, settings: DomEngineSettings, pages : List[DSpec]) = {
    val eng = ctx.mkEngine(dom, root, settings, pages)
    val p = Props(new DomEngineActor(eng))
    val a = Akka.system.actorOf(p, name = eng.id)
    DieselAppContext.engMap.put(eng.id, eng)
    DieselAppContext.refMap.put(eng.id, a)
    a ! DEInit
    eng
  }

  def engines = engMap.values.toList

  def stop = {
  }

  def ctx = appCtx.getOrElse(init())
}

/** a diesel app context */
class DieselAppContext (node:String, app:String) {
  /** make an engine instance for the given AST root */
  def mkEngine(dom: RDomain, root: DomAst, settings: DomEngineSettings, pages : List[DSpec]) =
    new DomEngine(dom, root, settings, pages)
}

case class DieselTrace(
  root:DomAst,
  node:String,        // actual server node
  engineId:String,      // engine Id
  app:String,         // application/system id twitter:pc:v5
  details:String="",
  parentNodeId:Option[String]=None ) extends CanHtml {

  def toj: Map[String, Any] =
    Map(
      "class" -> "DieselTrace",
      "ver" -> "v1",
      "node" -> node,
      "engineId" -> engineId,
      "app" -> app,
      "details" -> details,
      "id" -> root.id,
      "parentNodeId" -> parentNodeId.mkString,
      "root" -> root.toj
    )

  def toJson = toj

  override def toHtml = span("trace::", "primary") + s"$details (node=$node, engine=$engineId, app=$app) :: " + root.toHtml

  override def toString = toHtml
}

/** a tree node
  *
  * kind is spec/sampled/generated/test etc
  *
  * todo optimize tree structure, tree binding
  *
  * todo need ID conventions to suit distributed services
  */
case class DomAst(
  value: Any,
  kind: String = AstKinds.GENERATED,
  children: ListBuffer[DomAst] = new ListBuffer[DomAst](),
  id : String = new ObjectId().toString
  ) extends CanHtml {

  private var istatus:String = DomState.INIT
  def status:String = istatus
  def status_=(s:String) = istatus = s

  var moreDetails = " "
  var specs: List[Any] = Nil
  var prereq: List[String] = Nil

  /** depends on other nodes by IDs */
  def withPrereq (s:List[String]) = {
    prereq = s ::: prereq
    this
  }

  /** this node has a spec */
  def withSpec (s:Any) = {
    specs = s :: specs
    this
  }

  def withDetails (s:String) = {
    moreDetails = moreDetails + s
    this
  }

  def tos(level: Int, html:Boolean): String = ("  " * level) + kind + "::" + {
    value match {
      case c:CanHtml if(html) => c.toHtml
      case x => x.toString
    }
  }.lines.map(("  " * level) + _).mkString("\n") + moreDetails + "\n" +
    children.filter(k=> !AstKinds.shouldIgnore(k.kind)).map(_.tos(level + 1, html)).mkString

  override def toString = tos(0, false)
  override def toHtml = tos(0, true)

  def toj : Map[String,Any] =
    Map (
      "class" -> "DomAst",
      "kind" -> kind,
      "value" ->
        (value match {
          case m:EMsg => m.toj
          case v:EVal => v.toj
          case x => x.toString
        }),
      "details" -> moreDetails,
      "id" -> id,
      "status" -> status,
      "children" -> children.filter(k=> !AstKinds.shouldIgnore(k.kind)).map(_.toj).toList
    )

  def toJson = toj

  // visit/recurse with filter
  def collect[T](f: PartialFunction[DomAst, T]) : List[T] = {
    val res = new ListBuffer[T]()
    def inspect(d: DomAst, level: Int): Unit = {
      if (f.isDefinedAt(d)) res append f(d)
      d.children.map(inspect(_, level + 1))
    }
    inspect(this, 0)
    res.toList
  }

  /** GUI needs position info for surfing */
  def posInfo = collect{
    case d@DomAst(m:EMsg, _, _, _) if(m.pos.nonEmpty) =>
      Map(
        "kind" -> "msg",
        "id" -> (m.entity+"."+m.met),
        "pos" -> m.pos.get.toJmap
      )
  }

  /** find in subtree, by id */
  def find(id:String) : Option[DomAst] =
    if(this.id == id) Some(this)
    else children.foldLeft(None:Option[DomAst])((a,b)=>a orElse b.find(id))
}

object DomEngineSettings {
  /** */
  def from(stok:RazRequest) = {
    val q = fromRequest(stok.req)

    // find the config tag (which configuration to use - default to userId
    if(q.configTag.isEmpty && stok.au.isDefined)
      q.configTag = Some(stok.au.get._id.toString)

    // todo should keep the original user or switch?
    if(q.userId.isEmpty && stok.au.isDefined)
      q.userId = Some(stok.au.get._id.toString)

    q
  }

  /** take the settings from either URL or body form or default */
  def fromRequest(request:Request[AnyContent]) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    // form query
    def fParm(name:String)=
      request.body.asFormUrlEncoded.flatMap(_.getOrElse(name, Seq.empty).headOption)

    // from query or body
    def fqParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).getOrElse(dflt)

    def fqhParm(name:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name))

    def fqhoParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).orElse(request.headers.get(name)).getOrElse(dflt)

    new DomEngineSettings(
      mockMode = fqhoParm(MOCK_MODE, "true").toBoolean,
      blenderMode = fqhoParm(BLENDER_MODE, "true").toBoolean,
      draftMode = fqhoParm(DRAFT_MODE, "true").toBoolean,
      sketchMode = fqhoParm(SKETCH_MODE, "false").toBoolean,
      execMode = fqhoParm(EXEC_MODE, "sync"),
      resultMode = fqhoParm(RESULT_MODE, "json"),
      parentNodeId = fqhParm("dieselNodeId"),
      configTag = fqhParm("dieselConfigTag"),
      userId = fqhParm("dieselUserId"),
      {
        if(request.contentType.exists(c=> c == "application/json")) {
          Some(new EEContent(request.body.asJson.mkString, request.contentType.get))
        } else None
      }
    )
  }

  /** take the settings from either URL or body form or default */
  def fromJson(j:Map[String, String]) = {
    def fqhParm(name:String) =
      j.get(name)

    def fqhoParm(name:String, dflt:String) =
      j.get(name).getOrElse(dflt)

    new DomEngineSettings(
      mockMode = fqhoParm(MOCK_MODE, "true").toBoolean,
      blenderMode = fqhoParm(BLENDER_MODE, "true").toBoolean,
      draftMode = fqhoParm(DRAFT_MODE, "true").toBoolean,
      sketchMode = fqhoParm(SKETCH_MODE, "false").toBoolean,
      execMode = fqhoParm(EXEC_MODE, "sync"),
      resultMode = fqhoParm(RESULT_MODE, "json"),
      parentNodeId = fqhParm(DIESEL_NODE_ID),
      configTag = fqhParm(DIESEL_CONFIG_TAG),
      userId = fqhParm(DIESEL_USER_ID)
    )
  }

  final val SKETCH_MODE="sketchMode"
  final val MOCK_MODE="mockMode"
  final val BLENDER_MODE="blenderMode"
  final val DRAFT_MODE="draftMode"
  final val EXEC_MODE="execMode"
  final val RESULT_MODE="resultMode"
  final val DIESEL_NODE_ID = "dieselNodeId"
  final val DIESEL_CONFIG_TAG = "dieselConfigTag"
  final val DIESEL_USER_ID = "dieselUserId"
  final val TAG_QUERY = "tagQuery"
  final val FILTER = Array(SKETCH_MODE, MOCK_MODE, BLENDER_MODE, DRAFT_MODE, EXEC_MODE, RESULT_MODE)
}

class DomEngineSettings
(
  var mockMode    : Boolean = false,
  var blenderMode : Boolean = true,
  var draftMode   : Boolean = true,
  var sketchMode  : Boolean = true,
  var execMode    : String = "sync",
  var resultMode    : String = "json",

  // when ran for a separate request
  var parentNodeId: Option[String] = None,

  /** tag for configuration: either a userId or a recognized global tag */
  var configTag : Option[String] = None,

  /** user id */
  var userId : Option[String] = None,

  /** content that was posted with the request */
  var postedContent : Option[EEContent] = None,

  /** tag query to select for modeBlender */
  var tagQuery : Option[String] = None
  ) {
  val node = Services.config.node

  /** is this supposed to use a user cfg */
  def configUserId = configTag.map(x=>if(ObjectId.isValid(x)) Some(new ObjectId(x)) else None)

  def toJson : Map[String,String] = {
    import DomEngineSettings._
    Map(
      MOCK_MODE -> mockMode.toString,
      SKETCH_MODE -> sketchMode.toString,
      BLENDER_MODE -> blenderMode.toString,
      DRAFT_MODE -> draftMode.toString,
      EXEC_MODE -> execMode,
      RESULT_MODE -> resultMode
    ) ++ parentNodeId.map(x=>
      Map(DIESEL_NODE_ID -> x)
    ).getOrElse(Map.empty) ++ configTag.map(x=>
      Map(DIESEL_CONFIG_TAG -> x)
    ).getOrElse(Map.empty) ++ userId.map(x=>
      Map(DIESEL_USER_ID -> x)
    ).getOrElse(Map.empty) ++ tagQuery.map(x=>
      Map(TAG_QUERY -> x)
    ).getOrElse(Map.empty)
  }
}

/** an engine */
class DomEngine(
  val dom: RDomain,
  val root: DomAst,
  val settings: DomEngineSettings,
  val pages : List[DSpec],
  val id:String = new ObjectId().toString) {

  var status = DomState.INIT
  var synchronous = false

  val maxLevels = 25

  // setup the context for this eval
  implicit val ctx = new DomEngECtx(settings).withEngine(this).withDomain(dom).withSpecs(pages)

  val rules = dom.moreElements.collect {
    case e:ERule => e
  }

  val flows = dom.moreElements.collect {
    case e:EFlow => e
  }

  def spawn (nodes:List[DomAst]) = {
    val newRoot = DomAst("root", AstKinds.ROOT).withDetails("(spawned)")
    newRoot.children appendAll nodes
    val engine = DieselAppContext.mkEngine(dom, newRoot, settings, pages)
    engine.ctx._hostname = ctx._hostname
    engine
  }

  /** process a list of continuations */
  def later (m:List[DEMsg]) = {
    if(synchronous) {
      m.map(processDEMsg)
    } else
      m.map(m=>DieselAppContext.router.map(_ ! m))
  }

  def collectValues[T] (f: PartialFunction[Any, T]) : List[T] =
    root.collect {
      case v if(f.isDefinedAt(v.value)) => f(v.value)
    }

  def findParent(node: DomAst): Option[DomAst] =
    root.collect {
      case a if a.children.exists(_.id == node.id) => a
    }.headOption

  // completed a node - udpate stat
  def done(a: DomAst, level:Int=1): List[DEMsg] = {
    a.status = DomState.DONE

    clog << "DomEng"+id+("  " * level)+" done " + a.value

    // parent status update

    val x =
      checkState(findParent(a).getOrElse(root)) :::
      depys.toList.filter(_.prereq.id == a.id).flatMap(d=>
        root.find(d.depy.id).toList
      ).filter { n =>
        val prereq = depys.filter(x => x.depy.id == n.id && DomState.inProgress(x.prereq.status))
        prereq.isEmpty && n.status == DEPENDENT
      }.map { n =>
        n.status = LATER
        DEReq(id, n, true, level + 1)
      }
    x
  }

  /** dependency between two nodes */
  case class DADepy (prereq:DomAst, depy:DomAst)

  val depys = ListBuffer[DADepy]()

  /** create / add some dependencies, so d waits for p */
  def crdep(p:List[DomAst], d:List[DomAst]) = {
    d.map{d=>
      // d will wait
      if(p.nonEmpty) d.status = DEPENDENT
      p.map(p=>depys.append(DADepy(p,d)))
    }
  }

  // find the next execution list
  // a is already assumed to have been stitched in the main tree
  def findFront(a: DomAst, results:List[DomAst]): List[DomAst] = {
    // any flows to shape it?
    var res = results

    // returns the front of that branch and builds side effecting depys from those to the rest
    def rec(e:FlowExpr) : List[DomAst] = e match {
      case SeqExpr(op, l) if op == "+" => {
        val res = rec(l.head)
        l.drop(1).foldLeft(res)((a,b)=> {
          val bb = rec(b)
          crdep(a,bb)
          bb
        })
        res
      }
      case SeqExpr(op, l) if op == "|" => {
        l.flatMap(rec).toList
      }
      case MsgExpr(ea) => a.children.collect {
        case n:DomAst if n.value.isInstanceOf[EMsg] && n.value.asInstanceOf[EMsg].entity+"."+n.value.asInstanceOf[EMsg].met == ea => n
      }.toList
      case BFlowExpr(ex) => rec(ex)
    }

    if(a.value.isInstanceOf[EMsg])
      flows.filter(_.e.test(a.value.asInstanceOf[EMsg])).map { f =>
        res = rec(f.ex)
    }

    // add explicit depys
    results.filter(_.prereq.nonEmpty).map{res=>
      crdep(res.prereq.flatMap(root.find), List(res))
    }

    res.filter(_.status != DEPENDENT)
  }

  /** a decomp req - starts processing a message
    *
    * @param a the node to decompose/process
    * @param recurse
    * @param level
    */
  def req(a: DomAst, recurse: Boolean = true, level: Int): Unit = {
    a.status = DomState.STARTED

    clog << "DomEng"+id+("  " * level)+" expand " + a.value
    var msgs = expand(a, recurse, level + 1)

    if(msgs.isEmpty)
      msgs = msgs ::: done(a, level)
    checkState()
    later(msgs)
  }

  /** process a reply with results
    *
    * @param a the node that got this reply
    * @param recurse
    * @param level
    * @param results
    * @return
    */
  def rep(a: DomAst, recurse: Boolean = true, level: Int, results:List[DomAst]): List[DEMsg] = {
    var msgs : List[DEMsg] = Nil // continuations from this cycle

    a.children appendAll results
    if (recurse)
      msgs = msgs ::: findFront(a, results).map { n =>
//      req(n, recurse, level + 1)
      n.status = LATER
      DEReq(id, n, recurse, level+1)
    }
    msgs
  }

  // check the state at end of step - anything still running?
  private def checkState (node:DomAst = root) = {
    val res = node.collect {
      case a if a.id != node.id && DomState.inProgress(a.status) => a
    }

    var result : List[DEMsg] = Nil

    if(res.size > 0) {
      node.status = STARTED
    } else {
      if(node.status != DONE)
        // call only when transitioning to DONE
        result = done(node) //node.status = DONE
    }

    // when all done, the entire engine is done

    if(root.status == DONE && status != DONE) {
      status = DONE
      clog << "DomEng"+id+" finish"
      finishP.success(this)
      DieselAppContext.refMap.get(id).map(_ ! DEStop) // stop the actor and remove engine
    }

    result
  }

  def processTests = {
    Future {
      root.children.filter(_.kind == "test").foreach(expand(_, true, 1))
      this
    }
  }

  // waitingo for flow to complete
  val finishP = Promise[DomEngine]()
  val finishF = finishP.future

  def process = {
//    Future {
//      root.children.foreach(expand(_, true, 1))
//      this
//    }
    clog << "DomEng"+id+" process"
    root.status = STARTED

    // async start
//    val msgs = root.children.toList.map{x=>
    val msgs = findFront(root, root.children.toList).toList.map{x=>
      x.status = LATER
      DEReq(id, x, true, 0)
    }

    if(msgs.isEmpty)
      done(root)
    later(msgs)

    finishF
  }

  /** transform one element / one step
    *
    * 1. you can collect children of the current node, like info nodes etc
    * 2. return continuations, including processing through the children
    *
    * @param a node to decompose
    * @param recurse should recurse
    * @param level
    * @return continuations
    */
  def expand(a: DomAst, recurse: Boolean = true, level: Int): List[DEMsg] = {
    var newNodes : List[DomAst] = Nil // nodes generated this call collect here
    var msgs : List[DEMsg] = Nil // continuations from this cycle

    /** reused */
    def runRule (in:EMsg, r:ERule) = {
      var result : List[DomAst] = Nil

      // generate each gen/map
      r.i.map { ri =>
        // find the spec of the generated message, to ref
        val spec = dom.moreElements.collect {
          case x: EMsg if x.entity == ri.cls && x.met == ri.met => x
        }.headOption

        var newMsgs = ri.apply(in, spec, r.pos).collect {
          // hack: if only values then collect resulting values and dump message
          // only for size 1 because otherwise they may be in sequence and can't be evaluated now
          case x: EMsg if x.entity == "" && x.met == "" && r.i.size == 1 => {
            a.children appendAll x.attrs.map(x => DomAst(EVal(x), AstKinds.GENERATED).withSpec(r))
            ctx putAll x.attrs
            None
          }
          case x: EMsg if x.entity == "" && x.met == "" && r.i.size > 1 => {
            // can't execute now, but later
//            a.children appendAll x.attrs.map(x => DomAst(EVal(x), AstKinds.GENERATED).withSpec(r))
//            ctx putAll x.attrs
            //            None
            Some(DomAst(ENext(x, "=>"), AstKinds.GENERATED).withSpec(r))
          }
          // else collect message
          case x: EMsg => {
            Some(DomAst(x, AstKinds.GENERATED).withSpec(r))
          }
          case x: ENext => {
            Some(DomAst(x, AstKinds.GENERATED).withSpec(r))
          }
        }.filter(_.isDefined)

        // if multiple results from a single map, default to sequence, i.e. make them dependent
//        newMsgs.drop(1).foldLeft(newMsgs.headOption.flatMap(identity))((a,b)=> {
//          Some(b.get.withPrereq(List(a.get.id)))
//        })

        result = result ::: newMsgs.flatMap(_.toList)
      }
      // if multiple results from a single map, default to sequence, i.e. make them dependent
      if(result.size > 1) result.drop(1).foldLeft(result.head)((a,b)=> {
        b.withPrereq(List(a.id))
      })
      else result

      result
    }

    if (level >= maxLevels) {
      a.children append DomAst(TestResult("fail: Max-Level!", "You have a recursive rule generating this branch..."), "error")
    } else a.value match {

      case next@ENext(m, "==>", cond) => {
        // start new engine/process
        // todo should find settings for target service  ?
        val eng = spawn(List(DomAst(m)))
        eng.process // start it up in the background
        a.children append DomAst(EInfo(s"""Spawn engine <a href="/diesel/engine/view/${eng.id}">${eng.id}</a>"""))//.withPos((m.get.pos)))
        // no need to return anything - children have been decomposed
      }

      case n1@ENext(m, arr, cond) => {
        if(n1.test())
          msgs = rep(a, recurse, level, List(DomAst(m, AstKinds.GENERATED)))
      }

      case x: EMsg if x.entity == "" && x.met == "" => {
        a.children appendAll x.attrs.map(x => DomAst(EVal(x), AstKinds.GENERATED).withSpec(x))
        ctx putAll x.attrs
      }

      case in: EMsg => {

        // if the message attrs were expressions, calculate their values
        val n: EMsg = in.copy(
          attrs = in.attrs.map {p=>
            p.copy(
              // only if not already calculated =likely in a different context=
              dflt = if(p.dflt.nonEmpty) p.dflt else p.expr.map(_.apply("").toString).getOrElse(p.dflt),
              ttype = if(p.ttype.nonEmpty) p.ttype else p.expr match {
                case Some(CExpr(_, "String")) => "String"
                case Some(CExpr(_, "Number")) => "Number"
                case _ => p.ttype
              }
            )
          }
        )

        // 1. look for mocks
        var mocked = false

        if (settings.mockMode) {
          (root.collect {
            // mocks from story AST
            case d@DomAst(m: EMock, _, _, _) if m.rule.e.test(n) && a.children.isEmpty => m
          } ::: dom.moreElements.toList).collect {
            // todo perf optimize moreelements.toList above
            // plus mocks from spec dom
            case m: EMock if m.rule.e.test(n) && a.children.isEmpty => {
              mocked = true

              // run the mock
              newNodes = newNodes ::: runRule(n, m.rule)
            }
          }
        }

        // 2. rules

        var ruled = false
        // no mocks fit, so let's find rules
        // I run rules even if mocks fit - mocking mocks only going out, not decomposing
        if (true || !mocked) {
          rules.filter(_.e.test(n)).map { r =>
            // each matching rule
            ruled = true

            newNodes = newNodes ::: runRule(n, r)
          }
        }

        // 3. executors

        // no mocks, let's try executing it
        // I run snaks even if rules fit - but not if mocked
        if (!mocked) {
          Executors.all.filter { x =>
            (!settings.mockMode || x.isMock) && x.test(n)
          }.map { r =>
            mocked = true

            val news = try {
              val xx = r.apply(n, None)(new StaticECtx(n.attrs, Some(ctx), Some(a)))
                xx.collect{
//              r.apply(n, None)(new StaticECtx(n.attrs, Some(ctx), Some(a))).collect{
                // collect resulting values in the context as well
                case v@EVal(p) => {
                  ctx.put(p)
                  v
                }
                case e@_ => e
              }.map(x =>
                DomAst(x,
                  (if (x.isInstanceOf[DieselTrace]) AstKinds.SUBTRACE
                  else AstKinds.GENERATED)
                ).withSpec(r)
              )
            } catch {
              case e: Throwable =>
                razie.Log.alarmThis("wtf", e)
                List(DomAst(EError("Exception:" + e.getMessage), AstKinds.GENERATED))
            }

            newNodes = newNodes ::: news
          }
        }

        // 4. sketch

        // last ditch attempt, in sketch mode: if no mocks or rules, run the expects
        if (!mocked && settings.sketchMode) {
          // sketch messages
          (collectValues {
            case x: ExpectM if x.when.exists(_.test(n)) => x
          }).map { e =>
            mocked = true

            val spec = dom.moreElements.collect {
              case x: EMsg if x.entity == e.m.cls && x.met == e.m.met => x
            }.headOption

            val news = e.sketch(None).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
            newNodes = newNodes ::: news
          }

          // sketch values
          (collectValues {
            case x: ExpectV if x.when.exists(_.test(n)) => x
          }).map { e =>
            val news = e.sketch(None).map(x => EVal(x)).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
            mocked = true

            news.foreach { n =>
              a.children append n
            }
          }
        }

        msgs = rep(a, recurse, level, newNodes) ::: msgs
        // EMsg
      }

      case n: EVal if !AstKinds.isGenerated(a.kind) => {
        // $val defined in scope
        a.children append DomAst(EVal(n.p).withPos(n.pos), AstKinds.MOCKED)
        ctx.put(n.p)
      }

      case e: ExpectM => {
        val cole = new MatchCollector()
        val targets = if (e.when.isDefined) {
          // find generated messages that should be tested
          root.collect {
            case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(n)) => d
          }
        } else List(root)

        if (targets.size > 0) {
          // todo look at all possible targets - will need to create a matchCollector per etc
          targets.head.collect {
            case d@DomAst(n: EMsg, k, _, _) if AstKinds.isGenerated(k) =>
              cole.newMatch(d)
              if (e.m.test(n, Some(cole)))
                a.children append DomAst(TestResult("ok"), "test").withSpec(e)
          }

          cole.done

          // if it matched some Msg then highlight it there
          if (cole.highestMatching.exists(c =>
            c.score >= 2 &&
              c.diffs.values.nonEmpty &&
              c.x.isInstanceOf[DomAst] &&
              c.x.asInstanceOf[DomAst].value.isInstanceOf[EMsg]
          )) {
            val c = cole.highestMatching.get
            val d = c.x.asInstanceOf[DomAst]
            val m = d.value.asInstanceOf[EMsg]
            val s = c.diffs.values.map(_._2).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")
            d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s
          }

          // did some rules succeed?
          if (a.children.isEmpty) {
            // oops - add test failure
            a.children append DomAst(
              TestResult(
                "fail",
                label("found", "warning") + " " +
                  cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")).mkString
              ),
              AstKinds.TEST
            ).withSpec(e)
          }
        }
      }

      case e: ExpectV => {
        val cole = new MatchCollector()

        // identify sub-trees that it applies to
        val subtrees = if (e.when.isDefined) {
          // find generated messages that should be tested
          root.collect {
            case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(n)) => d
          }
        } else List(root)

        if (subtrees.size > 0) {
          // todo look at all possible targets - will need to create a matchCollector per etc
          val vals = subtrees.flatMap(_.collect {
            case d@DomAst(n: EVal, k, _, _) if AstKinds.isGenerated(k) => d
          })

          // test each generated value
          if (e.test(vals.map(_.value.asInstanceOf[EVal].p), Some(cole), vals))
            a.children append DomAst(TestResult("ok"), AstKinds.TEST).withSpec(e)

          cole.done

          // did some rules succeed?
          if (a.children.isEmpty) {
            // oops - add test failure
            a.children append DomAst(
              TestResult(
                "fail",
                  cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")).mkString
              ),
              AstKinds.TEST
            ).withSpec(e)
          }

          // if it matched some name then highlight it there
          // any match means a name matched ?
          if (cole.highestMatching.exists(c =>
            c.score >= 0 &&
              c.diffs.values.nonEmpty &&
              c.x.isInstanceOf[DomAst] &&
              c.x.asInstanceOf[DomAst].value.isInstanceOf[EVal]
          )) {
            val c = cole.highestMatching.get
            val d = c.x.asInstanceOf[DomAst]
            val m = d.value.asInstanceOf[EVal]
            val s = c.diffs.values.map(_._2).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")
            d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s
          }

          // did some rules succeed?
          if (a.children.isEmpty) {
            // oops - add test failure
            a.children append DomAst(
              TestResult(
                "fail",
                label("found", "warning") + " " +
                  cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")).mkString
              ),
              AstKinds.TEST
            ).withSpec(e)
          }
        }
      }

      case s@_ => {
        clog << "NOT KNOWN: " + s.toString
      }
    }
  msgs
  }

  /** main processing of next - called from actor in async and in thread when sync */
  def processDEMsg (m:DEMsg) = {
    m match {
      case req@DEReq(eid, a, r, l) => {
        this.req(a, r, l)
      }

      case rep@DERep(eid, a, r, l, results) => {
        this.rep(a, r, l, results)
      }
    }
  }
}


/** ====================== Actor infrastructure =============== */

/** base class for engine internal message */
class DEMsg ()

/** a message - request to decompose
  *
  * The engine will decompose the given node a and send to self a DERep
  */
case class DEReq (engineId:String, a:DomAst, recurse:Boolean, level:Int) extends DEMsg

/** a message - reply to decompose
  *
  * The engine will stich the AST together and continue
  */
case class DERep (engineId:String, a:DomAst, recurse:Boolean, level:Int, results:List[DomAst]) extends DEMsg

/** initialize and stop the engine */
case object DEInit extends DEMsg
case object DEStop extends DEMsg

case class DEStartTimer (engineId:String, d:Int, results:List[DomAst]) extends DEMsg
case class DETimer      (engineId:String, results:List[DomAst]) extends DEMsg

/**
  * engine router - routes updates to proper engine actor
  */
class DomEngineRouter () extends Actor {

  def receive = {
    case DEInit => { }

    case req @ DEReq(id, a, r, l) => {
      DieselAppContext.refMap.get(id).map(_ ! req).getOrElse(
        clog << "DomEngine Router DROP message "+req
      )
    }

    case rep @ DERep(id, a, r, l, results) => {
      DieselAppContext.refMap.get(id).map(_ ! rep).getOrElse(
        clog << "DomEngine Router DROP message "+rep
      )
    }

    case DEStop => {
//      DieselAppContext.refMap.values.map(_ ! DEStop)
    }
  }
}

/** exec context for engine - each engine has its own.
  *
  * it will serialize status udpates and execution
  */
class DomEngineActor (eng:DomEngine) extends Actor {

  def receive = {
    case DEInit => {
      //save refs for active engines
    }

    case req @ DEReq(eid, a, r, l) => {
      if(eng.id == eid) eng.processDEMsg(req)
      else DieselAppContext.router.map(_ ! req)
    }

    case rep @ DERep(eid, a, r, l, results) => {
      if(eng.id == eid) eng.processDEMsg(rep)
      else DieselAppContext.router.map(_ ! rep)
    }

    case DEStop => {
      //remove refs for active engines
//      DieselAppContext.engMap.remove(eng.id)
      DieselAppContext.refMap.remove(eng.id)
      context stop self
    }

    case timer @ DEStartTimer(id,d,m) => {
      if(eng.id == id) {
        Akka.system.scheduler.scheduleOnce(
          Duration.create(1, TimeUnit.MINUTES),
          this.self,
          DETimer(id,m)
        )
      }
      else DieselAppContext.router.map(_ ! timer)
    }

    case timer @ DETimer(id,m) => {
      if(eng.id == id) {
      }
      else DieselAppContext.router.map(_ ! timer)
    }

  }
}


