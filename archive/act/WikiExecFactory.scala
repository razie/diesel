/**
 * ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package act

import org.scalatest.junit._
//import razie.actionables._
//import razie.actionables.library._
//import razie.actionables._
//import razie.actionables.ActionableSpec
import razie.base.ActionContext

//wait(event: String, attr:Map[String,String]) extends WikiAct ("wait", attr)
//waitStep(event: String, attr:Map[String,String], nextWpath:String) extends WikiAct ("waitStep", attr)
//either(or:WikiAct*) extends WikiAct ("either", Map())

/** create a wiki task */
//class crTask extends razie.gremlins.JWFunc {
//  override def apply(in: ActionContext, v: Any): Any = {
//    val user = in sa "user"
//    val task = in sa "task"
//    task
//  }
//}
//
///** create a wiki task */
//class rmTask extends razie.gremlins.JWFunc {
//  override def apply(in: ActionContext, v: Any): Any = {
//    val user = in sa "user"
//    val task = in sa "task"
//    task
//  }
//}
//
///** create a wiki topic */
//class crWiki extends razie.gremlins.JWFunc {
//  override def apply(in: ActionContext, v: Any): Any = {
//    val wpath = in sa "wpath"
//    val content = in sa "content"
//    wpath
//  }
//}
//
///** submitted form */
//class submitForm extends razie.gremlins.JWFunc {
//  override def apply(in: ActionContext, v: Any): Any = {
//    val user = in sa "user"
//    val wpath = in sa "wpath"
//    val reviewer = in sa "reviewer"
//    wpath
//  }
//}
//
///** submitted form */
//class email extends razie.gremlins.JWFunc {
//  override def apply(in: ActionContext, v: Any): Any = {
//    val user = in sa "user"
//    val content = in sa "content"
//    "ok"
//  }
//}
//
///**
// * factory for the basic executables offered
// */
//object WikiExecutables extends ExecutableFactory {
//
//  /**
//   * make an executable
//   */
//  override def make(unifiedstring: String): razie.gremlins.JWFunc = {
//    val Executables.pat(domain, cmd, parens, args) = unifiedstring
//
//    require(cmds contains cmd, "need to support command: " + cmd)
//
//    cmd match {
//      case "crWiki" => new crWiki
//      case "email" => new email
//    }
//  }
//
//  /**
//   * @param domain
//   * @return the actions in this domain
//   */
//  override def commands(): Array[razie.AI] = cmds map (new razie.AI(_))
//
//  val cmds = Array("crWiki", "email")
//}
//
////  Executables.reg(razie.wiki.model.Wikis.RK, SampleExecutables)
