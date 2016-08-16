package controllers

import play.api.i18n.Messages
import play.api.i18n.MessagesApi
import play.api.i18n.Messages.Implicits._
import play.api.Play.current

import admin.Config
import akka.actor.{Actor, Props}
import controllers.Wiki._
import model.RK
import play.api.libs.concurrent.Akka
import play.mvc.Result
import play.twirl.api.Html
import razie.wiki.admin.SendEmail
import controllers.Profile._
import controllers.Tasks._
import razie.db.RMongo.as
import razie.db.{RTable, REntity, RMany, ROne}
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}
import razie.wiki.{Dec, WikiConfig, EncUrl, Enc}
import razie.wiki.model.{Reactors, Wikis, WID, FormStatus}
import razie.{clog, Logging, cout}
import views.html.club.doeClubRegsRepHtml
import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import razie.wiki.util.VErrors
import razie.wiki.admin.SendEmail
import razie.db.Txn

import scala.concurrent.Future

case class RoleWid(role: String, wid: WID)

/** additional information for a club - associated to a regular user, designated type=organization */
@RTable
case class Club (
  userId: ObjectId, // not used anymore
  userName: String, // it's actually the topic name
  regType: String = "",
  curYear: String = Config.curYear,
  regAdmin: String = "",
  dsl: String = "",
  _id: ObjectId = new ObjectId) extends REntity[Club] {

  def uwid = Wikis.rk.find("Club", userName).get.uwid //todo ensure all clubs have a wiki topic

  def name = userName

  // optimize access to User object
  lazy val user = oUser.getOrElse(Users.findUserById(userId).get)
  private var oUser: Option[User] = None
  def setU(u: User) = { oUser = Some(u); this }

  /** props like Follows.Fan will be rolled up and killed in a map so you can find them in this here seq */
  lazy val propSeq = WikiConfig parsep dsl
  lazy val props = propSeq.toMap

  def isRegOpen = propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2)
  lazy val regForms = propSeq.filter(_._1 startsWith "Reg.").map(t =>
    RoleWid(t._1.replaceFirst("Reg.", ""), WID.fromPath(t._2).get))
  lazy val newFollows = propSeq.filter(_._1 startsWith "Follows.").map(t =>
    RoleWid(t._1.replaceFirst("Follows.", ""), WID.fromPath(t._2).get))
  lazy val filterRegFields = props.get("FilterFields").getOrElse("") split ","
  lazy val volunteering = props.get("Volunteering").getOrElse("") split ","

  def isAdminEmail(s:String) = props.get("adminEmails").exists(_.split(",").contains(s))
  def isClubAdmin(u: User) = u.isAdmin || isAdminEmail(Dec(u.email))
  def isClubCoach(u:User) = isMemberRole(u._id, "Coach")

  def isMemberRole(userId: ObjectId, role:String) = membership(userId).exists(_.role == role)
  def isMember(u: User) = membership(u._id).isDefined
  def membership(userId: ObjectId) = ROne[UserWiki]("uwid" -> uwid.grated, "userId" -> userId)

  override def equals (other:Any) = other match {              // or override them
    case c:Club => name == c.name
    case _ => false                             // wildcard serves like a default case
  }

  lazy val newTasks = propSeq filter (_._1 startsWith "Task.") map { t =>
    val PAT1 = "([^,]*),(.*)".r
    val PAT1(name, args) = t._2
    (name, args.split(",").map(x => x.split(":")(0) -> x.split(":")(1)).toMap)
  }

  lazy val approvers = propSeq filter (_._1 startsWith "Approver.") map { t =>
    (t._1.replaceFirst("Approver.", ""), t._2)
  }

  lazy val msgFormsAccepted = (props filter (_._1 startsWith "Msg.formsAccepted")).toSeq.sortBy (_._1) map (_._2) mkString ("<p>")

  // P.1=y AND P.1.name=x returns map (x,y)
  def nvp (prefix:String) = (props filter (_._1 startsWith prefix)).toSeq.map(kv=>(kv._1.replace(prefix+".",""), kv._2)).sortBy(_._1).groupBy(t=> t._1.substring(0,t._1.indexOf('.')-1)).toSeq.map(g=>(g._2.find(_._1.contains("name")).get._2,g._2.find(! _._1.contains("name")).get._2))

  def regForm(role: String) = regForms.find(_.role == role)
  def uregAdmin = Users.findUser(Enc(regAdmin))

  def reg(u:User) = model.Regs.findClubUserYear(name, u._id, curYear)
  def reg(wid: WID) = model.Regs.findWid(wid)
  def userLinks = model.Users.findUserLinksTo(uwid)

  // TODO filter by year as well
  def roleOf(rkId: ObjectId) =
    // registered or owned
    ROne[RacerKidAssoc]("from" -> userId, "to" -> rkId).map(_.role).mkString

  def wid = WID("Club", user.userName)

  def rka : Iterator[RacerKidAssoc] = rka("")
  def rka(role:String="") =
    if (role.isEmpty || role == "*")
      RMany[RacerKidAssoc]("from" -> userId, "year" -> curYear)
    else
      RMany[RacerKidAssoc]("from" -> userId, "year" -> curYear, "role" -> role)

  def rka(rkId:ObjectId, role:String) =
    if (role.isEmpty || role == "*")
      ROne[RacerKidAssoc]("from" -> userId, "year" -> curYear, "to"->rkId)
  else
      ROne[RacerKidAssoc]("from" -> userId, "year" -> curYear, "to"->rkId, "role"->role)

  def rk = {
    val mine = rka map (_.to) flatMap (RacerKidz.findById)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }

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
  def findForAdmin(n: String, u:User) = ROne[Club]("userName"->n).filter(c=> u.isAdmin || c.isAdminEmail(Dec(u.email)))

  def findForReviewer(u: User) = Some(apply(u)) // TODO allow many reviewers per club

  // manage user screen
  def membersData(club:Club, what: String, cols: String): (List[String], List[List[String]]) = {
    val members = club.userLinks.map(_.userId)
    val regs = model.Regs.findClubYear(club.name, club.curYear)
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

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def doeClubRegs (clubName:String, details:String="") = Action.async { implicit request =>
      Future {
          implicit val errCollector = new VErrors()
          (for (
            au <- activeUser;
            c <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
          ) yield {
              val members =
                model.Users.findUserLinksTo(c.uwid).map(uw =>
                  (model.Users.findUserById(uw.userId),
                    uw,
                    model.Regs.findClubUserYear(clubName, uw.userId, c.curYear))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)

              ROK.r apply { implicit stok => views.html.club.doeClubRegs(clubName, au,details, members) }
            }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
      }
  }

  def edRegForm(implicit request: Request[_]) = Form {
    tuple(
      "regType" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "curYear" -> number(min = 2012, max = 2025),
      "regAdmin" -> text.verifying("Invalid user", { x => Users.findUser(Enc(x)).isDefined }),
      "dsl" -> text) verifying
      ("Can't use last name for organizations!", { t: (String, Int, String, String) =>
        true
      })
  }

  /** registration settings */
  def doeClubRegSettings(clubName:String) = FAUR { implicit request =>
    (for (
      c <- Club.findForAdmin(clubName, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
        ROK.k apply { implicit stok => views.html.club.doeClubRegSettings(clubName, edRegForm.fill(
        (c.regType, c.curYear.toInt, c.regAdmin, c.dsl))) }
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  def doeClubUpdateRegSettings(clubName:String) = FAUR {implicit request =>
    edRegForm.bindFromRequest()(request.ireq).fold(
      formWithErrors => ROK.k badRequest { implicit stok => views.html.club.doeClubRegSettings(clubName, formWithErrors) },
      {
        case (t, y, a, d) => forActiveUser { au =>
          val c1 = Club.findForAdmin(clubName, au).get.copy(
            regType = t,
            curYear = y.toString,
            regAdmin = a,
            dsl = d)
          c1.update
          Redirect(routes.Club.doeClubRegSettings(clubName))
        }
      })
  }

  ////////////////////// manage user

  def mngUserForm(implicit request: Request[_]) = Form {
    tuple(
      "role" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "regStatus" -> text,
      "paid" -> text)
  }

  // club admin panel
  def doeClubAdminPanel(club:String) = FAUR { implicit request =>
    (for (
      c <- Club(club) orErr ("Not a club")
    ) yield {
        ROK.k noLayout { implicit stok =>
          views.html.club.doeClubAdminPanel(c)
        }
      }) getOrElse unauthorized()
  }

  // manage user screen
  def doeClubReg(club:String, uwid: String) = FAUR { implicit request =>
    (for (
      c <- Club.findForAdmin(club, request.au.get) orErr ("Not a club or you're not admin");
      uw <- model.Users.findUserLinksTo(c.uwid).find(_._id.toString == uwid);
      u <- uw.user
    ) yield {
      val reg = c.reg(u)
        ROK.k apply { implicit stok =>
          views.html.club.doeClubReg(c, mngUserForm.fill(
          (uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)), uw)
        }
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  import play.api.libs.json._

  def doeClubRegsReportHtml(club:String, what: String, cols: String) = FAUR { implicit request =>
    (for (
      c <- Club.findForAdmin(club, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
        val regs = model.Regs.findClubYear(club, c.curYear)
        val forms = regs.flatMap(_.wids).flatMap(_.page).filter(_.formRole.exists(_ == what)).toList

        ROK.k noLayout {implicit stok=>doeClubRegsRepHtml(request.au.get, forms)}
      }) getOrElse Msg2("CAN'T : " + errCollector.mkString)
  }

  def doeClubRegsReportJson(club:String, what: String, cols: String) = doeClubRegsReport(club, what, cols, "json")
  def doeClubRegsReportCsv(club:String, what: String, cols: String) = doeClubRegsReport(club, what, cols, "csv")
  def doeClubRegsReport(clubName:String, what: String, cols: String, format:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val DELIM = ","
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
    ) yield {
      val (headers, data) = membersData(club, what, cols)

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
  def doeClubMemberUpdate(clubName:String, uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin");
      olduw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- olduw.user orErr "no olduw user"
    ) yield {
      mngUserForm.bindFromRequest.fold(
        formWithErrors => ROK.s badRequest { implicit stok =>(views.html.club.doeClubReg(club, formWithErrors, olduw))},
        {
          case (r, s, p) =>
            olduw.updateRole(r)
            //reload
            val uw = model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid).get

            // update the role of the assoc as well
            for (
              rk <- ROne[RacerKid]("ownerId" -> olduw.userId, "userId" -> olduw.userId, "kind" -> RK.KIND_MYSELF);
              rka <- ROne[RacerKidAssoc]("from" -> au._id, "to" -> rk._id, "year" -> Club(au).curYear)
            ) if (rka.role != r) rka.copy(role = r).update

            var reg = club.reg(u)
            if (reg.exists(_.paid != p)) {
              reg = Some(reg.get.copy(paid = p))
              reg.get.update
            }

            ROK.s apply { implicit stok =>(views.html.club.doeClubReg(club, mngUserForm.fill(
              (uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)), uw))}
        })
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** send a help message */
  def doeClubRegMsg(clubName:String, uwid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin");
      uw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- uw.user;
      msg <- request.queryString.get("msg") orErr "no message"
    ) yield {
      SendEmail.withSession { implicit mailSession =>
        // notify user
        val link = club.reg(u) map {reg => routes.Club.doeClubUserReg(reg._id.toString).toString} getOrElse "http://www.racerkidz.com"
        Emailer.sendEmailClubRegHelp(u, au.userName, link, msg.mkString)
      }
      Redirect(routes.Club.doeClubReg(clubName, uwid))
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** change registration status */
  def doeClubUwRegstatusupd(clubName:String, uwid: String, how: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin");
      uw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr ("user is not a member");
      u <- uw.user
    ) yield {
      val ooldreg = club.reg(u) //orErr ("no registration record for year... ?")

      if (how == RegStatus.DELETE) {
        val r = uw.user.flatMap(u => club.reg(u))
        r.foreach(_.delete)
        r.toList flatMap { _.kids.toList } foreach { k =>
          k.delete
          // remove kid association as well
          club.rka.filter(a => a.to == k.rkId && a.assoc == RK.ASSOC_REGD).foreach(_.delete)
        }

        Redirect(routes.Club.doeClubReg(clubName, uwid))
      } else {
        if (ooldreg.isEmpty && how == RegStatus.FAMILY) {
          val r = Reg(u._id, au.userName, club.curYear, uw.role, Seq(), how)
          r.create
          r
        }

        ooldreg.foreach { oldreg =>
          oldreg.copy(regStatus = how).update
          val reg = club.reg(u).get

          // if status just changed to PENDING, send email invitation
          if (ooldreg.exists(how != _.regStatus) && how == RegStatus.PENDING) {
            SendEmail.withSession { implicit mailSession =>
              // notify user
              Emailer.sendEmailClubRegStart(u, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
            }
          }
        }
        Redirect(routes.Club.doeClubReg(clubName, uwid))
      }
    }) getOrElse Msg2("CAN'T find registration " + errCollector.mkString)
  }

  /** add a kid to current registration */
  def doeClubUwAddForm(clubName:String, uwid: String, role: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      c <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin");
      form <- c.regForm(role) orErr ("no reg form for role " + role);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      uw <- model.Users.findUserLinksTo(c.uwid).find(_._id.toString == uwid) orErr ("no uw");
      u <- uw.user orErr ("oops - missing user?");
      reg <- Club(au).reg(u) orCorr ("no registration record for year... ?" -> "did you expire it first?")
    ) yield {
      addForm(u, c, reg, regAdmin, role)
      Redirect(routes.Club.doeClubReg(clubName, c.uwid.toString))
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
        routes.Club.doeClubReg(reg.clubName, next)
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
      routes.Club.doeClubReg(au.userName, uwid)
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
      routes.Club.doeClubReg(au.userName, uwid)
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
      u <- Users.findUserById(reg.userId) orErr ("User not found!");
      c <- Club.findForName(reg.clubName) orErr "club not found";
      regAdmin <- c.uregAdmin orErr ("no regadmin")
    ) yield {
        ROK.s apply { implicit stok =>
          views.html.club.doeClubUserRegAdd(rk, next, u, reg)
        }
    }) getOrElse unauthorized()
  }

  /** list members */
  def doeClubUserRegs = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent
    ) yield {
      ROK.s apply { implicit stok => views.html.club.doeClubUserRegs() }
    }) getOrElse unauthorized()
  }

  /** */
  def doeClubUserReg(regid: String) = FAU { implicit au => implicit errCollector => implicit request =>
     implicit val errCollector = new VErrors()
    (for (
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      reg <- model.Regs.findId(regid) orErr ("no reg found")
    ) yield {
        ROK.s apply { implicit stok => views.html.club.doeClubUserReg(reg, RacerKidz.findForUser(reg.userId).toList) }
    }) getOrElse unauthorized()
  }

  // parms optional
  def doeClubKids(club:String, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      c <- Club(club);
      ism <- c.isMember(au) orCorr cNotMember(club)
    ) yield {
        val rks= (for (a <- c.rka(role);
                k <- model.RacerKidz.findById(a.to)) yield
          (k,a)).toList.sortBy(x=>x._1.info.lastName+x._1.info.firstName)

      ROK.s apply {implicit stok=>
        views.html.club.doeClubKidz(c, role, Wikis.linksTo("Team", c.uwid, "Child").toList/*.sortBy(_.from.nameOrId)*/, rks) // U8 is bigger than U10... ugh
      }
    }) getOrElse unauthorized()
  }

  // insert
  def doeClubKidsTeam(teamWpath:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- auth orErr "Login for more details";
      isa <- au.isActive orErr "Need active account for details";
      team <- WID.fromPath(teamWpath) orErr "Team not found";
      c <- team.parentOf("Club").map(_.name).flatMap(Club.apply) orErr "Club not found";
      ism <- c.isMember(au) orCorr cNotMember(c.name)
    ) yield {
        ROK.r noLayout {implicit stok=>
          views.html.club.doeClubKidzTeam(c, "", team)
        }
    }) getOrElse ROK.r.noLayout {implicit stok=>
      Html("<div class=\"alert alert-warning\">"+errCollector.mkString+"</div>") // return ok as this is an insert
    }
  }

  def doeUpdRka(rkaid:String, prop:String, value:String) = FAUR { implicit request =>
    (for(
      rka <- RacerKidz.findAssocById(rkaid);
      club <- rka.club
    ) yield {
        val newRka =
          if("role" == prop)
            rka.copy(role=value).update
        Ok("ok")
      }) getOrElse unauthorized();
  }

  def doeClubKidsSetTeam(rkaid:String, clubName:String, teamid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for(
      rka <- RacerKidz.findAssocById(rkaid);
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
    ) yield {
       val team = if(teamid != "-") Wikis.findById("Team", teamid) else None;
       val old = RacerKidz.findWikiAssocById(rka.to.toString, club.curYear, "Team").filter(
              _.uwid.wid.exists(_.parentWid.exists(_.name==club.name))).toList.headOption
        if(old.isDefined) {
          if(teamid == "-" || team.isEmpty) old.get.delete
          else old.get.copy(uwid=team.get.uwid).update
        }
        else team.foreach{team=>
            new RacerKidWikiAssoc(rka.to, team.uwid, club.curYear, rka.role).create
          }
        Ok("ok")
    }) getOrElse unauthorized()
  }

  // rollup volunteer hours per registration
  def volPerReg(reg:Reg) = {
    val club = Club(reg.clubName).get
    val rkasx = reg.kids.flatMap(rk=> RMany[RacerKidAssoc]("from" -> club.userId, "year" -> reg.year, "to" -> rk.rkId).filter(_.role != RK.ROLE_FAN))
    val x = rkasx.map(_.hours)
    if (x.isEmpty) "~" else x.sum.toString
  }

  /** for user per club au is the user */
  def doeUserVolAdd (regId:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      reg <- Regs.findId(regId) orErr "no registration found... ?";
      club <- Club(reg.clubName)
    ) yield {
      reg.kids.map(_.rkId);
      val rkas = club.rka.filter(_.role != RK.ROLE_FAN).filter(a=> reg.kids.exists(_.rkId == a.to))
        ROK.s apply { implicit stok => (views.html.club.doeClubUserVolAdd(reg, rkas.toList, addvol.fill("", 0, "?", "", ""), club))}
    }) getOrElse unauthorized()
  }

  /** for club per member */
  def doeVol(clubName:String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
    ) yield {

      val rks =
        (for (
          a <-  club.rka if a.role != RK.ROLE_FAN;
          k <-  model.RacerKidz.findById(a.to)        if k.info.status != RK.STATUS_FORMER
        ) yield (k, a)).toList.sortBy(x => x._1.info.lastName + x._1.info.firstName)

      val regs = Regs.findClubYear(clubName, club.curYear).toList

        ROK.s apply { implicit stok => (views.html.club.doeClubVol(club.name, rks, regs))}
      }) getOrElse unauthorized()
  }

  def addvol(implicit request: Request[_]) = Form {
    tuple(
      "who" -> text,
      "hours" -> number(min = 0, max = 100),
      "desc" -> text.verifying(vPorn, vSpec),
      "comment" -> text.verifying(vPorn, vSpec),
      "approver" -> text.verifying(vPorn, vSpec)
    )
  }

  /** capture volunteer hour record */
  def doeVolAdd(clubName:String, rkaId: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin");
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId)) orErr ("No RKA id "+rkaId)
    ) yield {
        ROK.s apply { implicit stok =>(views.html.club.doeClubVolAdd(clubName, rkaId, rka, addvol.fill(rkaId, 0, "?", "", "?"), au))}
    }) getOrElse Msg2("ERROR " + errCollector.mkString)
  }

  /** create volunteer hour record */
  def doeVolAdded(clubName:String, id: String) = Action { implicit request =>
    // if from club, id == rkaId
    // if from user id == regId
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
      addvol.bindFromRequest.fold(
        formWithErrors =>
          if(!clubName.isEmpty) {
            val rka = ROne[RacerKidAssoc]("_id" -> new ObjectId(id)).get
            ROK.s(au,request) badRequest { implicit stok =>(views.html.club.doeClubVolAdd(clubName, id, rka, formWithErrors, au))}
          } else
//            BadRequest(views.html.club.doeClubUserVolAdd(reg, rkas.toList, formWithErrors, club, au))

        Msg2("some error " + formWithErrors.errors.mkString)
//          errorsAsJson(Messages.Implicits.applicationMessagesApi(current)).toString)
      ,
        {
          case (w, h, d, c, a) =>
            if(!clubName.isEmpty) {
              val rka = ROne[RacerKidAssoc]("_id" -> new ObjectId(id)).get
              model.VolunteerH(new ObjectId(id), h, d, c, au._id, Some(a), VH.ST_OK).create
              rka.copy(hours = rka.hours + h).update
              Redirect(routes.Club.doeVolAdd(clubName, id))
            } else {
              model.VolunteerH(new ObjectId(w), h, d, c, au._id, Some(a), VH.ST_WAITING).create
//              rka.copy(hours = rka.hours + h).update
              Redirect(routes.Club.doeUserVolAdd(id))
            }
        })
    }) getOrElse Msg2("ERROR " + errCollector.mkString)
  }

  /** delete volunteer hour record */
  def doeVolDelete(clubName:String, rkaId: String, vhid: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      vh <- ROne[VolunteerH]("_id" -> new ObjectId(vhid))
    ) yield {
      vh.delete
      if(!clubName.isEmpty) {
        val rka = ROne[RacerKidAssoc]("_id" -> new ObjectId(rkaId)).get
        if (vh.status == VH.ST_OK) rka.copy(hours = rka.hours - vh.hours).update
        Redirect(routes.Club.doeVolAdd(clubName, rkaId))
      } else
        Redirect(routes.Club.doeUserVolAdd(rkaId))
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  import razie.wiki.Sec._

  /** capture volunteer hour record */
  def doeVolApprover(user:User) (implicit request:Request[_]) = {
    implicit val errCollector = new VErrors()
    val vhs =
      if (user.isClub)
        RMany[VolunteerH]("approvedBy" -> None)
      else
        RMany[VolunteerH]("approver" -> Some(user.email.dec), "approvedBy" -> None)
    ROK s (user, request) apply { implicit stok =>(views.html.club.doeClubUserVolApprove(vhs.toList, user))}
  }

  /** approve volunteer hour record */
  def doeVolApprove(vhid: String, approved:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      vh <- ROne[VolunteerH]("_id" -> new ObjectId(vhid));
      rka <- ROne[RacerKidAssoc]("_id" -> vh.rkaId)
    ) yield {
      if(approved.startsWith("to:")) {
        vh.copy(approver = Some(approved.replaceFirst("to:", ""))).update
      } else if("y" == approved) {
        vh.copy(approvedBy = Some(au._id), status=VH.ST_OK).update
        rka.copy(hours = rka.hours + vh.hours).update
      } else {
        vh.copy(approvedBy = Some(au._id), status=VH.ST_REJECTED).update
      }
      Redirect ("/user/task/approveVolunteerHours")
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

      if (au.isClub) Redirect(routes.Club.doeClubKids("",""))
      else Redirect(routes.Kidz.doeUserKids)
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // new user linked to club - give it access to forums
  def linkUser(u: User, cname: String, how: String)(implicit txn: Txn) = {
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
      razie.db.tx("userStartRegCopy") { implicit txn =>
//        cout << "OLD REG: " << prevReg
        var reg = prevReg.copy(_id = new ObjectId(), year = c.curYear, regStatus = RegStatus.PENDING, paid="")

//        cout << "REG: " << reg

       val widNameMap = new collection.mutable.HashMap[String,String]()

        val newWids = reg.wids.map { wid =>
//          cout << "  proc " << wid
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
//          cout << "  new form: " << newForm

          val newfwid = WID("Form", s"${newForm.wid.name}-${role}-${id + kid}-${reg.year}-${num}")
//          cout << "  old  wid: " << oldW.wid
//          cout << "  new fwid: " << newfwid

          widNameMap put (oldW.wid.name, newfwid.name)

          //    // have to create form ?
          if (!Wikis.find(newfwid).isDefined) {
            var label = s"${newForm.wid.name.replaceAll("_", " ")}"
            if (!label.contains(reg.year))
              label = label + s" for $kidName season ${reg.year}"
            val newW = controllers.Forms.copyForm (user, oldW, newfwid.name, label, newForm, c.filterRegFields)

//            cout << "  old form: " << oldW
//            cout << "  new form: " << newW
          }

          newfwid
        }

        reg = reg.copy (wids = newWids)
//        cout << "  NEW REG: " << reg

        reg.create

        // copy kid regs with new form names as well
        prevReg.kids.toList.map{ ork =>
          val rk = ork.copy(_id = new ObjectId(), regId = reg._id, wids=ork.wids.map(ow => WID(ow.cat, widNameMap(ow.name))), crDtm=DateTime.now())
//          cout << "   NEW REGKID" << rk
          rk.create
          assoc(c, rk.rk.get, model.RK.ASSOC_REGD, rk.role, au, c.curYear)
        }


        //      3. start - notify user
              SendEmail.withSession { implicit mailSession =>
        //        Emailer.sendEmailClubRegStart(au, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
        //        Emailer.tellRaz("Started registration", "user: " + au.userName, "club: " + clubName, "how: "+how)
        ////        TODO tell regAdmin so they know...
        //
        //        UserTask(au._id, UserTasks.START_REGISTRATION).delete
        //      }
            Redirect(routes.Club.doeClubUserReg(reg._id.toString))
      }
      }
    }) getOrElse  {
      if(activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
        ROK.r noLayout {implicit stok=>
          views.html.user.doeConsent()
        }
      else
        Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
      }
  }

  /** called by user not the club */
  def doeStartRegSimple(clubName: String) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      c <- Club(clubName);
      regAdmin <- c.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- c.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr ("Registration is not open yet!");
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      uclub <- Users.findUserByUsername(clubName)
    ) yield {
        ROK.s apply { implicit stok => views.html.club.doeClubUserStartReg(clubName) }
    }) getOrElse  {
      if(activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
        ROK.s noLayout {implicit stok=>views.html.user.doeConsent(routes.Club.doeStartRegSimple(clubName).url)}
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
      razie.db.tx("userStartReg") { implicit txn =>
        if ("None" == how) {
          UserTask(au._id, UserTasks.START_REGISTRATION).delete
          Redirect("/")
        } else Regs.findClubUserYear(clubName, au._id, c.curYear).map {reg=>
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
              reg = Regs.findClubUserYear(clubName, au._id, c.curYear).get
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
        ROK.r noLayout {implicit stok=>
          views.html.user.doeConsent()
        } else
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
      razie.db.tx("userStartReg") { implicit txn =>
        if(! u.tasks.exists(_.name == UserTasks.START_REGISTRATION))
          UserTask(u._id, UserTasks.START_REGISTRATION, Map("club" -> au.userName)).create
        }
      Redirect(routes.Club.doeClubReg(au.userName, uwid))
    }) getOrElse  {
      Msg2("CAN'T " + errCollector.mkString)
    }
  }

  /** change owner */
  def doeAddClubFollowers(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val sForm = Form("newvalue" -> nonEmptyText)

      sForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, bad newValue"),
      {
        case newvalue =>
          log("Club.addFollowers " + wid + ", " + newvalue)
          (for (
            c <- Users.findUserByUsername(newvalue);
            isClub <- c.isClub orErr "registration only for a club";
            club <- Some(Club(c));
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            w <- Wikis.find(wid)
          ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Club.addFollowers") { implicit txn =>
                club.userLinks.foreach {uw=>
                  new UserWiki(uw.userId, w.uwid, "Fan").create
                  }
                }
              Redirect(controllers.Wiki.w(wid))
            }) getOrElse
            noPerm(wid, "ADMIN_UADD_FOLLOWERS")
      })
  }

  /** */
  def doeDelClubFollowers(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val sForm = Form("newvalue" -> nonEmptyText)

      sForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, bad newValue"),
      {
        case newvalue =>
          log("Club.delFollowers " + wid + ", " + newvalue)
          (for (
            c <- Users.findUserByUsername(newvalue);
            isClub <- c.isClub orErr "registration only for a club";
            club <- Some(Club(c));
            ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
            page <- Wikis.find(wid)
          ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Club.delFollowers") { implicit txn =>
                val l = model.Users.findUserLinksTo(page.uwid).toList
                l.filter(_.user.exists(club.isMember)).foreach(_.deleteNoAudit)
              }
              Redirect(controllers.Wiki.w(wid))
            }) getOrElse
            noPerm(wid, "ADMIN_UDEL_FOLLOWERS")
      })
  }

  /** */
  def doeDelAllFollowers(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

              log("Wikie.delFollowers " + wid)
              (for (
                ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
                page <- Wikis.find(wid)
              ) yield {
                  // can only change label of links OR if the formatted name doesn't change
                  razie.db.tx("Wikie.delFollowers") { implicit txn =>
                    val l = model.Users.findUserLinksTo(page.uwid).toList
                    l.foreach(_.deleteNoAudit)
                  }
                  Redirect(controllers.Wiki.w(wid))
                }) getOrElse
                noPerm(wid, "ADMIN_UDEL_ALL_FOLLOWERS")
  }

  // ----------------- pills

//  CodePills.add("mod.club/buy-and-sell") {implicit request=>
//    redirectToForum("buyandsell")
//  }
//
//  CodePills.add("mod.club/resources") {implicit request=>
//    redirectToForum("resources")
//  }

  def redirectToPage(role:String) = Action { implicit request=>
      (for(
        au <- activeUser;
        club <- au.pages("*", "Club").collect{case x if x.uwid.page.isDefined => x.uwid.page.get}.find(_.contentProps.contains(role)); // todo aggregate more clubs
        wid <- club.contentProps.get(role).flatMap(WID.fromPath)
      )yield
        Redirect(wid.url)
       ) getOrElse Msg("No such forum/calendar found in your clubs...",
         """
           |To join a club, click [/wikie/like/Club here].
           |
           |To host/create a club or learn more about this website, read [/wiki/Admin:Hosted_Services_for_Ski_Clubs this].
           |""".stripMargin)
    }
}

