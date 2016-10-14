/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin._
import com.mongodb.casbah.Imports._
import com.novus.salat._
import mod.diesel.controllers.DieselControl
import model._
import play.twirl.api.Html
import razie.base.Audit
import razie.db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import razie.db.{ROne, RazMongo}
import play.api.mvc.{Action, AnyContent, Request}
import razie.diesel.dom.RDOM
import razie.wiki.util.PlayTools
import razie.{cout, Logging}
import razie.wiki.model._
import views.html.wiki.wikieUsage
import scala.Array.canBuildFrom
import razie.wiki.{Services, Enc}
import razie.wiki.dom.WikiDomain
import razie.wiki.model.WikiAudit

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import Visibility.PUBLIC

import scala.util.Try

/** wiki controller */
object WikiTesting extends WikiBase {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  def fragById(cat:String, id: String, irealm:String) = Action { implicit request =>
    val realm = getRealm(irealm)
    ((for (au <- auth;
           wid <- UWID(cat, new ObjectId(id)).wid;
           w <- Wiki.cachedPage(wid, Some(au))//Wikis(realm).findById(cat, id)
     ) yield ROK.r noLayout { implicit stok =>
//      new Html(
        // some info for testing threads and concurrent users etc
//        Html("username="+auth.map(_.userName).mkString),
//        Html("css="+auth.map(_.userName).mkString),
        views.html.wiki.wikiFrag(w.wid, None, true, Some(w))
//      )
    }) getOrElse NotFound("wiki not found")).withHeaders("Access-Control-Allow-Origin" -> "*")
    //allow tests ran in other pages
  }
}


