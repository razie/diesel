package controllers

import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier
import akka.cluster.Cluster
import com.mongodb.casbah.Imports.{DBObject, IntOk}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.grater
import difflib.DiffUtils
import mod.notes.controllers.NotesLocker
import org.json.{JSONArray, JSONObject}
import play.api.libs.concurrent.Akka
import play.api.libs.json.JsObject
import play.twirl.api.Html
import razie.db.{RMany, RazMongo}
import razie.db.RazSalatContext.ctx
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc._
import razie.g.snakked
import razie.wiki.util.PlayTools
import razie.js
import razie.wiki.Enc
import razie.wiki.model.{WID, WikiEntry, Wikis}
import razie.wiki.admin.{MailSession, GlobalData, SendEmail}
import admin.MdbAuditService
import model._
import x.context

import scala.util.Try
import razie.Snakk._
import razie.wiki.Sec._
import scala.collection.JavaConversions._

object Api extends AdminBase {

  class WeRequest[A] (val au:Option[User], val errCollector:VErrors, val request:Request[A])
    extends WrappedRequest[A] (request) {
    def realm = Website.getRealm(request)
  }

  implicit def errCollector (r:WeRequest[_]) = r.errCollector

  def NFAU(f: WeRequest[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser
    ) yield {
        f(new WeRequest[AnyContent](Some(au), errCollector, request))
      }) getOrElse unauthorized("CAN'T")
  }

  def wix = NFAU {implicit request =>
    Ok(api.wix(None, request.au, Map.empty, request.realm).json).as("text/json")
  }

  def ownedPages(role: String, cat:String) = NFAU {implicit request =>
    Ok(api.wix(None, request.au, Map.empty, request.realm).json).as("text/json")
  }

  def user(id: String) = NFAU { implicit request =>
    ROK.r admin {implicit stok=>
      views.html.admin.adminUser(model.Users.findUserById(id))}
  }
}

