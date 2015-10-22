package controllers

import razie.RString._
import model._
import play.api.data.Forms._
import play.api.mvc._
import play.api._
import razie.Snakk
import scala.util.parsing.combinator.RegexParsers
import org.joda.time.DateTime
import razie.XP
import razie.XpSolver
import razie.Snakk._
import org.bson.types.ObjectId
import scala.Some
import razie.wiki.model.WWrapper
import razie.wiki.model.UWID
import razie.wiki.model.WikiXpSolver
import razie.wiki.model.Wikis
import razie.wiki.model.WikiWrapper
import razie.wiki.parser.ParserSettings
import razie.wiki.model.ILink
import razie.wiki.model.WikiLink
import razie.wiki.model.WID

class UserStuff (val realm:String, val user:User) {
  lazy val events = UserStuff.events(realm, user)
  private lazy val alocs = events flatMap (_._5 \ "Venue" \@ "loc")
  lazy val locs = alocs.filter (! _.isEmpty).map(_.replaceFirst("ll:",""))
    //xp(user, "Calendar") \ UserStuff.Race \ "Venue" \@ "loc"}.filter(! _.isEmpty).map(_.replaceFirst("ll:",""))

  def comingUp = {
    events.filter(_._3.isAfter(DateTime.now))
  }

  def pastEvents = {
    events.filter(_._3.isAfter(DateTime.now.minusDays(20)))
  }

}

/** profile related control */
object UserStuff extends RazController {

  // serve public profile
  def pub(id: String) =
    if (Wikis.find(WID("User", id)).isDefined)
      Wiki.show (WID("User", id))
    else
      Action { implicit request => Msg2 ("This user does not have a public profile!") }

  def wiki(id: String, cat: String, name: String) =
    WikiLink(UWID("User", new ObjectId(id)), WID(cat, name).uwid.get, "").page.map(w =>
        Wiki.show (WID("WikiLink", w.name))
      ).getOrElse(
        Action { implicit request => Redirect (Wiki.w (cat, name, Website.realm)) }
      )

  def Race = admin.Config.sitecfg("racecat").getOrElse("Race")

  /** user / Calendar / Race / Venue
   * @return (what,when)
   */
  def events(realm:String, u: User): List[(ILink, String, DateTime, ILink, Snakk.Wrapper[WWrapper])] = {
    val dates = u.pages(realm, "Calendar").flatMap{ uw =>
      val node = new WikiWrapper(WID("Calendar", uw.uwid.nameOrId).r(realm))
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      // TODO optimize this - lots of lookups...
      val races = (root \ "*" \ Race) ++ (root \ "*" \ "Event") ++ (root \ "*" \ "Training")
      val dates = races.map { race =>
        val wr = new Snakk.Wrapper(race, races.ctx)
        (race.mkLink,
        wr \@ "date",
        new ILink(WID("Venue", wr \@ "venue")),
        wr
        )
      }.filter(_._2 != "")
      // filter those that parse successfuly
      dates.map(x => (x._1, x._2, DateParser.apply(x._2), x._3, x._4)).filter(_._3.successful).map(t => (t._1, t._2, t._3.get, t._4, t._5)
          )
    }
    dates.sortWith((a, b) => a._3 isBefore b._3)
  }

  def xp(realm:String, u: User, cat: String) = {
    new XListWrapper(
      u.pages(realm, cat).map { uw => new WikiWrapper(WID(cat, uw.uwid.nameOrId).r(realm)) },
      WikiXpSolver)
  }

  // serve public profile
  def doeUserCreateSomething = Action { implicit request =>
    Ok (views.html.user.doeUserCreateSomething(auth))
    }

  def fragTasks (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Ok (views.html.user.uFragTasks(au, quiet == "true", new controllers.UserStuff(Website.getRealm, au)))
  }
  def fragEvents (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Ok (views.html.user.uFragEvents(au, quiet == "true", new controllers.UserStuff(Website.getRealm, au)))
  }
  def fragBlogs (quiet:String) = FAU { implicit au => implicit errCollector => implicit request =>
    Ok (views.html.user.uFragBlogs(au, quiet == "true", new controllers.UserStuff(Website.getRealm, au)))
  }
}

/** parse dates into joda.DateTime */
object DateParser extends RegexParsers {
  override def skipWhitespace = false

  type PS = Parser[DateTime]
  def apply(input: String) = parseAll(dates, input)

  import ParserSettings.{mth1, mth2}

  def dates = date1 | date2
  def date1 = """\d\d\d\d""".r ~ "-" ~ """\d\d""".r ~ "-" ~ """\d\d""".r ^^ { case y ~ _ ~ m ~ _ ~ d => new DateTime().withYear(y.toInt).withMonthOfYear(moy(m)).withDayOfMonth(d.toInt) }
  def date2 = (mth2 + "|" + mth1).r ~ " *".r ~ """\d[\d]?""".r ~ "[ ,-]*".r ~ """\d\d\d\d""".r ^^ { case m ~ _ ~ d ~ _ ~ y => new DateTime().withYear(y.toInt).withMonthOfYear(moy(m)).withDayOfMonth(d.toInt) }

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
class XListWrapper[T](nodes: List[T], ctx: XpSolver[T]) extends ListWrapper[T](nodes, ctx) {
  /** factory method - overwrite with yours*/
  override def wrapList(nodes: List[T], ctx: XpSolver[T]) = new XListWrapper(nodes, ctx)
  override def wrapNode(node: T, ctx: XpSolver[T]) = new XWrapper(node, ctx)

  /** the attributes with the respective names */
  def \@-(n: (String, String)): List[(String, String)] = (this \@ n._1) zip (this \@ n._2)
  def \@-(n: (String, String, String)): List[(String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3)).map (x => (x._1._1, x._1._2, x._2))
  def \@-(n: (String, String, String, String)): List[(String, String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3) zip (this \@ n._4)).map (x => (x._1._1._1, x._1._1._2, x._1._2, x._2))
}

/** OO wrapper for self-solving XP elements */
class XWrapper[T](node: T, ctx: XpSolver[T]) extends Wrapper(node, ctx) {
  /** factory method - overwrite with yours*/
  override def wrapList(nodes: List[T], ctx: XpSolver[T]) = new XListWrapper(nodes, ctx)
  override def wrapNode(node: T, ctx: XpSolver[T]) = new XWrapper(node, ctx)

  /** the attributes with the respective names */
  def \@-(n: (String, String)): (String, String) = (this \@ n._1, this \@ n._2)
  def \@-(n: (String, String, String)): (String, String, String) = (this \@ n._1, this \@ n._2, this \@ n._3)
  def \@-(n: (String, String, String, String)): (String, String, String, String) = (this \@ n._1, this \@ n._2, this \@ n._3, this \@ n._4)
}

object Maps extends razie.Logging {

  def latlong(addr: String): Option[(String, String)] = {
    try {
      val resp = Snakk.json (
        Snakk.url(
          "http://maps.googleapis.com/maps/api/geocode/json?address=" + addr.toUrl + "&sensor=false",
          Map.empty,
          //        Map("privatekey" -> "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip" -> "kk", "challenge" -> challenge, "response" -> response),
          "GET"))

      Some((
        resp \ "results" \ "geometry" \ "location" \@@ "lat",
        resp \ "results" \ "geometry" \ "location" \@@ "lng"))
    } catch {
      case e @ (_ :Throwable) => {
        error ("ERR_COMMS can't geocode address", e)
        None
      }
    }
  }
}

object TMRKK extends App {
  val addr = "3325 Cochrane Rd N, Cramahe"

  println(Maps.latlong(addr))
}
