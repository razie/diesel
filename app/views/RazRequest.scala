/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package views

import model.{Website, User}
import play.api.Application
import play.api._
import play.api.mvc._
import razie.wiki.{WikiConfig, Alligator, EncryptService, Services}

class RazRequest (val req:Request[_]) {
  def oreq = Some(req)
  lazy val au = Services.auth.authUser (req).asInstanceOf[Option[User]]
  lazy val realm = Website.getRealm (req)
  lazy val stok = new controllers.StateOk(Seq(), realm, au, Some(req))
}


