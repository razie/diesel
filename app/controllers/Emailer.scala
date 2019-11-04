/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import mod.snow.RacerKid
import model.{TPersonInfo, User}
import org.joda.time.DateTime
import razie.Logging
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.exec.EESnakk
import razie.diesel.engine.nodes.Attrs
import razie.diesel.expr.StaticECtx
import razie.tconf.Visibility
import razie.wiki.admin.{MailSession, SecLink, SendEmail}
import razie.wiki.model._
import razie.wiki.{Config, Enc, EncUrl, Services}

/** all emails sent by site
  *
  * Emails are formatted using a set of templates configured as a wiki topic "Admin:template-emails".
  *
  * You can overwrite it in your realm.
  */
object Emailer extends RazController with Logging {
  import razie.wiki.Sec._

  def hostport = Services.config.hostport

  /** name of website, used in subject */
  def RK(implicit mailSession: MailSession) = mailSession.RK
  def SUPPORT(implicit mailSession: MailSession) = mailSession.SUPPORT

  /** bottom section on each email */
  def bottom(implicit mailSession: MailSession) = mailSession.bottom

  /** email body section template */
  def text(name: String)(implicit mailSession: MailSession) = mailSession.text(name)

  /** make it simpler to call - we need to preserve order */
  def expand (name:String, attrs:List[(String,String)])(implicit mailSession: MailSession) : String =
    expanda(name, attrs.map(t=> P(t._1, t._2)))

  /** find and expand email template with name */
  def expanda (name:String, attrs:Attrs)(implicit mailSession: MailSession): String  = {
    val str = text(name)
    if(str contains "${")
        EESnakk.prepStr2(str, attrs)(new StaticECtx(attrs))
    else
      str.format(attrs.map(_.currentStringValue):_*)
  }

  def noteShared(from:String, toEmail:String, toName:String, url:String)(implicit mailSession: MailSession) = {
    val html1 = text("noteShared").format(toName, from, url);

    mailSession.send(toEmail, SUPPORT, "No Folders - note shared with you", html1)
  }

  def circled(from:String, toEmail:String, toName:String, url:String)(implicit mailSession: MailSession) = {
    val html1 = text("circled").format(toName, from, url);

    mailSession.send(toEmail, SUPPORT, "No Folders - added to circle", html1)
  }

