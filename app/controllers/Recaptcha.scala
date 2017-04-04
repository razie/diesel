package controllers

import razie.{Logging, Snakk, cout}
import play.api.Play
import razie.wiki.admin.SendEmail
import razie.wiki.{Enc, Services}

/** to use this put these in the application.conf

recaptcha {
  privatekey = "..."
  localhost = true // true means bypass RE for localhost
  secret = "..."
}

  */
object Recaptcha extends Logging {

  final val RE_privatekey = Play.current.configuration.getString("recaptcha.privatekey").mkString
  final val RE_localhost = // true means bypass RE for localhost
    Play.current.configuration.getString("recaptcha.localhost").getOrElse("true").toBoolean
  final val RE2_secret = Play.current.configuration.getString("recaptcha.secret").mkString

  def verify(challenge: String, response: String, clientIp: String) =
    (Services.config.isLocalhost && RE_localhost || challenge == T.TESTCODE) || {
      val resp = Snakk.body(
        Snakk.url("http://www.google.com/recaptcha/api/verify").form(
          Map("privatekey" -> RE_privatekey, "remoteip" -> clientIp, "challenge" -> challenge, "response" -> response)))

      debug("CAPTCHCA RESP=" + resp)

      if (resp contains "invalid")
        SendEmail.withSession { implicit mailSession =>
          Emailer.tellRaz("ERROR CAPTCHA", "response is " + resp)
        }

      resp.startsWith("true")
    }

  // new one, simpler
  def verify2(response: String, clientIp: String) =
    (Services.config.isLocalhost && RE_localhost) || {
      val resp = Snakk.json(
        Snakk.url("https://www.google.com/recaptcha/api/siteverify").form(
          Map("secret" -> RE2_secret, "remoteip" -> clientIp, "response" -> response)))

      debug("CAPTCHCA RESP=" + resp)

//      if (resp contains "invalid")
//        SendEmail.withSession { implicit mailSession =>
//          Emailer.tellRaz("ERROR CAPTCHA", "response is " + resp)
//        }

      resp \@ "success" == "true"
    }

}
