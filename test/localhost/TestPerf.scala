package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import razie.cout

class TestPerf extends FlatSpec with ShouldMatchers with RkTester {
//  implicit val hostport = "http://localhost:9000"
  implicit val hostport = "http://test.racerkidz.com"
//  implicit val hostport = "http://cloud2a.razie.com:9000"

  "site" should "be fast" in {
    cout << "priming the server"
    thr(2, 2)

    razie.Timer({
      
      thr(20, 10000)
      
    })._1 should be <= (10*1000L)
  }

  def thr(threads: Int, loops: Int) {
    razie.Threads.forkjoin(0 to threads) { i =>
      ((0 to loops) map { x =>
        cout << s"run $x-$i"
        "/wiki/Admin:Hosted_Services_for_Ski_Clubs" eok " forms"
        "/wiki/Blog:Razie_Enduro_School" eok "dirt bike"
      })
    }
  }

}
