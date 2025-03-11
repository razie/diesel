/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.engine

import org.bson.types.ObjectId
import razie.diesel.engine.nodes.{CanHtml, EDuration, EError, EInfo, EMsg, ETrace, EVal, HasPosition, StoryNode}
import razie.diesel.expr.ECtx
import scala.collection.mutable.ListBuffer

/** mix this in if you want to control display/traversal */
trait DomAstInfo {
  /** prune i.e. stop showing children */
  def shouldPrune : Boolean

  /** ignore this node and branch */
  def shouldIgnore : Boolean

  /** skip this node */
  def shouldSkip : Boolean

  /** don't show this node, just show it's children as if they'r eunder the parent */
  def shouldRollup : Boolean
}


/** a tree node in an engine/flow
  *
  * kind is spec/sampled/generated/test etc
  *
  * todo need ID conventions to suit distributed services
  */
case class DomAst(
  var value: Any,
  var kind: String = AstKinds.GENERATED,
  childrenCol: ListBuffer[DomAst] = new ListBuffer[DomAst](),
  id : String = new ObjectId().toString
  ) extends CanHtml {

  /** children should be read-only. If you need to modify them, use append* - do not modify the children directly */
  def children: List[DomAst] = childrenCol.toList

  //=========== runtime data

  var parent: Option[DomAst] = None

  var tailrec = false
  def withTailrec (b:Boolean) = {
    this.tailrec = b
    this
  }

  // todo the nodes should remember the context they had, so we can see the values at that point, later
  // todo most likely clone the environment, when processing done?
  private var imyCtx: Option[ECtx] = None

  /** get the closest enclosing context */
  def getCtx: Option[ECtx] = imyCtx orElse parent.flatMap(_.getCtx)

  /** get my context if any, no parent fallbacks */
  def getMyOwnCtx: Option[ECtx] = imyCtx

  /** reset to null if you're changing parent... */
  def resetParent(f: => DomAst) = {
    this.parent = Option.apply(f)
    this
  }

  def withParent(f: => DomAst) = {
    if (this.parent.isEmpty) {
      this.parent = Some(f)
    }
    else {
      throw new IllegalStateException("Ast already has a parent...")
    }
    this
  }

  /** use null to remove context */
  def replaceCtx(f: => ECtx) = {
    val ret = f
    this.imyCtx = Option(ret)
    if(ret != null) this.imyCtx.get else null
  }

  def withCtx(f: => ECtx) = {
    if (this.imyCtx.isEmpty) {
      this.imyCtx = Some(f)
      this.imyCtx.get
    }
    else {
      throw new IllegalStateException("Ast already has a context...")
    }
  }

  def orWithCtx(f: => Option[ECtx]) = {
    if (this.imyCtx.isEmpty) {
      this.imyCtx = f
    }
    this.imyCtx
  }

  var guard: String = DomState.GUARD_NONE

  private var istatus: String = DomState.INIT

  /** current status - see [[DomState]] for values */
  def status: String = istatus

  def status_=(s: String): Unit = istatus = s

  /** timestamp started */
  var tstart: Long = System.currentTimeMillis()
  /** timestamp ended */
  var tend: Long = System.currentTimeMillis()
  /** execution sequence number - an engine is a single sequence */
  var seqNo: Long = -1

  /** SPECIAL: NO EVENTS generated - use carefully */
  def prependAllNoEvents(other: List[DomAst]): Unit = {
    this.childrenCol.prependAll(other)
    other.foreach(_.withParent(this))
  }

  /** SPECIAL: NO EVENTS generated - use carefully */
  def appendVerbose(m: ETrace): Unit = {
    this.appendAllNoEvents(
      List(
        DomAst(m, AstKinds.VERBOSE).withStatus(DomState.SKIPPED)
      ))
  }

  /** SPECIAL: NO EVENTS generated - use carefully */
  def appendAllNoEvents(other: List[DomAst]): Unit = {
    this.childrenCol.appendAll(other)
    other.foreach(_.withParent(this))
  }

  /** SPECIAL: NO EVENTS generated - use carefully */
  def moveAllNoEvents(from: DomAst)(implicit engine: DomEngineState): Unit = {
    this.childrenCol.appendAll(from.children)
    from.children.foreach(_.resetParent(this))
    from.childrenCol.clear() // need to remove them from parent, or they will be duplos and create problems
  }

  /** will force updates to go through a DES */
  def appendAll(other: List[DomAst])(implicit engine: DomEngineState): Unit = {
    engine.evAppChildren(this, other)
  }

  /** will force updates to go through a DES */
  def append(other: DomAst)(implicit engine: DomEngineState): Unit = {
    engine.evAppChildren(this, other)
  }

  def start(seq: Long): Unit = {
    tstart = System.currentTimeMillis()
    tend = tstart
    seqNo = seq
  }

  def end(): Unit = {
    tend = System.currentTimeMillis()
  }

  def withDuration (start:Long, end:Long) = {
    tstart = start
    tend = end
    this
  }

  //============ domain details

  var moreDetails = " "
  var specs: List[Any] = Nil
  var prereq: List[String] = Nil

  /** depends on other nodes by IDs
    *
    * this node will not start unless those prereq are complete
    *
    * engines strategies, tags and rules dictate how these prerequisites are setup
    */
  def withPrereq(s: List[String]) = {
    prereq = (s ::: prereq).distinct  // distinct is important for some reason - hanoi fails miserably otherwise
    this
  }

  /** this node has a spec */
  def withSpec(s: Any) = {
    if (s.isInstanceOf[Option[_]]) {
      s.asInstanceOf[Option[_]].foreach { x =>
        specs = x :: specs
      }
    }
    else specs = s :: specs
    this
  }

  def withStatus(s: String) = {
    this.status = s
    this
  }

  def withDetails(s: String) = {
    moreDetails = moreDetails + s
    this
  }

  /** reduce footprint/memory size */
  def removeDetails(): Unit = {
    moreDetails = " "
  }

  /** prune story - call this on Story nodes */
  def removeTestDetails(): Unit = {

    // todo this must be smarter and traverse and prun back - there are now stories in stories and test results on deeper levels, with diesel.expect etc

    //only if children do not contain story nodes
    val tokill = childrenCol.filter {x=>
      // todo optimize - use like a findFirst or such
    val stories = x.find {
      d => d.value.isInstanceOf[StoryNode]
    }
    stories.isEmpty
    }

    tokill.foreach(_.childrenCol.clear())
  }

  //============== traversal

  private def shouldPrune(k: DomAst) =
    AstKinds.shouldPrune(k.kind) ||
        k.value.isInstanceOf[DomAstInfo] && k.value.asInstanceOf[DomAstInfo].shouldPrune

  private def shouldIgnore(k: DomAst) =
    AstKinds.shouldIgnore(k.kind) ||
        k.value.isInstanceOf[DomAstInfo] && k.value.asInstanceOf[DomAstInfo].shouldIgnore

  private def shouldSkip(k: DomAst) =
    AstKinds.shouldSkip(k.kind) ||
        k.value.isInstanceOf[DomAstInfo] && k.value.asInstanceOf[DomAstInfo].shouldSkip

  private def shouldRollup(k: DomAst) =
    AstKinds.shouldRollup(k.kind) ||
        k.value.isInstanceOf[DomAstInfo] && k.value.asInstanceOf[DomAstInfo].shouldRollup

  // visit/recurse with filter
  def collect[T](f: PartialFunction[DomAst, T]): List[T] = {
    val res = new ListBuffer[T]()

    def inspect(d: DomAst, level: Int): Unit = {
      if(level > 100) {
        throw new DieselException("Recursion problem at: " + this.value.toString)
      }
      if (f.isDefinedAt(d)) res append f(d)
      d.children.foreach(inspect(_, level + 1))
    }

    inspect(this, 0)
    res.toList
  }

  /** when you need to avoid certain paths like subtraces. the filter stops recursion */
  def collectWithFilter[T](f: PartialFunction[DomAst, T]) (filter:PartialFunction[DomAst, Boolean]): List[T] = {
    val res = new ListBuffer[T]()

    def inspect(d: DomAst, level: Int): Unit = {
      if(level > 100) throw new DieselException("Recursion problem at: " + this.value.toString)
      if(filter.isDefinedAt(d) && filter(d)) {
        if (f.isDefinedAt(d)) res append f(d)
        d.children.foreach(inspect(_, level + 1))
      }
    }

    inspect(this, 0)
    res.toList
  }

  // visit/recurse with filter AND level
  def collect2[T](f: PartialFunction[(DomAst, Int), T]): List[T] = {
    val res = new ListBuffer[T]()

    def inspect(d: DomAst, level: Int): Unit = {
      if (f.isDefinedAt((d, level))) res append f((d, level))
      d.children.foreach(inspect(_, level + 1))
    }

    inspect(this, 0)
    res.toList
  }

  /** find in subtree, by id */
  def find(id:String) : Option[DomAst] =
    if(this.id == id)
      Option(this)
    else
      children.foldLeft(None: Option[DomAst])((a, b) => a orElse b.find(id))

  /** find in subtree, by predicate */
  def find(pred: DomAst => Boolean): Option[DomAst] =
    if (pred(this))
      Option(this)
    else
      children.foldLeft(None: Option[DomAst])((a, b) => a orElse b.find(pred))

  /** find looking up to the root, by predicate */
  def findParent(pred: DomAst => Boolean): Option[DomAst] =
    if (pred(this))
      Option(this)
    else
      parent.flatMap(_.findParent(pred))

  //================= view

  /** brief one line desc for logging */
  def description = meTos(level=0, html=false)

  /** non-recursive tostring */
  def meTos(level: Int, html: Boolean): String = {

    def theKind = {
      val duration = tend - tstart

      val (style, cls) = kind match {
        case AstKinds.RECEIVED => ("font-weight:bold", "label label-warning")
        case AstKinds.TEST => ("font-weight:bold", "label label-warning")
        case AstKinds.TRACE | AstKinds.VERBOSE => ("color:lightgray", "")
        case _ => ("", "")
      }

      // do NOT change msec, it's used for durations in engineView
      if (html)
        s"""<span status="$status" seqNo="$seqNo" msec="$duration" id="$id" prereq="${prereq.mkString(",")}"
           |title="$kind, $duration ms" style="$style" class="$cls">${kind.take(3)}</span>""".stripMargin
      else kind
    }

    def theState = {
      if (!DomState.inProgress(this.status)) {
        if(html) "" else this.status
      } else {
        if (html)
          s""" <span style="color:#ff3e3e" title="State: $status"><img src="https://cdn.razie.com/Public/spinner.gif" height="10" width="10"></span>"""
//          s""" <span class="glyphicon glyphicon-exclamation-sign" style="color:#ff3e3e" title="State: $status"></span>"""
        else this.status
      }
    }

    // todo - this prints the context type and id, add it with popup for values?
    val c = ""
//    +
//        this.getMyOwnCtx.map(_.getClass.getSimpleName).mkString.take(2) +
//        this.getMyOwnCtx.map(_.hashCode().toString.reverse.take(3)).mkString

    (c + " " * level) +
        theKind +
        "::" +
        theState + {
      value match {
        case c: CanHtml if html => c.toHtml(kind)
        case x => x.toString
      }
    }.lines.map((" " * 1) + _).mkString("\n") + moreDetails
  }


  /** recursive tostring */
  private def tos(level: Int, html: Boolean): String = {

    def h(s: String) = if (html) s else ""

    def toschildren(level: Int, kids: List[DomAst]): List[Any] =
      kids.filter(k => !shouldIgnore(k)).flatMap { k =>
        if (shouldRollup(k) && k.children.size == 1) {
//           rollup NEXT nodes and others - just show the children
          toschildren(level + 1, k.children)
        } else
          List(k.tos(level + 1, html))
      }

    if (!shouldSkip(this)) {
      val hidden = if(kind == AstKinds.VERBOSE) """style="display:none" """
      h(s"""<div kind="$kind" level="$level" $hidden>""") +
          meTos(level, html) + "\n" +
          toschildren(level, children).mkString +
          h("</div>")
    } else {
      toschildren(level, children).mkString
    }
  }

  override def toString = tos(0, html = false)

  /** this html works well in a diesel fiddle, use toHtmlInPage elsewhere */
  override def toHtml = tos(0, html = true)
  def toHtml (level : Int) = tos(level, html = true)

  /** as opposed to toHtml, this will produce an html that can be displayed in any page, not just the fiddle */
  def toHtmlInPage = toHtml.replaceAllLiterally("weref", "wefiddle")

  type HasJ = {def toj : collection.Map[String,Any]}

  def toj : collection.Map[String,Any] = {
    Map (
      "class" -> "DomAst",
      "kind" -> kind,
      "value" ->
        (value match {
          case m if m.getClass.getDeclaredMethods.exists(_.getName == "toj") => m.asInstanceOf[HasJ].toj
          case x => x.toString
        }),
      "details" -> moreDetails,
      "id" -> id,
      "status" -> status,
      "children" -> tojchildren(children)
    )
  }

  private def tojchildren(kids : List[DomAst]) : List[Any] =
      kids.filter(k=> !AstKinds.shouldIgnore(k.kind)).flatMap{k=>
        if(shouldSkip(k)) {
          tojchildren(k.children.toList)
        } else
          List(k.toj)
      }

  def toJson = toj

  /** GUI needs position info for surfing */
  def posInfo = collect{
    case d@DomAst(m:EMsg, _, _, _) if m.pos.nonEmpty =>
      Map(
        "kind" -> "msg",
        "id" -> (m.entity+"."+m.met),
        "pos" -> m.pos.get.toJmap
      )
  }

  /** GUI needs position info for surfing */
  def pos = value match {
    case d@DomAst(m:HasPosition, _, _, _) => m.pos
    case _ => None
  }

  def setKinds(kkk: String): DomAst = {
    this.kind = kkk
    this.children.map(_.setKinds(kkk))
    this
  }
}


