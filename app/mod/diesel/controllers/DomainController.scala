/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports._
import controllers.{RazController, RazRequest, VErrors}
import mod.diesel.model._
import model.Tags
import play.api.mvc.{Action, AnyContent, Request}
import razie.Logging
import razie.audit.Audit
import razie.base.scriptingx.DieselScripster.jstypeSafe
import razie.diesel.dom.DomInventories.{jtok, oFromJMap, oToA}
import razie.diesel.dom.RDOM.P.isArrayOfSimpleType
import razie.diesel.dom.RDOM.{A, O, P}
import razie.diesel.dom._
import razie.diesel.engine.DieselException
import razie.diesel.engine.nodes.EMsg
import razie.diesel.expr.StaticECtx
import razie.hosting.Website
import razie.tconf.{FullSpecRef, SpecRef}
import razie.wiki.admin.Autosave
import razie.wiki.model.{WID, WikiEntry, Wikis}
import scala.collection.mutable.HashMap
import scala.concurrent.Future

/** diesel controller */
@Singleton
class DomainController extends RazController with Logging {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)


  // ============================ DOMAIN browsing ================

  def domain = Action { implicit request =>
    val wg = WG.fromRealm(Website.realm)
    retj << wg.tojmap
  }

  def dslDomBrowser(wpath:String, cat:String, ipath:String) = Action { implicit request =>
    val errCollector = new VErrors()

    (for (
      wid  <- WID.fromPath(wpath) orErr "wpath not found";
      page <- wid.page orErr "page not found";
      dom <- WikiDomain.domFrom(page) orErr "no dom"
    ) yield {
      val rdom = dom.revise addRoot
      val left = rdom.assocs.filter(_.z == cat)
      val right = rdom.assocs.filter(_.a == cat)
      val path = if (ipath == "/") ipath + cat else ipath
      val c = rdom.classes.get(cat)
      val base = c.toList.flatMap(_.base)

      def mkLink(s: String, dir: String, assoc: Option[RDOM.A]) = routes.DomainController.dslDomBrowser(wpath, s,
        path + "/" + s).toString()

      ROK.r apply { implicit stok =>
        views.html.modules.diesel.catBrowser("diesel", wid.getRealm, wid.getRealm, Some(page), cat, base, left, right)(
          mkLink)
      }
    }) getOrElse NotFound(errCollector.mkString)
  }

  /** page for looking and navigating among categories
    *
    * @param plugin
    * @param conn
    * @param realm
    * @param cat class to find
    * @param ipath
    * @param o
    * @return
    */
  def catBrowser(plugin: String, conn: String, realm: String, cat: String, ipath: String, o: Option[O] = None, errors:List[Any] = Nil) = Action
  { implicit request =>
    val rdom = WikiDomain(realm).rdom
    val c = rdom.classes.get(cat)
    val left = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(x=> x.a == cat && !WTypes.PRIMARY_TYPES.contains(x.z))
    val base = c.toList.flatMap(_.base)
    val path = if (ipath == "/") ipath + cat else ipath

    val pl = if(plugin.trim.length > 0) plugin.trim else "-"
    val co = if(conn.trim.length > 0) conn.trim else "-"

    /** context aware linking for the cat browser at the top
      *
      * @param s class to find
      * @param dir
      * @param assoc
      * @return
      */
    def mkLink(s: String, dir: String, assoc: Option[A] = None) = {
      val newPath =
        if (path.split("/") contains s) s"/$s" // stop recursive traverses - some robots are stupid
        else s"$path/$s"

      // if class is found and I'm browsing one to many object
      rdom.classes.get(s).filter(x =>
        o.isDefined && dir == "from").flatMap(_.parms.find(p => p.isRef && p.ttype == o.get.base)).map { sc =>
        // find ref parm from s to o
        val as = assoc.map(_.zRole).getOrElse(sc.name)
        val ref = new FullSpecRef(
          plugin,
          conn,
          cat,
          o.get.name,
          "",
          realm
        )
        // we're navigating backwards a many to one association
        s"/diesel/dom/query/$s/${as}/'${o.get.name}'?plugin=$plugin&conn=$conn"
      } getOrElse
          routes.DomainController.catBrowser(pl, co, realm, s, newPath).toString()
    }

    ROK.r.withErrors(errors) apply  { implicit stok =>
      if (c.exists(_.stereotypes contains "wikiCategory"))
      // real wiki Category
        views.html.modules.diesel.catBrowser(pl, co, realm, Wikis(realm).category(cat), cat, base, left, right)(
          mkLink)
      else {
        // parsed Class from domain

        // is there a template for it?
        val otemplate = c.flatMap(c=>Wikis(realm).find("CategoryTemplate", c.name))

        otemplate.filter(x=>o.isDefined).map {t=>
            // use any drafts to make this simple to develop
          val c =  (new RazRequest(request)).au.flatMap { u =>
            Autosave.find("wikie", t.wid.defaultRealmTo(realm),u._id).flatMap(_.get("content"))
          }

          val newcontent = Wikis.templateSimple(c.getOrElse(t.content), "this.", o.get.toJson)

          views.html.modules.diesel.domCatWithTemplate(realm, cat, base, left, right, o, t, newcontent)(mkLink)
        }.getOrElse {
          views.html.modules.diesel.domCat(realm, cat, base, left, right, o)(mkLink)
        }
      }
    }
  }


  // ===================== DOM CAT =================

  def apiDomCatGet   (cat:String, path:String="/", plugin:String="", conn:String="") =
    domCatGet(cat, path, plugin, conn)
  def apiDomCatQuery (cat:String, q:String, path:String="/", plugin:String="", conn:String="") =
    domQuery  (cat, parm=q.split(":")(0), value=q.split(":")(1), path, plugin, conn, fullPage=false, format="json")
  def apiDomCatGetId (cat:String, id:String, path:String="/", plugin:String="", conn:String="") =
    domBrowseId(cat, id, path, plugin, conn, format=WTypes.json)
  def apiDomCatPost  (cat:String, path:String="/", plugin:String="", conn:String="") =
    domCatPost(cat, path, plugin, conn)
  def apiDomCatPut   (cat:String, path:String="/", plugin:String="", conn:String="") = domCatGet(cat, path, plugin, conn)
  def apiDomCatDel   (cat:String, path:String="/", plugin:String="", conn:String="") = domCatGet(cat, path, plugin, conn)

  def domCatGet   (cat:String, path:String="/", plugin:String="", conn:String="", format:String="") =
    domList   (cat, path, plugin, conn, format)
  def domCatQuery (cat:String, q:String="", path:String="/", plugin:String="", conn:String="") =
    domQuery  (cat, parm=q.split(":")(0), value=q.split(":")(1), path, plugin, conn, fullPage=false, format="json")
  def domCatGetId (cat:String, id:String, path:String="/", plugin:String="", conn:String="") =
    domBrowseId(cat, id, path, plugin, conn, format=WTypes.json)
  def domCatPost  (cat:String, path:String="/", plugin:String="", conn:String="") =
    domDoUpsert (cat, path, plugin, conn)
  def domCatPut   (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok => ??? }
  def domCatDel   (cat:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok => ??? }


  def domStartUpdate  (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok => ??? }
  def domStartDelete  (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok => ??? }

  def domDoDelete  (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok => ??? }

  def domQueryQ   (cat:String, q:String, path:String="/", plugin:String="", conn:String="", fullPage:Boolean=true) = FAUR { implicit stok => ??? }

  def domApiMetaCat  (cat:String, path:String="/", format:String="") =
    domMetaCat     (cat, path, format=WTypes.json)


  // ===================== CRUD =================

  /** helper to unpact arguments passed in */
  private def unpack (cat:String, plugin:String="", conn:String="", checkCat:Boolean = true) (implicit stok:RazRequest) = {
    val dom = WikiDomain(stok.realm)
    val rdom = dom.rdom
    val oc = rdom.classes.get(cat)

    if(oc.isEmpty) {
      if(checkCat)
        throw new DieselException(s"Category $cat not found!", Option(401))
      else
        (stok.realm, dom, rdom, null, oc, plugin, conn)
    } else {
      val c = rdom.classes(cat)
      val iconn = if(conn == "") "default" else conn
      // todo validate
      val iplugin = if(plugin == "") DomInventories.getPluginForClass(stok.realm, c, iconn).map(_.name).mkString else plugin

      (stok.realm, dom, rdom, c, oc, iconn, iplugin)
    }
  }


  /** create a new instance of cat */
  def domStartCreate  (cat:String, path:String="/", plugin:String="", conn:String="") =
    domStartUpsert(cat, "", path, plugin, conn)

  /** start forms to create a new instance of cat */
  def domStartUpsert  (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok =>
    val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn)

    val left = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(x=> x.a == cat && !WTypes.PRIMARY_TYPES.contains(x.z))
    val ipath = if (path == "/") path + cat else path

    def mkLink(s: String, dir: String, assoc: Option[RDOM.A]) = {
      routes.DomainController.catBrowser("diesel", realm, realm, s, path + "/" + s).toString()
    }

    val obj = DomInventories.defaultClassAttributes(Nil, oc)(new StaticECtx().withDomain(rdom))

    ROK.r apply { implicit stok =>
      // todo create empty object with defaults
      views.html.modules.diesel.createDD(iplugin, iconn, realm, cat, left, right, WikiDomain(realm), obj, Map.empty)(mkLink)
    }
  }

  /** POST to create new entity as a result of create */
  def domDoUpsert  (cat:String, path:String="/", plugin:String="", conn:String="") = Filter(activeUser) { implicit stok =>
    val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn)

    dom.findPlugins(plugin, conn).headOption.map {inv=>
      val e = stok.postedContent
      val s = e.map(_.body).mkString
      val j = razie.js.parse(s)
      j.remove("content")
      j.remove("tags")
      j.remove("category")
      j.remove("realm")
      val k = jtok(j, oc)
      val ref = SpecRef.make(realm, iplugin, iconn, cat, k)
      val o = oFromJMap(k, j.toMap, c, c.name, Array.empty)
      val a = oToA(o, ref, j.toMap, stok.realm, oc)

      val res = DomInventories.resolveEntity(realm, ref, inv.upsert(rdom, ref, a))
      val pres = res.map(_.asP)
      pres.filter(x=> x.ttype.isJson && x.value.isDefined).map {j=>
        retj << (j.value.get.asJson ++ Map ("assetRef" -> res.get.ref.toJson)).toMap
      }.getOrElse {
        pres.map {p=>
          retj << Map(
            "assetRef" -> res.get.ref.toJson,
            "value" -> p.currentStringValue
          )
        }.getOrElse {
          NotFound(s"Object not resolved! $iplugin / $iconn / $cat / $k")
            .withHeaders("dieselError" -> "Object not resolved!")
        }
      }
    } getOrElse {
      NotFound(s"Inventory not found for $iplugin / $iconn / $cat")
        .withHeaders("dieselError" -> "Plugin not found")
    }
  }

  /** start forms to create a new instance of cat */
  def domStartEdit  (cat:String, id:String, path:String="/", plugin:String="", conn:String="") = FAUR { implicit stok =>
    val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn)

    val left = rdom.assocs.filter(_.z == cat)
    val right = rdom.assocs.filter(x=> x.a == cat && !WTypes.PRIMARY_TYPES.contains(x.z))
    val ipath = if (path == "/") path + cat else path

    def mkLink(s: String, dir: String, assoc: Option[RDOM.A]) = {
      routes.DomainController.catBrowser("diesel", realm, realm, s, path + "/" + s).toString()
    }

    val dov = DomInventories.findByRef(SpecRef.make(realm, iplugin, iconn, cat, id))

    val obj = dov.flatMap(_.getValueO).toList.flatMap(_.parms).map(x=>(x.name, x))

