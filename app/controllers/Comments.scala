package controllers

import org.joda.time.DateTime
import com.mongodb.WriteResult
import admin.Audit
import admin.Config
import admin.SendEmail
import model.Api
import model.DoSec
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
import model.CommentStream
import org.bson.types.ObjectId

object Comments extends RazController with Logging {
  case class Habibi(s: String)
  val commentForm = Form {
    mapping (
      "content" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)))(Habibi.apply)(Habibi.unapply)
  }

  //  // authenticated means doing a task later
  //  def wikiComment (cat:String,name:String) = Action { implicit request =>
  //    (for (u <- auth)
  //      yield Ok(views.html.user.edUsername(chgusernameform.fill(u.userName, ""), auth.get))) getOrElse
  //      Unauthorized("Oops - how did you get here?")
  //  }

  def add(topic:String, what:String, oid:String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Hein?", Some("/wiki/id/" + topic)),
      {
        case Habibi(content) =>
          (for (
            au <- auth orCorr cNoAuth;
            w <- Wikis.findById(topic) orErr "Wiki topic not found"
          ) yield {
            val cs = model.Comments.findForWiki(new ObjectId(topic)).getOrElse(CommentStream(new ObjectId(topic), "Wiki").create)
            if (!cs.isDuplo(oid)) {
              cs.addComment(au, content, oid)
              // TODO send email to parent if kid
              au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildCommentWiki(parent, au, w.wid))
            } else log ("ERR_DUPLO_COMMENT")
            Redirect(routes.Wiki.showId(topic))
          }) getOrElse {
            Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
          }
      })
  }

  def reply(topic: String, what: String, replyId: String, oid:String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Hein?", Some("/wiki/id/" + topic)),
      {
        case Habibi(content) =>
          (for (
            u <- auth orCorr cNoAuth;
            cs <- model.Comments.findForWiki(new ObjectId(topic)) orErr ("No comments for this page...");
            parent <- cs.comments.find(_._id.toString == replyId) orErr ("Can't find the comment to reply to...")
          ) yield {
            cs.addComment(u, content, oid, Some(parent._id))
            Redirect(routes.Wiki.showId(topic))
          }) getOrElse {
            Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
          }
      })
  }

}
