package controllers

import razie.wiki.model.features.WikiCount
import admin.Config
import controllers.Profile.cNoConsent
import mod.cart.Carts.Redirect
import mod.cart._
import mod.snow.{RK, _}
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}
import play.twirl.api.Html
import razie.db.RMongo.as
import razie.db._
import razie.diesel.dom.WikiDomain
import razie.diesel.ext.EExecutor
import razie.hosting.Website
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.model.features._
import razie.wiki.{Enc, WikiConfig}
import razie.wiki.Sec._
import razie.{Logging, cout}
import views.html.club.doeClubRegsRepHtml

import scala.Array.canBuildFrom
import scala.Option.option2Iterable
import scala.concurrent.Future

/** just refactoring remains */
case class Club(userId: ObjectId, iwid: Option[WID] = None) {
  private var oUser: Option[User] = None

  lazy val settings = oUser.flatMap(_.clubSettings).getOrElse("")
  lazy val dsl = settings.replaceAll(
    // less the lines with specific props below
    "(?m)^(regType|curYear|regAdmin)=[^\n]*\n",
    ""
  )

  def regType = props.get("regType").getOrElse("")

  def curYear = props.get("curYear").getOrElse(Config.curYear)

  def regAdmin = props.get("regAdmin").getOrElse("")

  def userName = oUser.get.userName

  def name = userName // todo inline

  lazy val wid = iwid getOrElse {
    ROne[WikiEntry] ("name" -> userName).map(_.wid).get
  }

  def uwid = wid.uwid.get

  // optimize access to User object
  lazy val user = oUser.getOrElse(Users.findUserById(userId).get)

  def setU(u: User) = {
    oUser = Some(u); this
  }

  /** props like Follows.Fan will be rolled up and killed in a map so you can find them in this here seq */
  lazy val propSeq = WikiConfig parsep settings
  lazy val props = propSeq.toMap

  def isRegOpen = propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2)

  lazy val regForms = propSeq.filter(_._1 startsWith "Reg.").map(t =>
    RoleWid(t._1.replaceFirst("Reg.", ""), WID.fromPath(t._2).get))
  lazy val newFollows = propSeq.filter(_._1 startsWith "Follows.").map(t =>
    RoleWid(t._1.replaceFirst("Follows.", ""), WID.fromPath(t._2).get))
  lazy val filterRegFields = props.get("FilterFields").getOrElse("") split ","
  lazy val volunteering = props.get("Volunteering").getOrElse("") split ","

  def isAdminEmail(s: String) = props.get("adminEmails").exists(_.split(",").contains(s))

  def isClubAdmin(u: User) = u.isAdmin || isAdminEmail(u.emailDec)

  def isClubCoach(u: User) = isMemberRole(u._id, "Coach") || isMemberRole(u._id, "Pro")

  def isMemberRole(uid: ObjectId, role: String) = membership(uid).exists(_.role == role)

  def isMember(u: User) = membership(u._id).isDefined

  def membership(uid: ObjectId) = ROne[UserWiki]("uwid" -> uwid.grated, "userId" -> uid) orElse ROne[UserWiki]("uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id, "userId" -> uid)

  override def equals(other: Any) = other match {
    // or override them
    case c: Club => userName == c.userName
    case _ => false // wildcard serves like a default case
  }

  lazy val newTasks = propSeq filter (_._1 startsWith "Task.") map { t =>
    val PAT1 = "([^,]*),(.*)".r
    val PAT1(name, args) = t._2
    (name, args.split(",").map(x => x.split(":", 2)(0) -> x.split(":", 2)(1)).toMap)
  }

  lazy val approvers = propSeq filter (_._1 startsWith "Approver.") map { t =>
    (t._1.replaceFirst("Approver.", ""), t._2)
  }

  lazy val msgWelcome = (props filter (_._1 startsWith "Msg.welcome")).toSeq.sortBy(_._1) map (_._2) mkString ("<p>")

  lazy val msgFormsAccepted = (props filter (_._1 startsWith "Msg.formsAccepted")).toSeq.sortBy(_._1) map (_._2) mkString ("<p>")

  // P.1=y AND P.1.name=x returns map (x,y)
  def nvp(prefix: String) = (props filter (_._1 startsWith prefix)).toSeq.map(kv => (kv._1.replace(prefix + ".", ""), kv._2)).sortBy(_._1).groupBy(t => t._1.substring(0, t._1.indexOf('.') - 1)).toSeq.map(g => (g._2.find(_._1.contains("name")).get._2, g._2.find(!_._1.contains("name")).get._2))

  def regForm(role: String) = regForms.find(_.role == role)

  def uregAdmin = Users.findUserByEmailDec(regAdmin)

  def reg(u: User) = Regs.findClubUserYear(wid, u._id, curYear)

  def userLinks = model.Users.findUserLinksTo(uwid)
  def activeMembers = userLinks.filter(_.role != RK.ROLE_FAN).toList

  // TODO filter by year as well
  def roleOf(rkId: ObjectId) =
  // registered or owned
    ROne[RacerKidAssoc]("from" -> userId, "to" -> rkId).map(_.role).mkString

  def rka: Iterator[RacerKidAssoc] = rka("")

  def rka(role: String = "", year: String = curYear) =
    if (role.isEmpty || role == "*")
      RMany[RacerKidAssoc]("from" -> userId, "year" -> year)
    else
      RMany[RacerKidAssoc]("from" -> userId, "year" -> year, "role" -> role)

  def rka(rkId: ObjectId, role: String) =
    if (role.isEmpty || role == "*")
      ROne[RacerKidAssoc]("from" -> userId, "year" -> curYear, "to"->rkId)
    else
      ROne[RacerKidAssoc]("from" -> userId, "year" -> curYear, "to"->rkId, "role"->role)

  def rk = {
    val mine = rka map (_.to) flatMap (RacerKidz.findById)
    //    val fromOthers = RMany[RacerKid]("userId" -> id) flatMap (x=> RMany[RacerKidAssoc]("from" -> x._id, "what" -> RK.ASSOC_PARENT)) map (_.to) flatMap (findById)
    mine //::: fromOthers
  }

  def teamMembers(team: WID, role: String = "*") =
    (for (
      uwid <- team.uwid.toList;
      w <- RacerKidz.findWikiAssocs(curYear, uwid).toList;
      a <- this.rka(w.rkId, role);
      k <- w.rk
    ) yield
      (k, a)
      ).toList.sortBy(x => x._1.info.lastName + x._1.info.firstName)

  def activeTeamMembers(team: WID, role: String = "*") =
    teamMembers(team, role).filter(t =>
      t._1.info.status != RK.STATUS_FORMER &&
        t._2.role != RK.ROLE_FAN).toList

}

