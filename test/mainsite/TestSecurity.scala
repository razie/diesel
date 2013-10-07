package mainsite

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import java.net.Socket
import java.net.InetSocketAddress

/** test a socket port is open or not */
class IsOpen (host:String, port:Int) {
  val server = new Socket();
  server.connect(new InetSocketAddress(host, port), 250);
  val ok = server.isConnected
  server.close
}

class TestSecurity extends FlatSpec with ShouldMatchers {

  "Clouds" should "disable mongo ports" in {
    new IsOpen ("cloud1.razie.com", 27017).ok should equal (false)
    new IsOpen ("cloud1.razie.com", 27018).ok should equal (false)
    new IsOpen ("cloud1.razie.com", 27019).ok should equal (false)
    }

}
