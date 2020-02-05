package mod.snow

import controllers._
import model._
import org.bson.types.ObjectId
import razie.Logging
import razie.audit.Audit
import razie.db.{ROne, tx}
import razie.diesel.dom.WikiDomain
import razie.wiki.Sec._
import razie.wiki.model._
import razie.wiki.model.features.WForm
import razie.hosting.WikiReactors
import razie.tconf.Visibility
import razie.wiki.Config
import scala.Option.option2Iterable

case class RoleWid(role: String, wid: WID)

/** controller for club management */
object Snow extends RazController with Logging {

  def invite(wid:WID, role:String) = FAUR { implicit request =>
    val club = Club(wid).get
    Redirect(controllers.routes.Kidz.doeUserKid(club.userId.toString, "11", role, "-", "invite:"+club.wid.wpath))
  }

  def manage(club:WID, role:String, team:String) = FAUR { implicit request =>
    Redirect(controllers.routes.Club.doeClubKidz(club, role, team))
  }

  // list of clubs and teams with links
  def cteams (cat:String) = FAUR { implicit stok=>
    var msg =
      Some(RacerKidz.myself(stok.au.get._id)).map { rk =>
        Kidz.findAllClubs(stok.au.get, rk).map { club =>
          club.wid.ahrefNice(stok.realm) +
            // me and kids
            {
              val x = RacerKidz.rk(stok.au.get).flatMap(_.teams(club,"")).toList.
            // distinct by uwid
              groupBy(_.uwid).values.map(_.head).map { team=>
                team.uwid.findWid.map(_.ahrefNice(stok.realm)).getOrElse(
                  s"""<span title="Team assoc with no name ${team._id.toString}">?</span>"""
                )
              }.toList.sorted
              if(x.isEmpty) "" else x.mkString(" ( ", "|", " ) ")
            }
        } mkString " | "
      } mkString

    if(msg.trim.isEmpty)
      msg = s"""<small>[You need to join a """ +
        cat.split(",").map(cat=>s"""<a href="/wikie/like/$cat">$cat</a>""").mkString(", ") +
        """ OR read more <a href="/wiki/Admin:Hosted_Services_for_Ski_Clubs">about this website</a>] </small>"""
    else {
      val cats = cat split ","
      msg = msg+
        s"""&nbsp;&nbsp;<small style="float:right">[Add a """ +
        cats.map(cat=>s"""<a href="/wikie/like/$cat">$cat</a>""").mkString("|")+
      """] </small>"""
    }

    Ok( msg )
  }

  // pro activated account?
  def firstTime(cat:String) = FAUR { implicit stok=>
    var msg = ""
    if(!WID(cat, stok.au.get.userName).r(stok.realm).page.isDefined)
      msg =
        """
          |<div class="alert alert-danger">
          |<small><b>
          |You need to <a href="/4us/activate/Pro">Activate your Pro page</a>
          |OR read more <a href="/wiki/Admin:Hosted_Services_for_Ski_Pros">about this website</a>]
          |</b></small>
          |</div>
          |""".stripMargin

    Ok( msg )
  }


  // pro activate account
  def activate(cat:String) = FAUR { implicit stok =>
    Forms.sForm(WID("FormDesign", "ActivatePro"), routes.Snow.activate1(cat).url)
  }

