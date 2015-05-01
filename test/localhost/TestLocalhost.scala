package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import com.razie.pub.comms.CommRtException

import razie.Snakk
import razie.UrlTester

class TestLocalhost extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "http://localhost:9000"
  val (u,p) = ("H-joe@razie.com", "H-321mm321mm")

  "/" sok "RacerKidz"
  "/wiki" sok "categories"

  //  "special admin topics" should "not visible" in {
  "/wiki/Category:Admin" snok "urlmap"
  "/wiki/Category:Admin" snok "urlfwd"
  "/wiki/Category:Admin" snok "sitecfg"
  "/wiki/Category:Admin" snok "topicred"
  "/wiki/Category:Admin" snok "safesites"

  "/wikie/edit/Admin:home".s400
  ("/wikie/edit/Admin:home", u, p).s400

  "/admin".s400
  "/admin/db".s400
  "/admin/db/col/Audit".s400
  "/admin/users".s400
  "/admin/wikidx".s400
  "/admin/reloadurlmap".s400
  "/admin/audit".s400

  // permissions

  "/wiki/Blog:Razie_Enduro_School" sok "dirt bike"
  "/wikie/edit/Blog:Razie_Enduro_School".s400
  "/wikie/rename1/Blog:Razie_Enduro_School".s400
  "/wikie/linkuser/Blog:Razie_Enduro_School".s400
  "/wikie/report/Blog:Razie_Enduro_School" sok "report"

  ("/wiki/Blog:Razie_Enduro_School", u, p) sok ("dirt bike")
  ("/wikie/edit/Blog:Razie_Enduro School", u, p).s400

//  "private topics" should "not be visible" in {
    "/wiki/Note:Joe_Private_Note".s400
    ("/wiki/Note:Joe_Private_Note", u, p) sok "Joe Private Note"
    ("/wikie/edit/Note:Joe_Private_Note", u, p) sok "edit"
    ("/wikie/edit/Note:Joe_Private_Note", "X", p).s400
    ("/wikie/edit/Note:Joe_Private_Note", u, "X").s400
    ("/wiki/Note:Razie_Private_Note", u, p).s400

    "/wiki/Category:Note" snok "Private Note"
    "/w/rk/wiki/all/Note" snok "Private Note"

    "/wikie/search?q=Glacier" sok "Glacier"

  //public vs private profiles
  "/user/Razie" sok "Razvan"
  "/user/Matei" snok "not visible"

    // new notes include cat as tag
//    ("/wikie/edit/Venue:Some New Note", u, p) sok "venue"
}

class TestEdit extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "http://localhost:9000"

  val (u,p) = ("H-joe@razie.com", "H-321mm321mm")

  val milis = System.currentTimeMillis.toString

  val form = Map (
      "label" -> ("Joe Private Note "+milis),
      "markup" -> "md",
      "content" -> "hehe",
      "visibility" -> "Private",
      "wvis" -> "Private",
      "tags" -> "note")
  ("/wikie/edited/Note:Joe_Private_Note_"+milis).url.basic(u,p).form(form).wget // first create, no check
//  ("/wiki/Note:Joe_Private_Note_"+milis).url.basic(u,p) sok "Private" // now check
}

class TestForms extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "http://localhost:9000"

  val s = "/wikie/form/Note:test_forms"
  val (u,p) = ("H-joe@razie.com", "H-321mm321mm")

  val form = Map (
      "name" -> "Joe Private Note 3")
  "/wikie/form/Note:test_forms".url.basic(u,p).form(form) sok "ok"
}

object RunTestLocalhost extends App {
  org.scalatest.tools.Runner.run("-s localhost.TestLocalhost".split(" "))
  org.scalatest.tools.Runner.run("-s localhost.TestLocalhost".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
