/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.diesel.engine.RDExt.TestResult
import razie.diesel.engine.nodes.{EError, ExpectAssert, ExpectM, ExpectV, StoryNode, StoryTestStats}
import razie.diesel.utils.DomHtml.quickBadge

/** utilities to show engine info
  */
object DomEngineView {

  /** story */
  def stories(a: DomAst) = {
    a.children.filter(_.kind == "story").map(ast =>
      (ast, ast.value.asInstanceOf[StoryNode])
    )
  }

  /** story summary if this was a test story */
  def storySummary(a: DomAst) = {
    val zip = a.children.zipWithIndex
    val stories = zip.filter(_._1.kind == AstKinds.STORY)

    val resl = Range(0, stories.size).map { i =>
      val s = stories(i)
      val sn = s._1.value.asInstanceOf[StoryNode]

      val StoryTestStats(failed, total, _, _, _) = if(s._1.status == DomState.STARTED) sn.calculateTempStats(s._1) else sn.getStats

      s"""<a href="#${s._1.value.asInstanceOf[StoryNode].path.wpath.replaceAll("^.*:", "")}">[details]</a>""" +
          quickBadge(failed, total, -1, "", s._1.status) +
          (s._1.value match {
            // doing this to avoid getting the a name at the top and confuse the scrolling
            case sn: StoryNode => s""" Story ${sn.path.ahref.mkString}"""
            case _ => s._1.meTos(1, true)
          })
    }

    resl.mkString("\n")
  }

  /** story summary if this was a test story */
  def storySummaryNice(a: DomAst) = {
    val zip = a.children.zipWithIndex
    val stories = zip.filter(_._1.kind == "story")

    val resl = Range(0, stories.size).map { i =>
      val s = stories(i)
      val nodes = s._1.children
//        if(i < stories.size - 1) a.children.slice(s._2+1, stories(i+1)._2 - 1)
//        else a.children.slice(s._2+1, a.children.size - 1)

      val failed = failedTestCount(nodes.toList)
      val total = totalTestedCount(nodes.toList)

      val n = s._1.value.asInstanceOf[StoryNode].path.wpath.replaceAll("^.*:", "")

      (n, failed, total)
    }

    resl.toList
  }

  // failed tests
  def failedTestCount(a: DomAst): Int = failedTestCount(List(a))

  def failedTestList(nodes: List[DomAst]): List[DomAst] = nodes.flatMap(_.collect {
    case d@DomAst(n: TestResult, _, _, _) if n.value.startsWith("fail") => d
    case d@DomAst(n: EError, _, _, _) if !n.handled => d
  })

  /** list all tests and errors for junit report */
  def testList(nodes: List[DomAst]): List[DomAst] = nodes.flatMap(_.collect {
    case d@DomAst(n: TestResult, _, _, _) => d
    case d@DomAst(n: EError, _, _, _) if !n.handled => d
  })

  def failedTestListStr(nodes: List[DomAst]): List[String] =
    failedTestList(nodes).map(_.meTos(1, true))

  // exceptions and errors other than failed tests
  def errorCount(a: DomAst): Int = errorCount(List(a))

  def successTestCount(a: DomAst): Int = successTestCount(List(a))

  def totalTestedCount(a: DomAst): Int = totalTestedCount(List(a))

  def todoTestCount(a: DomAst): Int = todoTestCount(List(a))

  /** count test results */
  def totalTestedCount(nodes: List[DomAst]): Int = (nodes.flatMap(_.collect {
    case d@DomAst(n: TestResult, _, _, _) => n
  })).size + storyStats(nodes, _.total)

  /** count test results */
  def storyStats(nodes: List[DomAst], f: StoryTestStats => Int) = (nodes.flatMap(_.collect {
    case d@DomAst(n: StoryNode, _, _, _) if n.stats.isDefined => n
  })).map(sn => f(sn.getStats)).sum

  /** count tests to do */
  def todoTestCount(nodes: List[DomAst]): Int = (nodes.flatMap(_.collect {
    case d@DomAst(n: ExpectAssert, _, _, _) /*if DomState.isDone(d.status)*/ => n
    case d@DomAst(n: ExpectM, _, _, _) /*if DomState.isDone(d.status)*/ => n
    case d@DomAst(n: ExpectV, _, _, _) /*if DomState.isDone(d.status)*/ => n
  })).size

  def failedTestCount(nodes: List[DomAst]): Int = {
    val l = (nodes.flatMap(_.collect {
      case d@DomAst(n: TestResult, _, _, _) if n.value.startsWith("fail") => n
      case d@DomAst(n: EError, _, _, _) if !n.handled => n
    }))
      l.size
  }
  // not add storyStats because we keep erorred stories intact

  def errorCount(nodes: List[DomAst]): Int = (nodes.flatMap(_.collect {
    case d@DomAst(n: EError, _, _, _) if !n.handled => n
  })).size
  // not add storyStats because we keep erorred stories intact

  def successTestCount(nodes: List[DomAst]): Int = (nodes.flatMap(_.collect {
    case d@DomAst(n: TestResult, _, _, _) if n.value.startsWith("ok") => n
  })).size + storyStats(nodes, _.success)
}
