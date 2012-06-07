package controllers

import razie.RString._
import admin.Audit
import model.User
import model.WID
import model.WikiLink
import model.WikiWrapper
import play.api.data.Forms._
import play.api.mvc._
import play.api._
import model.WikiXpSolver
import razie.Snakk
import model.ILink
import scala.util.parsing.combinator.RegexParsers
import model.WikiParser
import org.joda.time.DateTime
import model.Wikis
import model.Users
import razie.XP
import razie.XpSolver
import razie.Snakk._

/** profile related control */
object UserStuff extends RazController {

  // serve public profile
  def pub(id: String) =
    if (Wikis.withIndex(_.get2(id, "User").isDefined))
      Wiki.show ("User", id)
    else
      Action { implicit request => NotFound ("User not found or profile is private!") }

  def wiki(email: String, cat: String, name: String) =
    Wiki.show ("WikiLink", WikiLink(WID("User", email), WID(cat, name), "").wname)

  //(what,when)
  def events(u: User): List[(ILink, String, DateTime, ILink)] = {
    val dates = u.pages("Season").flatMap{ uw =>
      val node = new WikiWrapper("Season", uw.name)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      val races = (root \ "*" \ "Race")
      //      val gigi = (root \ "*" \ "Race" \@ "date")
      val dates = races.map(x => (x.mkLink,
        new Snakk.Wrapper(x, races.ctx) \@ "date",
        ILink("Venue", new Snakk.Wrapper(x, races.ctx) \@ "venue"))).filter(_._2 != "")
      //      val dates2 = dates.filter(s => DateParser.apply(s._2).successful)
      // filter those that parse successfuly
      dates.map(x => (x._1, x._2, DateParser.apply(x._2), x._3)).filter(_._3.successful).map(t => (t._1, t._2, t._3.get, t._4))
      //      dates2.map(x => (x._1, x._2, DateParser.apply(x._2).get))
    }
    dates.sortWith((a, b) => a._3 isBefore b._3)
  }

  def xp(u: User, cat: String) = {
    new XListWrapper(
      u.pages(cat).map { uw => new WikiWrapper(cat, uw.name) },
      WikiXpSolver)
  }

  def comingUp(u: User) = {
    events(u).filter(_._3.isAfter(DateTime.now))
  }
  def pastEvents(u: User) = {
    events(u).filter(_._3.isAfter(DateTime.now.minusDays(20)))
  }
}

/** parse dates into joda.DateTime */
object DateParser extends RegexParsers {
  override def skipWhitespace = false

  type PS = Parser[DateTime]
  def apply(input: String) = parseAll(dates, input)

  val mth1 = WikiParser.mth1
  val mth2 = WikiParser.mth2

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
  println(UserStuff.pastEvents(u.get).mkString("\n"))
  //  }
}

/** OO wrapper for self-solving XP elements HEY this is like an open monad :) */
class XListWrapper[T](nodes: List[T], ctx: XpSolver[T]) extends ListWrapper[T](nodes, ctx) {
  /** factory method - overwrite with yours*/
  override def wrapList(nodes: List[T], ctx: XpSolver[T]) = new XListWrapper(nodes, ctx)
  override def wrapNode(node: T, ctx: XpSolver[T]) = new XWrapper(node, ctx)

  /** the attributes with the respective names */
  def \@-(n: (String, String)): List[(String, String)] = (this \@ n._1) zip (this \@ n._2)
  def \@-(n: (String, String, String)): List[(String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3)).map (x=>(x._1._1, x._1._2, x._2))
  def \@-(n: (String, String, String, String)): List[(String, String, String, String)] = ((this \@ n._1) zip (this \@ n._2) zip (this \@ n._3)  zip (this \@ n._4)).map (x=>(x._1._1._1, x._1._1._2, x._1._2, x._2))
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
          razie.AA(),
          //        razie.AA("privatekey", "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip", "kk", "challenge", challenge, "response", response),
          "GET"))

      Some((
        resp \ "results" \ "geometry" \ "location" \@@ "lat",
        resp \ "results" \ "geometry" \ "location" \@@ "lng"))
    } catch {
      case e @ _ => {
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
