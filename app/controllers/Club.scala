package controllers

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import admin.SendEmail
import admin.VError
import db.REntity
import db.RMany
import db.RMongo.as
import db.ROne
import db.RTable
import model.FormStatus
import model.RK
import model.RacerKid
import model.RacerKidAssoc
import model.RacerKidz
import model.Reg
import model.RegKid
import model.RegStatus
import model.Regs
import model.Sec.EncryptedS
import model.User
import model.Users
import model.VolunteerH
import model.WID
import model.Wikis
import play.api.data.Form
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.Logging
import razie.cout
import admin.Config

case class RoleWid(role: String, wid: WID)

/** additional information for a club - associated to a regular user, designated type=organization */
@db.RTable
case class Club(
  userId: ObjectId,
  userName: String,
  regType: String = "",
  curYear: String = Config.curYear,
  regAdmin: String = "",
  regForms: Seq[RoleWid] = Seq.empty,
  newFollows: Seq[RoleWid] = Seq.empty,
  _id: ObjectId = new ObjectId) extends REntity[Club] {

  // optimize access to User object
  lazy val user = oUser.getOrElse(Users.findUserById(userId).get)
  private var oUser: Option[User] = None
  def setU(u: User) = { oUser = Some(u); this }

  def regForm(role: String) = regForms.find(_.role == role)
  def uregAdmin = Users.findUser(regAdmin.enc)

  def reg(u: User) = model.Regs.findClubUserYear(user, u._id, curYear)
  def reg(wid: WID) = model.Regs.findWid(wid)
  def userLinks = model.Users.findUserLinksTo(model.WID("Club", user.userName))

  // TODO filter by year as well
  def roleOf(rkId: ObjectId) =
    // registered or owned
    ROne[RacerKidAssoc]("from" -> userId, "to" -> rkId).map(_.role).mkString
  //    ROne[RegKid]("rkId" -> rkId).map(_.role).orElse(
  //        RacerKidz.findForUser(userId).find(_._id == rkId).map(_.info.role)
  //        ).mkString

  def wid = WID("Club", user.userName)

  def rkAssocs = RMany[RacerKidAssoc]("from" -> userId, "year" -> curYear)

}

case class RKU(user: User) {

  def rkAssocs = if (user.isClub) RMany[RacerKidAssoc]("from" -> user._id, "year" -> Club(user).curYear) else RMany[RacerKidAssoc]("from" -> user._id)

  /** identify merge candidates for an assoc */
  def mergeCandidates(rka: RacerKidAssoc) = {
    val rk = rka.to.as[RacerKid].get

    // TODO if already merged, they will have a newRkId and should show that one instead...
    val res = if (user.isClub) {
      rkAssocs.map(x =>
        (x, x.to.as[RacerKid].get)).filter(t =>
        t._1._id != rka._id &&
          t._2.info.firstName.toLowerCase == rk.info.firstName.toLowerCase &&
          t._2.info.lastName.toLowerCase == rk.info.lastName.toLowerCase).filter(_._1.assoc == RK.ASSOC_LINK)
      // LINK dies for REGD
    } else {
      rkAssocs.map(x =>
        (x, x.to.as[RacerKid].get)).filter(t =>
        t._1._id != rka._id &&
          t._2.info.firstName.toLowerCase == rk.info.firstName.toLowerCase &&
          t._2.info.lastName.toLowerCase == rk.info.lastName.toLowerCase).filter(_._1.assoc == RK.ASSOC_PARENT)
      // PARENT dies for CHILD
    }
    res
  }
}

/** controller for club management */
object Club extends RazController with Logging {

  def apply(club: User) = findForUser(club).getOrElse(new Club(club._id, club.userName).setU(club))

  def apply(name: String) = findForName(name) orElse {
    Users.findUserByUsername(name).map { u =>
      new Club(u._id, u.userName).setU(u)
    }
  }

  def findForUser(u: User) = ROne[Club]("userId" -> u._id).map(_.setU(u))
  def findForName(n: String) = RMany[Club]().find(_.user.userName == n) // TODO optimize

