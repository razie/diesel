package controllers

import admin.{Corr, Config, SendEmail, VErrors}
import controllers.Profile._
import controllers.Tasks._
import db.RMongo.as
import db.{REntity, RMany, ROne}
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}
import razie.{clog, Logging, cout}

import scala.Array.canBuildFrom
import scala.Option.option2Iterable

case class RoleWid(role: String, wid: WID)

/** additional information for a club - associated to a regular user, designated type=organization */
@db.RTable
case class Club(
  userId: ObjectId,
  userName: String,
  regType: String = "",
  curYear: String = Config.curYear,
  regAdmin: String = "",
  dsl: String = "",
  _id: ObjectId = new ObjectId) extends REntity[Club] {

  def uwid = Wikis.rk.find("Club", userName).get.uwid //todo ensure all clubs have a wiki topic

  // optimize access to User object
  lazy val user = oUser.getOrElse(Users.findUserById(userId).get)
  private var oUser: Option[User] = None
  def setU(u: User) = { oUser = Some(u); this }

  /** props like Follows.Fan will be rolled up and killed in a map so you can find them in this here seq */
  lazy val propSeq = Config parsep dsl
  lazy val props = propSeq.toMap

  def isRegOpen = propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2)
  lazy val regForms = propSeq.filter(_._1 startsWith "Reg.").map(t =>
    RoleWid(t._1.replaceFirst("Reg.", ""), WID.fromPath(t._2).get))
  lazy val newFollows = propSeq.filter(_._1 startsWith "Follows.").map(t =>
    RoleWid(t._1.replaceFirst("Follows.", ""), WID.fromPath(t._2).get))
  lazy val filterRegFields = props.get("FilterFields").getOrElse("") split ","

  lazy val newTasks = propSeq filter (_._1 startsWith "Task.") map { t =>
    val PAT1 = "([^,]*),(.*)".r
    val PAT1(name, args) = t._2
    (name, args.split(",").map(x => x.split(":")(0) -> x.split(":")(1)).toMap)
  }

  lazy val msgFormsAccepted = (props filter (_._1 startsWith "Msg.formsAccepted")).toSeq.sortBy (_._1) map (_._2) mkString ("<p>")

  def regForm(role: String) = regForms.find(_.role == role)
  def uregAdmin = Users.findUser(Enc(regAdmin))

  def reg(u: User) = model.Regs.findClubUserYear(user, u._id, curYear)
  def reg(wid: WID) = model.Regs.findWid(wid)
  def userLinks = model.Users.findUserLinksTo(uwid)

  // TODO filter by year as well
  def roleOf(rkId: ObjectId) =
    // registered or owned
    ROne[RacerKidAssoc]("from" -> userId, "to" -> rkId).map(_.role).mkString

  def wid = WID("Club", user.userName)

  def rkAssocs = RMany[RacerKidAssoc]("from" -> userId, "year" -> curYear)
}

