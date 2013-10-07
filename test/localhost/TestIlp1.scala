package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import razie.Snakk

class TestIlp1 extends FlatSpec with ShouldMatchers with razie.UrlTester {
  implicit val hostport = "http://ilp1.razie.com"

  val bod = ("/wikie/s/Session:_Beer_chugging_date_Oct_20,_2012/sessionInfo", "gigi@razie.com", "cristi21").wget
    
  println (bod)
}
