package localhost

import model.WikiParser
import model.Wikis
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.razie.pub.comms.CommRtException
import razie.Snakk

object TestHelper {
  var thou = 1000
  def uniq = "-"+System.currentTimeMillis().toString + "-" + { thou = (thou + 1) % 1000; thou }.toString+"-"
}

trait UrlTester { self: FlatSpec with ShouldMatchers =>
  import TestHelper._

  case class MyString(s: String, basic: Option[(String, String)] = None) {
    def w(implicit hostport: String): String = {
      import razie.Snakk

      val u = Snakk.url("http://" + hostport + s)
      val uu = basic.map(x => u.basic(x._1, x._2)).getOrElse(u)
      val bod = Snakk.body(uu)
      bod
    }

    def e400(implicit hostport: String) = {
      evaluating { this.w } should produce[CommRtException]
    }

    def s400(implicit hostport: String) = {
      (s+uniq) should "not be visible" in {
        evaluating { this.w } should produce[CommRtException]
      }
    }

    def sok(incl: String)(implicit hostport: String) = {
      (s+uniq) should "be visible" in {
        this.w should include(incl)
      }
    }

    def snok(incl: String)(implicit hostport: String) = {
      (s+uniq) should "be visible but exclude " + incl in {
        this.w should not include (incl)
      }
    }

    def eok(incl: String)(implicit hostport: String) = {
      this.w should include(incl)
    }

    def enok(incl: String)(implicit hostport: String) = {
      this.w should not include (incl)
    }

  }

  implicit def toMyString(s: String) = MyString(s)
  implicit def toMyString2(s: (String, String, String)) = MyString(s._1, Some(s._2, s._3))
}

class TestWiki extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "localhost:9000"

  "/" sok "RacerKidz"
  "/wiki" sok "categories"

  //  "special admin topics" should "not visible" in {
  "/wiki/Category:Admin" snok "urlmap"
  "/wiki/Category:Admin" snok "urlfwd"
  "/wiki/Category:Admin" snok "sitecfg"
  "/wiki/Category:Admin" snok "topicred"
  "/wiki/Category:Admin" snok "safesites"

  "/wikie/edit/Admin:home".s400

  "/admin".s400
  "/admin/db".s400
  "/admin/db/col/Audit".s400
  "/admin/users".s400
  "/admin/wikidx".s400
  "/admin/reloadurlmap".s400
  "/admin/audit".s400

  // permissions

  "/wiki/Blog:Razie's_Enduro_Blog" sok "dirt bike"
  "/wikie/edit/Blog:Razie's_Enduro_Blog".s400
  "/wikie/rename1/Blog:Razie's_Enduro_Blog".s400
  "/wikie/linkuser/Blog:Razie's_Enduro_Blog".s400
  "/wikie/report/Blog:Razie's_Enduro_Blog" sok "report"

  ("/wikie/edit/Sport:Alpine_Ski", "joe@razie.com", "321mm321mm") sok "edit"
  ("/wikie/edit/Sport:Alpine_Ski", "Xjoe@razie.com", "321mm321mm").s400
  ("/wikie/edit/Sport:Alpine_Ski", "joe@razie.com", "X321mm321mm").s400

  ("/wiki/Blog:Razie_Enduro_School", "joe@razie.com", "321mm321mm") sok ("dirt bike")
  ("/wikie/edit/Blog:Razie_Enduro School", "joe@razie.com", "321mm321mm").s400

//  "private topics" should "not be visible" in {
    "/wiki/Note:Joe_Private_Note".s400
    ("/wiki/Note:Joe_Private_Note", "joe@razie.com", "321mm321mm") sok "Joe Private Note"
    ("/wikie/edit/Note:Joe_Private_Note", "joe@razie.com", "321mm321mm") sok "edit"
    ("/wiki/Note:Razie_Private_Note", "joe@razie.com", "321mm321mm").s400

    "/wiki/Category:Note" snok "Private Note"
    "/wiki/all/Note" snok "Private Note"
    
    // new notes include cat as tag
    ("/wikie/edit/Venue:Some New Note", "joe@razie.com", "321mm321mm") sok "xnote"
}

class TestTemp extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "localhost:9000"

  val s = "/wikie/edited/Note:Joe_Private_Note_3"
  val (u,p) = ("joe@razie.com", "321mm321mm")

  val form = Map (
      "label" -> "Joe Private Note 3",
      "markup" -> "md",
      "content" -> "hehe",
      "visibility" -> "Public",
      "wvis" -> "Public",
      "tags" -> "note")
  val surl = Snakk.url("http://" + hostport + s, razie.AA(), "POST")//.basic(u,p)
  val bod = Snakk.body(surl)
  println (bod)
}

class TestIlp1 extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "ilp1.razie.com"

  val s = "/wikie/s/Session:_Beer_chugging_date_Oct_20,_2012/sessionInfo"
  val (u,p) = ("gigi@razie.com", "cristi21")

  val surl = Snakk.url("http://" + hostport + s, razie.AA()).basic(u,p)
  val bod = Snakk.body(surl)
  println (bod)
}

class TestPerf extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "localhost:9000"

  "site" should "be fast" in {
    razie.Threads.forkjoin(0 to 100) { i =>
      ((0 to 10) map { x => "/wiki/Blog:Razie's_Enduro_School".w contains "dirt bike" }).exists(identity)
    }.exists(p => !p.isDefined || !p.get) === true
  }
}

object TestLocalhost extends App {
//  org.scalatest.tools.Runner.run("-s localhost.TestWiki".split(" "))
//    org.scalatest.tools.Runner.run("-s localhost.TestTemp".split(" "))
    org.scalatest.tools.Runner.run("-s localhost.TestIlp1".split(" "))
  //  org.scalatest.tools.Runner.run("-s localhost.TestPerf".split(" "))
}