  // edit form submitted
  def activate1(cat:String) = FAUR { implicit stok=>
    val w = WID("FormDesign", "ActivatePro").r(stok.realm).page.get
    val wf = new WForm(w)
    val (newData, errors) = wf.validate(stok.formParms)
//    val x = wf.mkContent(Forms.json(Map() ++ newData, !errors.isEmpty))
    val x = WForm.formData(Forms.json(Map() ++ newData, false))

    val wid = WID(cat, stok.au.get.userName).r(stok.realm)
    val name = wid.name
    val email = stok.au.get.emailDec

    val discipline=stok.formParm("discipline")
    val system=stok.formParm("system")
    val hasCalendar=stok.formParm("cbCalendar") == "y"
    val hasForum=stok.formParm("cbForum") == "y"
    val hasBuy=stok.formParm("cbBuyAndSell") == "y"
    val approve=
      if(stok.formParm("cbApprove") == "y") "no" else "yes"
    val desc=stok.formParm("desc")
    val certs=stok.formParm("certs")
    val photo=stok.formParm("photo")
    val video=stok.formParm("video")
    val visible=stok.formParm("visible")

    implicit val txn = razie.db.tx.local("activate1", stok.userName)

    if(!wid.page.isDefined) {
      // first time
      val firstPara =
        s"""
        |$x
        """.stripMargin

      val ibuy = if(hasBuy) s"..role.buyandsell $cat:$name/Forum:${name}_Buy_Sell" else ""
      val iforum = if(hasForum) s"..role.resources $cat:$name/Forum:${name}_News" else ""
      val ical = if(hasCalendar) s"..role.calendars $cat:$name/Forum:${name}_Calendar" else ""

      val club = WikiEntry(
        wid.cat,
      wid.name,
      stok.au.get.fullName,
      "md",
      s"""
        |$firstPara
        |
        |{{.DO NOT EDIT THIS PART below}}
        |
        |..editMode Draft
        |
        |{{.moderator:$email}}
        |
        |$ibuy
        |$iforum
        |$ical
        |
        |{{.DO NOT EDIT THIS PART above}}
      """.stripMargin,
      stok.au.get._id,
      Seq(cat.toLowerCase),
      stok.realm).copy(props=Map(
        "wvis" -> Visibility.MODERATOR,
        "visibility" -> visible,
        "owner" -> stok.au.get.id))

      val buy = if(!hasBuy) None else Some(
        WikiEntry(
          "Forum",
          s"${name}_Buy_Sell",
          s"${stok.au.get.fullName} - buy and sell",
          "md",
          s"""
             |${stok.au.get.fullName}'s buy and sell.
             |
        |{{.DO NOT EDIT THIS PART below}}
             |..editMode Draft
             |{{.DO NOT EDIT THIS PART above}}
      """.stripMargin,
          stok.au.get._id,
          Seq("forum"),
          stok.realm).copy(parent=Some(club._id), props=Map("wvis" -> Visibility.CLUB, "owner" -> stok.au.get.id))
      )

      val forum = if(!hasForum) None else Some(
        WikiEntry(
          "Forum",
          s"${name}_Forum",
          s"${stok.au.get.fullName} - forum",
          "md",
          s"""
             |${stok.au.get.fullName}'s forum.
             |
        |{{.DO NOT EDIT THIS PART below}}
        |..editMode Draft
        |{{.DO NOT EDIT THIS PART above}}
      """.stripMargin,
          stok.au.get._id,
          Seq("forum"),
          stok.realm).copy(parent=Some(club._id), props=Map("wvis" -> Visibility.MODERATOR, "owner" -> stok.au.get.id))
      )

      val calendar = if(!hasCalendar) None else Some(
        WikiEntry(
          "Calendar",
          s"${name}_Calendar",
          s"${stok.au.get.fullName} - calendar",
          "md",
          s"""
             |${stok.au.get.fullName}'s calendar.
             |
        |{{.DO NOT EDIT THIS PART below}}
        |..editMode Draft
        |{{.DO NOT EDIT THIS PART above}}
      """.stripMargin,
          stok.au.get._id,
          Seq("calendar"),
          stok.realm).copy(parent=Some(club._id), props=Map("wvis" -> Visibility.MODERATOR, "owner" -> stok.au.get.id))
      )

      val settings =
        Some(s"""
          |curYear=${Config.curYear}
          |regType=None
          |regAdmin=$email
          |
          |system.discipline $discipline
          |system.org $system
          |system.templates $system,effective
          |
          |# new users follow these (Follows.role=WPATH)
          |Follows.Contributor=$cat:$name/Forum:${name}_Buy_Sell
          |Follows.Fan=$cat:$name/Forum:${name}_News
          |Follows.Fan=$cat:$name/Calendar:${name}_Calendar
          |Follows.Fan=ski.Blog:carving-blog
          |
          |# Tasks assigned to new users - like registration etc
          |#Task.Reg=startRegistration,club:Demo_Ski_Club
          |
          |link.auto=$approve
          |link.notify.1=$email
          |admin.reg.1=$email
          |adminEmails=$email
        """.stripMargin)

      club.create
      UserWiki( stok.au.get._id, club.uwid, "Pro").create

      buy.map {we=>
        we.create
        UserWiki( stok.au.get._id, we.uwid, "Contributor").create
        WikiLink(we.uwid, club.uwid, "Child").create
      }

      forum.map {we=>
        we.create
        UserWiki( stok.au.get._id, we.uwid, "Fan").create
        WikiLink(we.uwid, club.uwid, "Child").create
      }

      calendar.map {we=>
        we.create
        UserWiki( stok.au.get._id, we.uwid, "Fan").create
        WikiLink(we.uwid, club.uwid, "Child").create
      }

      stok.au.map{user=>
        user.update(user.copy(clubSettings = settings))

        if (!user.quota.updates.exists(_ > 10))
          user.quota.reset(20)
      }

      Emailer.withSession(stok.realm) { implicit mailSession =>
        Emailer.tellAdmin("ACTIVATED_PRO", "user: "+stok.au.get.fullName)
      }
      cleanAuth()

      txn.commit

      Redirect(club.wid.urlRelative(stok.realm))
    } else
      Msg("Already activated", wid)
  }

