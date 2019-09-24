package controllers

import admin.Config
import com.mongodb.casbah.Imports._
import mod.snow.{RacerKidInfo, RacerKidz, _}
import model._
import org.joda.time.DateTime
import play.api.mvc.Action
import razie.audit.Audit
import razie.db.{RMany, ROne}
import razie.db.RazMongo.RazMongoTable
import razie.hosting.{BannedIps, RkReactors, Website, WikiReactors}
import razie.tconf.Visibility
import razie.wiki.Sec._
import razie.wiki.model._
import razie.wiki.{Enc, Services}
import scala.concurrent.Future
import scala.util.Try

/** main entry points */
object Application extends RazController {

  /** serve the root of a website - figure out which and serve the main page */
  def root = Action.async { implicit request =>
    Try {
      val getHost = Website.getHost

      // 1. url forward?

      getHost.flatMap(Config.urlfwd(_)).map { host =>
        log ("URL - Redirecting main page from "+getHost + " TO "+host)
        Future.successful(Redirect(host))

      } orElse getHost.flatMap(Website.forHost).filter{w=>
        debug ("Is home page defined: " + w.homePage.isDefined)

        // is there reactor and home page?
        w.homePage.isDefined

      }.flatMap {x=>

        // found reactor and home page

        log ("URL - serve Website homePage for "+x.name)

        auth.map{au=>
          au.roles.find(y=>(x wprop "userHome."+y).isDefined).flatMap { y =>
            (x wprop "userHome." + y)
          } orElse x.userHomePage
        } getOrElse x.homePage

      }.map { home =>
        Wiki.show(home, 1).apply(request)

      } getOrElse {

        // no known host

        var r = Wiki.getRealm()

        // no longer default to RK, but WIKI - no weird skier pages

        if(r == "" || r == WikiReactors.RK)
          r = WikiReactors.WIKI

        log (s"URL - show default reactor main $r current host=$getHost")

        // print not found...

        val hostedDomains = Config.prop("wiki.hostedDomains", "dieselapps.com").split(",").map("." + _)

        // is this the first tiem in a new db ?
        if(RMany[User]().size <= 0) {
          Future.successful {
            Ok(views.html.util.newDb())
          }
        } else {

          val res =

            if(!Config.isLocalhost && getHost.exists(h=> hostedDomains.exists(d=> h.endsWith(d)))) {
              // does it have a different domain?
              val w = Website.forRealm(r)

              val dom = w.flatMap(_.prop("domain"))

              log(s"### - $r - $dom - ${w.isDefined}")

              if(!dom.exists(h=> hostedDomains.exists(d=> h.endsWith(d)))) {
                // had another domain - redirect
                log(s"Redirecting to ${w.flatMap(_.prop("url")).mkString}")
                Future.successful {
                  Redirect(w.flatMap(_.prop("url")).mkString)
                }
              } else
                // if a hosted reactor domain, print a generic project not found message
                Wiki.show(
                  WID("Admin", "WebsiteNotFound").r(WikiReactors.WIKI).page.map(_.wid) getOrElse WikiReactors(r).mainPage(auth),
                  1).apply(request)
            }
            else
            // if nice URL - don't just redirect to dieselapps - just print a generic message
              Wiki.show(
                WikiReactors(r).mainPage(auth),
                1).apply(request)
          res
        }
        //        else  Future.successful(Application.idoeIndexItem(1))
      } // RK main home screen

    } getOrElse {

      // is this the first tiem in a new db ?

      if (RMany[User]().size <= 0) {
        Future.successful {
          Ok(views.html.util.newDb())
        }
      } else Future.successful {
        ServiceUnavailable("If you imported a new realm, please reboot this instance!")
      }
    }
  }

