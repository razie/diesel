/**
  *   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package api

import controllers.{Club, XListWrapper}
import mod.diesel.controllers.SFiddles
import model._
import razie.db.RazMongo
import razie.hosting.WikiReactors
import razie.wiki.model._
import razie.wiki.util.M._
import razie.wiki.{Sec, Services}
import scala.collection.immutable.ListMap

/** this is available to scripts inside the wikis */
class wix (owe: Option[WikiPage], ou:Option[WikiUser], q:Map[String,String], r:String) {
  lazy val hostport:String = Services.config.hostport

  // hide actual app objects and give selective access via public objects below
  protected var ipage: Option[WikiPage] = owe
  protected var iuser: Option[User] = ou.asInstanceOf[Option[User]]
  protected var iquery: Map[String,String] = q
  protected var irealm: String = if(r.isEmpty) owe.map(_.realm).mkString else r

  // so you can do wix.diesel.env
  val diesel = new {
    // todo slow db lookup - cache in user
    def env = wix.dieselEnvFor(irealm, ou)
  }

  // so you can do wix.page.name
  val page = new {
    def name = ipage.get.name
    def isDefined = ipage.isDefined
    def isEmpty = ipage.isEmpty
    def wid = ipage.get.wid
    def findAssocWithCat(cat:List[String]) = {
      def isc (c:String) = cat contains c
      ipage.find(cat contains _.category).orElse {
        def flatten (page:WikiPage):List[WikiPage] = page :: page.parent.flatMap(Wikis.find).toList.flatMap(flatten)
        ipage.toList.flatMap(flatten).find(cat contains _.category).orElse(
          ipage.toList.flatMap(w=> w.linksFrom.toList).flatMap(_.pageTo.toList).find(cat contains _.category)
        )
      }
    }

//    def map[T] (f: page.type => T) = if(isDefined) Some(f(page)) else None
  }

  def ownedPages(realm:String, cat:String) =
    utils.wikiList(iuser.toList.flatMap(_.ownedPages(realm, cat)))

  def ownedReactors(realm:String) = {
    val names = iuser.toList.flatMap(_.memberReactors)
    if(names.isEmpty) "<em>None</em>"
    else
      names.map(r=>(r, Wikis(r).count))
        .sortWith(_._2 > _._2)
        .map(t=> s"""<a href="/wikie/switchRealm/${t._1}">${t._1}</a>""")
        .mkString("", " <b>|</b> ", "")
  }