  // pro activate account
  def invitePro(cat:String) = FAUR { implicit stok =>
    Forms.sForm(
      """
        |..label Invite a PRO
        |..xform.class form-horizontal
        |
        |You can invite a pro to join us...
        |
        |Name:
        |{{f:name:req=yes}}
        |
        |Email:
        |{{f:email:req=yes}}
        |
        |Add a quick paragraph for the invite:
        |{{f:desc:type=memo,rows=5,label="Description"}}
        |
      """.stripMargin,
      routes.Snow.invitePro1(cat).url
    )
  }

  def invitePro1(cat:String) = FAUR { implicit stok=>
      val name=stok.formParm("name")
      val email=stok.formParm("email")
      val desc=stok.formParm("desc")

      Emailer.withSession(stok.realm) { implicit mailSession =>
        Emailer.sendEmailInvitePro(stok.au.get, name, email, desc)
        Emailer.tellAdmin("INVITED_PRO", "user: "+stok.au.get.fullName)
      }

      Msg("Thank you - invite sent")
  }

  object ROLES {
    final val EVALUATION = "Evaluation"
    final val QUESTIONAIRE = "Questionnaire"
    final val MAREQ = "MA-Request"
    final val ASK = "Question"
    final val FEEDBACK = "Feedback"
    final val MESSAGE = "Message"
    final val REPLY = "Reply"
    final val INSIGHT = "Insight"
    final val PLAN = "Plan"
    final val GOAL = "Goal"
    final val NOTE = "Note"
    final val VIDEO = "Video"
  }

  /** either coach or me or parent */
  def canHistory(clubWid:Option[WID], rkid:String)(implicit request:RazRequest) = {
    request.au.isDefined &&
      (
      RacerKidz.findByIds(rkid).exists(_.usersToNotify.contains(request.au.get._id)) ||
      clubWid.flatMap(Club.apply).exists(_.isClubCoach(request.au.get))
      )
  }

  def doeFindCoaches(clubWid:CMDWID, rkid:String) = FAUR("find.team.coaches") { implicit request =>
    if(canHistory(clubWid.wid, rkid))
      Some(Ok(
        findCoaches(clubWid.wid, rkid).map(x=>
          x._1.wpath + "-------" + x._2.userId.flatMap(Users.findUserById).map(_.fullName)).mkString(",")
      ))
    else
      None
  }

  def findCoaches(clubWid:Option[WID], rkid:String) = {
    val club = clubWid.flatMap(Club.apply);
    val teamCoach = for(
      rk <- RacerKidz.findByIds(rkid).toList;
      c <- rk.clubs.filter(x=> club.isEmpty || club.get.uwid.id == x.uwid.id);
      t <- rk.teams(c, "");
      twid <- t.uwid.wid.toList;
      coach <- c.activeTeamMembers(twid).filter(x=>Club.isCoachRole(x._2.role))
    ) yield (twid, coach._1)
    teamCoach ++ (
      if(teamCoach.isEmpty)
        club.toList.flatMap(_.rka().filter(x=>Club.isCoachRole(x.role)).flatMap(rka=>
          rka.rk.toList.map(rrk=>
            (club.get.wid, rrk)
          )
        )
      )
      else Nil
      )
  }

