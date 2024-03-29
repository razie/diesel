/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.Logging
import razie.diesel.dom.RDOM._
import razie.diesel.dom.{RDomain, _}
import razie.diesel.engine.exec.Executors
import razie.diesel.engine.nodes._
import razie.diesel.expr.{CExpr, ECtx}
import razie.diesel.model.DieselMsg
import razie.tconf.{DSpec, EPos}
import razie.wiki.Enc
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** accumulate results and infos and errors */
class InfoAccumulator (var eres : List[Any] = Nil) {

  def += (x:Any) = append(x)

  // todo - weird match/case instead of explicit Seq
  def append (x:Any) = {
    x match {
      case l : List[_] => eres = eres ::: l
      case _ => eres = eres ::: x :: Nil
    }
    eres
  }
}

/** RDOM extensions */
object RDExt extends Logging {


  /** parse from/to json utils */
  object DieselJsonFactory {

    final val dieselTrace = "dieselTrace"
    final val TREE = "tree"

    private def sm(x:String, m:collection.Map[String, Any]) : String =
      if(m.contains(x)) m(x).asInstanceOf[String] else ""

    def fromj (o:scala.collection.Map[String, Any]) : Any = {
      def s(x:String)  : String = sm(x, o)
      def l(x:String) = if(o.contains(x)) o(x).asInstanceOf[collection.Seq[_]] else Nil

      def parms (name:String) = l(name).collect {
        case m:collection.Map[_, _] => new P(
          sm("name",  m.asInstanceOf[collection.Map[String, Any]]),
          sm("value", m.asInstanceOf[collection.Map[String, Any]])
        )
      }.toList

      if(o.contains("class")) s("class") match {
        case "EMsg" => EMsg(
          s("entity"),
          s("met"),
          parms("attrs"),
          s("arch"),
          parms("ret"),
          s("stype")
        ).withPos(if(o.contains("pos")) Some(new EPos(o("pos").asInstanceOf[collection.Map[String, Any]])) else None)

        case "EVal" => EVal (new P(
          s("name"), s("value")
        )).withPos(if(o.contains("pos")) Some(new EPos(o("pos").asInstanceOf[collection.Map[String, Any]])) else None)

        case "DomAst" => {
          val c = l("children").collect {
            case m:collection.Map[_, _] => fromj(m.asInstanceOf[collection.Map[String, Any]]).asInstanceOf[DomAst]
          }

          val v = o("value") match {
            case s:String => s
            case m : collection.Map[_,_] => DieselJsonFactory.fromj(m.asInstanceOf[collection.Map[String, Any]])
            case x@_ => x.toString
          }

          val d = DomAst(
            v,
            s("kind"),
            (new ListBuffer[DomAst]()) ++ c,
            s("id"))
          d.moreDetails = s("details")
          d
        }
        case _ => o.toString
      } else o.toString
    }

    // parse a DieselTrace
    def trace (o:collection.Map[String, Any]) : DieselTrace = {
      DieselTrace (
        fromj (o("root").asInstanceOf[collection.Map[String,Any]]).asInstanceOf[DomAst],
        o.getOrElse("node", "").toString,
        o.getOrElse("engineId", "").toString,
        o.getOrElse("app", "").toString,
        o.getOrElse("details", "").toString,
        o.get("parentNodeId").map(_.toString)
      )
    }
  }

  // an instance at runtime
  case class EInstance(cls: String, attrs: List[RDOM.P]) {
  }

  // an instance at runtime
  case class EEvent(e: EInstance, met: String, attrs: List[RDOM.P]) {
  }

  // a context - MAP, use this to test the speed of MAP
  class ECtxM() {
    val attrs = new mutable.HashMap[String, P]()

    def apply(name: String): String = get(name).getOrElse("")

    def get(name: String): Option[String] = attrs.get(name).map(_.currentStringValue)

    def put(p: P) = attrs.put(p.name, p)

    def putAll(p: List[P]) = p.foreach(x => attrs.put(x.name, x))
  }


  // find the spec of the generated message, to ref
  def spec(m:EMsg)(implicit ctx: ECtx) : Option[EMsg] = spec(m.entity, m.met)

  // find the spec of the generated message, to ref
  def spec(entity: String, met: String)(implicit ctx: ECtx): Option[EMsg] =
    ctx.domain.flatMap(_.moreElements.collect {
      case x: EMsg
        if
        ("*" == x.entity || x.entity == entity || regexm(x.entity, entity)) &&
            ("*" == x.met || x.met == met || regexm(x.met, met))
      => x
    }.headOption)

  /** find the usages in stories
    *
    * @return (what, ea, pos, line, parent)
    */
  def usagesStories(entity: String, met: String, stories: List[DSpec])
  : List[(String, String, Option[EPos], String, String)] = {
    stories
        .flatMap(
          RDomain.domFilter(_) {
            case x: EMsg if x.entity == entity && x.met == met => ("$send", x.ea, x.pos, x.toString, "")
            case x: ExpectM if x.m.cls == entity && x.m.met == met => ("$expect", x.m.ea, x.pos, x.m.toString, "")
          })
  }

