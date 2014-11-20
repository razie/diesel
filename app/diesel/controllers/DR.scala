/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package diesel.controllers

import admin._
import com.mongodb.casbah.Imports._
import com.novus.salat._
import controllers.RazController
import db.RazSalatContext._
import com.mongodb.{BasicDBObject, DBObject}
import db.{ROne, RazMongo}
import diesel.model._
import model.{CMDWID, WikiEntryOld, _}
import play.api.mvc.{Action, AnyContent, Request}
import razie.{cout, Logging}

object DR extends RazController with Logging {
  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  // todo eliminate stupidity
  var re:Option[DieselReactor] = None
  var dbr:Option[DReactor] = None

  /** load and initialize a reactor */
  private def iload(uname:String, rname: String) = {
    if(dbr.isEmpty) dbr = DReactors.find(uname, rname).flatMap(_.page) map DReactors.apply
    if(re.isEmpty) re = dbr map (_.load)
    re
  }

  /** (re)load a reactor */
  def load(uname:String, rname: String) = Action { implicit request=>
    Audit("x", "DR_LOAD", s"$uname/$rname")
    dbr=None
    re=None
    iload(uname, rname) map (_.activate)
    Ok(re.mkString)
  }

  /** show a reactor */
  def show(uname:String, rname: String) = Action { implicit request=>
    Audit("x", "DR_SHOW", s"$uname/$rname")
    Redirect(s"/wiki/$rname")
  }

  /** interact with a reactor */
  def react(uname:String, rname: String, event:String) = FAU { implicit au => implicit errCollector => implicit request=>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    Audit("x", "DR_EVENT", s"$uname/$rname/$event with ${q.mkString}")
    val res = iload(uname, rname) map (_.react(event, q))
    Ok(res.mkString)
  }

}


