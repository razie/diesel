package controllers

import play.api.mvc.{Action, Request, Result}

import scala.collection.mutable

/** simple code pills controller
  *
  * pills are called with /pill/name
  */
object CodePills extends RazController {

  sealed abstract class BasePill(val name: String) {
    def run (request:Request[_]) : Result
  }

  /** simple pill wrapper */
  private class Pill1(override val name: String, body: Request[_] => Result) extends BasePill(name) {
    def run (request:Request[_]) : Result = body(request)
  }

  /** text pill wrapper */
  private class Pill2(override val name: String, body: Request[_] => String) extends BasePill(name) {
    def run (request:Request[_]) : Result = Ok(body(request)).as("application/text")
  }

  private val pills = new mutable.HashMap[String, BasePill]()

  /** add a pill by name with the given body */
  def add(name: String)(body: Request[_] => Result): Unit = {
    pills += (name -> new Pill1(name, body))
  }

  /** add a text pill by name with the given body */
  def addString(name: String)(body: Request[_] => String): Unit = {
    pills += (name -> new Pill2(name, body))
  }

  // routes pill/:name?attrs
  def run(pill: String) = Action { implicit request =>
    pill match {
      case _ if pills contains pill => {
        pills(pill).run(request)
      }

      case _ => {
        Unauthorized("pill not found")
      }
    }
  }

  // initialize the thing
  add ("list") {request=>
    Ok(pills.keySet.mkString("\n"))
  }

}
