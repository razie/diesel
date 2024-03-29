package controllers

import com.google.inject.{Inject, Singleton}
import controllers.UserStuff.findPublicProfile
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.Configuration
import play.api.mvc._
import razie.Snakk._
import razie.hosting.Website
import razie.tconf.parser.SpecParserSettings
import razie.wiki.{Config, Services}
import razie.wiki.model._
import razie.wiki.util.Maps
import razie.{ListWrapper, Snakk, XpSolver, XpWrapper}
import scala.util.parsing.combinator.RegexParsers

/** stuff related to users: events, locations etc */
class UserStuff(val realm: String, val user: User) {
  lazy val events = UserStuff.events(realm, user)
  private lazy val alocs = events flatMap (_._5 \ "Venue" \@ "loc")

  lazy val locs = alocs.filter(!_.isEmpty).map(_.replaceFirst("ll:", ""))
  //xp(user, "Calendar") \ UserStuff.Race \ "Venue" \@ "loc"}.filter(! _.isEmpty).map(_.replaceFirst("ll:",""))

  def comingUp = {
    events.filter(_._3.isAfter(DateTime.now))
  }

  def pastEvents = {
    events.filter(_._3.isAfter(DateTime.now.minusDays(20)))
  }

}

/** utilities for user's stuff */
object UserStuff {

  def findPublicProfile(realm: String, uid: String) =
    Wikis.find(WID("User", realm + "-" + uid)) orElse Wikis.find(WID("User", uid))

  def Race = Services.config.sitecfg("racecat").getOrElse("Race")

  def events(u: User): List[(ILink, String, DateTime, ILink, XpWrapper[WWrapper])] =
    events("*", u)

  /** user / Calendar / Race / Venue
    *
    * @return (what,when)
    */
  def events(realm: String, u: User): List[(ILink, String, DateTime, ILink, XpWrapper[WWrapper])] = {
    val dates = u.pages(realm, "Calendar").flatMap { uw =>
      val node = new WikiWrapper(uw.uwid.wid.get)
      val root = new razie.XpWrapper(node, WikiXpSolver)

      // TODO optimize this - lots of lookups...
      val races = (root \ "*" \ Race) ++ (root \ "*" \ "Event") ++ (root \ "*" \ "Training")
      val dates = races.map { race =>
        val wr = new XpWrapper(race, races.ctx)
        (race.mkLink,
            wr \@ "date",
            new ILink(WID("Venue", wr \@ "venue")),
            wr
        )
      }.filter(_._2 != "")
      // filter those that parse successfuly
      dates.map(x => (x._1, x._2, DateParser.apply(x._2), x._3, x._4)).filter(_._3.successful).map(
        t => (t._1, t._2, t._3.get, t._4, t._5)
      )
    }
    dates.sortWith((a, b) => a._3 isBefore b._3)
  }

  def xp(realm: String, u: User, cat: String) = {
    new XListWrapper(
      u.pages(realm, cat).map { uw => new WikiWrapper(WID(cat, uw.uwid.nameOrId).r(realm)) },
      WikiXpSolver)
  }
}

/** profile related control */
@Singleton
class UserStuffCtl @Inject()(wikiCtl: Wiki) extends RazController {

  // serve public profile
  def pub(id: String) = RAction { implicit stok =>
    val user = Users.findUserByUsername(id)
    val w = user.flatMap(u => findPublicProfile(stok.realm, u.userName))

    w.flatMap(_.alias).map { wid =>
      Redirect(WikiUtil.wr(wid, stok.realm))
    } orElse user.map { u =>
      ROK.r apply { implicit stok =>
        views.html.user.userInfo(u)
      }
    } getOrElse {
      Msg(s"No public profile for user $id")
    }
  }

  def wiki(id: String, cat: String, name: String) =
    WikiLink(UWID("User", new ObjectId(id)), WID(cat, name).uwid.get, "").page.map(w =>
      wikiCtl.show(WID("WikiLink", w.name))
      ).getOrElse(
      Action { implicit request => Redirect(WikiUtil.w(cat, name, Website.realm)) }
      )

  // serve public profile
  def doeUserCreateSomething = Action { implicit request =>
    ROK.r noLayout {implicit stok=>views.html.user.doeUserCreateSomething()}
    }

