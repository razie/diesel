/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import org.bson.types.ObjectId
import admin.NoAuthService

/** basic user concept - you have to provide your own implementation */
abstract class WikiUser {
  def userName: String
  def email: String
  def _id: ObjectId
  
  def ename: String // make up a nice name: either first name or email or something
  
  /** pages of category that I linked to */
  def myPages (cat: String) : List[Any]
}

/** user factory and utils */
trait WikiUsers {
  def findUserById(id: ObjectId) : Option [WikiUser]
}

/** sample dummy */
object NoWikiUsers extends WikiUsers {
  def findUserById(id: ObjectId) : Option [WikiUser] = Some(NoAuthService.harry)
}

/** provide implementation in Global::beforeStart() */
object WikiUsers {
  var impl : WikiUsers = NoWikiUsers
}
