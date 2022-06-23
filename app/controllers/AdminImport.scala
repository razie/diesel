/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  * */
package controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports.{DBObject, _}
import com.novus.salat.grater
import com.razie.pub.comms.CommRtException
import difflib.DiffUtils
import java.io.{BufferedWriter, File, FileWriter, IOException}
import model.User
import org.json.JSONArray
import play.api.mvc.{Action, AnyContent, Request}
import razie.Snakk.{url, _}
import razie.audit.Audit
import razie.db.RazSalatContext.ctx
import razie.db.{RCreate, RMany}
import razie.hosting.WikiReactors
import razie.tconf.hosting.Reactors
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
import scala.io.Source
import scala.util.Try
import special.CypherEncryptService

/** export and import realms and topics */
@Singleton
class AdminImport extends AdminBase with Logging {

  def wput(reactor: String) = FAD { implicit au =>
    implicit errCollector =>
      implicit request =>
        Ok("")
  }

  //==================== initial setup and reactor import

  /** fetch local WE (meta info)
    *
    * @param target
    * @param wid
    * @param au
    * @return either ( (entry meta parsed, unparsed) OR error msg)
    */
  private def getLocalWE(source: String, wpath:String, wid: WID)(implicit au: User): Either[(WikiEntry, String), String] = {
    try {
      val fname = wid.wpath.replaceAllLiterally(":", ".")

      val meta = io.Source.fromFile(s"$source/${fname}.meta.txt").mkString
      val contents = io.Source.fromFile(s"$source/${fname}.txt").mkString

      if (!meta.isEmpty) {
        val dbo = com.mongodb.util.JSON.parse(meta).asInstanceOf[DBObject];
        dbo.put("content", contents)
        val remote = grater[WikiEntry].asObject(dbo)
        Left((remote -> meta))
      } else {
        Right("Couldnot read remote content from: " + source)
      }
    } catch {
      case x: Throwable => {
        Right("error " + x)
      }
    }
  }

