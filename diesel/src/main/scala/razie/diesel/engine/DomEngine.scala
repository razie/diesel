/*  ____    __    ____  ____  ____  ___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.Logging
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{DieselAssets, RDomain, WTypes}
import razie.diesel.engine.nodes._
import razie.diesel.expr._
import razie.diesel.model.DieselMsg
import razie.diesel.model.DieselMsg.ENGINE.{
  DIESEL_MSG_ACTION, DIESEL_MSG_ATTRS, DIESEL_MSG_EA, DIESEL_MSG_ENTITY,
  DIESEL_SUMMARY
}
import razie.diesel.utils.DomCollector
import razie.tconf.DSpec
import razie.wiki.admin.GlobalData
import razie.wiki.model.WID
import scala.Option.option2Iterable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.Try

trait InfoNode // just a marker for useless info nodes

trait EGenerated // just a marker for "action" generated nodes (EMsg, EMsgPas, ENext etc)

/** the engine: one flow = one engine = one actor
  *
  * this class is a generic engine, managing the execution of the nodes, starting from the root
  *
  * specific instances, like DomEngineV1 will take care of the actual execution of nodes. The two methods to
  * implement are:
  * - expand
  *
  *
  * The DomEngineExec is the actual implementation
  *
  * @param correlationId is parentEngineID.parentSuspendID - if dot is missing, this was a fire/forget
  * @param initialMsg    - the initial message that kicked off this engine - used to extract final value
  *
  */
