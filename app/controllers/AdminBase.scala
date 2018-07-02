package controllers

import com.google.inject.Singleton
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
import mod.snow.RK
import org.json.{JSONArray, JSONObject}
import play.api.libs.concurrent.Akka
import play.api.libs.json.JsObject
import play.twirl.api.Html
import razie.db.{RCreate, RMany, RazMongo, WikiTrash}
import razie.db.RazSalatContext.ctx
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc.{Action, AnyContent, Request, Result}
import razie.g.snakked
import razie.{cout, js}
import razie.wiki.{Enc, Services}
import razie.wiki.model.{Perm, WID, WikiEntry, Wikis}
import razie.wiki.admin.{GlobalData, MailSession, SendEmail}
import razie.audit.ClearAudits
import model.{User, Users, WikiScripster}
import x.context
import razie.hosting.Website

import scala.util.Try
import razie.Snakk._
import razie.audit.{Audit, ClearAudits}
import razie.wiki.Sec._
import razie.wiki.util.DslProps

import scala.collection.JavaConversions._
import scala.concurrent.Future

class AdminBase extends RazController {
  protected def forAdmin[T](body: => play.api.mvc.Result)(implicit request: Request[_]) = {
    if (auth.map(_.hasPerm(Perm.adminDb)) getOrElse false) body
    else noPerm(HOME)
  }

  protected def FA[T](body: Request[_] => play.api.mvc.Result) = Action { implicit request =>
    forAdmin {
      body(request)
    }
  }

  def FAD(f: User => VErrors => Request[AnyContent] => Result) = Action { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      can <- au.hasPerm(Perm.adminDb) orErr "no permission"
    ) yield {
      f(au)(errCollector)(request)
    }) getOrElse unauthorized("CAN'T")
  }

  /** action builder that decomposes the request, extracting user and creating a simple error buffer */
  def FADR(f: RazRequest => Result) = Action { implicit request =>
    val req = razRequest
    (for (
      au <- req.au;
      isA <- checkActive(au);
      can <- au.hasPerm(Perm.adminDb) orErr "no permission"
    ) yield {
        f(req)
    }) getOrElse unauthorized("CAN'T")
  }

  // use my layout
  implicit class StokAdmin (s:StateOk) {
    def admin (content: StateOk => Html) = {
      RkViewService.Ok (views.html.admin.adminLayout(content(s))(s))
    }
  }
}
