/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package controllers

import com.google.inject.Singleton
import razie.hosting.Website
import razie.wiki.admin.SendEmail

/** Diff and sync remote wiki copies */
@Singleton
class AdminTest extends AdminBase {

  def test() = FADR { implicit stok =>
    ROK.k admin { implicit stok => views.html.admin.adminTest() }
  }

  def testEmail() = FAD {
    implicit au =>
      implicit errCollector =>
        implicit request =>
          SendEmail.withSession(Website.realm(request)) { implicit ms =>
            val html1 = ms.text("testBody")
            ms.notif("razie@razie.com", ms.SUPPORT, "TEST email Notify It's " + System.currentTimeMillis(), html1)
            ms.send("razie@razie.com", ms.SUPPORT, "TEST email Send It's " + System.currentTimeMillis(), html1)
          }

          Ok("ok")
  }

  def proxyTest(what: String, url: String) = FAD { implicit au =>
    implicit errCollector =>
      implicit request =>
        what match {
          case "" => ""
          case "Joe" => "" //"Authorization": "Basic " + btoa('H-@Dec(stok.au.get.email)', + ":" + 'H-@Dec(stok.au
          // .get.pwd)')
          case "" => ""
        }
        Ok("")
  }
}
