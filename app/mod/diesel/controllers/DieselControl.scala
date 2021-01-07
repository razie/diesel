/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports._
import controllers.{RazController, VErrors}
import mod.diesel.model._
import model.{MiniScripster, Tags}
import play.api.mvc.{Action, AnyContent, Request}
import razie.Logging
import razie.audit.Audit
import razie.diesel.dom.RDOM.{A, O}
import razie.diesel.dom._
import razie.hosting.Website
import razie.tconf.{FullSpecRef, SpecRef}
import razie.wiki.admin.Autosave
import razie.wiki.model.{WID, WikiEntry, Wikis}
import scala.concurrent.Future

/** diesel controller */
@Singleton
class DieselControl extends RazController with Logging {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  // todo eliminate stupidity
  var re: Option[DieselReactor] = None
  var dbr: Option[DslModule] = None

  /** load and initialize a reactor */
  private def iload(mod: String, realm: String) = {
    if (dbr.isEmpty) dbr = Diesel.find(mod, realm) map Diesel.apply
    if (re.isEmpty) re = dbr map (_.load)
    re
  }

  /** (re)load a reactor */
  def load(imod: String) = Action { implicit request =>
    val mod = if (imod == "") Website.realm else imod
    Audit("x", "DSL_LOAD", s"$mod")
    dbr = None
    re = None
    iload(mod, Website.realm) map (_.activate)
    Ok(re.mkString)
  }

  /** show a reactor */
  def show(mod: String) = Action { implicit request=>
    Audit("x", "DSL_SHOW", s"$mod")
    Redirect(s"/wiki/$mod")
  }

  /** interact with a reactor */
  def react(imod: String, event:String) = FAU { implicit au => implicit errCollector => implicit request=>
    val mod = if(imod == "") Website.realm else imod
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    Audit("x", "DSL_EVENT", s"$mod/$event with ${q.mkString}")
    val res = iload(mod, Website.realm) map (_.react(event, q))
    Ok(res.mkString)
  }

  /** TOPIC - call a topic level function */
  def fcall(wpath: String, fname:String) = RAction { implicit stok=>
    val q = stok.req.queryString.map(t=>(t._1, t._2.mkString))
    Audit("x", "DSL_FCALL", s"$wpath with ${q.mkString}")
    (for(
      wid <- WID.fromPath(wpath) orErr "bad wid";
      we <- wid.page.orElse {
        if( (wid.cat == "Spec" || wid.cat == "Story") && wid.name == "fiddle") {
          val x = Autosave.find(s"wikie",WID("","").r(stok.realm), stok.au.map(_._id)).flatMap(_.get("content")).mkString
          val page = new WikiEntry(wid.cat, "fiddle", "fiddle", "md", x, stok.au.map(_._id).getOrElse(new ObjectId()), Seq("dslObject"), "")
          Some(page)
        } else None
      } orErr "no page";
      dom <- WikiDomain.domFrom(we).map(_.revise) orErr "no domain in page";
      //      fname <- wid.section.orElse(request.) orErr "no func name";
      f <- dom.funcs.get(fname) orErr s"no func $fname in domain"
    ) yield {
        // prepare the func body - put a return on it and stuff
        val res = try {
          if (f.script != "") {
            val c = dom.mkCompiler("js")
            val x = c.compileAll ( c.not {case fx:RDOM.F if fx.name == f.name => true})
            val s = x + "\n" + f.script
            MiniScripster.isfiddleMap(s, "js", Some(we), stok.au, q, Some(qTyped(q,Some(f))))._2
          } else
            "ABSTRACT FUNC"
        } catch {
          case e:Throwable => e.getMessage
        }
        Ok(res.toString)
      }) getOrElse NotFound("NotFound: "+wpath+" "+stok.errCollector.mkString)
  }

  /** TOPIC - call a topic level function */
  def jplay(wpath: String, fname:String) = FAU { implicit au => implicit errCollector => implicit request=>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    Audit("x", "DSL_FPLAY", s"$wpath with ${q.mkString}")
    (for(
      wid <- WID.fromPath(wpath) orErr "bad wid";
      we <- wid.page orErr "no page";
      dom <- WikiDomain.domFrom(we).map(_.revise) orErr "no domain in page";
      //      fname <- wid.section.orElse(request.) orErr "no func name";
      f <- dom.funcs.get(fname) orErr s"no func $fname in domain"
    ) yield {
        // prepare the func body - put a return on it and stuff
        val c = dom.mkCompiler("js")
        val s = c.compile(f) + "\n" + c.call(f)
        val x = c.compileAll ( c.not {case fx:RDOM.F if fx.name == f.name => true})

        Ok("TODO - look at commented code here - where is this?")
//        ROK.s reactorLayout12 { implicit stok =>
//          views.html.fiddle.playBrowserFiddle("js", s, q, Some(we), x)
//        }
      }) getOrElse NotFound("NotFound: "+wpath+" "+errCollector.mkString)
  }

