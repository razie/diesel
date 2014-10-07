package controllers

import db.Txn
import db.tx
import scala.Array.canBuildFrom
import org.joda.time.DateTime
import com.mongodb.DBObject
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin.Audit
import admin.Config
import admin.Corr
import admin.IgnoreErrors
import admin.MailSession
import admin.Notif
import admin.SendEmail
import admin.VErrors
import model.Enc
import model.Perm
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import model.Stage
import model.User
import model.UserType
import model.UserWiki
import model.Users
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import db.ROne
import model.WikiCount
import model.WikiIndex
import model.Wikis
import model.CMDWID
import model.WikiAudit
import model.WikiEntry
import model.WikiEntryOld
import model.WID
import model.WikiLink
import model.WikiDomain
import model.WikiWrapper
import model.WikiXpSolver
import model.RegStatus
import model.FormStatus
import model.Regs
import razie.cout
import razie.clog
import razie.cdebug
import model.WikiForm
import model.WForm
import model.RacerKid

/**
 * wiki controller
 *
 *  form fields are formatted in WForm
 *
 */
object Forms extends WikiBase with Logging {

  /** create a new form instance for a user */
  def crForm(u: User, formSpec: WID, formData: WID, label: String, reviewer: User, formRole: Option[String], defaults: Map[String, String] = Map.empty)(implicit txn: Txn = tx.auto) = {
    val wid = formData

    // build the defaults - cross check with formSpec
    var defaultStr = ""
    val spec = formSpec.page.get
    defaults.filter(x=> spec.form.fields.contains(x._1)).map { t =>
      val (k, v) = t
      defaultStr = defaultStr + s""", "$k":"$v" """
    }

    val content = s"""
[[include:${formSpec.wpath}]]
{{.title:${formSpec.name} for ${u.ename}}}
{{.section:formData}}
{"formState":"created" $defaultStr }
{{/section}}
"""
    var we = model.WikiEntry(
      formData.cat,
      formData.name,
      label,
      "md",
      content,
      u._id,
      Seq(),
      "rk",
      1,
      None,
      Map("visibility" -> "ClubAdmin",
        "wvis" -> "ClubAdmin"))

    if (WikiDomain.needsOwner(wid.cat)) {
      we = we.cloneProps(we.props ++ Map("owner" -> u.id), u._id)
      model.UserWiki(u._id, we.uwid, "Owner").create
      //      RazController.cleanAuth()
    }

    formRole.foreach(fr =>
      we = we.cloneProps(Map(FormStatus.FORM_ROLE -> fr), u._id))

    we.create
    WikiAudit("CREATE", we.wid.wpath, Some(u._id)).create

    Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
  }

    /** copy an old form data for new formSpec */
  def copyForm(u: User, oldW:WikiEntry, newName:String, label:String, newFormSpec: RoleWid, filterFields:Seq[String])(implicit txn: Txn = tx.auto) = {

    val oldfw = new WForm(oldW)
    
    // build the 
    var defaultStr = ""
    val spec = newFormSpec.wid.page.get
   
    val m = oldW.form.fields.filter(t=> !filterFields.contains(t._1)).map(t=>(t._1, t._2.value))
    m.put("formState", "created")

    // don't make the string by hand - some newline values get messed up.
    val content = oldfw.mkContent(json(Map()++m, false), s"""
[[include:${newFormSpec.wid.wpath}]]
{{.title:${newFormSpec.wid.name} for ${u.ename}}}
{{.section:formData}}
{"formState":"created" }
{{/section}}
""")

    var we = model.WikiEntry(
      oldW.category,
      newName,
      label, 
      "md",
      content,
      u._id,
      Seq(),
      "rk",
      1,
      None,
      Map("visibility" -> "ClubAdmin",
        "wvis" -> "ClubAdmin"))

    if (WikiDomain.needsOwner(oldW.category)) {
      we = we.cloneProps(we.props ++ Map("owner" -> u.id), u._id)
      model.UserWiki(u._id, we.uwid, "Owner").create
      //      RazController.cleanAuth()
    }

    we = we.cloneProps(Map(FormStatus.FORM_ROLE -> newFormSpec.role), u._id)

    we.create
    WikiAudit("CREATE", we.wid.wpath, Some(u._id)).create

    we
  }

  def fdate(d: DateTime) = {
    f"${d.getYear()}%4d-${d.getMonthOfYear()}%02d-${d.getDayOfMonth()}%02d"
  }

  import razie.|>

  /** create a new form instance for a user */
  def crFormKid(u: User, formSpec: WID, formData: WID, label: String, reviewer: User, formRole: Option[String], rk: RacerKid) = {
    crForm(u, formSpec, formData, label, reviewer, formRole,
      Map(
        "firstName" -> rk.info.firstName,
        "lastName" -> rk.info.lastName,
        "dob" -> fdate(rk.rki.map(_.dob).getOrElse(new DateTime(rk.info.yob, 1, 1, 1, 1))),
        "gender" -> rk.info.gender,
        "email" -> rk.info.email.dec) ++
        (if (rk.userId.exists(_ == u._id)) u.profile.flatMap(_.contact).map(_.info).getOrElse(Map.empty) else Map.empty))
  }

  final val buttons = Array("save_button",
    "submit_button",
    "approve_button",
    "reject_button")

