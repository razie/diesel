package controllers

import admin.{Config, MailSession, SendEmail, Services}
import model.{DoSec, Enc, EncUrl, User, WID, WikiEntry, Wikis}
import org.joda.time.DateTime
import razie.Logging

/** all emails sent by site */
object Emailer extends RazController with Logging {
  import model.Sec._

  def RK = admin.Config.sitecfg("RacerKidz").getOrElse("RacerKidz")

  def text(name: String) = Wikis.find("Admin", "template-emails").flatMap(_.section("template", name)).map(_.content).getOrElse("[ERROR] can't find Admin template-emails: " + name)

  def sendSupport(subj:String, name:String, e: String, desc: String, details: String, page:String)(implicit mailSession: MailSession) {
    val html = text("supportrequested").format(name, e, desc, details, page)

    admin.SendEmail.send(SUPPORT, SUPPORT, subj+": " + desc, html)
  }

  def sendEmailChildUpdatedProfile(parent: User, child: User)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedprofile").format(parent.ename, child.userName);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child updated their profile", html1)
  }

  def sendEmailChildUpdatedPublicProfile(parent: User, child: User)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedpublicprofile").format(parent.ename, child.userName);

    SendEmail.send(parent.email.dec, SUPPORT, RK + " - child updated their profile", html1)
  }

  def noteShared(from:String, toEmail:String, toName:String, url:String)(implicit mailSession: MailSession) = {
    val html1 = text("noteShared").format(toName, from, url);

    SendEmail.send(toEmail, SUPPORT, "No Folders - note shared with you", html1)
  }

  def circled(from:String, toEmail:String, toName:String, url:String)(implicit mailSession: MailSession) = {
    val html1 = text("circled").format(toName, from, url);

    SendEmail.send(toEmail, SUPPORT, "No Folders - added to circle", html1)
  }

  /** invite to join on notes */
  def makeNotesInvite(toName:String, validDays: Int, acceptUrl: String, u: User) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = DoSec(acceptUrl, Some("www.nofolders.net"), true, dt)
    text("notesInvite").format(toName, ds1.secUrl, u.ename)
  }

  def sendEmailRequest(to: String, validDays: Int, task: String, description: String, userNotif: Option[String], acceptUrl: String, denyUrl: String, u: User)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = DoSec(acceptUrl, None, true, dt)
    val ds2 = DoSec(denyUrl, None, true, dt)

    val html1 = text("emailrequest").format(description, ds1.secUrl, ds2.secUrl);

    SendEmail.send(to, SUPPORT, "RacerKidz - " + task, html1)

    userNotif.map(uhtml => SendEmail.send(u.email.dec, SUPPORT, RK + " - " + task, uhtml))
  }

  def sendEmailUname(newUsername: String, u: User, notifyUser:Boolean = true)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(3)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))

    val html1 = text("usernamechangerequest1").format(u.userName, newUsername);

    val html2 = text("usernamechangerequest2").format(u.ename, u.userName, newUsername)

    sendEmailRequest(SUPPORT, 1, "username change request", html1, (if(notifyUser) Some(html2) else None), hc1, hc2, u)
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
    val ds1 = DoSec(hc1, None, true, dt)

    val html1 = text("followerlinkrequest").format(topic.name, ds1.secUrl, comment);

    SendEmail.notif(to, SUPPORT, RK + " - activate subscription", html1)
  }

  def sendEmailFollowerNewTopic(to: String, commenter: User, parent: WID, wpost: WikiEntry, comment: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(30)
    val hc2 = "http://" + Config.hostport + """/wikie/unlinkFollower4/%s/%s/%s""".format(EncUrl(dt.toString), to.enc, parent.wpath)

    val html1 = text("followernewtopic").format(commenter.userName, parent.url, parent.cat, parent.name, wpost.label, hc2, comment);

    SendEmail.notif(to, SUPPORT, RK + " - new " + wpost.wid.cat + " in " + parent.name + " : " + wpost.label, html1)
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

    SendEmail.notif(to.email.dec, SUPPORT, RK + " - new " + wpost.wid.cat + " in " + wiki.name + " : " + wpost.label, html1)
  }

  def sendEmailNeedQuota(uName: String, uId: String)(implicit mailSession: MailSession) = {
    val html1 = text("needquota").format(uName + " - " + uId, "http://" + Config.hostport + "/razadmin/user/" + uId);

    SendEmail.send(SUPPORT, SUPPORT, RK + " - NEEDS QUOTA", html1)
  }

  def sendEmailClubRegStart(u: User, club: String, link: String)(implicit mailSession: MailSession) = {
    val html1 = text("regstart").format(u.ename, club, "http://" + Config.hostport + link);
    SendEmail.send(u.email.dec, SUPPORT, club + " - registration forms", html1)
  }

  def sendEmailClubRegHelp(u: User, club: String, link: String, msg:String)(implicit mailSession: MailSession) = {
    val html1 = text("reghelp").format(u.ename, club, msg, "http://" + Config.hostport + link);
    SendEmail.send(u.email.dec, SUPPORT, club + " - registration help", html1)
  }

  def sendEmailFormSubmitted(reviewer: String, owner: User, link: String)(implicit mailSession: MailSession) = {
    val html1 = text("formSubmitted").format(reviewer, owner.ename, "http://" + Config.hostport + link);
    SendEmail.send(reviewer, SUPPORT, RK + " - form submitted", html1)
  }

  def sendEmailFormRejected(reviewer: User, owner: User, cname: String, link: String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("formRejected").format(owner.ename, "http://" + Config.hostport + link, msg);
    SendEmail.send(owner.email.dec, SUPPORT, cname + " - form rejected ", html1)
  }

  def sendEmailFormsAccepted(reviewer: User, owner: User, cname: String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("formsAccepted").format(owner.ename, msg);
    SendEmail.send(owner.email.dec, SUPPORT, cname + " - all forms accepted", html1)
  }

  def tellRaz(what: String, args: Any*)(implicit mailSession: MailSession) = {
    tell("razie@razie.com", what, args:_*)
  }

  def tell(who:String, what: String, args: Any*)(implicit mailSession: MailSession) = {
    SendEmail.notif(who, SUPPORT, RK + " - " + what, args.mkString("\n"))
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

