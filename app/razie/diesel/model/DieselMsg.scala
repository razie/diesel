/**
  *    ____    __    ____  ____  ____,,___     ____  __  __  ____
  *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.model

import razie.audit.Audit
import razie.diesel.engine.{CachedEngingPrep, DieselAppContext, DomEngineSettings}
import razie.diesel.engine.nodes.EMsg
import razie.diesel.samples.DomEngineUtils
import razie.diesel.samples.DomEngineUtils.extractResult
import razie.tconf.TagQuery
import razie.wiki.admin.GlobalData
import razie.wiki.admin.GlobalData.{dieselEnginesActive, dieselStreamsActive, serving, servingApiRequests}
import scala.util.Try
import razie.{cdebug, clog, cout}
import scala.::

/** a message string - send these to Services to have them executed
  *
  * these will be executed as a new process/engine instance
  *
  * @param msg properly formatted message string, i.e. $msg ent.ac (p1="value")
  */
case class DieselMsgString(msg: String,
                           target: DieselTarget = DieselTarget.RK,
                           ctxParms: Map[String, Any] = Map.empty,
                           osettings: Option[DomEngineSettings] = None,
                           omsg: Option[DieselMsg] = None,
                           ocache: Option[CachedEngingPrep] = None
                          ) {

  /** convert to msg string */
  def mkMsgString: String = {
    if (ctxParms.nonEmpty) {
      // add the params to the context with an artificial ctx.set message so we don't parse the msg which could be a story
//      val extra = ctxParms.map(t => s"""${t._1} = "${t._2}"""").mkString(", ")
      val extra = DieselMsg.argsToMsgString(ctxParms)
      s"""$$msg ctx.set($extra)\n\n$msg"""
    } else
      msg
  }

  /** clone with new context */
  def withContext(p: Map[String, Any]) = this.copy(ctxParms = ctxParms ++ p)
  def withSettings(p: Option[DomEngineSettings]) = this.copy(osettings = osettings)
  def withCachedPrep(p: Option[CachedEngingPrep]) = this.copy(ocache = p)

 /** try to parse and get the message invoked */
  def getEMsg : Option[EMsg] = {
    Try {
      val pat = s"""[$$.][^ ]* *${EMsg.REGEX}.*""".r
      val pat(e, a) = msg
      Some(EMsg(e,a))
    }.getOrElse(
      None
    )
  }

  /** make the engine to run this, using targets etc */
  def mkEngine = {
    val m = this
    // todo auth/auth
    cout << "======== DIESEL MSG: " + m
    val settings = osettings.getOrElse(new DomEngineSettings())
    settings.realm = Some(target.realm)

    // important to use None here, to let the engines use the envList setting otherwise
    settings.env = if (target.env == DieselTarget.DEFAULT) None else Some(target.env)
    settings.tagQuery = if (target.tagQuery.tags.isEmpty) None else Some(target.tagQuery.tags)

    val ms = m.mkMsgString
    DomEngineUtils
        .createEngine(ms, target.specs, target.stories, settings, Some(this), ocache)
  }

  /** audit that this engine has run and details */
  def auditEngine(res: Map[String, Any]) = {
    // don't audit these frequent ones
    if (
      this.msg.startsWith(DieselMsg.WIKI_UPDATED) ||
          this.msg.startsWith(DieselMsg.CRON_TICK) ||
          this.msg.startsWith(DieselMsg.GUARDIAN_POLL) ||
          false//        m.msg.startsWith(DieselMsg.REALM_LOADED)
    ) {
      clog << "DIESEL_MSG: " + this + " : RESULT: " + res.get("value").mkString.take(500)
    } else {
      val id = res.get("engineId").mkString
      // this will also clog it - no need to clog it
      Audit.logdb(
        "DIESEL_MSG.done:",
        s"[[DieselEngine:$id]]",
        this.toString,
        "result-length: " + res.get("value").mkString.length,
        res.get("value").mkString.take(500)
      )
    }
    res
  }

  def startMsg = {
//    import scala.concurrent.ExecutionContext.Implicits.global
    import DieselAppContext.executionContext
    mkEngine.process.map { engine =>
        clog << ("engine completed...")
      val res = extractResult(msg, None, engine)
      auditEngine(res)
      (engine, res)
    }
  }
}

/** schedule a message for later - send this to Services */
case class ScheduledDieselMsg(schedule: String, msg: DieselMsg) {}

case class ScheduledDieselMsgString(schedule: String, msg: DieselMsgString) {}

