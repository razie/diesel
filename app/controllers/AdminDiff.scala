/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports.{DBObject, _}
import com.novus.salat.grater
import com.razie.pub.comms.CommRtException
import difflib.DiffUtils
import java.io.{BufferedWriter, File, FileWriter, IOException}
import model.User
import org.joda.time.DateTime
import org.json.JSONArray
import play.api.mvc.{Action, AnyContent, Request}
import razie.Snakk._
import razie.db.RazSalatContext.ctx
import razie.db.{RCreate, RMany}
import razie.hosting.WikiReactors
import razie.wiki.Config
import razie.wiki.Sec._
import razie.wiki.admin.Autosave
import razie.wiki.model.{WID, WikiEntry}
import razie.wiki.util.DslProps
import razie.{Logging, js}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Try

/** metadata about a we */
case class WEAbstract(id: String, cat: String, name: String, realm: String, ver: Int, updDtm: DateTime, hash: Int, tags: String, drafts:Int) {

  def this(we: WikiEntry) = this(
    we._id.toString,
    we.category,
    we.name,
    we.realm,
    we.ver,
    we.updDtm,
    we.content.hashCode,
    we.tags.mkString,
    Autosave.allDrafts(we.wid).toList.size
  )

  def this(x: collection.Map[String, String]) = this(
    x("id"),
    x("cat"),
    x("name"),
    x("realm"),
    x("ver").toInt,
    new DateTime(x("updDtm")),
    x("hash").toInt,
    x("tags"),
    x("drafts").toInt
  )

  def j = js.tojson(Map("id" -> id, "cat" -> cat, "name" -> name, "realm" -> realm, "ver" -> ver.toString, "updDtm" -> updDtm, "hash" -> hash.toString, "tags" -> tags, "drafts" -> drafts.toString))
}

/** Diff and sync remote wiki copies */
@Singleton
class AdminDiff extends AdminBase with Logging {

