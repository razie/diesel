package controllers

import mod.snow._
import model._
import org.joda.time.DateTime
import play.api.mvc.{Action, Result}
import razie.db.{Txn, tx}
import razie.diesel.dom.WikiDomain
import razie.wiki.admin.SendEmail
import razie.wiki.model.FormStatus
import razie.wiki.model.WID
import razie.wiki.model.WikiEntry
import razie.wiki.model.Wikis
import razie.wiki.model._
import razie.{Logging, cout}
import razie.wiki.Sec._

/**
 * wiki controller
 *
 *  form fields are formatted in WForm
 *
 */
object Forms extends WikiBase with Logging {

  /** create the data section */
  def mkFormData(spec: WikiEntry, defaults: Map[String, String] = Map.empty) = {
    // build the defaults - cross check with formSpec
    var defaultStr = ""
    defaults.filter(x=> spec.form.fields.contains(x._1)).map { t =>
      val (k, v) = t
      defaultStr = defaultStr + s""", "$k":"$v" """
    }

    val content = s"""
{{.section:formData}}
{"formState":"created" $defaultStr }
{{/section}}
"""

  content
  }

  /** create a new form instance for a user */
  def crForm(u: User, formSpec: WID, formData: WID, label: String, reviewer: User, formRole: Option[String], defaults: Map[String, String] = Map.empty)(implicit txn: Txn) = {
    val wid = formData

    // build the defaults - cross check with formSpec
    var fdata =mkFormData(formSpec.page.get, defaults)

    val content = s"""
[[include:${formSpec.wpath}]]
{{.title:${formSpec.name} for ${u.ename}}}
$fdata
"""
    var we = WikiEntry(
      formData.cat,
      formData.name,
      label,
      "md",
      content,
      u._id,
      Seq(),
      Wikis.RK,
      1,
      None,
      Map("visibility" -> "ClubAdmin",
        "wvis" -> "ClubAdmin"))

    if (WikiDomain(wid.getRealm).needsOwner(wid.cat)) {
      we = we.cloneProps(we.props ++ Map("owner" -> u.id), u._id)
//      model.UserWiki(u._id, we.uwid, "Owner").create
      //      RazController.cleanAuth()
    }

    formRole.foreach(fr =>
      we = we.cloneProps(Map(FormStatus.FORM_ROLE -> fr), u._id))
    we.form.formState.foreach(fr =>
      we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), u._id))

    we.create
    WikiAudit("CREATE", we.wid.wpath, Some(u._id)).create

    Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
  }

    /** copy an old form data for new formSpec
      *
      * @param u - form owner
      * @param oldW old entry
      * @param newName new form name
      * @param label new label
      * @param newFormSpec new wid for the spec
      */
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

    var we = WikiEntry(
      oldW.category,
      newName,
      label,
      "md",
      content,
      u._id,
      Seq(),
      Wikis.RK,
      1,
      None,
      Map("visibility" -> "ClubAdmin",
        "wvis" -> "ClubAdmin"))

    if (WikiDomain(oldW.realm).needsOwner(oldW.category)) {
      we = we.cloneProps(we.props ++ Map("owner" -> u.id), u._id)
//      model.UserWiki(u._id, we.uwid, "Owner").create
      //      RazController.cleanAuth()
    }

    we = we.cloneProps(Map(FormStatus.FORM_ROLE -> newFormSpec.role), u._id)
    we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> "created"), u._id)

    we.create
    WikiAudit("CREATE", we.wid.wpath, Some(u._id)).create

    we
  }

  def fdate(d: DateTime) = {
    f"${d.getYear()}%4d-${d.getMonthOfYear()}%02d-${d.getDayOfMonth()}%02d"
  }

  /** create a new form instance for a user */
  def crFormKid(u: User, formSpec: WID, formData: WID, label: String, reviewer: User, formRole: Option[String], rk: RacerKid)(implicit txn:Txn) = {
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

    def json(d: Map[String, String], errors: Boolean) = {
      val j = new org.json.JSONObject()

      d.filter(t => !buttons.contains(t._1)).foreach(t => j.put(t._1, t._2))

      j.put("formState", FormStatus.EDITING)

      // or some other status
      if (!errors) {
        if (d.contains("save_button"))
          j.put("formState", FormStatus.EDITING)
        else if (d.contains("submit_button"))
          j.put("formState", FormStatus.SUBMITTED)
        else if (d.contains("approve_button"))
          j.put("formState", FormStatus.APPROVED)
        else if (d.contains("reject_button"))
          j.put("formState", FormStatus.EDITING)
      }

      cdebug << "form.jsondata " + j.toString
      j
    }

  /** save the form and possibly change status: submit/reject/accept */
  def doeSubmit(iwid: WID) = Action.async { implicit request =>
    if(iwid.cat.contains("DslEntity") || iwid.content.exists(_.indexOf("wikie.form") >= 0))
      doeSubmit2(iwid).apply(request)
    else if(request.body.asFormUrlEncoded.get.contains("weNextUrl"))
      doeSubmit3(iwid).apply(request)
    else
      doeSubmitOld(iwid).apply(request)
  }

  /** save the form and possibly change status: submit/reject/accept */
  def doeSubmitOld(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val cat = iwid.cat
    val name = Wikis.formatName(iwid)
    val wid = WID(cat, name).r(iwid.getRealm)

    val data = request.body.asFormUrlEncoded
    val data2 = data.map(_.collect { case (k, v) => (k, v.head) }).get // somehow i get list of values?

    // TODO this is copy paste from def save
    clog << "Wiki.FORM.save " + wid

    Wikis.find(wid) match {
      case Some(w) =>
        val wf = new WForm(w)
        (for (
          au <- activeUser;
          can <- canEdit(wid, auth, Some(w));
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          isFormData <- (w.content.contains("section:formData}}") orErr "Not a form");
          upd <- Wikie.before(w, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
        ) yield {
            val (newData, errors) = wf.validate(data2)

            // validation also cleans up the data, of symbols, tabs etc
            val newVer = w.cloneNewVer(w.label, w.markup, wf.mkContent(json(Map() ++ newData, !errors.isEmpty)), au._id)

            if (!errors.isEmpty) {
              // render erors
              clog << "Wiki.FORM.Errors: " + errors.toString
              cout << "new content:" + newVer.content
              newVer.preprocess(Some(au))
              Wiki.showForm(wid, None, Some(newVer), Some(au), false, Map() ++ errors, can)(ROK.r)
            } else {
              // save the wiki page?

              var we = newVer

              we.form.formState.foreach(fr =>
                we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), au._id))

              razie.db.tx("forms.submitted", au.userName) { implicit txn =>
                w.update(we, Some("form_submitted"))
                act.WikiWf.event("wikiFormSubmit", Map("wpath" -> we.wid.wpath, "userName" -> au.userName))
                Wikie.after(we, WikiAudit.UPD_CONTENT, Some(au))
                Emailer.withSession(w.realm) { implicit mailSession =>
                  //                    au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))

                  if (data2.contains("submit_button")) {
                    SendEmail.withSession(Website.realm(request)) { implicit mailSession =>
                      //                  cout << Regs.findWid(wid)
                      //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName))
                      //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin)
                      Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin).foreach { reviewer =>
                        Emailer.sendEmailFormSubmitted(reviewer, au, Wiki.w(wid))
                      }
                      we.props.get("notifyUsers").toList.flatMap(_.split(",")).flatMap(Users.findUserById(_).toList).map{u=>
                        Emailer.sendEmailFormNotify(u, au, Wiki.w(wid), we.props.getOrElse("role", ""))
                      }
                    }
                  } else if (data2.contains("approve_button")) {
                    // if all forms in a registration are good, change status
                    // TODO optimize this
                    for (
                      r <- Regs.findWid(wid);
                      club <- Club(r.club);
                      owner <- w.owner
                    ) {
                      cdebug << r.deprecatedWids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size
                      if (r.deprecatedWids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size == r.deprecatedWids.size) {
                        r.updateRegStatus(RegStatus.ACCEPTED)
                        SendEmail.withSession(Website.realm(request)) { implicit mailSession =>
                          Emailer.sendEmailFormsAccepted(au, owner.asInstanceOf[User], r.clubName, r.fee(), club.msgFormsAccepted)
                        }
                      }
                    }
                    // TODO send email with accepted
                    1 // TODO send email with reg. current
                  } else if (data2.contains("reject_button")) {
                    SendEmail.withSession(Website.realm(request)) { implicit mailSession =>
                      w.owner.foreach { owner =>
                        Regs.findWid(wid).foreach { r =>
                          Emailer.sendEmailFormRejected(au, owner.asInstanceOf[User], r.clubName, routes.Club.doeClubUserReg(r._id.toString).toString, data2.get("formRejected").getOrElse("Something's wrong...?"))

                          // if it was ok and one rejected, then reset status of the entire reg to pending
                          if (r.regStatus != RegStatus.PENDING && r.deprecatedWids.filter(_.page.flatMap(_.form.formState).exists(_ == FormStatus.APPROVED)).size != r.deprecatedWids.size)
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
          Unauthorized(errCollector.mkString)
      case None =>
        noPerm(wid, "HACK_SAVEEDIT")
    }
  }

  /** new clean version, without Reg */
  def doeSubmit2(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val cat = iwid.cat
    val name = Wikis.formatName(iwid)
    val wid = WID(cat, name)

    val data = request.body.asFormUrlEncoded
    val data2 = data.map(_.collect { case (k, v :: r) => (k, v) }).get // somehow i get list of values?

    // TODO this is copy paste from def save
    clog << "Wiki.FORM.save2 " + wid

    Wikis.find(wid) match {
      case Some(w) =>
        val wf = new WForm(w)
        (for (
          au <- activeUser;
          can <- canEdit(wid, auth, Some(w));
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          isFormData <- (w.content.contains("section:formData}}") orErr "Not a form");
          upd <- Wikie.before(w, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
        ) yield {
            val (newData, errors) = wf.validate(data2)

            // validation also cleans up the data, of symbols, tabs etc
            val newVer = w.cloneNewVer(w.label, w.markup, wf.mkContent(json(Map() ++ newData, !errors.isEmpty)), au._id)

            if (!errors.isEmpty) {
              // render erors
              clog << "Wiki.FORM.Errors: " + errors.toString
              cout << "new content:" + newVer.content
              newVer.preprocess(Some(au))
              Wiki.showForm(wid, None, Some(newVer), Some(au), false, Map() ++ errors, can)(ROK.r)
            } else {
              var we = newVer

              we.form.formState.foreach(fr =>
                we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), au._id))

              razie.db.tx("forms.submitted", au.userName) { implicit txn =>
                w.update(we, Some("form_submitted"))
                act.WikiWf.event("wikiFormSubmit", Map("wpath" -> we.wid.wpath, "userName" -> au.userName))
                Wikie.after(we, WikiAudit.UPD_CONTENT, Some(au))
                Emailer.withSession(w.realm) { implicit mailSession =>
                  //                    au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))

                  if (data2.contains("submit_button")) {
                    Emailer.withSession(w.realm) { implicit mailSession =>
                      //                  cout << Regs.findWid(wid)
                      //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName))
                      //                  cout << Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin)
                      Regs.findWid(wid).flatMap(x => Users.findUserByUsername(x.clubName)).map(Club(_).regAdmin).foreach { reviewer =>
                        Emailer.sendEmailFormSubmitted(reviewer, au, Wiki.w(wid))
                      }
                      we.props.get("notifyUsers").toList.flatMap(_.split(",")).flatMap(Users.findUserById(_).toList).map{u=>
                        Emailer.sendEmailFormNotify(u, au, Wiki.w(wid), we.props.getOrElse("role", ""))
                      }
                    }
                  } else {
                    throw new IllegalArgumentException("")
                  }
                }

                WikiAudit("EDIT_FORM", w.wid.wpath, Some(au._id)).create
              }
              Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
            }
          }) getOrElse
          Unauthorized(errCollector.mkString)

      case None =>
        val w = new WikiEntry(data2("category"), data2("name"), data2("category") + " - " +data2("name"), "md", data2("content"), auth.get._id, Seq(data2("tags")), data2("realm"))
        val wf = new WForm(w)
        (for (
          au <- activeUser;
          can <- canEdit(wid, auth, Some(w));
          r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
          //          club <- Club.findForReviewer(au);
          hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
          isFormData <- (w.content.contains("section:formData}}") orErr "Not a form");
          upd <- Wikie.before(w, WikiAudit.UPD_CONTENT) orErr ("Not allowerd")
        ) yield {
            val (newData, errors) = wf.validate(data2 - "content")

            // validation also cleans up the data, of symbols, tabs etc
            val newVer = w.cloneNewVer(w.label, w.markup, wf.mkContent(json(Map() ++ newData, !errors.isEmpty)), au._id)

            if (!errors.isEmpty) {
              // render erors
              clog << "Wiki.FORM.Errors: " + errors.toString
              cout << "new content:" + newVer.content
              newVer.preprocess(Some(au))
              Wiki.showForm(wid, None, Some(newVer), Some(au), false, Map() ++ errors, can)(ROK.r)
            } else {
              var we = newVer

              we.form.formState.foreach(fr =>
                we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), au._id))

              razie.db.tx("forms.submitted", au.userName) { implicit txn =>
                we.create
                act.WikiWf.event("wikiFormSubmit", Map("wpath" -> we.wid.wpath, "userName" -> au.userName))
                Wikie.after(we, WikiAudit.UPD_CONTENT, Some(au))
                Emailer.withSession(w.realm) { implicit mailSession =>
                  //                    au.quota.incUpdates
                  au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, WID(w.category, w.name)))

                  if (data2.contains("submit_button")) {
                  } else {
                    throw new IllegalArgumentException("")
                  }
                }

                WikiAudit("EDIT_FORM", w.wid.wpath, Some(au._id)).create
              }
              Redirect(controllers.Wiki.w(we.wid, true)).flashing("count" -> "0")
            }
          }) getOrElse
          Unauthorized(errCollector.mkString)
    }
  }

  /** new version for code, with next */
  def doeSubmit3(iwid: WID) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val data = request.body.asFormUrlEncoded
    val data2 = data.map(_.collect { case (k, v) => (k, v.head) }).get // somehow i get list of values?

        val w = new WikiEntry(iwid.cat, iwid.name, iwid.name, "md", data2("weContent"), auth.get._id)
        val wf = new WForm(w)
        (for (
          au <- activeUser
        ) yield {
            val (newData, errors) = wf.validate(data2 - "weContent")

            // validation also cleans up the data, of symbols, tabs etc
            val newVer = w.cloneNewVer(w.label, w.markup, wf.mkContent(json(Map() ++ newData, !errors.isEmpty)), au._id)

            if (!errors.isEmpty) {
              // render erors
              clog << "Wiki.FORM.Errors: " + errors.toString
              cout << "new content:" + newVer.content
              newVer.preprocess(Some(au))
              Wiki.showForm(iwid, None, Some(newVer), Some(au), false, Map() ++ errors, true)(ROK.r)
            } else {
              var we = newVer

              we.form.formState.foreach(fr =>
                we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), au._id))

              razie.db.tx("forms.submitted", au.userName) { implicit txn =>

                act.WikiWf.event("wikiFormSubmit", Map("wpath" -> we.wid.wpath, "userName" -> au.userName))
                Wikie.after(we, WikiAudit.UPD_CONTENT, Some(au))
                  if (data2.contains("submit_button")) {
                  } else {
                    throw new IllegalArgumentException("")
                  }

                WikiAudit("EDIT_FORM", w.wid.wpath, Some(au._id)).create
              }
              we.fields.put("weNextUrl", FieldDef("weNextUrl", data2("weNextUrl"), Map.empty))
              we.fields.put("weRedirectPlease", FieldDef("weRedirectPlease", "yes", Map.empty))
              Wiki.showForm(iwid, None, Some(we), Some(au), false, Map(), true)(ROK.r)
            }
      }) getOrElse
          Unauthorized(errCollector.mkString)
  }

  def doeCreateReg(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }

  /** when using a configured form */
  def sForm(form:WID, next:String)(implicit stok:RazRequest) : Result = {
    form.r(stok.realm).content.map(sForm(_, next)).getOrElse(
      Msg("Form specification NOT found: "+form.wpath)
    )
  }

  /** temp form - avoid having to create play forms and stuff
    *
    * */
  def sForm(content:String, next:String, form:Option[WID]=None)(implicit stok:RazRequest) : Result = {
    ROK.k apply {
      val spec = WikiEntry(
        "Form",
        "a_form",
        "A form",
        "md",
        content,
        stok.au.get._id
      )

      var we = WikiEntry(
        "Form",
        "a_form",
        "A form",
        "md",
        content + "\n\n"+Forms.mkFormData(spec),
        stok.au.get._id
      )

      we.form.formState.foreach(fr =>
        we = we.cloneProps(we.props ++ Map(FormStatus.FORM_STATE -> fr), stok.au.get._id))

      views.html.wiki.wikiGenForm(
        we,
        Map.empty,
        next,
        form
      )
    }
  }

  /** edit a new form, will reference its form design */
  def doeFormEdit(wid:WID) = FAUR {implicit stok =>
    val we = wid.page.get
    val form = Wikis.formFor(we)
    val fs = form.flatMap(WID.fromPath)

    val newe = we.copy(content = fs.flatMap(_.content).mkString +"\n{{.wiki.noTemplates true}}\n"+ we.content)

    ROK.k apply {
      views.html.wiki.wikiGenForm(
        newe,
        Map.empty,
        "",
        form.flatMap(WID.fromPath)
      )
    }
  }
}

/** wiki controller */
object FormReg extends WikiBase with Logging {

  def doeCreateReg(iwid: WID) = Action { implicit request =>
    Ok("next step...")
  }
}
