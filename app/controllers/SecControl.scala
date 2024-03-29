/**
 * ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import play.api.mvc.Action
import razie.wiki.admin.SecLink

/** executing secured one time links (like confirm email, approve etc)
  *
  * @see razie.wiki.admin.DoSec
  */
class SecControl extends RazControllerBase {

  /** user does not have to be logged in */
  def doeSec(whats: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    clog << "doeSec " + whats
    (for (
      ds <- SecLink.find(whats) orErr "[already used]";
      x <- (if (ds.expiry.isAfterNow) Some(true) else None) orErr ("[expired]")
    ) yield {
        ds.done
        Redirect(ds.link).flashing(SecLink.HEADER -> ds.id)
      }) getOrElse
      Msg2("The link has expired. Please try again. <br><small>" + errCollector.mkString + "</small>", Some("/"))
  }
}