// just some helpers
case class RKU(user: User) {
  def rkAssocs =
    if (user.isClub) RMany[RacerKidAssoc]("from" -> user._id, "year" -> Club(user).curYear)
    else RMany[RacerKidAssoc]("from" -> user._id)

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

  def mkSettings(u: User, regAdmin: String) =
    s"""
regType=?
curYear=${Config.curYear}
regAdmin=$regAdmin
       """

  def isCoachRole(s: String) = s == "Coach" || s == "Pro"

  def apply(u: User): Club = findForUser(u).get

  def apply(wid: WID): Option[Club] =
    Users.findUserByUsername(wid.name).map { u =>
      new Club(u._id, Some(wid)).setU(u)
    }

  def apply(name: String): Option[Club] = {
    WID.fromPath(name).filter(_.cat.length > 0).flatMap(wid =>
      apply(wid)
    ).orElse {
      Users.findUserByUsername(name).map { u =>
        new Club(u._id).setU(u)
      }
    }
  }

  def findForUserId(uid: ObjectId) = Users.findUserById(uid).filter(_.isClub).map(u => new Club(u._id).setU(u))

  def findForUser(u: User) = Some(new Club(u._id).setU(u))

  def findForName(n: String) = Users.findUserByUsername(n).map(u => new Club(u._id).setU(u))

  def findForAdmin(n: String, u: User) = findForName(n).filter(c => u.isAdmin || c.isAdminEmail(u.emailDec))

  def findForAdmin(wid: WID, u: User) = apply(wid).filter(c => u.isAdmin || c.isAdminEmail(u.emailDec))

  // does the topic belong to a club (hierarchy) and is the user an admin
  def canAdmin(wid: WID, au: User) = {
    val pclub = if (wid.cat == "Club") Some(wid) else wid.parentOf(WikiDomain(wid.getRealm).isA("Club", _))
    au.isAdmin || pclub.exists(x => findForAdmin(x.name, au).isDefined)
  }

  // need to do some stuff when creating new clubs
  //  WikiObservers mini {
  //    case ev@WikiEvent(
  //      WikiAudit.CREATE_WIKI,
  //      "WikiEntry",
  //      wpath,
  //      entity: Option[WikiEntry],
  //      _, _, _) => {
  //    }
  //  }

  // manage user screen
  def membersData(club: Club, what: String, cols: String): (List[String], List[List[String]]) = {
    val members = club.userLinks.map(_.userId)
    val regs = Regs.findClubYear(club.wid, club.curYear)
    val forms = regs.flatMap(_.deprecatedWids).flatMap(_.page).filter(_.formRole.exists(_ == what)).map(_.form.fields).toList

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

  def doeClubRegs(clubName: WID, details: String = "") = Action.async { implicit request =>
    Future {
      val x = new razie.CSTimer("REGS", "")
      x.start("1")
      implicit val errCollector = new VErrors()
      (for (
        au <- activeUser;
        c <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
      ) yield {
        Regs.upgradeAllRegs1
        x.snap("2")

        val members =
          model.Users.findUserLinksTo(c.uwid).map(uw =>
            (model.Users.findUserById(uw.userId),
              uw,
              Regs.findClubUserYear(clubName, uw.userId, c.curYear))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString)
        x.snap("3")

        val y = ROK.r apply { implicit stok =>
          views.html.club.doeClubRegs(clubName, c, details, members)
        }

        x.snap("4")
        y
      }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
    }
  }

  def edRegForm(implicit request: Request[_]) = Form {
    tuple(
      "regType" -> nonEmptyText.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "curYear" -> number(min = 2012, max = 2025),
      "regAdmin" -> text.verifying("Invalid user", { x => Users.findUserByEmailDec((x)).isDefined }),
      "dsl" -> text) verifying
      ("Please check reg forms and follows - some topics are not created", { t: (String, Int, String, String) =>
        val propSeq = WikiConfig parsep t._4

        val regForms = propSeq.filter(_._1 startsWith "Reg.").map(t =>
          RoleWid(t._1.replaceFirst("Reg.", ""), WID.fromPath(t._2).get))
        val newFollows = propSeq.filter(_._1 startsWith "Follows.").map(t =>
          RoleWid(t._1.replaceFirst("Follows.", ""), WID.fromPath(t._2).get))

        regForms.foldLeft(true)((a, b) => a && b.wid.page.isDefined) &&
          newFollows.foldLeft(true)((a, b) => a && b.wid.page.isDefined)
      })
  }

  /** registration settings */
  def doeClubRegSettings(club: WID) = FAUR("club.settings") { implicit request =>
    for (
      c <- Club.findForAdmin(club.name, request.au.get) orErr ("Not a club or you're not admin")
    ) yield {
      ROK.k apply { implicit stok =>
        views.html.club.doeClubRegSettings(club, edRegForm.fill(
          (c.regType, c.curYear.toInt, c.regAdmin, c.dsl)))
      }
    }
  }


  def doeClubUpdateRegSettings(club: WID) = FAUR { implicit request =>
    edRegForm.bindFromRequest()(request.ireq).fold(
      formWithErrors => ROK.k badRequest { implicit stok =>
        views.html.club.doeClubRegSettings(club, formWithErrors)
      }, {
        case (t, y, a, d) => {
          Club.findForAdmin(club.name, request.au.get).map { c =>
            val s = "curYear=" + y + "\nregType=" + t + "\nregAdmin=" + a + "\n" + d
            val newu = c.user.copy(clubSettings = Some(s))
            c.user.update(newu)
            cleanAuth()
            val newc = Club(newu)

            ROK.k apply { implicit stok =>
              stok.msg("ok" -> "[Settings saved]")
              views.html.club.doeClubRegSettings(club, edRegForm.fill(
                (newc.regType, newc.curYear.toInt, newc.regAdmin, newc.dsl)))
            }
          } getOrElse {
            unauthorized("Only club admin can change settings")
          }
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

  // club admin panel - use basic RAction to get no audit of no user
  def doeClubAdminPanel(club: WID) = RAction { implicit request =>
    (for (
      au <- activeUser;
      c <- Club(club) orErr ("Not a club");
      can <- (!c.props.get("memberCanSeeOthers").exists(_ == "no") ||
        au.isAdmin || c.isClubCoach(au) || c.isClubAdmin(au)
        ) orErr "can't see"
    ) yield {
      ROK.k noLayout { implicit stok =>
        views.html.club.doeClubAdminPanel(c, club)
      }
    }) getOrElse Unauthorized("") // use this one to get no audit
  }

  // manage user screen
  def doeClubReg(clubName: WID, uwid: String) = FAUR("club.registration") { implicit request =>
    for (
      c <- Club.findForAdmin(clubName, request.au.get) orCorr cNotAdmin(clubName.wpath);
      uw <- model.Users.findUserLinksTo(c.uwid).find(_._id.toString == uwid);
      u <- uw.user
    ) yield {
      val reg = c.reg(u)
      ROK.k apply { implicit stok =>
        views.html.club.doeClubReg(c, mngUserForm.fill(
          (uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)
        ),
          uw,
          reg)
      }
    }
  }

  import play.api.libs.json._

  def doeClubRegsReportHtml(clubwid: WID, what: String, cols: String) = FAUR("reg.report.html") { implicit request =>
    for (
      c <- Club.findForAdmin(clubwid, request.au.get) orCorr cNotAdmin(clubwid.wpath)
    ) yield {
      val regs = Regs.findClubYear(clubwid, c.curYear)
      val forms = regs.flatMap(_.deprecatedWids).flatMap(_.page).filter(_.formRole.exists(_ == what)).toList

      ROK.k noLayout { implicit stok =>
        doeClubRegsRepHtml(request.au.get, forms)
      }
    }
  }

  private def escNL(s:String) = s.replaceAllLiterally("\n", " - ").replaceAllLiterally(",", " - ")

  def doeClubRegsReportJson(clubwid: WID, what: String, cols: String) = doeClubRegsReport(clubwid, what, cols, "json")

  def doeClubRegsReportCsv(clubwid: WID, what: String, cols: String) = doeClubRegsReport(clubwid, what, cols, "csv")

  def doeClubRegsReport(clubwid: WID, what: String, cols: String, format: String) = FAUR { implicit request =>
    val DELIM = ","
    (for (
      club <- Club.findForAdmin(clubwid, request.au.get) orCorr cNotAdmin(clubwid.wpath)
    ) yield {
      val (headers, data) = membersData(club, what, cols)

      if ("csv" == format)
        Ok(
          headers.mkString(DELIM) +
            "\n" +
            data.map(_.map(escNL).mkString(DELIM)).mkString("\n")).as("text/csv")
      else
        Ok(Json.toJson(headers :: data))
    }) getOrElse Msg2("CAN'T : " + errCollector.mkString)
  }

  // update user role
  def doeClubMemberUpdate(clubwid: WID, uwid: String) = FAUR { implicit request =>
    (for (
      au <- request.au;
      club <- Club.findForAdmin(clubwid, au) orCorr cNotAdmin(clubwid.wpath);
      olduw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- olduw.user orErr "no olduw user"
    ) yield {
      mngUserForm.bindFromRequest.fold(
        formWithErrors => ROK.k badRequest { implicit stok =>
          views.html.club.doeClubReg(club, formWithErrors, olduw)
        }, {
          case (r, s, p) =>
            razie.db.tx("doeClubMemberUpdate", au.userName  ) { implicit txn =>
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
                reg = Some(reg.get.copy(paid = p, amountPaid = Price(p.toFloat, reg.get.amountPaid.currency)))
                reg.get.update
              }

              ROK.k apply {
                (views.html.club.doeClubReg(club, mngUserForm.fill(
                  (uw.role, reg.map(_.regStatus).getOrElse("n/a"), reg.map(_.paid).mkString)), uw))
              }
            }
        })
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  /** send a help message */
  def doeClubRegMsg(clubName: WID, uwid: String) = FAUR { implicit request =>
    (for (
      au <- request.au;
      club <- Club.findForAdmin(clubName, au) orCorr cNotAdmin(clubName.wpath);
      uw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr "user is not a member";
      u <- uw.user;
      msg <- request.queryString.get("msg") orErr "no message"
    ) yield {
      SendEmail.withSession(request.realm) { implicit mailSession =>
        // notify user
        val link = club.reg(u) map { reg => routes.Club.doeClubUserReg(reg._id.toString).toString } getOrElse "http://www.racerkidz.com"
        Emailer.sendEmailClubRegHelp(u, clubName.name, link, msg.mkString)
      }
      Redirect(routes.Club.doeClubReg(clubName, uwid))
    }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  /** change registration status */
  def doeClubUwRegstatusupd(clubName: WID, uwid: String, how: String) = FAUR {implicit request =>
    (for (
      au <- request.au;
      club <- Club.findForAdmin(clubName, au) orCorr cNotAdmin(clubName.wpath);
      uw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr ("user is not a member");
      u <- uw.user
    ) yield {
      razie.db.tx("doeClubUwRegstatusupd", au.userName) { implicit txn =>
        val ooldreg = club.reg(u) //orErr ("no registration record for year... ?")

        if (how == RegStatus.DELETE) {
          val r = uw.user.flatMap(u => club.reg(u))
          r.foreach(_.delete)
          r.toList flatMap {
            _.kids.toList
          } foreach { k =>
            k.delete
            // remove kid association as well
            club.rka.filter(a => a.to == k.rkId && a.assoc == RK.ASSOC_REGD).foreach(_.delete)
          }

          Redirect(routes.Club.doeClubReg(clubName, uwid))
        } else {
          if (ooldreg.isEmpty && how == RegStatus.FAMILY) {
            val r = Reg(u._id, clubName.name, club.wid, club.curYear, uw.role, Seq(), Seq(), how)
            r.updFees.create
            r
          }

          ooldreg.foreach { oldreg =>
            oldreg.copy(regStatus = how).updFees.update
            val reg = club.reg(u).get

            // if status just changed to PENDING, send email invitation
            if (ooldreg.exists(how != _.regStatus) && how == RegStatus.PENDING) {
              SendEmail.withSession(request.realm) { implicit mailSession =>
                // notify user
                Emailer.sendEmailClubRegStart(u, clubName.name, routes.Club.doeClubUserReg(reg._id.toString).toString)
              }
            }
          }
          Redirect(routes.Club.doeClubReg(clubName, uwid))
        }
      }
    }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  /** club admin add a kid to current registration */
  def doeClubUwAddForm(clubName: WID, uwid: String, role: String) = FAUR { implicit request =>
    (for (
      au <- request.au;
      c <- Club.findForAdmin(clubName, au) orCorr cNotAdmin(clubName.wpath);
      form <- c.regForm(role) orErr ("no reg form for role " + role);
      regAdmin <- c.uregAdmin orErr ("no regadmin");
      uw <- model.Users.findUserLinksTo(c.uwid).find(_._id.toString == uwid) orErr ("no uw");
      u <- uw.user orErr ("oops - missing user?");
      reg <- c.reg(u) orCorr ("no registration record for year... ?" -> "did you expire it first?")
    ) yield {
      implicit val txn = razie.db.tx.local("doeClubUwAddForm", au.userName  )
      addForm(u, c, reg, regAdmin, role)
      Redirect(routes.Club.doeClubReg(clubName, uwid.toString))
    }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  // remove a year in the form 20xx
  def removeYear(s: String) = {
    "20\\d\\d".r.replaceSomeIn(s, { m =>
      if (m.group(0) > "2000" && m.group(0) <= "2099") Some("")
      else None
    })
  }

  /** add a form to current registration - NOT for a kid */
  private def addForm(u: User, c: Club, reg: Reg, regAdmin: User, role: String)(implicit txn:Txn) {
    val form = c.regForm(role).get
    val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${reg.year}-${reg.deprecatedWids.size}")
    var label = removeYear(s"${form.wid.name.replaceAll("_", " ")}")
    if (!label.contains(reg.year))
      label = label + s" for season ${reg.year}"

    val newSt = if (Array(RegStatus.EXPIRED, RegStatus.PENDING) contains reg.regStatus) reg.regStatus else RegStatus.PENDING
    reg.copy(
      //      wids = reg.deprecatedWids ++ Seq(newfwid),
      roleWids = reg.roleWids ++ Seq(RoleWid(form.role, newfwid)),
      regStatus = newSt).update

    // have to create form ?
    if (!Wikis.find(newfwid).isDefined) {
      controllers.Forms.crForm(u, form.wid, newfwid, label, regAdmin, Some(role))
    }
  }

  /** can user modify reg */
  def canModifyReg(au: User, reg: Reg, c: Club)(implicit errCollector: VErrors) =
    (for (
      can1 <- (reg.userId == au._id || c.isClubAdmin(au)) orCorr cNotAdmin(reg.club.wpath);
      can2 <- (c.curYear == reg.year) orErr s"Can only work on current year registrations (${c.curYear})";
      can2 <- (c.isRegOpen) orErr s"Registration not open for ${reg.clubName}";
      regAdmin <- c.uregAdmin orErr (s"No regadmin configured for ${c.name}")
    ) yield true
      ) orElse None

  /** add a kid to current registration
    *
    * au is either member or admin
    */
  def doeClubUwAddFormKid(regId: String, rkId: String, next: String, role: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      (for (
        rk <- RacerKidz.findById(new ObjectId(rkId));
        reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
        club <- Users.findUserByUsername(reg.clubName) orErr ("Club not found");
        c <- Club.findForUser(club);
        u <- Users.findUserById(reg.userId) orErr ("User not found!");
        can <- canModifyReg(au, reg, c);
        regAdmin <- c.uregAdmin orErr ("no regadmin")
      ) yield {
        razie.db.tx("doeClubUwAddFormKid", au.userName) { implicit txn =>

          val nextPage = if (reg.userId != au._id) // if not member, then admin
            routes.Club.doeClubReg(reg.club, next)
          else
            routes.Club.doeClubUserReg(regId)
          val before = ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id)
          var r = reg

          if (!before.isDefined) {
            val fwids = (for (form <- c.regForms.filter(_.role.startsWith(role))) yield {
              val fn = rk.info.firstName.trim.replaceAll(" ", "-")
              val newfwid = WID("Form", s"${form.wid.name}-${form.role}-${u._id}-${fn}-${reg.year}-${reg.deprecatedWids.size}")

              var label = removeYear(s"${form.wid.name.replaceAll("_", " ")}")
              if (!label.contains(reg.year))
                label = label.trim + s" for ${fn} season ${reg.year}"

              val newSt = if (Array(RegStatus.EXPIRED, RegStatus.PENDING) contains reg.regStatus) reg.regStatus else RegStatus.PENDING

              r = r.copy(
                //            wids = r.deprecatedWids ++ Seq(newfwid),
                roleWids = r.roleWids ++ Seq(RoleWid(form.role, newfwid)),
                regStatus = newSt)

              // have to create form ?
              if (!Wikis.find(newfwid).isDefined) {
                controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
              }
              RoleWid(form.role, newfwid)
            }).toList

            RegKid(reg._id, rk._id, fwids.map(_.wid), fwids, role).create
            r.update
            assoc(c, rk, mod.snow.RK.ASSOC_REGD, role, au, c.curYear)
            Redirect(nextPage)
          } else
            Msg2(
              s"""${rk.info.firstName} was already added as <em>${before.get.role}</em> - please click continue, then remove her/him from the registration with the red <span class="label label-important">x</span> button and then re-add with the different role. <p>Note that any forms filled for his role will be <em>removed</em>!""",
              Some(nextPage.url))
        }
      }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  /** remove a kid from the current registration */
  def doeClubUwRmFormKid(regId: String, rkId: String, uwid: String, role: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      (for (
        rk <- RacerKidz.findById(new ObjectId(rkId));
        reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
        club <- Users.findUserByUsername(reg.clubName) orErr ("Club not found");
        c <- Club.findForUser(club);
        can <- canModifyReg(au, reg, c);
        regAdmin <- c.uregAdmin orErr ("no regadmin");
        regkid <- ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id) orErr ("can't find regkid");
        notCompleted <- (!regkid.deprecatedwids.flatMap(x => Wikis.find(x).flatMap(_.formState).toList).exists(_ == FormStatus.APPROVED)) orErr
          ("some forms have been approved for this person")
      ) yield {
        def sex1 = if (rk.info.gender.toLowerCase startsWith "m") "his" else "her"
        def sex2 = if (rk.info.gender.toLowerCase startsWith "m") "him" else "her"
        Msg2(
          s"""This will remove ${rk.info.firstName} from your family and this registration.
    <p>Note that any forms filled for $sex1 role will be <em>removed</em>! You can then re-add $sex2 back, with the same or different role.
    <p>If you don't want to remove $sex2, just go back... otherwise click Continue below.""",
          Some(routes.Club.doeClubUwRmFormKid1(regId, rkId, uwid, role).url))
      }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  def doeClubUwRmFormKid1(regId: String, rkId: String, uwid: String, role: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val next = Regs.findId(regId).filter(_.userId != au._id).map { reg =>
        routes.Club.doeClubReg(reg.club, uwid)
      } getOrElse routes.Club.doeClubUserReg(regId)
      (for (
        rk <- RacerKidz.findById(new ObjectId(rkId));
        reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
        club <- Users.findUserByUsername(reg.clubName) orErr ("Club not found");
        u <- Users.findUserById(reg.userId) orErr ("User not found!");
        c <- Club.findForUser(club);
        can <- canModifyReg(au, reg, c);
        regAdmin <- c.uregAdmin orErr ("no regadmin");
        regkid <- ROne[RegKid]("regId" -> reg._id, "rkId" -> rk._id) orErr ("can't find regkid");
        notCompleted <- (!regkid.deprecatedwids.flatMap(x => Wikis.find(x).flatMap(_.formState).toList).exists(_ == FormStatus.APPROVED)) orErr
          ("some forms have been approved for this person")
      ) yield {
        razie.db.tx("doeClubUwRmFormKid1", au.userName) { implicit txn =>
          var r = reg
          regkid.deprecatedwids.foreach { wid =>
            clog << "drop form " + wid
            r = r.copy(
              //          wids = (r.deprecatedWids.filter(_.name != wid.name)),
              roleWids = (r.roleWids.filter(_.wid.name != wid.name))
            )

            // have to delete form ?
            if (Wikis.find(wid).isDefined) {
              //          controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
            }
          }

          r.update
          regkid.delete
          // remove kid association as well
          c.rka.filter(a => a.to == regkid.rkId && a.assoc == RK.ASSOC_REGD).foreach(_.delete)

          Redirect(next)
        }
      }) getOrElse Msg2("CAN'T remove registration: " + errCollector.mkString,
        Some(next.toString))
  }

  /** remove a single form from the current registration */
  def doeClubUwRmFormSeq(regId: String, rkId: String, uwid: String, seq: Integer) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      (for (
        reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
        club <- Users.findUserByUsername(reg.clubName) orErr ("Club not found");
        c <- Club.findForUser(club);
        can <- canModifyReg(au, reg, c);
        regAdmin <- c.uregAdmin orErr ("no regadmin");
        isValid <- seq >= 0 && seq < reg.deprecatedWids.size orErr "bad form seq";
        formWid <- Some(reg.deprecatedWids(seq));
        form <- Wikis.find(formWid) orErr "can't find form";
        regkid <- RMany[RegKid]("regId" -> reg._id).find(_.deprecatedwids.exists(_.name == formWid.name)).isEmpty orErr "this form belongs to a regKid: remove the kid from the list of Racers";
        notCompleted <- form.formState != FormStatus.APPROVED orErr "form has been approved !"
      ) yield {
        Msg2(
          s"""This will remove Form: ${formWid.name} from this registration.
    <p>If you don't want to remove Form: ${formWid.name}, just go back... otherwise click Continue below.""",
          Some(routes.Club.doeClubUwRmFormSeq1(regId, rkId, uwid, seq).url))
      }) getOrElse Msg2("OOPS" + errCollector.mkString)
  }

  /** remove a single form from the current registration */
  def doeClubUwRmFormSeq1(regId: String, rkId: String, uwid: String, seq: Integer) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val next = Regs.findId(regId).filter(_.userId != au._id).map { reg =>
        routes.Club.doeClubReg(reg.club, uwid)
      } getOrElse routes.Club.doeClubUserReg(regId)
      (for (
        reg <- Regs.findId(regId) orCorr ("no registration found... ?" -> "did you start the registration?");
        club <- Users.findUserByUsername(reg.clubName) orErr ("Club not found");
        u <- Users.findUserById(reg.userId) orErr ("User not found!");
        c <- Club.findForUser(club);
        can <- canModifyReg(au, reg, c);
        regAdmin <- c.uregAdmin orErr ("no regadmin");
        isValid <- seq >= 0 && seq < reg.deprecatedWids.size orErr "bad form seq";
        formWid <- Some(reg.deprecatedWids.apply(seq));
        form <- Wikis.find(formWid) orErr "can't find form";
        regkid <- RMany[RegKid]("regId" -> reg._id).find(_.deprecatedwids.exists(_.name == formWid.name)).isEmpty orErr "this form belongs to a regKid";
        notCompleted <- form.formState != FormStatus.APPROVED orErr "form has been approved !"
      ) yield {
        razie.db.tx("doeClubUwRmFormSeq1", au.userName) { implicit txn =>
          var r = reg
          clog << "drop form " + formWid
          r = r.copy(
            //        wids = (r.deprecatedWids.filter(_.name != formWid.name)),
            roleWids = (r.roleWids.filter(_.wid.name != formWid.name))
          )

          // have to delete form ?
          // controllers.Forms.crFormKid(u, form.wid, newfwid, label, regAdmin, Some(form.role), rk)
          r.update
          Redirect(next)
        }
      }) getOrElse Msg2("CAN'T remove form: " + errCollector.mkString,
        Some(next.toString))
  }

  /** build or update an association... there's a few possibilities */
  def assoc(c: Club, rk: RacerKid, assoc: String, role: String, owner: User, year: String)(implicit txn:Txn) {
    // simple case, same rk again (member registers himself)
    val rka = ROne[RacerKidAssoc]("from" -> c.userId, "to" -> rk._id, "year" -> year)
    // if assoc already from Link, just update role
    if (rka.isDefined)
      rka.foreach(_.copy(role = role, assoc = RK.ASSOC_REGD).update)
    else {
      // TODO other use cases will be manual - should I notify?
      RacerKidAssoc(c.userId, rk._id, mod.snow.RK.ASSOC_REGD, role, owner._id, 0, year).create
      RacerKidz.rkwa(rk._id, c.uwid, c.curYear, role, mod.snow.RK.ASSOC_REGD)
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
        can <- canModifyReg(au, reg, c)
      ) yield {
        ROK.s apply { implicit stok =>
          views.html.club.doeClubUserRegAdd(rk, next, u, reg)
        }
      }) getOrElse unauthorized()
  }

  def acthost = Form {
    tuple(
      "regAdmin" -> text.verifying("Invalid user", { x => Users.findUserByEmailDec((x)).isDefined }),
      "rel" -> text.verifying(vBadWords)
    ) verifying
      ("", { t: (String, String) =>
        true
      })
  }

  def doeClubActivateHostingBox(wid: WID) = RAction { implicit stok =>
    (for (
      au <- activeUser(stok.req)
    ) yield {
      if (Users.findUserByUsername(wid.name).isDefined) Ok("")
      else Ok(
        s"""<div class="alert alert-info">
           |Activate free hosting, registration and events management for this club.
           |<a href="/doe/club/activateHosting/${wid.wpath}" class="btn btn-success">Request activation</a>
           |
            |</div>""".stripMargin)
    }) getOrElse {
      Ok(
        s"""<div class="alert alert-warning">
           |Inquire about free hosting options.
           |You will need an active account to start hosting for this club.
           |</div>""".stripMargin
      )
    }
  }

  def doeClubActivateHosting(wid: WID) = RAction { implicit stok =>
    (for (
      au <- activeUser(stok.req)
    ) yield {
      ROK.k apply { implicit stok =>
        views.html.club.doeClubActivateHosting(
          wid,
          acthost.fill(
            "",
            ""
          )
        )
      }
    }) getOrElse {
      Ok(
        """<div class="alert alert-warning">
          |Inquire about hosting options. You will need an active account to start hosting for this club.
          |</div>""".stripMargin
      )
    }
  }

  def doeClubActivateHosting1(wid: WID) = RAction { implicit stok =>
    acthost.bindFromRequest()(stok.ireq).fold(
      formWithErrors => ROK.k badRequest { implicit stok =>
        views.html.club.doeClubActivateHosting(wid, formWithErrors)
      }, {
        case (a, r) => {
          if (stok.au.get.isAdmin && r == "admin") {
            Users.findUserByUsername(wid.name).map { u =>
              audit("CREATING_CLUB EXISTING USER" + wid.name)
              u.update(u.copy(roles = u.roles + UserType.Organization, clubSettings = Some(mkSettings(u, a))))
            }.getOrElse {
              audit("CREATING_CLUB NEW USER" + wid.name)
              var u = User(
                wid.name,
                wid.name,
                "",
                Config.curYear.toInt,
                Enc(wid.name + "@nobody.com"),
                Enc("nopassword"),
                's',
                Set(UserType.Organization))

              u = u.copy(clubSettings = Some(mkSettings(u, a)))
              val p = new model.Profile(u._id)
              u.create(p)
            }
            Msg2("Ok, boss, user created. Now edit the club...")
          } else {
            SendEmail.withSession(stok.realm) { implicit mailSession =>
              Emailer.tellAdmin(
                "Requires club activate",
                "user: " + stok.au.map(_.userName),
                "admin: " + a,
                "club: " + wid.name,
                "relation: " + r)
            }
            Msg2("Ok, request sent. You'll be notified by email asap.")
          }
        }
      })
  }

  /** list members */
  def doeClubUserRegs = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent
    ) yield {
      ROK.s apply { implicit stok =>
        views.html.club.doeClubUserRegs()
      }
    }) getOrElse unauthorized()
  }

  /** */
  def doeClubUserReg(regid: String) = FAU { implicit au => implicit errCollector => implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      reg <- Regs.findId(regid) orErr ("no reg found")
    ) yield {
      ROK.s apply { implicit stok =>
        views.html.club.doeClubUserReg(
          reg,
          //            RacerKidz.findForUser(reg.userId).toList)
          RacerKidz.findAssocForUser(reg.userId).map { rka =>
            (rka, RacerKidz.findById(rka.to))
          }.filter(_._2.isDefined).map(
            t => (t._1, t._2.get)
          ).toList)
      }
    }) getOrElse unauthorized()
  }

  // find and redirect to the reg form
  def doeRedirectRegKid(club: WID, id: String) = FAUR { implicit stok =>
    (for (
      c <- Club(club);
      au <- stok.au;
      ism <- c.isClubAdmin(au) orCorr cNotAdmin(club.wpath);
      rka <- ROne[RacerKidAssoc]("_id" -> new ObjectId(id)) orErr "no rka";
      regk <- RMany[RegKid]("rkId" -> rka.to).find(_.reg.exists(_.year == c.curYear)) orErr "no regk";
      form <- regk.roleWids.find(_.role == "Racer.Info").map(_.wid) orErr "no form"
    ) yield {
      Redirect(form.urlRelative(stok.realm))
    }) getOrElse unauthorized()
  }

  // parms optional
  def doeClubKidz(club: WID, role: String, team:String="") = FAUR { implicit stok =>
    (for (
      c <- Club(club);
      au <- stok.au;
      ism <- c.isMember(au) orCorr cNotMember(club.name)
    ) yield {
      val rks = (for (a <- c.rka(role);
                      k <- RacerKidz.findById(a.to)) yield
        (k, a)).toList.sortBy(x => x._1.info.lastName + x._1.info.firstName)

      val teams = Wikis.linksTo("Program", c.uwid, "Child").toList /*.sortBy(_.from.nameOrId)*/ // U8 is bigger than U10... ugh

      ROK.k apply {
        views.html.club.doeClubKidz(c, role, team, teams , rks) // U8 is bigger than U10... ugh
      }
    }) getOrElse unauthorized()
  }

  // insert
  def doeClubKidzTeam(teamWpath: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- auth orErr "Login for more details";
      isa <- au.isActive orErr "Need active account for details";
      team <- WID.fromPath(teamWpath) orErr "Team not found";
      c <- team.parentOf(WikiDomain(team.getRealm).isA("Club", _)).flatMap(Club.apply) orErr "Club not found";
      ism <- c.isMember(au) orCorr cNotMember(c.userName)
    ) yield {
      if ((!c.props.get("memberCanSeeOthers").exists(_ == "no") ||
        au.isAdmin || c.isClubCoach(au) || c.isClubAdmin(au)
        )) {
        // show members only to coaches and admins
        ROK.r noLayout { implicit stok =>
          views.html.club.doeClubKidzTeam(c, "", team)
        }
      } else {
        // show that they are members or nothing
        if (c.activeTeamMembers(team).exists(_._1.userId.exists(_ == au._id)))
          ROK.r.noLayout { implicit stok =>
            Html("<div class=\"alert alert-warning\">You are a member</div>") // return ok as this is an insert
          }
        else
          Ok("")
      }
    }) getOrElse ROK.r.noLayout { implicit stok =>
      Html("<div class=\"alert alert-warning\">" + errCollector.mkString + "</div>") // return ok as this is an insert
    }
  }

  def doeUpdRka(rkaid: String, prop: String, value: String) = FAUR { implicit request =>
    (for (
      rka <- RacerKidz.findAssocById(rkaid);
      club <- rka.club
    ) yield {
      razie.db.tx("doeUpdRka", request.userName) { implicit txn =>
        val newRka =
          if ("role" == prop)
            rka.copy(role = value).update
        Ok("ok")
      }
    }) getOrElse unauthorized();
  }

  def doeClubKidzSetTeam(rkaid: String, club: WID, teamid: String) = FAUR { implicit request =>
    (for (
      au <- request.au;
      rka <- RacerKidz.findAssocById(rkaid);
      club <- Club.findForAdmin(club, au) orErr ("Not a club or you're not admin")
    ) yield {
      razie.db.tx("doeClubKidzSetTeam", au.userName) { implicit txn =>
        val team = if (teamid != "-") Wikis(request.realm).findById("Program", teamid) else None;
        val old = RacerKidz.findWikiAssocById(rka.to.toString, club.curYear, "Program").filter(
          _.uwid.wid.exists(_.parentWid.exists(_.name == club.userName))).toList.headOption
        if (old.isDefined) {
          if (teamid == "-" || team.isEmpty) old.get.delete
          else old.get.copy(uwid = team.get.uwid).update
        }
        else team.foreach { team =>
          new RacerKidWikiAssoc(rka.to, team.uwid, club.curYear, rka.role).create
        }
      }
      Ok("ok")
    }) getOrElse unauthorized()
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

      razie.db.tx("doeMergeKid", au.userName) { implicit txn =>
        // override with email from user account
        liverk.user.foreach { u =>
          liverk.rki.foreach { rki =>
            rki.copy(email = u.email).update
          }
        }

        liverka.update
        liverk.update
        dierk.update
        dies.moveTo(liverka) // also moves hours and wikiassocs
      }

      if (au.isClub) Redirect("/") //routes.Club.doeClubKidz("",""))
      else Redirect(routes.Kidz.doeUserKidz)
    }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
  }

  // new user linked to club - give it access to forums
  def linkedUser(u: User, cname: WID, how: String)(implicit txn: Txn) = {
    Club(cname) foreach { club =>
      club.newFollows.foreach { rw =>
        val role = if (how == "Fan") "Fan" else rw.role
        rw.wid.uwid.foreach { uwid =>
          if (u.isLinkedTo(uwid).isEmpty)
            model.UserWiki(u._id, uwid, role).create
        }
      }
      club.newTasks.foreach { t => //(name, args)
        val args = t._2.toMap
        clog << "Creating user Task " + t

        //if not already
        val x = u.tasks.exists(ut =>
          ut.name == t._1 &&
            ut.args.toList.foldLeft(true)((a, b) =>
              a && args.get(b._1).exists(_ == b._2)
            )
        )

        if (!x)
          UserTask(u._id, t._1, args).create
      }
    }
  }

  // backwards - remove it all
  def userLeft(u: User, cname: String)(implicit txn: Txn) = {
    Club(cname) foreach { club =>
      club.newFollows.foreach { rw =>
        rw.wid.uwid.foreach { uwid =>
          // remove all forums and calendars
          u.isLinkedTo(uwid).filter(_.uwid.cat != "Blog").map { uw =>
            uw.delete
          }
        }
      }
      club.newTasks.foreach { t => //(name, args)
        val args = t._2.toMap
        //todo if not already
        val x = u.tasks.filter(ut =>
          ut.name == t._1 &&
            ut.args.toList.foldLeft(true)((a, b) =>
              a && args.get(b._1).exists(_ == b._2)
            )
        )
        x.map(_.delete)
      }
    }
  }

  /** called by user not the club - copy previous registratrion forms and then continue */
  def doeStartRegCopy(clubName: WID, prevRegId: String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Club(clubName);
      regAdmin <- c.uregAdmin orErr "Registration is not open yet! [no regadmin]";
      isOpen <- c.isRegOpen orErr "Registration is not open yet!";
      prevReg <- Regs.findId(prevRegId);
      // either mine or i'm the club
      isMine <- (prevReg.userId == au._id && prevReg.clubName == clubName.name || prevReg.clubName == au.userName) orErr ("Not your registration: " + prevRegId);
      user <- Users.findUserById(prevReg.userId) orErr ("Cant find user: " + prevReg.userId);
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent;
      isSame <- (prevReg.year != c.curYear) orErr ("Can't copy this year's registration: " + prevRegId)
    ) yield {
      // 1. expire
      razie.db.tx("userStartRegCopy", au.userName) { implicit txn =>
        //        cout << "OLD REG: " << prevReg
        var reg = prevReg.copy(_id = new ObjectId(), year = c.curYear, regStatus = RegStatus.PENDING, paid = "", amountPaid = Price(0), rFee=None, rFeeDue = None)

        //        cout << "REG: " << reg

        val widNameMap = new collection.mutable.HashMap[String, String]()

        val newWids = reg.deprecatedWids.zipWithIndex.map { t =>
          val (wid, seqNum) = t

          //          cout << "  proc " << wid
          val oldW = Wikis.find(wid).get

          // find and upgrade the year
          val PAT = "([^-]+)-([^-]+)-([^-]+)(-.*)?-([^-]+)-([^-]+)".r
          val PAT(name, role, id, kkid, y, num) = oldW.wid.name
          val kid = Option(kkid) getOrElse ""
          val kidName = if (kid startsWith "-") kid.substring(1) else kid

          // new form spec
          val newForm = c.regForms.find(_.wid.name == name).getOrElse {
            val newname = name.replaceFirst(prevReg.year, reg.year)
            c.regForms.find(_.wid.name == newname).getOrElse {
              c.regForms.find(_.role == role).get // todo kaboom - not nice
            }
          }

          // reset seq numbers
          val newfwid = WID("Form", s"${newForm.wid.name}-${role}-${id + kid}-${reg.year}-${seqNum}")

          widNameMap put(oldW.wid.name, newfwid.name)

          //    // have to create form ?
          if (!Wikis.find(newfwid).isDefined) {
            var label = removeYear(s"${newForm.wid.name.replaceAll("_", " ")}")
            if (!label.contains(reg.year))
              label = label.trim + s" for $kidName season ${reg.year}"
            val newW = controllers.Forms.copyForm(user, oldW, newfwid.name, label, newForm, c.filterRegFields)
          }

          RoleWid(role, newfwid)
        }

        reg = reg.copy(
          //          wids = newWids.map(_.wid),
          roleWids = newWids
        )

        reg.create

        // copy kid regs with new form names as well
        prevReg.kids.toList.map { ork =>
          val rk = ork.copy(
            _id = new ObjectId(),
            regId = reg._id,
            //            wids=ork.deprecatedwids.map(ow => WID(ow.cat, widNameMap(ow.name))),
            roleWids = ork.roleWids.map(rw => RoleWid(rw.role, WID(rw.wid.cat, widNameMap(rw.wid.name)))),
            crDtm = DateTime.now()
          )
          rk.create
          assoc(c, rk.rk.get, mod.snow.RK.ASSOC_REGD, rk.role, au, c.curYear)
        }


        //      3. start - notify user
        SendEmail.withSession(Website.realm(request)) { implicit mailSession =>
          //        Emailer.sendEmailClubRegStart(au, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
          //        Emailer.tellAdmin("Started registration", "user: " + au.userName, "club: " + clubName, "how: "+how)
          ////        TODO tell regAdmin so they know...
          //
          //        UserTask(au._id, UserTasks.START_REGISTRATION).delete
          //      }
          Redirect(routes.Club.doeClubUserReg(reg._id.toString))
        }
      }
    }) getOrElse {
      if (activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
        ROK.r apply { implicit stok =>
          views.html.user.doeConsent()
        }
      else
        Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
    }
  }

  /** called by user not the club */
  def doeStartRegSimple(clubWid: WID) = FAUR { implicit stok=>
    (for (
      c <- Club(clubWid);
      regAdmin <- c.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- c.isRegOpen orErr ("Registration is not open yet!");
      isConsent <- stok.au.get.profile.flatMap(_.consent).isDefined orCorr cNoConsent
    ) yield {
      Regs.findClubUserYear(clubWid, stok.au.get._id, c.curYear).map { reg =>
        UserTask(stok.au.get._id, UserTasks.START_REGISTRATION).delete(tx.auto)
        Msg2("Registration already in progress for club " + clubWid, Some(routes.Club.doeClubUserReg(reg._id.toString).url))
      }.getOrElse {
        if(Regs.findClubUser(c.wid, stok.au.get._id).size > 0 ||
           c.props.get("reg.newMembers").exists(_ == "yes"))
          ROK.k apply views.html.club.doeClubUserStartReg(clubWid)
        else
          Msg2("Registration not open yet for club " + clubWid)
      }
    }) getOrElse {
      if (activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
        ROK.k apply views.html.user.doeConsent(routes.Club.doeStartRegSimple(clubWid).url)
      else
        Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
    }
  }

  /** called by user not the club
    *
    * todo finish the code - it's meant to allow reg when is not open
    */
  def doeStartReg(clubWid: WID, how: String, code:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      c <- Club(clubWid);
      regAdmin <- c.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- (c.isRegOpen || code.length == 7) orErr ("Registration is not open yet!");
      isConsent <- au.profile.flatMap(_.consent).isDefined orCorr cNoConsent
    ) yield {
      // current registration?
      razie.db.tx("userStartReg", au.userName) { implicit txn =>
        if ("None" == how) {
          UserTask(au._id, UserTasks.START_REGISTRATION).delete
          Redirect("/")
        } else Regs.findClubUserYear(clubWid, au._id, c.curYear).map { reg =>
          Msg2("Registration already in progress for club " + clubWid, Some(routes.Club.doeClubUserReg(reg._id.toString).url))
        } getOrElse {
          // 1. expire
          var reg = Reg(au._id, clubWid.name, c.wid, c.curYear, RK.ROLE_MEMBER, Seq(), Seq(), RegStatus.PENDING)
          reg.create

          // 2. add family
          cout << "3 " + c.regForms.filter(_.role startsWith how).mkString
          if (how == "Family")
            c.regForms.filter(_.role startsWith how).foreach { rw =>
              addForm(au, c, reg, regAdmin, rw.role)
              reg = Regs.findClubUserYear(clubWid, au._id, c.curYear).get
            }

          // 3. start - notify user
          SendEmail.withSession(Website.realm(request)) { implicit mailSession =>
            Emailer.sendEmailClubRegStart(au, au.userName, routes.Club.doeClubUserReg(reg._id.toString).toString)
            Emailer.tellAdmin("Started registration", "user: " + au.userName, "club: " + clubWid, "how: " + how)
            // TODO tell regAdmin so they know...
            UserTask(au._id, UserTasks.START_REGISTRATION).delete
            Redirect(routes.Club.doeClubUserReg(reg._id.toString))
          }
        }
      }
    }) getOrElse {
      if (activeUser.isDefined && !activeUser.get.profile.flatMap(_.consent).isDefined)
        ROK.r apply { implicit stok =>
          views.html.user.doeConsent()
        } else
        Msg2("CAN'T START REGISTRATION " + errCollector.mkString)
    }
  }

  /** called by club */
  def doeCreateRegTask(uwid: String, cwid: WID) = FAU { implicit au => implicit errCollector => implicit request =>
    (for (
      club <- Club(cwid);
      regAdmin <- club.uregAdmin orErr ("Registration is not open yet! [no regadmin]");
      isOpen <- club.propSeq.exists(x => "reg.open" == x._1 && "yes" == x._2) orErr ("Registration is not open yet!");
      uw <- model.Users.findUserLinksTo(club.uwid).find(_._id.toString == uwid) orErr ("user is not a member");
      u <- uw.user
    ) yield {
      // current registration?
      razie.db.tx("userStartReg", au.userName) { implicit txn =>
        if (!u.tasks.exists(_.name == UserTasks.START_REGISTRATION))
          UserTask(u._id, UserTasks.START_REGISTRATION, Map("club" -> cwid.wpath)).create
      }
      Redirect(routes.Club.doeClubReg(cwid, uwid))
    }) getOrElse {
      Msg2("CAN'T " + errCollector.mkString)
    }
  }

  /** change owner */
  def doeAddClubFollowers(wid: WID) = FAU {
    implicit au => implicit errCollector => implicit request =>

      val sForm = Form("newvalue" -> nonEmptyText)

      sForm.bindFromRequest.fold(
        formWithErrors =>
          Msg2(formWithErrors.toString + "Oops, bad newValue"), {
          case newvalue =>
            log("Club.addFollowers " + wid + ", " + newvalue)
            (for (
              c <- WID.fromPath(newvalue);
//              c <- Users.findUserByUsername(newvalue);
//              isClub <- c.isClub orErr "registration only for a club";
              club <- Club(c);
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              w <- Wikis.find(wid)
            ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Club.addFollowers", au.userName) { implicit txn =>
                club.userLinks.foreach { uw =>
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
          Msg2(formWithErrors.toString + "Oops, bad newValue"), {
          case newvalue =>
            log("Club.delFollowers " + wid + ", " + newvalue)
            (for (
              c <- WID.fromPath(newvalue);
//              c <- Users.findUserByUsername(newvalue);
//              isClub <- c.isClub orErr "registration only for a club";
              club <- Club(c);
              ok1 <- au.hasPerm(Perm.adminDb) orCorr cNoPermission;
              page <- Wikis.find(wid)
            ) yield {
              // can only change label of links OR if the formatted name doesn't change
              razie.db.tx("Club.delFollowers", au.userName) { implicit txn =>
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
        razie.db.tx("Wikie.delFollowers", au.userName) { implicit txn =>
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

  def redirectToPage(role: String) = FAUR { implicit request =>
    val clubs = request.au.get.clubs.collect {
      case x if x.uwid.page.isDefined && x.uwid.page.get.contentProps.contains(role) => x.uwid.page.get
    }
    val wids = clubs.map(p => (p, p.contentProps.get(role).flatMap(WID.fromPath))).filter(_._2.isDefined)

    if (wids.size == 1)
      Redirect(wids.head._2.get.urlRelative(request.realm))
    else if (wids.size > 1) {
      val links = wids.map { t =>
        s"""For ${t._1.getLabel} - ${t._2.get.ahrefNice(request.realm)}"""
      }.mkString("<br>")

      Msg("Multiple found in your clubs... select one, to continue",
        s"""
           |$links
           |""".stripMargin)
    } else
      Msg("No such forum/calendar found in your clubs...",
        """
          |To join a club, click [/wikie/like/Club here].
          |
          |To host/create a club or learn more about this website, read [/wiki/Admin:Hosted_Services_for_Ski_Clubs this].
          | """.stripMargin)
  }

  def doePurgeRegs(clubName: WID, year: String = "") = Action.async { implicit request =>
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
              Regs.findClubUserYear(clubName, uw.userId, year))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString
          )
        val regs = Regs.findClubYear(clubName, year).toList
        val rks = RMany[ModRkEntry]("curYear" -> year).filter(_.wpath contains clubName).toList
        val rkas = RMany[RacerKidAssoc]("from" -> c.userId, "year" -> year).toList //.filter(_.assoc == "Registered").toList
        //          val rkas = RMany[RacerKidAssoc]("year" -> year).toList//.filter(_.assoc == "Registered").toList

        ROK.r apply { implicit stok =>
          views.html.club.doeClubPurgeRegs(clubName, au, year, members, regs, rks, rkas)
        }
      }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
    }
  }

  def doeDoPurgeRegs(what: String, clubName: WID, year: String = "") = Action.async { implicit request =>
    Future {
      implicit val errCollector = new VErrors()
      (for (
        au <- activeUser;
        can <- au.isAdmin orErr "not admin";
        c <- Club.findForAdmin(clubName, au) orErr ("Not a club or you're not admin")
      ) yield {
        {
          val members =
            model.Users.findUserLinksTo(c.uwid).map(uw =>
              (model.Users.findUserById(uw.userId),
                uw,
                Regs.findClubUserYear(clubName, uw.userId, year))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString
            )
          val regs = Regs.findClubYear(clubName, year).toList
          val rks = RMany[ModRkEntry]("curYear" -> year).filter(_.wpath contains clubName).toList
          val rkas = RMany[RacerKidAssoc]("year" -> year).filter(_.assoc == "Registered").toList

          razie.db.tx("doeDoPurgeRegs", au.userName) { implicit txn =>
            what match {
              case "WikiCount" =>
                regs.map(_.deprecatedWids.flatMap(_.uwid.toSeq).flatMap { uw =>
                  RMany[WikiCount]("pid" -> uw.id).toSeq
                }.foreach(_.trash(au.id)))
              case "WikiLink" =>
                regs.map(_.deprecatedWids.flatMap(_.uwid.toSeq).flatMap { uw =>
                  RMany[WikiLink]("from.id" -> uw.id).toSeq ++
                    RMany[WikiLink]("to.id" -> uw.id).toSeq
                }.foreach(_.trash(au.id)))
              case "UserWiki" =>
                regs.map(_.deprecatedWids.flatMap(_.uwid.toSeq).flatMap { uw =>
                  RMany[UserWiki]("uwid.id" -> uw.id).toSeq
                }.foreach(_.trash(au.id)))
              case "ModRkEntry" => rks.foreach(_.trash(au.id))
              case "RacerKidAssoc" => rkas.foreach(_.trash(au.id))
              case "Form" => regs.foreach(_.deprecatedWids.flatMap(_.page.toSeq).foreach(_.delete(au.id)))
              case "RegKid" => regs.map(_.kids.foreach(_.trash(au.id)))
              case "Reg" => regs.foreach(_.trash(au.id))
              case "VolunteerH" =>
                rkas.foreach { x =>
                  RacerKidz.findVolByRkaId(x._id.toString).foreach(_.trash(au.id))
                }
            }
          }
        }

        val members =
          model.Users.findUserLinksTo(c.uwid).map(uw =>
            (model.Users.findUserById(uw.userId),
              uw,
              Regs.findClubUserYear(clubName, uw.userId, year))).toList.sortBy(x => x._1.map(y => y.lastName + y.firstName).mkString
          )
        val regs = Regs.findClubYear(clubName, year).toList
        val rks = RMany[ModRkEntry]("curYear" -> year).filter(_.wpath contains clubName).toList
        val rkas = RMany[RacerKidAssoc]("year" -> year).filter(_.assoc == "Registered").toList

        ROK.r apply { implicit stok =>
          views.html.club.doeClubPurgeRegs(clubName, au, year, members, regs, rks, rkas)
        }
      }) getOrElse Msg2("CAN'T SEE PROFILE " + errCollector.mkString)
    }
  }

  // user starting to pay from reg: create cart and redirect to it
  def doeClubRegStartPay(regid: String, what:String) = FAUR { implicit stok =>
    //regStatus should be ACCEPTED right now
    (for (
      reg <- Regs.findId(regid) orErr ("no reg found");
      club <- Club(reg.club) orErr "Club not found";
      ism <- club.isMember(stok.au.get) orCorr cNotMember(club.name)
    ) yield razie.db.tx("doeClubRegStartPay", stok.userName) { implicit txn =>
        //        e = e.copy(state = Some(STATE_INCART))
        val cart = Cart.createOrFind(stok.au.get._id, club.uwid)
        //        val name = RacerKidz.findByIds(rkid).map(_.info.ename).getOrElse("??")
        // todo items added to cart etc
        val newFailState = reg.regStatus

        // the cart checkout will take current balance into account
        val price =
          if(what == "total") (reg.fee - reg.amountPaid).orZero
          else (reg.feeDue - reg.amountPaid).orZero

        cart.add(CartItem(
          s"Registration for ${club.name} for ${club.curYear}",
          regid,
          regid,
          s"""$$msg modsnow.updregstatus(regid="$regid", status="${RegStatus.CURRENT}", paid="${price.amount}")""",
          s"""$$msg modsnow.updregstatus(regid="$regid", status="$newFailState")""",
          ItemPrice(Some(price))
        ))
        Redirect(mod.cart.routes.Carts.cart())
    }) getOrElse unauthorizedPOST()
  }

}


