package controllers

import admin.Config
import com.google.inject._
import razie.{Logging, Snakk}
import play.api.{Configuration}
import razie.wiki.admin.SendEmail
import razie.wiki.Services

/** to use this put these in the application.conf

recaptcha {
  privatekey = "..."
  localhost = true // true means bypass RE for localhost
  secret = "..."
}

  */
@Singleton
class Recaptcha @Inject() (config:Configuration) extends Logging {

  final val RE_privatekey = config.underlying.getString("recaptcha.privatekey").mkString
  final val RE_localhost = // true means bypass RE for localhost
    config.underlying.getString("recaptcha.localhost").mkString.toBoolean
  final val RE2_secret = config.underlying.getString("recaptcha.secret").mkString

  def verify(challenge: String, response: String, clientIp: String) =
    (Services.config.isLocalhost && RE_localhost || challenge == T.TESTCODE) || {
      val resp = Snakk.body(
        Snakk.url("http://www.google.com/recaptcha/api/verify").form(
          Map("privatekey" -> RE_privatekey, "remoteip" -> clientIp, "challenge" -> challenge, "response" -> response)))

      debug("CAPTCHCA RESP=" + resp)

      if (resp contains "invalid")
        SendEmail.withSession() { implicit mailSession =>
          Emailer.tellAdmin("ERROR CAPTCHA", "response is " + resp)
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
//          Emailer.tellAdmin("ERROR CAPTCHA", "response is " + resp)
//        }

      resp \@ "success" == "true"
    }

}
