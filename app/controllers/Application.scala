package controllers

import java.io.File

import admin.{IgnoreErrors, Audit, Config}
import com.mongodb.casbah.Imports._
import db._
import model.Sec._
import model._
import org.joda.time.DateTime
import play.api.mvc.{Action, Request}

/** main entry points */
object Application extends RazController {

  // serve any URL other than routes matches - try the forward list...
  def whatever(path: String) = Action { implicit request =>
    log("REDIRECTING? - " + path)
    Website.getHost.orElse(Some(Config.hostport)).flatMap(x =>
      Config.urlfwd(x + "/" + path)).map { host =>
      log("  REDIRECTED TO - " + host)
      Redirect(host)
    } getOrElse {
      val c = Config.config(Config.BANURLS)
      if (c.exists(_.contains(path))) {
        val ip = request.headers.get("X-Forwarded-For")

        if (ip.isDefined && BannedIps.isBanned(ip)) {
          Audit.logdb("BANNED_IP", List("request:" + request.toString, "headers:" + request.headers, "body:" + request.body).mkString("<br>"))
          BannedIps.ban(ip.get, request.method + " " + path)
        }
        // TODO emulate some well known wiki error response to throw them off
        NotFound("")
      } else {
        Audit.missingPage(" NO ROUTE FOR: " + path)
//        NotFound("This is not the page you're looking for...!")
        Redirect ("/")
      }
    }
  }

  def options(path: String) = Action { implicit request =>
    Status(400) // bad request
  }

  /** serve the root of a website - figure out which and serve the main page */
  def root = Action { implicit request =>
    Website.getHost.flatMap(Config.urlfwd(_)).map { host =>
      Redirect(host)
    } orElse Website.getHost.flatMap(Website.apply).flatMap(_.homePage).map{ home=>
        Wiki.show(home, 1).apply(request).value.get.get
    } getOrElse Application.idoeIndexItem(1) // RK main home screen
    //todo un-hardcode that
  }

  def index = doeIndexItem(1)
  def index1 = doeIndexItem(1)

  def idoeIndexItem(i: Int)(implicit request: Request[_]) =
    Ok(views.html.index("", auth, i, session.get("mobile").isDefined))

  def doeIndexItem(i: Int) = Action { implicit request =>
    idoeIndexItem(i)
  }

  def skiwho(who: String) = Action { implicit request =>
    Audit.logdb("ADD_SKI", "who: " + who)
    Redirect(Wiki.w("Admin", "Hosted_Services_for_Ski_Clubs"))
  }

  /** randomly redirect to a topic */
  def lucky = Action { implicit request =>

    val au = auth
    var w: Option[model.WID] = None
    var wpage: Option[model.WikiEntry] = None
    var i = 0
    do {
      w = Wikis(Wikis.DFLT).index.random
      i = i + 1
      wpage = w flatMap(_.page)
    } while ((w.isEmpty || !Wiki.canSee(w.get, au, wpage)(IgnoreErrors).exists(identity)) && i < 100)

    w.map { wid =>
      Audit.logdb("LUCKY", "wpath", wid.wpath)
      Config.urlcanon(wid.wpath, wid.page).map { canon =>
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
        Msg2("Can't find Harry Potter - sorry!")
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
    Ok(views.html.user.doeSpin(auth))
  }

  def doeSelectTheme = Action { implicit request =>
    Ok(views.html.user.doeSelectTheme(auth))
  }

  // TODO better mobile display
  def mobile(m: Boolean) = Action { implicit request =>
    Redirect("/").withSession(
      if (m) session + ("mobile" -> "yes")
      else session - "mobile")
  }

  var razSu = "nothing" // if I su, this is the email of who I su'd as
  var razSuTime = 0L // if I su, this is the email of who I su'd as

  def show(page: String) = {
    page match {
      case "index" => index
      case "profile" => Action { implicit request => Redirect("/doe/profile") }
      case "terms" => Action { implicit request => Redirect("/page/Terms_of_Service") }
      case "join" => Action { implicit request => Redirect("/doe/join") }
      case "logout" | "signout" => Action { implicit request =>
        val au = auth
        auth map (_.auditLogout)
        cleanAuth(auth)
        if (au.isDefined &&
          System.currentTimeMillis - razSuTime < 15 * 60 * 1000 &&
          request.session.get("extra").exists(_ == razSu)) {
          val me = razSu
          razSu = "no"
          Audit.logdb("ADMIN_SU_RAZIE", "sure?")
          Redirect("/").withSession(Config.CONNECTED -> Enc.toSession(me))
        } else
          Redirect("/").withNewSession
      }
      case _ => { Audit.missingPage(page); TODO }
    }
  }

  // TODO audit visits to site
  def hosted(site: String, path: String) = controllers.Assets.at("/public/hosted", site + "/" + path)

  // list all the hosted sites
  def hostedAll = Action { implicit request =>
    def link(s: String) = """<a href="/hosted/%s/index.html">%s</a>""".format(s, s)
    Msg2("Assets are: \n" + new File("public/hosted/").list().map(link(_)).mkString("<br>"))
  }

  // testing specific stuff - needs a code sent over but any user account
  def test(what: String, data: String, code: String) = Action { implicit request =>
    implicit class SSO(s: String) {
      def aso = new ObjectId(s)
    }

    razie.clog << s"TESTING what=$what, data=$data, code=$code" << request.headers
    (for (
      u <- auth orErr "no user";
      isok <- (code == T.TESTCODE) orErr "no code"
    ) yield Ok(what match {
      case "regByUserId" =>
        ROne[model.Reg]("userId" -> data.aso).map(_._id.toString).mkString
      case "wikiSetOwnerById" =>
        val Array(wId, uId) = data.split(",")
        val w = ROne[model.WikiEntry]("_id" -> wId.aso).get
        db.tx("test") { implicit txn =>
          w.update(w.cloneProps(w.props ++ Map("owner" -> uId), uId.aso))
        }
        "ok"
      case "wikiIdByName" =>
        ROne[model.WikiEntry]("name" -> data).map(_._id.toString).mkString
      case "uwIdByUserId" =>
        ROne[model.User]("_id" -> data.aso).toList.flatMap(_.wikis).map(_._id.toString).mkString
      case "userIdByFirstName" =>
        ROne[model.User]("firstName" -> data).map(_._id.toString).mkString
      case "setuserUsernameById" =>
        ROne[model.User]("_id" -> data.aso).foreach(u => u.update(u.copy(userName = data)))
        "ok"
      case "verifyUserById" =>
        val email = db.ROne[model.User]("_id" -> data.aso).map(_.email.toString).get
        controllers.Tasks.verifiedEmail(DateTime.now().plusHours(1).toString().enc, email, data, auth)
        "ok"
      case "auth" =>
        auth.toString
      case "kidByName" =>
        val Array(f, l) = data split ","
        ROne[model.RacerKidInfo]("firstName" -> f, "lastName" -> l).map(_.rkId.toString).mkString
      case "rkForUserId" =>
        model.RacerKidz.findForUser(data.aso).map(_._id).toList.mkString(",")
      case _ => "?"
    })) getOrElse
      unauthorized("Oops - some error")
  }
}
