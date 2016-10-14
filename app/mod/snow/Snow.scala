package mod.snow

import admin.Config
import akka.actor.{Actor, Props}
import controllers._
import model._
import org.bson.types.ObjectId
import razie.wiki.dom.WikiDomain
import razie.wiki.{Dec, WikiConfig, EncUrl, Enc}
import razie.wiki.model._
import razie.{clog, Logging, cout}
import scala.Option.option2Iterable

import scala.concurrent.Future

case class RoleWid(role: String, wid: WID)

/** controller for club management */
object Snow extends RazController with Logging {

  def invite(wid:WID, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val club = Club(wid).get
    Redirect(controllers.routes.Kidz.doeUserKid(club.userId.toString, "11", role, "-", "invite:"+club.wid.wpath))
  }

  def manage(club:WID, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Redirect(controllers.routes.Club.doeClubKidz(club, role))
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

  def activate1(cat:String) = FAUR { implicit stok=>
    val wid = WID(cat, stok.au.get.userName).r(stok.realm)
    if(!wid.page.isDefined) {
      val wid = WID(cat, stok.au.get.userName).r(stok.realm)
      val name = wid.name
      val email = Dec(stok.au.get.email)

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

      val iphoto = if(photo.trim.length > 0) s"""{{photo $photo"}}""" else ""
      val icerts = if(certs.trim.length > 0) certs else "Certifications"
      val idesc  = if(desc.trim.length > 0) desc else s"${stok.au.get.fullName}'s page."
      val ivideo  = if(video.trim.length > 0) s"""{{video $video}}""" else ""

      val firstPara =
        s"""
          |<div class="row">
          |
          |<div class="col-sm-4">
          |$iphoto
          |</div>
          |
          |<div class="col-sm-8">
          |$icerts
          |</div>
          |</div>
          |
          |$idesc
          |<br>
          |$ivideo
          |
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
        |<div id="admin"></div>
        |{{later admin /doe/club/adminpanel/${wid.wpath}}}
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
      stok.realm).copy(props=Map("wvis" -> Visibility.MODERATOR, "owner" -> stok.au.get.id))

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
          |system.goal $system
          |system.plan $system
          |system.eval $system
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
      UserWiki( stok.au.get._id, club.uwid, "Owner").create

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

      stok.au.get.update(stok.au.get.copy(clubSettings = settings))

      Emailer.withSession { implicit mailSession =>
        Emailer.tellRaz("ACTIVATED_PRO", "user: "+stok.au.get.fullName)
      }
      cleanAuth()
      Redirect(club.wid.url)
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

      Emailer.withSession { implicit mailSession =>
        Emailer.sendEmailInvitePro(stok.au.get, name, email, desc)
        Emailer.tellRaz("INVITED_PRO", "user: "+stok.au.get.fullName)
      }

      Msg("Thank you - invite sent")
  }

  def doeAddNote(clubName:String, noteid:String, role:String, rkid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val club = Club(clubName).get

    RacerKidz.findByIds(rkid).foreach { rk =>
      rk.history.add(
        RkHistory(
          new ObjectId(rkid),
          Some(au._id),
          Some(new ObjectId(noteid)),
          "Note",
          role,
          "Club:" + clubName
        ))
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

  def doeAddPost(clubName:String, postid:String, role:String, rkid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val h = RkHistory(
      new ObjectId(rkid),
      Some(au._id),
      Some(new ObjectId(postid)),
      "WikiEntry",
      role,
      "Club:"+clubName
    )

    h.create

    Ok("history created") // this is ajax
  }

  def doeConnectBadge(club:WID) = FAUR { implicit request =>
    val site = if(club.cat == "Club" ) "www.racerkidz.com" else "www.snowproapp.com"
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
        |<p><a href="http://$site/wiki/${club.wpathnocats}" class="xbutton">Connect on SnowProApp</a></p>
        |<p></p>
      """.stripMargin)
  }

  /** send a message to the team members */
  def doeSendMsgTeam(teamWpath:String, what:String) = FAUR { implicit stok =>
    (for (
      team <- WID.fromPath(teamWpath) orErr "Team not found";
      c <- team.parentOf(WikiDomain(team.getRealm).isA("Club", _)).map(_.name).flatMap(Club.apply) orErr "Club not found";
      ism <- c.isMember(stok.au.get) orCorr cNotMember(c.name);
      isc <- c.isClubCoach(stok.au.get) orCorr cNotMember(c.name)
    ) yield {
      c.activeTeamMembers(team).map{kid=>
        val memo = stok.formParm("content")
        val h = RkHistory(
          kid._1._id,
          Some(stok.au.get._id),
          None,
          "memo",
          "memo",
          memo
          )
        h.create
        }
      Ok("history created") // this is ajax

      Ok(c.activeTeamMembers(team).mkString)
    }) getOrElse unauthorizedPOST()
  }

}