/** a message intended for a target. Send to CQRS for execution, via Services */
case class DieselMsg(
  e: String,
  a: String,
  args: Map[String, Any],
  target: DieselTarget = DieselTarget.RK,
  osettings:Option[DomEngineSettings] = None
) {

  def withSettings (set:DomEngineSettings) = this.copy(osettings = Some(set))

  def this(m: EMsg, target: DieselTarget) = this(
    m.entity,
    m.met,
    m.attrs.map(t => (t.name, t.currentStringValue)).toMap,
    target
  )

  def ea = e + "." + a

  def toMsgString = DieselMsgString(
    s"$$msg $e.$a (" + DieselMsg.argsToMsgString(args) + ")",
    target,
    Map.empty,
    osettings,
    Some(this)
  )

  def withArgs(more: Map[String, Any]) = this.copy(e, a, args ++ more, target, osettings)

  def toJson: Map[String, Any] = {
    Map(
      "e" -> e,
      "a" -> a,
      "ea" -> (e + "." + a),
      "args" -> args,
      "target" -> target.toString
    ) ++ osettings.map(x => Map("osettings" -> x.toJson)
    ).getOrElse(Map.empty)
  }

  override def toString = razie.js.tojsons(toJson)
}

/** constants */
object DieselMsg {

  /** somewhat type aware nvp args to string */
  def argsToMsgString (margs:Map[String, Any]) =
    (margs
        .map(
          t =>
            t._1 + "=" + (t._2 match {
              case s: String if s.startsWith("{") || s.startsWith("[") => {
//                    val ss = s.replaceAll("\\\"", "\\\"")
                s""" $s """
              }
              case s: String => {
                // todo should escape them...
//                    val ss = s.replaceAll("\\\"", "\\\"")
                s""" "$s" """
              }
              case s: Int => s"$s"
              case s@_ => s"${s.toString}"
            })
        )
        .mkString(", ")
        )

  def logdb(what: String, details: Any*) = {
    import GlobalData._
    val stats = s"EnginesActive=$dieselEnginesActive, StreamsActive=$dieselStreamsActive, servingApi=$servingApiRequests, servingThreads=$serving"
    val newdetails = stats :: details.toList
    Audit.logdb(what, newdetails: _*)
  }


  final val CRON_TICK = "$msg diesel.cron.tick"
  final val GUARDIAN_POLL = "$msg diesel.guardian.poll"
  final val GUARDIAN_RUN = "$msg diesel.guardian.run"
  final val GUARDIAN_ENDS = "$msg diesel.guardian.ends"
  final val WIKI_UPDATED = "$msg diesel.wiki.updated"
  final val USER_JOINED = "$msg diesel.user.joined"

  final val DIESEL_REQUIRE = "diesel.require"

  final val GPOLL = "diesel.guardian.poll"

  final val irunDom = "irunDom:"
  final val runDom = "runDom:"

  object REALM {
    final val REALM_LOADED_MSG = "$msg diesel.realm.loaded"
    final val REALM_LOADED = "diesel.realm.loaded"
    final val REALM_CONFIGURE = "diesel.realm.configure"
    final val REALM_SET = "diesel.realm.set"
    final val EVENTS = "diesel.realm.events"
    final val EVENTS_SET = "diesel.realm.events.add"
    final val EVENTS_DEL = "diesel.realm.events.remove"
    final val ENTITY = "diesel.realm"
    final val LOADED = "loaded"
    final val CONFIGURE = "configure"
  }

  object SCOPE {
    final val ENTITY = "diesel.scope"
    final val DIESEL_PUSH = "diesel.scope.push"
    final val DIESEL_POP = "diesel.scope.pop"
    final val RULE_PUSH = "diesel.scope.rule.push"
    final val RULE_POP = "diesel.scope.rule.pop"
  }

  object STREAMS {
    final val PREFIX = "diesel.stream"
    final val CREATE = "new"
    final val STREAM_PUT = "diesel.stream.put"
    final val STREAM_ONDATA = "diesel.stream.onData"
    final val STREAM_ONDATASLICE = "diesel.stream.onDataSlice"
    final val STREAM_ONDONE = "diesel.stream.onDone"
    final val STREAM_ONERROR = "diesel.stream.onError"
  }

  object ENGINE {
    final val ENTITY = "diesel"
    final val VALS = "vals"
    final val DIESEL_CLEANSTORY = "diesel.story.clean"
    final val DIESEL_EXIT = "diesel.pleaseexit"
    final val DIESEL_CRASHAKKA = "diesel.crashakka"
    final val DIESEL_VALS = "diesel.vals"
    final val DIESEL_WARNINGS = "diesel.warnings"
    final val DIESEL_BEFORE = "diesel.before"
    final val DIESEL_AFTER = "diesel.after"
    final val DIESEL_LEVELS = "diesel.levels"

    final val DIESEL_STORY_RUN = "diesel.story.run"
    final val DIESEL_STORY_EXPECT = "diesel.story.expect"

