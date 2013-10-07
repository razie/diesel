package model

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import admin.Audit
import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import admin.CipherCrypt
import java.net.URLEncoder
import com.mongodb.util.JSON
import razie.Log
import controllers.UserStuff
import model.Sec._
import controllers.Maps
import controllers.RazController
import play.api.cache.Cache
import admin.MailSession
import controllers.Emailer
import db.RTable
import scala.annotation.StaticAnnotation
import db.ROne
import db.RMany
import db.RCreate
import db.RDelete
import db.Mongo

/** register an email for news */
@db.RTable
case class BannedIp (
    ip:String,
    reason:String, 
    crDtm: DateTime = DateTime.now,
    _id:ObjectId = new ObjectId()) {

  def toJson = grater[BannedIp].asDBObject(this).toString
  def create = { Audit.create(this); RCreate[BannedIp](this) }
  def delete = { Audit.delete(this); RDelete[BannedIp]("_id" -> _id) }
  def update = Mongo("BannedIp").m.update(Map("_id" -> _id), grater[BannedIp].asDBObject(Audit.update(this)))
}

/** user factory and utils */
object BannedIps {
  def fromJson(j: String) = Option(grater[BannedIp].asObject(JSON.parse(j).asInstanceOf[DBObject]))

  def findId(id:String) = ROne[BannedIp]("_id" -> new ObjectId(id))
  def load = RMany[BannedIp]()
  
  lazy val all = collection.mutable.Map()++load.toList.map(x=>(x.ip -> "")).toMap
  def isBanned(ip:String) = all.contains(ip)
  def isBanned(ip:Option[String]) = ip.map(all.contains(_)).getOrElse(false)
  
  def ban(ip:String, reason:String) = {
    BannedIp(ip, reason).create
    all += ip -> ""
  }
}
