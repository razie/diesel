package controllers

import admin.Config
import razie.db.{ROne, Txn}
import razie.{Logging, cout, Snakk}
import razie.db.RMongo._
import razie.wiki.Sec._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, tuple, _}
import play.api.mvc.{Action, Request}
import razie.OR._
import razie.wiki.util.PlayTools
import razie.wiki.admin.SendEmail
import razie.wiki.model.{WikiReactors, WikiIndex, Wikis, WID}
import razie.wiki.{Services, Enc}

object Recaptcha extends Logging {

  // from main razie@ account
  final val RE_privatekey = "6Ld9uNASAAAAADEg15VTEoHjbLmpGTkI-3BE3Eax"
  final val RE_localhost = true // true means bypass RE for localhost

  final val RE2_secret = "6Ldg3R8TAAAAAGz2ZybBQMxG4DL5B0klDUmHvzm7"

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
