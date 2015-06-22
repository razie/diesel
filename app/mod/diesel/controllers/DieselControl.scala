/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.controllers

import com.mongodb.casbah.Imports._
import com.novus.salat._
import controllers.RazController
import model.{Tags, Website}
import razie.db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import razie.db.{RMany, ROne, RazMongo}
import mod.diesel.model._
import play.api.mvc.{Action, AnyContent, Request}
import razie.wiki.dom.WikiDomain
import razie.wiki.model.{WikiEntry, Wikis, WID}
import razie.wiki.util.PlayTools
import razie.{cout, Logging}
import razie.wiki.admin.Audit

object DieselControl extends RazController with Logging {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  // todo eliminate stupidity
  var re:Option[DieselReactor] = None
  var dbr:Option[DslModule] = None

  /** load and initialize a reactor */
  private def iload(mod: String, realm:String) = {
    if(dbr.isEmpty) dbr = Diesel.find(mod, realm) map Diesel.apply
    if(re.isEmpty) re = dbr map (_.load)
    re
  }

  /** (re)load a reactor */
  def load(imod: String) = Action { implicit request=>
    val mod = if(imod == "") Website.realm else imod
    Audit("x", "DSL_LOAD", s"$mod")
    dbr=None
    re=None
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
    val wid = WID.fromPath(wpath).get
    val page = wid.page
    val rdom = page.flatMap(WikiDomain.domFrom).get.revise addRoot
    val left  = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(_.a == cat)
    val path = if(ipath == "/") ipath+cat else ipath

    def mkLink (s:String) = routes.DieselControl.dslDomBrowser (wpath, s, path+"/"+s).toString()


    ROK(auth, request) apply {implicit stok=>
      views.html.diesel.catBrowser(wid.getRealm, page, cat, left, right)(mkLink)
    }
  }

  def catBrowser(realm:String, cat:String, ipath:String) = Action { implicit request =>
    val rdom  = WikiDomain(realm).rdom
    val c  = WikiDomain(realm).rdom.classes.get(cat)
    val left  = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(_.a == cat)
    val path = if(ipath == "/") ipath+cat else ipath

    def mkLink (s:String) = routes.DieselControl.catBrowser (realm, s, path+"/"+s).toString()

    val page = Wikis(realm).category(cat)

    ROK(auth, request) apply {implicit stok=>
      if(c.exists(_.stereotypes contains "wikiCategory"))
        views.html.diesel.catBrowser(realm, page, cat, left, right)(mkLink)
      else
        views.html.diesel.domCat(realm, cat, left, right)(mkLink)
    }
  }

  private def getrdom(wpath:String) = {
    val wid = WID.fromPath(wpath).get
    if(wid.cat == "Realm" || wid.cat == "Reactor") {
      WikiDomain(wid.name).rdom
    } else {
      val page = wid.page
      page.flatMap(WikiDomain.domFrom).get.revise addRoot
    }
  }

  /** list entities of cat from domain wpath */
  def list2(cat:String, ipath:String) = Action { implicit request =>
    val realm = Website.realm
    val rdom = WikiDomain(realm).rdom
    val left  = rdom.assocs.filter(_.z == cat).map(_.a)
    val right = rdom.assocs.filter(_.a == cat).map(_.z)
    val path = if(ipath == "/") ipath+cat else ipath

    val wl = Wikis(realm).pages(cat).toList
    val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

    ROK(auth, request) noLayout  {implicit stok=>
      views.html.wiki.wikiList("list diesel entities", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", realm)
    }
  }

  /** list entities of cat from domain wpath */
  def list(wpath:String, cat:String, ipath:String) = Action { implicit request =>
    val rdom = getrdom(wpath)
    val wid = WID.fromPath(wpath).get
    val path = if(ipath == "/") ipath+cat else ipath

    val wl = Wikis(wid.getRealm).pages(cat).toList
    val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

    ROK(auth, request) noLayout  {implicit stok=>
      views.html.wiki.wikiList("list diesel entities", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "", wid.getRealm)
    }
  }

  def createDD(cat:String, ipath:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val realm = Website.realm
    val rdom = WikiDomain(realm).rdom
    val left  = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(_.a == cat)
    val path = if(ipath == "/") ipath+cat else ipath

    def mkLink (s:String) = routes.DieselControl.catBrowser (s, path+"/"+s).toString()

    ROK(auth, request) apply {implicit stok=>
      views.html.diesel.createDD(realm, cat, left, right, rdom, Map.empty)(mkLink)
    }
  }

}