    final val DIESEL_RETURN = "diesel.return" // todo obsolete
    final val DIESEL_FLOW_RETURN = "diesel.flow.return"
    final val DIESEL_SCOPE_RETURN = "diesel.scope.return"
    final val DIESEL_RULE_RETURN = "diesel.rule.return"
    final val DIESEL_STORY_RETURN = "diesel.story.return"

    final val DIESEL_TRY = "diesel.try"
    final val DIESEL_THROW = "diesel.throw"
    final val DIESEL_CATCH = "diesel.catch"

    final val DIESEL_CALL = "diesel.call"
    final val DIESEL_MSG = "diesel.msg"

    final val DIESEL_REALM_READY = "diesel.realm.ready"

    final val DIESEL_PING = "diesel.ping"
    final val DIESEL_ERROR = "diesel.error"
    final val DIESEL_LOG = "diesel.log"
    final val DIESEL_AUDIT = "diesel.audit"
    final val DIESEL_SUMMARY = "diesel.summary"
    final val DIESEL_DEBUG = "diesel.debug"
    final val DIESEL_LATER = "diesel.later"
    final val DIESEL_WHILE = "diesel.while"
    final val DIESEL_ASSERT = "diesel.assert"
    final val DIESEL_MAP = "diesel.map"
    final val DIESEL_REST = "diesel.rest"
    final val STRICT = "diesel.engine.strict"
    final val NON_STRICT = "diesel.engine.nonstrict" // deprecated - just pass a value to strict
    final val DIESEL_SYNC = "diesel.engine.sync"
    final val DIESEL_ASYNC = "diesel.engine.async"
    final val DIESEL_PONG = "diesel.engine.pong"
    final val DIESEL_HEADING = "diesel.heading"
    final val DIESEL_STEP = "diesel.step"
    final val DIESEL_TODO = "diesel.todo"
    final val DIESEL_NOP = "diesel.nop"
    final val DIESEL_ENG_DEBUG = "diesel.engine.debug"
    final val DO_THIS = "do.this"
    final val DO_THAT = "do.that"

    final val DIESEL_ENG_PAUSE = "diesel.engine.pause"
    final val DIESEL_ENG_CONTINUE = "diesel.engine.continue"
    final val DIESEL_ENG_PLAY = "diesel.engine.play"
    final val DIESEL_ENG_CANCEL = "diesel.engine.cancel"

    final val DIESEL_PROGRESS = "diesel.engine.progress"

    final val STEP = "step"
    final val TODO = "todo"
    final val BEFORE = "before"
    final val AFTER = "after"
    final val DEBUG = "debug"

    final val DIESEL_MSG_ENTITY = "diesel.msg.entity"
    final val DIESEL_MSG_ACTION = "diesel.msg.action"
    final val DIESEL_MSG_EA = "diesel.msg.ea"
    final val DIESEL_MSG_ATTRS = "diesel.msg.attrs"

    final val DIESEL_ENG_SET = "diesel.engine.set"

    final val DIESEL_ENG_SETTINGS = "diesel.engine.settings"
    final val DIESEL_ENG = "diesel.engine"
    final val DIESEL_ENG_DESC = "diesel.engine.description"

    final val ERR_NORULESMATCH = "ERR_DIESEL_NORULESMATCH"
  }

  object HTTP {
    final val PREFIX        = "diesel.http."
    final val RESPONSE      = "diesel.http.response"
    final val STATUS        = "diesel.http.response.status"
    final val CTYPE         = "diesel.http.response.contentType"
    final val HEADER_PREFIX = "diesel.http.response.header."
  }

  object GUARDIAN {
    final val ENTITY = "diesel.guardian"

    final val STARTS = "starts"
    final val NOTIFY = "notify"
    final val POLL = "poll"
    final val RUN = "run"

    final val STARTS_STORY = "guardian-starts-story"
    final val ENDS_STORY = "guardian-ends-story"
  }

  object CRON {
    final val ENTITY = "diesel.cron"

    final val SET = "set"
    final val LIST = "list"
    final val TICK = "tick"
    final val STOP = "stop"
  }

  object APIGW {
    final val LIMIT = "diesel.apigw.limit"
    final val ENTITY = "diesel.apigw"
  }

  object PROPS {
    final val ENTITY = "diesel.props"

    final val SET = "system"
    final val LIST = "file"
  }

  object IO {
    final val ENTITY = "diesel.io"

    final val TEXT_FILE = "diesel.io.textFile"
    final val LIST_FILES = "diesel.io.listFiles"
    final val LIST_DIRS = "diesel.io.listDirectories"
    final val CAN_READ = "diesel.io.canRead"
  }

  object DOM {
    final val ENTITY = "diesel.dom"

    final val META = "diesel.dom.meta"
  }


  final val fiddleStoryUpdated = "fiddleStoryUpdated"
  final val fiddleSpecUpdated = "fiddleSpecUpdated"
}