  // serve any URL other than routes matches - try the forward list...
  def whatever(path: String) = Action.async { implicit request =>
    log("URL - REDIRECTING? - " + path)

// first look for rewrites and host redirection

    Website.getHost.orElse(Some(Services.config.hostport)).flatMap(x =>
      Config.urlrewrite(x + "/" + path) orElse
      Config.urlfwd(x + "/" + path)

    ).map { host =>
      log("  REDIRECTED TO - " + host)
      Future.successful(Redirect(host))

// next - is it banned?

    } getOrElse {
      val c = Config.config(Config.BANURLS)

      if (c.exists(_.contains(path))) {
        val ip = request.headers.get("X-Forwarded-For")

        if (ip.isDefined && BannedIps.isBanned(ip)) {
          Audit.logdb("BANNED_IP", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
          BannedIps.ban(ip.get, request.method + " " + path)
        }
        // TODO emulate some well known wiki error response to throw them off
        Future.successful(NotFound(""))

      } else {

// next - find reactor it belongs to

        Audit.missingPage("URL - NO ROUTE FOR: " + path)

        RkReactors(request).map(Wikis.apply).flatMap {wiki=>

          // found reactor - look for a simple name first

          wiki.find("Admin", path) orElse wiki.find("Page", path) map { we =>
            Wiki.showWid(CMDWID(Some(we.wid.wpath), Some(we.wid), "", ""), 1, wiki.realm).apply(request)
          }
        }.getOrElse {

          // no simple name match - check for /cat/name

          val e = path.split("/")

          WID.PATHCATS.find(s=>(e(0) == s.toLowerCase || e(0) == s)).map {cat=>
            if(e.size > 1) {
              // normal /Topic/name
              val wid = WID(cat, e(1))
              val rewritten = path.replace(s"${e(0)}/${e(1)}", wid.wpath)
              val cmd = WID.cmdfromPath(rewritten).get
              Wiki.showWid(cmd, 1, "?").apply(request)
            } else {
              // just /Topic
              val cmd = WID.cmdfromPath("Category:"+e(0)).get
              Wiki.showWid(cmd, 1, "?").apply(request)
            }
          } getOrElse
            Future.successful(Redirect("/"))
        }
      }
    }
  }

  //todo implement options
  def options(path: String) = Action { implicit request =>
    Status(400) // bad request
  }

  // the point is to list all the canonical forms for this realm
  def sitemap = RAction { implicit request =>
    Ok(Wikis(request.realm).index.withIndex {idx=>
      val hostName = WikiReactors(request.realm).websiteProps.prop("url").getOrElse("http://www.dieselapps.com")

      idx.idx.map {e=>
        // copy the wid so it doesn't cache the page
        e._2
          .map(_._1)
          .flatMap(wid=>
            Wikis(wid.getRealm).find(wid))
          .filter(_.visibility == Visibility.PUBLIC)
          .map{w=>
            Services.config.urlcanon(w.wid.wpath, None).getOrElse {
              s"$hostName/${w.wid.canonpath}"
            }
        }.mkString
      }
        .filter(_.nonEmpty)
    }.mkString("\n"))
  }

 class Node(prev:Node = null)
  new Node()

  // is this the first tiem in a new db ?
  def isDbEmpty =
    WID("Admin", "Home").r("rk").page.isEmpty &&
    RMany[User]().size <= 1

  def index = root

  /** randomly redirect to a topic
    * todo not used anymore */
  def lucky = Action { implicit request =>

    val au = auth
    var w: Option[WID] = None
    var wpage: Option[WikiEntry] = None
    var i = 0
    do {
      w = Wikis(Wikis.DFLT).index.random
      i = i + 1
      wpage = w flatMap(_.page)
    } while ((w.isEmpty || !Wiki.canSee(w.get, au, wpage)(IgnoreErrors).exists(identity)) && i < 100)

    w.map { wid =>
      Audit.logdb("LUCKY", "wpath", wid.wpath)
      Config.urlcanon(wid.wpath, wid.page.map(_.tags)).map { canon =>
        Redirect(canon)
      } getOrElse {
        Redirect(Wiki.w(wid))
      }
    } getOrElse {
      Redirect("/")
    }
  }

  // login as harry p.
  def doeHarry(css: String) = Action { implicit request =>
    if (css != null && css.length > 0 && !Array("light", "dark").contains(css)) {
      Audit.logdb("DOE_HARRY_HACK", css + " - " + request.toString)
      Unauthorized("")
    } else {
      Audit.logdb("DOE_HARRY", css)

      (for (u <- Users.findUserById("4fdb5d410cf247dd26c2a784")) yield {
          Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(u.email), "css" -> css)
      }) getOrElse {
        Audit.logdb("ERR_HARRY", "account is missing???")
        Msg("Can't find Harry Potter - sorry!")
      }
    }
  }