  /** invite to join on notes */
  def makeNotesInvite(toName:String, validDays: Int, acceptUrl: String, u: User)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = SecLink(acceptUrl, Some("notes.razie.com"), 1, dt)
    text("notesInvite").format(toName, ds1.secUrl, u.ename)
  }

  def sendEmailRequest(to: String, validDays: Int, task: String, description: String, userNotif: Option[String], acceptUrl: String, denyUrl: String, u: User)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(validDays)
    val ds1 = SecLink(acceptUrl, None, 1, dt)
    val ds2 = SecLink(denyUrl, None, 1, dt)

    val html1 = text("emailrequest").format(description, ds1.secUrl, ds2.secUrl);

    mailSession.send(to, SUPPORT, "RacerKidz - " + task, html1)

    userNotif.map(uhtml => mailSession.send(u.emailDec, SUPPORT, RK + " - " + task, uhtml))
  }

  def sendEmailUname(newUsername: String, u: User, notifyUser:Boolean = true)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(3)
    val hc1 = """/doe/profile/unameAccept?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))
    val hc2 = """/doe/profile/unameDeny?expiry=%s&userId=%s&newusername=%s""".format(EncUrl(dt.toString), u.id, Enc.toUrl(newUsername))

    // to admin
    val html1 = text("usernamechangerequest1").format(u.userName, newUsername);

    // to user
    val html2 = text("usernamechangerequest2").format(u.ename, u.userName, newUsername)

    sendEmailRequest(SUPPORT, 1, "Welcome!", html1, (if(notifyUser) Some(html2) else None), hc1, hc2, u)
  }

  def sendEmailUnameOk(newUsername: String, u: User)(implicit mailSession: MailSession) = {
    val html1 = text("unameok").format(u.ename, newUsername);

    mailSession.send(u.emailDec, SUPPORT, RK + " :) username change approved", html1)
  }

  def sendEmailUnameDenied(newUsername: String, u: User)(implicit mailSession: MailSession) = {
    val html1 = text("unamedenied").format(u.ename, u.userName);

    mailSession.send(u.emailDec, SUPPORT, RK + " :( username change denied", html1)
  }

  def sendEmailLink(mod: User, u: User, club: WID, how: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(1)
    val hc1 = """/doe/wikie/linkAccept/%s?expiry=%s&userId=%s&how=%s""".format(club.wpath, EncUrl(dt.toString), u.id, how)
    val hc2 = """/doe/wikie/linkDeny/%s?expiry=%s&userId=%s&how=%s""".format(club.wpath, EncUrl(dt.toString), u.id, how)

    val html1 = text("linkrequest1").format(club, how, u.userName, u.firstName + " " + u.lastName, u.emailDec, u.prefs.get("about").mkString);

    val html2 = text("linkrequest2").format(u.ename, club)

    sendEmailRequest(mod.emailDec, 5, "club join request", html1, Some(html2), hc1, hc2, u)
  }

  def sendEmailFollowerLink(to: String, topic: WID, comment: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(10)
    val hc1 = """/wikie/linkFollower3/%s/%s/%s/%s""".format(EncUrl(dt.toString), to.enc, (if (comment.length > 0) comment else "Enjoy!").encUrl, topic.wpathFull)
    val ds1 = SecLink(hc1, None, 1, dt)

    val html1 = text("followerlinkrequest").format(topic.name, ds1.secUrl, comment);

    mailSession.notif(to, SUPPORT, RK + " - activate subscription", html1)
  }

  def sendEmailFollowerNewTopic(to: String, commenter: User, parent: WID, wpost: WikiEntry, comment: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(30)
    val hc2 = "http://" + hostport + """/wikie/unlinkFollower4/%s/%s/%s""".format(EncUrl(dt.toString), to.enc, parent.wpath)

    val html1 = text("followernewtopic").format(commenter.userName, parent.url, parent.cat, parent.name, wpost.label, hc2, comment);

    mailSession.notif(to, SUPPORT, /*RK + " - new " + wpost.wid.cat + " in " + */ parent.name + " : " + wpost.label, html1)
  }

  def sendEmailLinkOk(u: User, club: String, welcome:String)(implicit mailSession: MailSession) = {
    val html1 = text("linkok").format(u.ename, club, welcome);

    mailSession.send(u.emailDec, SUPPORT, RK + s" - Welcome to ${club}", html1)
  }

  def sendEmailLinkDenied(u: User, club: WID)(implicit mailSession: MailSession) = {
    val html1 = text("linkdenied").format(u.ename, club.name);

    mailSession.send(u.emailDec, SUPPORT, RK + " :( club membership denied", html1)
  }

  def sendEmailNewComment(to: User, commenter: User, wid: WID)(implicit mailSession: MailSession) = {
    val html1 = expand("newcomment", List(
      "to" -> to.ename,
      "commenter" -> commenter.userName,
      "url" -> wid.url,
      "cat" -> wid.cat,
      "name" -> wid.name))

    mailSession.notif(to.emailDec, SUPPORT, RK + " - new comment posted", html1)
  }

  def sendEmailNewWiki(to: User, commenter: User, wpost: WikiEntry)(implicit mailSession: MailSession) = {
    val avail = "Visibility: " + (wpost.visibility match {
      case Visibility.PUBLIC => "public"
      case Visibility.MEMBER => "members only"
      case Visibility.BASIC => "paid members only"
      case Visibility.GOLD => "black/gold members only"
      case Visibility.PLATINUM => "platinum members (racing level) only"
      case Visibility.UNOBTANIUM => "unobtanium members (master level) only"
      case _ => s"? (${wpost.visibility})"
    })

    val html1 = text("newwiki").format(to.ename, commenter.userName,
      wpost.getLabel, wpost.getDescription, wpost.wid.url, avail);

    mailSession.notif(to.emailDec, SUPPORT, RK + " - new " + wpost.wid.cat + " : " + wpost.getLabel, html1)
  }

  def sendEmailNewPost(to: User, commenter: User, wiki: WikiEntry, wpost: WikiEntry)(implicit mailSession: MailSession) = {
    val html1 = text("newtopic").format(to.ename, commenter.userName, wiki.wid.url, wiki.wid.cat, wiki.getLabel,
      wpost.getLabel, wpost.getDescription, wpost.wid.url);

    mailSession.notif(to.emailDec, SUPPORT, RK + " - new " + wpost.wid.cat + " in " + wiki.getLabel + " : " + wpost.getLabel, html1)
  }

  def sendEmailNeedQuota(uName: String, uId: String)(implicit mailSession: MailSession) = {
    val html1 = text("needquota").format(uName + " - " + uId, "http://" + hostport + "/razadmin/user/" + uId);

    mailSession.notif(SUPPORT, SUPPORT, RK + " - NEEDS QUOTA", html1)
  }

  def sendEmailInvited(u: User, rk:RacerKid, role: String, link: String)(implicit mailSession: MailSession) = {
    val dt = DateTime.now().plusDays(30)
    val ds1 = SecLink(link, None, 1, dt)
    val html1 = text("clubInvite").format(rk.info.firstName, ds1.secUrl, u.ename)
    mailSession.send(rk.info.email, SUPPORT, "Invitation to join " + u.userName, html1)
  }

  def sendEmailNewNote(what:String, to: TPersonInfo, by:User, link: String, role:String, memo:String)(implicit mailSession: MailSession) = {
    val sub = s" - $what note for you"
    val r = if(role.length > 0) role else "form"
    val html1 = text("newNote").format(to.ename, r, by.ename, link, memo);
    mailSession.notif(to.emailDec, SUPPORT, RK + sub, html1)
  }


  //=========================================== children


  def sendEmailChildUpdatedWiki(parent: User, child: User, wiki: WID)(implicit mailSession: MailSession) = {
    val html1 = text("childupdatedwiki").format(parent.ename, child.userName, wiki.url, wiki.cat, wiki.name);

    mailSession.notif(parent.emailDec, SUPPORT, RK + " - child updated public topic", html1)
  }

  def sendEmailChildCommentWiki(parent: User, child: User, wiki: WID)(implicit mailSession: MailSession) = {
    val html1 = text("childcommentedwiki").format(parent.ename, child.userName, wiki.url, wiki.cat, wiki.name);

    mailSession.notif(parent.emailDec, SUPPORT, RK + " - child commented on public topic", html1)
  }


  //=========================================== clubs and forms


  def sendEmailClubRegStart(u: User, club: String, link: String)(implicit mailSession: MailSession) = {
    val html1 = text("regstart").format(u.ename, club, "http://" + hostport + link);
    mailSession.send(u.emailDec, SUPPORT, club + " - registration forms", html1)
  }

  def sendEmailClubRegHelp(u: User, club: String, link: String, msg:String)(implicit mailSession: MailSession) = {
    val html1 = text("reghelp").format(u.ename, club, msg, "http://" + hostport + link);
    mailSession.send(u.emailDec, SUPPORT, club + " - registration help", html1)
  }

  def sendEmailFormAssigned(to: TPersonInfo, by:User, link: String, role:String)(implicit mailSession: MailSession) = {
    val sub = if(role.length > 0) s" - $role assigned to you" else " - form assigned"
    val r = if(role.length > 0) role else "form"
    val html1 = text("formAssigned").format(to.ename, r, by.ename, link);
    mailSession.notif(to.emailDec, SUPPORT, RK + sub, html1)
  }

  def sendEmailFormNotify(to: User, by: User, link: String, role:String="")(implicit mailSession: MailSession) = {
    val r = if(role.length > 0) role else "form"
    val html1 = text("formNotify").format(to.ename, r, by.ename, "http://" + hostport + link);
    val sub = if(role.length > 0) s" - $role completed for you" else " - form submitted"
    mailSession.notif(to.emailDec, SUPPORT, RK + sub, html1)
  }

  def sendEmailFormSubmitted(reviewer: String, owner: User, link: String, role:String="")(implicit mailSession: MailSession) = {
    val html1 = text("formSubmitted").format(reviewer, owner.ename, "http://" + hostport + link);
    val sub = if(role.length > 0) s" - $role completed for you" else " - form submitted"
    mailSession.notif(reviewer, SUPPORT, RK + sub, html1)
  }

  def sendEmailFormRejected(reviewer: User, owner: User, cname: String, link: String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("formRejected").format(owner.ename, "http://" + hostport + link, msg);
    mailSession.notif(owner.emailDec, SUPPORT, cname + " - form rejected ", html1)
  }

  def sendEmailFormsAccepted(reviewer: User, owner: User, cname: String, fee:String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("formsAccepted").format(owner.ename, fee, msg);
    mailSession.send(owner.emailDec, SUPPORT, cname + " - all forms accepted", html1)
  }

  def sendEmailInvitePro(from: User, name:String, email: String, msg: String)(implicit mailSession: MailSession) = {
    val html1 = text("invitePro").format(name, from.fullName, msg);
    mailSession.send(email, SUPPORT, "Invitation from "+from.fullName, html1)
  }

  def tellRaz(what: String, args: Any*)(implicit mailSession: MailSession) = {
    tell("razie@razie.com", what, args:_*)
  }

  def tellAdmin(what: String, args: Any*)(implicit mailSession: MailSession) = {
    tell(Config.adminEmail, what, args:_*)
  }

  def tellSiteAdmin(what: String, args: Any*)(implicit mailSession: MailSession) = {
    tell(Config.adminEmail, what, args:_*)
  }

  def tell(who:String, what: String, args: Any*)(implicit mailSession: MailSession) = {
    mailSession.notif(who, SUPPORT, RK + " - " + what, args.mkString("\n"))
  }

  /** see SendEmail.withSession - email is sent in a background thread */
  def withSession[C](realm:String)(body: (MailSession) => C): C =
    SendEmail.withSession(realm)(body)
}


