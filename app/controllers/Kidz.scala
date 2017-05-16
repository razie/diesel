package controllers

import mod.snow._
import razie.db.{RMany, RazMongo, ROne, Txn}
import razie.db.RMongo._
import razie.wiki.Sec._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, tuple, _}
import play.api.mvc.Action
import razie.OR._
import razie.wiki.admin.SendEmail
import razie.wiki.model.{WikiLink, WID}

import scala.util.Try

/** manage kidz (persons) */
object Kidz extends RazController {

  def doeUserKidz = FAUR { implicit stok=>
    ROK.k apply {
      views.html.user.doeUserKidz()
    }
  }

  def findAllClubs(au:User, rk:RacerKid) = {
    val clubs = au.clubs.flatMap(_.uwid.wid.toList).flatMap(wid=>Club(wid).toList)
    clubs ::: rk.clubs.filter(c=> !clubs.exists(_.name == c.name))
  }

  def form(au: User) = Form {
    tuple(
      "fname" -> nonEmptyText.verifying(vSpec, vBadWords),
      "lname" -> nonEmptyText.verifying(vSpec, vBadWords),
      "email" -> text.verifying(vSpec, vEmail),
      "dob" -> jodaDate,
      "gender" -> text.verifying(vSpec).verifying(x => x == "M" || x == "F"),
      "role" -> nonEmptyText.verifying(vSpec, vBadWords),
      "status" -> text.verifying(vSpec),//.verifying("bad status", x => "asf" contains x),
      "assocRole" -> text.verifying(vSpec, vBadWords),
      "invite" -> text.verifying(vSpec, vBadWords),
      "notifyParent" -> text.verifying(vSpec).verifying(x => x == "y" || x == "n")) verifying (
        "Must notify parent if there's no email...", {
          t: (String, String, String, DateTime, String, String, String, String, String, String) =>
            val _@ (f, l, e, d, g, r, ar, s, i, n) = t
            !(e.isEmpty && n == "n")
        }) verifying (
          "Must have no more than one spouse...", {
            t: (String, String, String, DateTime, String, String, String, String, String, String) =>
              val _@ (f, l, e, d, g, r, ar, s, i, n) = t
              !(r == RK.ROLE_SPOUSE && RacerKidz.findAllForUser(au._id).exists(_.info.roles.toString == RK.ROLE_SPOUSE))
          }) verifying (
            "You are already defined...", {
              t: (String, String, String, DateTime, String, String, String, String, String, String) =>
                val _@ (f, l, e, d, g, r, ar, s, i, n) = t
                !(r == RK.ROLE_ME && RacerKidz.findAllForUser(au._id).exists(_.info.roles.toString == RK.ROLE_ME))
            })
  }

  def canSeeKid(rkid:String, au:User, next:String) = {
    if(au.isClub) true
    else if(next.startsWith("kidz") || next.startsWith("Reg:")) {
      //user managing his kids
      rkid.length <=3 || RacerKidz.rka(au).toList.find(_.to.toString == rkid).isDefined
    } else if(next.startsWith("clubkidz:") || next.startsWith("Club:") || next.startsWith("invite:")) {
      val cname = next.substring(next.indexOf(':') + 1)
      //either myself, my kids or club admin/coach
      RacerKidz.rka(au).toList.find(_.to.toString == rkid).isDefined ||
      Club(cname).exists(c=> c.isClubCoach(au) || c.isClubAdmin(au))
    } else false

//    else if (next startsWith "ClubMem:")
//      Redirect(routes.Club.doeClubRegs(clubName))
//    else if (next startsWith "invite:")
//      Redirect(routes.Kidz.doeKidHistory(clubName, goodRkid, ""))
  }

  def canEditKid(rkid:String, au:User, next:String) = {
    if(au.isClub) true
    else if(rkid.length <= 2) true
    else if(next.startsWith("kidz") || next.startsWith("Reg:")) {
//    else if(next.startsWith("kidz") || ObjectId.isValid(next)) {
      //user managing his kids
      rkid.length <=3 || RacerKidz.rka(au).toList.find(_.to.toString == rkid).isDefined
    } else if(next.startsWith("clubkidz:") || next.startsWith("Club:") || next.startsWith("invite:")) {
      val cname = next.substring(next.indexOf(':') + 1)
      Club(cname).exists(c => c.isClubAdmin(au))
    } else false
  }

