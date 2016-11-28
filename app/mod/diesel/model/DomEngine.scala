/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import akka.actor.{ActorRef, Actor, Props}
import mod.diesel.model.parser.{BFlowExpr, MsgExpr, FlowExpr, SeqExpr}
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

object AstKinds {
  final val ROOT = "root"
  final val RECEIVED = "received"
  final val SAMPLED = "sampled"
  final val GENERATED = "generated"
  final val SUBTRACE = "subtrace"
  final val SKETCHED = "sketched"
  final val MOCKED = "mocked"
  final val TEST = "test"

  def isGenerated (k:String) = GENERATED==k || SKETCHED==k || MOCKED==k
}

object DomState {
  final val INIT="final.init" // new node
  final val STARTED="exec.started" // is executing now
  final val DONE="final.done" // done
  final val LATER="exec.later" // queued up somewhere for later
  final val DEPY="exec.depy" // waiting on another task

  def inProgress(s:String) = s startsWith "exec."
}

object DieselAppContext {
  private var appCtx : Option[DieselAppContext] = None
  var engMap = new mutable.HashMap[String,DomEngine]()
  var refMap = new mutable.HashMap[String,ActorRef]()
  var router : Option[ActorRef] = None
  var asyncDispatch : Option[ActorRef] = None

  def init (node:String="", app:String="") = {
    if(appCtx.isEmpty) appCtx = Some(new DieselAppContext(
      if(node.length > 0) node else Services.config.node,
      if(app.length > 0) app else "default"
    ))

    val p = Props(new DomEngineRouter())
    val a = Akka.system.actorOf(p)
    router = Some(a)
    a ! DEInit

    val pa = Props(new DEAsyncDispatcher())
    val aa = Akka.system.actorOf(pa)
    asyncDispatch = Some(aa)
    aa ! DEInit

    appCtx.get
  }

  def mkEngine(dom: RDomain, root: DomAst, settings: DomEngineSettings, pages : List[DSpec]) = {
    val eng = ctx.mkEngine(dom, root, settings, pages)
    val p = Props(new DomEngineActor(eng))
    val a = Akka.system.actorOf(p, name = eng.id)
    DieselAppContext.engMap.put(eng.id, eng)
    DieselAppContext.refMap.put(eng.id, a)
    a ! DEInit
    eng
  }

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
  kind: String,
  children: ListBuffer[DomAst] = new ListBuffer[DomAst](),
  id : String = new ObjectId().toString
  ) extends CanHtml {

  private var istatus:String = DomState.INIT
  def status:String = istatus
  def status_=(s:String) = istatus = s

  var moreDetails = " "
  var specs: List[Any] = Nil

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
  }.lines.map(("  " * level) + _).mkString("\n") + moreDetails + "\n" + children.map(_.tos(level + 1, html)).mkString

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
      "children" -> children.map(_.toj).toList
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

    new DomEngineSettings(
      mockMode = fqParm("mockMode", "true").toBoolean,
      blenderMode = fqParm("blenderMode", "true").toBoolean,
      draftMode = fqParm("draftMode", "true").toBoolean,
      sketchMode = fqParm("sketchMode", "false").toBoolean,
      execMode = fqParm("execMode", "sync"),
      parentNodeId = fqhParm("dieselNodeId")
    )
  }
}

class DomEngineSettings(
  var mockMode    : Boolean = false,
  var blenderMode : Boolean = true,
  var draftMode   : Boolean = true,
  var sketchMode  : Boolean = true,
  var execMode    : String = "sync",
  var parentNodeId: Option[String] = None // when ran for a separate request
  ) {
  val node = Services.config.node
}

