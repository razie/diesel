package model

import org.joda.time.DateTime

import com.novus.salat.grater

import admin.Audit
import model.RazSalatContext.ctx

case class Support(
  email: String,
  desc: String,
  closed: Boolean,
  resolution:String,
  createdDtm: DateTime = DateTime.now,
  solvedDtm: Option[DateTime] = None) {

  def create = Mongo ("Support") += grater[Support].asDBObject(Audit.create(this))
  
//  def close (resolution:String) = {
//val newOne = Support(
//  email, desc, closed=true,
//  resolution,
//  createdDtm=createdDtm,
//  solvedDtm=Some(DateTime.now))
//
//    Mongo("Support").m.update(key, grater[Support].asDBObject(Audit.update(this)))
//  }
}
