package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import com.razie.pub.comms.CommRtException

import razie.Snakk
import razie.UrlTester

class TestCaptcha extends FlatSpec with ShouldMatchers with UrlTester {
  implicit val hostport = "http://localhost:9000"
  val (u,p) = ("H-joe@razie.com", "H-321mm321mm")


  // can get recaptcha
  "http://www.google.com/recaptcha/api/noscript?k=6Ld9uNASAAAAAL_5jXRwtBAtiJy8XGwSzZOWW80s" sok "recaptcha_challenge_field"

  // TO TEST it - use the url above yourself / view source / copy here the challenge and response
  val challenge = "x"
  val response = "x"

//	  val resp = Snakk.body(
//	  Snakk.url(
//	    "http://www.google.com/recaptcha/api/verify",
//	    Map("privatekey" -> "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax", "remoteip" -> "kk", "challenge" -> challenge, "response" -> response),
//	    "POST"))
//
//   println ( "CAPTCHCA RESP=" + resp)

  //  "special admin topics" should "not visible" in {

}