  // note was added - now process it
  def doeAddNote(clubWid:CMDWID, noteid:String, role:String, rkid:String) = FAUR { implicit request =>
    val created = request.formParm("created") == "true"
    var memo = request.formParm("content")
    var toRkId : Option[ObjectId] = None

    if(canHistory(clubWid.wid, rkid)) RacerKidz.findByIds(rkid).foreach { rk =>
      val club = clubWid.wid.map(Club.apply)
      val id = new ObjectId(noteid)
      var hrole = role
      val weNote = Wikis(request.realm).findById("Note", id) // the note, if any

      if(created && weNote.isDefined)
        memo = weNote.map(_.content).getOrElse("")

      implicit val txn = tx.local("doeAddNote", request.userName)

      def link(we:WikiEntry) = "http://" + WikiReactors(request.realm).websiteProps.prop("domain").getOrElse(Config.hostport) + we.wid.urlRelative(request.realm)
      def linkH = "http://" + WikiReactors(request.realm).websiteProps.prop("domain").getOrElse(Config.hostport) + "/doe/history"

      if (role == ROLES.EVALUATION || role == ROLES.FEEDBACK || role == ROLES.VIDEO) {
        // created by coach, completed by coach, notify user WHEN COMPLETE
        weNote.map { we =>
          we.update(we.copy(
            tags = Seq("coaching") ++ we.tags,
            props = we.props ++ Map(
              "visibility" -> "ClubCoach",
              "wvis" -> "ClubCoach",
              "notifyUsers" -> rk.usersToNotify.mkString(","),
              "role" -> role)
          ))
        }

        // notify user of coache's notes
        weNote.map { we =>
          Emailer.withSession(request.realm) { implicit mailSession =>
            rk.personsToNotify.map { u =>
              Emailer.sendEmailNewNote(role, u, request.au.get, Wiki.w(we.wid), role,memo)
            }
          }
        }
      } else if (role == ROLES.QUESTIONAIRE) {
        // created by coach, notify user NOW, completed by user, notify coach
        weNote.map { we =>
          we.update(we.copy(
            tags = Seq("coaching") ++ we.tags,
            props = we.props ++
              Map("notifyUsers" -> request.au.get._id.toString,
                "role" -> role,
                "visibility" -> "ClubCoach",
                "wvis" -> "ClubCoach") ++
              (if (rk.usersToNotify.nonEmpty) Map(
                "owner" -> rk.usersToNotify.head.toString
              )
              else Map.empty[String, String])
          ))

          // notify users to complete questionaire
          Emailer.withSession(request.realm) { implicit mailSession =>
            rk.personsToNotify.map { u =>
              Emailer.sendEmailFormAssigned(
                u,
                request.au.get,
                /*link(we)*/linkH,
                role)
            }
          }
        }
      } else if (role == ROLES.MAREQ || role == ROLES.ASK) {
        // created by racer, completed by coach, notify user WHEN COMPLETE
        val coaches: List[ObjectId] = findCoaches(clubWid.wid, rkid).flatMap(_._2.userId).toList.take(1)

        weNote.map { we =>
          we.update(we.copy(
            tags = Seq("coaching") ++ we.tags,
            props = we.props ++ Map(
              "visibility" -> "ClubCoach",
              "wvis" -> "ClubCoach")
          ))

          // notify coaches
          Emailer.withSession(request.realm) { implicit mailSession =>
            coaches.flatMap(Users.findUserById(_).toList).filter(_.isActive).map { u =>
              val rk = RacerKidz.myself(u._id)
              toRkId = Some(rk._id)
              rk.history.add(
                RkHistory(
                  rk._id,
                  Some(request.au.get._id),
                  Some(id),
                  "Note",
                  role,
                  clubWid.wid.map(_.wpath).mkString
                ).copy(
                  toRkId = toRkId
                ))
              Emailer.sendEmailNewNote(role, u, request.au.get, /*link(we)*/linkH, role, memo)
            }
          }
        }
      } else if (role.startsWith(ROLES.REPLY)) {
        val to = role.substring(ROLES.REPLY.length)
        val toH = ROne[RkHistory]("_id" -> new ObjectId(to))
        val toUser = toH.flatMap(_.authorId)
        hrole = ROLES.MESSAGE

        // if sending from the other's history, then need to flip and save for myself
        // if sending from my history, nothing to do


        if (created) {
          weNote.map { we =>
            we.update(we.copy(
              tags = Seq("coaching") ++ we.tags,
              props = we.props ++ Map(
                "visibility" -> "ClubCoach",
                "wvis" -> "ClubCoach")
            ))

            // notify other
            Emailer.withSession(request.realm) { implicit mailSession =>
              toUser.flatMap(Users.findUserById).filter(_.isActive).map { u =>
                var respondTo = RacerKidz.myself(u._id)
                var tork = RacerKidz.myself(u._id)
                toRkId = Some(respondTo._id)

                if(tork._id == rk._id) tork=RacerKidz.myself(request.au.get._id) //was looking at the other's history, force this copy to mine
                tork.history.add(
                  RkHistory(
                    tork._id,
                    Some(request.au.get._id),
                    Some(id),
                    "Note",
                    hrole,
                    clubWid.wid.map(_.wpath).mkString
                  ).copy(
                    toRkId = toRkId
                  ))
                Emailer.sendEmailNewNote(ROLES.REPLY, u, request.au.get, /*link(we)*/linkH, hrole, memo)
              }
            }
          }
        } else {
          // notify other
          Emailer.withSession(request.realm) { implicit mailSession =>
            toUser.flatMap(Users.findUserById).filter(_.isActive).map { u =>
              var respondTo = RacerKidz.myself(u._id)
              var tork = RacerKidz.myself(u._id)
              toRkId = Some(respondTo._id)

              if(tork._id == rk._id) tork=RacerKidz.myself(request.au.get._id) //was looking at the other's history, force this copy to mine
              tork.history.add(
                RkHistory(
                  tork._id,
                  Some(request.au.get._id),
                  None,
                  "Note",
                  hrole,
                  clubWid.wid.map(_.wpath).mkString
                ).copy(
                  toRkId = toRkId,
                  content = Some(request.formParm("content")),
                  tags = Some(request.formParm("tags"))
                ))
              Emailer.sendEmailNewNote(ROLES.REPLY, u, request.au.get, linkH, hrole,memo)
            }
          }
        }
      } else if (role == ROLES.MESSAGE) {
          Emailer.withSession(request.realm) { implicit mailSession =>
            rk.personsToNotify.map { u =>
              Emailer.sendEmailNewNote(role, u, request.au.get,linkH, role,memo)
            }
          }
      }

      val shouldBadge = role != ROLES.INSIGHT

      if (created) {
        // add history for the kid
        rk.history.add(
          RkHistory(
            new ObjectId(rkid),
            Some(request.au.get._id),
            Some(id),
            "Note",
            hrole,
            clubWid.wid.map(_.wpath).mkString
          ).copy(
            toRkId = Some(rk._id)
          ), shouldBadge)
      } else {
        rk.history.add(
          RkHistory(
            rk._id,
            Some(request.au.get._id),
            None,
            "Note",
            hrole,
            clubWid.wid.map(_.wpath).mkString
          ).copy(
            toRkId = toRkId,
            content = Some(request.formParm("content")),
            tags = Some(request.formParm("tags"))
          ), shouldBadge)
      }
    }
    Ok("history created") // this is ajax
  }

