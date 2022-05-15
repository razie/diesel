/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.engine

/** the kinds of nodes we understand */
object AstKinds {
  final val ROOT = "root"          // only one root node, no other purpose
  final val STORY = "story"        // story node - scope for a story
  final val RECEIVED = "received"  // received from a story or elsewhere
  final val SAMPLED = "sampled"

  final val ERROR = "error"        // an error
  final val DEBUG = "debug"        // debug level info
  final val TRACE = "trace"        // trace level info
  final val VERBOSE = "verbose"    // most fine level of logging

  // these are like "info"
  final val GENERATED = "generated" // generated nodes like info etc
  final val TEST = "test"
  final val BUILTIN = "built-in"

  final val SUBTRACE = "subtrace"
  final val RULE = "rule"
  final val SKETCHED = "sketched"
  final val MOCKED = "mocked"
  final val NEXT = "next"

  final val IGNORE = "ignore" // don't use these nodes

  def isGenerated(k: String) = GENERATED == k || SKETCHED == k || MOCKED == k || DEBUG == k || ERROR == k || TRACE == k || VERBOSE == k

  def shouldIgnore(k: String) = RULE == k || BUILTIN == k

  def shouldSkip(k: String) = NEXT == k

  def shouldRollup(k: String) = NEXT == k

  def shouldPrune(k: String) = false

  /** kind based on arch so you can specify <trace> and all kids become "trace" */
  def kindOf(arch: String) = {
    val kind = arch match {
      case _ if arch contains "verbose" => AstKinds.VERBOSE
      case _ if arch contains "trace"   => AstKinds.TRACE
      case _ if arch contains "debug"   => AstKinds.DEBUG
      case _ => AstKinds.GENERATED
    }

    kind
  }
}