  /** find where it is decomposed (applicable rules)
    *
    * @return (what, ea, pos, line, parent)
    */
  def usagesSpecs(entity: String, met: String)(implicit ctx: ECtx)
  : List[(String, String, Option[EPos], String, String)] =
    ctx.domain.toList.flatMap(_.moreElements.collect {
      case u: EMsg if u.entity == entity && u.met == met => List(
        ("$msg", u.ea, u.pos.orElse(u.rulePos), u.toCAString, ""))
      case u: ERule => usagesInRule(u, entity, met)
      case u: EMock => usagesInRule(u.rule, entity, met, true)
    }).flatten

  /** unpack usage in a rule
    *
    * @return (what, ea, pos, line, parent)
    */
  def usagesInRule(u: ERule, entity: String, met: String, ismock: Boolean = false)(implicit ctx: ECtx)
  : List[(String, String, Option[EPos], String, String)] =
    (
        if (u.e.cls == entity && u.e.met == met)
          List(((if (ismock) "$mock" else "$when"), u.e.cls + "." + u.e.met, u.pos, u.e.toString, ""))
        else Nil
        ) :::
        u.i.collect {
          case x: EMap if x.cls == entity && x.met == met => ("=>", x.cls + "." + x.met, x.pos, x.toCAString, u.e.ea)
        }

  /** simple json for content assist
    *
    * @param d
    * @return Map (msg, list of messages) + (attr, list of attributes)
    */
  def toCAjmap(d: RDomain) = {

    /** (kind, entity, method, CA-String */
    val visited = new ListBuffer[(String, String, String, String)]()

    def wasVisited(kind:String, e:String, m:String) = visited.exists(x=> x._1 == kind && x._2 == e && x._3 == m)

      // todo collapse defs rather than select
    def collect(e:String, m:String, kind:String="msg") = {
      // don't check m length - see collectP below
      if (e.length > 0 && !wasVisited(kind, e, m)) {
        visited.append((kind, e, m, ""))
      }
    }

    // todo collapse defs rather than select
    def collectMsg(m:EMsg, kind:String="msg") = {
      if (m != null && m.entity != null && m.met != null && m.entity.length > 0 && !wasVisited(kind, m.entity, m.met)) {
        visited.append((kind, m.entity, m.met, m.toCAString))
      }
    }

    // todo collapse defs rather than select
    def collectRule(m:ERule, kind:String="msg") = {
      if (m.e.cls.length > 0 && !wasVisited(kind, m.e.cls, m.e.met)) {
        visited.append((kind, m.e.cls, m.e.met, m.e.toCAString))
      }
    }

    def collectP(l:List[P]) = {
      l.map(p=>collect(p.name, "", "attr"))
    }
    def collectPM(l:List[PM]) = {
      l.map(p=>collect(p.name, "", "attr"))
    }

    // add known executors
    Executors.withAll(_.values.toList.flatMap(_.messages).map(m=> collectMsg(m)))

    Map(

      "msg" -> {
        d.moreElements.collect({
          case n: EMsg => collectMsg(n)
          case n: ERule => {
            collectRule(n)
            n.i.map {x=>
              collect(x.cls, x.met)
            }
          }
          case n: EMock => collectRule(n.rule)
          case n: ExpectM => collect(n.m.cls, n.m.met)
        })
        visited.filter(_._1 == "msg").toList.map(t => t._2 + "." + t._3)
      },

      "attr" -> {
        d.moreElements.collect({
          case n: EMsg => collectP(n.attrs)
          case n: ERule => {
            collectPM(n.e.attrs)
            n.i.collect{
              case e:EMapCls => e
            }.map { x =>
              collectP(x.attrs)
            }
          }
          case n: EMock => collectPM(n.rule.e.attrs)
          case n: ExpectM => collectPM(n.m.attrs)
        })

        // return collected
        visited.filter(_._1 == "attr").toList.map(t => t._2)
      }
    )
  }

  /** record a test result
    *
    * domAST kind should be TRACE if target exists or TEST otherwise
    *
    * @param value    "ok" for good, otherwise is failed
    * @param more     - an escaped value
    * @param moreHtml - unescaped html
    */
  case class TestResult(value: String, more: String = "", moreHtml:String="") extends CanHtml with HasPosition {
    private var ipos: Option[EPos] = None

    def pos: Option[EPos] = ipos.orElse(target.flatMap(_.pos))

    def withPos(p: Option[EPos]) = {
      this.ipos = p;
      this
    }

    var target: Option[HasTestResult with HasPosition] = None

    def withTarget(p: HasTestResult with HasPosition) = {
      this.target = Some(p);
      p.testResult = Some(value)
      this
    }

    override def toHtml = {
      def htrimmedDflt =
        if (more.size > 80) more.take(60)
        else more

      val hmore = Enc.escapeHtml(htrimmedDflt) + " " + moreHtml

      if (value == "ok")
        kspan(value, "success") + s" $hmore"
      else if (value startsWith "fail")
        kspan(value, "danger", Some(EPos.EMPTY), None, Some("error")) + s" $hmore"
      else
        kspan(value, "warning") + s" $hmore"
    }

    override def toString =
      value + s" $more"
  }

  def label(value:String, color:String="default") =
    s"""<span class="label label-$color">$value</span>"""

}