//    val obj = DomInventories.defaultClassAttributes(Nil, oc)(new StaticECtx().withDomain(rdom))

    ROK.r apply { implicit stok =>
      // todo create empty object with defaults
      views.html.modules.diesel.createDD(iplugin, iconn, realm, cat, left, right, WikiDomain(realm), obj, Map.empty)(mkLink)
    }
  }

  /** POST to create new entity as a result of create */
  def domDoEdit  (cat:String, path:String="/", plugin:String="", conn:String="") = Filter(activeUser) { implicit stok =>
    val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn)

      dom.findPlugins(plugin, conn).headOption.map {inv=>
        val e = stok.postedContent
        val s = e.map(_.body).mkString
        val j = razie.js.parse(s)
        j.remove("content")
        j.remove("tags")
        j.remove("category")
        j.remove("realm")
        val k = jtok(j, oc)
        val ref = SpecRef.make(realm, iplugin, iconn, cat, k)
        val o = oFromJMap(k, j.toMap, c, c.name, Array.empty)
        val a = oToA(o, ref, j.toMap, stok.realm, oc)

        val res = DomInventories.resolveEntity(realm, ref, inv.upsert(rdom, ref, a))
        Ok(res.map(_.asP.currentStringValue).mkString)
      } getOrElse {
        NotFound(s"Inventory not found for $iplugin / $iconn / $cat")
            .withHeaders("dieselError" -> "Plugin not found")
      }
  }

  def domMetaCat (cat:String, path:String="/", plugin:String="", conn:String="", format:String="") = RAction.withAuth {
    implicit stok =>
      try {
        val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack(cat, plugin, conn)

        if ("json" == format && oc.isDefined) {
          retj << rdom.tojmap(c)
        } else {
          catBrowser (iplugin, iconn, realm, cat, path, None).apply(
            stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
        }
      } catch {
        case t:Throwable =>
          // it'll say "nothing known about"
          catBrowser ("", "", stok.realm, cat, path, None).apply(
            stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
      }
  }

  def domReset = RAction.withAuth {
    implicit stok =>
        val realm = stok.realm
        val dom = WikiDomain(realm)

        dom.resetDom
        dom.addRootIfMissing()

        Redirect("/diesel/dom/browse")
  }

  def domBrowseRoot  (format:String="") = RAction.withAuth {
    implicit stok =>
        val realm = stok.realm
        val dom = WikiDomain(realm)

        dom.addRootIfMissing()

        Redirect("/diesel/dom/browse/Domain")
  }

  def domBrowse  (cat:String, path:String="/", plugin:String="", conn:String="", format:String="") = RAction.withAuth {
    implicit stok =>
        try {
          val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack(cat, plugin, conn)

          if ("json" == format && oc.isDefined) {
            retj << rdom.tojmap(c)
          } else {
            catBrowser (iplugin, iconn, realm, cat, path, None).apply(
              stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
          }
        } catch {
          case t:Throwable =>
            // it'll say "nothing known about"
            catBrowser ("", "", stok.realm, cat, path, None).apply(
              stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
        }
  }

  def domBrowseId  (cat:String, id:String, path:String="/", plugin:String="", conn:String="", format:String="", fieldsToShow:String="") = RAction.withAuth {
      implicit stok =>
        val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn, checkCat=false)

        val dov = DomInventories.findByRef(SpecRef.make(realm, iplugin, iconn, cat, id))

        val o = dov.flatMap(_.getValueO)

        if("json" == format) {
          o.fold{
            NotFound(id)
          } { x =>
            retj << x.toJson
          }
        } else {
          if(o.isEmpty) stok.withError(s"Not found: $id")
          catBrowser(iplugin, iconn, realm, cat, path, o).apply(
            stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
        }
    }

  def domQuery  (cat:String, parm:String, value:String, path:String="/", plugin:String="", conn:String="", fullPage:Boolean=true, format:String="") = RAction.withAuth { implicit stok =>
    val v = if(value.startsWith("'")) value.substring(1, value.length-1)

    try {
      val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack(cat, plugin, conn)

      val ref = SpecRef.make(stok.realm, iplugin, iconn, cat, "")
      val res = DomInventories.findByQuery(ref, Left(cat + "/" + parm + "/" + v), 0, 100, Array.empty[String])
      val list = res.data

      val fieldsToShow = c.props.find(_.name == "ui.fieldsToShow").map(_.currentStringValue).getOrElse("").split(",")

      if ("json" == format) {
        retj << Map(
          "count" -> list.size,
          "data" -> list.map { x =>
            x.asP.value.get.asJson
          }
        )
      } else {
        if (list.size <= 1 && fullPage) {
          // not fullpage means embedded search results - show list
          val o = list.headOption.flatMap(_.getValueO)
          catBrowser(iplugin, iconn, stok.realm, cat, path, o, List("Nothing found !")).apply(
            stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
        } else {
          if (fullPage) {
            ROK.k reactorLayout12 {
              views.html.wiki.wikiListAssets(
                cat, "", cat, res, Nil, "./", "", stok.realm, fieldsToShow
              )
            }
          } else {
            ROK.k noLayout {
              views.html.wiki.wikiListTableAssets(
                "list diesel entities",
                stok.realm,
                "",
                res,
                fieldsToShow
              )
            }
          }
        }
      }
    } catch {
    case t:Throwable =>
      // it'll say "nothing known about"
      catBrowser("", "", stok.realm, cat, path, None).apply(
        stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
  }
}

  private def getrdom(wpath:String) = {

    val wid = WID.fromPath(wpath).get
    if(wid.cat == "Realm" || wid.cat == "Reactor") {
      Option(WikiDomain(wid.name).rdom)
    } else {
      val page = wid.page
      page.flatMap(WikiDomain.domFrom).map(_.revise addRoot)
    }
  }

  /** list entities of cat from domain wpath */
  def domList   (cat:String, path:String="/", plugin:String="", conn:String="", format:String="") = RAction.withAuth.noRobots { implicit stok =>
      try {
        val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack(cat, plugin, conn)

//    val left = rdom.assocs.filter(_.z == cat).map(_.a)
//    val right = rdom.assocs.filter(_.a == cat).map(_.z)
//    val path = if (ipath == "/") ipath + cat else ipath

        if (dom.isWikiCategory(cat)) {

          val wl = Wikis(realm).pages(cat).toList
          val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(
            t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

          ROK.k reactorLayout12 {
            views.html.wiki.wikiList("list diesel entities", "", "", wl.map(x => (x.wid, x.label)), tags, "./", "",
              realm)
          }

        } else {
          val invlist = dom.findInventoriesForClass(c)
          if (invlist.isEmpty) {

            NotFound(s"Inventory/plugin for realm $realm cat $cat not found")

          } else {

            // DOM asset/objec

            val p = invlist.head
            val ref = SpecRef.make(stok.realm, p.name, p.conn, cat, "")
            val res = DomInventories.listAll(ref, start = 0, limit = 100, Array.empty[String])

            val fieldsToShow = c.props.find(_.name == "ui.fieldsToShow").map(_.currentStringValue).getOrElse("").split(
              ",")

            // todo find tags for assets
//      val tags = wl.flatMap(_.tags).filter(_ != Tags.ARCHIVE).filter(_ != "").groupBy(identity).map(
//        t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse

            ROK.k.withErrors(res.errors) reactorLayout12 {
              views.html.wiki.wikiListAssets(cat, "", cat, res,
                Nil, "./", "", realm, fieldsToShow)
            }
          }
        }
      } catch {
        case t:Throwable =>
          // it'll say "nothing known about"
            catBrowser("", "", stok.realm, cat, path, None, errors = List(t.toString)).apply(
              stok.ireq.asInstanceOf[Request[AnyContent]]).value.get.get
      }
  }

  /** recalculate the computed fields of an object, considering the other values */
  def recalcForm(cat:String) = FAU { implicit au => implicit errCollector => implicit request =>

    // get body of post as orig
    val body = P.fromTypedValue("this", request.body.asJson.get.toString, WTypes.wt.JSON).withSchema(cat)

    val realm = Website.realm
    val rdom = WikiDomain(realm).rdom
//    val left = rdom.assocs.filter(_.z == cat)
//    val right = rdom.assocs.filter(x=> x.a == cat && !WTypes.PRIMARY_TYPES.contains(x.z))

    val c = rdom.classes.get(cat)

    val bodyAsList = body.value.get.asJson.map (t=> (t._1, P.fromSmartTypedValue(t._1, t._2))).toList
    val obj = DomInventories.calculatedClassAttributes(body, bodyAsList, c)(new StaticECtx().withDomain(rdom))

    Ok(razie.js.tojsons(obj.toMap)).as("application/json")
  }

  /** generic domain plugin actions */
  def domAction (plugin: String, conn: String, action: String, epath: String) = doeAction(plugin, conn, action, epath)

  def doeAction (plugin: String, conn: String, action: String, epath: String) = Filter(activeUser).async
  { implicit stok =>
    Future.successful {
      // epath of the form cat/id/attr etc
      val cat = epath.replaceFirst("/.*", "")
      val (realm, dom, rdom, c, oc, iconn, iplugin) = unpack (cat, plugin, conn)

      val url = "http" + (if (stok.secure) "s" else "") + "://" + stok.hostPort
      val wd = WikiDomain(stok.realm)
      val p = wd.findPlugins(iplugin, iconn)
      val r = p.map(_.doAction(wd.rdom, iconn, action, url, epath)).mkString

      val res = if (r.startsWith("<"))
        Ok(r).as("text/html")
      else
        Ok(r)

      if (p.isEmpty) res.withHeaders("dieselError" -> "Plugin not found")
      else res
    }
  }

}


