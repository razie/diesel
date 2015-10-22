package mod.flow

import model.{User, Website}
import play.api.mvc.{Result, Request, Action}
import razie.clog
import razie.wiki.admin.{WikiRefinery, WikiRefined}
import razie.wiki.mods.{WikiMods, WikiMod}
import razie.wiki.parser.WAST

import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import admin.{Config}
import controllers.{RazController, CodePills, Club}
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.model._
import razie.|>._

import scala.collection.mutable.ListBuffer

// for traversal
abstract class FNode
case class FFolder(x:FList) extends FNode
case class FTopic(x:UWID)  extends FNode
case class FFolderEnd(x:FList)  extends FNode

/** a flow item */
case class FItem (
  target:String,
  action:String,
  parms:Map[String,String] = Map.empty,
  _id : ObjectId = new ObjectId
) {
  def default = parms get "default"
}

/** a list of items */
case class FList (
  ownerTopic: WID, // like Flow definition book/section/course
  items: Seq[FItem] // child progresses
  ) {
  def this (ownerTopic:WID, defn:String) = this (ownerTopic, {
    defn.split(";").toSeq.map(FProgress.item)
  })
}

/** either Entry or Level */
case class FProgressRecord (
  itemId: ObjectId,
  status: String, // see Status below
  dtm: DateTime = DateTime.now()
  )

/** progress for a flow in progress
  *
  * records are being added to - newest first.
  */
@RTable
case class FProgress (
  // todo owner to be an RK
  ownerId: ObjectId, // user that owns this progress
  spec: FList, // like book/section/course - must be a WID since it includes section
  desc:String,
  status:String,
  records:Seq[FProgressRecord],
  lastDtm: DateTime = DateTime.now,
  crDtm:  DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[FProgress] {

  def rec   (id:ObjectId, status:String) = records.find(x=> x.itemId == id && x.status == status)
  def recs  (id:ObjectId) = records.filter(x=> x.itemId == id)

  def isInFProgress (id:ObjectId) =
    records.find(x=> x.itemId == id).exists(x=> x.status == FProgress.STATUS_IN_PROGRESS)// || x.status == FProgress.STATUS_READ)
  def isComplete (id:ObjectId) =
    records.find(x=> x.itemId == id).exists(x=>
      x.status == FProgress.STATUS_PASSED || x.status == FProgress.STATUS_COMPLETE || x.status == FProgress.STATUS_SKIPPED
    )
  def isNext (id:ObjectId) =
    ! records.find(x=> x.itemId == id).exists(x=>
      x.status == FProgress.STATUS_READ || x.status == FProgress.STATUS_PASSED || x.status == FProgress.STATUS_COMPLETE || x.status == FProgress.STATUS_SKIPPED
    )

  def addAndUpdate (id:ObjectId, status:String) = {
    if (rec(id, status).isDefined)
      this
    else {
      val t = copy(records = new FProgressRecord(id, status) +: records)
      t.update
      t
    }
  }

  def process (item:FItem, ctx:WFContext) : Option[Result] = {
    val temp = (item.target, item.action) match {
      case ("wiki", "redirect") =>
        Some(FProgress.Redirect(item.default.get))
      case ("wiki", "follow") =>
        for (
          w <- item.default flatMap WID.fromPath;
          uwid <- w.uwid;
          u <- ctx.user
        ) {
          model.UserWiki(u._id, uwid, "Fan").create
        }
        None
      case ("wiki", "log") => {
        clog << item.default
        None
      }
    }
    addAndUpdate(item._id, FProgress.STATUS_COMPLETE)
    temp
  }

  def advance (ctx:WFContext) : Option[Result] = {
    val next = spec.items.find(p=>isNext(p._id))

    if(next.isEmpty) {
      this.copy(status=FProgress.STATUS_COMPLETE).update
    }

    val res = next.flatMap(p=> process (p, ctx))
    res
  }
}

/** racer kid info utilities */
object FProgress extends RazController {
  final val STATUS_SKIPPED = "s"
  final val STATUS_READ = "r"
  final val STATUS_PASSED = "p"
  final val STATUS_COMPLETE = "c"
  final val STATUS_NOT_STARTED = "n"
  final val STATUS_IN_PROGRESS = "i"

  def item (defn:String) = {
    val PAT = """(wiki) (\s+) (.+)""".r
    val PAT(r,a,p) = defn
    FItem (r, a, Map("default" -> p))
  }

  def findById(id: ObjectId) = ROne[FProgress]("_id" -> id)
  def findForUser(id: ObjectId) = RMany[FProgress]("ownerId" -> id)

  def findByUserAndTopic(userId:ObjectId, uwid:UWID) = ROne[FProgress]("ownerId" -> userId, "ownerTopic" -> uwid.grated)

  def startFProgress (ownerId:ObjectId, tl:FList, desc:String): FProgress = {
    val p = new FProgress (ownerId, tl, desc, STATUS_NOT_STARTED, Seq(new FProgressRecord(tl.items.head._id, STATUS_IN_PROGRESS)))
    p.create
    p
  }

  def init = {}

  // ----------------- pills

  CodePills.addString ("mod.flow/sayhi") {implicit request=>
    "ok"
  }
}

class WFContext (val user:Option[User], request:Option[Request[_]], parms:Map[String,Any] = Map.empty) {
}

object WikiFlows {
}