  def doeDeletePosts() = FAUR { implicit stok=>
    var count = 0
    val history = RacerKidz.myself(stok.au.get._id).history
    val posts = history.findByRole("post").toList
    count += posts.size
    posts.foreach(p=> history.delete(p))

    Ok(s"deleted $count posts") // this is ajax
  }

  def doeAddPost(clubName:String, postid:String, role:String, rkid:String) = FAUR { implicit request =>
    val h = RkHistory(
      new ObjectId(rkid),
      Some(request.au.get._id),
      Some(new ObjectId(postid)),
      "WikiEntry",
      role,
      "Club:"+clubName
    )

    h.create(razie.db.tx.auto)

    Ok("history created") // this is ajax
  }

  def doeConnectBadge(club:WID) = FAUR { implicit request =>
    val site = if(club.cat == "Club" ) "www.dieselapps.com" else "www.snowproapp.com"
    val path = club.canonpath
    Ok(
      s"""
        |<style>
        |.xbutton {
        |   border-top: 1px solid #96d1f8;
        |   background: #65a9d7;
        |   background: -webkit-gradient(linear, left top, left bottom, from(#3e779d), to(#65a9d7));
        |   background: -webkit-linear-gradient(top, #3e779d, #65a9d7);
        |   background: -moz-linear-gradient(top, #3e779d, #65a9d7);
        |   background: -ms-linear-gradient(top, #3e779d, #65a9d7);
        |   background: -o-linear-gradient(top, #3e779d, #65a9d7);
        |   padding: 11px 22px;
        |   -webkit-border-radius: 8px;
        |   -moz-border-radius: 8px;
        |   border-radius: 8px;
        |   -webkit-box-shadow: rgba(0,0,0,1) 0 1px 0;
        |   -moz-box-shadow: rgba(0,0,0,1) 0 1px 0;
        |   box-shadow: rgba(0,0,0,1) 0 1px 0;
        |   text-shadow: rgba(0,0,0,.4) 0 1px 0;
        |   color: white;
        |   font-size: 14px;
        |   font-family: 'Lucida Grande', Helvetica, Arial, Sans-Serif;
        |   text-decoration: none;
        |   vertical-align: middle;
        |   }
        |.xbutton:hover {
        |   border-top-color: #28597a;
        |   background: #28597a;
        |   color: #ccc;
        |   }
        |.xbutton:active {
        |   border-top-color: #1b435e;
        |   background: #1b435e;
        |   }
        |</style>
        |<p><a href="http://$site/$path" class="xbutton">Connect on SnowProApp</a></p>
        |<p></p>
      """.stripMargin)
  }

