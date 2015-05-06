package model.book

import scala.annotation.StaticAnnotation
import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import admin.Config
import controllers.Club
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.model.{Wikis, UWID, WID}
import razie.|>._
import razie.wiki.Sec._

/** a list of topics, picked up from another topic/section */
case class TopicList (
  ownerTopic: WID, // like book/section/course
  topics: Seq[WID] // child progresses
  ) {
  def this (ownerTopic:WID) = this (ownerTopic, {
    val res = Wikis.preprocess(ownerTopic, "md", ownerTopic.content.get)
    res.ilinks.map(_.wid).toSeq
  })
}

/** progress record for a topic */
//@RTable
case class ProgressEntry (
  //  progressId: ObjectId, // container
  topic: UWID,
  status: String, // 's' skipped, 'r' read, 'p' passed quiz
  crDtm: DateTime = DateTime.now ) {
  //  _id: ObjectId = new ObjectId()) extends REntity[ProgressEntry] {
}

/** */
case class ProgressLevel (
                         // todo owner to be an RK
  ownerId: ObjectId, // user that owns this progress
  ownerTopic: WID, // like book/section/course - must be a WID since it includes section
  lastDtm: DateTime = DateTime.now,
  progresses: Seq[ProgressEntry] = Nil, // child progresses
  status: String = Progress.STATUS_NOT_STARTED, // 's' skipped, 'r' read, 'p' passed quiz, 'c' complete
  crDtm:  DateTime = DateTime.now) {

  def addOrUpdate(topic:UWID, status: String) = {
    var found = false
    val newP = progresses.map{x=>
        if(x.topic == topic) {
          found = true
          ProgressEntry(x.topic, status, DateTime.now)
        } else x
      } ++ {
        if(found) Nil else Seq (
          ProgressEntry(topic, status, DateTime.now)
        )
      }
    this.copy(ownerId, ownerTopic, lastDtm, newP,
    (
      if(status == Progress.STATUS_COMPLETE && newP.filter(_.status == Progress.STATUS_COMPLETE).size == newP.size)
        Progress.STATUS_COMPLETE
      else
        status
    ),
      crDtm)
  }

  lazy val topics : TopicList = new TopicList(ownerTopic)
}

/** a user that may or may not have an account - or user group */
@RTable
case class Progress (
  ownerId: ObjectId, // user that owns this progress
  ownerTopic: WID, // like book/section/course - must be a WID since it includes section
  lastDtm: DateTime = DateTime.now,
  levels: Seq[ProgressLevel] = Nil, // child progresses
  crDtm:  DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Progress] {
//  def parents: Set[ObjectId] = RMany[RacerKidAssoc]("to" -> _id, "what" -> RK.ASSOC_PARENT).map(_.from).toSet
}

/** racer kid info utilities */
object Progress {
  final val STATUS_SKIPPED = "s"
  final val STATUS_READ = "r"
  final val STATUS_PASSED = "p"
  final val STATUS_COMPLETE = "c"
  final val STATUS_NOT_STARTED = "n"
  final val STATUS_IN_PROGRESS = "i"

  def findById(id: ObjectId) = ROne[Progress]("_id" -> id)
  def findByUserTopic(userId:ObjectId, uwid:UWID) = ROne[Progress]("ownerId" -> userId, "ownerTopic" -> uwid.grated)

  def startProgress (ownerId:ObjectId, tl:TopicList): Progress = {
    new Progress (ownerId, tl.ownerTopic)
  }
}

