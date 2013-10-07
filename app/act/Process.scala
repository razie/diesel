package act

import com.novus.salat._
import com.novus.salat.annotations._
import db.RazSalatContext._
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import scala.util.parsing.combinator.RegexParsers
import admin.Audit
import scala.util.matching.Regex.Match
import scala.util.matching.Regex
import razie.Log
import razie.base.data.TripleIdx
import admin.Notif
import admin.Config
import play.mvc.Http.Request
import razie.base.ActionContext
import db.{ RTable, RCreate, RDelete }
import model.WikiEntry
import model.WID
import razie.Logging

/** specification of a process - all data in topic */
case class WfProcessSpec(we: WikiEntry) {
  def name = we.name
}

/** specification of a process activity - all data in topic */
case class WfActivitySpec(we: WikiEntry) {
  def name = we.name
}

/** the states for a process or an activity */
object WfState {
  final val CREATED = "created" // just created, waiting to start
  final val INPROGRESS = "inprogress" // actively doing something
  final val WAITING = "waiting" // waiting for something
  final val DONE = "done"
  final val FAILED = "done.failed"
  final val SUCC = "done.succ"
}

/** wf instance - has a spec and state, parms, activities */
@RTable
case class WfProcess(
  specName: String,
  activities: List[WfActivity],
  state: String, // created,inprogress,waiting,done
  props: Map[String, String] = Map(),
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {
}

/** wf activity - has a spec and state, parms, activities */
@RTable
case class WfActivity(
  specName: String,
  parent: ObjectId,
  state: String, // created,inprogress,waiting,done
  props: Map[String, String] = Map(),
  crDtm: DateTime = DateTime.now,
  updDtm: DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) {

}

case class WFID(id: ObjectId)

/** most relevant and common functionality required from a wf subsystem */
trait WfApi {
  /** start a process given a spec */
  def start(specName: String, parms: Map[String, String])
  /** try to stop a process by some id */
  def stop(wf: WFID)
  /** get an event from the outside world that a process may wait for. Someone will match to waiting activities */
  def event(name: String, parms: Map[String, String])
  /** same as event, but there is some next step info, like the next web page etc. You must produce some object understood by the other system */
  def step(name: String, parms: Map[String, String]) : Option[AnyRef]
}

/** most relevant and common functionality required from a wf subsystem */
object WikiWf extends WfApi with Logging {
  /** start a process given a spec */
  def start(specName: String, parms: Map[String, String]) {
    log(s"WF_START: $specName with $parms.mkString")
  }
  /** try to stop a process by some id */
  def stop(wf: WFID) {
    log(s"WF_STOP: $wf")
  }
  /** get an event from the outside world that a process may wait for. Someone will match to waiting activities */
  def event(name: String, parms: Map[String, String]) {
    log(s"WF_EVENT: $name with $parms.mkString")
  }
  /** same as event, but there is some next step info, like the next web page etc. You must produce some object understood by the other system */
  def step(name: String, parms: Map[String, String]) : Option[AnyRef] = {
    log(s"WF_EVENT_STEP: $name with $parms.mkString")
    None
  }
}

/** represents a wiki activity */
class WikiAct (val name:String, val parms:Map[String,String])

case class wait(event: String, attr:Map[String,String]) extends WikiAct ("wait", attr)
case class waitStep(event: String, attr:Map[String,String], nextWpath:String) extends WikiAct ("waitStep", attr)
case class either(or:WikiAct*) extends WikiAct ("either", Map())

/** wiki activities */
class WikiFlows {

  def form(user:String, reviewer:String, formWpath: String, content: String) = {
//    import razie.wfs._
    
    val i1 = System.currentTimeMillis().toString
    
    val wf =
"""
if (1==1) 
then act:simple:pipe(cmd="pwd") 
else act:simple:telnet(host="pwd",port="",cmd="") 
"""

//      val w1 = seq {
      
//    }
      
//    email (user, "please click /wikie/wf/step/" + i1) ::
//    wait ("nextStep", Map("id"->i1)) ::
//    crWiki (formWpath, content) ::
//    wait ("submit", Map("id"->formWpath)) ::
//    email (reviewer, "please review " + i1) ::
//    wait ("submit", Map("id"->formWpath)) :: Nil
  }
}