  /** send a message to the team members */
  def doeSendMsgTeam(teamWpath:String, what:String) = FAUR { implicit request =>
    val memo = request.formParm("content")
    val tags = request.formParm("tags")
    val role = request.formParm("role")
    val kids = request.formParm("kids")
    val sendEmail = request.formParm("sendEmail") == "true"
    (for (
      team <- WID.fromPath(teamWpath) orErr "Team not found";
      c <- team.parentOf(WikiDomain(team.getRealm).isA("Club", _)).flatMap(Club.apply) orErr "Club not found";
      ism <- c.isMember(request.au.get) orCorr cNotMember(c.name);
      isc <- c.isClubCoach(request.au.get) orCorr cNotMember(c.name, "coach")
    ) yield {
      var size = 0

      Emailer.withSession(request.realm) { implicit mailSession =>
      size = kids.split(",").toList.flatMap(RacerKidz.findByIds).map{kid=>
        val h = RkHistory(
          kid._id,
          Some(request.au.get._id),
          None,
          "Memo",
          role,
          "coaching",
          None,
          None,
          Some(memo),
          if(tags != "") Some(tags) else None
          )

        h.createNoAudit(razie.db.tx.auto)

        def linkH = "http://" + WikiReactors(request.realm).websiteProps.prop("domain").getOrElse(Config.hostport) + "/doe/history"
        def linkP = "http://" + WikiReactors(request.realm).websiteProps.prop("domain").getOrElse(Config.hostport) + "/doe/kid/history/" + kid._id.toString + "?club="

        if(sendEmail) {
          kid.personsToNotify.map { u =>
            Emailer.sendEmailNewNote(ROLES.MESSAGE, u, request.au.get,linkP, ROLES.MESSAGE, memo)
          }
        }
      }.size
    }

      Audit.logdb("ENTITY_CREATE", size + " RkHistory entries")
      Ok("history created") // this is ajax

//      Ok(c.activeTeamMembers(team).mkString)
    }) getOrElse unauthorizedPOST()
  }

}