  // login as harry p.
  def doeTheme(css: String) = Action { implicit request =>
    if (css != null && css.length > 0 && !Array("light", "dark").contains(css)) {
      Audit.logdb("SET_THEME_HACK", css + " - " + request.toString)
      Unauthorized("")
    } else {
      Audit.logdb("SET_THEME", "X-FORWARDED-HOST = " + request.headers.get("X-FORWARDED-HOST"), css)

      (for (au <- auth)
        yield
          Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(au.email), "css" -> css)
      ) getOrElse
          Redirect("/").withSession("css" -> css)
    }
  }

  def doeSpin = Action { implicit request =>
    ROK.r noLayout { implicit stok=> views.html.user.doeSpin() }
  }

  import Admin.StokAdmin

  def doeSelectTheme = Action { implicit request =>
    ROK.r admin {implicit stok=> views.html.user.doeSelectTheme()}
  }

  // TODO better mobile display
  def mobile(m: Boolean) = Action { implicit request =>
    Redirect("/").withSession(
      if (m) request.session + ("mobile" -> "yes")
      else request.session - "mobile")
  }

  var razSu = "nothing" // if I su, this is the email of who I su'd as
  var razSuTime = 0L // if I su, this is the email of who I su'd as

  def show(page: String) = {
    page match {
      case "index" => index
      case "profile" => Action { implicit request => Redirect("/doe/profile") }
      case "terms" => Action { implicit request => Redirect("/wiki/Terms_of_Service") }
      case "join" => Action { implicit request => Redirect("/doe/join") }
      case "logout" | "signout" => Action { implicit request =>
        val au = auth
        auth map (_.auditLogout(Website.getRealm))
        cleanAuth(auth)
        if (au.isDefined &&
          System.currentTimeMillis - razSuTime < 15 * 60 * 1000 &&
          request.session.get("extra").exists(_ == razSu)) {
          val me = razSu
          razSu = "no"
          Audit.logdb("ADMIN_SU_RAZIE", "sure?")
          Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(me))
        } else {
          Redirect("/").withNewSession
        }
      }
      case _ => { Audit.missingPage(page); TODO }
    }
  }

  // TODO audit visits to site
  def hosted(site: String, path: String) = controllers.Assets.at("/public/hosted", site + "/" + path)

  def listswitch = FAU { implicit au => implicit errCollector => implicit request =>
    Ok(WikiReactors.reactors.values.map{r=>
      r.websiteProps.prop("domain")}.filter(_.isDefined).map(_.get).map{d=>
      s"""<a href="/doe/www/$d">$d</a><br>"""
    }.mkString
    ).as("html")
  }

  def switch(domain: String) = FAU { implicit au => implicit errCollector => implicit request =>
    Config.isimulateHost = domain
    Redirect("/")
  }

  // testing specific stuff - needs a code sent over but any user account
  def test(what: String, data: String, code: String) = Action { implicit request =>
    implicit class SSO(s: String) {
      def aso = new ObjectId(s)
    }

    clog << s"TESTING what=$what, data=$data, code=$code" << request.headers
    (for (
      u <- auth orErr "no user";
      isok <- (code == T.TESTCODE) orErr "no code"
    ) yield Ok(what match {
      case "regByUserId" =>
        ROne[Reg]("userId" -> data.aso).map(_._id.toString).mkString
      case "wikiSetOwnerById" =>
        val Array(wId, uId) = data.split(",")
        val w = ROne[WikiEntry]("_id" -> wId.aso).get
        razie.db.tx("test", "?") { implicit txn =>
          w.update(w.cloneProps(w.props ++ Map("owner" -> uId), uId.aso), Some("testing"))
        }
        "ok"
      case "wikiIdByName" =>
        ROne[WikiEntry]("name" -> data).map(_._id.toString).mkString
      case "uwIdByUserId" =>
        ROne[User]("_id" -> data.aso).toList.flatMap(_.wikis).map(_._id.toString).mkString
      case "userIdByFirstName" =>
        ROne[User]("firstName" -> data).map(_._id.toString).mkString
      case "setuserUsernameById" =>
        ROne[User]("_id" -> data.aso).foreach(u => u.update(u.copy(userName = data)))
        "ok"
      case "verifyUserById" =>
        val email = ROne[model.User]("_id" -> data.aso).map(_.email.toString).get
        controllers.Tasks.verifiedEmail(DateTime.now().plusHours(1).toString().enc, email, data, auth)
        "ok"
      case "auth" =>
        auth.toString
      case "kidByName" =>
        val Array(f, l) = data split ","
        ROne[RacerKidInfo]("firstName" -> f, "lastName" -> l).map(_.rkId.toString).mkString
      case "rkForUserId" =>
        RacerKidz.findAllForUser(data.aso).map(_._id).toList.mkString(",")
//      case "crUser" =>
//        RkTe
      case "testCycle" => {
        def apply(table: String) = new RazMongoTable(table)
        val c = apply("Ver").findOne(Map("name" -> "TestCycle"))
        if(c.isDefined)
          apply("Ver").update(Map("name" -> "TestCycle"), Map("$set" -> Map("cycle" -> (c.get.getAs[String]("cycle").get.toInt+1).toString)))
        else
          apply("Ver") += Map("name" -> "TestCycle", "cycle" -> "1")
        c.map(_.getAs[String]("cycle").get).getOrElse(1).toString
      }
      case _ => "?"
    })) getOrElse
      unauthorized("Oops - some error")
  }
}
