/**
 * the mod rk is to allow parents to register RK/kidz for any topic / events etc
 */
package controllers

import admin.Config
import model.RacerKid
import org.bson.types.ObjectId
import play.api.mvc.{Action, Request}
import razie.Logging
import razie.db.{RTable, REntity, RMany}
import razie.wiki.admin.Audit
import razie.wiki.model.WID

/** per topic reg */
case class Modx1(
  wid: WID,
  curYear: String = Config.curYear) {
  lazy val kids = RMany[ModRkEntry]("curYear" -> curYear, "wpath" -> wid.wpath).toList
}

/** per topic reg */
@RTable
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

  import play.api.data.Forms._
  import play.api.data._

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

