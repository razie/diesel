/*
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.wiki

import com.google.inject._
import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import controllers.WikiUtil._
import difflib.{DiffUtils, Patch}
import mod.diesel.model.Diesel
import mod.notes.controllers.DomC.retj
import mod.snow.RacerKidz
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.Configuration
import play.api.mvc.Request
import razie.audit.Audit
import razie.cout
import razie.db._
import razie.diesel.dom.WikiDomain
import razie.diesel.utils.DieselData
import razie.hosting.{Website, WikiReactors}
import razie.tconf.Visibility._
import razie.wiki.Sec.EncryptedS
import razie.wiki.admin._
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.wiki.parser.WAST
import razie.wiki.util.{PlayTools, Stage}
import razie.wiki.{Enc, Services}


/** a simple edit lock
  *
  * // todo candidate for a different temporary store, other than Mongodb
  *
  * @param uwid  for existing pages, quick lookup
  * @param wpath for new pages
  */
@RTable
case class EditLock(uwid: UWID, wpath: String, ver: Int, uid: ObjectId, uname: String, dtm: DateTime = DateTime.now()
                    , _id: ObjectId = new ObjectId()) extends REntity[EditLock] {

  def isLockedFor(userId: ObjectId) = dtm.plusSeconds(300).isAfterNow && uid != userId

  /** extend the lock for another period, on autosave etc */
  def extend = {
    this.copy(dtm = DateTime.now()).updateNoAudit(tx.auto)
  }
}

object EditLock {
  implicit def txn = tx.auto

  /** lock a page by a user */
  def lock (uwid:UWID, wpath:String, ver:Int, editor:User) : Boolean = {
    if(isLocked(uwid, wpath, editor)) false
    else {
      unlock(uwid, wpath, editor)
      EditLock(uwid, wpath, ver, editor._id, editor.userName).createNoAudit
      true
    }
  }

  /** unlock a page when saving etc */
  def unlock (uwid:UWID, wpath:String, u:User) = {
    if(isLocked(uwid, wpath, u)) throw new IllegalStateException("page is locked: "+wpath)

    find(uwid, wpath).map(_.deleteNoAudit)
    keepClean()
  }

  def canSave (uwid:UWID, wpath:String, u:User) = {
    // locked by someone else
    if(isLocked(uwid, wpath, u)) false
    else {
      true
    }
  }

  def find (uwid:UWID, wpath:String) =
    if(uwid == UWID.empty)
      ROne[EditLock] ("wpath" -> wpath)
    else
      ROne[EditLock] ("uwid.id" -> uwid.id)

  def isLocked (uwid:UWID, wpath:String, u:User) =
    find(uwid, wpath)
      .exists(_.isLockedFor(u._id))

  def who (uwid:UWID, wpath:String) =
    find(uwid, wpath)
        .map(_.uname).mkString

  /** sometimes they stay behind - we'll keep this clean and fast */
  def keepClean() = {
    // todo spawn async task
    val threshold = DateTime.now().minusHours(2)
    val x = RMany[EditLock]().filter(_.dtm.isBefore(threshold)).toList
    x.map(_.deleteNoAudit)
  }
}
