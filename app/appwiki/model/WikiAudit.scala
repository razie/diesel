/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import org.bson.types.ObjectId
import org.joda.time.DateTime
import db.RCreate
import db.RTable
import razie.clog
import admin.Audit


/**
 * auditing events on wiki pages, like edits, views etc
 *
 *  TODO should probably allow customization to track only interesting events, i.e. filters
 *
 *  for now - just purge the table as you feel like...
 */
@db.RTable
case class WikiAudit(
  event: String,
  wpath: String,
  userId: Option[ObjectId],
  crDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

  def create = RCreate noAudit this
}
