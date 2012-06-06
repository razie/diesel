package controllers

import org.bson.types.ObjectId

import admin.Corr
import admin.VError
import model.CommentStream
import model.Comments
import model.Perm
import model.User
import model.Wikis
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Form
import play.api.mvc.Action
import razie.Logging

object Comment extends RazController with Logging {
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

  /** is mine or i am amdin */
  def canEdit (comm:model.Comment, auth:Option[User]) = {
    auth.map(au=>comm.userId == au._id || au.hasPerm(Perm.adminDb)).getOrElse(false)
  }
  
  def edit(cat:String, name:String, cid: String) = Action{ implicit request =>
    implicit val errCollector = new VError()

        (for (
          au <- auth orCorr new Corr("not logged in", "Sorry - need to log in"); //cNoAuth;
          comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
          can <- canEdit(comm, auth) orErr ("can only edit your comments")
        ) yield {
          Ok (views.html.comments.commEdit(cat, name, cid, commentForm.fill(Habibi(comm.content)), auth))
        }) getOrElse
          noPerm("?", "?", errCollector.mkString)
  }

  def save(cat:String, name:String, cid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.comments.commEdit(cat, name, cid, formWithErrors, auth))
      },
      {
        case h @ Habibi(newcontent) => 
        (for (
          au <- auth orCorr new Corr("not logged in", "Sorry - need to log in"); //cNoAuth;
          comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
          can <- canEdit(comm, auth) orErr ("can only edit your comments")
        ) yield {
          comm.update(newcontent)
          Redirect(controllers.Wiki.w(cat, name))
        }) getOrElse
          noPerm("?", "?", errCollector.mkString)
      })
  }

}
