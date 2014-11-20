/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.mongodb.casbah.Imports._
import com.typesafe.config.ConfigValue
import controllers.Wikie._
import org.bson.types.ObjectId

import scala.Array.canBuildFrom
import com.mongodb.DBObject
import admin._
import model._
import db.{REntity, RazMongo, ROne, RMany}
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{SimpleResult, AnyContent, Action, Request}
import razie.{cout, Logging, clog}

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

    val data = request.body.asFormUrlEncoded.map(_.collect { case (k, v :: r) => (k, v) }).get // somehow i get list of values?
    val name = data("name")

    val wid = WID(cat, name).r(realm).formatted // the wid to create

    (for (
      au <- activeUser;
      isNew <- Wikis.find(wid).isEmpty orErr "Reactor with same name already created";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates;
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      n1 <- name.matches("[a-zA-Z0-9_ -]+") orErr "no special characters in the name";
      n2 <- (name.length > 3 && name.length < 20) orErr "name too short or too long";
      twid <- WID.fromPath(templateWpath) orErr s"template/spec wpath $templateWpath not found";
      tw <- Wikis(realm).find(twid) orErr s"template/spec $twid not found";
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
	val parms = Map(
	  "realm"-> name,
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
	val wikisc = parms.foldLeft(iwikisc)((a,b)=>a.replaceAll("\\$\\{"+b._1+"\\}", b._2))
	val wikis = ConfigFactory.parseString(wikisc).resolveWith(ConfigFactory.parseMap(parms))

	pages = wikis.getObject("pages") map {t =>
	  val (n, page) = t
	  val cat = page s "category"
	  val name = page s "name"
	  val label = page s "label"
	  val tm = page s "template"
	  val co = Wikis.template(tm, parms)
	  model.WikiEntry(cat, name, label, "md", co, au._id, Seq(), realm, 1, wid.parent, Map("owner" -> au.id))
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
//		  Realm.template(templateWpath.replaceFirst("#form$", "#template"), parms)
	      else
		Wikis.template(templateWpath+"#template", parms)
	    } getOrElse "[[include:"+templateWpath+"]]\n" // for Specs - just include the form...
	  }
	else
	  Wikis.template(s"Category:$cat#template", parms) // last ditch attempt to find some overrides

	var we = model.WikiEntry(wid.cat, wid.name, s"$name", "md", weCo, au._id, Seq(), realm, 1, wid.parent)

	// add the form fields as formDAta to the main page, if this came from a form
	if(templateWpath.endsWith("#form")) {
	  //todo validate data, use errors etc - this requires some rework on forms code
//	    val (newData, errors) = wf.validate(data2)
	  val j = new org.json.JSONObject()
	  parms.foreach(t => j.put(t._1, t._2))
	  we = we.copy(content = we.content + "\n\n"+WForm.formData(j))
	}

	we
      }

      // todo visibility? public unless you pay 20$ account

      db.tx(s"create.$cat") { implicit txn =>
	this dbop model.UserWiki(au._id, mainPage.uwid, "Owner").create
	mainPage.create // create first, before using the reactor just below
	cleanAuth()
	Audit ! WikiAudit("CREATE_FROM_TEMPLATE", mainPage.wid.wpath, Some(au._id))
	if ("Reactor" == cat) {
	  Reactors add name
	  pages = pages.filter(_.name != name) map (_.copy (realm=name))
	}
	pages foreach(_.create)
      }

      admin.SendEmail.withSession { implicit mailSession =>
	au.quota.incUpdates
	au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, wid)) // ::: notifyFollowers (we)

	Emailer.tellRaz("New REACTOR", au.userName, wid.ahref)
      }

      Redirect(controllers.Wiki.w(mainPage.wid, true)).flashing("count" -> "0")
    }) getOrElse
      noPerm(wid, s"Cant' create your $cat ...")
  }

  def addModule1(realm:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      (for (
	au <- activeUser;
	//todo check permissions to create reactors
	//	can <- canEdit(wid, auth, Some(w));
	//	r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
	twid <- WID.fromPath(s"Reactor:$realm") orErr s"template/spec $realm not found";
	uwid <- twid.uwid
      ) yield {
	Ok(views.html.wiki.wikieAddModule(realm, auth))
      }) getOrElse
	Msg2("Can't find the reactor...")
  }

  /** POSTed - add module to a reactor
    * @param module is the mod to add
    * @param realm is the id of the reactor to add to
    */
  def addModule2(module:String, realm:String) = Action { implicit request =>
    implicit val errCollector = new VErrors()

    val data = request.body.asFormUrlEncoded.map(_.collect { case (k, v :: r) => (k, v) }) getOrElse Map.empty // somehow i get list of values?
    val wid = WID("Reactor", realm)

    (for (
      au <- activeUser;
      twid <- WID.fromPath(module) orErr s"$module not a proper WID";
      tw <- Wikis.rk.find(twid) orErr s"Module $twid not found";
      reactor <- Wikis.rk.find(wid) orErr s"Reactor $realm not found";
      can <- canEdit(wid, auth, Some(reactor));
      r1 <- au.hasPerm(Perm.uWiki) orCorr cNoPermission;
      hasQuota <- (au.isAdmin || au.quota.canUpdate) orCorr cNoQuotaUpdates
    ) yield {
      val parms = Map(
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
	  if(WID(cat,name).r(realm).page.isDefined) Nil
	  else List(model.WikiEntry(cat, name, label, "md", co, au._id, Seq(), realm, 1, None))
	}
      }

      pages = pages.map(we=> we.cloneProps(we.props ++ Map("owner" -> au.id), au._id))

      // todo visibility? public unless you pay 20$ account

      db.tx(s"addMod.$module") { implicit txn =>
	Audit ! WikiAudit("CREATE_MOD", tw.wid.wpath, Some(au._id))
	pages foreach(_.create)
      }

      admin.SendEmail.withSession { implicit mailSession =>
	au.quota.incUpdates
	au.shouldEmailParent("Everything").map(parent => Emailer.sendEmailChildUpdatedWiki(parent, au, reactor.wid)) // ::: notifyFollowers (we)

	Emailer.tellRaz("ADD MOD", au.userName, reactor.wid.ahref)
      }

      Redirect(controllers.Wiki.w(reactor.wid, true)).flashing("count" -> "0")
    }) getOrElse
      Msg2("Can't add module: " + errCollector.mkString)
  }
}

