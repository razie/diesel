/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package model

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.mongodb.util.JSON
import salat._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.audit.Audit
import razie.db.RazSalatContext._
import razie.db._
import razie.db.tx.txn
import razie.tconf.DUsers
import razie.wiki.Sec._
import razie.wiki.{Enc, Services}
import razie.wiki.model._

/** user persistance ops interface: users and profiles */
trait UsersPersist {

  /** for now these user persists are static - they need config from a realm */
  def setDefaultRealm(realm:String)

  /** find user by lowercase email - at loging
    *
    * @param uncEmail unencoded email
    * @return user if found
    */
  def findUserNoCase(uncEmail: String): Option[User]

  /** list of users per realm */
  def findUsersForRealm(realm: String): scala.Iterator[User]

  /** find by encrypted email */
  def findUserByEmailEnc(emailEnc: String): Option[User]

  /** find by api key */
  def findUserByApiKey(key: String): Option[User]

  /** find by unique id */
  def findUserById(id: ObjectId): Option[User]

  /** find by username */
  def findUserByUsername(uname: String): Option[User]

  def findProfileByUserId(userId: String): Option[Profile]

  /** display name of user with id, for comments etc */
  def nameOf(uid: ObjectId): String

  def createProfile(p:Profile)
  def updateProfile(p:Profile)
  def updateUser(oldu:User, newu:User)
  def createUser(u:User)
}

/** Mongo user persistance (diesel default) */
class UsersPersistMongo extends UsersPersist {

  /** for now these user persists are static - they need config from a realm */
  def setDefaultRealm(realm:String) = {}

  /** find user by lowercase email - at loging
    *
    * @param uncEmail unencoded email
    * @return user if found
    */
  def findUserNoCase(uncEmail: String): Option[User] = {
    // todo optimize somwhow - should store all encoded emails in lowercase and search lowercase too
    val tl = uncEmail.toLowerCase()
    RazMongo("User") findAll() filter (_.containsField("email")) find (x => x.as[String](
      "email") != null && x.as[String]("email").dec.toLowerCase == tl) map (grater[User].asObject(_))
  }

  /** find user by lowercase email - at loging */
  def findUsersForRealm(realm: String): scala.Iterator[User] = {
    // todo optimize somwhow
    RazMongo("User") findAll() filter (x =>
      realm == "*" ||
          x.containsField("realms") &&
              x.as[Seq[String]]("realms") != null &&
              x.as[Seq[String]]("realms").contains(realm)
        ) map (
        grater[User].asObject(_)
        ) filter (u =>
      realm == "*" || (u.realms contains realm)
        )
  }

  /** find by encrypted email */
  def findUserByEmailEnc(emailEnc: String): Option[User] = ROne[User]("email" -> emailEnc)

  /** find by encrypted email */
  def findUserByApiKey(key: String): Option[User] = ROne[User]("apiKey" -> key)

  def findUserById(id: ObjectId): Option[User] = ROne[User](id)

  def findUserByUsername(uname: String): Option[User] = ROne[User]("userName" -> uname)

  import play.api.Play.current

  /** display name of user with id, for comments etc */
  def nameOf(uid: ObjectId): String = {
    val n = ROne.raw[User]("_id" -> uid).fold("???")(_.apply("userName").toString)
    n
  }

  def findProfileByUserId(userId: String): Option[Profile] = ROne[Profile]("userId" -> new ObjectId(userId))

  def createProfile(p:Profile) = RCreate(p)
  def updateProfile(p:Profile) = RUpdate(Map("userId" -> p.userId), p)
  def updateUser(oldu:User, newu:User) = RazMongo("User").update(Map("email" -> oldu.email), grater[User].asDBObject(Audit.update(newu.copy(updDtm = Some(DateTime.now())))))
  def createUser(newu:User) = RCreate(newu)

}

/** bridge for base wiki users interface */
object WikiUsersImpl extends DUsers[User] {
  def findUserById(id: String) = Users.findUserById(id)

  def findUserById(id: ObjectId) = Users.findUserById(id)

  def findUserByUsername(uname: String) = Users.findUserByUsername(uname)

  def findUserByEmailDec(emailDec: String) = Users.findUserByEmailDec(emailDec)
}

