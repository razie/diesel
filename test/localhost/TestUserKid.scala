package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.razie.pub.comms.CommRtException
import razie.Snakk
import razie.UrlTester
import razie.SnakkUrl

class TestUserKid extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "http://localhost:9000"
  val (u, p) = ("H-joe@razie.com", "H-321mm321mm")

  //  "private topics" should "not be visible" in {
  "/doe/user/kidz".s400
  ("/doe/user/kidz", u, p) sok "Kidz"

  crKid("Joe")

  val milis = System.currentTimeMillis.toString

  def crKid(n: String) = {
    def kid(n: String) = Map(
      "fname" -> n,
      "lname" -> "Test",
      "email" -> (n + "@hehe.com"),
      "dob" -> "2000-01-01",
      "role" -> "kid",
      "status" -> "a")
    ("/doe/user/kidupd/11").url.basic(u, p).form(form) sok "Kidz" // first create, no check
  }

  val form = Map(
    "name" -> "Joe Private Note 3")
  "/wikie/form/Note:test_forms".url.basic(u, p).form(form) sok "ok"
}

object RunTestUserKid extends App {
  org.scalatest.tools.Runner.run("-s localhost.TestUserKid".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestUserKid".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
