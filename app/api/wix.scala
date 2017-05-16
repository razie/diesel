package api

import mod.diesel.controllers.SFiddles
import model._
import controllers.{Club, XWrapper, XListWrapper}
import razie.db.RazMongo
import razie.wiki.{Services, Dec}
import razie.wiki.model._
import razie.wiki.util.M._

/** this is available to scripts inside the wikis */
class wix (owe: Option[WikiEntry], ou:Option[WikiUser], q:Map[String,String], r:String) {
  lazy val hostport:String = Services.config.hostport

  // hide actual app objects and give selective access via public objects below
  private var ipage: Option[WikiEntry] = owe
  private var iuser: Option[User] = ou.asInstanceOf[Option[User]]
  private var iquery: Map[String,String] = q
  private var irealm: String = if(r.isEmpty) owe.map(_.realm).mkString else r

  val page = new {
    def name = ipage.get.name
    def isDefined = ipage.isDefined
    def isEmpty = ipage.isEmpty
    def wid = ipage.get.wid
    def findAssocWithCat(cat:List[String]) = {
      def isc (c:String) = cat contains c
      ipage.find(cat contains _.category).orElse {
        def flatten (page:WikiEntry):List[WikiEntry] = page :: page.parent.flatMap(Wikis.find).toList.flatMap(flatten)
        ipage.toList.flatMap(flatten).find(cat contains _.category).orElse(
          ipage.toList.flatMap(w=> w.linksFrom.toList).flatMap(_.pageTo.toList).find(cat contains _.category)
        )
      }
    }

//    def map[T] (f: page.type => T) = if(isDefined) Some(f(page)) else None
  }

  def ownedPages(realm:String, cat:String) = utils.wikiList(iuser.toList.flatMap(_.ownedPages(realm, cat)))
  def ownedReactors(realm:String) = {
    val wids = iuser.toList.flatMap(_.ownedPages(realm, "Reactor"))
    if(wids.isEmpty) "<em>None</em>"
    else
      wids.map(r=>(r, Wikis(r.name).count))
        .sortWith(_._2 > _._2)
        .map(t=> s"""<a href="/wikie/switchRealm/${t._1.name}">${t._1.name}</a>""")
        .mkString("", " <b>|</b> ", "")
  }

  val user = new {
    def userName = iuser.get.userName
    def firstName = iuser.get.firstName
    def ename = iuser.get.ename
    def isDefined = iuser.isDefined
    def isEmpty = iuser.isEmpty
    def hasMembershipLevel(s:String) = iuser.map(_.hasMembershipLevel(s)) getOrElse false
    def id = iuser.get._id
    def isOwner = iuser.exists(u=> ipage.flatMap(_.owner).exists(_._id == u._id))
    def isClubAdmin = iuser.exists{u=>
      isDbAdmin ||
        page.findAssocWithCat(List("Club", "Pro")).map(_.wid).flatMap(Club.apply).exists(_.isAdminEmail(Dec(u.email)))
    }
    def isClubMember = iuser.exists{u=>
      page.findAssocWithCat(List("Club", "Pro")).map(_.wid).exists(name=>u.wikis.filter(_.wid.cat == "Club").exists(_.wid.name == name))
    }
    def isClubCoach = iuser.exists{u=>
      page.findAssocWithCat(List("Club", "Pro")).map(_.wid).flatMap(Club.apply).exists(_.isMemberRole(u._id, "Coach"))
    }

    def isDbAdmin = iuser.exists{_.isAdmin }

//    def map[T] (f: user.type => T) = if(isDefined) Some(f(user)) else None

    def isRegistered = iuser exists (u=> ipage >>> (x=>model.Users.findUserLinksTo(x.uwid).toList) exists (_.userId == u._id))
  }

  def jsonBrowser = {
    """var wix = {
    """ +
   s"""
      "hostport" : "${hostport}",
    """ +
      (if(ipage.isDefined) {
        s"""
    "page" : {
      "name" : "${ipage.get.name}",
      "category" : "${ipage.get.category}",
      "isModerated" : ${ipage.flatMap(_.attr("moderator")).exists(_.length > 0)},
      "isDefined" : ${ipage.isDefined},
      "isEmpty" : ${ipage.isEmpty},
      "wid" : "${ipage.get.wid.wpath}",
      "wpath" : "${ipage.get.wid.wpath}",
      "wpathnocats" : "${ipage.get.wid.wpathnocats}"
    },
    """
      } else "") +
      (if(iuser.isDefined) {
        s"""
    "user" : {
      "userName" : "${iuser.get.userName}",
      "firstName" : "${iuser.get.firstName}",
      "ename" : "${iuser.get.ename}",
      "isDefined" : ${iuser.isDefined},
      "isEmpty" : ${iuser.isEmpty},
      "isClubMember" : ${user.isClubMember},
      "isClubAdmin" : ${user.isClubAdmin},
      "isClubCoach" : ${user.isClubCoach},
      "isRegistered" : ${user.isRegistered},
      "id" : "${iuser.get._id.toString}",
      "perms" : ["${iuser.get.perms.mkString("\",\"")}"],
      "groups" : ["${iuser.get.groups.map(_.name).mkString("\",\"")}"]
    },
    """
      } else "") +
      s"""
    "query" :
    """ +
      SFiddles.qtojson(iquery) +
      """
    };
      """
  }

  def json = {
    jsonBrowser +
      """wix.utils = new (Java.type("api.WixUtils"))(wixj);"""
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

  def utils = new WixUtils(this)
}

/** this is available to scripts inside the wikis */
object wix {

  def apply (owe: Option[WikiEntry], ou:Option[WikiUser], q:Map[String,String], r:String) = {
    new wix(owe, ou, q, r)
  }

  object utils {
    def countForms() = RazMongo("weForm").size

  }
}

class WixUtils(w:wix) {
  def countRealmPages() = Wikis(w.realm.name).count
  def countForms() = wix.utils.countForms()
  def wikiList(wids:List[WID]) =
    if(wids.isEmpty) "<em>None</em>" else "<ul>"+wids.map(x=> "<li>"+ x.ahrefRelative() + "</li>").mkString("") + "</ul>"
}

