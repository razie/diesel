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
import play.api.data.Forms._
import play.api.data._
import play.api.mvc._
import play.api._
import razie.Logging
import model.ParentChild
import admin.SendEmail
import model.DoSec
import admin.MailSession
import model.WID
import model.WikiEntry
import model.Wikis
import admin.Services

/** all emails sent by site */
object Emailer extends RazController with Logging {
  import model.Sec._

  def RK = admin.Config.sitecfg("RacerKidz").getOrElse("RacerKidz")

  def text(name: String) = Wikis.find("Admin", "template-emails").flatMap(_.section("template", name)).map(_.content).getOrElse("[ERROR] can't find Admin template-emails: " + name)

  def sendSupport(e: String, desc: String, details: String)(implicit mailSession: MailSession) {
    val html = text("supportrequested").format(e, desc, details)

    admin.SendEmail.send(SUPPORT, SUPPORT, "Support request: " + desc, html)
  }

  def sendEmailChildUpdatedProfile(parent: User, child: User)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedprofile").format(parent.ename, child.userName);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child updated their profile", html1)
  }

  def sendEmailChildUpdatedPublicProfile(parent: User, child: User)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedpublicprofile").format(parent.ename, child.userName);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child updated their profile", html1)
  }

  def sendEmailRequest(to: String, validDays: Int, task: String, description: String, userNotif: Option[String], acceptUrl: String, denyUrl: String, u: User)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = DoSec(acceptUrl, dt)
    val ds2 = DoSec(denyUrl, dt)

    val html1 = text("emailrequest").format(description, ds1.secUrl, ds2.secUrl);

    SendEmail.send(to, SUPPORT, "RacerKidz - " + task, html1)

    userNotif.map(uhtml => SendEmail.send(u.email.dec, SUPPORT, RK + " - " + task, uhtml))
  }

  def sendEmailUname(newUsername: String, u: User)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(3)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))

    val html1 = text("usernamechangerequest1").format(u.userName, newUsername);

    val html2 = text("usernamechangerequest2").format(u.ename, u.userName, newUsername)

    sendEmailRequest(SUPPORT, 1, "username change request", html1, Some(html2), hc1, hc2, u)
  }

  def sendEmailUnameOk(newUsername: String, u: User)(implicit mailSession: MailSession) = {
    val html1 = text("unameok").format(u.ename, newUsername);

    SendEmail.send(u.email.dec, SUPPORT, RK + " :) username change approved", html1)
  }

  def sendEmailUnameDenied(newUsername: String, u: User)(implicit mailSession: MailSession) = {
    val html1 = text("unamedenied").format(u.ename, u.userName);

    SendEmail.send(u.email.dec, SUPPORT, RK + " :( username change denied", html1)
  }

  def sendEmailLink(mod: User, u: User, club: String, how: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(1)
    val hc1 = """/doe/wikie/linkAccept?expiry=%s&userId=%s&club=%s&how=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(club), how)
    val hc2 = """/doe/wikie/linkDeny?expiry=%s&userId=%s&club=%s&how=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(club), how)

    val html1 = text("linkrequest1").format(club, how, u.userName, u.firstName + " " + u.lastName, u.email.dec);

    val html2 = text("linkrequest2").format(u.ename, club)

    sendEmailRequest(mod.email.dec, 5, "club join request", html1, Some(html2), hc1, hc2, u)
  }

  def sendEmailFollowerLink(to: String, topic: WID, comment: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(10)
    val hc1 = """/wikie/linkFollower3/%s/%s/%s/%s""".format(EncUrl(dt.toString), to.enc, (if (comment.length > 0) comment else "Enjoy!").encUrl, topic.wpath)
    val ds1 = DoSec(hc1, dt)

    val html1 = text("followerlinkrequest").format(topic.name, ds1.secUrl, comment);

    SendEmail.notif(to, SUPPORT, RK + " - activate subscription", html1)
  }

  def sendEmailFollowerNewTopic(to: String, commenter: User, wiki: WID, wpost: WikiEntry, comment: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(30)
    val hc2 = "http://" + Config.hostport + """/wikie/unlinkFollower4/%s/%s/%s""".format(EncUrl(dt.toString), to.enc, wiki.wpath)

    val html1 = text("followernewtopic").format(commenter.userName, wiki.url, wiki.cat, wiki.name, wpost.label, hc2, comment);

    SendEmail.notif(to, SUPPORT, RK + " - new " + wpost.wid.cat + " created", html1)
  }

  def sendEmailLinkOk(u: User, club: String)(implicit mailSession: MailSession) = {
    val html1 = text("linkok").format(u.ename, club);

    SendEmail.send(u.email.dec, SUPPORT, RK + " :) club membership approved", html1)
  }

  def sendEmailLinkDenied(u: User, club: String)(implicit mailSession: MailSession) = {
    val html1 = text("linkdenied").format(u.ename, club);

    SendEmail.send(u.email.dec, SUPPORT, RK + " :( club membership denied", html1)
  }

  def sendEmailChildUpdatedWiki(parent: User, child: User, wiki: WID)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedwiki").format(parent.ename, child.userName, wiki.url, wiki.cat, wiki.name);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child updated public topic", html1)
  }

  def sendEmailChildCommentWiki(parent: User, child: User, wiki: WID)(implicit mailSession: MailSession) = {
    val html1 = text("childcommentedwiki").format(parent.ename, child.userName, wiki.url, wiki.cat, wiki.name);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child commented on public topic", html1)
  }

  def sendEmailNewComment(to: User, commenter: User, wiki: WID)(implicit mailSession: MailSession) = {
    val html1 = text("newcomment").format(to.ename, commenter.userName, wiki.url, wiki.cat, wiki.name);

    SendEmail.notif(to.email.dec, SUPPORT, RK + " - new comment posted", html1)
  }

  def sendEmailNewTopic(to: User, commenter: User, wiki: WID, wpost: WikiEntry)(implicit mailSession: MailSession) = {
    val html1 = text("newtopic").format(to.ename, commenter.userName, wiki.url, wiki.cat, wiki.name, wpost.label);

    SendEmail.notif(to.email.dec, SUPPORT, RK + " - new " + wiki.cat + " created", html1)
  }

  def sendEmailNeedQuota(uName: String, uId: String)(implicit mailSession: MailSession) = {
    val html1 = text("needquota").format(uName + " - " + uId, "http://" + Config.hostport + "/admin/user/" + uId);

    SendEmail.send(SUPPORT, SUPPORT, RK + " - NEEDS QUOTA", html1)
  }

  def sendEmailClubRegStart(u: User, club: String, link: String)(implicit mailSession: MailSession) = {
    val html1 = text("regstart").format(u.ename, club, "http://" + Config.hostport + link);
    SendEmail.send(u.email.dec, SUPPORT, club + " - registration forms", html1)
  }

  def sendEmailFormSubmitted(reviewer: String, owner: User, link: String)(implicit mailSession: MailSession) = {
    val html1 = text("formSubmitted").format(reviewer, owner.ename, "http://" + Config.hostport + link);
    SendEmail.send(reviewer, SUPPORT, RK + " - form submitted", html1)
  }

  def sendEmailFormRejected(reviewer: User, owner: User, cname: String, link: String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("formRejected").format(owner.ename, "http://" + Config.hostport + link, msg);
    SendEmail.send(owner.email.dec, SUPPORT, cname + " - form rejected ", html1)
  }

  def tellRaz(what: String, args: Any*)(implicit mailSession: MailSession) = {
    SendEmail.send("razie@razie.com", SUPPORT, RK + " - " + what, args.mkString("\n"))
  }

  /** see SendEmail.withSession - email is sent in a background thread */
  def withSession[C](body: (MailSession) => C): C = SendEmail.withSession(body)

  /** not really needed - email is sent on a background thread anyhow */
  def laterSession[C](body: (MailSession) => Unit): Unit = Services.alli ! new Emailing(body)
}

/** used to send a set of emails later */
class Emailing(body: (MailSession) => Unit) {
  def send {
    Emailer.withSession (body)
  }
}
