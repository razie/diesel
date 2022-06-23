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

trait NofoTester extends RkTester { self: FlatSpec with ShouldMatchers =>
  /** create a note */
  def crNote(content: String, tags: String, u: String)(implicit hostport: String) = {
    val id = new ObjectId().toString

    val form = Map(
      "next" -> "",
      "id" -> id,
      "ver" -> "1",
      "content" -> content,
      "tags" -> tags)

    s"/notes/save".url.basic(u, p).form(form).wget // first create, no check

    cout << s"Note created: $id"
    id
  }
}

/** test the basic test utils */
class TestNofo extends FlatSpec with ShouldMatchers with NofoTester {
  implicit val hostport = "http://localhost:9000"
  //  implicit val hostport = "http://test.razie.com"
  val (joe, pjoe) = (Services.config.prop("wiki.testuserjoe"), Services.config.prop("wiki.testuserjoepass")))

  def milis = System.currentTimeMillis.toString

  var (noClubs, noUsers, noKidz) = (0, 0, 0)

  ("/razadmin/config/noemailstesting", joe, pjoe) eok "true" // can't send emails in perf testing

  val (u1, p1) = (crUser("nofo1a", "Individual")._1, p)
  val (u2, p2) = (crUser("nofo2a", "Individual")._1, p)

  val n11 = crNote("note-1-1", "", u1)
  val n12 = crNote("note-1-2", "", u2)

//  "private topics" should "not be visible" in {
    (s"/notes/id/$n11", u1, p1) sok "note-1-1"
    (s"/notes/view/id/$n11", u1, p1) sok "note-1-1"
    (s"/notes/id/$n12", u1, p1) snok "note-1-2"
    (s"/notes/view/id/$n12", u1, p1) snok "note-1-2"
//  }

//  "basic tags " should "work" in {
    (s"/notes/tag/recent", u1, p1) sok "note-1-1"
    (s"/notes/tag/none", u1, p1) sok "note-1-1"
    (s"/notes/tag/recent", u2, p2) snok "note-1-1"
    (s"/notes/tag/none", u2, p2) snok "note-1-1"
//  }
}

object RunTestNofos extends App {
  org.scalatest.tools.Runner.run("-s nofolders.TestNofo".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestUserKid".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
