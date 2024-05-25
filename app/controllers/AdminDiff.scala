/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  * */
package controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports.{DBObject, _}
import salat.grater
import com.razie.pub.comms.CommRtException
import difflib.DiffUtils
import java.io.{BufferedWriter, File, FileWriter, IOException}
import model.{User, WEAbstract}
import org.json.JSONArray
import play.api.mvc.{Action, AnyContent, Request}
import razie.Snakk.{url, _}
import razie.db.RazSalatContext.ctx
import razie.db.{RCreate, RMany}
import razie.hosting.WikiReactors
import razie.tconf.hosting.Reactors
import razie.wiki.{Config, Services}
import razie.wiki.Sec._
import razie.wiki.admin.Autosave
import razie.wiki.model.{UWID, WID, WikiEntry, Wikis}
import razie.wiki.util.DslProps
import razie.{Logging, js}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.io.Source
import scala.util.Try

/** Diff and sync remote wiki copies */
@Singleton
class AdminDiff extends AdminBase with Logging {

  def wput(reactor: String) = FAD { implicit au =>
    implicit errCollector =>
      implicit request =>
        Ok("")
  }

  /** get list of pages - invoked by remote trying to sync */
  def adminDrafts(reactor: String) = FAUR { implicit stok =>

    if(! stok.au.exists(u=> u.realms.contains(stok.realm) || u.isAdmin)) {
      Unauthorized("You don't belong to this realm")
    }
    else {
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
  }

  /** get list of pages - invoked by remote trying to sync */
  // todo auth that user belongs to realm
  def adminDraftsCleanAll = FAUR { implicit stok =>

    if (!stok.au.exists(_.isAdmin)) Unauthorized("Only admins can do that...")
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

  /** show the list of diffs to remote */
  private def diffById(lsrc: List[WEAbstract], ldest: List[WEAbstract]) = {
    try {
      val lnew = lsrc.filter(x => ldest.find(y => y.id == x.id).isEmpty)
      val lremoved = ldest.filter(x => lsrc.find(y => y.id == x.id).isEmpty)

      val lchanged = for (
        x <- lsrc;
        y <- ldest if y.id == x.id &&
            (
                    x.hash != y.hash ||
                    x.name != y.name ||
                    x.realm != y.realm ||
                        x.tags != y.tags ||
                    x.cat != y.cat
                // todo compare properties as well
                )
      ) yield {
          var dk = ""
          if(x.hash != y.hash) dk += "HASH."
          if(x.name != y.name) dk += "NAME."
        if(x.tags != y.tags) dk += "TAGS."
        if(x.realm != y.realm) dk += "REALM."
          if(x.cat != y.cat) dk += "CAT."

        cdebug << s"DiffBiId $dk for ${x.cat}:${x.name}"

        (x,
            y,
            if (x.hash == y.hash && x.tags == y.tags
                && x.name == y.name && x.cat != y.cat
//                && x.realm == y.realm) "-" else if (x.ver > y.ver || x.updDtm.isAfter(
                // don't use version as it may be misleading. problem may be with time sync but eh...
                && x.realm == y.realm) "-" else if (x.updDtm.isAfter(y.updDtm) || y.ver == 1) "L" else "R", // ver 1 means docker build
            dk
        )
      }

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
                    x.hash != y.hash ||
                //              x.name != y.name ||
                        x.tags != y.tags ||
                              x.cat != y.cat
                //              x.realm != y.realm
                // todo compare properties as well
                )
      ) yield {
        var dk = ""
        if(x.hash != y.hash) dk += "HASH."
        if(x.name != y.name) dk += "NAME."
        if(x.tags != y.tags) dk += "TAGS."
        if(x.realm != y.realm) dk += "REALM."
        if(x.cat != y.cat) dk += "CAT."

        cdebug << s"DiffByName $dk for ${x.cat}:${x.name}"

        (x,
            y,
            if (x.hash == y.hash && x.tags == y.tags && x.cat == y.cat) "-"
//            else if (x.ver > y.ver || x.updDtm.isAfter(y.updDtm)) "L"
            // don't use version as it may be misleading. problem may be with time sync but eh...
            else if (x.updDtm.isAfter(y.updDtm) || y.ver == 1) "L" // ver 1 means docker build
            else "R",
            dk
        )
      }

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
  def difflist(localRealm: String, toRealm: String, remote: String, remoteLabel:String) = FAUR { implicit request =>

    val REDIR = "\"" + s"/admin/difflist/$localRealm/$toRealm?remoteLabel=dieselapps&remote=https://${toRealm}.dieselapps.com" + "\""

    // get last saved remote from cookies
    if(remote.length == 0) ROK.r admin { implicit stok =>
      views.html.admin.adminDifflist(localRealm, toRealm, remote, remoteLabel, Nil, Nil, Nil)
    } else try {
      // look only at all the local realms
      val localRealms = WikiReactors
          .reactors
          .keySet
          .toList

      // send local realm list to remote so we don't download all the remote realms
      // limit at 10 so it's not too long URL
      val remoteRealms = if("all" == toRealm && localRealms.size < 10) localRealms.mkString(",") else toRealm

      val host = if(remote.startsWith("http")) remote else "http://" + remote
      // get remote list
      val b = body(url(s"$host/razadmin/wlist/$remoteRealms").basic("H-" + request.au.get.emailDec,
        "H-" + request.au.get.pwd.dec))

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
          .filter(we => toRealm.isEmpty || (toRealm == "all" && localRealms.contains(we.realm)) || we.realm == localRealm)
          .map(x => new WEAbstract(x))
          .toList

      val (lnew, lchanged, lremoved) =
        if (toRealm == "all" || toRealm == localRealm)
          // diff to remote
          diffById(lsrc, ldest)
        else
          // diff to another reactor
          diffByName(lsrc, ldest)

//      ?remoteLabel=dieselapps.com&remote=www.dieselapps.com

      val xremote = if(remote.startsWith("http")) remote else "http://" + remote
      ROK.r admin { implicit stok =>
        views.html.admin.adminDifflist(localRealm, toRealm, xremote, remoteLabel, lnew, lremoved, lchanged.sortBy(_._3))
      }
    } catch {
      case x: CommRtException if x.httpCode == 401 => {
        audit(s"ERROR getting remote diffs ${x.getMessage} ", x)
        ImATeapot(
          s"error HTTP 401 (Unauthorized) - did you change your password locally or remotely? Do you have the same " +
              s"account locally and on remote?\n\nError details: $x \n\n Fix it up or point this to <a href=${REDIR}>the cloud</a>").as("text/html")
      }
      case x: CommRtException => {
        audit(s"ERROR getting remote diffs ${x.getMessage} ", x)
        ImATeapot(s"error HTTP ${x.httpCode} \n\nerror details: $x \n\nBring it up or point this to <a href=${REDIR}>the cloud</a>").as("text/html")
      }
      case x: Throwable => {
        audit("ERROR getting remote diffs", x)
        ImATeapot(s"Unkown error <br>error details: $x <br><br>Bring it up or point this to <a href=${REDIR}>the cloud</a>").as("text/html")
      }
    }
  }

  /** compute and show diff for a WID */
  def showDiff(onlyContent: String, side: String, localRealm: String, toRealm: String, targetHost: String, iwid: WID, leftId:String, rightId:String) = FAUR
  { implicit request =>
//    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
//    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

      val localWid = Wikis.findById(leftId).map(x=> x.wid.withCachedPage(x)).get
      val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

      AdminDiff.getRemoteWE(targetHost, remoteWid)(request.au.get).fold({ t =>
      val remote = t._1.content
      val patch =
        DiffUtils.diff(localWid.content.get.lines.toList, remote.lines.toList)

      def diffTable = s"""<small>${views.html.admin.diffTable(side, patch, Some(("How", "Local (v"+localWid.page.get.ver+") upd=" + localWid.page.get.updDtm, "Remote (v"+t._1.ver + ") upd=" + t._1.updDtm)))}</small>"""

      if ("yes" == onlyContent.toLowerCase) {
        // only content diff, using diffTable
        val url = routes.AdminDiff.showDiff("no", side, localRealm, toRealm, targetHost, iwid, leftId, rightId)
        Ok(
          s"""Diffs for <span style="color:#34caee;font-weight:bold">${localWid.name}</span> - <small><a href="$url">See in separate page</a></small><br>""" + diffTable
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

  /**
    * to remote - remote checks editability for this user of that page
    *
    * @param localRealm
    * @param toRealm
    * @param targetHost
    * @param iwid local wpath
    * @return
    */
  def deleteRemote(localRealm: String, toRealm: String, targetHost: String, iwid: WID, leftId:String, rigthId:String) = FAUR { implicit request =>
    val localWid = iwid.r(if (toRealm == "all") iwid.getRealm else localRealm)
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)


    if (request.au.exists(_.realms.contains(toRealm)) || request.au.exists(_.forRealm(toRealm).isAdmin)) {
      try {
        val page = localWid.page.get

        val host = if(targetHost.startsWith("http")) targetHost else "http://" + targetHost
        val b = body(
          url(s"$host/wikie/delete2/${remoteWid.wpathFull}")
              .basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))

        // response contains ok - is important
        val s = if (b.contains("DELETED")) "ok" else "Not"
        Ok(s)
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

  /**
    * to remote - remote checks editability for this user of that page
    *
    * @param localRealm
    * @param toRealm
    * @param targetHost
    * @param iwid local wpath
    * @return
    */
  def applyDiffTo(localRealm: String, toRealm: String, targetHost: String, iwid:WID, leftId:String, rightId:String) = FAUR { implicit request =>
    val localWid = Wikis.findById(leftId).map(x=> x.wid.withCachedPage(x)).get
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)

    if (request.au.exists(_.realms.contains(toRealm)) || request.au.exists(_.isAdmin)) {
      try {
        val page = localWid.page.get

        val host = if(targetHost.startsWith("http")) targetHost else "http://" + targetHost
        val b = body(
          url(s"$host/wikie/setContent/${remoteWid.wpathFull}?id=$rightId").
              form(Map("we" -> page.grated.toString)).
              basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec))

        // b contains ok - is important
        Ok(b + " <a href=\"" + s"http://$targetHost${
          remoteWid.urlRelative(request.realm)
        }" + "\">" + remoteWid.wpath + "</a>")
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

  /** from remote to local - the local setContent call will check editability of the topic by this user
    *
    * @param localRealm
    * @param toRealm
    * @param targetHost
    * @param iwid
    * @param leftId
    * @param rightId
    * @return
    */
  def applyDiffFrom(localRealm: String, toRealm: String, targetHost: String, iwid: WID, leftId:String, rightId:String) = FAUR { implicit request =>
    val remoteWid = iwid.r(if (toRealm == "all") iwid.getRealm else toRealm)
    val localWid = if(leftId.trim.length > 0) Wikis.findById(leftId).map(x=> x.wid.withCachedPage(x)).get else remoteWid.r(localRealm)

    AdminDiff.getRemoteWE(targetHost, remoteWid)(request.au.get).fold({ t =>
      val protocol = if (request.hostUrlBase.startsWith("https")) "https" else "http"
      val b = body(
        // local url may be different from outside mappings and routings - it's accessed from this same server backend
        // todo still have an issue of http vs https - should this be configured?
        url(protocol + "://" + Services.config.hostport + s"/wikie/setContent/${localWid.wpathFull}?id=$leftId")
            .form(Map("we" -> t._2, "remote" -> targetHost))
            .basic("H-" + request.au.get.emailDec, "H-" + request.au.get.pwd.dec)
      )
      Ok(b + localWid.ahrefRelative(request.realm))
    }, { err =>
      Ok("ERR: " + err)
    })
  }

}

/** shareables */
object AdminDiff {

  /** fetch remote WE (meta info)
    *
    * @param target
    * @param wid
    * @param au
    * @return either ( (entry meta parsed, unparsed) OR error msg)
    */
  def getRemoteWE(target: String, wid: WID)(implicit au: User): Either[(WikiEntry, String), String] = {
    try {
      val host = if(target.startsWith("http")) target else "http://" + target
      val remote = s"$host/wikie/json/${wid.wpathFull}"
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
}