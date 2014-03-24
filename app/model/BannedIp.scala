package model

import org.joda.time.DateTime

import com.mongodb.casbah.Imports.DBObject
import com.mongodb.casbah.Imports.ObjectId
import com.mongodb.casbah.Imports.wrapDBObj
import com.mongodb.util.JSON
import com.novus.salat.grater

import db.REntity
import db.RMany
import db.ROne
import db.RazSalatContext.ctx

/** register an IP address to be banned */
@db.RTable
case class BannedIp (
    ip:String,
    reason:String, 
    crDtm: DateTime = DateTime.now,
    _id:ObjectId = new ObjectId()) extends REntity[BannedIp]

/** user factory and utils */
object BannedIps {
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
