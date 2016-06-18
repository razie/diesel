package mod.snow

import admin.Config
import akka.actor.{Actor, Props}
import controllers.{Club, RazController}
import model._
import org.bson.types.ObjectId
import razie.wiki.{Dec, WikiConfig, EncUrl, Enc}
import razie.wiki.model.{Reactors, Wikis, WID, FormStatus}
import razie.{clog, Logging, cout}
import scala.Option.option2Iterable

import scala.concurrent.Future

case class RoleWid(role: String, wid: WID)

/** controller for club management */
object Snow extends RazController with Logging {

  def addUser(club:String, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Ok("")
  }

  def invite(clubName:String, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val club = Club(clubName).get
    Redirect(controllers.routes.Kidz.doeKid(club.userId.toString, "11", role, "-", "invite:"+club.name))
  }

  def manage(club:String, role:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Redirect(controllers.routes.Club.doeClubKids(club, role))
  }

  // list of clubs and teams with links
  def cteams = FAU { implicit au => implicit errCollector => implicit request =>
    var msg =
        Some(au.myself).map { rk =>
        rk.clubs.distinct.map { club =>
          club.wid.ahrefNice + rk.teams(club,"").map{ team=>
            team.uwid.findWid.map(_.ahrefNice).getOrElse(
              s"""<span title="Team assoc with no name ${team._id.toString}">?</span>"""
            )
          }.mkString(" ( ", "|", " ) ")
        } mkString " | "
      } mkString

    if(msg.trim.isEmpty) msg = """<small>[Need to <a href="/wikie/like/Club">join</a> a club OR read more <a href="/wiki/Admin:Hosted_Services_for_Ski_Clubs">about this website</a>] </small>"""

    Ok( msg )
  }

  def doeAddNote(clubName:String, noteid:String, role:String, rkid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val club = Club(clubName).get

    RacerKidz.findByIds(rkid).foreach { rk =>
      rk.history.add(
      RkHistory(
        new ObjectId(rkid),
        Some(au._id),
        new ObjectId(noteid),
        "Note",
        role,
        "Club:" + clubName
      ))
    }

    Ok("history created") // this is ajax
  }

  def doeAddPost(clubName:String, postid:String, role:String, rkid:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val h = RkHistory(
      new ObjectId(rkid),
      Some(au._id),
      new ObjectId(postid),
      "WikiEntry",
      role,
      "Club:"+clubName
    )

    h.create

    Ok("history created") // this is ajax
  }

}