  // see kid form
  def doeUserKid(pId: String, rkId: String, role: String, associd: String, next: String) = FAUR { implicit stok =>
      val rk =
        if (rkId.length > 3) RacerKidz.findById(new ObjectId(rkId))
        else None

      val k =
        if (rkId.length > 3)
          rk map (_.info) getOrElse RacerKidz.empty
        else RacerKidz.empty

      val rki =
        if (rkId.length > 3)
          rk flatMap (_.rki) getOrElse RacerKidz.empty
        else RacerKidz.empty

      val arole =
        if (associd.length > 3) new ObjectId(associd).as[RacerKidAssoc].get.role
        else ""

    if(canSeeKid(rkId, stok.au.get, next))
      ROK.k apply {implicit stok=>
        (
          views.html.user.doeUserKid(
            pId, rkId, role, associd, next,
            form(stok.au.get).fill((
              k.firstName, k.lastName, k.email.dec,
              rk flatMap (_.rki) map (_.dob) getOrElse rk.map(rk => new DateTime(rk.info.yob, 1, 1, 1, 1)).getOrElse(RacerKidz.empty.dob),
              rki.gender,
              if (rkId.length > 3) k.roles.mkString else role,
              k.status.toString,
              arole,
              "",
              if (k.notifyParent) "y" else "n"))))
      }
    else unauthorized("This info is private")
  }

  // updated/created kid form
  def doeKidUpdate(userId: String, rkId: String, role: String, associd: String, next: String) = FAUR { implicit request =>
    val au = request.au.get
    var goodRkid = rkId
    form(auth.get).bindFromRequest.fold(
      formWithErrors => ROK.k badRequest {implicit stok=>
        views.html.user.doeUserKid(userId, rkId, role, associd, next, formWithErrors)
      },
      {
        case (xf, xl, xe, d, g, r, s, ar, i, n) =>
          val f = xf.trim
          val l = xl.trim
          val e = xe.trim
          val status=if(s.length > 1) s else "a"

          // call only if it's for a club
          def clubName = next.substring(next.indexOf(':') + 1)
          def clubwid = WID.fromPath(clubName).get

          // where to next
          def res = if (next startsWith "Club:")
            Redirect(routes.Club.doeClubReg(clubwid, Club(clubName).get.userLinks.filter(_.userId.toString == userId).next._id.toString))
          else if (next startsWith "ClubMem:")
            Redirect(routes.Club.doeClubRegs(clubwid))
          else if (next startsWith "clubkidz:")
            Redirect(routes.Club.doeClubKidz(WID.fromPath(clubName).get))
          else if (next startsWith "invite:")
            Redirect(routes.Kidz.doeKidHistory(clubName, goodRkid, ""))
          else if (next == "kidz")
            Redirect(routes.Kidz.doeUserKidz)
          else if(next startsWith("Reg:"))
            Redirect(routes.Club.doeClubUserReg(clubName))
          else
            Redirect("/")

          // just update the association type - for former members, to make them fans
          var assocOnly = false
          if (rkId.length > 2 && associd.length > 2) {
            new ObjectId(associd).as[RacerKidAssoc].foreach { rka =>
              if (ar.length > 0 && ar != rka.role) {
                rka.copy(role = ar).update
                //todo update the userlink as well
                assocOnly = true
              }
            }
          }

          if (assocOnly) {
            res
          } else if (rkId.length > 2) {
            // update
            val rk = RacerKidz.findById(new ObjectId(rkId)).get
            val rki = rk.rki.get
            rki.copy(firstName = f, lastName = l, email = e.enc, dob = d,
              gender = g, status = status charAt 0, roles = Set(r)).update
//              if (associd.length > 2) new ObjectId(associd).as[RacerKidAssoc].foreach { rka =>
//                if (ar != rka.role)
//                  rka.copy(role = ar).update
//              }
            res
          } else {
            // create
            if (RacerKidz.findByParentUser(new ObjectId(userId)).exists(x => x.info.firstName == f || (x.info.email.dec == e && e.length > 1)))
            // is there already one with same name or email?
              Msg2("Kid with same first name or email already added", Some("/doe/user/kidz"))
            else {
              val rk = new RacerKid(au._id)
              val rki = new RacerKidInfo(f, l, e.enc, d, g, Set(r), status charAt 0, n == "y", rk._id, au._id)
              rk.copy(rkiId = Some(rki._id)).create
              rki.create
              goodRkid = rk._id.toString
              val rka = RacerKidAssoc(
                new ObjectId(userId),
                rk._id,
                (if (next.startsWith("invite:")) RK.ASSOC_INVITED else RK.ASSOC_PARENT),
                role OR r,
                au._id)
              rka.create
              (rk, rki)

              if (next.startsWith("invite:") && e.length > 0) {
                //todo email invite
                SendEmail.withSession(request.realm) { implicit mailSession =>
                  Emailer.sendEmailInvited(au, rk, role, "/doe/kid/acceptInvite/"+rka._id.toString)
                  Emailer.tellRaz("Invited", "user: " + au.userName, "club: " + clubName, "how: "+role)
                }
              }

              res
            }
          }
      })
  }

