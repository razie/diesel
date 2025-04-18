/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.mongodb.casbah.Imports.ObjectId
import com.typesafe.config.ConfigValue
import model.{DieselSettings, UserWiki, Users}
import org.bson.types.ObjectId
import play.api.mvc.{Action, Cookie, DiscardingCookie}
import razie.Logging
import razie.audit.Audit
import razie.db._
import razie.hosting.{Website, WikiReactors}
import razie.tconf.Visibility
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.model.features.WForm
import razie.wiki.util.PlayTools
import razie.wiki.{Config, Enc, Sec, Services}

/** realm/reactor controller */
import com.google.inject.Singleton
@Singleton
class Realm extends RazController with Logging {

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  val RK: String = Wikis.RK

  /** POSTed - start creating a new wiki with template. most parms are captured as form fields, in queryParms
    * @param cat is the category to create
    * @param templateWpath is the wpath to the template to use, usually a section
    * @param torspec is Spec vs Template
    */
  def createR2(cat:String, templateWpath: String, torspec:String, realm:String="rk") = FAUR { implicit request =>
    val au = request.au.get

    val data = PlayTools.postData(request.req)
    val name = data("name")

      val wid =
        if(cat == "Reactor") WID(cat, name).r(name).formatted // the wid to create
        else WID(cat, name).r(realm).formatted // the wid to create

    (for (
      au <- activeUser;
      isNew <- Wikis.find(wid).isEmpty orErr "Reactor with same name already created";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      n1 <- name.matches("[a-zA-Z0-9_ -]+") orErr "no special characters in the name";
      n2 <- (name.length >= 3 && name.length < 30) orErr "name too short or too long";
      twid <- WID.fromPath(templateWpath) orErr s"template/spec wpath $templateWpath not parsed";
      tw <- Wikis(realm).find(twid) orErr s"template/spec $twid not found";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
        val parms =
          (
            if("Reactor" == cat) Map(
            "reactor"-> name,
            "realm"-> name,
            "access"-> data.getOrElse("access","Public")
            ) else Map.empty
          ) ++ Map(
            "name" -> name,
            "description"-> data.getOrElse("description","no description")
        ) ++ data

      import com.typesafe.config.ConfigFactory
      import scala.collection.JavaConversions._

      var pages : Iterable[WikiEntry] = Nil
      var addMods : Iterable[String] = Nil

      // do we have pages to create?
      if(tw.section("section", "pages").isDefined) {
        implicit class RConfValue (c:ConfigValue) {
          def s (k:String) =
            Option(c.unwrapped().asInstanceOf[java.util.HashMap[String,String]].get(k)).mkString
        }

        val iwikisc = tw.section("section", "pages").get.content
        // substitution
        val wikisc = parms.foldLeft(iwikisc)((a,b)=>a.replaceAll("\\{\\{\\$\\$"+b._1+"\\}\\}", b._2))
        val wikis = ConfigFactory.parseString(wikisc).resolveWith(ConfigFactory.parseMap(parms))

        pages = wikis.getObject("pages") map {t =>
          val (n, page) = t
          val cat = page s "category"
          val name = page s "name"
          val label = page s "label"
          val tm = page s "template"
          val tags = (cat.toLowerCase :: (page s "tags").split(",").toList).distinct
          val co = Wikis.templateDouble(tm, parms)
          WikiEntry(cat, name, label, "md", co, au._id, tags.distinct.toSeq, name, 1, wid.parent,
            Map("owner" -> au.id,
              WikiEntry.PROP_WVIS -> Visibility.MODERATOR))
          // the realm is changed later just before saving
        }

        if(wikis.hasPath("addMods"))
          addMods = wikis.getObject("addMods") map {t =>
            val (n, page) = t
            val tm = page s "template"
            tm
          }
      }

      val mainPage = pages.find(_.name == name) getOrElse {
        // if the main page doesn't have a custom template, use a default
        val weCo =
          if ("Reactor" == cat && templateWpath.endsWith("#form"))
            Wikis.templateDouble(templateWpath, parms) // use the form that captured it
          else if("Spec" == torspec) {
            // for Specs, if there is a template, use it - otherwise just include the form
            tw.sections.find(_.name == "template").map {sec=>
              if(templateWpath.endsWith("#form"))
                "[[template:"+templateWpath.replaceFirst("#form$", "#template") +"]]\n" // for Specs - just include the form...
              else
                Wikis.templateDouble(templateWpath+"#template", parms)
            } getOrElse "[[include:"+templateWpath+"]]\n" // for Specs - just include the form...
          }
          else if("Template" == torspec) {
            tw.sections.find(_.name == "template").map {sec=>
              if(templateWpath.endsWith("#form"))
                Wikis.templateDouble(templateWpath.replaceFirst("#form$", "#template"), parms)
              else
                Wikis.templateDouble(templateWpath+"#template", parms)
            } getOrElse "[[include:"+templateWpath+"]]\n" // for Specs - just include the form...
          }
        else
          Wikis.templateDouble(s"Category:$cat#template", parms) // last ditch attempt to find some overrides

        var we = WikiEntry(wid.cat, wid.name, s"$name", "md", weCo, au._id, Seq(), realm, 1, wid.parent,
        Map("owner" -> au.id,
          WikiEntry.PROP_WVIS -> Visibility.PRIVATE))

        // add the form fields as formDAta to the main page, if this came from a form
        if(templateWpath.endsWith("#form")) {
          //todo validate data, use errors etc - this requires some rework on forms code
//          val (newData, errors) = wf.validate(data2)
          val j = new org.json.JSONObject()
          parms.foreach(t => j.put(t._1, t._2))
          we = we.copy(content = we.content + "\n\n"+WForm.formData(j))
        }

        we
      }

      // todo visibility? public unless you pay 20$ account

      razie.db.tx(s"create.$cat", au.userName) { implicit txn =>
        UserWiki(au._id, mainPage.uwid, "Admin").create

        if ("Reactor" == cat) {
          mainPage.copy(realm=name).create // create first, before using the reactor just below
          WikiReactors.add(name, mainPage)
          pages = pages.filter(_.name != name) map (_.copy (realm=name))
          // this will also copy verified etc
          request.au.get.update(
            request.au.get.addPerm(
              name, Perm.Moderator.s
            ).copy(realms=au.realms+name)
          )
        } else {
          WikiUtil.applyStagedLinks(mainPage.wid,
            mainPage).create // create first, before using the reactor just below
        }
        cleanAuth(request.au)
        Services ! WikiAudit("CREATE_FROM_TEMPLATE", mainPage.wid.wpath, Some(au._id))
        pages foreach {p=>
          WikiUtil.applyStagedLinks(p.wid, p).create
        }
      }

      SendEmail.withSession(request.realm) { implicit mailSession =>
        au.quota.incUpdates
        au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)

        Emailer.tellAdmin("New REACTOR", au.userName, wid.ahref)
      }

      val x = addMods.toList
      if(x.nonEmpty) {
        x.map(m=>
          addMod2(m, name).apply(request.req).value.get.get
        )
      }

      if("Reactor" == cat)
        Redirect(s"/wikie/switchRealm/$name", SEE_OTHER)
      else
        Redirect(wid.urlRelative(request.realm))
    }) getOrElse
      noPerm(wid, s"Cant' create your $cat ...")
  }

  def sso(id:String, token:String) = Action { request =>
    val tok = Sec.dec(new String(Sec.decBase64(token)))
    val res = Some(id).filter(_ == tok).flatMap {conn=>
      // extract user id and check with token
      val uid = Enc.fromSession(id)
      Users.findUserByEmailEnc(uid).map { u =>
        Audit.logdb("USER_LOGIN.SSO", u.userName, u.firstName + " " + u.lastName + " realm: " + request.host)
        debug("SSO.conn=" + (Services.config.CONNECTED -> Enc.toSession(u.email)))
        Redirect("/").withSession(Services.config.CONNECTED -> Enc.toSession(u.email))
      }
    } getOrElse Redirect("/")
    res
  }

  /** switch to new reactor and sso */
  def switchRealm(realm:String) = RAction { implicit request =>
    // on local razie dev
    if(!(Services.config.isDevMode || Services.config.isRazDevMode) && !request.au.isDefined) {
      Unauthorized(s"OOPS - unauthorized")
    } else {
      // if active and owns target, then sso -
      if (Services.config.isLocalhost) {
        Services.config.isimulateHost = s"$realm.dieselapps.com"
        DieselSettings(None, None, "isimulateHost", Services.config.isimulateHost).set

        Services ! WikiEvent("SWITCH_REALM", "Realm", realm, None)

        Redirect("/", SEE_OTHER)
      } else {
        // send user id and encripted
        val conn = request.session.get(Services.config.CONNECTED).mkString
        val token = Sec.encBase64(Sec.enc(conn))
        val w = Website.forRealm(realm)
        val url = w.map(_.url).getOrElse(s"http://$realm.dieselapps.com")
        Redirect(s"$url/wikie/sso/$conn?token=" + token, SEE_OTHER)
      }
    }
  }

  // cluster mode switch local realm notif
  WikiObservers mini {
    case WikiEvent("SWITCH_REALM", "Realm", realm, _, _, _, _) => {
      if (Services.config.isLocalhost) {
        Services.config.isimulateHost = s"$realm.dieselapps.com"
        DieselSettings(None, None, "isimulateHost", Services.config.isimulateHost).set
      }
    }
  }

  /** proxy to other node */
  def switchNode(node:String) = RAction { implicit request =>
    // on local razie dev
      info(s"switchNode to $node")
    if(!(Services.config.isDevMode || Services.config.isRazDevMode) && !request.au.isDefined) {
      Unauthorized(s"OOPS - unauthorized").withCookies(Cookie("dieselProxyNode", node))
    } else {
      if(node == "lb") {
        info(s"switchNode to $node - reset cookie")
        Redirect("/diesel/listAst", SEE_OTHER).discardingCookies(DiscardingCookie("dieselProxyNode"))
      } else if(node != "local" && node != Services.cluster.clusterNodeSimple) {
        info(s"switchNode to $node")
        Redirect("/diesel/listAst", SEE_OTHER).withCookies(Cookie("dieselProxyNode", node))
      } else {
        info(s"switchNode to $node / which is local")
        // do not discard cookie - it may be served by some other node later...
        Redirect("/diesel/listAst", SEE_OTHER).withCookies(Cookie("dieselProxyNode", node))
      }
    }
  }

  /** simulate this realm as default */
  def setRealm(realm:String) = FAUR { implicit request =>
      if(request.au.exists(_.isAdmin)) {
        // if active and owns target, then sso -
          Services.config.isimulateHost = s"$realm.dieselapps.com"
          Redirect("/", SEE_OTHER)
      } else
        Unauthorized("meh")
  }

  /** start wizard to add module to reactor */
  def addMod1(realm:String) = FAUR("add mod") { implicit request =>
      for (
        au <- request.au;
        can <- controllers.WikiUtil.canEdit(WID("Reactor", realm), auth, None);
        r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
        twid <- Some(WID("Reactor", realm).r(realm));
        uwid <- twid.uwid orErr s"template/spec $realm not found"
      ) yield {
          ROK.k apply { implicit stok =>
            views.html.wiki.wikieAddModule(realm)
          }
      }
  }

  /** POSTed - add module to a reactor
    * @param module is the mod to add
    * @param realm is the id of the reactor to add to
    */
  def addMod2(module:String, realm:String) = FAUR("adding mod") { implicit request =>
    val data = PlayTools.postData(request.req)
    val wid = WID("Reactor", realm).r(realm)

    for (
      au <- request.au;
      twid <- WID.fromPath(module) orErr s"$module not a proper WID";
      tw <- Wikis.dflt.find(twid) orErr s"Module $twid not found";
      reactor <- Wikis.find(wid) orErr s"Reactor $realm not found";
      can <- controllers.WikiUtil.canEdit(wid, auth, Some(reactor));
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
      val parms = Map(
        "reactor"-> realm,
        "realm"-> realm,
        "description"-> data.getOrElse("description","no description")
      ) ++ data

      import com.typesafe.config.ConfigFactory
      import scala.collection.JavaConversions._

      var pages : Iterable[WikiEntry] = Nil

      // do we have pages to create?
      if(tw.section("section", "pages").isDefined) {
        implicit class RConfValue (c:ConfigValue) {
          def s (k:String) = c.unwrapped().asInstanceOf[java.util.HashMap[String,String]].get(k)
        }

        val iwikisc = tw.section("section", "pages").get.content
        val wikisc = parms.foldLeft(iwikisc)((a,b)=>a.replaceAll("\\$\\{"+b._1+"\\}", b._2))
        val wikis = ConfigFactory.parseString(wikisc).resolveWith(ConfigFactory.parseMap(parms))

        pages = wikis.getObject("pages") flatMap {t =>
          val (n, page) = t
          val cat = page s "category"
          val name = page s "name"
          val label = page s "label"
          val tm = page s "template"
          val co = Wikis.templateDouble(tm, parms)
          // if page with same exists, forget it (make sure it didn't fallback to another realm)
          if(WID(cat,name).r(realm).page.exists(_.realm == realm)) Nil
          else List(WikiEntry(cat, name, label, "md", co, au._id, Seq(), realm, 1, None,
          Map("owner" -> au.id,
            WikiEntry.PROP_WVIS -> Visibility.PRIVATE))
          )
        }
      }

      pages = pages.map(we=> we.cloneProps(we.props ++ Map("owner" -> au.id), au._id))

      // todo visibility? public unless you pay 20$ account

      razie.db.tx(s"addMod.$module", au.userName) { implicit txn =>
        pages foreach{we=>
          we.create
          Services ! WikiAudit(WikiAudit.CREATE_WIKI, we.wid.wpathFull, Some(au._id), None, Some(we))
        }
      }

      Services ! WikiAudit("CREATE_MOD", tw.wid.wpath, Some(au._id))
      Services ! WikiAudit(WikiAudit.UPD_EDIT, reactor.wid.wpathFull, Some(au._id), None, Some(reactor), Some(reactor))
      // clean caches etc

      SendEmail.withSession(request.realm) { implicit mailSession =>
        au.quota.incUpdates
        au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, reactor.wid)) // ::: notifyFollowers (we)

        Emailer.tellAdmin("ADD MOD", au.userName, reactor.wid.ahref)
      }

      Redirect(controllers.WikiUtil.w(reactor.wid, true)).flashing("count" -> "0")
    }
  }
}

