/**
  *   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 **/
package razie.tconf.parser

import razie.tconf.{DSpec, DUser}
import razie.{audit, cdebug}
import scala.util.parsing.input.Positional

/** an AST node collects the result of a parser rule - base trait for all AST node types
  *
  * @param s the HTML representation of this section
  * @param tags the properties collected from this section, will be added to the page's tags
  * @param ilinks the links to other pages collected in this rule
  */
trait BaseAstNode {
  def s: String
  def props: Map[String, String]
  def ilinks: List[Any]

  var pos:Option[Positional] = None
  def withPos(k:Positional) = {
    this.pos = Some(k)
    this
  }

  var keyw:Option[String] = None
  def withKeyw(k:String) = {
    this.keyw = Some(k.trim)
    this
  }

  /** composing AST elements */
  def +(other: BaseAstNode): BaseAstNode = ListAstNode(this, other)

  /** lazy unfolding of AST tree */
  def fold(ctx: FoldingContext[_, _]): StrAstNode = {
    try {
      if (SpecParserSettings.debugAstNodes) {
        cdebug << "======================= F O L D ========================="
        cdebug << print(1)
        cdebug << "======================= F O L D I N G ========================="
      }
      ifold(StrAstNode.EMPTY, ctx)
    } catch {
      case t: Throwable =>
        audit.Audit.logdb(
          "EXCEPTION_PARSING.folding", ctx.wpath + " " + t.getMessage
        )
        razie.Log.error("EXCEPTION_PARSING.folding - " + ctx.wpath + " ", t)
        StrAstNode("EXCEPTION_PARSING.folding - " + t.getLocalizedMessage())
    }
  }
  def ifold(current: StrAstNode, ctx: FoldingContext[_, _]): StrAstNode

  def isLazy = false

  def print(level: Int): String = ("--" * level) + this.toString
  def printHtml(level: Int): String = "<ul>" + this.toString + "</ul>"
}

object StrAstNode {
  // todo why can't this be a final val ?
  def EMPTY = StrAstNode("")
}

/**
  * leaf AST node - final computed value
  */
case class LeafAstNode(s: String,
                       other: BaseAstNode,
                       props: Map[String, String] = Map.empty,
                       ilinks: List[Any] = List.empty)
    extends BaseAstNode {
  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode =
    other.ifold(current, ctx).copy(s = s)

  override def toString = s"LeafNODE ($s)"
}

/**
  * leaf AST node - final computed value
  *
  * @param s the string representation of this section
  * @param props the tags collected from this section, will be added to the page's tags
  * @param ilinks the links to other pages collected in this rule
  */
case class StrAstNode(s: String,
                      props: Map[String, String] = Map.empty,
                      ilinks: List[Any] = List.empty)
    extends BaseAstNode {
  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  def this(s: String, ilinks: List[Any]) = this(s, Map.empty, ilinks)

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode = this
  override def toString = s"SLeafNODE ($s)"
}

/**
  * a list of AST nodes - it's a branch in the AST tree
  */
case class ListAstNode (states: BaseAstNode*) extends BaseAstNode {
  def this(l: List[BaseAstNode]) = this()

  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  // nobody should ask for these - fold the parse result into a SState always
  override def s: String = "n/a" //???
  override def props: Map[String, String] = ???
  override def ilinks: List[Any] = ???

  /** optimize composiiotn of lists */
  override def +(other: BaseAstNode): BaseAstNode = other match {
    case ls: ListAstNode => ListAstNode(states ++ ls.states)
    case ps: BaseAstNode => ListAstNode(states ++ Seq(ps))
  }

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode = {
    states.foldLeft(StrAstNode.EMPTY) { (a, b) =>
      val c = b.ifold(a, ctx)
      StrAstNode(a.s + c.s, a.props ++ c.props, a.ilinks ++ c.ilinks)
    }
  }
  override def toString = s"LNODE (${states.mkString})"
  override def print(level: Int): String =
    ("--" * level) + "LNODE" + states.map(_.print(level + 1)).mkString
  override def printHtml(level: Int): String =
    "LNODE" + "<ul>" + states
      .map(x => "<li>" + x.printHtml(level + 1))
      .mkString + "</ul>"
}

/**
  * an aggregation AST node - pattern is: prefix+midAST+suffix
  */
case class TriAstNode(prefix: String, mid: BaseAstNode, suffix: String)
    extends BaseAstNode {
  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  // nobody should ask for these - fold the parse result into a SState always
  override def s: String = mid.s //???
  override def props: Map[String, String] = ???
  override def ilinks: List[Any] = ???

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode = {
    val c = mid.ifold(current, ctx)
    StrAstNode(prefix + c.s + suffix, c.props, c.ilinks)
  }
  override def toString = s"TriNODE ($prefix, $mid, $suffix)"
  override def print(level: Int): String =
    ("--" * level) + s"TriNODE ($prefix, $suffix)" + mid.print(level + 1)
  override def printHtml(level: Int): String =
    s"TriNODE ($prefix, $suffix)" + "<ul><li>" + mid.printHtml(level + 1) + "</ul>"
}

/**
  * lazy STATIC AST node - value computed when they're folded
  *
  * ... but these are cacheable.
  *
  * within parsers, see use of ifoldStatic. the ifold is only used internally to fold
  */
case class LazyStaticAstNode[T <: DSpec](
  f: (StrAstNode, StaticFoldingContext[T]) => StrAstNode
) extends BaseAstNode {
  var dirty = false
  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  // nobody should ask for these - fold the parse result into a SState always
  override def s: String = ???
  override def props: Map[String, String] = ???
  override def ilinks: List[Any] = ???
  override def isLazy = true

  def ifoldStatic(current: StrAstNode,
                     ctx: StaticFoldingContext[_]): StrAstNode = {
    if (dirty) ctx.cacheable = false
    f(current, ctx.asInstanceOf[StaticFoldingContext[T]])
  }

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode =
    ifoldStatic(current, ctx)

  override def toString = s"LazyStaticNODE ()"

}

/**
  * lazy AST node - value computed when they're folded.
  *
  * By default a lazy state will cause a non cacheable wiki
  *
  * within parsers, see use of ifoldStatic. the ifold is only used internally to fold
  */
case class LazyAstNode[T <: DSpec, U <: DUser](
  f: (StrAstNode, FoldingContext[T, U]) => StrAstNode
) extends BaseAstNode {
  var dirty = true

  if (SpecParserSettings.debugAstNodes) cdebug << this.toString

  // nobody should ask for these - fold the parse result into a SState always
  override def s: String = ???
  override def props: Map[String, String] = ???
  override def ilinks: List[Any] = ???
  override def isLazy = true

  override def ifold(current: StrAstNode,
                     ctx: FoldingContext[_, _]): StrAstNode = {
    if (dirty) ctx.cacheable = false
    f(current, ctx.asInstanceOf[FoldingContext[T, U]])
  }
  override def toString = s"LazyNODE ()"

  def cacheOk = { this.dirty = false; this }
}
