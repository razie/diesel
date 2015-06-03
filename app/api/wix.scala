package api

import model._
import controllers.{XWrapper,XListWrapper}
import admin.Config
import razie.db.RazMongo
import razie.wiki.model._
import razie.wiki.util.M._

/** this is available to scripts inside the wikis */
object wix {
  //todo wix is static - make nonstatic

  lazy val hostport:String = Config.hostport

  // hide actual app objects and give selective access via public objects below
  private var ipage: Option[WikiEntry] = None
  private var iuser: Option[User] = None //todo use this
  private var iquery: Map[String,String] = Map()
  private var irealm: String = ""

  def init (owe: Option[WikiEntry], ou:Option[User], q:Map[String,String], r:String): Unit = {
    ipage=owe
    iuser=ou
    iquery=q
    irealm=r
  }

  object page {
    def name = ipage.get.name
    def isDefined = ipage.isDefined
    def isEmpty = ipage.isEmpty
    def wid = ipage.get.wid

    def map[T] (f: page.type => T) = if(isDefined) Some(f(page)) else None
  }

  object user {
    def userName = iuser.get.userName
    def firstName = iuser.get.firstName
    def ename = iuser.get.ename
    def isDefined = iuser.isDefined
    def isEmpty = iuser.isEmpty
    def id = iuser.get._id
    def isOwner = iuser.exists(u=> ipage.flatMap(_.owner).exists(_._id == u._id))
    def ownedPages(realm:String, cat:String) = iuser.toList.flatMap(_.ownedPages(realm, cat))

    def map[T] (f: user.type => T) = if(isDefined) Some(f(user)) else None

    def isRegistered = iuser exists (u=> ipage >>> (x=>model.Users.findUserLinksTo(x.uwid).toList) exists (_.userId == u._id))
  }

  def json = {
    """var wix = {
    """ +
      (if(ipage.isDefined) {
    s"""
    "page" : {
      "name" : "${ipage.get.name}",
      "isDefined" : "${ipage.isDefined}",
      "isEmpty" : "${ipage.isEmpty}",
      "wid" : "${ipage.get.wid}"
    },
    """
    } else "") +
   (if(iuser.isDefined) {
      s"""
    "user" : {
      "userName" : "${iuser.get.userName}",
      "firstName" : "${iuser.get.firstName}",
      "ename" : "${iuser.get.ename}",
      "isDefined" : "${iuser.isDefined}",
      "isEmpty" : "${iuser.isEmpty}",
      "id" : "${iuser.get._id.toString}"
    }
    """
   } else "") +
    """}"""
  }

  def query: Map[String,String] = iquery

  object realm {
    def count = Wikis(irealm).count
    def name = irealm
  }

  /** start xp from the current page */
  def xp =
    new XListWrapper(
      ipage.toList.map { p => new WikiWrapper(p.wid) },
      WikiXpSolver)

  /** start xp from user's pages of given category, i.e. the races he subscribed to */
  def uxp (cat:String) =
    new XListWrapper(
      iuser.toList.flatMap(_.pages(realm.name, cat)).map { uw => new WikiWrapper(WID(cat, uw.uwid.nameOrId)) },
      WikiXpSolver)
  //  {controllers.UserStuff.xp(user, "Calendar") \ UserStuff.Race \ "Venue" \@ "loc"}.filter(! _.isEmpty).map(_.replaceFirst("ll:",""))

  object utils {
    def countForms = RazMongo("weForm").size
    def wikiList(wids:List[WID]) = "<ul>"+wids.map(x=> "<li>"+ x.ahrefRelative + "</li>").mkString("") + "</ul>"
  }

}