/** an engine */
class DomEngine(
  val dom: RDomain,
  val root: DomAst,
  val settings: DomEngineSettings,
  val pages : List[DSpec],
  val id:String = new ObjectId().toString) {

  var status = DomState.INIT

  val maxLevels = 15

  def later (m:List[DEMsg]) = {
    m.map(m=>DieselAppContext.router.map(_ ! m))
  }

  // setup the context for this eval
  implicit val ctx = new DomEngECtx().withDomain(dom).withSpecs(pages)

  val rules = dom.moreElements.collect {
    case e:ERule => e
  }

  val flows = dom.moreElements.collect {
    case e:EFlow => e
  }

  def collectValues[T] (f: PartialFunction[Any, T]) : List[T] =
    root.collect {
      case v if(f.isDefinedAt(v.value)) => f(v.value)
    }


  def parent(node: DomAst): Option[DomAst] =
    root.collect {
      case a if a.children.exists(_.id == node.id) => a
    }.headOption

  // completed a node - udpate stat
  def done(a: DomAst, level:Int=1): List[DEMsg] = {
    a.status = DomState.DONE

    clog << "DomEng"+id+("  " * level)+" done " + a.value

    // parent status update

    val x =
      checkState(parent(a).getOrElse(root)) :::
      depys.toList.filter(_.prereq.id == a.id).flatMap(d=>root.find(d.depy.id).toList).filter{n=>
        val prereq = depys.filter(x => x.depy.id == n.id && DomState.inProgress(x.prereq.status))
        prereq.isEmpty && n.status == DEPY
      }.map { n =>
        n.status = LATER
        DEReq(id, n, true, level + 1)
      }
    x
  }

  case class DADepy (prereq:DomAst, depy:DomAst)

  val depys = ListBuffer[DADepy]()

  // find the next execution list
  def findFront(a: DomAst, results:List[DomAst]): List[DomAst] = {
    // any flows to shape it?
    var res = results

    def crdep(p:List[DomAst], d:List[DomAst]) = {
      d.map{d=>
        // d will wait
        if(p.nonEmpty) d.status = DEPY
        p.map(p=>depys.append(DADepy(p,d)))
      }
    }

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

    res
  }

  // a decomp req
  def req(a: DomAst, recurse: Boolean = true, level: Int): Unit = {
    a.status = DomState.STARTED

    clog << "DomEng"+id+("  " * level)+" expand " + a.value
    var msgs = expand(a, recurse, level + 1)

    if(msgs.isEmpty)
      msgs = msgs ::: done(a, level)
    checkState()
    later(msgs)
  }

  // process a reply with results
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
    val msgs = root.children.toList.map{x=>
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
    * @param a node to decompose
    * @param recurse should recurse
    * @param level
    * @return continuations
    */
  def expand(a: DomAst, recurse: Boolean = true, level: Int): List[DEMsg] = {
    var newNodes : List[DomAst] = Nil // nodes generated this call collect here
    var msgs : List[DEMsg] = Nil // continuations from this cycle

    if (level >= maxLevels) {
      a.children append DomAst(TestResult("fail: Max-Level!", "You have a recursive rule generating this branch..."), "error")
    } else a.value match {

      case n: EMsg => {
        // look for mocks
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
              val values = m.rule.i.apply(n, None, m.pos).collect {
                // mocks onl generate values, not messages
                // collect resulting values and dump message
                case x: EMsg => {
                  a.children appendAll x.attrs.map(x => DomAst(EVal(x).withPos(m.pos), AstKinds.MOCKED).withSpec(m))
                  ctx putAll x.attrs
                }
              }
            }
          }
        }

        var ruled = false
        // no mocks fit, so let's find rules
        // I run rules even if mocks fit - mocking mocks only going out, not decomposing
        if (true || !mocked) {
          val news = rules.filter(_.e.test(n)).map { r =>
            ruled = true

            // find the spec of the generated message, to ref
            val spec = dom.moreElements.collect {
              case x: EMsg if x.entity == r.i.cls && x.met == r.i.met => x
            }.headOption

            val news = r.i.apply(n, spec, r.pos).collect {
              // if values then collect resulting values and dump message
              case x: EMsg if x.entity == "" && x.met == "" => {
                a.children appendAll x.attrs.map(x => DomAst(EVal(x), AstKinds.GENERATED).withSpec(r))
                ctx putAll x.attrs
                None
              }
              // else collect message
              case x: EMsg => {
                Some(DomAst(x, AstKinds.GENERATED).withSpec(r))
              }
            }

            newNodes = newNodes ::: news.flatMap(_.toList)
          }
        }

        // no mocks, let's try executing it
        // I run snaks even if rules fit - but not if mocked
        if (!mocked) {
          Executors.all.filter { x =>
            (!settings.mockMode || x.isMock) && x.test(n)
          }.map { r =>
            mocked = true

            val news = try {
              r.apply(n, None)(new StaticECtx(n.attrs, Some(ctx), Some(a))).map(x =>
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

        //          /* NEED expects to have a match in and a match out...?

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
            //            case x:ExpectV => x // expectV have no criteria
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
            //            if(! settings.sketchMode) {
            // oops - add test failure
            a.children append DomAst(
              TestResult(
                "fail",
                label("found", "danger") + " " + cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")).mkString),
              "test"
            ).withSpec(e)
            //            } else {
            //               or... fake it, sketch it
            //              todo should i add the test as a warning?
            //              a.children appendAll e.sketch(None).map(x => DomAst(x, AstKinds.GENERATED).withSpec(e))
            //            }
          }
        }
      }

      case e: ExpectV => {
        val cole = new MatchCollector()

        // identify sub-trees that it applies to
        val targets = if (e.when.isDefined) {
          // find generated messages that should be tested
          root.collect {
            case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(n)) => d
          }
        } else List(root)

        if (targets.size > 0) {
          // todo look at all possible targets - will need to create a matchCollector per etc
          val vals = targets.flatMap(_.collect {
            case d@DomAst(n: EVal, k, _, _) if AstKinds.isGenerated(k) => n.p
          })

          // test each generated value
          if (e.test(vals, Some(cole)))
            a.children append DomAst(TestResult("ok"), AstKinds.TEST).withSpec(e)

          // did some rules succeed?
          if (a.children.isEmpty) {
            // oops - add test failure
            //          if(! settings.sketchMode) {
            a.children append DomAst(TestResult("fail"), AstKinds.TEST).withSpec(e)
            //          } else {
            // or... fake it, sketch it
            //todo should i add the test as a warning?
            //            a.children appendAll e.sketch(None).map(x=>EVal(x)).map(x => DomAst(x, "generated.sketched").withSpec(e))
            //          }
          }
        }
      }

      case s@_ => {
        clog << "NOT KNOWN: " + s.toString
      }
    }
  msgs
  }
}


