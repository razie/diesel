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

    def sendSupport(e:String, desc:String, details:String) (implicit mailSession:Option[javax.mail.Session] = None) {
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

  def sendEmailChildUpdatedProfile(parent: User, child: User) (implicit mailSession:Option[javax.mail.Session] = None)= {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated his/her profile. <p>
You may want to review the changes with him/her. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated their profile", html1)
  }

  def sendEmailChildUpdatedPublicProfile(parent: User, child: User) (implicit mailSession:Option[javax.mail.Session] = None)= {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated his/her public profile. <p>
You may want to review the changes with him/her. <p>
Thank you,
<br>The RacerKidz
""".format(parent.ename, child.userName);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated their profile", html1)
  }

  def sendEmailRequest(to:String, validDays:Int, task:String, description:String, userNotif:Option[String], acceptUrl:String, denyUrl:String, u:User)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = DoSec(acceptUrl, dt)
    val ds2 = DoSec(denyUrl, dt)

    val html1 = """
%s
<p>
Action
<ul>
<li><a href="%s">Accept</a>
<li><a href="%s">Deny</a>
</ul>
<p>
Thank you, <br>The RacerKidz
""".format(description, ds1.secUrl, ds2.secUrl);

    SendEmail.send (to, SUPPORT, "RacerKidz - "+task, html1)

    userNotif.map(uhtml=>SendEmail.send (u.email.dec, SUPPORT, "RacerKidz - "+task, uhtml))
  }

  def sendEmailUname(newUsername: String, u: User)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val dt = DateTime.now().plusDays(3)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))

    val html1 = """
User requested username change. <p>
old username %s<br>
new username %s
""".format(u.userName, newUsername);

    val html2 = """
Hello %s, <p>
You requested to change your username. We'll review your request and let you know. <p>
Changing username from %s -> %s <p>
Thank you, <br>The RacerKidz
""".format(u.ename, u.userName, newUsername)

    sendEmailRequest (SUPPORT, 1, "username change request", html1, Some(html2), hc1, hc2, u)
  }

  def sendEmailUnameOk(newUsername: String, u: User)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Your username has been approved and changed to %s. Use it carelesly... no...wait... carefully! <p>
Thank you, <br>The RacerKidz
<p>P.S. If you'd like to request another username, you can do so from your profile.
""".format(u.ename, newUsername);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :) username change approved", html1)
  }

  def sendEmailUnameDenied(newUsername: String, u: User)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Sorry - your new username request has been denied. Please try another username. We're sorry for the inconvenience. <p>
Note that you can use the default anonymized username to use the site. <p>
Thank you, <br>The RacerKidz
""".format(u.ename, u.userName);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :( username change denied", html1)
  }

  def sendEmailLink(mod:User, u: User, club: String, how: String)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val dt = DateTime.now().plusDays(1)
    val hc1 = """/doe/wikie/linkAccept?expiry=%s&userId=%s&club=%s&how=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(club), how)
    val hc2 = """/doe/wikie/linkDeny?expiry=%s&userId=%s&club=%s&how=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(club), how)

    val html1 = """
User requested to become a member in club %s with role %s. <p>
  username %s<br>
  name %s
  email %s
""".format(club, how, u.userName, u.firstName + " " + u.lastName, u.email.dec);

    val html2 = """
Hello %s, <p>
You requested to join club %s was sent to the club's moderator. We'll review your request and let you know. <p>
Thank you, <br>The RacerKidz
""".format(u.ename, club)

    sendEmailRequest (mod.email.dec, 5, "club join request", html1, Some(html2), hc1, hc2, u)
  }

  def sendEmailLinkOk(u: User, club:String)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Your request to join club %s was approved by the moderator.
<p>
Thank you, <br>The RacerKidz
""".format(u.ename, club);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :) club membership approved", html1)
  }

  def sendEmailLinkDenied(u: User, club:String)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Sorry - your request to join club %s was denied by the club's moderator. We're sorry for the inconvenience. <p>
Thank you, <br>The RacerKidz
""".format(u.ename, club);

    SendEmail.send (u.email.dec, SUPPORT, "RacerKidz :( club membership denied", html1)
  }

  def sendEmailChildUpdatedWiki(parent: User, child: User, wiki: WID)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has updated a public topic: <a href="%s">%s:%s</a> <p>
You may want to review the changes and make sure no personal information is revealed. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName, "http://" + Config.hostport + "/wiki/"+wiki.cat+":"+wiki.name, wiki.cat, wiki.name);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child updated public topic", html1)
  }

  def sendEmailChildCommentWiki(parent: User, child: User, wiki: WID)(implicit mailSession:Option[javax.mail.Session] = None) = {
    val html1 = """
Hello %s, <p>
Your child, %s, has commented on a public topic: <a href="%s">%s:%s</a> <p>
You may want to review the comments and make sure no personal information is revealed. <p>
Thank you, <br>The RacerKidz
""".format(parent.ename, child.userName, "http://" + Config.hostport + "/wiki/"+wiki.cat+":"+wiki.name, wiki.cat, wiki.name);

    SendEmail.send (parent.email.dec, SUPPORT, "RacerKidz - child commented on public topic", html1)
  }

}