  /** get the remote wlist for a realm */
  private def remoteWids(source: String, realm: String, me: String, cat: String, au: User): List[WID] = {
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

  /** get list of pages for local realm - invoked by remote trying to sync
    *
    * @param reactor reactor list or "all" to get list for - the more the slower
    * @param cat if required, only diff one cat, generally empty
    * @return list of local abstracts
    */
  private def localwlist(reactor: String, cat: String) = {
    val rlist = reactor.split(",")
    val l =
      if (cat.length == 0)
        RMany[WikiEntry]()
            .filter(we => reactor.isEmpty || reactor == "all" || rlist.contains(we.realm))
      else
        RMany[WikiEntry]()
            .filter(we => we.category == cat && (reactor.isEmpty || reactor == "all" || rlist.contains(we.realm)))
    l.toList
  }

  /** get list of pages for realm - invoked by remote trying to sync
    *
    * todo includes auth that user belongs to realm - but it also allows to download base realms too... nothing is hidden in the base realms
    *
    * @param reactor
    * @param hostname target host - if empty this is an API call for local
    * @param me the host this is coming from, if same then is local
    * @param cat
    * @return
    */
  def wlist(reactor: String, hostname: String, me: String, cat: String) = FAUPRAPI(isApi = true, checkRealm = true) {
    implicit request =>

      if (hostname.isEmpty) {
        val l = localwlist(reactor, cat)
            .map(x => new WEAbstract(x)).toList
        val list = l.map(_.j)
        Ok(js.tojson(list).toString).as("application/json")
      } else if (hostname != me) {
        val b = body(url(s"http://$hostname/razadmin/wlist/$reactor?me=${request.req.host}&cat=$cat").basic(
          "H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))
        Ok(b).as("application/json")
      } else {
        NotFound("same host again?")
      }
  }

  // is this the first time in a new db ?
  private def isDbEmpty: Boolean = RMany[User]().size <= 0

  /** is current user active here - used with basic auth from remote, to verify before import */
  def isu(key: String) = FAUR {
    implicit request =>
      Ok("yes") // getting here means he's auth
  }

  /** get the profile of the current user, encrypted. special care taken to not reveal encrypted attributes
    *
    * this should be safe, as we're only returning the current user, meaning he/she was auth
    */
  def getu(key: String) = FAU {
    implicit au =>
      implicit errCollector =>
        implicit request =>

          Audit.logdb("ADMIN", "get user remote:" + au.userName)

          val e = new CypherEncryptService(key, "")
          val pu = PU(au.copy(email = e.enc(au.emailDec), pwd = e.enc(au.pwd.dec)), au.profile.get)

          val j = grater[PU].asDBObject(pu).toString

          Ok(j).as("application/json")
  }

  /** is this a remote user - email and password match something on remote? */
  private[controllers] def isRemoteUser(email: String, pwd: String) = {

    val source = "http://www.dieselapps.com"

    // temporary key used to encrypt/decrypt transfer
    val key = System.currentTimeMillis().toString + "87654321"

    // first get the user and profile and create them locally
    Try {
      clog << s"ADMIN_isRemoteUser $source"
      val u = body(url(s"$source/dmin-isu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
      clog << s"  Response: $u"
      u.contains("yes")
    }.getOrElse(false)
  }

  /** import a remote user if the email and password match */
  private[controllers] def importRemoteUser(email: String, pwd: String) = {

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
    val e = new CypherEncryptService("", key)
    // re=encrypt passworkd with the local key
    val au = iau.copy(email = e.enc(e.dec(iau.email)), pwd = e.enc(e.dec(iau.pwd)))

    au.create(pu.p)

    true
  }

  /** actual import implementation */
  private def importDbUser(implicit stok: RazRequest) : User = {

    clog << "ADMIN_IMPORT_DB_IMPL"

    log("IMPORT DB")

    val source = stok.fqhParm("source").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get

    // temp key used to encrypt/decrypt transfer
    val key = System.currentTimeMillis().toString + "87654321"

    // user already local or import from remote?
    val au = if (stok.au.isEmpty) {
      clog << "ADMIN_IMPORT_DB_IMPL import user"

      // first get the user and profile and create them locally
      val u = body(url(s"http://$source/dmin-getu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
      val dbo = com.mongodb.util.JSON.parse(u).asInstanceOf[DBObject];
      val pu = grater[PU].asObject(dbo)
      val iau = pu.u
      val e = new CypherEncryptService("", key)
      // re=encrypt passworkd with the local key
      val au = iau.copy(email = e.enc(e.dec(iau.email)), pwd = e.enc(e.dec(iau.pwd)))

      au.create(pu.p)
      au
    } else {
      stok.au.get
    }

    au
  }

  /** import the database from remote
    *
    * uses credentials passed in to authenticate on remote
    */
  private def importDbImpl(implicit stok: RazRequest) = {

    clog << "ADMIN_IMPORT_DB_IMPL"

    log("IMPORT DB")

    val realm = stok.fqhParm("realm").get
    val source = stok.fqhParm("source").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get

    // user already local or import from remote?
    val au = importDbUser

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
          importRealmFromSource(au, stok)
        }
    )
  }

  /** main entry point for importing a remote db
    * uses credentials passed in to authenticate on remote
    */
  def importDbApi = Action { implicit request =>
    clog << "ADMIN_IMPORT_DB"

    if (!isDbEmpty) {
      Ok("ERR db not empty!").as("application/text")
    } else {
      val res = importDbImpl(new RazRequest(request))

      Ok(s"""${res._1}""").as("application/text")
    }
  }

  /** import remote db - called from curl, prints nice
    * uses credentials passed in to authenticate on remote
    */
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

  /** import remote db - called from curl, prints nice */
  def importDbLocal = Action.async { implicit request =>
    clog << "ADMIN_IMPORT_DB LOCAL"

    if (!isDbEmpty) {
      Future.successful {
        Ok("ERR db not empty!").as("application/text")
      }
    } else {
      val stok = new RazRequest(request)
      val au = importDbUser(stok)

      val folder = stok.fqhParm("folder").get
      val wlist = Source.fromFile(s"$folder/welist.txt").getLines

      import scala.concurrent.ExecutionContext.Implicits.global
      val res = (
          wlist.size,
          Future {
            importRealmFromFolder(au, stok)
          }
      )

      res._2.map { i =>
        val rs = razRequest(request).fqParm("restart", "yes").take(2)
        if (!(razRequest(request).fqParm("restart", "yes") == "no")) {
          Future {
            Thread.sleep(2)
            System.exit(7)
          }
        }

        val cn4 = "%4d" format (res._1)
        val er4 = "%4d" format (i)

        val okres = (
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      IMPORTED CONFIGURATION FROM LOCAL...            *
             |*            Total $cn4 wikis with  $er4 errors...     *
             |*      RESTARTING THE PROCESS: $rs...                  *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin)
        log(okres)

        Ok(okres).as("application/text")
      }
    }
  }

  /** importing a remote realm, in an existing local db */
  def importRealmApi = FAUR { implicit request =>
    clog << "ADMIN_IMPORT_REALM"
    val res = importRealmFromSource(request.au.get, request, false)
    Ok(s"""ERRORS: ${res}""").as("application/text")
  }

  /** importing a remote realm, in an existing local db */
  def importRealmLocal = RAction.async { implicit request =>
    clog << "ADMIN_IMPORT_REALM LOCAL"

    if (isDbEmpty) {
      Future.successful {
        Ok("ERR db is empty!").as("application/text")
      }
    } else {

      val stok = request
      val folder = stok.fqhParm("folder").get
      val wlist = Source.fromFile(s"$folder/welist.txt").getLines
      val tot = "%4d" format wlist.size

      import scala.concurrent.ExecutionContext.Implicits.global
      val res = Future {
        importRealmFromFolder(request.au.get, request, false)
      }

      import scala.concurrent.ExecutionContext.Implicits.global

      res.map { i =>
        if (!(request.fqParm("restart", "yes") == "no")) {
          Future {
            Thread.sleep(2)
            System.exit(7)
          }
        }

        val res =(
          s"""
             |
             |********************************************************
             |*                                                      *
             |*      IMPORTED CONFIGURATION FROM REMOTE...           *
             |*            Total $tot wikis                          *
             |*      $i errors... RESTARTING THE PROCESS...          *
             |*                                                      *
             |********************************************************
             |
             |""".stripMargin)
        log(res)

        Ok(res).as("application/text")
      }
    }
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
        importRealmFromSource(request.au.get, request, false)
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

  /** import a realm from local
    *
    * @param au
    * @param stok
    * @param setAsDefault
    * @return the error count
    */
  def importRealmFromFolder(au: User, stok: RazRequest, setAsDefault: Boolean = true) = {

    val realm = stok.fqhParm("realm").get
    val folder = stok.fqhParm("folder").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get
    val key = System.currentTimeMillis().toString

    clog << s"ADMIN_IMPORT_REALM LOCAL from folder:$folder"

    // this list also includes the mixins, no need to get them elsewhere

    val wlist = Source.fromFile(s"$folder/welist.txt").getLines

    var ldest = wlist.toList

    val RK_REACTOR = "rk.Reactor:rk"
    val WIKI_REACTOR = "wiki.Reactor:wiki"

    //if the list includes these, we need to put them first
    // todo do we?
    if(ldest.contains(RK_REACTOR)) {
      ldest = RK_REACTOR :: ldest
    }

    if(ldest.contains(WIKI_REACTOR)) {
      ldest = WIKI_REACTOR :: ldest
    }

    // remove children with parents
    ldest = ldest.distinct.filter(x=> !(x contains "/"))

    var count = 0
    val total = ldest.size
    var countErr = 0

    // wid, error
    val errors = new ListBuffer[(WID, String)]()

    razie.db.tx("importdb", email) { implicit txn =>
      ldest.foreach { wpath =>

        log("IMPORTING: " + wpath)

        WID.fromPath(wpath).map { wid =>

          getLocalWE(folder, wpath, wid)(au).fold({ t =>
            // success
            count = count + 1
            lastImport = Some(s"Importing $count of $total ")

            RCreate.noAudit(t._1)
          }, { err =>
            // failure
            errors.append((wid, err))
            countErr = countErr + 1
            clog << "============ ERR-IMPORT DB: " + err
          })
        }.getOrElse {
          // failure
          errors.append((WID.empty, s"wpath can't be parsed: $wpath"))
          countErr = countErr + 1
          clog << "============ ERR-IMPORT DB: " + s"wpath can't be parsed: $wpath"
        }
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

  /** import a realm from remote
    *
    * @param au
    * @param stok
    * @param setAsDefault
    * @return the error count
    */
  def importRealmFromSource(au: User, stok: RazRequest, setAsDefault: Boolean = true) = {

    val source = stok.fqhParm("source").get
    val realm = stok.fqhParm("realm").get
    val email = stok.au.map(_.emailDec).orElse(stok.fqhParm("email")).get
    val pwd = stok.au.map(_.pwd.dec).orElse(stok.fqhParm("pwd")).get
    val key = System.currentTimeMillis().toString

    clog << s"ADMIN_IMPORT_REALM $realm from source:$source"

    // get mixins
    clog << "============ get mixins"
    val reactors = WID.fromPath(s"$realm.Reactor:$realm").map(wid => AdminDiff.getRemoteWE(source, wid)(au).fold({ t =>
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
              if (res) {
                clog << s"*************** SKIPPING $r - already local"
              }
              res
            })
            .flatMap(r => remoteWids(source, r, stok.req.host, "", au))

    var count = 0
    val total = ldest.size
    var countErr = 0

    // wid, error
    val errors = new ListBuffer[(WID, String)]()

    razie.db.tx("importdb", email) { implicit txn =>
      ldest.foreach { wid =>

        log("IMPORTING: " + wid.wpath)

        AdminDiff.getRemoteWE(source, wid)(au).fold({ t =>
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

  /** list all the topics from both realm and mixins, text/plain, one wpath per line
    *
    * @param realm comma separated list of realms
    * @param only
    * @return
    */
  def listTopics(realm: String, only: String) = FAUPRAPI(isApi = true) { implicit request =>
    // get mixins
    clog << "============ get mixins"

    val reactors =
      if("no" == only.toLowerCase) {
        WID
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
      } else realm

      // put rk,wiki first in the list
      val lulu =
        if("no" == only.toLowerCase) {
          List(
            "rk.Reactor:rk",
            "wiki.Reactor:wiki"
          ).map(x => WID.fromPath(x).get) :::
              localwlist("rk", "").map(_.wid) :::
              localwlist("wiki", "").map(_.wid)
        } else Nil

      val list = ( lulu ::: (
          reactors
              .split(",")
              .toList
              .distinct
              // todo uncomment this when uncommenting the mixins above
              //.filter(r => r.length > 0 && !Array("rk", "wiki").contains(r))
              .filter(r => r.length > 0)
              .flatMap(r =>
                localwlist(r, "").map(_.wid)
              )
          )).distinct

    Ok(list.map(_.wpathFull).mkString("\n"))
  }
}

/** for transfering info */
case class PU(u: User, p: model.Profile)
