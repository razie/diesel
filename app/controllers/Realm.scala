/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.mongodb.casbah.Imports._
import com.typesafe.config.ConfigValue
import controllers.Wikie._
import model.{UserWiki, Perm}
import org.bson.types.ObjectId
import scala.Array.canBuildFrom
import com.mongodb.DBObject
import razie.db.{REntity, RazMongo, ROne, RMany}
import razie.db.RazSalatContext.ctx
import razie.wiki.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{SimpleResult, AnyContent, Action, Request}
import razie.{cout, Logging, clog}
import razie.wiki.model._
import razie.wiki.util.{PlayTools, VErrors}
import razie.wiki.admin.{SendEmail, Audit}

/** realm/reactor controller */
object Realm extends RazController with Logging {

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  val RK: String = Wikis.RK

  /** POSTed - start creating a new wiki with template. most parms are captured as form fields, in queryParms
    * @param cat is the category to create
    * @param templateWpath is the wpath to the template to use, usually a section
    * @param torspec is Spec vs Template
    */
  def createR2(cat:String, templateWpath: String, torspec:String, realm:String="rk") = FAU {
    implicit au => implicit errCollector => implicit request =>

    val data = PlayTools.postData
    val name = data("name")

    val wid = WID(cat, name).r(name).formatted // the wid to create

    (for (
      au <- activeUser;
      isNew <- Wikis.find(wid).isEmpty orErr "Reactor with same name already created";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      n1 <- name.matches("[a-zA-Z0-9_ -]+") orErr "no special characters in the name";
      n2 <- (name.length >= 3 && name.length < 20) orErr "name too short or too long";
      twid <- WID.fromPath(templateWpath) orErr s"template/spec wpath $templateWpath not found";
      tw <- Wikis(realm).find(twid) orErr s"template/spec $twid not found";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
        val parms =
          (
            if("Reactor" == cat) Map(
            "reactor"-> name,
            "realm"-> name) else Map.empty
            ) ++ Map(
            "name" -> name,
            "description"-> data.getOrElse("description","no description")
        ) ++ data

      import com.typesafe.config.{Config, ConfigFactory}
      import scala.collection.JavaConversions._

      var pages : Iterable[WikiEntry] = Nil

      // do we have pages to create?
      if(tw.section("section", "pages").isDefined) {
        implicit class RConfValue (c:ConfigValue) {
          def s (k:String) = c.unwrapped().asInstanceOf[java.util.HashMap[String,String]].get(k)
        }

        val iwikisc = tw.section("section", "pages").get.content
        // substitution
        val wikisc = parms.foldLeft(iwikisc)((a,b)=>a.replaceAll("\\$\\{"+b._1+"\\}", b._2))
        val wikis = ConfigFactory.parseString(wikisc).resolveWith(ConfigFactory.parseMap(parms))

        pages = wikis.getObject("pages") map {t =>
          val (n, page) = t
          val cat = page s "category"
          val name = page s "name"
          val label = page s "label"
          val tm = page s "template"
          val co = Wikis.template(tm, parms)
          WikiEntry(cat, name, label, "md", co, au._id, Seq(), name, 1, wid.parent,
            Map("owner" -> au.id,
              WikiEntry.PROP_WVIS -> Visibility.PRIVATE))
          // the realm is changed later just before saving
        }
      }

      val mainPage = pages.find(_.name == name) getOrElse {
        // if the main page doesn't have a custom template, use a default
        val weCo =
          if ("Reactor" == cat && templateWpath.endsWith("#form"))
            Wikis.template(templateWpath, parms) // use the form that captured it
          else if("Spec" == torspec) {
            // for Specs, if there is a template, use it - otherwise just include the form
            tw.sections.find(_.name == "template").map {sec=>
              if(templateWpath.endsWith("#form"))
                "[[template:"+templateWpath.replaceFirst("#form$", "#template") +"]]\n" // for Specs - just include the form...
              else
                Wikis.template(templateWpath+"#template", parms)
            } getOrElse "[[include:"+templateWpath+"]]\n" // for Specs - just include the form...
          }
        else
          Wikis.template(s"Category:$cat#template", parms) // last ditch attempt to find some overrides

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

      razie.db.tx(s"create.$cat") { implicit txn =>
        UserWiki(au._id, mainPage.uwid, "Owner").create

        if ("Reactor" == cat) {
          mainPage.copy(realm=name).create // create first, before using the reactor just below
          Reactors.add(name, mainPage)
          pages = pages.filter(_.name != name) map (_.copy (realm=name))
        } else {
          mainPage.create // create first, before using the reactor just below
        }
        cleanAuth()
        Audit ! WikiAudit("CREATE_FROM_TEMPLATE", mainPage.wid.wpath, Some(au._id))
        pages foreach(_.create)
      }

      SendEmail.withSession { implicit mailSession =>
        au.quota.incUpdates
        au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)

        Emailer.tellRaz("New REACTOR", au.userName, wid.ahref)
      }

      Redirect(controllers.Wiki.w(mainPage.wid, true)).flashing("count" -> "0")
    }) getOrElse
      noPerm(wid, s"Cant' create your $cat ...")
  }

  /** start wizard to add module to reactor */
  def addMod1(realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      (for (
        can <- canEdit(WID("Reactor", realm), auth, None);
        r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
        twid <- Some(WID("Reactor", realm).r(realm));
        uwid <- twid.uwid orErr s"template/spec $realm not found"
      ) yield {
        Ok(views.html.wiki.wikieAddModule(realm, auth))
      }) getOrElse
        Msg2("Can't find the reactor..." + errCollector.mkString)
  }

  /** POSTed - add module to a reactor
    * @param module is the mod to add
    * @param realm is the id of the reactor to add to
    */
  def addMod2(module:String, realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>

    val data = PlayTools.postData
    val wid = WID("Reactor", realm).r(realm)

    (for (
      twid <- WID.fromPath(module) orErr s"$module not a proper WID";
      tw <- Wikis.dflt.find(twid) orErr s"Module $twid not found";
      reactor <- Wikis.find(wid) orErr s"Reactor $realm not found";
      can <- canEdit(wid, auth, Some(reactor));
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
      val parms = Map(
        "reactor"-> realm,
        "realm"-> realm,
        "description"-> data.getOrElse("description","no description")
      ) ++ data

      import com.typesafe.config.{Config, ConfigFactory}
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
          val co = Wikis.template(tm, parms)
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

      Audit ! WikiAudit("CREATE_MOD", tw.wid.wpath, Some(au._id))

      razie.db.tx(s"addMod.$module") { implicit txn =>
        pages foreach(_.create)
      }

      SendEmail.withSession { implicit mailSession =>
        au.quota.incUpdates
        au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, reactor.wid)) // ::: notifyFollowers (we)

        Emailer.tellRaz("ADD MOD", au.userName, reactor.wid.ahref)
      }

      Redirect(controllers.Wiki.w(reactor.wid, true)).flashing("count" -> "0")
    }) getOrElse
      Msg2("Can't add module: " + errCollector.mkString)
  }
}

