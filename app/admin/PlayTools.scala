package admin

import play.api.mvc.Request

/**
 * Created by raz on 2014-11-18.
 */
object PlayTools {
  def getHost (implicit request: Request[_]) =
    if(request.headers.get("X-FORWARDED-HOST").exists(_ startsWith "localhost:"))
      Some("www.racerkidz.com")    // for testing locally
    //      Some("www.enduroschool.com")    // for testing locally
    //      Some("www.glacierskiclub.com")    // for testing locally
    //      Some("www.nofolders.net")    // for testing locally
    //      Some("www.dieselreactor.net")    // for testing locally
    else
      request.headers.get("X-FORWARDED-HOST")
}
