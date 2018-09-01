package controllers

import com.google.inject.Singleton
import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier

import akka.cluster.Cluster
import com.mongodb.casbah.Imports.{DBObject, IntOk}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.grater
import difflib.DiffUtils
import mod.notes.controllers.NotesLocker
import mod.snow.RK
import org.json.{JSONArray, JSONObject}
import play.api.libs.concurrent.Akka
import play.api.libs.json.JsObject
import play.twirl.api.Html
import razie.db.{RCreate, RMany, RazMongo, WikiTrash}
import razie.db.RazSalatContext.ctx
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc.{Action, AnyContent, Request, Result}
import razie.g.snakked
import razie.{cout, js}
import razie.wiki.{Enc, Services}
import razie.wiki.model.{Perm, WID, WikiEntry, Wikis}
import razie.wiki.admin.{Autosave, GlobalData, MailSession, SendEmail}
import razie.audit.ClearAudits
import model.{User, Users, WikiScripster}
import x.context
import razie.hosting.Website

import scala.util.Try
import razie.Snakk._
import razie.audit.{Audit, ClearAudits}
import razie.wiki.Sec._
import razie.wiki.util.DslProps

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.reflect.macros.whitebox

/** Diff and sync remote wiki copies */
//@Singleton
object AdminDiff extends AdminBase {

  case class WEAbstract (id:String, cat:String, name:String, realm:String, ver:Int, updDtm:DateTime, hash:Int, tags:String) {
    def this (we:WikiEntry) = this(we._id.toString, we.category, we.name, we.realm, we.ver, we.updDtm, we.content.hashCode, we.tags.mkString)
    def j = js.tojson(Map("id"->id, "cat"->cat, "name"->name, "realm"->realm, "ver"->ver, "updDtm" -> updDtm, "hash" -> hash.toString, "tags" -> tags))
  }

