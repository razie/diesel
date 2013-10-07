package localhost

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.razie.pub.comms.CommRtException
import razie.Snakk
import razie.UrlTester
import razie.SnakkUrl
import razie.cout

object T {
  final val TESTCODE = "RazTesting"
}

trait RkTester extends UrlTester { self: FlatSpec with ShouldMatchers =>
  import T._

  val p = "H-" + TESTCODE

  /** create a user */
  def crUser(n: String, role: String)(implicit hostport: String): (String, String) = {
    def user(n: String) = Map(
      "firstName" -> n,
      "lastName" -> "",
      "yob" -> "1991",
      "address" -> "City of future",
      "userType" -> role,
      "accept" -> "true",
      "recaptcha_challenge_field" -> TESTCODE,
      "recaptcha_response_field" -> TESTCODE)
    ("/doe/profile/create?testcode=" + TESTCODE).url.form(user(n)).wget // sok "we sent an emai" 
    //    Thread.sleep(1000)
    val email = "H-" + n + "@k.com"
    val userId = (s"/testingRaz/userIdByFirstName/$n/$TESTCODE", email, p).wget
    userId should have length (24)
    (s"/testingRaz/auth/x/$TESTCODE", email, p).wget.length should be > 5
    cout << (s"/testingRaz/verifyUserById/$userId/$TESTCODE", email, p).wget
    cout << (s"/testingRaz/setuserUsernameById/$userId/$TESTCODE", email, p).wget
    cout << "User created: " << ("H-" + n, userId, p)
    (email, userId)
  }

  /** create a wiki page */
  def crWiki(parent: Option[String], cat: String, name: String, content: String, u: String)(implicit hostport: String) = {
    val form = Map(
      "label" -> name,
      "markup" -> "md",
      "content" -> content,
      "visibility" -> "Public",
      "wvis" -> "Club",
      "notif" -> "n",
      "tags" -> cat.toLowerCase)
    if (parent.isDefined)
      s"/wikie/edited/${parent.get}/$cat:$name".url.basic(u, p).form(form).wget // first create, no check
    else
      s"/wikie/edited/$cat:$name".url.basic(u, p).form(form).wget // first create, no check

    val id = (s"/testingRaz/wikiIdByName/$name/$TESTCODE", u, p).wget
    id should have length (24)
    cout << s"Wiki created: $cat:$name = $id"
    id
  }

  /** create a racerkid for a user */
  def crKid(userId: String, u: String, f: String, l:String, role: String)(implicit hostport: String) = {
    def kid = Map(
      "fname" -> f,
      "lname" -> l,
      "email" -> (f+l + "@k.com"),
      "dob" -> "2000-01-01",
      "gender" -> "M",
      "role" -> role,
      "status" -> "a",
      "assocRole" -> "Racer",
      "notifyParent" -> "y")
    cout << "Kid created: " << (userId, f+"."+l, role, u)
    (s"/doe/user/kidupd/$userId/11/$role/-/Club").url.form(kid).basic(u, p).wget // sok "Kidz" // first create, no check
    val rkId = (s"/testingRaz/kidByName/$f,$l/$TESTCODE", u, p).wget
    rkId should have length (24)
    (rkId, role)
  }
}
