/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.wiki.util

import com.mongodb.DBObject
import com.mongodb.casbah.Imports.{DBObject, _}
import com.mongodb.util.JSON
import salat._
import controllers.PU
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.audit.Audit
import razie.db.RazSalatContext._
import razie.db._
import razie.db.tx.txn
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.{P, PValue}
import razie.diesel.dom.RDOM.P.asString
import razie.diesel.engine.nodes.EMsg
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.diesel.samples.DomEngineUtils
import razie.tconf.{DUsers, FullSpecRef, TagQuery}
import razie.wiki.Sec._
import razie.wiki.{Enc, Services}
import razie.wiki.model._
import model.{Profile, User}

/** user persistance */
class UsersPersistDiesel extends model.UsersPersist {

  private final val TQ = new TagQuery("diesel.common,diesel.users,database,inventory,elk")

  var dfltRealm = "specs"
  var dfltTarget = DieselTarget.ENV(dfltRealm, "local", TQ)

  /** for now these user persists are static - they need config from a realm */
  def setDefaultRealm(realm:String) = {
    dfltRealm = realm
    dfltTarget = DieselTarget.ENV(dfltRealm, "local", TQ)
  }

  /** msg returns array of json take first as option */
  def resolveOpt(m: EMsg): Option[String] = resolveList(m).headOption

  // todo need to make this faster, not to string and back
  val toUserM   = (s:collection.Map[String,Any]) => {
    val str = razie.js.tojsons(s)
    val dbo = com.mongodb.util.JSON.parse(str).asInstanceOf[DBObject];
    dbo.put("_id", dbo.get("mongoId"))
    grater[User].asObject(dbo)
  }
  val toProfileM   = (s:collection.Map[String,Any]) => {
    val str = razie.js.tojsons(s)
    val dbo = com.mongodb.util.JSON.parse(str).asInstanceOf[DBObject];
    dbo.put("_id", dbo.get("mongoId"))
    grater[Profile].asObject(dbo)
  }
  val toUser    = (s:String) => grater[User].fromJSON(s)
  val toProfile = (s:String) => grater[Profile].fromJSON(s)

  /** msg returns array of json take first as option */
  def resolve(m: EMsg): P = {
    val res = DomEngineUtils
        .runMsgSync(new DieselMsg(m, dfltTarget), 10)
        .getOrElse(P.undefined(Diesel.PAYLOAD))
    res
  }

  /** msg returns json of {total, data:[] } */
  def resolveDataOpt(m: EMsg): Option[collection.Map[String,Any]] = {
    resolveDataList(m)
        .headOption
  }

  /** msg returns json of {total, data:[] } */
  def resolveDataList(m: EMsg): List[collection.Map[String,Any]] = {
    resolveJson(m)
      .get("data")
      .map(
        _.asInstanceOf[collection.Seq[_]]
        .map(_.asInstanceOf[collection.Map[String,Any]])
        .toList
      ).getOrElse(Nil)
  }

  /** msg returns array of json take first as option */
  def resolveJson(m: EMsg): collection.Map[String, Any] = {
    val res = resolve(m)
    res.getJsonStructure
  }

  /** msg returns array of json take first as option */
  def resolveList(m: EMsg): List[String] = {
    val res = resolve(m)
    val arr = res.value.getOrElse(PValue.emptyArray).asArray
    arr.toList.map(x => res.currentStringValue)
  }

  /** msg returns array of json take first as option */
  def resolveStr(m: EMsg): String = {
    val res = resolve(m)
    asString(res)
  }

  def msg(name: String, p: List[P]) =
    new EMsg(
      "diesel.users.impl",
      name,
      p
    )

  /** find user by lowercase email - at loging
    *
    * @param uncEmail unencoded email
    * @return user if found
    */
  def findUserNoCase(uncEmail: String): Option[User] = {
    resolveDataOpt(msg(
      "findUserNoCase",
      List(
        P.of("uncEmail", uncEmail)
      ))).map(toUserM)

    // todo optimize somwhow
//    val tl = uncEmail.toLowerCase()
//    RazMongo("User") findAll() filter (_.containsField("email")) find (x => x.as[String](
//      "email") != null && x.as[String]("email").dec.toLowerCase == tl) map (grater[User].asObject(_))
  }

  /** find user by lowercase email - at loging */
  def findUsersForRealm(realm: String): scala.Iterator[User] = {
    resolveDataList(msg(
      "findUsersForRealm",
      List(
        P.of("realm", realm)
      ))).map(toUserM).iterator

//    RazMongo("User") findAll() filter (x =>
//      realm == "*" ||
//          x.containsField("realms") &&
//              x.as[Seq[String]]("realms") != null &&
//              x.as[Seq[String]]("realms").contains(realm)
//        ) map (
//        grater[User].asObject(_)
//        ) filter (u =>
//      realm == "*" || (u.realms contains realm)
//        )
  }

  /** find by encrypted email */
  def findUserByEmailEnc(emailEnc: String): Option[User] =
    resolveDataOpt(msg(
      "findUserByEmailEnc",
      List(
        P.of("email", emailEnc)
      ))).map(toUserM)

  /** find by encrypted email */
  def findUserByApiKey(key: String): Option[User] =
    resolveDataOpt(msg(
      "findUserByApiKey",
      List(
        P.of("apiKey", key)
      ))).map(toUserM)

  def findUserById(id: ObjectId): Option[User] =
    resolveDataOpt(msg(
      "findUserById",
      List(
        P.of("id", id)
      ))).map(toUserM)


  def findUserByUsername(uname: String): Option[User] =
    resolveDataOpt(msg(
      "findUserByUsername",
      List(
        P.of("userName", uname)
      ))).map(toUserM)


  /** display name of user with id, for comments etc */
  def nameOf(uid: ObjectId): String = {
    resolveStr(msg(
      "nameOf",
      List(
        P.of("id", uid.toString)
      )))

//    val n = ROne.raw[User]("_id" -> uid).fold("???")(_.apply("userName").toString)
  }

  def findProfileByUserId(userId: String): Option[Profile] = {
    resolveDataOpt(msg(
      "findProfileByUserId",
      List(
        P.of("userId", userId)
      ))).map(toProfileM)
  }

  def createProfile(p:Profile) = {
    resolveStr(msg(
      "createProfile",
      List(
        P.of("profile", p.toJsonSafe)
      )))
  }

  def updateProfile(p:Profile) = {
    resolveStr(msg(
      "updateProfile",
      List(
        P.of("profile", p.toJson)
      )))
  }

  def updateUser(oldu:User, newu:User) = {
    resolveStr(msg(
      "updateUser",
      List(
        P.of("oldUser", newu.toJson),
        P.of("newUser", newu.toJson)
      )))
  }//RazMongo("User").update(key, grater[User].asDBObject(Audit.update(newu.copy(updDtm = Some(DateTime.now())))))

  def createUser(newu:User) = {
    val n = newu.copy(emailLower = Some(Enc(newu.emailDec.toLowerCase)))
    resolveStr(msg(
      "createUser",
      List(
        P.of("user", newu.toJsonSafe)
      )))
  }

  /** one-time migration from mongo to elk */
  def migrate() = {

    var limit = 30
    var count = 0

    RMany[User]().take(limit).foreach{u =>
      razie.Log("Migrating user: " + u)
      createUser(u)
      count += 1
    }

    RMany[Profile]().take(limit).foreach{u =>
      razie.Log("Migrating profile: " + u)
      createProfile(u)
      count += 1
    }

    count
  }
}

