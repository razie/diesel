package controllers

import play.api.mvc.{Action, Request, Result}

import scala.collection.mutable

/** simple code pills controller
  *
  * pills are called with /pill/name
  */
object CodePills extends RazController {

  abstract class BasePill(val name: String) {
    def run (request:Request[_]) : Result
  }
  class Pill1(override val name: String, body: Request[_] => Result) extends BasePill(name) {
    def run (request:Request[_]) : Result = body(request)
  }
  class Pill2(override val name: String, body: Request[_] => String) extends BasePill(name) {
    def run (request:Request[_]) : Result = Ok(body(request)).as("application/text")
  }

  val pills = new mutable.HashMap[String, BasePill]()

  def add(name: String)(body: Request[_] => Result): Unit = {
    pills += (name -> new Pill1(name, body))
  }

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

  add ("list") {request=>
    Ok(pills.keySet.mkString("\n"))
  }

}
