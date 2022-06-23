package nofolders

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.razie.pub.comms.CommRtException
import razie.Snakk
import razie.UrlTester
import razie.SnakkUrl
import razie.cout
import scala.collection.mutable.ListBuffer
import razie.Perf
import org.bson.types.ObjectId
import localhost.{ MT, RkTester }
import controllers.T

/** test the basic test utils */
class TestNofoPerf extends FlatSpec with ShouldMatchers with NofoTester {
  /** test the basic test utils */
  implicit val hostport = "http://localhost:9000"
  //  implicit val hostport = "http://test.razie.com"
  val (joe, pjoe) = (Services.config.prop("wiki.testuserjoe"), Services.config.prop("wiki.testuserjoepass")))

  import T._

  def milis = "nomillis" //System.currentTimeMillis.toString

 val TAGS = "tag1,tag2,tag3".split(",")

  val (szClubs, szUsers, szNotes) = (1, 500, 1000) // test size
  val THREADS=20

  var (noClubs, noUsers, noNotes) = (0, 0, 0) // these are the RESULTS COLLECTORS

  ("/razadmin/config/noemailstesting", joe, pjoe) eok "true" // can't send emails in perf testing

  /////////////////////////////////////////////////
  val clubs = doClubs(milis, szClubs, szUsers, false, THREADS)
  cout << "Clubs: " + clubs.mkString
  /////////////////////////////////////////////////

  //  Perf.perf(Seq(1, 5, 10), 10) { (t, l) =>
  //    val clubs = doClubs(s"$milis$t$l", 1, 10, false, 1)
  //  }

  ("/razadmin/config/okemailstesting", joe, pjoe) eok "false" // can't send emails in perf testing

  cout << s"$noClubs clubs $noUsers users"
  cout << "------------DONE"

  /** create clubs with users and registrations */
  def doClubs(testId: String, clubs: Int, members: Int, doReg: Boolean, threads: Int) = {
    val c = (for (i <- 0 until clubs) yield {
      doClub(members, testId.toString + "x" + i.toString, doReg, threads)
    }).toList

    cout << "Clubs: " + c.mkString
    c
  }

  /** create a club with members and share notes etc */
  def doClub(members: Int, clubEname: String, doReg: Boolean, threads: Int) = {

    val users = MT.slicejoin(threads, (0 until members).toList) { i =>
      val (u, userId) = crUser(clubEname + "x" + i.toString + "user", "Member")
      (u, userId)
    }.flatMap(_.toList).toList

    noUsers = noUsers + users.size
    noClubs = noClubs + 1

    // link users
    val notes = MT.slicejoin(threads, users) { t =>
      val (u, userId) = t

      val n = 0 until szNotes map { kk =>
        val note = crNote(s"note-kk-$kk", TAGS((Math.random()*TAGS.size).toInt), u)
        (note, u, kk)
      }

      n.toList
    }.flatMap(_.toList).flatMap(identity)

    notes map { t =>
      val (note, u, kk) = t
      (s"/notes/id/$note", u, p) sok s"note-kk-$kk"
    }

    noNotes = noNotes + notes.size

    cout << users.mkString << s"   $notes notes"
  }

}

object RunTestNofosPerf extends App {
  org.scalatest.tools.Runner.run("-s localhost.TestClubs".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestUserKid".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
