package controllers

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

/** profile related control */
object UserStuff extends RazController {

  // serve public profile
  def pub(id: String) =
    if (Wikis.withIndex(_.get2(id, "User").isDefined))
      Wiki.show ("User", id)
    else
      Action { implicit request => NotFound ("User not found or profile is private!") }

  def task2(name: String) = Action { implicit request =>
    auth match {
      case su @ Some(u) =>
        name match {
          case _ =>
            Oops ("I don't know yet how to " + name + " !!!", "Page", "home")
        }
      case None =>
        Oops ("Not your task?", "Page", "home")
    }
  }

  def task(id: String, name: String) = task2 (name)

  def show(email: String, what: String, name: String) = {
    { Audit.missingPage(email + what + name); TODO }
  }

  def wiki(email: String, cat: String, name: String) =
    Wiki.show ("WikiLink", WikiLink(WID("User", email), WID(cat, name), "").wname)

  //(what,when)
  def events(u: User): List[(ILink, String, DateTime)] = {
    val dates = u.pages("Season").flatMap{ uw =>
      val node = new WikiWrapper("Season", uw.name)
      val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

      val races = (root \ "*" \ "Race")
      //      val gigi = (root \ "*" \ "Race" \@ "date")
      val dates = races.map(x => (x.mkLink, new Snakk.Wrapper(x, races.ctx) \@ "date")).filter(_._2 != "")
      //      val dates2 = dates.filter(s => DateParser.apply(s._2).successful)
      // filter those that parse successfuly
      dates.map(x => (x._1, x._2, DateParser.apply(x._2))).filter(_._3.successful).map(t => (t._1, t._2, t._3.get))
      //      dates2.map(x => (x._1, x._2, DateParser.apply(x._2).get))
    }
    dates.sortWith((a, b) => a._3 isBefore b._3)
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