  def doeKidOverride(userId: String, rkId: String, role: String, associd: String, next: String) = FAUR { implicit request =>
    val au = request.au.get
    (for (
      rk <- RacerKidz.findById(new ObjectId(rkId));
      u <- rk.user
    ) yield {
        // call only if it's for a club
        def clubName = next.substring(next.indexOf(':') + 1)
        def clubwid = WID.fromPath(clubName).get

        def res = if (next startsWith "Club:")
          Redirect(routes.Club.doeClubReg(clubwid, Club(au).userLinks.filter(_.userId.toString == userId).next._id.toString))
        else if (next startsWith "ClubMem:")
          Redirect(routes.Club.doeClubRegs(clubwid))
        else if (next startsWith "clubkidz:")
          Redirect(routes.Club.doeClubKidz(WID.fromPath(clubName).get))
        else if (next == "kidz")
          Redirect(routes.Kidz.doeUserKidz)
        else if(next startsWith("Reg:"))
          Redirect(routes.Club.doeClubUserReg(clubName))
        else
          Redirect("/")

        val rki = new RacerKidInfo(
          u.firstName,
          u.lastName,
          u.email,
          DateTime.parse(u.yob.toString + "-01-01"),
          u.gender,
          u.roles,
          'a',
          true,
          rk._id,
          au._id)

        rki.create
        rk.copy(rkiId = Some(rki._id)).update

        Redirect(routes.Kidz.doeUserKid(userId, rkId, role, associd, next))
      }) getOrElse {
      error("ERR_CANT_CREATE_KID ")
      unauthorized("Oops - cannot create this entry... ")
    }
  }

  def cNotCoach (clubName:String) = new Corr(
    "This is only for coaches of club "+clubName,
    "")

  def doeKidHistory(club:String, rkId: String, settings:String) = FAUR { implicit stok=>
    (for (
      rk <- RacerKidz.findById(new ObjectId(rkId)) orErr "No Person records found";
      c <- Club(club) orElse Kidz.findAllClubs(stok.au.get, rk).headOption orErr s"Club not found ($club)";
      au <- stok.au
    ) yield {
        if( // me
          rk.userId.exists(_ == au._id) ||
            // or parent
          RacerKidz.rka(au).toList.find(_.to.toString == rkId).isDefined
        ) {
          ROK.k apply {
            views.html.user.doeKidHistory(Some(c), rk, settings)
          }
        } else {
          if(c.isClubAdmin(au) || c.isMemberRole(au._id, RK.ROLE_COACH))
            ROK.k apply { views.html.user.doeKidHistory(Some(c), rk, settings) }
          else unauthorized("This info is private (not coach)")
        }
      }) getOrElse unauthorized("This info is private")
  }

  // todo optimize - cache badge in user
  def doeHistoryBadge = RAction { implicit stok=>
    stok.au.map {au=>
      val rk = RacerKidz.myself(au._id)
      def f(x:String) =
        s"""<a href="/doe/history"><span class="badge" style="background-color: red" title="updates since last time">$x</span></a>"""
      Ok(
        if(rk.history.news > 9) f("9+")
        else if(rk.history.news > 0) f(rk.history.news.toString)
        else ""
      )
    } getOrElse {
      Ok("")
    }
  }

  /** entry point from badge - my own history */
  def doeHistory(settings:String) = FAUR { implicit stok=>
    val rk = RacerKidz.myself(stok.au.get._id)
    // todo only supports one club - validations in doeKidHistory
    val c = Kidz.findAllClubs(stok.au.get, rk).headOption
    ROK.k apply {
      val x = views.html.user.doeKidHistory(c, rk, settings)
      // if me - reset me news badge
      // AFTER the screen is rendered
      val h = rk.history
      if(rk.userId.exists(_ == stok.au.get._id)) rk.history.reset
      x
    }
  }

  /** delete a history item */
  def doeDismissHistory(hid:String) = FAUR { implicit stok=>
    (for (
      i <- ROne[RkHistory]("_id" -> new ObjectId(hid)) orErr "can't find history element";
      rk <- ROne[RacerKid]("_id" -> i.rkId) orErr "no rk";
      can <- (rk.userId.exists(_ == stok.au.get._id) || stok.au.exists(_.isAdmin)) orErr "not yours to dismiss"
    ) yield {
      rk.history.delete(i)
      Ok("ok")
    }) getOrElse unauthorizedPOST()
  }
}

