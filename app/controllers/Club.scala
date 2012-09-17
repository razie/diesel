package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import admin.SendEmail
import model.Api
import model.DoSec
import model.Sec._
import model.Enc
import model.EncUrl
import model.ParentChild
import model.RegdEmail
import model.Registration
import model.User
import model.UserTask
import model.Users
import model.Wikis
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.data.Form
import play.api.mvc.Request
import play.api.mvc.Action
import razie.Logging
import razie.Snakk
import model.Base64
import model.Perm
import admin._
import model.WID
import model.UserTasks

object Club extends RazController with Logging {

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // create profile
  case class Member(
    firstName: String,
    lastName: String,
    email: String,
    userType: String,
    pass: String,
    yob: Int,
    address: String)

  def doeMembers1 (uid:String) = //TODO
  Action { implicit request =>
    Ok(views.html.club.members1(auth.get))
  }

  //  def crMembers (m:List[Member]) = {
  //    for (
  //        member <- m
  //        )
  //      Profile.createUser (member)
  //    
  //  }

}