abstract class DomEngine(
  val dom: RDomain,
  val root: DomAst,
  val settings: DomEngineSettings,
  val pages: List[DSpec],
  val description: String,
  val correlationId: Option[String] = None,
  val id: String = new ObjectId().toString,
  val createdDtm: DateTime = DateTime.now
) extends Logging with DomEngineState with DomRoot with DomEngineExpander {

  /** info node replacing pruned nodes */
  class Pruned(keep: Int, var removed: Int) {
    override def toString = s"Keeping only $keep nodes, removed $removed..."
  }

  def shouldPrune(a: DomAst, parent: Option[DomAst]) =
    a.value.isInstanceOf[EMsg] &&
        a.value.asInstanceOf[EMsg].ea.contains("diesel.stream.onData") || // todo why doesn't KeepSiblings work here??
        parent.isDefined &&
            parent.get.value.isInstanceOf[EMsg] &&
            parent.get.value.asInstanceOf[EMsg].ea.contains("ctx.foreach") &&
            parent.get.childrenCol.size > 20 &&
            !(a.value.isInstanceOf[EInfoWrapper] &&
                a.value.asInstanceOf[EInfoWrapper].a.isInstanceOf[Pruned])//&&
//            a.value.isInstanceOf[KeepOnlySomeSiblings] // todo why doesn't KeepSiblings work here??

  /** prune children of parent and keep only some */
  def prune(parent: DomAst, k: Int, level: Int) = {

    val p = parent
    val rem = new ListBuffer[DomAst]()

    if (p.childrenCol.size > k + 1 && p.childrenCol.exists(_.status == DomState.DONE)) {

      var prunes = p.childrenCol.zipWithIndex.filter(
        n => n._1.status == DomState.DONE && shouldPrune(n._1, Some(parent)))

      while (prunes.size > k) {
        val toRemove = prunes.head

        val removed = p.childrenCol(toRemove._2)
        rem.append(removed)
        p.childrenCol.remove(toRemove._2)

        log("DomEng " + id + ("  " * level) + s" remove kid #${toRemove._2} from " + parent.value)

        val prunedNode = p.childrenCol.find(
          x => x.value.isInstanceOf[EInfoWrapper] && x.value.asInstanceOf[EInfoWrapper].a.isInstanceOf[Pruned])

        val prunedInfo = prunedNode
            .map(_.value.asInstanceOf[EInfoWrapper].a.asInstanceOf[Pruned])

        var keep = false

        // save summaries, if any...
        val summaries = removed.collect {
          case ac: DomAst
            if ac.value != null &&
                ac.value.isInstanceOf[EMsg] &&
                ac.value.asInstanceOf[EMsg].ea == DIESEL_SUMMARY => {
            keep = keep || ac.value.asInstanceOf[EMsg].attrs.exists(_.currentStringValue == "true")
            (DomAst(ac.value, ac.kind).withStatus(ac.status))
          }
        }

        // todo should record the move in the event history?
        // replace only if there's some details or

        if (removed.childrenCol.nonEmpty && prunedInfo.isEmpty) {

          // impersonate the replaced ID's?
          val replacement = new DomAst(EInfoWrapper(new Pruned(k, 1)), AstKinds.DEBUG,
            new ListBuffer[DomAst](),
            removed.id)
              .withStatus(DomState.DONE)

          p.childrenCol.insert(toRemove._2, replacement)
          replacement.appendAll(summaries)
          // if a summary says "keep" then we keep it
          if (keep) replacement.append(removed.resetParent(null))
        } else {
          prunedInfo.foreach(_.removed += 1)
          prunedNode.map(_.appendAll(summaries))
          // if a summary says "keep" then we keep it
          if (keep) prunedNode.map(_.append(removed.resetParent(null)))
        }


        // next?
        prunes = prunes.drop(1)

        // the nodes were DONE so we can remove depys, both from and to, so they don't grow forever

        toRemove._1.collect {
          case a => {
            val i = depys.indexWhere(_.prereq.id == a.id)
            if (i >= 0) depys.remove(i)
            val j = depys.indexWhere(_.depy.id == a.id)
            if (j >= 0) depys.remove(j)
          }
        }
      }

      var more = false // keep removing

      // either way, just keep no more than 100
//      while (p.childrenCol.size > 30 && more) {
//        more = false
//        val toRemove = p.childrenCol.indexWhere(
//          x => x.value.isInstanceOf[EInfo] && x.value.asInstanceOf[EInfo].msg.startsWith("Keeping only"))
//
//        if (toRemove >= 0) {
//          more = true
//          p.childrenCol.remove(toRemove)
//          log("DomEng " + id + ("  " * level) + s" remove kid #${toRemove} from " + parent.value)
//        }
//      }


      // todo if not already
//      p.childrenCol.insert(0,
//        new DomAst(EInfo(s"Keeping only $k nodes below..."), AstKinds.DEBUG)
//            .withStatus(DomState.DONE)
//      )

      // todo optimize - remove rem and count while removing
      val shouldBeZero = rem.toList.flatMap(freeDepys)
      shouldBeZero
    }
  }

  var initialMsg: Option[EMsg] = None

  def withInitialMsg(m: Option[EMsg]) = {
    this.initialMsg = m
    this
  }

  assert(settings.realm.isDefined, "need realm defined for engine settings")

  GlobalData.dieselEnginesTotal.incrementAndGet()

  // remove parameters so it becomes more invariable, like just message name or smth
  def collectGroup = {
    val x = settings.collectGroup.getOrElse(description.replaceFirst("\\(.*", ""))
    if (x.length < 10) x + "-collectGroup" else x
  }

  def wid = WID("DieselEngine", id)

  def href = DieselAssets.mkAhref(WID("DieselEngine", this.id))

  var synchronous = false
  implicit val engine: DomEngineState = this

  // setup the context for this eval
  implicit var ctx: ECtx = new DomEngECtx(settings)
      .withEngine(this)
      .withDomain(dom)
      .withSpecs(pages)
      .withCredentials(settings.userId)
      .withHostname(settings.node)

  // assign the root ctx, to have a backstop
  root.withCtx(ctx)

  /** myself */
  def href(format: String = "") = s"/diesel/engine/view/$id?format=$format"

  val rules = dom.moreElements.collect {
    case e: ERule => e
  }

  val flows = dom.moreElements.collect {
    case e: EFlow => e
  }

  // collecting warnings
  protected var warnings: Option[DomAst] = None

  /** collect warnings */
  def warning(warning: InfoNode) = {
    warnings.foreach(w =>
      evAppChildren(w, List(DomAst(warning, AstKinds.ERROR).withStatus(DomState.SKIPPED))))
  }

  //==========================

  /** make a static context for children */
  protected def mkMsgContext(in: Option[EMsg], attrs: List[P], parentCtx: ECtx, a: DomAst) = {
    new StaticECtx(
      in.map(in => P.fromTypedValue(DIESEL_MSG_ENTITY, in.entity)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_ACTION, in.met)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_EA, in.ea)).toList :::
          // todo avoid this every time - lazy parms ?
          List(P.fromTypedValue(DIESEL_MSG_ATTRS,
            attrs.map(p => (p.name, p.calculatedTypedValue(parentCtx).value)).toMap)) :::
          attrs,
      Some(parentCtx),
      Some(a))
  }

  /** local context for a rule - does not propagate values */
  protected def mkLocalMsgContext(in: Option[EMsg], attrs: List[P], parentCtx: ECtx, a: DomAst) = {
    new LocalECtx(
      in.map(in => P.fromTypedValue(DIESEL_MSG_ENTITY, in.entity)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_ACTION, in.met)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_EA, in.ea)).toList :::
          // todo avoid this every time - lazy parms ?
          List(P.fromTypedValue(DIESEL_MSG_ATTRS,
            attrs.map(p => (p.name, p.calculatedTypedValue(parentCtx).value)).toMap)) :::
          attrs,
      Some(parentCtx),
      Some(a))
  }

  /** a passthrough context for PAS assignments - which always go up */
  protected def mkPassthroughMsgContext(in: Option[EMsg], attrs: List[P], parentCtx: ECtx, a: DomAst) = {
    new PassthroughECtx(
      in.map(in => P.fromTypedValue(DIESEL_MSG_ENTITY, in.entity)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_ACTION, in.met)).toList :::
          in.map(in => P.fromTypedValue(DIESEL_MSG_EA, in.ea)).toList :::
          // todo avoid this every time - lazy parms ?
          List(P.fromTypedValue(DIESEL_MSG_ATTRS,
            attrs.map(p => (p.name, p.calculatedTypedValue(parentCtx).value)).toMap)) :::
          attrs,
      Some(parentCtx),
      Some(a))
  }

  /** spawn new engine */
  protected def spawn(nodes: List[DomAst], correlationId: Option[String]) = {
    val newRoot = DomAst("root", AstKinds.ROOT).withDetails("(spawned)")
    newRoot.appendAllNoEvents(nodes)
    val engine = DieselAppContext.mkEngine(dom, newRoot, settings, pages,
      "engine:spawn " + nodes.head.value.toString.take(200), correlationId)
    engine.ctx.root._hostname = ctx.root._hostname
    engine
  }

  /** if have correlationID, notify parent... */
  protected def notifyParent(a: DomAst, parentId: String, targetId: String, level: Int) = {
    // completed - notify parent
    val newD = DomAst(new EInfo(
      "Completing - notifying parent: " + DieselAssets.mkLink(WID("DieselEngine", parentId)), ""),
      AstKinds.GENERATED)

    DieselAppContext ! DEComplete(parentId, targetId, recurse = true, level,
      ctx.getp(Diesel.PAYLOAD).map(p => DomAst(EVal(p))).toList
    )

    evAppChildren(a, newD)
    true
  }

  /** process a list of continuations */
  protected def later(m: List[DEMsg]): List[Any] = {
    if (synchronous) {
      clog << "SYNCHRONOUS engine processing..."
      m.map(processDEMsg)
    } else
      m.map(m => DieselAppContext ! m) // cause err if router not up
  }

  /** stop and discard this engine */
  def discard = {
    if (status != DomState.INIT) {
      // did something, need to stop it instead
      stopNow
    } else {
      clog << s"WF.DISCARD.$id"
      status = DomState.CANCEL
      trace("DomEng " + id + " discard")
      finishP.success(this)
      DieselAppContext.stopActor(id)
    }
  }

  /** finalize and release resources, actors etc */
  def engineDone(collect: Boolean = true) = {
    clog << s"WF.STOP.$id"
    GlobalData.dieselEnginesActive.decrementAndGet()

    if (!finishP.isCompleted) finishP.success(this)

    // remove ctx references
    try {
      root.collect {
        case x: DomAst => x.replaceCtx(null)
      }
    } catch {
      case t: Exception =>
        log("EXC when cleaning contexts: ", t)
    }

    if (collect) {
      DomCollector.collectAst("engine", settings.realm.mkString, id, settings.userId, this)
    }

    // stop owned streams
    // todo get pissed if not done?
    this.ownedStreams.foreach(stream => {
      if (!stream.streamIsDone) {
        root.append(
          new DomAst(
            EWarning(s"Stream ${stream.name} was open!! Closing, but some generator or consumer may still use it!"),
            AstKinds.ERROR
          )
        )
      }
      stream.cleanup()
    })
  }

  /** stop me now */
  def stopNow = {
    if (!DomState.isDone(status)) {
      status = DomState.CANCEL
      trace("DomEng " + id + " stopNow")
      engineDone()

      cleanResources()
    }
  }

  /** stop me now */
  def cleanResources() = {
    DieselAppContext.activeActors.get(id).foreach(_ ! DEStop) // stop the actor and remove engine
    DieselAppContext.stopActor(id)
  }

  /** completed a node - udpate stat
    *
    * @param a     node that completed
    * @param level - current level, root is 0
    * @return
    */
  def nodeDone(a: DomAst, level: Int = 1): List[DEMsg] = {
    if (DomState.inProgress(a.status) || DomState.inWaiting(a.status)) {
      a.end()
    }

    evChangeStatus(a, DomState.DONE)

    val parent = a.parent.orElse(findParent(a))

    trace("DomEng " + id + ("  " * level) + " done " + a.value)

    // parent status update

    val x = checkState(findParent(a).getOrElse(root)) ::: freeDepys(a)

    // remove siblings if needed

    val rem = new ListBuffer[DEMsg]()

    if (parent.exists(_.value.isInstanceOf[KeepOnlySomeChildren]) ||
        a.value.isInstanceOf[KeepOnlySomeSiblings] ||
        shouldPrune(a, parent)) { // todo why doesn't KeepSiblings work here??
      val p = parent.get
      val k =
        if (parent.exists(_.value.isInstanceOf[KeepOnlySomeChildren]))
          parent.get.value.asInstanceOf[KeepOnlySomeChildren].keepCount
        else if (a.value.isInstanceOf[KeepOnlySomeChildren])
          a.value.asInstanceOf[KeepOnlySomeSiblings].keepCount
        else 3//this.ctx.get("diesel.engine.keep").map(_.toInt).getOrElse(3)

      if (p.childrenCol.size > k + 1 && p.childrenCol.exists(_.status == DomState.DONE)) {
        rem.append(new DEPruneChildren(this.id, parent.get.id, k, level))
      }
    }

    x ::: rem.toList
  }

  /** free dependents, if possible */
  def freeDepys(a: DomAst): List[DEMsg] = {
    val x =
      depys.toList.filter(_.prereq.id == a.id).flatMap(d =>
        root.find(d.depy.id).toList
      ).filter { n =>
        val prereq = depys.filter(x => x.depy.id == n.id && DomState.inProgress(x.prereq.status))
        prereq.isEmpty && n.status == DomState.DEPENDENT // not started - important!
      }.map { n =>
        evChangeStatus(n, DomState.LATER)
        DEReq(id, n, recurse = true,
          findLevel(n)) // depys are not even equals - they would have their own level...?
        // had a problem here, where the level was reseting sometimes - can't depend on parent level, have to
        // re-find it
      }
    x
  }

  /** find the next *async* execution list. this makes the sync/async determination, based on
    * dependencies, node type etc
    *
    * a is already assumed to have been stitched in the main tree
    *
    * @param parent  front from here down
    * @param results - the results returned from a parent's executor
    * @return
    */
  protected def findFront(parent: DomAst, results: List[DomAst]): List[DomAst] = {
    // any flows to shape it?
    var res = results

    // seq-par: returns the front of that branch and builds side effecting depys from those to the rest
    def rec(e: FlowExpr): List[DomAst] = e match {

      // sequential, create depys
      case SeqExpr(op, l) if op == "+" => {
        val res = rec(l.head)
        l.drop(1).foldLeft(res)((a, b) => {
          val bb = rec(b)
          crdep(a, bb)
          bb
        })
        res
      }

      // parallel, start them all
      case SeqExpr(op, l) if op == "|" => {
        val x = l.flatMap(rec).toList
        x
      }

      // more seq-par
      case BFlowExpr(ex) => rec(ex)

      case MsgExpr(ea) => parent.children.collect {
        case n: DomAst if
        n.value.isInstanceOf[EMsg] && n.value.asInstanceOf[EMsg].ea == ea ||
            n.value.isInstanceOf[ENext] && n.value.asInstanceOf[ENext].msg.ea == ea
        => n
      }

    }

    // any seq/par flows apply to a ? (a is the parent that generated these messages)
    findFlows(parent).map { f =>
      res = rec(f.ex)
    }

    // add explicit depys for results/children
    // note - the strategy to exec logically sync or async rests with the V1, not here - see who populates prereq
    // here we just respect them by creating depys
    results
        .filter(a => !DomState.isDone(a.status))
        .filter(_.prereq.nonEmpty).map { res =>
      crdep(res.prereq.flatMap(x => root.find(x)), List(res))
    }

    res.filter(a => a.status != DomState.DEPENDENT && !DomState.isDone(a.status))
  }

  /** find any applicable flow directives */
  def findFlows(parent: DomAst) = {
    // any seq/par flows apply to a ? (a is the parent that generated these messages)
    if (parent.value.isInstanceOf[EMsg]) {
      implicit val ctx: StaticECtx = new StaticECtx(parent.value.asInstanceOf[EMsg].attrs, Some(this.ctx), Some(parent))
      flows.filter(_.e.test(parent, parent.value.asInstanceOf[EMsg]))
    } else
      Nil
  }


  /** a decomp req - starts processing a message. these can be deferred async or recurse synchronously
    *
    * @param a       the node to decompose/process
    * @param recurse recurse or not
    * @param level   - current level, root is 0
    */
  protected def req(a: DomAst, recurse: Boolean = true, level: Int) {
    var msgs: List[DEMsg] = Nil

    if(!DomState.isDone(a.status)) { // may have been skipped by others
      evChangeStatus(a, DomState.STARTED)
      a.start(seq())

      trace("DomEng " + id + ("  " * level) + " expand " + a.value)
      msgs = Try {
        expand(a, recurse, level + 1)
      }.recover {
        case t if t.isInstanceOf[javax.script.ScriptException] || t.isInstanceOf[DieselExprException] => {
          info("Exception wile decompose() " + t.getMessage)
          val err = DEError(this.id, t.toString)
          val ast = DomAst(
            new EError("Exception: " + t.getMessage, t)
          ).withStatus(DomState.DONE)

          evAppChildren(a, ast)
          err :: nodeDone(ast, level + 1)
        }
        case t: Throwable => {
          razie.Log.log("Exception wile decompose()", t)
          val err = DEError(this.id, t.toString)
          val ast = DomAst(new EError("Exception " + t.getMessage, t)).withStatus(DomState.DONE)

          evAppChildren(a, ast)

          err :: nodeDone(ast, level + 1)
        }
      }.get

      if (a.value.isInstanceOf[EEngSuspendDaemon]) {
        // nop
        evChangeStatus(a, DomState.SUSPENDED)
      } else if (a.value.isInstanceOf[EEngSuspend]) {
        // nop
        evChangeStatus(a, DomState.SUSPENDED)
      } else if (msgs.isEmpty)
        msgs = msgs ::: nodeDone(a, level)
    }

    checkState()
    later(msgs)
  }

  /** process a reply with results
    *
    * will decompose this node into children and spawn async those that need it
    *
    * @param a       the node that got this reply
    * @param recurse do i need to recurse into children? default=true
    * @param level   - current level, 0 is first
    * @param results - the results
    * @return
    */
  def rep(a: DomAst, recurse: Boolean = true, level: Int, results: List[DomAst], justAddChildren: Boolean = false)
  : List[DEMsg] = {
    var msgs: List[DEMsg] = Nil // continuations from this cycle

    evAppChildren(a, results)

    // can't do this - too stupid. it's easy to have stories with 100 or more activities
//    if (a.children.size >= maxLevels) {
    // simple protection against infinite loops
//      evAppChildren(a, DomAst(TestResult("fail: Max-Children!", "You have a loop rule out of control ( > 20 loops).
//      .."), "error"))
//    } else
    if (recurse) {
      msgs =
          msgs :::
              findFront(a, results).map { n =>
                // start the next child
                // todo this would be sync:     req(n, recurse, level + 1)i
                evChangeStatus(n, DomState.LATER)
                DEReq(id, n, recurse, level + 1) // increase level for children
              }
    }

    msgs
  }

  /** check the state at end of step - anything still running?
    *
    * @return list of continuations triggered by this state change
    */
  protected def checkState(node: DomAst = root) = {
    val res = node.collect {
      case a if a.id != node.id && DomState.inProgress(a.status) => a
    }

    var result: List[DEMsg] = Nil

    if (res.size > 0) {
      if (node.status != DomState.SUSPENDED) {
        // suspended nodes stay in SUSPENDED. their children can continue tho
        if (!DomState.inProgress(node.status)) {
          evChangeStatus(node, DomState.STARTED)
          //node.end()
        }
      }
    } else if (
      node.status != DomState.SUSPENDED ||
          node.childrenCol.exists(child =>
            child.value.isInstanceOf[EEngComplete] &&
                DomState.isDone(child.status)
          )
    ) {
      // only if they have a completed EEngComplete can complete a SUSPENDED

      if (node.status != DomState.DONE) {
        // call only when transitioning to DONE
        result = nodeDone(node) //node.status = DONE
      }
    }

    // when all done, the entire engine is done

    if (root.status == DomState.DONE && status != DomState.DONE) {
      status = DomState.DONE
      trace("DomEng " + id + " finish")
      engineDone()
      DieselAppContext.activeActors.get(id).foreach(_ ! DEStop) // stop the actor and remove engine

    }

    result
  }

  /** add built-in triggers and rules
    *
    * IT IS important that this be called just before starting execution, after all children were
    * added to the root
    *
    * @param l children alredy added tothe root
    */
  protected def prepRoot(l: ListBuffer[DomAst]): ListBuffer[DomAst] = {
    // spec vals are expanded on exec of this VALS
    val vals = DomAst(EMsg(DieselMsg.ENGINE.DIESEL_VALS), AstKinds.TRACE)
    val warns = DomAst(EMsg(DieselMsg.ENGINE.DIESEL_WARNINGS), AstKinds.TRACE)
    val before = DomAst(EMsg(DieselMsg.ENGINE.DIESEL_BEFORE), AstKinds.TRACE)
    val after = DomAst(EMsg(DieselMsg.ENGINE.DIESEL_AFTER), AstKinds.TRACE)
    val desc = DomAst(EInfo(
      description,
      this.pages.map(_.specRef.wpath).mkString("\n") + "\n" +
          this.settings.toString
    ), AstKinds.DEBUG).withStatus(DomState.SKIPPED)

    // create dependencies and add them to the list
    after.prereq = l.map(_.id).toList
    root.appendAllNoEvents(List(after))
    l.foreach(x => x.prereq = before.id :: x.prereq)

    root.prependAllNoEvents(List(desc, vals, before, warns))

    warnings = Some(warns)

    //child engines notify parent if needed - payload is passed as well, see V1 for pong
    correlationId
        .filter(_ contains ".")
        .foreach(cid => {
          val ids = cid.split("\\.")
          val notifyParent = DomAst(EMsg(
            DieselMsg.ENGINE.DIESEL_PONG,
            List(
              P.of("parentId", ids(0)),
              P.of("targetId", ids(1)),
              P.of("level", -1)
            )
          ))

          // dead last node
          notifyParent.prereq = l.map(_.id).toList
          root.appendAllNoEvents(List(notifyParent))
        })
    l
  }

  /**
    * process only the tests - in sequence, this is meant to validate a data from another engine
    *
    * we will remove all other nodes but the $expect from the story
    *
    * this will prep the root node, then start execution
    */
  def processTests = {
    Future {
      GlobalData.dieselEnginesActive.incrementAndGet()
      clog << s"WF.START.$id"
      trace("*******************************************************")
      trace("DomEng.tests STARTING " + id + " - " + description)
      trace("*******************************************************")
      trace("")

      root.status = DomState.STARTED
      this.status = DomState.STARTED
      root.start(seq())

      // tricky - skipping all nodes from root,
      // but the test nodes before prepping the engine
      root
          .childrenCol
          .filter(_.kind != AstKinds.TEST)
          .filter(_.kind != AstKinds.STORY)
          .foreach(_.withStatus(DomState.SKIPPED))

      prepRoot(
        root.childrenCol
      ).foreach(expand(_, recurse = true, 1))

      this
    }
  }

  // waitingo for flow to complete
  val finishP = Promise[DomEngine]()
  val finishF = finishP.future

  /**
    * process the root
    *
    * this will prep the root node, then start execution
    */
  def process: Future[DomEngine] = {
    if (root.status != DomState.STARTED) {

      GlobalData.dieselEnginesActive.incrementAndGet()
      clog << s"WF.START.$id"
      trace("*******************************************************")
      trace("DomEng STARTING " + id + " - " + description)
      trace("*******************************************************")
      trace("")
      root.status = DomState.STARTED
      this.status = DomState.STARTED
      root.start(seq())

      // async start
      val msgs = findFront(root, prepRoot(root.childrenCol).toList).map { x =>
        x.status = DomState.LATER
        DEReq(id, x, recurse = true, 0)
      }

      if (msgs.isEmpty)
        nodeDone(root)
      later(msgs)
    }

    finishF
  }

  /** exec sync as a func call, used from AExprFunc
    *
    * @param ast   new root
    * @param level starting level
    * @param ctx   root context to use
    * @return
    */
  def execSync(ast: DomAst, level: Int, ctx: ECtx): Option[P] = {
    // stop propagation of local vals to parent engine
    var newCtx: ECtx = new ScopeECtx(Nil, Some(ctx), Some(ast))
    // include this messages' context
    newCtx =
        if (ast.value.isInstanceOf[EMsg]) new StaticECtx(ast.value.asInstanceOf[EMsg].attrs, Some(newCtx), Some(ast))
        else new StaticECtx(Nil, Some(newCtx), Some(ast))

    ast.replaceCtx(newCtx)

//    GlobalData.dieselEnginesActive.incrementAndGet()

    // inherit all context from parent engine
//    this.ctx.root.overwrite(newCtx)

    // todo clone the root context passed - if anyone goes to the root will find the other engine...

    var msgs: List[_] = Nil

    if (ast.getCtx.isEmpty) ast.withCtx(newCtx)

    if (ast.value.isInstanceOf[EMsg]) {
      val (msgsx, skipped) = expandEMsg(ast, ast.value.asInstanceOf[EMsg], recurse = true, level, newCtx)
      msgs = msgsx
      evAppChildren(ast, msgsx)
    } else {
      msgs = expand(ast, recurse = true, level)
    }

    var res = newCtx.getp(Diesel.PAYLOAD)

    // recurse manually
    msgs.collect {

      case a: DomAst if a.value.isInstanceOf[EMsg] =>
        res = execSync(a, level + 1, newCtx)

      case a: DomAst if a.value.isInstanceOf[ENextPas] =>
        res = execSync(a, level + 1, newCtx)
//          a.value.isInstanceOf[ENext] ||
//          a.value.isInstanceOf[EMsgPas] =>

//      case a if a.value.isInstanceOf[EVal] =>
//        res = execSync(a, level + 1, newCtx)
    }
    ast.status = DomState.DONE // bypass evChangedStatus
    ast.end()

//    engineDone(false)
//    DieselAppContext.activeActors.get(id).foreach(_ ! DEStop) // stop the actor and remove engine

    res
  }

  /** main processing of next - called from actor in async and in thread when sync/decompose */
  private[engine] def processDEMsg(m: DEMsg) = {
    m match {
      case DEReq(eid, a, r, l) => {
        require(eid == this.id) // todo logical error not a fault
        this.req(a, r, l)
      }

      case DERep(eid, a, r, l, results) => {
        require(eid == this.id) // todo logical error not a fault
        this.rep(a, r, l, results)
      }

      case DEComplete(eid, aid, r, l, results) => {
        // completed child spawned that I'm waiting for
        require(eid == this.id) // todo logical error not a fault
        val target = n(aid)
        val completer = DomAst(EEngComplete("DEComplete"), AstKinds.BUILTIN)
        val toAdd = results ::: completer :: Nil
        // don't trust them, find level
        val level = Try {findLevel(target)}.getOrElse(l)

        if (target.status != DomState.DONE) {
          evAppChildren(target, toAdd)
          val msgs = nodeDone(completer, level + 1)
          checkState()

          // populate values
          results.collect {
            case a: DomAst if a.value.isInstanceOf[EVal] =>
              setSmartValueInContext(a, a.getCtx.get, a.value.asInstanceOf[EVal].p)
          }

          later(msgs)
        } else {
          // todo add a warn
          evAppChildren(
            target,
            List(DomAst(EWarning("DEComplete on DONE"), AstKinds.DEBUG))
          )

          Nil
        }
      }

      case DEAddChildren(eid, aid, r, l, results, mapper) => {
        // add more children to a node (used for async prcs, like stream consumption)
        require(eid == this.id) // todo logical error not a fault
        val target = n(aid)

        // don't trust them, find level
        val level = Try {findLevel(target)}.getOrElse(l)

        val asts = if (mapper.isDefined) results.map(mapper.get.apply(_, this)) else results

        later(
          this.rep(target, true, level + 1, asts)
        )
      }

      case DEPruneChildren(eid, tid, leave, level) => {
        require(eid == this.id) // todo logical error not a fault
        val target = n(tid)

        this.prune(target, leave, level)
      }

    }
  }

  /** extract the resulting values from this engine */
  def extractValues (e:String, a:String) = {
    require(DomState.isDone(this.status)) // no sync

    // find the spec and check its result
    // then find the resulting value.. if not, then json
    val oattrs = dom.moreElements.collectFirst {
      case n: EMsg if n.entity == e && n.met == a => n
    }.toList.flatMap(_.ret)

    // collect values
    val valuesp = root.collect {
      case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _) if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => p
    }

    valuesp
  }

  /** this was resulted in an html response - add it as a trace */
  def addResponseInfo(code: Int, body: String, headers: Map[String, String]): Unit = {
    val h = headers.mkString("\n")
    val ast = new DomAst(
      EInfo(s"Response: ${code} BODY: ${body.take(500)}", s"BODY:\n${body}\n\nHEADERS:\n$h"), AstKinds.TRACE)
    this.root.appendAllNoEvents(List(ast))
  }

  /** collect the last generated value OR empty string */
  def resultingValue = root.collect {
    case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _,
    _) /*if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined */ => (p.name, p.currentStringValue)
  }.lastOption.map(_._2).getOrElse("")

  /** extract the resulting value from this engine
    * extract one value - try:
    * 1. defined response oattrs
    * 2. last valuep - the last message produced in the flow
    * 3. payload
    *
    * @param ea = initial message ea if known, empty otherwise
    * */
  def extractFinalValue(ea: String, evenIfExecuting: Boolean = false): Option[P] = {
    if (!evenIfExecuting) require(DomState.isDone(this.status)) // no sync

    // find the spec and check its result
    // then find the resulting value.. if not, then json
    val oattrs = dom.moreElements.collectFirst {
      case n: EMsg if n.ea == ea => n
    }.toList.flatMap(_.ret)

    // collect all values
    // todo this must be smarter - ignore diese.before values, don't look inside scopes etc?
    val valuesp = root.collect {
      case d@DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _)
        if oattrs.isEmpty || oattrs.find(_.name == p.name).isDefined => p
    }

    // extract one value - try:
    // 1. defined response oattrs
    // 2. last valuep - the last message produced in the flow
    // 3. payload
    val resp = oattrs.headOption.flatMap(oa => valuesp.reverse.find(_.name == oa.name))
        .orElse(valuesp.lastOption)
        .orElse(valuesp.find(_.name == Diesel.PAYLOAD))

    resp.map(_.calculatedP)
  }

  // todo still used in some places...
  // todo not right to look first at PAYLOAD - if you use any message creating payload this fucks it
  // but what if you don't and you populate it yourself?
  def extractOldValue(ea: String, evenIfExecuting: Boolean = false) = {
    val payload = this.ctx.getp(Diesel.PAYLOAD).filter(_.ttype != WTypes.wt.UNDEFINED)
    val resp = payload.orElse(
      this.extractFinalValue(ea)
    )
    resp
  }

  def finalContext(e: String, a: String) = {
    require(DomState.isDone(this.status)) // no sync

    // find the spec and check its result
    // then find the resulting value.. if not, then json
    val oattrs = dom.moreElements.collectFirst {
      case n: EMsg if n.entity == e && n.met == a => n
    }.toList.flatMap(_.ret)

    // collect values
    val values = root.collect {
      case DomAst(EVal(p), /*AstKinds.GENERATED*/ _, _, _) if oattrs.isEmpty || oattrs.find(
        _.name == p.name).isDefined => p
    }

    values
  }

  def addError(t: Throwable) {
    root.append(DomAst(new EError("Exception:", t), AstKinds.ERROR).withStatus(DomState.DONE))
  }
}

