package controllers

import admin.VErrors
import model.{Perm, User, _}
import org.bson.types.ObjectId
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.Action
import razie.Logging

object Comment extends RazController with Logging {
  val commentForm = Form {
    tuple(
      "link" -> text.verifying(vSpec, vPorn),
      "content" -> text.verifying(vSpec, vPorn))
  }

  /** add a comment **/
  def add(topicId: String, what: String, oid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    commentForm.bindFromRequest.fold(
      formWithErrors => //todo fix realm
        Msg2(formWithErrors.toString + "Hein?", Some(routes.Wiki.showId(topicId, Wikis.RK).url)),
      {
        case (link, content) => iadd(topicId, what, oid, None, content).apply(request).value.get.get
      })
  }

  def kopt (kind:String) = if(Array ("video","photo","slideshow") contains kind) Some(kind) else None 
  
  /** add a comment **/
  private def iadd(topicId: String, kind: String, oid: String, link:Option[String], content: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      w <- Wikis.findById(topicId) orErr "Wiki topic not found"
    ) yield {
      val cs = model.Comments.findForWiki(new ObjectId(topicId)).getOrElse(CommentStream(new ObjectId(topicId), "Wiki").create)
      if (!cs.isDuplo(oid)) {
        cs.addComment(au, content, oid, link, kopt(kind))
        // TODO send email to parent if kid

        admin.SendEmail.withSession { implicit mailSession =>
          au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildCommentWiki(parent, au, w.wid))

          // email creator and all other commenters
          Wikis.findById(topicId).map { w =>
            (Users.findUserById(w.by).map(_._id).toList ++ cs.comments.map(_.userId)).distinct.filter(_ != au._id).map(uid =>
              Users.findUserById(uid).map(u => Emailer.sendEmailNewComment(u, au, w.wid)))
          }
        }
      } else log("ERR_DUPLO_COMMENT")
      Redirect(routes.Wiki.showId(topicId, Wikis.RK)) //todo fix realm
    }) getOrElse {
      Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
    }
  }

  def reply(topicId: String, what: String, replyId: String, oid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    commentForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Hein?", Some("/wiki/id/" + topicId)),
      {
        case (link, content) =>
          (for (
            au <- activeUser;
            cs <- model.Comments.findForWiki(new ObjectId(topicId)) orErr ("No comments for this page...");
            parent <- cs.comments.find(_._id.toString == replyId) orErr ("Can't find the comment to reply to...")
          ) yield {
            cs.addComment(au, content, oid, None, None, Some(parent._id))
            Redirect(routes.Wiki.showId(topicId, Wikis.RK)) //todo fix realm
          }) getOrElse {
            Unauthorized("Oops - cannot add comment... " + errCollector.mkString)
          }
      })
  }

  /** is mine or i am amdin */
  def canEdit(comm: model.Comment, auth: Option[User]) = {
    auth.exists(au => comm.userId == au._id || au.hasPerm(Perm.adminDb))
  }

  private def split(content: String, kind: String) = {
    if (content startsWith ("{{" + kind)) {
      val pat = ("(?s)\\{\\{"+kind+" ([^}]*)\\}\\}(.*)").r
      val pat(l,c) = content
      (l,c)
    } else 
      ("", content)
  }

  def edit(wid: WID, cid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    (for (
      au <- activeUser;
      comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
      can <- canEdit(comm, auth) orErr ("can only edit your comments")
    ) yield {
      val (link, content) = split (comm.content, comm.kind.getOrElse("text"))
      Ok(views.html.comments.commEdit(wid, cid, comm.kind.getOrElse("text"), commentForm.fill(link, content.trim), auth))
    }) getOrElse
      noPerm(wid)
  }

  def save(wid: WID, cid: String, kind: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    commentForm.bindFromRequest.fold(
      formWithErrors => {
        log(formWithErrors.toString)
        BadRequest(views.html.comments.commEdit(wid, cid, kind, formWithErrors, auth))
      },
      {
        case (newlink, newcontent) =>
          val con = if (newlink.length <= 0) newcontent else {
          (kind match {
            case "video" => 
              if ((newlink contains "<a href") || (newlink contains "<iframe")) newlink
              else "{{video " + newlink + "}}"
            case "photo" => 
              if ((newlink contains "<a href") || (newlink contains "<img")) newlink
              else "{{photo " + newlink + "}}"
            case "slideshow" => 
              if ((newlink contains "<a href") || (newlink contains "<iframe") || (newlink contains "<embed")) newlink
              else "{{slideshow " + newlink + "}}"
            case _ => ""
          }) + (if(newcontent.trim.length > 0) ("\n\n"+newcontent.trim) else "")
          }

          Comments.findCommentById(cid) map { comm =>
            (for (
              au <- activeUser;
              can <- canEdit(comm, auth) orErr ("can only edit your comments")
            ) yield {
              if (con.length > 0) comm.update(con, None, au)
              Redirect(controllers.Wiki.w(wid, false))
            }) getOrElse
              noPerm(wid)
          } getOrElse {
            if (con.length > 0) iadd(wid.findId.get.toString, kind, cid, kopt(newlink), con).apply(request).value.get.get
            else  Redirect(controllers.Wiki.w(wid, false))
          }
      })
  }

  /** start to add a video/photo comment **/
  def vComment1(cat:String, topic: String, what: String, oid: String, kind: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      w <- Wikis.findById(cat,topic) orErr "Wiki topic not found"
    //      comm <- Comments.findCommentById(cid) orErr ("bad comment id?");
    //      can <- canEdit(comm, auth) orErr ("can only edit your comments")
    ) yield {
      Ok(views.html.comments.commEdit(w.wid, oid, kind, commentForm.fill("", ""), auth))
    }) getOrElse
      noPerm(WID("?", "?"))
  }
}
