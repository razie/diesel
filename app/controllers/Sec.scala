/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.VError
import model.DoSec
import play.api.mvc.Action
import razie.cdebug

/** executing secured one time links */
object Sec extends RazControllerBase {

  def doeSec(whats: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    cdebug << "doeSec " + whats
    (for (
      ds <- DoSec.find(whats) orErr "cantfindit";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("expired")
    ) yield {
      ds.done
      Redirect(ds.link)
    }) getOrElse
      Msg2("Link is invalid/expired... " + errCollector.mkString, Some("/"))
  }
}