  // manage user screen
  def membersData(au: User, what: String, cols: String): (List[String], List[List[String]]) = {
    val club = Club(au)
    val members = club.userLinks.map(_.userId)
    val regs = model.Regs.findClubYear(au, club.curYear)
    val forms = regs.flatMap(_.wids).flatMap(_.page).filter(_.formRole.exists(_ == what)).map(_.form.fields).toList

    // each set of fields has all attr - pick the first
    val headers = forms.find(_ => true).toList.flatMap(_.keys)

    val fields = if (cols.length > 0) cols.split(",").toList else headers

    // actual rows L[L[String]]
    val res = forms.map { m =>
      fields.map(h => m.get(h).map(_.value).getOrElse(""))
    }.toList

    (fields, res)
  }

  import play.api.data._
  import play.api.data.Forms._
  import play.api.data.validation.Constraints._

  // create profile
  case class Member(
    firstName: String,
    lastName: String,
    email: String,
    userType: String,
    pass: String,
    yob: Int,
    address: String)

  /** list members */
  def doeClubRegs = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registrations possible only for a club")
    ) yield {

      val members =
        model.Users.findUserLinksTo(model.WID("Club", au.userName)).map(uw =>
          (model.Users.findUserById(uw.userId),
            uw,
            model.Regs.findClubUserYear(au, uw.userId, controllers.Club(au).curYear))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)

      Ok(views.html.club.doeClubRegs(au, members))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def edRegForm(implicit request: Request[_]) = Form {
    tuple(
      "regType" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "curYear" -> number(min = 2012, max = 2015),
      "regAdmin" -> text.verifying("Invalid user", { x => Users.findUser(x.enc).isDefined }),
      "regForms" -> text.verifying("Invalid wiki", { x => x.length <= 0 || WID.fromPath(x).isDefined }),
      "newFollows" -> text.verifying("Invalid wiki", { x => x.length <= 0 || WID.fromPath(x).isDefined })) verifying
      ("Can't use last name for organizations!", { t: (String, Int, String, String, String) =>
        true
      })
  }

  /** registration settings */
  def doeClubRegSettings = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registrations possible only for a club");
      c <- Club.findForUser(au).orElse({ val x = Club(au); x.create; Some(x) })
    ) yield {
      Ok(views.html.club.doeClubRegSettings(edRegForm.fill(
        (c.regType, c.curYear.toInt, c.regAdmin,
          c.regForms.map(t => t.role + "=" + t.wid.wpath).mkString("\n"),
          c.newFollows.map(t => t.role + "=" + t.wid.wpath).mkString("\n"))),
        au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeClubUpdateRegSettings = Action { implicit request =>
    edRegForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.club.doeClubRegSettings(formWithErrors, auth.get)),
      {
        case (t, y, a, k, n) => forActiveUser { au =>
          val c1 = Club.findForUser(au).get.copy(
            regType = t,
            curYear = y.toString,
            regAdmin = a,
            regForms =
              if (k.trim.length <= 0) Seq.empty
              else k.trim.split("\n").map(x => x.split("=")).map(t =>
                RoleWid(t(0).trim, WID.fromPath(t(1).trim).get)),
            newFollows =
              if (n.trim.length <= 0) Seq.empty
              else n.trim.split("\n").map(x => x.split("=")).map(t =>
                RoleWid(t(0).trim, WID.fromPath(t(1).trim).get)))
          c1.update
          Redirect(routes.Club.doeClubRegSettings)
        }
      })
  }

  ////////////////////// manage user

  def mngUserForm(implicit request: Request[_]) = Form {
    tuple(
      "role" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "regStatus" -> text)
  }

  // manage user screen
  def doeClubReg(uwid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      uw <- model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid);
      u <- uw.user
    ) yield {
      Ok(views.html.club.doeClubReg(mngUserForm.fill(
        (uw.role, Club(au).reg(u).map(_.regStatus).getOrElse("n/a"))), uw, au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  import play.api.libs.json._

  // manage user screen
  def doeClubRegsJson(what: String, cols: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club")
    ) yield {
      val (headers, data) = membersData(au, what, cols)

      Ok(Json.toJson(headers :: data))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // manage user screen
  def doeClubRegsCsv(what: String, cols: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club")
    ) yield {
      val (headers, data) = membersData(au, what, cols)

      Ok(
        headers.mkString("|") +
          "\n" +
          data.map(_.mkString("|")).mkString("\n"))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // update user role
  def doeClubMemberUpdate(uwid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      olduw <- model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid);
      u <- olduw.user
    ) yield {
      mngUserForm.bindFromRequest.fold(
        formWithErrors => BadRequest(views.html.club.doeClubReg(formWithErrors, olduw, au)),
        {
          case (r, s) =>
            olduw.updateRole(r)
            val uw = model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid).get

            // update the role of the assoc as well
            for (
              rk <- ROne[RacerKid]("ownerId" -> olduw.userId, "userId" -> olduw.userId, "kind" -> RK.KIND_MYSELF);
              rka <- ROne[RacerKidAssoc]("from" -> au._id, "to" -> rk._id, "year" -> Club(au).curYear)
            ) if (rka.role != r) rka.copy(role = r).update

            Ok(views.html.club.doeClubReg(mngUserForm.fill(
              (uw.role, Club(au).reg(u).map(_.regStatus).getOrElse("n/a"))), uw, au))
        })
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** change registration status */
  def doeClubUwRegstatusupd(uwid: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      club <- Some(Club(au));
      uw <- model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid) orErr ("user is not a member");
      u <- uw.user
    ) yield {
      val ooldreg = club.reg(u) //orErr ("no registration record for year... ?")

      if (how == RegStatus.DELETE) {
        val r = uw.user.flatMap(u => club.reg(u))
        r.foreach(_.delete)
        r.toList flatMap { _.kids.toList } foreach { k =>
          k.delete
          // remove kid association as well
          RacerKidz.findAssocByClub(club).filter(a => a.to == k.rkId && a.assoc == RK.ASSOC_REGD).foreach(_.delete)
        }

        Redirect(routes.Club.doeClubReg(uwid))
      } else {
        val oldreg = ooldreg.getOrElse {
          val r = Reg(u._id, au.userName, club.curYear, uw.role, Seq(), how)
          r.create
          r
        }

        oldreg.copy(regStatus = how).update
        val reg = club.reg(u).get

        // if status just changed to PENDING, send email invitation
        if (!ooldreg.exists(how == _.regStatus) && how == RegStatus.PENDING) {
          SendEmail.withSession { implicit mailSession =>
            // notify user
            Emailer.sendEmailClubRegStart(u, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
          }
        }
        Redirect(routes.Club.doeClubReg(uwid))
      }

    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** add a kid to current registration */
  def doeClubUwAddForm(uwid: String, role: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      c <- Club.findForUser(au);
      form <- c.regForm(role) orErr ("no reg form for role " + role);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      uw <- model.Users.findUserLinksTo(model.WID("Club", au.userName)).find(_._id.toString == uwid) orErr ("no uw");
      u <- uw.user orErr ("oops - missing user?");
      reg <- Club(au).reg(u) orCorr ("no registration record for year... ?" -> "did you expire it first?")
    ) yield {
      val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${reg.year}-${reg.wids.size}")
      var label = s"${form.wid.name.replaceAll("_", " ")}"
      if (!label.contains(reg.year)) label = label + s" for season ${reg.year}"

      val newSt = if (Array(RegStatus.EXPIRED, RegStatus.PENDING) contains reg.regStatus) reg.regStatus else RegStatus.PENDING
      reg.copy(wids = reg.wids ++ Seq(newfwid), regStatus = newSt).update

      // have to create form ?
      if (!Wikis.find(newfwid).isDefined) {
        controllers.Forms.crForm(u, form.wid, newfwid, label, regAdmin, Some(role))
      }

      Redirect(routes.Club.doeClubReg(uwid))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** add a kid to current registration */
  def doeClubUwAddFormKid(regId: String, rkId: String, next: String, role: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      rk <- RacerKidz.findById(new ObjectId(rkId));
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      u <- if (!au.isClub) Some(au) else Users.findUserById(reg.userId) orErr ("User not found!");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin")
    ) yield {
      val nextPage = if (au.isClub)
        routes.Club.doeClubReg(next)
      else
        routes.Club.doeClubUserReg(regId)
      val before = ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id)

      if (!before.isDefined) {
        var r = reg
        val fwids = (for (form <- c.regForms.filter(_.role.startsWith(role))) yield {
          val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${rk.info.firstName}-${reg.year}-${reg.wids.size}")
          var label = s"${form.wid.name.replaceAll("_", " ")}-${rk.info.firstName}"
          if (!label.contains(reg.year)) label = label + s" for season ${reg.year}"

          val newSt = if (Array(RegStatus.EXPIRED, RegStatus.PENDING) contains reg.regStatus) reg.regStatus else RegStatus.PENDING

          r = r.copy(wids = r.wids ++ Seq(newfwid), regStatus = newSt)
          r.update

          // have to create form ?
          if (!Wikis.find(newfwid).isDefined) {
            controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
          }
          newfwid
        }).toList

        RegKid(reg._id, rk._id, fwids, role).create
        assoc(c, rk, model.RK.ASSOC_REGD, role, au, c.curYear)
        Redirect(nextPage)
      } else
        Msg2(s"""${rk.info.firstName} was already added as <em>${before.get.role}</em> - please click continue, then remove her/him from the registration with the red <span class="label label-important">x</span> button and then re-add with the different role. <p>Note that any forms filled for his role will be <em>removed</em>!""", 
            Some(nextPage.url))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** add a kid to current registration */
  def doeClubUwRMFormKid(regId: String, rkId: String, uwid: String, role: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    val next = if (auth.exists(_.isClub))
      routes.Club.doeClubReg(uwid)
    else
      routes.Club.doeClubUserReg(regId)
    (for (
      au <- activeUser;
      rk <- RacerKidz.findById(new ObjectId(rkId));
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      u <- if (!au.isClub) Some(au) else Users.findUserById(reg.userId) orErr ("User not found!");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      regkid <- ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id) orErr ("can't find regkid");
      notCompleted <- (!regkid.wids.flatMap(x => Wikis.find(x).flatMap(_.form.formState).toList).exists(_ == FormStatus.APPROVED)) orErr
        ("some forms have been approved for this person")
    ) yield {
      var r = reg
      regkid.wids.foreach { wid =>
        razie.clog << "drop form " + wid
        r = r.copy(wids = (r.wids.filter(_.name != wid.name)))

        // have to delete form ?
        if (Wikis.find(wid).isDefined) {
          //          controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
        }
      }

      r.update
      regkid.delete
      // not deleting assoc
      //      assoc(c, rk, model.RK.ASSOC_REGD, role, au, c.curYear)

      Redirect(next)
    }) getOrElse Msg2("CAN'T remove registration: " + errCollector.mkString,
      Some(next.toString))

  }

  /** build or update an association... there's a few possibilities */
  def assoc(c: Club, rk: RacerKid, assoc: String, role: String, owner: User, year: String) {
    // simple case, same rk again (member registers himself)
    val rka = ROne[RacerKidAssoc]("from" -> c.userId, "to" -> rk._id, "year" -> year)
    // if assoc already from Link, just update role
    if (rka.isDefined)
      rka.foreach(_.copy(role = role, assoc = RK.ASSOC_REGD).update)
    else {
      // TODO other use cases will be manual - should I notify?

      //      val rka = RMany[RacerKidAssoc]("from" -> c.userId, "year" -> year).map(
      //          x=>(x,x.to.as[RacerKid]).filter(t=>t._.2.)

      RacerKidAssoc(c.userId, rk._id, model.RK.ASSOC_REGD, role, owner._id).create
    }
  }

  /** add a kid to current registration */
  def doeClubUserRegAdd(regId: String, rkId: String, uid: String, next: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      rk <- RacerKidz.findById(new ObjectId(rkId));
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      u <- if (!au.isClub) Some(au) else Users.findUserById(reg.userId) orErr ("User not found!");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin")
    ) yield {
      Ok(views.html.club.doeClubUserRegAdd(rk, next, u, reg))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** list members */
  def doeClubUserRegs = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth
    ) yield {
      Ok(views.html.club.doeClubUserRegs(au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** */
  def doeClubUserReg(regid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- auth orCorr cNoAuth;
      reg <- model.Regs.findId(regid) orErr ("no reg found")
    ) yield {
      Ok(views.html.club.doeClubUserReg(au, reg))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeClubKids = Action { implicit request =>
    forActiveUser { au =>
      Ok(views.html.club.doeClubKidz(au))
    }
  }

  def doeVol = Action { implicit request =>
    forActiveUser { au =>
      val rks =
        (for (
          a <- model.RacerKidz.findAssocForUser(au._id);
          k <- model.RacerKidz.findById(a.to)
        ) yield (k, a)).toList.sortBy(x => x._1.info.lastName + x._1.info.firstName)

      val regs = Regs.findClub(Club("Glacier_Ski_Club").get.user).toList

      Ok(views.html.club.doeClubVol(au, rks, regs))
    }
  }

  def addvol(implicit request: Request[_]) = Form {
    tuple(
      "hours" -> number(min = 0, max = 16),
      "desc" -> text.verifying(vPorn, vSpec))
  }

  /** capture volunteer hour record */
  def doeVolAdd(rkaId: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId))
    ) yield {
      Ok(views.html.club.doeClubVolAdd(rkaId, rka, addvol.fill(0, "?"), au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** create volunteer hour record */
  def doeVolAdded(rkaId: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId))
    ) yield {
      addvol.bindFromRequest.fold(
        formWithErrors => BadRequest(views.html.club.doeClubVolAdd(rkaId, rka, formWithErrors, au)),
        {
          case (h, d) =>
            model.VolunteerH(new ObjectId(rkaId), h, d, au._id).create
            rka.copy(hours = rka.hours + h).update
            Redirect(routes.Club.doeVolAdd(rkaId))
        })
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** delete volunteer hour record */
  def doeVolDelete(rkaId: String, vhid: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registration only for a club");
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId));
      vh <- ROne[VolunteerH]("_id" -> new ObjectId(vhid))
    ) yield {
      vh.delete
      rka.copy(hours = rka.hours - vh.hours).update
      Redirect(routes.Club.doeVolAdd(rkaId))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // rollup per family/reg
  def volFamily(rk: RacerKid, rks: List[(model.RacerKid, model.RacerKidAssoc)], regs: List[Reg]) = {
    (for (
      uid <- rk.userId;
      reg <- regs.find(_.userId == uid)
    ) yield {
      val rkids = reg.kids.toList.flatMap(_.rkId.as[RacerKid].toList).flatMap(_.all)
      val x = rks.filter(x => rkids exists (_._id == x._1._id)).map(_._2.hours)
      if (x.isEmpty) "" else x.sum.toString
    }) getOrElse ""
  }

  def doeMergeKid(todie: String, tolive: String) = Action { implicit request =>
    implicit val errCollector = new VError()
    (for (
      au <- activeUser;
      // works for users too
      //      isClub <- au.isClub orErr ("registration only for a club");
      dies <- ROne[RacerKidAssoc]("_id" -> new ObjectId(todie));
      lives <- ROne[RacerKidAssoc]("_id" -> new ObjectId(tolive))
    ) yield {
      var liverka = lives.copy(hours = lives.hours + dies.hours)
      var liverk = lives.rk.get
      var dierk = dies.rk.get

      // also copy the individual records
      RMany[VolunteerH]("rkaId" -> dies._id) map (_.copy(rkaId = lives._id).update)

      // gets a record - has gender, better birth date etc
      if (!lives.rk.get.rkiId.isDefined && dies.rk.get.rkiId.isDefined)
        liverk = liverk.copy(rkiId = dies.rk.get.rkiId)

      // if the one that dies had better info, update it
      if (!lives.rk.get.userId.isDefined && dies.rk.get.userId.isDefined)
        liverk = liverk.copy(userId = dies.rk.get.userId)

      if (liverk._id != dierk._id) {
        // different record, old one id old, new one is new
        liverk = liverk.copy(oldRkId = lives.rk.get.oldRkId ++ Seq(dies.rk.get._id) ++ dies.rk.get.oldRkId)
        dierk = dierk.copy(newRkId = Option(liverk._id))
      }

      val rk = ROne[RacerKidAssoc]("_id" -> new ObjectId(tolive)) flatMap (_.rk) get

      // override with email from user account
      rk.user.foreach { u =>
        rk.rki.foreach { rki =>
          rki.copy(email = u.email).update
        }
      }

      liverka.update
      liverk.update
      dierk.update
      dies.delete

      if (au.isClub) Redirect(routes.Club.doeClubKids)
      else Redirect(routes.Kidz.doeUserKids)
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // new user linked to club - give it access to forums
  def linkUser(u: User, cname: String, how: String) = {
    Club(cname) foreach { club =>
      club.newFollows.foreach { rw =>
        val role = if (how == "Fan") "Fan" else rw.role
        this dbop model.UserWiki(u._id, rw.wid, role).create
      }
    }
  }

  // stuff to do 
  def tempChangeYUear {
    // 1. for each user member, create a rka
    RMany[model.UserWiki]().filter(_.wid.cat == "Club").foreach { uw =>
      val rk = model.RacerKidz.myself(uw.userId)
      cout << uw
      val c = controllers.Club(uw.wid.name)
      c.foreach { c =>
        model.RacerKidAssoc(c.userId, rk._id, model.RK.ASSOC_LINK, uw.role, c.userId).create
      }
    }

  }

}

