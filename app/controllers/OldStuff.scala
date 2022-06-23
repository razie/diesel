/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package controllers

import com.mongodb.casbah.Imports.{DBObject, _}
import com.novus.salat.grater
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.audit.Audit
import razie.db.RazMongo
import razie.db.RazSalatContext.ctx

// TODO should I backup old removed entries ?
case class OldStuff(table: String, by: ObjectId, entry: DBObject, date: DateTime = DateTime.now,
                    _id: ObjectId = new ObjectId()) {
  def create = {
    RazMongo("OldStuff") += grater[OldStuff].asDBObject(Audit.createnoaudit(this))
  }
}
