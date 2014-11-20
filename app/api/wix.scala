package api

import model._
import controllers.{XWrapper,XListWrapper}
import admin.Config
import db.RazMongo

/** this is available to scripts inside the wikis */
object wix {
  import admin.M._

  lazy val hostport:String = Config.hostport

  // TODO don't use the actual classes but some dumbed-down read-only data-strippers
  var page: Option[WikiEntry] = None
  var user: Option[User] = None
  var query: Map[String,String] = Map()

  def isUserRegistered = user exists (u=> page >>> (x=>model.Users.findUserLinksTo(x.uwid).toList) exists (_.userId == u._id))

  /** start xp from the current page */
  def xp =
    new XListWrapper(
      page.toList.map { p => new WikiWrapper(p.wid) },
      WikiXpSolver)

  /** start xp from user's pages of given category, i.e. the races he subscribed to */
  def uxp (cat:String) =
    new XListWrapper(
      user.toList.flatMap(_.pages(cat)).map { uw => new WikiWrapper(WID(cat, uw.uwid.nameOrId)) },
      WikiXpSolver)

  def countForms = RazMongo("weForm").size
//  {controllers.UserStuff.xp(user, "Calendar") \ UserStuff.Race \ "Venue" \@ "loc"}.filter(! _.isEmpty).map(_.replaceFirst("ll:",""))
}
