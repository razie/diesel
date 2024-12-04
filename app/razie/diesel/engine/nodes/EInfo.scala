/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.nodes

import razie.diesel.engine._
import razie.diesel.expr.DieselExprException
import razie.tconf.EPos
import razie.wiki.Enc
import scala.collection.mutable.ListBuffer


object EErrorUtils {
  val MAX_STACKTRACE_LINES = 5
  val MAX_STACKTRACE_LINES_PER = 2 // per exception

  /** throwable to string */
  def ttos(t: Throwable) = {
    val sw = new java.io.StringWriter()
    val pw = new java.io.PrintWriter(sw)
    t.printStackTrace(pw)

    // why always big stack traces? they're kind'a pointless
    val f = new ListBuffer[ListBuffer[String]]()
    sw.toString.lines.toList.zipWithIndex.collect {
      case t@(l, i) if i == 0 => {
        f.append(new ListBuffer[String]())
        f.last.append(l)
      }
      case t@(l, i) if l.contains("Caused by:") => {
        f.last.append("...")
        f.append(new ListBuffer[String]())
        f.last.append(l)
      }
      case t@(l, i) => {
        if ((
            f.last.size < MAX_STACKTRACE_LINES_PER ||
                f.size == 1 && f.last.size < MAX_STACKTRACE_LINES
            )
        // this cannot be - we did toString above...
        // &&
//            !(
//                t.isInstanceOf[DieselExprException]
//                ) &&
//            !(
//                t.isInstanceOf[Exception] &&
//                    t.asInstanceOf[Exception].getCause != null &&
//                    t.asInstanceOf[Exception].getCause.isInstanceOf[DieselExprException]
//                )
        ) f.last.append(l)
      }
    }
    f.flatten.mkString("\n")
  }
}

/** some info node with pos */
abstract class EInfoPos () extends CanHtml with HasPosition with InfoNode {
  var pos: Option[EPos] = None

  def withPos(p: Option[EPos]) = {
    this.pos = p;
    this
  }
}

/** some error, with a message and details */
case class EError(msg: String, details: String = "", code: String = "ERROR", t: Option[Throwable] = None)
    extends EInfoPos {

  def this(msg: String, t: Throwable) =
    this(
      Enc.escapeHtml(msg + ": " + t.getClass.getSimpleName + ": " + t.getMessage),
      Enc.escapeHtml(EErrorUtils.ttos(t)),
      "ERROR",
      Option(t)
    ) // escape html - some exc contain html content

  razie.Log.error(Enc.unescapeHtml(msg))

  var handled: Boolean = false

  def withCode(code: String) = {
    this.copy(code = code)
  }

  override def toHtml = {
    val getErrClass = if (handled) "warn" else "err"
    val color = if (handled) CanHtml.COLOR_WARN else CanHtml.COLOR_DANGER
    val spos = if (pos.isDefined) kspan("pos") else ""

    kspan(getErrClass, "default") +
        (
            if (details.length > 0)
              spanClick("fail-error::", color, details) + spos + msg
            else
              span("fail-error::", color, details) + spos + " " + msg
            )
  }

  override def toString = "fail-error::" + msg + details
}

/** some error, with a message and details */
case class EWarning(msg: String, details: String = "", code:String = "WARNING") extends EInfoPos {
  def this(msg:String, t:Throwable) =
    this(
      Enc.escapeHtml(msg + t.toString),
      Enc.escapeHtml(EErrorUtils.ttos(t))
    ) // escape html - some exc contain html content

  razie.Log.warn(Enc.unescapeHtml(msg))

  def withCode(code: String) = {
    this.copy(code = code)
  }

  override def toHtml = {
    val spos = if (pos.isDefined) kspan("pos") else ""
    if (details.length > 0)
      spanClick("warn::", "warning", details) + spos + msg.replace("\n", "")
    else
      span("warn::", "warning", details) + spos + " " + msg.replace("\n", "")
  }

  override def toString = "fail-warn::" + msg
}

/** a simple info node with a message and details - link opens */
case class ELink(msg: String, url: String = "") extends EInfoPos {
  override def toHtml =
    s"""<span onclick="welink('$url')" style="cursor:pointer" class="label
       |label-info" title="click to open">link</span>&nbsp; $msg""".stripMargin

  override def toString = "info::" + msg
}

/** a simple info node with a message and details - details are displayed as a popup */
case class ETrace(override val msg: String, override val details: String = "") extends EInfoBase (msg, details, k = "info") {
//  razie.Log.trace(Enc.unescapeHtml(msg))
}

/** a simple info node with a message and details - details are displayed as a popup */
case class EInfo(override val msg: String, override val details: String = "") extends EInfoBase (msg, details, "default") {
//  razie.Log.log(Enc.unescapeHtml(msg).take(3000))
}

/** a simple info node with a message and details - details are displayed as a popup */
class EInfoBase(val msg: String, val details: String = "", val k:String = "default") extends EInfoPos {

  override def toHtml = {
    val spos = if (pos.isDefined) kspan("pos") else ""
    if (details.length > 0) {
      spanClick(
        "info::",
        k,
        details,
        spos + msg.replace("\n", ""),
        color = "darkgray"
      )
    } else {
      span("info::", "default", details) + spos + " " + s"""<span style="color:darkgray">$msg</span>"""
    }
  }

  override def toString = "info::" + shorten(msg, 200)
}


/** a simple wrapper */
case class EInfoWrapper(a:Any) extends CanHtml with HasPosition with InfoNode {
  var pos: Option[EPos] = if(a.isInstanceOf[HasPosition]) a.asInstanceOf[HasPosition].pos else None

  override def toHtml =
    if (a.isInstanceOf[CanHtml]) a.asInstanceOf[CanHtml].toHtml
    else span("info::", "info", "") + " " + a.toString.replace("\n", "")

  override def toString = "info:: " + shorten(a.toString, 200)
}

/** duration of the curent op */
case class EDuration(millis:Long, msg: String="") extends CanHtml with InfoNode {

  override def toHtml = span("info::", "info") + s" $millis ms - $msg"

  override def toString = "info::" + s" $millis ms - $msg"
}