  def wput(reactor: String) = FAD { implicit au =>
    implicit errCollector =>
      implicit request =>
        Ok("")
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def adminDrafts(reactor: String) = FAUR { implicit stok =>

    var l = Autosave.activeDrafts(stok.au.get._id).toList

    def toHtml(x: Autosave) =
      (
          x.what,
          x.realm,
          x.name,
          WID.fromPath(x.name).map("[" + _.urlForEdit + "]  ").mkString,
          if (stok.au.exists(_.isAdmin)) s"""  (**[/razadmin/db/col/del/Autosave/${x._id.toString} del]**)"""
          else ""
      )

    // filter some internal states
    l = l.filter(_.name != "")

    // only I see all realms
    if (!stok.au.exists(_.isAdmin))
      l = l.filter(_.realm == reactor)

    // filter those with content similar
    val ok = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ != x.contents("content"))))
    val neq = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ == x.contents("content"))))
    val non = l.filter(x => WID.fromPath(x.name).flatMap(_.page).isEmpty)

    val lok = ok.map(toHtml)
    val lneq = neq.map(toHtml)
    val lnon = non.map(toHtml)

    // collect duplos
    val d = new mutable.HashMap[String, List[String]]()
    // check that there's no screwup
    RMany[WikiEntry]().toList.foreach { we =>
      val k = we.wid.wpathFull

      if (d.contains(k)) {
        d.put(k, (we._id.toString) :: d(k))
      } else {
        d.put(k, (we._id.toString) :: Nil)
      }
    }

    val duplos = d.filter(_._2.size > 1)

    ROK.r admin { implicit stok =>
      views.html.admin.adminDrafts(lok.sortBy(_._2), lneq, lnon, duplos)
    }
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def adminDraftsCleanAll = FAUR { implicit stok =>

    if (!stok.au.exists(_.isAdmin)) Unauthorized("ONly for admins...")
    else {

      var l = Autosave.activeDrafts(stok.au.get._id).toList

      // filter some internal states
      l = l.filter(_.name != "")

      // filter those with content similar
      val ok = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ != x.contents("content"))))
      val neq = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ == x.contents("content"))))
      val non = l
          .filter(x =>
            WID
                .fromPath(x.name)
                .filter(_.cat != "Fiddle")
                .filter(_.cat != "Fiddle")
                .flatMap(_.page).isEmpty)

      neq.map(_.delete)
      non.map(_.delete)

      Redirect("/admin/drafts/" + stok.realm)
    }
  }

  /** get list of pages for realm - invoked by remote trying to sync */
  private def localwlist(reactor: String, cat: String) = {
        val l =
          if (cat.length == 0)
            RMany[WikiEntry]()
                .filter(we => reactor.isEmpty || reactor == "all" || we.realm == reactor)
          else
            RMany[WikiEntry]()
                .filter(we => we.category == cat && (reactor.isEmpty || reactor == "all" || we.realm == reactor))
        l.toList
  }

  /** get list of pages for realm - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def wlist(reactor: String, hostname: String, me: String, cat: String) = FAUPRAPI(isApi=true) {
    implicit request =>
    if (hostname.isEmpty) {
      val l = localwlist(reactor, cat)
          .map(x => new WEAbstract(x)).toList
      val list = l.map(_.j)
      Ok(js.tojson(list).toString).as("application/json")
    } else if (hostname != me) {
      val b = body(url(s"http://$hostname/razadmin/wlist/$reactor?me=${request.req.host}&cat=$cat").basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))
      Ok(b).as("application/json")
    } else {
      NotFound("same host again?")
    }
  }

  /** show the list of diffs to remote */
  private def diffById(lsrc: List[WEAbstract], ldest: List[WEAbstract]) = {
    try {
      val lnew = lsrc.filter(x => ldest.find(y => y.id == x.id).isEmpty)
      val lremoved = ldest.filter(x => lsrc.find(y => y.id == x.id).isEmpty)

      val lchanged = for (
        x <- lsrc;
        y <- ldest if y.id == x.id &&
          (
            x.ver != y.ver ||
              x.updDtm.compareTo(y.updDtm) != 0 ||
                x.hash != y.hash ||
              x.name != y.name ||
              x.realm != y.realm ||
              x.cat != y.cat
            // todo compare properties as well
            )
      ) yield
        (x,
          y,
          if (x.hash == y.hash && x.tags == y.tags) "-" else if (x.ver > y.ver || x.updDtm.isAfter(y.updDtm)) "L" else "R"
        )

      (lnew, lchanged, lremoved)
    } catch {
      case x: Throwable => {
        audit("ERROR getting remote diffs", x)
        (Nil, Nil, Nil)
      }
    }
  }

  /** show the list of diffs to remote */
  private def diffByName(lsrc: List[WEAbstract], ldest: List[WEAbstract]) = {
    try {
      val lnew = lsrc.filter(x => !ldest.exists(y => y.name == x.name && y.cat == x.cat))
      val lremoved = ldest.filter(x => !lsrc.exists(y => y.name == x.name && y.cat == x.cat))

      val lchanged = for (
        x <- lsrc;
        y <- ldest if y.name == x.name && y.cat == x.cat &&
          (
            x.ver != y.ver ||
              x.updDtm.compareTo(y.updDtm) != 0 ||
              x.hash != y.hash
            //              x.name != y.name ||
            //              x.cat != y.cat ||
            //              x.realm != y.realm
            // todo compare properties as well
            )
      ) yield
        (x,
          y,
          if (x.hash == y.hash && x.tags == y.tags) "-"
          else if (x.ver > y.ver || x.updDtm.isAfter(y.updDtm)) "L"
          else "R"
        )

      (lnew, lchanged, lremoved)
    } catch {
      case x: Throwable => {
        audit("ERROR getting remote diffs", x)
        (Nil, Nil, Nil)
      }
    }
  }

  /** show the list of diffs to remote
    *
    * diff THIS realm with remote realm. If remote is ALL, then we diff ALL realms
    *
    * @param toRealm - remote reactor, "all" for all
    * @param remote
    * @return
    */
  // todo auth that user belongs to realm
  def difflist(localRealm:String, toRealm: String, remote: String) = FAUR { implicit request =>
    try {
      // get remote list
      val b = body(url(s"http://$remote/razadmin/wlist/$toRealm").basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))

      val gd = new JSONArray(b)
      val ldest = js.fromArray(gd).collect {
        case m: collection.Map[_, _] => {
          try {
            val x = m.asInstanceOf[collection.Map[String, String]]
            new WEAbstract(x)
          } catch {
            case e: Throwable =>
              error("Can't parse from remote: " + m.mkString, e)
              throw e
          }
        }
      }.toList

      // local list
      val lsrc = RMany[WikiEntry]()
          .filter(we => toRealm.isEmpty || toRealm == "all" || we.realm == localRealm)
          .map(x => new WEAbstract(x))
          .toList

      val (lnew, lchanged, lremoved) =
        if (toRealm == "all" || toRealm == localRealm)
        // diff to remote
          diffById(lsrc, ldest)
        else
        // diff to another reactor
          diffByName(lsrc, ldest)

      ROK.r admin { implicit stok =>
        views.html.admin.adminDifflist(localRealm, toRealm, remote, lnew, lremoved, lchanged.sortBy(_._3))
      }
    } catch {
      case x: CommRtException if x.httpCode == 401 => {
        audit(s"ERROR getting remote diffs ${x.getMessage} ", x)
        Ok(s"error HTTP 401 (Unauthorized) - did you change your password locally or remotely?\n\nError details: $x ")
      }
      case x: CommRtException => {
        audit(s"ERROR getting remote diffs ${x.getMessage} ", x)
        Ok(s"error HTTP ${x.httpCode} \n\nerror details: $x ")
      }
      case x: Throwable => {
        audit("ERROR getting remote diffs", x)
        Ok("Unknown error\n\ndetails: " + x)
      }
    }
  }

  /** compute and show diff for a WID */
  // todo auth that user belongs to realm
  def showDiff(onlyContent: String, side: String, localRealm:String, toRealm: String, target: String, iwid: WID) = FAUR { implicit request =>
    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

    getWE(target, remoteWid)(request.au.get).fold({ t =>
      val remote = t._1.content
      val patch =
          DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)

      def diffTable = s"""<small>${views.html.admin.diffTable(side, patch, Some(("How", "Local", "Remote")))}</small>"""

      if ("yes" == onlyContent.toLowerCase) {
        // only content diff, using diffTable
        val url = routes.AdminDiff.showDiff("no", side, localRealm, toRealm, target, iwid)
        Ok(
          s"""<a href="$url">See separate</a><br>""" + diffTable
        )
      } else {
        // full diff, not just content
        ROK.r admin { implicit stok => {
          views.html.admin.adminDiffShow(side, localWid.content.get, remote, patch, localWid.page.get, t._1)
        }
        }
      }

    }, { err =>
      Ok("ERR: " + err)
    })
  }

  // to remote
  def applyDiffTo(localRealm:String, toRealm: String, target: String, iwid: WID) = FAUR { implicit request =>
    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

    if (request.au.exists(_.realms.contains(toRealm)) || request.au.exists(_.isAdmin)) {
      try {
        val page = localWid.page.get

        val b = body(
          url(s"http://$target/wikie/setContent/${remoteWid.wpathFull}").
            form(Map("we" -> page.grated.toString)).
            basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))

        // b contains ok - is important
        Ok(b + " <a href=\"" + s"http://$target${remoteWid.urlRelative(request.realm)}" + "\">" + remoteWid.wpath + "</a>")
      } catch {
        case x: CommRtException => {
          Ok("error " + x.httpCode + " " + x.details)
        }
        case x: Throwable => Ok("error " + x)
      }
    } else {
      Unauthorized(s"You are not a member or project $toRealm...")
    }
  }

  // from remote to local
  // todo auth that user belongs to realm
  def applyDiffFrom(localRealm: String, toRealm:String, target: String, iwid: WID) = FAUR { implicit request =>
    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

    getWE(target, remoteWid)(request.au.get).fold({ t =>
      val protocol = if(request.hostUrlBase.startsWith("https")) "https" else "http"
      val b = body(
//        url(request.hostUrlBase + s"/wikie/setContent/${localWid.wpathFull}")
        // local url may be different from outside mappings and routings - it's accessed from this same server backend
        // todo still have an issue of http vs https - should this be configured?
        url(protocol + "://" + Config.hostport + s"/wikie/setContent/${localWid.wpathFull}")
          .form(Map("we" -> t._2, "remote" -> target))
          .basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec)
      )
      Ok(b + localWid.ahrefRelative(request.realm))
    }, { err =>
      Ok("ERR: " + err)
    })
  }

  /** fetch remote WE */
  private def getWE(target: String, wid: WID)(implicit au: User): Either[(WikiEntry, String), String] = {
    try {
      val remote = s"http://$target/wikie/json/${wid.wpathFull}"
      val wes = body(
        url(remote).basic("H-" + au.emailDec, "H-" + au.pwd.dec))

      if (!wes.isEmpty) {
        val dbo = com.mongodb.util.JSON.parse(wes).asInstanceOf[DBObject];
        val remote = grater[WikiEntry].asObject(dbo)
        Left((remote -> wes))
      } else {
        Right("Couldnot read remote content from: " + remote)
      }
    } catch {
      case x: Throwable => {
        Right("error " + x)
      }
    }
  }

  def wlist(source: String, realm: String, me: String, cat: String, au: User): List[WID] = {
    val b = body(
      url(s"http://$source/razadmin/wlist/$realm?me=$me&cat=$cat")
          .basic("H-" + au.emailDec, "H-" + au.pwd.dec))

    val gd = new JSONArray(b)
    cdebug << s"remoteWids JS $realm: \n  " + gd.toString(2)
    var ldest = js.fromArray(gd).collect {
      case m: collection.Map[_, _] => {
        val x = m.asInstanceOf[collection.Map[String, String]]
        new WEAbstract(x)
      }
    }.map(wea => WID(wea.cat, wea.name).r(wea.realm)).toList
    cdebug << s"remoteWids $realm: \n  " + ldest.mkString("\n  ")
    ldest
  }

  def remoteWids(source: String, realm: String, me: String, cat: String, au: User): List[WID] = {
    val b = body(
      url(s"http://$source/razadmin/wlist/$realm?me=$me&cat=$cat")
          .basic("H-" + au.emailDec, "H-" + au.pwd.dec))

    val gd = new JSONArray(b)
    cdebug << s"remoteWids JS $realm: \n  " + gd.toString(2)
    var ldest = js.fromArray(gd).collect {
      case m: collection.Map[_, _] => {
        val x = m.asInstanceOf[collection.Map[String, String]]
        new WEAbstract(x)
      }
    }.map(wea => WID(wea.cat, wea.name).r(wea.realm)).toList
    cdebug << s"remoteWids $realm: \n  " + ldest.mkString("\n  ")
    ldest
  }

  //==================== initial setup and reactor import

  // is this the first time in a new db ?
  def isDbEmpty:Boolean = RMany[User]().size <= 0

  /** is this a remote user - email and password match something on remote? */
  def isRemoteUser(email:String, pwd:String) = {
    clog << "ADMIN_isRemoteUser"

    val source = "www.dieselapps.com"

    // key used to encrypt/decrypt transfer
    val key = System.currentTimeMillis().toString + "87654321"

    // first get the user and profile and create them locally
    Try {
      val u = body(url(s"http://$source/dmin-isu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
      u.contains("yes")
    }.getOrElse(false)
  }

  /** import a remote user if the email and password match */
  def importRemoteUser(email:String, pwd:String) = {
    clog << "ADMIN_importRemoteUser"

    log("IMPORT USER")

    val source = "www.dieselapps.com"

    // key used to encrypt/decrypt transfer
    val key = System.currentTimeMillis().toString + "87654321"

    // first get the user and profile and create them locally
    val u = body(url(s"http://$source/dmin-getu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
    val dbo = com.mongodb.util.JSON.parse(u).asInstanceOf[DBObject];
    val pu = grater[PU].asObject(dbo)
    val iau = pu.u
    val e = new admin.CypherEncryptService("", key)
    // re=encrypt passworkd with the local key
    val au = iau.copy(email = e.enc(e.dec(iau.email)), pwd = e.enc(e.dec(iau.pwd)))

    au.create(pu.p)

    true
  }

  /** actual import implementation */
  def importDbImpl(implicit stok:RazRequest) = {

    clog << "ADMIN_IMPORT_DB_IMPL"

    log("IMPORT DB")

    val realm = stok.fqhParm("realm").get
    val source = stok.fqhParm("source").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get

    // temp key used to encrypt/decrypt transfer
    val key = System.currentTimeMillis().toString + "87654321"

    // user already local or import from remote?
    val au = if(stok.au.isEmpty) {
      clog << "ADMIN_IMPORT_DB_IMPL import user"

      // first get the user and profile and create them locally
      val u = body(url(s"http://$source/dmin-getu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
      val dbo = com.mongodb.util.JSON.parse(u).asInstanceOf[DBObject];
      val pu = grater[PU].asObject(dbo)
      val iau = pu.u
      val e = new admin.CypherEncryptService("", key)
      // re=encrypt passworkd with the local key
      val au = iau.copy(email = e.enc(e.dec(iau.email)), pwd = e.enc(e.dec(iau.pwd)))

      au.create(pu.p)
      au
    } else {
      stok.au.get
    }

    // init realms and cats

    // todo count is not accurate: include mixins, see the import below

    val ldest = List(
      "rk.Reactor:rk",
      "wiki.Reactor:wiki"
    ).map(x => WID.fromPath(x).get) :::
//        remoteWids(source, "rk", request.host, "Category", au) :::
//        remoteWids(source, "wiki", request.host, "Category", au) :::
        remoteWids(source, "rk", stok.req.host, "", au) :::
        remoteWids(source, "wiki", stok.req.host, "", au) :::
        remoteWids(source, realm, stok.req.host, "", au)

    var total = ldest.size

    import scala.concurrent.ExecutionContext.Implicits.global
    (
        total,
        Future {
          importRealm(au, stok)
        }
    )
  }

  /** main entry point for importing a remote db */
  def importDbApi = Action { implicit request =>
    clog << "ADMIN_IMPORT_DB"

    if (!isDbEmpty) {
      Ok("ERR db not empty!").as("application/text")
    } else {
      val res = importDbImpl(new RazRequest(request))

      Ok(s"""${res._1}""").as("application/text")
    }
  }

  /** import remote db - called from curl, prints nice */
  def importDbSync = Action.async { implicit request =>
    clog << "ADMIN_IMPORT_DB"

    if (!isDbEmpty) {
      Future.successful {
        Ok("ERR db not empty!").as("application/text")
      }
    } else {
      val res = importDbImpl(new RazRequest(request))

      import scala.concurrent.ExecutionContext.Implicits.global

      res._2.map { i =>
        if (!(razRequest(request).fqParm("restart", "yes") == "no")) {
          Future {
            Thread.sleep(2)
            System.exit(7)
          }
        }
        Ok(
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      IMPORTED CONFIGURATION FROM REMOTE...           *
             |*            Total ${res._1}  wikis                    *
             |*      $i errors... RESTARTING THE PROCESS...          *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin).as("application/text")
      }
    }
  }

  /** importing a remote realm, in an existing local db */
  def importRealmApi = FAUR { implicit request =>
    clog << "ADMIN_IMPORT_REALM"
    val res = importRealm(request.au.get, request, false)
    Ok(s"""ERRORS: ${res}""").as("application/text")
  }

  /** importing a remote realm, in an existing local db */
  def importRealmSync = RAction.async { implicit request =>
    clog << "ADMIN_IMPORT_REALM"

    if (isDbEmpty) {
      Future.successful {
        Ok("ERR db is empty!").as("application/text")
      }
    } else {

      import scala.concurrent.ExecutionContext.Implicits.global
      val res = Future {
        importRealm(request.au.get, request, false)
      }

      import scala.concurrent.ExecutionContext.Implicits.global

      res.map { i =>
        if (!(request.fqParm("restart", "yes") == "no")) {
          Future {
            Thread.sleep(2)
            System.exit(7)
          }
        }

        Ok(
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      IMPORTED CONFIGURATION FROM REMOTE...           *
             |*            Total ??? wikis                           *
             |*      $i errors... RESTARTING THE PROCESS...          *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin).as("application/text")
      }
    }
  }

  /** import a realm, if not already in local */
  var lastImport: Option[String] = None

  def getLastImport() = Action { implicit request =>
    Ok(lastImport.getOrElse("No import operation performed..."))
  }

  /** import a realm from remote
    *
    * @param au
    * @param stok
    * @param setAsDefault
    * @return the error count
    */
  def importRealm(au: User, stok: RazRequest, setAsDefault: Boolean = true) = {

    val source = stok.fqhParm("source").get
    val realm = stok.fqhParm("realm").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get
    val key = System.currentTimeMillis().toString

    clog << s"ADMIN_IMPORT_REALM $realm from source:$source"

    // get mixins
    clog << "============ get mixins"
    val reactors = WID.fromPath(s"$realm.Reactor:$realm").map(wid => getWE(source, wid)(au).fold({ t =>
      val m = new DslProps(Some(t._1), "website,properties")
          .prop("mixins")
          .getOrElse(realm)
      clog << "============ mixins: " + m
      m + "," + realm // add itself to mixins
    }, { err =>
      clog << "============ ERR-IMPORT DB: " + err
      ""
    }
    )).getOrElse(realm)

    val ldest = List(
      "rk.Reactor:rk",
      "wiki.Reactor:wiki"
    ).map(x => WID.fromPath(x).get) :::
        (
            "rk" :: "wiki" :: (reactors.split(",").toList)
            )
            .distinct
            .filter(r => r.length > 0 && {
              // filter out already local reactors
              val res = WikiReactors.findWikiEntry(r).isEmpty
              if(res) {
                clog << s"*************** SKIPPING $r - already local"
              }
              res
            })
            .flatMap(r => remoteWids(source, r, stok.req.host, "", au))

    var count = 0
    var total = ldest.size
    var countErr = 0

    // wid, error
    val errors = new ListBuffer[(WID, String)]()

    razie.db.tx("importdb", email) { implicit txn =>
      ldest.foreach { wid =>

        log("IMPORTING: " + wid.wpath)

        getWE(source, wid)(au).fold({ t =>
          // success
          count = count + 1
          val realms = "rk,wiki,$reactors".split(",").distinct.mkString(",")
          lastImport = Some(s"Importing $count of $total ($realms)")
          RCreate.noAudit(t._1)
          //            t._1.create
        }, { err =>
          // failure
          errors.append((wid, err))
          countErr = countErr + 1
          clog << "============ ERR-IMPORT DB: " + err
        })
      }
    }

    // remember who I am supposed to be
    if (setAsDefault) {
      DieselSettings(None, None, "isimulateHost", s"$realm.dieselapps.com").set
    }

    lastImport = Some(
      s"""Done: imported... $count wikis, with $countErr errors. Please reboot the server!
         |<br>
         |To reboot the server, go back to the docker terminal where you started this container,
         |stop it (^C) and start it again.
         |<br><br>
         |Warnings and errors (permission errors are ok):<br>
         |<small>
         |${errors.mkString("<br>")}
         |</small>
         """.stripMargin
    )
    countErr
  }

  /** import a realm from remote */
  def listTopics(realm:String, only:String) = FAUPRAPI(isApi=true) {implicit request=>
    // get mixins
    clog << "============ get mixins"

    var reactors = WID
        .fromPath(s"$realm.Reactor:$realm")
        .flatMap(_.page)
        .map { we =>
      val m = new DslProps(Some(we), "website,properties")
          .prop("mixins")
          .getOrElse(realm)
      clog << "============ mixins: " + m
      m + "," + realm // add itself to mixins
    }
        .getOrElse(realm)

    // not all mixins, just top reactor
    reactors = realm

    val ldest =
    // not all mixins, just top reactor
//      List(
//      "rk.Reactor:rk",
//      "wiki.Reactor:wiki"
//    ).map(x => WID.fromPath(x).get) :::
//        localwlist("rk", "").map(_.wid) :::
//        localwlist("wiki", "").map(_.wid) :::
      (
          reactors
              .split(",")
              .toList
              .distinct
              .filter(r => r.length > 0 && !Array("rk", "wiki").contains(r))
              .flatMap(r =>
                localwlist(r, "").map(_.wid)
              )
          )

    Ok(ldest.map(_.wpathFull).mkString("\n"))
  }

  // WIP
  def exportDbSync: Action[AnyContent] = Action.async { implicit request =>
    val au = auth(request).filter(_.isActive)

    clog << "ADMIN_EXPORT_DB"

    val realm = request.queryString.map(t => (t._1, t._2.mkString)).get("realm").toString

    if (au.isEmpty || realm.length <= 0 || !au.exists(_.realms.contains(realm))) {
      Future.successful {
        Ok(s"NO ACTIVE USER for REALM $realm!").as("application/text")
      }
    } else {
      val res = exportRealm(au.get, request)

      Future.successful(
        Ok(
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      EXPORTED CONFIGURATION FROM REMOTE...           *
             |*            Total ${res._1}  wikis                    *
             |*      ${res._2} errors...                                    *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin).as("application/text")
      )
    }
  }

  /** export a realm to a folder */
  def exportRealm(au: User, request: Request[AnyContent]) = {

    lazy val query = request.queryString.map(t => (t._1, t._2.mkString))
    lazy val form = request.asInstanceOf[Request[AnyContent]].body.asFormUrlEncoded

    def fParm(name: String): Option[String] =
      form.flatMap(_.getOrElse(name, Seq.empty).headOption)

    def fqhParm(name: String): Option[String] =
      query.get(name).orElse(fParm(name)).orElse(request.headers.get(name))

    val source = fqhParm("source").get
    val email = fqhParm("email").get
    val pwd = fqhParm("pwd").get
    val realm = fqhParm("realm").get
    val folder = fqhParm("folder").get
    val mixins = fqhParm("mixins").get
    val key = System.currentTimeMillis().toString

// we should not save mixins - just local realm

    clog << s"ADMIN_EXPORT_REALM $realm"

// get mixins
    clog << "============ get mixins"
    val rmixins = "rk" :: "wiki" :: WID.fromPath(s"$realm.Reactor:$realm").flatMap(_.page).map({
      t =>
        val m = new DslProps(Some(t), "website,properties")
            .prop("mixins")
            .getOrElse(realm)
        clog << "============ mixins: " + m
        m
    }
    ).getOrElse(realm).split(",").toList ::: Nil

    val reactors = if ("yes" == mixins) realm :: rmixins else List(realm)

    // export all local entries
    val ldest = RMany[WikiEntry]()
        .filter(we => reactors.contains(we.realm))
        .toList

//    val ldest = List(
//      "rk.Reactor:rk",
//      "wiki.Reactor:wiki"
//    ).map(x => WID.fromPath(x).get) :::
//      remoteWids(source, "rk", request.host, "", au) :::
//      remoteWids(source, "wiki", request.host, "", au) :::
//      (reactors.split(",")
//        .toList
//        .distinct
//        .filter(r => r.length > 0 && !Array("rk", "wiki").contains(r))
//        .flatMap(r => remoteWids(source, r, request.host, "", au))
//        )

    var count = 0
    var total = ldest.size
    var countErr = 0

    // wid, error
    val errors = new ListBuffer[(WID, String)]()

    razie.db.tx("exportDb", email) {
      implicit txn =>
        ldest.foreach {
          we =>

            log("EXPORT: " + we.wid.wpath)

            writeFile(
              we.content,
              folder + "/" + realm,
              we.realm + "." + we.category + "." + we.name
            )

//          errors.append((wid, err))
//          countErr = countErr + 1
//          clog << "============ ERR-IMPORT DB: " + err
        }
    }

    (total, countErr)
  }

  /** write a file, make dir struct etc */
  private def writeFile(value: String, directoryName: String, fileName: String) {
    val directory = new File(directoryName)

    if (!directory.exists) {
      directory.mkdirs
    }

    val file = new File(directoryName + "/" + fileName)
    try {
      val fw = new FileWriter(file.getAbsoluteFile)
      val bw = new BufferedWriter(fw)
//      bw.write(value)
//      bw.close()
    } catch {
      case e: IOException =>
        e.printStackTrace()
    }
  }

  /** is current user active here - used with basic auth from remote, to verify before import */
  def isu(key: String) = FAUR {
    implicit request =>
      Ok("yes") // getting here means he's auth
  }

  /** get the profile of the current user, encrypted */
  def getu(key: String) = FAU {
    implicit au =>
      implicit errCollector =>
        implicit request =>

          val e = new admin.CypherEncryptService(key, "")
          val pu = PU(au.copy(email = e.enc(au.emailDec), pwd = e.enc(au.pwd.dec)), au.profile.get)

          val j = grater[PU].asDBObject(pu).toString

          Ok(j).as("application/json")
  }

}

/** for transfering info */
case class PU(u: User, p: model.Profile)
