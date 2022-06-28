/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import org.bson.types.ObjectId
import razie.db._

/** overall settings and state for this deployment */
@RTable
case class DieselSettings(uid: Option[String], realm: Option[String], name: String, value: String, _id: ObjectId =
new ObjectId) {
  def set() = {
    import razie.db.tx.txn

    ROne[DieselSettings]("uid" -> uid, "realm" -> realm, "name" -> name).map { s =>
      // todo cluster propagate notification? use WikiConfigChanged(node, config)
      RUpdate[DieselSettings](s.copy(value = this.value))
    }.getOrElse {
      RCreate[DieselSettings](this)
    }
  }
}

/** utility for diesel settings */
object DieselSettings {
  def findOrElse (uid:Option[String], realm:Option[String], name:String, default:String) : String =
    ROne[DieselSettings]("uid" -> uid, "realm" -> realm, "name"->name).map(_.value).getOrElse(default)

  def find(uid:Option[String], realm:Option[String], name:String) : Option[String] =
    ROne[DieselSettings]("uid" -> uid, "realm" -> realm, "name"->name).map(_.value)
}