object DomAst {

  /** wrap a given value in an AST, respecting some conventions
    *
    * @param x
    * @param parentKind
    * @return
    */
  def wrap (x:Any, parentKind:String = AstKinds.DEBUG) = {
    if((x.isInstanceOf[DieselTrace]))
      x.asInstanceOf[DieselTrace].toAst
    else
      DomAst(x,
        (
            if (x.isInstanceOf[EInfo]) AstKinds.DEBUG
            else if (x.isInstanceOf[ETrace]) AstKinds.TRACE
            else if (x.isInstanceOf[EDuration]) AstKinds.TRACE
            else if (x.isInstanceOf[EVal]) x.asInstanceOf[EVal].kind.getOrElse(parentKind)
            else if (x.isInstanceOf[EError]) AstKinds.ERROR
            else parentKind
            )
      )
  }
}

/** how many kids to keep, for loops and large items */
trait KeepOnlySomeChildren {
  var keepCount: Int = 3

  /** how many kids to keep, for loops and large items */
  def withKeepCount(k: Int) = {
    this.keepCount = k;
    this
  }
}

/** how many kids to keep, for loops and large items */
trait KeepOnlySomeSiblings {
  var keepCount: Int = 3

  /** how many kids to keep, for loops and large items */
  def withKeepCount(k: Int) = {
    this.keepCount = k;
    this
  }
}