  def fragTasks (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    ROK.s noLayout { implicit stok => views.html.user.uFragTasks(au, quiet, new UserStuff(Website.getRealm, au)) }
  }
  def fragEvents (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    ROK.s noLayout { implicit stok => views.html.user.uFragEvents(au, quiet == "true", new UserStuff(Website.getRealm, au)) }
  }
  def fragBlogs (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    ROK.s noLayout { implicit stok => views.html.user.uFragBlogs(au, quiet == "true", new UserStuff(Website.getRealm, au)) }
  }
}

/** parse dates into joda.DateTime */
object DateParser extends RegexParsers {
  override def skipWhitespace = false

  type PS = Parser[DateTime]
  def apply(input: String) = parseAll(dates, input)

  import SpecParserSettings.{mth1, mth2}

  def dates = date1 | date2
  def date1 = """\d\d\d\d""".r ~ "-" ~ """\d\d""".r ~ "-" ~ """\d\d""".r ^^ {
    case y ~ _ ~ m ~ _ ~ d =>
      new DateTime().withYear(y.toInt).withMonthOfYear(m.toInt).withDayOfMonth(d.toInt)
  }
  def date2 = (mth2 + "|" + mth1).r ~ " *".r ~ """\d[\d]?""".r ~ "[ ,-]*".r ~ """\d\d\d\d""".r ^^ {
    case m ~ _ ~ d ~ _ ~ y =>
      new DateTime().withYear(y.toInt).withMonthOfYear(moy(m)).withDayOfMonth(d.toInt)
  }

  val moy = Map(
    "Jan" -> 1, "Feb" -> 2, "Mar" -> 3, "Apr" -> 4, "May" -> 5, "Jun" -> 6, "Jul" -> 7, "Aug" -> 8, "Sep" -> 9, "Sept" -> 9, "Oct" -> 10, "Nov" -> 11, "Dec" -> 12,
    "January" -> 1, "February" -> 2, "March" -> 3, "April" -> 4, "May" -> 5, "June" -> 6, "July" -> 7,
    "August" -> 8, "September" -> 9, "October" -> 10, "November" -> 11, "December" -> 12)

}

object DateParserTA extends App {
  //  def main (argv:Array[String]) {
  val u = Users.findUserByUsername("Razie")
  println(new UserStuff(Wikis.RK, u.get).pastEvents.mkString("\n"))
  //  }
}

/** OO wrapper for self-solving XP elements HEY this is like an open monad :) */
class XListWrapper[T](nodes: List[T], ctx: XpSolver[T]) extends razie.ListWrapper[T](nodes, ctx) {
  /** factory method - overwrite with yours*/
  override def wrapList(nodes: List[T], ctx: XpSolver[T]) = new XListWrapper(nodes, ctx)
  override def wrapNode(node: T, ctx: XpSolver[T]) = new XWrapper(node, ctx)

  /** the attributes with the respective names */
  def \@-(n: (String, String)): List[(String, String)] = (this \@ n._1) zip (this \@ n._2)
  def \@-(n: (String, String, String)): List[(String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3)).map (x => (x._1._1, x._1._2, x._2))
  def \@-(n: (String, String, String, String)): List[(String, String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3) zip (this \@ n._4)).map (x => (x._1._1._1, x._1._1._2, x._1._2, x._2))
}

/** OO wrapper for self-solving XP elements */
class XWrapper[T](node: T, ctx: XpSolver[T]) extends razie.XpWrapper(node, ctx) {
  /** factory method - overwrite with yours*/
  override def wrapList(nodes: List[T], ctx: XpSolver[T]) = new XListWrapper(nodes, ctx)
  override def wrapNode(node: T, ctx: XpSolver[T]) = new XWrapper(node, ctx)

  /** the attributes with the respective names */
  def \@-(n: (String, String)): (String, String) = (this \@ n._1, this \@ n._2)
  def \@-(n: (String, String, String)): (String, String, String) = (this \@ n._1, this \@ n._2, this \@ n._3)
  def \@-(n: (String, String, String, String)): (String, String, String, String) = (this \@ n._1, this \@ n._2, this \@ n._3, this \@ n._4)
}

object TMRKK extends App {
  val addr = "3325 Cochrane Rd N, Cramahe"

  println(Maps.latlong(addr))
}
