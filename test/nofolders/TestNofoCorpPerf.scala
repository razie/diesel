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
import localhost.{ MT, RkTester}
import controllers.T

  /** test the basic test utils */
class TestNofoCorpPerf extends FlatSpec with ShouldMatchers with NofoTester {
  /** test the basic test utils */
  implicit val hostport = "http://localhost:9000"
//  implicit val hostport = "http://test.razie.com"
  val (joe, pjoe) = (Services.config.prop("wiki.testuserjoe"), Services.config.prop("wiki.testuserjoepass")))

  import T._

  def milis = System.currentTimeMillis.toString

  var (noClubs, noUsers, noKidz) = (1, 5, 0)

  ("/razadmin/config/noemailstesting", joe, pjoe) eok "true" // can't send emails in perf testing

  /////////////////////////////////////////////////
//    val clubs = doClubs(milis, noClubs, noUsers, false, 20)
//    cout << "Clubs: " + clubs.mkString
  /////////////////////////////////////////////////

  /////////////////////////////////////////////////
//    val clubs = doClubs(milis, noClubs, noUsers, false, 20)
//    cout << "Clubs: " + clubs.mkString
  /////////////////////////////////////////////////

//  Perf.perf(Seq(1, 5, 10), 10) { (t, l) =>
//    val clubs = doClubs(s"$milis$t$l", 1, 10, false, 1)
//  }

  ("/razadmin/config/okemailstesting", joe, pjoe) eok "false" // can't send emails in perf testing

  cout << s"$noClubs clubs $noUsers families ${noUsers+noKidz} members"
  cout << "------------DONE"

  /** create clubs with users and registrations */
  def doClubs(testId:String, clubs: Int, members: Int, doReg: Boolean, threads: Int) = {
    val c = (for (i <- 0 until clubs) yield {
      doClub(members, testId.toString+"x"+i.toString, doReg, threads)
    }).toList

    cout << "Clubs: " + c.mkString
    c
  }

  /** create a club with members and share notes etc */
  def doClub(members: Int, clubEname: String, doReg: Boolean, threads: Int) = {
    val (club, clubId) = crUser(clubEname + "org", "Organization")
    val wcId = crWiki(None, "Club", clubId, "haha", club)
    (s"/testingRaz/wikiSetOwnerById/$wcId,$clubId/$TESTCODE", club, p) eok "ok"

    val tname = s"T1-$clubId"
    val tribecontent = s"""
team

Details:

* {{role:Team}}
* {{name:T1}}
* {{label:T1}}
* {{desc:T1 team}}
* {{year:2013}}
"""
    val tId = crWiki(Some(s"Club:$clubId"), "Tribe", s"T1-$clubId", tribecontent, club)

    val form1 = crWiki(None, "Note", clubId + "-RacerForm",
      """
First Name {{f:firstName:}}

Phone: {{f:physiciansPhone:}}

Asthma: {{f:cbAsthma:type=checkbox}}

Birth date(dd/mm/yyyy): {{f:birthDate:type=date}}

""", club)
    val form2 = crWiki(None, "Note", clubId + "-RacerMed", "First Name {{f:firstName:}}", club)

    (s"/doe/club/regsettings", club, p) eok "settings" // first hit creates Club
    (s"/doe/club/updateregsettings").url.form(Map(
      "regType" -> "FamilyWithKids",
      "curYear" -> "2013",
      "regAdmin" -> "ileana@razie.com",
      "regForms" -> s"Racer=Note:$clubId-RacerForm\nRacer.Medical=Note:$clubId-RacerMed",
      "newFollows" -> "",
      "dsl" -> "")).basic(club, p) eok ""
    (s"/doe/club/regsettings", club, p) eok "Racer=Note" // check

    val users = localhost.MT.slicejoin(threads, (0 until members).toList) { i =>
      val (u, userId) = crUser(clubEname + "x"+i.toString + "coach", "Coach")
      val kids =
        crKid(userId, u, "a-kid" + clubEname+"x"+i, clubEname, "Kid") ::
          crKid(userId, u, "b-kid" + clubEname+"x"+i, clubEname, "Kid") ::
          crKid(userId, u, "c-kid" + clubEname+"x"+i, clubEname, "Spouse") :: Nil
      (u, userId, kids)
    }.flatMap(_.toList).toList

    noUsers = noUsers + users.size
    noKidz = noKidz + users.map(_._3.size).sum
    noClubs = noClubs + 1

    // link users
    razie.Threads join razie.Threads.sliceForeach(threads, users) { t =>
      val (u, userId, kids) = t
      // user becomes member of club
      val cname = (s"/testingRaz/userUsernameById/$userId/$TESTCODE", club, p).wget
      (s"/wikie/linked/User/$userId/Club/$clubId?wc=0").url.form(Map(
        "how" -> "Coach",
        "markup" -> "md",
        "notif" -> "e",
        "comment" -> "hahah")).basic(u, p) eok "added"

      val uwId = (s"/testingRaz/uwIdByUserId/$userId/$TESTCODE", club, p).wget
      uwId should have length (24)

      (s"/doe/club/regs", club, p) eok uwId

      if (doReg) {
        // start reg
        (s"/doe/club/uw/regstatusupd/$uwId/expired", club, p) eok userId
        val regId = (s"/testingRaz/regByUserId/$userId/$TESTCODE", club, p).wget
        regId should have length (24)

        // add kids to it
        //http://localhost:9000/doe/club/uw/addformkid/522f4d910577fc9c61f7f1ac/522f4d910577fc9c61f7f197/522f4d910577fc9c61f7f1a3/Racer
        kids.foreach { t =>
          val (rkId, role) = t
          if (role == "Kid")
            (s"/doe/club/uw/addformkid/$regId/$rkId/$uwId/Racer", club, p) eok userId
          else
            (s"/doe/club/uw/addformkid/$regId/$rkId/$uwId/Volunteer", club, p) eok userId
        }
      }
    }

    cout << (club, clubId).toString << users.mkString

    (club, clubId)
  }

  // get data about an existing user by first name
  def getUser(uf: String) = {
    val t = ("H-" + uf + "@k.com",
      (s"/testingRaz/userIdByFirstName/$uf/$TESTCODE", "H-" + uf + "@k.com", p).wget)
    t
  }
}

object RunTestNofosCorpPerf extends App {
  org.scalatest.tools.Runner.run("-s localhost.TestClubs".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestUserKid".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