// just some helpers
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
  def findForName(n: String) = ROne[Club]("userName"->n)//.find(_.user.userName == n) // TODO optimize

  def findForReviewer(u: User) = Some(apply(u)) // TODO allow many reviewers per club

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

  import play.api.data.Forms._
  import play.api.data._

  // create profile
  case class Member(
    firstName: String,
    lastName: String,
    email: String,
    userType: String,
    pass: String,
    yob: Int,
    address: String)

  def doeClubRegs (details:String="") = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      uuwid <- WID("Club", au.userName).uwid orErr ("no uwid");
      isClub <- au.isClub orErr ("registrations possible only for a club")
    ) yield {

      val members =
	model.Users.findUserLinksTo(uuwid).map(uw =>
	  (model.Users.findUserById(uw.userId),
	    uw,
	    model.Regs.findClubUserYear(au, uw.userId, controllers.Club(au).curYear))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)

      Ok(views.html.club.doeClubRegs(au,!details.isEmpty, members))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def edRegForm(implicit request: Request[_]) = Form {
    tuple(
      "regType" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "curYear" -> number(min = 2012, max = 2025),
      "regAdmin" -> text.verifying("Invalid user", { x => Users.findUser(Enc(x)).isDefined }),
      "dsl" -> text) verifying
      ("Can't use last name for organizations!", { t: (String, Int, String, String) =>
	true
      })
  }

  /** registration settings */
  def doeClubRegSettings = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isClub <- au.isClub orErr ("registrations possible only for a club");
      c <- Club.findForUser(au).orElse({ val x = Club(au); x.create; Some(x) })
    ) yield {
      Ok(views.html.club.doeClubRegSettings(edRegForm.fill(
	(c.regType, c.curYear.toInt, c.regAdmin, c.dsl)),
	au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeClubUpdateRegSettings = Action { implicit request =>
    edRegForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.club.doeClubRegSettings(formWithErrors, auth.get)),
      {
	case (t, y, a, d) => forActiveUser { au =>
	  val c1 = Club.findForUser(au).get.copy(
	    regType = t,
	    curYear = y.toString,
	    regAdmin = a,
	    dsl = d)
	  c1.update
	  Redirect(routes.Club.doeClubRegSettings)
	}
      })
  }

  ////////////////////// manage user

  def mngUserForm(implicit request: Request[_]) = Form {
    tuple(
      "role" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "regStatus" -> text,
      "paid" -> text)
  }

  // manage user screen
  def doeClubReg(uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr ("registration only for a club");
      uuwid <- WID("Club", au.userName).uwid orErr ("no uwid");
      uw <- model.Users.findUserLinksTo(uuwid).find(_._id.toString == uwid);
      u <- uw.user
    ) yield {
      val reg = Club(au).reg(u)
      Ok(views.html.club.doeClubReg(mngUserForm.fill(
	(uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)), uw, au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  import play.api.libs.json._

  def doeClubRegsReportJson(what: String, cols: String) = doeClubRegsReport(what, cols, "json")
  def doeClubRegsReportCsv(what: String, cols: String) = doeClubRegsReport(what, cols, "csv")
  def doeClubRegsReport(what: String, cols: String, format:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val DELIM = ","
    (for (
      isClub <- au.isClub orErr ("registration only for a club")
    ) yield {
      val (headers, data) = membersData(au, what, cols)

      if("csv" == format)
	Ok(
	  headers.mkString(DELIM) +
	    "\n" +
	    data.map(_.mkString(DELIM)).mkString("\n")).as("text/csv")
      else
	Ok(Json.toJson(headers :: data))
    }) getOrElse Msg2("CAN'T : " + errCollector.mkString)
  }

  // update user role
  def doeClubMemberUpdate(uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr "registration only for a club";
      cuwid <- WID("Club", au.userName).uwid orErr "no uwid";
      olduw <- model.Users.findUserLinksTo(cuwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- olduw.user orErr "no olduw user"
    ) yield {
      mngUserForm.bindFromRequest.fold(
	formWithErrors => BadRequest(views.html.club.doeClubReg(formWithErrors, olduw, au)),
	{
	  case (r, s, p) =>
	    olduw.updateRole(r)
	    //reload
	    val uw = model.Users.findUserLinksTo(cuwid).find(_._id.toString == uwid).get

	    // update the role of the assoc as well
	    for (
	      rk <- ROne[RacerKid]("ownerId" -> olduw.userId, "userId" -> olduw.userId, "kind" -> RK.KIND_MYSELF);
	      rka <- ROne[RacerKidAssoc]("from" -> au._id, "to" -> rk._id, "year" -> Club(au).curYear)
	    ) if (rka.role != r) rka.copy(role = r).update

	    var reg = Club(au).reg(u)
	    if (reg.exists(_.paid != p)) {
	      reg = Some(reg.get.copy(paid = p))
	      reg.get.update
	    }

	    Ok(views.html.club.doeClubReg(mngUserForm.fill(
	      (uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)), uw, au))
	})
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** send a help message */
  def doeClubRegMsg(uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr "registration only for a club";
      club <- Some(Club(au));
      cuwid <- WID("Club", au.userName).uwid orErr "no uwid";
      uw <- model.Users.findUserLinksTo(cuwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- uw.user;
      msg <- request.queryString.get("msg") orErr "no message";
      reg <- club.reg(u) orErr "no registration record for year... ?"
    ) yield {
      SendEmail.withSession { implicit mailSession =>
	// notify user
	Emailer.sendEmailClubRegHelp(u, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString, msg.mkString)
      }
      Redirect(routes.Club.doeClubReg(uwid))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** change registration status */
  def doeClubUwRegstatusupd(uwid: String, how: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr "registration only for a club";
      club <- Some(Club(au));
      cuwid <- WID("Club", au.userName).uwid orErr ("no uwid");
      uw <- model.Users.findUserLinksTo(cuwid).find(_._id.toString == uwid) orErr ("user is not a member");
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
  def doeClubUwAddForm(uwid: String, role: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr ("registration only for a club");
      c <- Club.findForUser(au);
      form <- c.regForm(role) orErr ("no reg form for role " + role);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      uuwid <- WID("Club", au.userName).uwid orErr ("no uwid");
      uw <- model.Users.findUserLinksTo(uuwid).find(_._id.toString == uwid) orErr ("no uw");
      u <- uw.user orErr ("oops - missing user?");
      reg <- Club(au).reg(u) orCorr ("no registration record for year... ?" -> "did you expire it first?")
    ) yield {
      addForm(u, c, reg, regAdmin, role)
      Redirect(routes.Club.doeClubReg(uwid))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** add a kid to current registration */
  private def addForm(u: User, c: Club, reg: Reg, regAdmin: User, role: String) {
    val form = c.regForm(role).get
    val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${reg.year}-${reg.wids.size}")
    var label = s"${form.wid.name.replaceAll("_", " ")}"
    if (!label.contains(reg.year))
      label = label + s" for season ${reg.year}"

    val newSt = if (Array(RegStatus.EXPIRED, RegStatus.PENDING) contains reg.regStatus) reg.regStatus else RegStatus.PENDING
    reg.copy(wids = reg.wids ++ Seq(newfwid), regStatus = newSt).update

    // have to create form ?
    if (!Wikis.find(newfwid).isDefined) {
      controllers.Forms.crForm(u, form.wid, newfwid, label, regAdmin, Some(role))
    }
  }

  /** add a kid to current registration */
  def doeClubUwAddFormKid(regId: String, rkId: String, next: String, role: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
    (for (
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
	  val fn = rk.info.firstName.replaceAll (" ", "-")
	  val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${fn}-${reg.year}-${reg.wids.size}")
	  var label = s"${form.wid.name.replaceAll("_", " ")}-${fn}"
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

  /** remove a kid from the current registration */
  def doeClubUwRMFormKid(regId: String, rkId: String, uwid: String, role: String) =  FAU { implicit au =>
    implicit errCollector => implicit request =>
    (for (
      rk <- RacerKidz.findById(new ObjectId(rkId));
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      regkid <- ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id) orErr ("can't find regkid");
      notCompleted <- (!regkid.wids.flatMap(x => Wikis.find(x).flatMap(_.form.formState).toList).exists(_ == FormStatus.APPROVED)) orErr
	("some forms have been approved for this person")
    ) yield {
      def sex1 = if (rk.info.gender.toLowerCase startsWith "m") "his" else "her"
      def sex2 = if (rk.info.gender.toLowerCase startsWith "m") "him" else "her"
      Msg2(s"""This will remove ${rk.info.firstName} from this registration.
    <p>Note that any forms filled for $sex1 role will be <em>removed</em>! You can then re-add $sex2 back, with the same or different role.
    <p>If you don't want to remove $sex2, just go back... otherwise click Continue below.""",
	Some(routes.Club.doeClubUwRMFormKid1(regId, rkId, uwid, role).url))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  def doeClubUwRMFormKid1(regId: String, rkId: String, uwid: String, role: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
    val next = if (auth.exists(_.isClub))
      routes.Club.doeClubReg(uwid)
    else
      routes.Club.doeClubUserReg(regId)
    (for (
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
	clog << "drop form " + wid
	r = r.copy(wids = (r.wids.filter(_.name != wid.name)))

	// have to delete form ?
	if (Wikis.find(wid).isDefined) {
	  //	      controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
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

  /** remove a single form from the current registration */
  def doeClubUwRMFormSeq(regId: String, rkId: String, uwid: String, seq: Integer) =  FAU { implicit au =>
    implicit errCollector => implicit request =>
    (for (
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      isValid <- seq >= 0 && seq < reg.wids.size orErr "bad form seq";
      formWid <- Some(reg.wids(seq));
      form <- Wikis.find(formWid) orErr "can't find form";
      regkid <- RMany[RegKid]("regId" -> reg._id).find(_.wids.exists(_.name == formWid.name)).isEmpty orErr "this form belongs to a regKid: remove the kid from the list of Racers";
      notCompleted <- form.form.formState != FormStatus.APPROVED orErr "form has been approved !"
    ) yield {
      Msg2(s"""This will remove Form: ${formWid.name} from this registration.
    <p>If you don't want to remove Form: ${formWid.name}, just go back... otherwise click Continue below.""",
	Some(routes.Club.doeClubUwRMFormSeq1(regId, rkId, uwid, seq).url))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** remove a single form from the current registration */
  def doeClubUwRMFormSeq1(regId: String, rkId: String, uwid: String, seq: Integer) = FAU { implicit au =>
    implicit errCollector => implicit request =>
    val next = if (auth.exists(_.isClub))
      routes.Club.doeClubReg(uwid)
    else
      routes.Club.doeClubUserReg(regId)
    (for (
      reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
      club <- if (au.isClub) Some(au) else Users.findUserByUsername(reg.clubName) orErr ("Club not found");
      u <- if (!au.isClub) Some(au) else Users.findUserById(reg.userId) orErr ("User not found!");
      c <- Club.findForUser(club);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      isValid <- seq >= 0 && seq < reg.wids.size orErr "bad form seq";
      formWid <- Some(reg.wids.apply(seq));
      form <- Wikis.find(formWid) orErr "can't find form";
      regkid <- RMany[RegKid]("regId" -> reg._id).find(_.wids.exists(_.name == formWid.name)).isEmpty orErr "this form belongs to a regKid";
      notCompleted <- form.form.formState != FormStatus.APPROVED orErr "form has been approved !"
    ) yield {
      var r = reg
      clog << "drop form " + formWid
      r = r.copy(wids = (r.wids.filter(_.name != formWid.name)))

      // have to delete form ?
      // controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
      r.update
      Redirect(next)
    }) getOrElse Msg2("CAN'T remove form: " + errCollector.mkString,
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
      RacerKidAssoc(c.userId, rk._id, model.RK.ASSOC_REGD, role, owner._id, 0, year).create
    }
  }

  /** add a kid to current registration */
  def doeClubUserRegAdd(regId: String, rkId: String, uid: String, next: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
    (for (
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
    implicit val errCollector = new VErrors()
    (for (
      au <- auth orCorr cNoAuth;
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent
    ) yield {
      if (au.isClub)
	Redirect("/doe/club/regs")
      else
	Ok(views.html.club.doeClubUserRegs(au))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** */
  def doeClubUserReg(regid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- auth orCorr cNoAuth;
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      reg <- model.Regs.findId(regid) orErr ("no reg found")
    ) yield {
      Ok(views.html.club.doeClubUserReg(au, reg, RacerKidz.findForUser(reg.userId).toList))
    }) getOrElse Msg2("CAN'T use registration " + errCollector.mkString)
  }

  def doeClubKids = FAU { implicit au => implicit errCollector => implicit request =>
    Ok(views.html.club.doeClubKidz(au))
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
    implicit val errCollector = new VErrors()
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
    implicit val errCollector = new VErrors()
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
    implicit val errCollector = new VErrors()
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
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      // works for users too
      //      isClub <- au.isClub orErr ("registration only for a club");
      dies <- ROne[RacerKidAssoc]("_id" -> new ObjectId(todie));
      lives <- ROne[RacerKidAssoc]("_id" -> new ObjectId(tolive))
    ) yield {
      val liverka = lives.copy(hours = lives.hours + dies.hours)
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
  def linkUser(u: User, cname: String, how: String)(implicit txn: db.Txn) = {
    Club(cname) foreach { club =>
      club.newFollows.foreach { rw =>
	val role = if (how == "Fan") "Fan" else rw.role
	rw.wid.uwid.foreach { uwid =>
	  if (u.isLinkedTo(uwid).isEmpty)
	    model.UserWiki(u._id, uwid, role).create
	}
      }
      club.newTasks.foreach { t => //(name, args)
	clog << "Creating user Task " + t
	//todo if not already
	UserTask(u._id, t._1, t._2.toMap).create
      }
    }
  }

  /** called by user not the club - copy previous registratrion forms and then continue */
  def doeStartRegCopy(clubName: String, prevRegId: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Club(clubName);
      regAdmin <- c.uregAdmin orErr "Registration is not open yet! [no regadmin]";
      isOpen <- c.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr "Registration is not open yet!";
      uclub <- Users.findUserByUsername(clubName);
      prevReg <- model.Regs.findId(prevRegId);
      // either mine or i'm the club
      isMine <- (prevReg.userId == au._id && prevReg.clubName == clubName || prevReg.clubName == au.userName) orErr ("Not your registration: " + prevRegId);
      user <- Users.findUserById(prevReg.userId) orErr ("Cant find user: " + prevReg.userId);
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      isSame <- (prevReg.year != c.curYear) orErr ("Can't copy this year's registration: " + prevRegId)
    ) yield {
      // 1. expire
      db.tx("userStartRegCopy") { implicit txn =>
	cout << "OLD REG: " << prevReg
	var reg = prevReg.copy(_id = new ObjectId(), year = c.curYear, regStatus = RegStatus.EXPIRED, paid="")

	cout << "REG: " << reg

       val widNameMap = new collection.mutable.HashMap[String,String]()

	val newWids = reg.wids.map { wid =>
	  cout << "  proc " << wid
	  val oldW = Wikis.find(wid).get

	  // find and upgrade the year
	  val PAT = "([^-]+)-([^-]+)-([^-]+)(-.*)?-([^-]+)-([^-]+)".r
	  val PAT(name, role, id, kkid, y, num) = oldW.wid.name
	  val kid = Option(kkid) getOrElse ""
	  val kidName = if(kid startsWith "-") kid.substring(1) else kid

	  // new form spec
	  val newForm = c.regForms.find(_.wid.name == name).getOrElse {
	    val newname = name.replaceFirst(prevReg.year, reg.year)
	    c.regForms.find(_.wid.name == newname).get // todo kaboom
	  }
	  cout << "  new form: " << newForm

	  val newfwid = WID("Form", s"${newForm.wid.name}-${role}-${id + kid}-${reg.year}-${num}")
	  cout << "  old  wid: " << oldW.wid
	  cout << "  new fwid: " << newfwid

	  widNameMap put (oldW.wid.name, newfwid.name)

	  //	// have to create form ?
	  if (!Wikis.find(newfwid).isDefined) {
	    var label = s"${newForm.wid.name.replaceAll("_", " ")}"
	    if (!label.contains(reg.year))
	      label = label + s" for $kidName season ${reg.year}"
	    val newW = controllers.Forms.copyForm (user, oldW, newfwid.name, label, newForm, c.filterRegFields)

	    cout << "  old form: " << oldW
	    cout << "  new form: " << newW
	  }

	  newfwid
	}

	reg = reg.copy (wids = newWids)
	cout << "  NEW REG: " << reg

	reg.create

	// copy kid regs with new form names as well
	prevReg.kids.toList.map{ ork =>
	  val rk = ork.copy(_id = new ObjectId(), regId = reg._id, wids=ork.wids.map(ow => WID(ow.cat, widNameMap(ow.name))), crDtm=DateTime.now())
	  cout << "   NEW REGKID" << rk
	  rk.create
	  assoc(c, rk.rk.get, model.RK.ASSOC_REGD, rk.role, au, c.curYear)
	}


	//	3. start - notify user
	      SendEmail.withSession { implicit mailSession =>
	//	  Emailer.sendEmailClubRegStart(au, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
	//	  Emailer.tellRaz("Started registration", "user: " + au.userName, "club: " + clubName, "how: "+how)
	////	    TODO tell regAdmin so they know...
	//
	//	  UserTask(au._id, UserTasks.START_REGISTRATION).delete
	//	}
	    Redirect(routes.Club.doeClubUserReg(reg._id.toString))
      }
      }
    }) getOrElse  {
      if(activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
	Ok(views.html.user.doeConsent(activeUser.get))
      else
	Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
      }
  }

  /** called by user not the club */
  def doeStartRegSimple(clubName: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Club(clubName);
      regAdmin <- c.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- c.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr ("Registration is not open yet!");
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      uclub <- Users.findUserByUsername(clubName)
    ) yield {
      Ok(views.html.club.doeClubUserStartReg(au, clubName))
    }) getOrElse  {
      if(activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
	Ok(views.html.user.doeConsent(activeUser.get, routes.Club.doeStartRegSimple(clubName).url))
      else
	Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
    }
  }

  /** called by user not the club */
  def doeStartReg(clubName: String, how: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Club(clubName);
      regAdmin <- c.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- c.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr ("Registration is not open yet!");
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      uclub <- Users.findUserByUsername(clubName)
    ) yield {
      // current registration?
      db.tx("userStartReg") { implicit txn =>
	if ("None" == how) {
	  UserTask(au._id, UserTasks.START_REGISTRATION).delete
	  Redirect("/")
	} else Regs.findClubUserYear(uclub, au._id, c.curYear).map {reg=>
	  Msg2("Registration already in progress for club " + clubName, Some(routes.Club.doeClubUserReg(reg._id.toString).url))
	} getOrElse {
	  // 1. expire
	  var reg = Reg(au._id, clubName, c.curYear, RK.ROLE_MEMBER, Seq(), RegStatus.PENDING)
	  reg.create

	  // 2. add family
	  cout << "3 " + c.regForms.filter(_.role startsWith how).mkString
	  if (how == "Family")
	    c.regForms.filter(_.role startsWith how).foreach { rw =>
	      addForm(au, c, reg, regAdmin, rw.role)
	      reg = Regs.findClubUserYear(uclub, au._id, c.curYear).get
	    }

	  // 3. start - notify user
	  SendEmail.withSession { implicit mailSession =>
	    Emailer.sendEmailClubRegStart(au, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
	    Emailer.tellRaz("Started registration", "user: " + au.userName, "club: " + clubName, "how: " + how)
	    // TODO tell regAdmin so they know...
	    UserTask(au._id, UserTasks.START_REGISTRATION).delete
	    Redirect(routes.Club.doeClubUserReg(reg._id.toString))
	  }
	}
      }
    }) getOrElse  {
      if(activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
	Ok(views.html.user.doeConsent(activeUser.get))
      else
	Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
      }
  }

  // stuff to do
  def tempChangeYUear {
    // 1. for each user member, create a rka
    RMany[model.UserWiki]().filter(_.uwid.cat == "Club").foreach { uw =>
      val rk = model.RacerKidz.myself(uw.userId)
      cout << uw
      val c = controllers.Club(uw.uwid.wid.get.name)
      c.foreach { c =>
	model.RacerKidAssoc(c.userId, rk._id, model.RK.ASSOC_LINK, uw.role, c.userId).create
      }
    }
  }

  /** called by club */
  def doeCreateRegTask(uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      isClub <- au.isClub orErr "registration only for a club";
      club <- Some(Club(au));
      regAdmin <- club.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- club.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr ("Registration is not open yet!");
      cuwid <- WID("Club", au.userName).uwid orErr ("no uwid");
      uw <- model.Users.findUserLinksTo(cuwid).find(_._id.toString == uwid) orErr ("user is not a member");
      u <- uw.user
    ) yield {
      // current registration?
      db.tx("userStartReg") { implicit txn =>
	if(! u.tasks.exists(_.name == UserTasks.START_REGISTRATION))
	  UserTask(u._id, UserTasks.START_REGISTRATION, Map("club" -> au.userName)).create
	}
      Redirect(routes.Club.doeClubReg(uwid))
    }) getOrElse  {
      Msg2("CAN'T " + errCollector.mkString)
    }
  }

}

