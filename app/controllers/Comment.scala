package controllers

import org.bson.types.ObjectId
import admin.VError
import model.Perm
import model.User
import model.Wikis
import model._
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.mvc.Action
import razie.Logging
import admin.Corr

object Comment extends RazController with Logging {
  case class Habibi(s: String)
  val commentForm = Form {
    mapping(
      "content" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)))(Habibi.apply)(Habibi.unapply)
  }

  /** add a comment **/
  def add(topic: String, what: String, oid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Hein?", Some("/wiki/id/" + topic)),
      {
        case Habibi(content) =>
          (for (
            au <- auth orCorr cNoAuth;
            isA <- checkActive(au);
            w <- Wikis.findById(topic) orErr "Wiki topic not found"
          ) yield {
            val cs = model.Comments.findForWiki(new ObjectId(topic)).getOrElse(CommentStream(new ObjectId(topic), "Wiki").create)
            if (!cs.isDuplo(oid)) {
              cs.addComment(au, content, oid)
              // TODO send email to parent if kid

              admin.SendEmail.withSession { implicit mailSession =>
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildCommentWiki(parent, au, w.wid))

                // email creator and all other commenters
                Wikis.findById(topic).map { w =>
                  (Users.findUserById(w.by).map(_._id).toList ++ cs.comments.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
                    Users.findUserById(uid).map(u => Emailer.sendEmailNewComment(u, au, w.wid)))
                }
              }
            } else log("ERR_DUPLO_COMMENT")
            Redirect(routes.Wiki.showId(topic))
          }) getOrElse {
            Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
          }
      })
  }

  def reply(topic: String, what: String, replyId: String, oid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Hein?", Some("/wiki/id/" + topic)),
      {
        case Habibi(content) =>
          (for (
            au <- auth orCorr cNoAuth;
            isA <- checkActive(au);
            cs <- model.Comments.findForWiki(new ObjectId(topic)) orErr ("No comments for this page...");
            parent <- cs.comments.find(_._id.toString == replyId) orErr ("Can't find the comment to reply to...")
          ) yield {
            cs.addComment(au, content, oid, Some(parent._id))
            Redirect(routes.Wiki.showId(topic))
          }) getOrElse {
            Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
          }
      })
  }

  /** is mine or i am amdin */
  def canEdit(comm: model.Comment, auth: Option[User]) = {
    auth.exists(au => comm.userId == au._id || au.hasPerm(Perm.adminDb))
  }

  def edit(wid: WID, cid: String) = Action { implicit request =>
    implicit val errCollector = new VError()

    (for (
      au <- auth orCorr cNoAuth;
      isA <- checkActive(au);
      comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
      can <- canEdit(comm, auth) orErr ("can only edit your comments")
    ) yield {
      Ok(views.html.comments.commEdit(wid, cid, commentForm.fill(Habibi(comm.content)), auth))
    }) getOrElse
      noPerm(WID("?", "?"))
  }

  def save(wid: WID, cid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    commentForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.comments.commEdit(wid, cid, formWithErrors, auth))
      },
      {
        case h @ Habibi(newcontent) =>
          (for (
            au <- auth orCorr cNoAuth;
            isA <- checkActive(au);
            comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
            can <- canEdit(comm, auth) orErr ("can only edit your comments")
          ) yield {
            comm.update(newcontent, au)
            Redirect(controllers.Wiki.w(wid, false))
          }) getOrElse
            noPerm(WID("?", "?"))
      })
  }

}