    private def json(d: Map[String, String], errors: Boolean) = {
      val j = new org.json.JSONObject()

      d.filter(t => !buttons.contains(t._1)).foreach(t => j.put(t._1, t._2))

      if (!errors) {
        if (d.contains("save_button"))
          j.put("formState", FormStatus.EDITING)
        if (d.contains("submit_button"))
          j.put("formState", FormStatus.SUBMITTED)
        if (d.contains("approve_button"))
          j.put("formState", FormStatus.APPROVED)
        if (d.contains("reject_button"))
          j.put("formState", FormStatus.EDITING)
      } else {
        j.put("formState", FormStatus.EDITING)
      }

      cdebug << "form.jsondata " + j.toString
      j
    }

  /** save the form and possibly change status: submit/reject/accept */
  def doeSubmit(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val cat = iwid.cat
    val name = Wikis.formatName(iwid)
    val wid = WID(cat, name)

    val data = request.body.asFormUrlEncoded
    val data2 = data.map(_.collect { case (k, v :: r) => (k, v) }).get // somehow i get list of values?

    // TODO this is copy paste from def save
    clog << "Wiki.FORM.save " + wid

    Wikis.find(wid) match {
      case Some(w) =>
        val wf = new WForm(w)
        (for (
          au <- activeUser;
          can <- canEdit(wid, auth, Some(w));
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
          club <- Club.findForReviewer(au);
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          isFormData <- (w.content.contains("section:formData}}") orErr "Not a form")
        ) yield {
          val (newData, errors) = wf.validate(data2)

          // validation also cleans up the data, of symbols, tabs etc
          val newVer = w.cloneNewVer(w.label, w.markup, wf.mkContent(json(Map() ++ newData, !errors.isEmpty)), au._id)

          if (!errors.isEmpty) {
            // render erors
            clog << "Wiki.FORM.Errors: " + errors.toString
            cout << "new content:" + newVer.content
            newVer.preprocessed
            Wiki.showForm(wid, None, Some(newVer), Some(au), false, Map() ++ errors, can)
          } else {
            // save the wiki page?
            val upd = Notif.entityUpdateBefore(newVer, WikiEntry.UPD_CONTENT) orErr ("Not allowerd")

            var we = newVer
            db.tx("forms.submitted") { implicit txn =>
              w.update(we)
              Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
              act.WikiWf.event("wikiFormSubmit", Map("wpath" -> we.wid.wpath, "userName" -> au.userName))
              Notif.entityUpdateAfter(we, WikiEntry.UPD_CONTENT)
              Emailer.withSession { implicit mailSession =>
                //                    au.quota.incUpdates
                au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))

                if (data2.contains("submit_button")) {
                  SendEmail.withSession { implicit mailSession =>
                    //                  cout << Regs.findWid(wid)
                    //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName))
                    //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin)
                    Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin).foreach { reviewer =>
                      Emailer.sendEmailFormSubmitted(reviewer, au, Wiki.w(wid.wpath))
                    }
                  }
                } else if (data2.contains("approve_button")) {
                  // if all forms in a registration are good, change status
                  // TODO optimize this
                  for (
                    r <- club.reg(wid);
                    owner <- w.owner
                  ) {
                    cdebug << r.wids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size
                    if (r.wids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size == r.wids.size) {
                      r.updateRegStatus(RegStatus.ACCEPTED)
                      SendEmail.withSession { implicit mailSession =>
                        Emailer.sendEmailFormsAccepted(au, owner.asInstanceOf[User], r.clubName, club.msgFormsAccepted)
                      }
                    }
                  }
                  // TODO send email with accepted
                  1 // TODO send email with reg. current
                } else if (data2.contains("reject_button")) {
                  SendEmail.withSession { implicit mailSession =>
                    w.owner.foreach { owner =>
                      club.reg(wid).foreach { r =>
                        Emailer.sendEmailFormRejected(au, owner.asInstanceOf[User], r.clubName, routes.Club.doeClubUserReg(r._id.toString).toString, data2.get("formRejected").getOrElse("Something's wrong...?"))

                        // if it was ok and one rejected, then reset status of the entire reg to pending
                        if (r.regStatus != RegStatus.PENDING && r.wids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size != r.wids.size)
                          r.updateRegStatus(RegStatus.PENDING)
                      }
                    }
                  }
                }
              }

              WikiAudit("EDIT_FORM", w.wid.wpath, Some(au._id)).create
            }
            Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
          }
        }) getOrElse
          unauthorized("?")
      case None =>
        noPerm(wid, "HACK_SAVEEDIT")
    }
  }

  def doeAccept(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  def doeReject(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  def doeCreateReg(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  //  /** wid is the script name,his parent is the actual topic */
  //  def wikieNextStep(id:String) = Action { implicit request =>
  //    (for (
  //      au <- auth orCorr cNoAuth
  //    ) yield {
  //      // default to category
  //      Audit.logdb("WF_NEXT_STEP", id)
  //      act.WikiWf.event("WF_NEXT_STEP", Map("id"->id))
  //      Ok("next step...")
  //    }) getOrElse
  //      Unauthorized("You don't have permission to do this...")
  //  }
}

/** wiki controller */
object FormReg extends WikiBase with Logging {

  def doeAccept(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  def doeReject(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  def doeCreateReg(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  //  /** wid is the script name,his parent is the actual topic */
  //  def wikieNextStep(id:String) = Action { implicit request =>
  //    (for (
  //      au <- auth orCorr cNoAuth
  //    ) yield {
  //      // default to category
  //      Audit.logdb("WF_NEXT_STEP", id)
  //      act.WikiWf.event("WF_NEXT_STEP", Map("id"->id))
  //      Ok("next step...")
  //    }) getOrElse
  //      Unauthorized("You don't have permission to do this...")
  //  }
}
