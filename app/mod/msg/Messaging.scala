package mod.msg

import org.bson.types
import razie.db.Txn
import razie.{Logging, Snakk, cout}
import razie.db.RMongo._
import razie.wiki.Sec._
import model._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, tuple, _}
import play.api.mvc.{Action, Request}
import razie.OR._
import razie.wiki.util.PlayTools
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.Enc
import controllers.{Corr, RazController}
import model._
import org.joda.time.DateTime
import play.api.data.Form
import razie.Logging
import razie.db._
import play.api.data.Forms._
import org.bson.types.ObjectId
import razie.{cdebug, clog}
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.annotations._
import razie.audit.{Audit, Auditor}
import razie.db.RazSalatContext._
import razie.wiki.admin._


/**
 * a rule
 */
@RTable
case class JSRule (
  rule: String,
  hash: Int,
  _id: ObjectId = new ObjectId()) extends REntity[JSRule] {

  def execute(ctx:Any) : Boolean = {
    false
  }
}

object JSRules {
  def find (id:ObjectId) = ROne[JSRule](id)
  def findOrCreate (rule:String) = {
    ROne[JSRule]("hash"->rule.hashCode).map(_._id) getOrElse {
      val x = new JSRule(rule, rule.hashCode)
      x.create(tx.auto)
      x._id
    }
  }

  def group(name:String) = findOrCreate(s"wix.user.groups.contains('$name')")
  def perm(name:String) = findOrCreate(s"wix.user.perms.contains('$name')")
}

case class EntityAssoc (
  eType:String,
  eId:ObjectId,
  role:String // from,to,about,owner,group
  )

case class MsgUser (
  userId:ObjectId,
  role:String, // from,to,about,owner,group
  read:Boolean = false
  )

/**
 * a message thread: PM, MA, question, discussion
 */
@RTable
case class MsgThread (
  streamId: ObjectId, // the comment stream associated to this header
  realm:  String,
  role:   String, //PM,Thread,MA...
  title:  String,
  users: Seq[MsgUser],
  associations: Seq[EntityAssoc],
  accessRules: Seq[ObjectId],
  crDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) extends REntity[MsgThread] {

  def stream = ROne[CommentStream](streamId)

  override def delete(implicit txn: Txn = tx.auto) = {
    Audit.logdb(Messaging.THREAD_DELETED)
    stream.map(_.delete)
    super.delete
  }

  def from : Option[ObjectId] = users.find(_.role == "from").map(_.userId)
  def to   : Option[ObjectId] = users.find(_.role == "to").map(_.userId)

  def isRead (uid:ObjectId) = users.exists{x => x.userId == uid && x.read}
  def readNow(uid:ObjectId, state:Boolean=true) = this.copy(users=users.map{x=> if(x.userId == uid) x.copy(read=state) else x})
}

object Messaging extends RazController with Logging {
  final val THREAD_DELETED = "THREAD_DELETED"

  final val cNoConsent = new Corr("Need consent!", """You need to <a href="/doe/consent">give your consent</a>!""");

  final val UROLE_FROM = "from"
  final val UROLE_TO = "to"
  final val UROLE_ABOUT = "about"
  final val UROLE_MEMBER = "member"

  def startMsgForm = Form {
    tuple(
      "nothing" -> text,
      "title" -> nonEmptyText.verifying(vSpec, vBadWords)
    ) verifying
      ("", { t: (String, String) =>
        true
      })
  }

  def doePostToGroup (role:String, group:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      razie.db.tx("doePostToGroup", au.userName) { implicit txn =>
        ROK.s apply { implicit stok =>
          val mid = new ObjectId
          val cs = CommentStream(mid, "Msg")
          cs.create
          val thread = MsgThread(cs._id, stok.realm, role, s"$role ${au.userName}",
            Seq(MsgUser(au._id, UROLE_FROM)),
            Seq(), Seq(JSRules.group(group)), DateTime.now(), mid)
          thread.create
          views.html.modules.msg.thread(thread)
        }
      }
  }

  def doeStartThread (role:String, uid:String, title:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
      ROK.s apply { implicit stok =>
        val to = Users.findUserByUsername(uid).map(_._id.toString) orElse Users.findUserById(uid).map(_._id.toString) getOrElse (uid)
        views.html.modules.msg.startThread(startMsgForm.fill("title"->title), role, to)
    }
  }

  def doeStartThread2(role:String, uid:String) = FAU {
    implicit au => implicit errCollector => implicit request =>
    startMsgForm.bindFromRequest.fold(
    formWithErrors => BadRequest(
      ROK.s justLayout { implicit stok =>
        views.html.modules.msg.startThread(formWithErrors, role, uid)
      }
    ),
    {
      case (_, title) => razie.db.tx("doeStartThread2", au.userName) { implicit txn =>
        ROK.s apply { implicit stok =>
          val to = new ObjectId(uid)
          val mid = new ObjectId
          val cs = CommentStream(mid, "PM")
          cs.create
          val thread = MsgThread(cs._id, stok.realm, role, title,
            Seq(MsgUser(au._id, UROLE_FROM), MsgUser(to,UROLE_TO)),
            Seq(), Seq(), DateTime.now(), mid)
          thread.create
          views.html.modules.msg.thread(thread)
          }
        }
    })
  }

  def doePM() = FAU {
    implicit au => implicit errCollector => implicit request =>
      ROK.s apply  { implicit stok =>
        views.html.modules.msg.privateMessages(pmList(au))
      }
  }

  def doeThread(threadId:String) = FAU("msg.thread") {
    implicit au => implicit errCollector => implicit request =>
      ROne[MsgThread](new ObjectId(threadId)).map { thread =>
        razie.db.tx("doeModRkRemove", au.userName) { implicit txn =>
          ROK.s apply { implicit stok =>
            if (!thread.isRead(au._id)) thread.readNow(au._id).update
            views.html.modules.msg.thread(thread)
          }
        }
      }
  }



  private def pmList(au:User) = {
    RazMongo("MsgThread").m.find("users" $elemMatch MongoDBObject("userId" -> au._id)).map(grater[MsgThread].asObject(_)).toList
//    RazMongo("MsgThread").m.find("users" $elemMatch MongoDBObject("role" -> "to")).map(grater[MsgThread].asObject(_)).toList
//    RMany[MsgThread]("users" -> au._id).toList
  }

}