/** ====================== Actor infrastructure =============== */

class DEMsg ()

/** a message - request to decompose */
case class DEReq (engineId:String, a:DomAst, recurse:Boolean, level:Int) extends DEMsg

/** a message - reply to decompose */
case class DERep (engineId:String, a:DomAst, recurse:Boolean, level:Int, results:List[DomAst]) extends DEMsg

case object DEInit extends DEMsg
case object DEStop extends DEMsg

/** engine router - routes updates to proper engine
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

    case req @ DEReq(id, a, r, l) => {
      if(eng.id == id) eng.req(a, r, l)
      else DieselAppContext.router.map(_ ! req)
    }

    case rep @ DERep(id, a, r, l, results) => {
      if(eng.id == id) eng.rep(a, r, l, results)
      else DieselAppContext.router.map(_ ! rep)
    }

    case DEStop => {
      //remove refs for active engines
      DieselAppContext.engMap.remove(eng.id)
      DieselAppContext.refMap.remove(eng.id)
      context stop self
    }
  }
}

/** aka thread pool
  */
class DEAsyncDispatcher () extends Actor {
  var workers : List[ActorRef] = Nil
  var curWorker = 0

  def route(a:Any) = {
    workers(curWorker) ! a
    curWorker = (curWorker+1) % workers.size
  }

  def receive = {
    case DEInit => {
      for(i <- (0 until 10).toList) {
        val pa = Props(new DEAsyncActor())
        val aa = Akka.system.actorOf(pa)
        workers = aa :: workers
        aa ! DEInit
      }
    }

    case req @ DEReq(id, a, r, l) => route(req)

    case rep @ DERep(id, a, r, l, results) => route(rep)

    case DEStop => {
      workers.map(_ ! DEStop)
    }
  }
}

/** exec context for engine - each engine has its own.
  *
  * it will serialize status udpates and execution
  */
class DEAsyncActor () extends Actor {

  def receive = {
    case DEInit => { }

    case req @ DEReq(id, a, r, l) => {
//      DieselAppContext.refMap.get(id).map(_ ! req)
    }

    case DEStop => { }
  }
}