  /** TOPIC - call a topic level function */
  def splay(wpath: String, fname:String) = FAUR { implicit request=>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    Audit("x", "DSL_FPLAY", s"$wpath with ${q.mkString}")
    (for(
      wid <- WID.fromPath(wpath) orErr "bad wid";
      we <- wid.page orErr "no page";
      dom <- WikiDomain.domFrom(we).map(_.revise) orErr "no domain in page";
//      fname <- wid.section.orElse(request.) orErr "no func name";
      f <- dom.funcs.get(fname) orErr s"no func $fname in domain"
    ) yield {
        // prepare the func body - put a return on it and stuff
       val s = f.script

      ROK.k reactorLayout12 {
        views.html.fiddle.playServerFiddle("js", s, q, Some(we))
      }
    }) getOrElse NotFound("NotFound: "+wpath+" "+errCollector.mkString)
  }

  //************* DIESEL

  import razie.js

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojsons(x).toString).as("application/json")
  }

  def domain = Action { implicit request =>
    retj << WG.fromRealm(Website.realm).tojmap
  }

  def dslDomBrowser(wpath:String, cat:String, ipath:String) = Action { implicit request =>
    val errCollector = new VErrors()

    (for (
      wid  <- WID.fromPath(wpath) orErr "wpath not found";
      page <- wid.page orErr "page not found";
      dom <- WikiDomain.domFrom(page) orErr "no dom"
    ) yield {
      val rdom = dom.revise addRoot
      val left  = rdom.assocs.filter(_.z == cat)
      val right = rdom.assocs.filter(_.a == cat)
      val path = if(ipath == "/") ipath+cat else ipath
      val c  = rdom.classes.get(cat)
      val base = c.toList.flatMap(_.base)

      def mkLink (s:String,dir:String,assoc:Option[RDOM.A]) = routes.DieselControl.dslDomBrowser (wpath, s, path+"/"+s).toString()

      ROK.r apply {implicit stok=>
        views.html.modules.diesel.catBrowser("diesel", wid.getRealm, wid.getRealm, Some(page), cat, base, left, right)(mkLink)
      }
    }) getOrElse NotFound(errCollector.mkString)
  }

  /** page for looking and navigating among categories */
  def catBrowser(plugin:String, conn:String, realm:String, cat:String, ipath:String, o:Option[O]=None) = Action { implicit request =>
    val rdom  = WikiDomain(realm).rdom
    val c  = rdom.classes.get(cat)
    val left  = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(_.a == cat)
    val base = c.toList.flatMap(_.base)
    val path = if(ipath == "/") ipath+cat else ipath

    // context aware linking for the cat browser at the top
    def mkLink (s:String, dir:String, assoc:Option[A]=None) = {
      val newPath =
        if (path.split("/") contains s) s"/$s" // stop recursive traverses - some robots are stupid
        else s"$path/$s"

      // if class is found and I'm browsing one to many object
      rdom.classes.get(s).filter(x =>
        o.isDefined && dir == "from").flatMap(_.parms.find(p => p.isRef && p.ttype == o.get.base)).map { sc =>
        // find ref parm from s to o
        val as = assoc.map(_.zRole).getOrElse(sc.name)
        s"/diesel/objBrowserByQuery/$plugin/$conn/$s/_${as}_value/${o.get.name}"
      } getOrElse
          routes.DieselControl.catBrowser(plugin, conn, realm, s, newPath).toString()
    }

    ROK.r apply { implicit stok =>
      if (c.exists(_.stereotypes contains "wikiCategory"))
      // real wiki Category
        views.html.modules.diesel.catBrowser(plugin, conn, realm, Wikis(realm).category(cat), cat, base, left, right)(
          mkLink)
      else
      // parsed Class from domain
        views.html.modules.diesel.domCat(realm, cat, base, left, right, o)(mkLink)
    }
  }

  def objBrowserById(plugin: String, conn: String, cat: String, id: String, ipath: String, o: Option[O] = None) =
    RAction {
      implicit request =>
        val dov = DomInventories.findByRef(SpecRef.make(request.realm, plugin, conn, cat, id))

        val o = dov.flatMap(_.getValueO)

        catBrowser(plugin, conn, request.realm, cat, ipath, o).apply(
          request.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
    }

  def objBrowserByQuery(plugin: String, conn: String, cat: String, parm: String, value: String, ipath: String,
                        o: Option[O] = None) = RAction { implicit request =>

    val ref = SpecRef.make(request.realm, plugin, conn, cat, "")
    val list = DomInventories.findByQuery(ref, cat + "/" + parm + "/" + value)

    if (list.size <= 1) {
      val o = list.headOption.flatMap(_.getValueO)
      catBrowser(plugin, conn, request.realm, cat, ipath, o).apply(
        request.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
    } else {
      val s = list
          .flatMap(_.getValueO.toList)
          .map { dov =>
            val o = dov //.getValueO //value.asInstanceOf[O]
            val u = routes.DieselControl.objBrowserById(plugin, conn, cat, o.name, ipath).url
            s"""<a href="$u">${o.name}</a> ${o.getDisplayName}"""
          }
      Ok(s.mkString("<br>")).as("text/html")
    }
  }

  // todo deprecate - list2 works well now
  def listAll(plugin: String, conn: String, cat: String, ipath: String,
              start: Long, limit: Long) = RAction { implicit request =>

    val ref = SpecRef.make(request.realm, plugin, conn, cat, "")
    val list = DomInventories.listAll(ref, start, limit)

    if (list.size <= 1) {
      val o = list.headOption.flatMap(_.getValueO)
      catBrowser(plugin, conn, request.realm, cat, ipath, o).apply(
        request.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
    } else {
      val s = list
          .flatMap(_.getValueO.toList)
          .map { dov =>
            val o = dov //.getValueO //value.asInstanceOf[O]
            val u = routes.DieselControl.objBrowserById(plugin, conn, cat, o.name, ipath).url
            s"""<a href="$u">${o.name}</a> ${o.getDisplayName}"""
          }
      Ok(s.mkString("<br>")).as("text/html")
    }
  }

  private def getrdom(wpath:String) = {

    val wid = WID.fromPath(wpath).get
    if(wid.cat == "Realm" || wid.cat == "Reactor") {
      Some(WikiDomain(wid.name).rdom)
    } else {
      val page = wid.page
      page.flatMap(WikiDomain.domFrom).map(_.revise addRoot)
    }
  }

  /** list entities of cat from domain wpath */
  def list2(cat:String, ipath:String) = RAction { implicit request =>

    val realm = Website.realm
    val rdom = WikiDomain(realm).rdom
//    val left = rdom.assocs.filter(_.z == cat).map(_.a)
//    val right = rdom.assocs.filter(_.a == cat).map(_.z)
//    val path = if (ipath == "/") ipath + cat else ipath

    if (WikiDomain(realm).isWikiCategory(cat)) {

      val wl = Wikis(realm).pages(cat).toList
      val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(
        t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

      ROK.k reactorLayout12 {
        views.html.wiki.wikiList("list diesel entities", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", realm)
      }
    } else {

      val p = WikiDomain(realm).findPluginsForClass(rdom.classes.get(cat).get).head
      val ref = SpecRef.make(request.realm, p.name, p.conn, cat, "")
      val list = DomInventories.listAll(ref, start = 0, limit = 100)

      // todo find tags for assets
//      val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(
//        t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

      ROK.k reactorLayout12 {
        views.html.wiki.wikiListAssets("list diesel entities", "", "", list.map { x =>
          val ref = x.ref.asInstanceOf[FullSpecRef]
          (ref, ref.key)
        }, Nil, "./", "", realm)
      }
    }
  }

  // todo delete this?
  /** list entities of cat from domain wpath */
  def list(wpath:String, cat:String, ipath:String) = RAction { implicit request =>

    val rdom = getrdom(wpath).getOrElse(WikiDomain(request.realm).rdom)
    val wid = WID.fromPath(wpath).get
    val path = if (ipath == "/") ipath + cat else ipath

    val wl = Wikis(wid.getRealm).pages(cat).toList
    val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(
      t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

    ROK.k reactorLayout12 {
      views.html.wiki.wikiList("list diesel entities", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "",
        wid.getRealm)
    }
  }

  /** create a new instance of cat */
  def create(cat:String, ipath:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val realm = Website.realm
    val rdom = WikiDomain(realm).rdom
    val left = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(_.a == cat)
    val path = if (ipath == "/") ipath + cat else ipath

    def mkLink(s: String, dir: String, assoc: Option[RDOM.A]) = {
      routes.DieselControl.catBrowser("diesel", realm, realm, s, path + "/" + s).toString()
    }

    ROK.r apply { implicit stok =>
      views.html.modules.diesel.createDD(realm, cat, left, right, rdom, Map.empty)(mkLink)
    }
  }

  /** generic domain plugin actions */
  def pluginAction(plugin: String, conn: String, action: String, epath: String) = Filter(activeUser).async
  { implicit stok =>
    Future.successful {
      val url = "http" + (if (stok.secure) "s" else "") + "://" + stok.hostPort
      val wd = WikiDomain(stok.realm)
      val p = wd.findPlugins(plugin, conn)
      val c = p.map(_.doAction(wd.rdom, conn, action, url, epath)).mkString

      val res = if (c.startsWith("<"))
        Ok(c).as("text/html")
      else
        Ok(c)

      if (p.isEmpty) res.withHeaders("dieselError" -> "Plugin not found")
      else res
    }
  }

}


