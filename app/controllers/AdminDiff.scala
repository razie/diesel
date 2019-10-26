package controllers

import com.mongodb.casbah.Imports.{DBObject, _}
import com.novus.salat.grater
import difflib.DiffUtils
import model.User
import org.joda.time.DateTime
import org.json.JSONArray
import play.api.mvc.{Action, AnyContent, Request}
import razie.Snakk._
import razie.db.RazSalatContext.ctx
import razie.db.{RCreate, RMany}
import razie.wiki.Sec._
import razie.wiki.admin.Autosave
import razie.wiki.model.{WID, WikiEntry, Wikis}
import razie.wiki.util.DslProps
import razie.{cout, js}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/** Diff and sync remote wiki copies */
//@Singleton
object AdminDiff extends AdminBase {

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

    def this(x: Map[String, String]) = this(
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

  def wput(reactor: String) = FAD { implicit au =>
    implicit errCollector =>
      implicit request =>
        Ok("")
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def drafts(reactor: String) = FAUR { implicit stok =>

    var l = Autosave.activeDrafts(stok.au.get._id).toList

    def toHtml(x: Autosave) = (x.what, x.realm, x.name, WID.fromPath(x.name).map(_.url + "  ").mkString)

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
  def draftsCleanAll = FAUR { implicit stok =>

    if (!stok.au.exists(_.isAdmin)) Unauthorized("ONly for admins...")
    else {

      var l = Autosave.activeDrafts(stok.au.get._id).toList

      // filter some internal states
      l = l.filter(_.name != "")

      // filter those with content similar
      val ok = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ != x.contents("content"))))
      val neq = l.filter(x => WID.fromPath(x.name).exists(_.content.exists(_ == x.contents("content"))))
      val non = l.filter(x => WID.fromPath(x.name).flatMap(_.page).isEmpty)

      neq.map(_.delete)
      non.map(_.delete)

      Redirect("/admin/drafts/" + stok.realm)
    }
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def wlist(reactor: String, hostname: String, me: String, cat: String) = FAUR { implicit request =>
    if (hostname.isEmpty) {
      val l =
        if (cat.length == 0)
          RMany[WikiEntry]()
              .filter(we => reactor.isEmpty || reactor == "all" || we.realm == reactor)
              .map(x => new WEAbstract(x)).toList
        else
          RMany[WikiEntry]()
              .filter(we => we.category == cat && (reactor.isEmpty || reactor == "all" || we.realm == reactor))
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
        case m: Map[_, _] => {
          try {
            val x = m.asInstanceOf[Map[String, String]]
            new WEAbstract(x)
          } catch {
            case e : Throwable =>
              error("Can't parse from remote: "+m.mkString, e)
              throw e
          }
        }
      }

      // local list
      val lsrc = RMany[WikiEntry]().filter(we => toRealm.isEmpty || toRealm == "all" || we.realm == localRealm).map(x => new WEAbstract(x)).toList

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
      case x: Throwable => {
        audit("ERROR getting remote diffs", x)
        Ok("error " + x)
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
        if (side == "R")
          DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)
        else
          DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)

      def x = {
        if (side == "R")
          views.html.admin.adminDiffShow(side, localWid.content.get, remote, patch, localWid.page.get, t._1)
        else
          views.html.admin.adminDiffShow(side, localWid.content.get, remote, patch, localWid.page.get, t._1)
      }

      def diffTable = s"""<small>${views.html.admin.diffTable(side, patch, Some(("How", "Local", "Remote")))}</small>"""

      if ("yes" == onlyContent.toLowerCase) {
        val url = routes.AdminDiff.showDiff("no", side, localRealm, toRealm, target, iwid)
        Ok(
          s"""<a href="$url">See separate</a><br>""" + diffTable
        )
      } else {
        ROK.r admin { implicit stok => x }
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
        case x: Throwable => Ok("error " + x)
      }
    } else {
      Unauthorized(s"You are not a member or project $toRealm...")
    }
  }

  // from remote to local
  // todo auth that user belongs to realm
  def applyDiffFrom(localRealm: String, toRealm:String, target: String, iwid: WID) = FADR { implicit request =>
    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

    getWE(target, remoteWid)(request.au.get).fold({ t =>
      val b = body(
        url(request.hostUrlBase + s"/wikie/setContent/${localWid.wpathFull}")
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

  def remoteWids(source: String, realm: String, me: String, cat: String, au: User): List[WID] = {
    val b = body(url(s"http://$source/razadmin/wlist/$realm?me=$me&cat=$cat").basic("H-" + au.emailDec, "H-" + au.pwd.dec))

    val gd = new JSONArray(b)
    cdebug << s"remoteWids JS $realm: \n  " + gd.toString(2)
    var ldest = js.fromArray(gd).collect {
      case m: Map[_, _] => {
        val x = m.asInstanceOf[Map[String, String]]
        new WEAbstract(x)
      }
    }.map(wea => WID(wea.cat, wea.name).r(wea.realm)).toList
    cdebug << s"remoteWids $realm: \n  " + ldest.mkString("\n  ")
    ldest
  }

  //==================== initial setup and reactor import

  // is this the first time in a new db ?
  def isDbEmpty:Boolean = RMany[User]().size <= 0

  def importDbImpl(implicit request:Request[AnyContent]) = {
    clog << "ADMIN_IMPORT_DB_IMPL"

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

      // init realms and cats

      // todo count is not accurate: include mixins, see the import below

      val ldest = List(
        "rk.Reactor:rk",
        "wiki.Reactor:wiki"
      ).map(x => WID.fromPath(x).get) :::
//        remoteWids(source, "rk", request.host, "Category", au) :::
//        remoteWids(source, "wiki", request.host, "Category", au) :::
          remoteWids(source, "rk", request.host, "", au) :::
          remoteWids(source, "wiki", request.host, "", au) :::
          remoteWids(source, realm, request.host, "", au)

      var total = ldest.size

      import scala.concurrent.ExecutionContext.Implicits.global
      (
        total,
        Future {
          importRealm(au, request)
        }
      )
  }

  def importDbSync = Action.async { implicit request =>
    clog << "ADMIN_IMPORT_DB"

    if (!isDbEmpty) {
      Future.successful {
        Ok("ERR db not empty!").as("application/text")
      }
    } else {
      val res = importDbImpl(request)

      import scala.concurrent.ExecutionContext.Implicits.global
      res._2.map { i=>
          Future {
            Thread.sleep(2)
            System.exit(7)
          }
        Ok(
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      IMPORTED CONFIGURATION FROM REMOTE...           *
             |*                                                      *
             |*      $i errors... RESTARTING THE PROCESS...           *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin).as("application/text")
      }
    }
  }

  def importDb = Action { implicit request =>
    clog << "ADMIN_IMPORT_DB"

    if (!isDbEmpty) {
      Ok("ERR db not empty!").as("application/text")
    } else {
      val res = importDbImpl(request)

      Ok(s"""${res._1}""").as("application/text")
    }
  }

  /** import a realm, if not already in local */
  var lastImport: Option[String] = None

  def getLastImport() = Action { implicit request =>
    Ok(lastImport.getOrElse("No import operation performed..."))
  }

  /** import a realm from remote */
  def importRealm(au: User, request: Request[AnyContent]) = {
    clog << "ADMIN_IMPORT_REALM"

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
    val key = System.currentTimeMillis().toString

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
//      remoteWids(source, "rk", request.host, "Category", au) :::
//      remoteWids(source, "wiki", request.host, "Category", au) :::
      remoteWids(source, "rk", request.host, "", au) :::
      remoteWids(source, "wiki", request.host, "", au) :::
      (reactors.split(",")
        .toList
        .distinct
        .filter(r => r.length > 0 && !Array("rk", "wiki").contains(r))
        .flatMap(r => remoteWids(source, r, request.host, "", au))
        )

    var count = 0
    var total = ldest.size
    var countErr = 0

    // wid, error
    val errors = new ListBuffer[(WID, String)]()

    razie.db.tx("importdb", email) { implicit txn =>
      ldest.foreach { wid =>
        getWE(source, wid)(au).fold({ t =>
            // success
          count = count + 1
          lastImport = Some(s"Importing $count of $total (rk,wiki,$reactors)")
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
    DieselSettings(None, None, "isimulateHost", s"$realm.dieselapps.com").set

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


  /** get list of pages - invoked by remote trying to sync */
  def getu(key: String) = FAU { implicit au =>
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
