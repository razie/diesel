/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import admin.SendEmail
import admin.VErrors
import db.REntity
import db.RMany
import db.ROne
import db.RTable
import model.FormStatus
import model.RK
import model.RacerKid
import model.RacerKidAssoc
import model.RacerKidz
import model.Reg
import model.RegKid
import model.RegStatus
import model.Regs
import model.Sec.EncryptedS
import model.User
import model.Users
import model.VolunteerH
import model.WID
import model.Wikis
import play.api.data.Form
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import razie.cout
import admin.Config
import db.RMongo
import play.api.mvc.AnyContent
import play.api.mvc.Result
import db.RDelete
import admin.Audit

/** per topic reg */
case class Modx1(
  wid: WID,
  curYear: String = Config.curYear) {
  lazy val kids = RMany[ModRkEntry]("curYear" -> curYear, "wpath" -> wid.wpath).toList
}

/** per topic reg */
@db.RTable
case class ModTmaStudent(
  rkId: ObjectId,
  role: String,
  note: String = "",
  curYear: String = Config.curYear,
  _id: ObjectId = new ObjectId) extends REntity[ModTmaStudent] {

  // optimize access to User object
  lazy val rk = rkId.as[RacerKid]
}

/** controller for club management */
object ModTma extends RazController with Logging {

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  def FAU(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T SEE PROFILE ")
  }

  import db.RMongo.as

  /** TODO find all badges, sorted for current user */
  def doeGetBadges = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Ok("")
  }

  /** TODO record progress - lesson complete */
  def doeLessonDone(lesson: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      //      ModRkEntry(new ObjectId(rkid), wid.wpath, role).create
      Ok("")
  }

  /** TODO record progress - challenge complete */
  def doeAssignmentComplete(ass: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      Ok("")
  }

  //============= scripting stuff

 object razscr {
    def dec(s: String) = {
      s.replaceAll("scrRAZipt", "script").replaceAll("%3B", ";").replaceAll("%2B", "+").replaceAll("%27", "'")
    }
  }

  def jsecho(echo: String) = Action { implicit request =>
    println("EEEEEEEEEEEECHO: " + echo)
    Ok(razscr dec echo).as("text/html").withHeaders(
      "Content-Security-Policy" -> "script-src http: 'unsafe-inline'",
      "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
      "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
  }

  val OneForm = Form("echof" -> nonEmptyText)

  def jsechof = Action { implicit request =>
    OneForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops! some error"),
      {
        case content =>
          Ok(razscr dec content).as("text/html").withHeaders(
            "Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
            "X-Content-Security-Policy" -> "unsafe-inline,unsafe-eval",
            "X-WebKit-CSP" -> "unsafe-inline,unsafe-eval")
      })
  }

  def lform(implicit request: Request[_]) = Form {
    tuple(
      "hh" -> text,
      "h" -> text,
      "c" -> text,
      "j" -> text)
  }

  def buildhtml(id: String) = Action { implicit request =>
    lform.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops! some error"),
      {
        case (hh, h, c, j) =>
          xbuildhtml(id, hh, h, c, j)
      })
  }

  def xbuildhtml(id: String, hh: String, h: String, c: String, j: String)(implicit request: Request[_]) = {
    val hhx = razscr dec hh
    val hx = razscr dec h
    val cx = razscr dec c
    val jx = razscr dec j

    val res = s"""
<html>
  <head>
$hhx
  <style>
$cx
</style>
</head>
<body onload="onLoad_$id()">
<script>
function onLoad_$id() {
$jx
}
</script>
$hx
</body>
</html>
"""

    // stupid security disallow XSS by requiring a referer 
    // TODO better security - 
    if (request.headers.get("Referer").exists(r =>
      (r matches s"""^http[s]?://${Config.hostport}.*""") ||
        (r matches s"""^http[s]?://${request.headers.get("X-Forwarded-Host").getOrElse("NOPE")}.*""")))
      Ok(res).as("text/html")
    else {
      Audit.logdb("ERR_BUILDHTML", "Referer", request.headers.get("Referer"), "Host", request.host)
      Ok("n/a").as("text/html")
    }
  }

  def buildhtml(id: String, hh: String, h: String, c: String, j: String) = Action { implicit request =>
    xbuildhtml(id, hh, h, c, j)
  }

  def playjs(id: String) = Action { implicit request =>
    lform.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops! some error"),
    {
      case (hh, h, c, j) =>
        Ok(views.html.fiddle.playjs("", Map(), (hh, h, c, j), auth))
    })
  }

}

