/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import java.nio.file.{Files, Paths}
import model.Users
import org.joda.time.format.DateTimeFormat
import play.api.mvc.Action
import razie.audit.Audit
import razie.db.RazMongo
import razie.wiki.Services
import razie.wiki.admin.SendEmail

//@Singleton
class Admin extends AdminBase {

  private def escNL(s:String) = s.replaceAllLiterally("\n", " - ").replaceAllLiterally(",", " - ")

  /** realm users csv report */
  def realmUsers = FAU { implicit au => implicit errCollector => implicit request =>
      val stok = razRequest
        if(au.isAdmin || au.isMod) {
          if(request.getQueryString("format").contains("csv")) {
            val (headers, data) = usersData(stok.realm)
              Ok(
                headers.mkString(",") +
                    "\n" +
                    data.map(
                      _.map(escNL)
                          .mkString(",")
                    ).mkString("\n")
              ).as("text/csv")
          } else {
            ROK.s admin { implicit stok =>
              views.html.admin.adminRealmUsers(stok.realm)
            }
          }
        } else {
          unauthorized("CAN'T")
        }
  }

  private def usersData(realm: String): (List[String], List[List[String]]) = {
    val users = Users.findUsersForRealm(realm)
    val cols = "userName,_id,date,email,firstName,lastName,yob,extId,perms".split(",").toList

    // actual rows L[L[String]]
    val res = users.map(_.forRealm(realm)).map { u =>
      List(
        u.userName,
        u._id.toString,
        u.realmSet.get(realm).flatMap(_.crDtm).map(d => DateTimeFormat.forPattern("yyyy-MM-dd").print(d)).mkString,
        u.emailDec,
        u.firstName,
        u.lastName,
        u.yob.toString,
        u.profile.flatMap(_.newExtLinks.find(_.realm == realm)).map {_.extAccountId}.mkString,
        u.perms.mkString(" ") // not ,
      )
    }.toList

    (cols, res)
  }

  // admin/page/:page
  def show(page: String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      page match {
        case "reloadurlmap" => {
          Services.config.reloadUrlMap
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "send10" => {
          SendEmail.emailSender ! SendEmail.CMD_SEND10
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "stopEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_STOPEMAILS
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "resendEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_RESEND
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "tickEmails" => {
          SendEmail.emailSender ! SendEmail.CMD_TICK
          ROK.s admin { implicit stok => views.html.admin.adminIndex("") }
        }

        case "wikidx" => ROK.s admin { implicit stok => views.html.admin.adminWikiIndex() }
        case "db" => ROK.s admin { implicit stok => views.html.admin.adminDb() }
        case "index" => ROK.s admin { implicit stok => views.html.admin.adminIndex("") }

//        case "users" => ROK.s admin { implicit stok => views.html.admin.adminUsers() }
        case "users" => ROK.s admin { implicit stok =>
          val realm = "*" // should these be only for current realm? But only admins can see this specific page...
          views.html.admin.adminUsers(
            Users.findUsersForRealm(realm).map(Users.asDBO)
          )}

        case "init.db.please" => {
          if ("yeah" == System.getProperty("devmode") || !RazMongo("User").exists) {
            admin.Init.initDb()
            Redirect("/")
          } else Msg("Nope - hehe")
        }

        case _ => {
          Audit.missingPage(page);
          Redirect("/")
        }
      }
  }

  def showImage(file: String) = Action { implicit request =>
    log("Showing image: " + file)
    val f = Files.readAllBytes(Paths.get("/" + file))
    Ok(f).as("image/jpeg")
  }
}