  def wput(reactor:String) = FAD { implicit au =>
    implicit errCollector => implicit request =>
      Ok("")
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def drafts(reactor:String) = FAUR { implicit stok =>

    var l = Autosave.activeDrafts(stok.au.get._id).toList

    // filter some internal states
    l = l.filter(_.name != "")

    // filter those with content similar
    val ok = l.filter(x=> WID.fromPath(x.name).exists(_.content.exists(_ != x.contents("content"))))
    val neq = l.filter(x=> WID.fromPath(x.name).exists(_.content.exists(_ == x.contents("content"))))
    val non = l.filter(x=> WID.fromPath(x.name).flatMap(_.page).isEmpty)

    val lok = ok.map(x=>(x.what, x.realm, x.name, WID.fromPath(x.name).map(_.url + "  ").mkString ))
    val lneq = neq.map(x=>(x.what, x.realm, x.name, WID.fromPath(x.name).map(_.url + "  ").mkString ))
    val lnon = non.map(x=>(x.what, x.realm, x.name, WID.fromPath(x.name).map(_.url + "  ").mkString ))

    Ok(
      Wikis.sformat(lok.size + "\n- " + lok.mkString("\n- "))+
      Wikis.sformat("neq: "+lneq.size + "\n- " + lneq.mkString("\n- "))+
      Wikis.sformat("non: "+lnon.size + "\n- " + lnon.mkString("\n- "))
    ).as("text/html")
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def wlist(reactor:String, hostname:String, me:String, cat:String) = FAUR { implicit request =>
      if (hostname.isEmpty) {
        val l =
          if(cat.length == 0)
            RMany[WikiEntry]().filter(we=> reactor.isEmpty || reactor == "all" || we.realm == reactor).map(x=>new WEAbstract(x)).toList
          else
            RMany[WikiEntry]().filter(we=> we.category == cat && (reactor.isEmpty || reactor == "all" || we.realm == reactor)).map(x=>new WEAbstract(x)).toList
        val list = l.map(_.j)
        Ok(js.tojson(list).toString).as("application/json")
      } else if(hostname != me) {
        val b = body(url(s"http://$hostname/razadmin/wlist/$reactor?me=${request.req.host}&cat=$cat").basic("H-"+request.au.get.emailDec, "H-"+request.au.get.pwd.dec))
        Ok(b).as("application/json")
      }  else {
        NotFound("same host again?")
      }
  }

  /** show the list of diffs to remote */
  private def diffById(lsrc:List[WEAbstract], ldest:List[WEAbstract]) = {
    try {
      val lnew = lsrc.filter(x=> ldest.find(y=> y.id == x.id).isEmpty)
      val lremoved = ldest.filter(x=> lsrc.find(y=> y.id == x.id).isEmpty)

      val lchanged = for(
        x <- lsrc;
        y <- ldest if y.id == x.id &&
          (
            x.ver != y.ver ||
              x.updDtm.compareTo(y.updDtm) != 0 ||
              x.name != y.name ||
              x.realm != y.realm ||
              x.cat != y.cat
            // todo compare properties as well
            )
      ) yield
        (x,
          y,
          if(x.hash == y.hash && x.tags == y.tags) "-" else if (x.ver > y.ver || x.updDtm.isAfter(y.updDtm)) "L" else "R"
        )

      (lnew,lchanged,lremoved)
    } catch {
      case x : Throwable => {
        audit("ERROR getting remote diffs", x)
        (Nil,Nil,Nil)
      }
    }
  }

  /** show the list of diffs to remote */
  private def diffByName(lsrc:List[WEAbstract], ldest:List[WEAbstract]) = {
    try {
      val lnew = lsrc.filter(x=> !ldest.exists(y=> y.name == x.name && y.cat == x.cat))
      val lremoved = ldest.filter(x=> !lsrc.exists(y=> y.name == x.name && y.cat == x.cat))

      val lchanged = for(
        x <- lsrc;
        y <- ldest if y.name == x.name && y.cat == x.cat &&
          (
            x.ver != y.ver ||
              x.updDtm.compareTo(y.updDtm) != 0
//              x.name != y.name ||
//              x.cat != y.cat ||
//              x.realm != y.realm
            // todo compare properties as well
            )
      ) yield
        (x,
          y,
          if(x.hash == y.hash && x.tags == y.tags) "-"
          else if (x.ver > y.ver || x.updDtm.isAfter(y.updDtm)) "L"
          else "R"
        )

      (lnew,lchanged,lremoved)
    } catch {
      case x : Throwable => {
        audit("ERROR getting remote diffs", x)
        (Nil,Nil,Nil)
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
  def difflist(toRealm:String, remote:String) = FAUR { implicit request =>
      try {
        // get remote list
        val b = body(url(s"http://$remote/razadmin/wlist/$toRealm").basic("H-"+request.au.get.emailDec, "H-"+request.au.get.pwd.dec))

        val gd = new JSONArray(b)
        val ldest = js.fromArray(gd).collect {
          case m : Map[_, _] => {
            val x = m.asInstanceOf[Map[String,String]]
            WEAbstract(x("id"), x("cat"), x("name"), x("realm"), x("ver").toInt, new DateTime(x("updDtm")), x("hash").toInt, x("tags"))
          }
        }

        // local list
        val lsrc = RMany[WikiEntry]().filter(we=> toRealm.isEmpty || toRealm == "all" || we.realm == request.realm).map(x=>new WEAbstract(x)).toList

        val (lnew, lchanged, lremoved) =
          if(toRealm == "all" || toRealm == request.realm)
            // diff to remote
            diffById(lsrc, ldest)
          else
            // diff to another reactor
            diffByName(lsrc, ldest)

        ROK.r admin {implicit stok=>
          views.html.admin.adminDifflist(toRealm, remote, lnew, lremoved, lchanged.sortBy(_._3))
        }
      } catch {
        case x : Throwable => {
          audit("ERROR getting remote diffs", x)
          Ok ("error " + x)
        }
      }
  }

  /** compute and show diff for a WID */
  // todo auth that user belongs to realm
  def showDiff(onlyContent:String, side:String, realm:String, target:String, iwid:WID) = FAUR { implicit request =>
    val localWid = iwid.r(if(realm == "all") iwid.getRealm else request.realm)
    val remoteWid = iwid.r(if(realm == "all") iwid.getRealm else iwid.getRealm)

      getWE(target, remoteWid)(request.au.get).fold({t=>
        val remote = t._1.content
        val patch =
        if(side=="R")
          DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)
        else
          DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)

        def x = {
          if (side == "R")
            views.html.admin.adminDiffShow(side, localWid.content.get, remote, patch, localWid.page.get, t._1)
          else
            views.html.admin.adminDiffShow(side, localWid.content.get, remote, patch, localWid.page.get, t._1)
        }

        if("yes" == onlyContent.toLowerCase)
          ROK.r noLayout {implicit stok=> x }
        else
          ROK.r admin {implicit stok=> x }

      },{err=>
        Ok ("ERR: " + err)
      })
  }

  /** create the local page on remote
    */
  // todo auth that user belongs to realm
  def applyDiffCr(toRealm:String, target:String, iwid:WID) = FAUR { implicit request =>
    val localWid = iwid.r(if(toRealm == "all") iwid.getRealm else request.realm)
    val remoteWid = iwid.r(if(toRealm == "all") iwid.getRealm else toRealm)

      try {
        val content = localWid.content.get

        val b = body(
          url(s"http://$target/wikie/setContent/${remoteWid.wpathFull}").
            form(Map("we" -> localWid.page.get.grated.toString)).
            basic("H-"+request.au.get.emailDec, "H-"+request.au.get.pwd.dec))

        Ok(b + " <a href=\"" + s"http://$target${remoteWid.urlRelative(toRealm)}" + "\">" + remoteWid.wpath + "</a>")
      } catch {
        case x : Throwable => Ok ("error " + x)
      }
  }

  // to remote
  // todo auth that user belongs to realm
  def applyDiff(realm:String, target:String, iwid:WID) = FAUR { implicit request =>
    val localWid = iwid.r(if(realm == "all") iwid.getRealm else request.realm)
    val remoteWid = iwid.r(if(realm == "all") iwid.getRealm else iwid.getRealm)

      try {
        val page = localWid.page.get
        val b = body(
          url(s"http://$target/wikie/setContent/${remoteWid.wpathFull}").
            form(Map("we" -> localWid.page.get.grated.toString)).
            basic("H-"+request.au.get.emailDec, "H-"+request.au.get.pwd.dec))

        if(b contains "ok")
          //todo redirect to list
          Ok(b + " <a href=\"" + s"http://$target${remoteWid.urlRelative(request.realm)}" + "\">" + remoteWid.wpath + "</a>")
        else
          Ok(b + " <a href=\"" + s"http://$target${remoteWid.urlRelative(request.realm)}" + "\">" + remoteWid.wpath + "</a>")
      } catch {
        case x : Throwable => Ok ("error " + x)
      }
  }

  // from remote
  // todo auth that user belongs to realm
  def applyDiff2(realm:String, target:String, iwid:WID) = FADR {implicit request =>
    val localWid = iwid.r(if(realm == "all") iwid.getRealm else request.realm)
    val remoteWid = iwid.r(if(realm == "all") iwid.getRealm else iwid.getRealm)

      getWE(target, remoteWid)(request.au.get).fold({t =>
        val b = body(url(s"http://localhost:9000/wikie/setContent/${localWid.wpathFull}").form(Map("we" -> t._2)).basic("H-"+request.au.get.emailDec, "H-"+request.au.get.pwd.dec))
        Ok(b + localWid.ahrefRelative(request.realm))
      }, {err=>
        Ok ("ERR: "+err)
      })
  }

  /** fetch remote WE */
  private def getWE(target:String, wid:WID)(implicit au:User):Either[(WikiEntry, String), String] = {
    try {
      val remote = s"http://$target/wikie/json/${wid.wpathFull}"
      val wes = body(
        url(remote).basic("H-"+au.emailDec, "H-"+au.pwd.dec))

      if(! wes.isEmpty) {
        val dbo = com.mongodb.util.JSON.parse(wes).asInstanceOf[DBObject];
        val remote = grater[WikiEntry].asObject(dbo)
        Left((remote -> wes))
      } else {
        Right("Couldnot read remote content from: " + remote)
      }
    } catch {
      case x : Throwable => {
        Right("error " + x)
      }
    }
  }

  def remoteWids (source:String, realm:String, me:String, cat:String, au:User) : List[WID] = {
    val b = body(url(s"http://$source/razadmin/wlist/$realm?me=$me&cat=$cat").basic("H-" + au.emailDec, "H-" + au.pwd.dec))

    val gd = new JSONArray(b)
    var ldest = js.fromArray(gd).collect {
      case m: Map[_, _] => {
        val x = m.asInstanceOf[Map[String, String]]
        WEAbstract(x("id"), x("cat"), x("name"), x("realm"), x("ver").toInt, new DateTime(x("updDtm")), x("hash").toInt, x("tags"))
      }
    }.map(wea => WID(wea.cat, wea.name).r(wea.realm)).toList
    ldest
  }

  //==================== initial setup and reactor import

  // is this the first tiem in a new db ?
  def isDbEmpty = RMany[User]().size <= 0

  def importDb = Action { implicit request =>
    cout << "ADMIN_IMPORT_DB"

    if(! isDbEmpty) {
      Ok ("ERR db not empty!").as("application/text")
    } else {
      lazy val query = request.queryString.map(t=>(t._1, t._2.mkString))
      lazy val form = request.asInstanceOf[Request[AnyContent]].body.asFormUrlEncoded

      def fParm(name:String) : Option[String] =
        form.flatMap(_.getOrElse(name, Seq.empty).headOption)

      def fqhParm(name:String) : Option[String] =
        query.get(name).orElse(fParm(name)).orElse(request.headers.get(name))

      val source = fqhParm("source").get
      val email = fqhParm("email").get
      val pwd = fqhParm("pwd").get
      val realm = fqhParm("realm").get

      val key = System.currentTimeMillis().toString + "87654321"

      val u = body(url(s"http://$source/dmin-getu/$key", method = "GET").basic("H-" + email, "H-" + pwd))
      val dbo = com.mongodb.util.JSON.parse(u).asInstanceOf[DBObject];
      val pu = grater[PU].asObject(dbo)
      val iau = pu.u
      val e = new admin.CypherEncryptService("",key)
      val au = iau.copy (email=e.enc(e.dec(iau.email)), pwd = e.enc(e.dec(iau.pwd)))
      au.create(pu.p)

      // init realms and cats

      // todo count is not accurate: include mixins, see the import below

      val ldest = List(
        "rk.Reactor:rk",
        "wiki.Reactor:wiki"
      ).map(x=> WID.fromPath(x).get) :::
        remoteWids (source, "rk", request.host, "Category", au) :::
        remoteWids (source, "wiki", request.host, "Category", au) :::
        remoteWids (source, realm, request.host, "", au)

      var total = ldest.size

      import scala.concurrent.ExecutionContext.Implicits.global
      Future {
        importRealm(au, request)
      }

      Ok(s"""$total""").as("application/text")
    }
  }

  /** import a realm, if not already in local */
  var lastImport : Option[String] = None

  def getLastImport () = Action { implicit request =>
    Ok(lastImport.getOrElse("No import operation performed..."))
  }

  def importRealm (au:User, request:Request[AnyContent]) = {
    cout << "ADMIN_IMPORT_REALM"

      lazy val query = request.queryString.map(t=>(t._1, t._2.mkString))
      lazy val form = request.asInstanceOf[Request[AnyContent]].body.asFormUrlEncoded

      def fParm(name:String) : Option[String] =
        form.flatMap(_.getOrElse(name, Seq.empty).headOption)

      def fqhParm(name:String) : Option[String] =
        query.get(name).orElse(fParm(name)).orElse(request.headers.get(name))

      val source = fqhParm("source").get
      val email = fqhParm("email").get
      val pwd = fqhParm("pwd").get
      val realm = fqhParm("realm").get
      val key = System.currentTimeMillis().toString

    // get mixins
    cout << "============ get mixins"
      val m = WID.fromPath(s"$realm.Reactor:$realm").map(wid=>getWE(source, wid)(au).fold({ t=>
        val m = new DslProps(Some(t._1), "website")
          .prop("mixins")
          .getOrElse(realm)
        cout << "============ mixins: "+ m
        m + ","+realm // add itself to mixins
  }, { err =>
        cout << "============ ERR-IMPORT DB: " + err
        ""
      }
    )).getOrElse(realm)

      val ldest = List(
        "rk.Reactor:rk",
        "wiki.Reactor:wiki"
      ).map(x=> WID.fromPath(x).get) :::
        remoteWids (source, "rk", request.host, "Category", au) :::
        remoteWids (source, "wiki", request.host, "Category", au) :::
        (m.split(",")
          .toList
          .distinct
          .filter(r=>r.length > 0 && !Array("rk", "wiki").contains(r))
          .flatMap(r => remoteWids (source, r, request.host, "", au))
          )

      var count = 0
      var total = ldest.size
      var countErr = 0

      razie.db.tx ("importdb", email) { implicit txn =>
        ldest.foreach { wid =>
          getWE(source, wid)(au).fold({ t =>
            count = count + 1
            lastImport = Some(s"Importing $count of $total")
            RCreate.noAudit(t._1)
//            t._1.create
          }, { err =>
            countErr = countErr + 1
            cout << "============ ERR-IMPORT DB: " + err
          })
        }
      }

      lastImport = Some(s"Done: imported... $count wikis, with $countErr errors. Please reboot the server!")
  }


  /** get list of pages - invoked by remote trying to sync */
  def getu(key:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

    val e = new admin.CypherEncryptService(key,"")
    val pu = PU(au.copy (email=e.enc(au.emailDec), pwd = e.enc(au.pwd.dec)), au.profile.get)

    val j = grater[PU].asDBObject(pu).toString

    Ok(j).as("application/json")
  }

}

/** for transfering info */
case class PU (u:User, p:model.Profile)
