package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import razie.cout

class TestStress extends FlatSpec with ShouldMatchers with razie.UrlTester {
  implicit val hostport = "http://localhost:9000"
//  implicit val hostport = "http://test.razie.com"

  "site" should "be stable" in {
    cout << "priming the server"
    thr(2, 2)

    thr(10, 10)
  }

  def thr(threads: Int, loops: Int) {
    razie.Threads.forkjoin(0 to threads) { i =>
      ((0 to loops) map { x =>
        cout << s"run $x-$i"
        "/wiki/Blog:Razie_Enduro_School" eok "dirt bike"
        "/wiki/Admin:Join_A_Club" eok "hosted"
      })
    }
  }

}
