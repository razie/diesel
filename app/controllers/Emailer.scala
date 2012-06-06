package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import model.Api
import model.Enc
import model.EncUrl
import model.RegdEmail
import model.Registration
import model.User
import model.UserTask
import model.Users
import model.Wikis
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import razie.Logging
import model.ParentChild
import admin.SendEmail
import model.DoSec
import model.WID

/** all emails sent by site */
object Emailer extends RazController with Logging {
  import model.Sec._

    def sendSupport(e:String, desc:String, details:String) {
      val html = """
Support reuested: <p>
<table>
<tr><td>email:</td><td>%s</td></tr>
<tr><td>desc:</td><td>%s</td></tr>
<tr><td>details:</td><td>%s</td></tr>
</table>        
<p>        
Thank you,<br>The RacerKidz
""".format(e, desc, details)

    admin.SendEmail.send (SUPPORT, SUPPORT, "Support request: "+desc, html)
  }

  def sendEmailChildUpdatedProfile(parent: User, child: User)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated his/her profile. <p>
You may want to review the changes with him/her. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated their profile", html1)
  }

  def sendEmailChildUpdatedPublicProfile(parent: User, child: User)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated his/her public profile. <p>
You may want to review the changes with him/her. <p>
Thank you,
<br>The RacerKidz
""".format(parent.ename, child.userName);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated their profile", html1)
  }

  def sendEmailUname(newUsername: String, u: User)(implicit request: Request[_]) = {
    val dt = DateTime.now().plusDays(1)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val ds1 = DoSec(hc1, dt)
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val ds2 = DoSec(hc2, dt)

    val html1 = """
User requested username change. <p>
old username %s<br>
new username %s
<p>
Action
<ul>
<li><a href="%s">Accept</a>
<li><a href="%s">Deny</a>
</ul>
<p>
Thank you, <br>The RacerKidz
""".format(u.userName, newUsername, ds1.secUrl, ds2.secUrl);

    SendEmail.send (SUPPORT, SUPPORT, "RacerKidz - username change request", html1)

    val html2 = """
Hello %s, <p>
You requested to change your username. We'll review your request and let you know. <p>
Changing username from %s -> %s <p>
Thank you, <br>The RacerKidz
""".format(u.ename, u.userName, newUsername)

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz - username change request", html2)

    Msg("Ok - we sent a request - we'll review it asap and let you know.",
      "Page", "home", Some(u))
  }

  def sendEmailUnameOk(newUsername: String, u: User)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Your username has been approved and changed to %s. Use it carelesly... no...wait... carefully! <p>
Thank you, <br>The RacerKidz
""".format(u.ename, u.userName);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :) username change approved", html1)
  }

  def sendEmailUnameDenied(newUsername: String, u: User)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Sorry - your new username request has been denied. Please try another username. We're sorry for the inconvenience. <p>
Note that you can use the default anonymized username to use the site. <p>
Thank you, <br>The RacerKidz
""".format(u.ename, u.userName);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :( username change denied", html1)
  }

  def sendEmailChildUpdatedWiki(parent: User, child: User, wiki: WID)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated a public topic: <a href="%s">%s:%s</a> <p>
You may want to review the changes and make sure no personal information is revealed. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName, "http://" + Config.hostport + "/wiki/"+wiki.cat+":"+wiki.name, wiki.cat, wiki.name);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated public topic", html1)
  }

  def sendEmailChildCommentWiki(parent: User, child: User, wiki: WID)(implicit request: Request[_]) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has commented on a public topic: <a href="%s">%s:%s</a> <p>
You may want to review the comments and make sure no personal information is revealed. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName, "http://" + Config.hostport + "/wiki/"+wiki.cat+":"+wiki.name, wiki.cat, wiki.name);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child commented on public topic", html1)
  }

}