  // so you can do wix.user.isOwner
  val user = new {
    def userName = iuser.get.userName
    def firstName = iuser.get.firstName
    def email = iuser.get.emailDec
    def ename = iuser.get.ename
    def isDefined = iuser.isDefined
    def isEmpty = iuser.isEmpty
    def hasMembershipLevel(s:String) = iuser.map(_.hasMembershipLevel(s)) getOrElse false
    def id = iuser.get._id
    def isOwner = iuser.exists(u=> ipage.flatMap(_.owner).exists(_._id == u._id))
    def isClubAdmin = iuser.exists{u=>
      isDbAdmin ||
        page.findAssocWithCat(List("Club", "Pro")).map(_.wid).flatMap(Club.apply).exists(_.isAdminEmail(u.emailDec))
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

    def getExtLink (systemId:String, instanceId:String) =
      iuser.flatMap(_.profile).flatMap(_.getExtLink(irealm, systemId, instanceId)).map(_.extAccountId).mkString
  }

  /**
    * you can never remove from this or change behavior of these - there are scripts relying on this...
    *
    * @return
    */
  def OLD_DELETE_jsonBrowser = {
    """wix = {
    """ +
   s"""
      "hostport" : "${hostport}",
    """ +
      s"""
    "realm" : {
      "name" : "${irealm}"
      """ +
      (if(ipage.exists(_.included.contains("wix.realm.props"))) {
        // we can't use contentProps in the condition above, because inline expressions can use wix and start recursing
        ", \"props\" : {\n" +
        realm.props.toSeq.map {t =>
          val escaped = t._2.replaceAll("\"", "\\\\\"")
          s"""
             "${t._1}" : "$escaped}" """.stripMargin
        }.mkString (",") +
        """
          }
        """.stripMargin
      } else "") +
      """
    },
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
      "wpathnocats" : "${ipage.get.wid.wpathnocats}",
      "visibility" : "${ipage.get.visibility}"
    },
    """
      } else "") +
      (if(iuser.isDefined) {
        s"""
    "user" : {
      "userName" : "${iuser.get.userName}",
      "firstName" : "${iuser.get.firstName}",
      "organization" : "${iuser.get.organization.mkString}",
      "ename" : "${iuser.get.ename}",
      "isDefined" : ${iuser.isDefined},
      "isEmpty" : ${iuser.isEmpty},
      "isClubMember" : ${user.isClubMember},
      "isClubAdmin" : ${user.isClubAdmin},
      "isClubCoach" : ${user.isClubCoach},
      "isRegistered" : ${user.isRegistered},
      "id" : "${iuser.get._id.toString}",
      "perms" : ["${iuser.get.perms.mkString("\",\"")}"],
      "level" : "${iuser.get.membershipLevel}",
      "levelDesc" : "${getLevelDesc}",
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

  /**
    * you can never remove from this or change behavior of these - there are scripts relying on this...
    *
    * @return
    */
  def jsonBrowser = {
    val res = "wix = " + razie.js.tojsons( ListMap( // preserve order to have query at end - no particular reason

      "hostport" -> hostport,

      "realm" -> Map (
        "name" -> irealm,
        "props" -> (
          if(ipage.exists(_.included.contains("wix.realm.props"))) {
            // we can't use contentProps in the condition above, because inline expressions can use wix and start recursing
//            realm.props
            realm
                .props
                .filter(t=> ! t._1.contains("pwd"))
                .filter(t=> ! t._1.contains("password"))
                .filter(t=> ! t._1.contains("secret"))
                .map {t =>
              val escaped = t._2 //org.json.JSONObject.quote(t._2) //._2.replaceAll("\"", "\\\\\"")
              (t._1, escaped)
            }
          } else "undefined"
          )
      ),

      "page" -> (if(ipage.isDefined) Map(
        "name" -> ipage.get.name,
        "category" -> ipage.get.category,
        "isModerated" -> ipage.flatMap(_.attr("moderator")).exists(_.length > 0),
        "isDefined" -> ipage.isDefined,
        "isEmpty" -> ipage.isEmpty,
        "wid" -> ipage.get.wid.wpath,
        "wpath" -> ipage.get.wid.wpath,
        "wpathnocats" -> ipage.get.wid.wpathnocats,
        "visibility" -> ipage.get.visibility
      ) else "undefined"
        ),

      "user" -> (if(iuser.isDefined) Map(
        "userName" -> iuser.get.userName,
        "firstName" -> iuser.get.firstName,
        "ename" -> iuser.get.ename,
        "email" -> iuser.get.emailDec,
        "isDefined" -> iuser.isDefined,
        "isEmpty" -> iuser.isEmpty,
        "isClubMember" -> user.isClubMember,
        "isClubAdmin" -> user.isClubAdmin,
        "isClubCoach" -> user.isClubCoach,
        "isRegistered" -> user.isRegistered,
        "id" -> iuser.get._id.toString,
        "perms" -> iuser.get.perms.toList,
        "level" -> iuser.get.membershipLevel,
        "levelDesc" -> getLevelDesc,
        "groups" -> iuser.get.groups.map(_.name).toList
      ) else "undefined"
        ),

      "query" -> query
    )) + ";\n"
//    )).toString(2) + ";\n"
    res
  }


  def json = {
    jsonBrowser +
      """wix.utils = new (Java.type("api.WixUtils"))(wixj);"""
  }

  // todo  - customize per realm
  def getLevelDesc = iuser.get.membershipLevel match {
    case Perm.Unobtanium.s => " (expert content)"
    case Perm.Platinum.s => " (expert and racing content)"
    case Perm.Gold.s => " (black/expert content)"
    case Perm.Basic.s => " (green&blue content)"
    case Perm.Member.s => " (free content)"
    case Perm.Moderator.s => " (local god)"
    case Perm.Expired.s => " (expired)"
    case _ => ""
  }

  def query: Map[String,String] = iquery

  object realm {
    def count = Wikis(irealm).count
    def name = irealm
    def props = WikiReactors.apply(irealm).props.props
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

  def apply (owe: Option[WikiPage], ou:Option[WikiUser], q:Map[String,String], r:String) = {
    new wix(owe, ou, q, r)
  }

  object utils {
    def countForms() = RazMongo("weForm").size
  }

  def dieselEnvFor (realm:String, ou:Option[WikiUser]) = dwix.dieselEnvFor(realm, ou)
}

class WixUtils(w:wix) {
  def countRealmUsers() = Users.findUsersForRealm(w.realm.name).size
  def countRealmPages() = Wikis(w.realm.name).count
  def countForms() = wix.utils.countForms()
  def wikiList(wids:List[WID]) =
    if(wids.isEmpty) "<em>None</em>" else "<ul>"+wids.map(x=> "<li>"+ x.ahrefRelative() + "</li>").mkString("") + "</ul>"

  def getExtLink (systemId:String, instanceId:String) = w.user.getExtLink(systemId, instanceId)

  def enc(s:String) = Sec.enc(s)
  def dec(s:String) = {
    if(w.user.isDbAdmin) Sec.dec(s)
    else "No permission"
  }
}

