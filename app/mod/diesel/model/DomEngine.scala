/**
 *  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.model

import mod.diesel.model.RDExt._
import org.bson.types.ObjectId
import play.api.mvc.{AnyContent, Request}
import razie.clog
import razie.diesel.RDOM._
import razie.diesel.{StaticECtx, DomEngECtx, RDomain, ECtx}
import razie.wiki.model.WikiEntry

import scala.Option.option2Iterable
import scala.collection.mutable.ListBuffer

object AstKinds {
  final val ROOT = "root"
  final val RECEIVED = "received"
  final val SAMPLED = "sampled"
  final val GENERATED = "generated"
  final val SKETCHED = "sketched"
  final val MOCKED = "mocked"
  final val TEST = "test"

  def isGenerated (k:String) = GENERATED==k || SKETCHED==k || MOCKED==k
}

object DomAst {
  def fromj (s:String) = {

  }
}

object DomState {
  final val INIT="init"
  final val STARTED="started"
  final val FINISHED="finished"
}
/** a tree node
  *
  * kind is spec/sampled/generated/test etc
  *
  * todo optimize tree structure, tree binding
  *
  * todo need ID conventions to suit distributed services
  */
case class DomAst(
  value: Any,
  kind: String,
  children: ListBuffer[DomAst] = new ListBuffer[DomAst](),
//  prcsState:String = DomState.INIT,
  id : String = new ObjectId().toString
  ) extends CanHtml {

  var moreDetails = " "
  var specs: List[Any] = Nil

  /** this node has a spec */
  def withSpec (s:Any) = {
    specs = s :: specs
    this
  }

  def withDetails (s:String) = {
    moreDetails = moreDetails + s
    this
  }

  def tos(level: Int, html:Boolean): String = ("  " * level) + kind + "::" + {
    value match {
      case c:CanHtml if(html) => c.toHtml
      case x => x.toString
    }
  } + moreDetails + "\n" + children.map(_.tos(level + 1, html)).mkString

  override def toString = tos(0, false)
  override def toHtml = tos(0, true)

  def toj : Map[String,Any] =
    Map (
      "class" -> "DomAst",
      "kind" -> kind,
      "value" ->
        (value match {
          case m:EMsg => m.toj
          case v:EVal => v.toj
          case x => x.toString
        }),
      "details" -> moreDetails,
      "id" -> id,
      "children" -> children.map(_.toj).toList
    )

  def toJson = toj

  // visit/recurse with filter
  def collect[T](f: PartialFunction[DomAst, T]) : List[T] = {
    val res = new ListBuffer[T]()
    def inspect(d: DomAst, level: Int): Unit = {
      if (f.isDefinedAt(d)) res append f(d)
      d.children.map(inspect(_, level + 1))
    }
    inspect(this, 0)
    res.toList
  }

  /** GUI needs position info for surfing */
  def posInfo = collect{
    case d@DomAst(m:EMsg, _, _, _) if(m.pos.nonEmpty) =>
      Map(
        "kind" -> "msg",
        "id" -> (m.entity+"."+m.met),
        "pos" -> m.pos.get.toJmap
      )
  }
}

object DomEngineSettings {
  /** take the settings from either URL or body form or default */
  def fromRequest(request:Request[AnyContent]) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String)=
      request.body.asFormUrlEncoded.flatMap(_.getOrElse(name, Seq.empty).headOption)

    def fqParm(name:String, dflt:String) =
      q.get(name).orElse(fParm(name)).getOrElse(dflt)

    new DomEngineSettings(
      mockMode = fqParm("mockMode", "true").toBoolean,
      blenderMode = fqParm("blenderMode", "true").toBoolean,
      draftMode = fqParm("draftMode", "true").toBoolean,
      sketchMode = fqParm("sketchMode", "false").toBoolean,
      execMode = fqParm("execMode", "sync")
    )
  }
}

class DomEngineSettings(
  var mockMode    : Boolean = false,
  var blenderMode : Boolean = true,
  var draftMode   : Boolean = true,
  var sketchMode  : Boolean = true,
  var execMode    : String = "sync"
  ) {
}

/** an engine */
class DomEngine(
  val dom: RDomain,
  val root: DomAst,
  val settings: DomEngineSettings,
  val pages : List[WikiEntry]) {

  val maxLevels = 10

  // setup the context for this eval
  implicit val ctx = new DomEngECtx().withDomain(dom).withSpecs(pages)

  val rules = dom.moreElements.collect {
    case e:ERule => e
  }

  def collectValues[T] (f: PartialFunction[Any, T]) : List[T] =
    root.collect {
      case v if(f.isDefinedAt(v.value)) => f(v.value)
    }

  // transform one element / one step
  def expand(a: DomAst, recurse: Boolean = true, level: Int): Unit =
    if (level >= maxLevels) a.children append DomAst(TestResult("fail: Max-Level!", "You have a recursive rule generating this branch..."), "error")
    else a.value match {
      case n: EVal if ! AstKinds.isGenerated(a.kind) => {
        a.children append DomAst(EVal(n.p).withPos(n.pos), AstKinds.MOCKED)
        ctx.put(n.p)
      }
      case n: EMsg => {
        // look for mocks
        var mocked = false

        if (settings.mockMode) {
          (root.collect {
            // mocks from story AST
            case d@DomAst(m: EMock, _, _, _) if m.rule.e.test(n) && a.children.isEmpty => m
          } ::: dom.moreElements.toList).collect {
            // todo perf optimize moreelements.toList above
            // plus mocks from spec dom
            case m:EMock if m.rule.e.test(n) && a.children.isEmpty => {
              mocked = true
              // run the mock
              val values = m.rule.i.apply(n, None, m.pos).collect {
                // collect resulting values and dump message
                case x: EMsg => {
                  a.children appendAll x.attrs.map(x => DomAst(EVal(x).withPos(m.pos), AstKinds.MOCKED).withSpec(m))
                  ctx putAll x.attrs
                }
              }
            }
          }
        }

        var ruled = false
        // no mocks fit, so let's find rules
        // I run rules even if mocks fit - mocking mocks only going out, not decomposing
        if (true || !mocked) {
          rules.filter(_.e.test(n)).map { r =>
            ruled = true

            // find the spec of the generated message, to ref
            val spec = dom.moreElements.collect {
              case x: EMsg if x.entity == r.i.cls && x.met == r.i.met => x
            }.headOption

            val news = r.i.apply(n, spec, r.pos).collect {
              // if values then collect resulting values and dump message
              case x: EMsg if x.entity == "" && x.met == "" => {
                a.children appendAll x.attrs.map(x => DomAst(EVal(x), AstKinds.GENERATED).withSpec(r))
                ctx putAll x.attrs
              }
              // else collect message
              case x: EMsg => {
                val n = DomAst(x, AstKinds.GENERATED).withSpec(r)
                a.children append n
                if (recurse) {
                  expand(n, recurse, level + 1)
                }
              }
            }

            true
          }
        }

        // no mocks, let's try executing it
        // I run snaks even if rules fit - but not if mocked
        if (!mocked) {
          executors.filter(x=> (!settings.mockMode || x.isMock) && x.test(n)).map { r =>
            mocked = true

            val news = r.apply(n, None)(new StaticECtx(n.attrs, Some(ctx))).map(x => DomAst(x, AstKinds.GENERATED).withSpec(r))
            a.children appendAll news
            if (recurse) news.foreach { n =>
              expand(n, recurse, level + 1)
            }
            true
          }
        }

        //          /* NEED expects to have a match in and a match out...?

        // last ditch attempt, in sketch mode: if no mocks or rules, run the expects
        if (!mocked && settings.sketchMode) {
          // sketch messages
          (collectValues {
            case x:ExpectM if x.when.exists(_.test(n)) => x
          }).map{e=>
            mocked=true

            val spec = dom.moreElements.collect {
              case x:EMsg if x.entity == e.m.cls && x.met == e.m.met => x
            }.headOption

            // each rule may recurse and add stuff
            //              implicit val ctx = ECtx((root.collect {
            //                case d@DomAst(v:EVal, _, _) => v.p
            //              }).toList)

            val news = e.sketch(None).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
            if(recurse) news.foreach{n=>
              a.children append n
              expand(n, recurse, level+1)
            }
            true
          }

          // sketch values
          (collectValues {
            case x:ExpectV if x.when.exists(_.test(n)) => x
//            case x:ExpectV => x // expectV have no criteria
          }).map{e=>
            val news = e.sketch(None).map(x=>EVal(x)).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
            mocked=true

//            if(recurse)
              news.foreach{n=>
              a.children append n
//              expand(n, recurse, level+1)
            }
            true
          }
        }

        //          */
      }

      case e: ExpectM => {
        val cole = new MatchCollector()
        val targets = if(e.when.isDefined) {
          // find generated messages that should be tested
          root.collect {
            case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(n)) => d
          }
        } else List(root)

        if(targets.size > 0) {
        // todo look at all possible targets - will need to create a matchCollector per etc
        targets.head.collect {
          case d@DomAst(n: EMsg, k, _, _) if AstKinds.isGenerated(k) =>
            cole.newMatch(d)
            if (e.m.test(n, Some(cole)))
              a.children append DomAst(TestResult("ok"), "test").withSpec(e)
        }

        cole.done

        // if it matched some Msg then highlight it there
        if (cole.highestMatching.exists(c =>
          c.score >= 2 &&
            c.diffs.values.nonEmpty &&
            c.x.isInstanceOf[DomAst] &&
            c.x.asInstanceOf[DomAst].value.isInstanceOf[EMsg]
        )) {
          val c = cole.highestMatching.get
          val d = c.x.asInstanceOf[DomAst]
          val m = d.value.asInstanceOf[EMsg]
          val s = c.diffs.values.map(_._2).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")
          d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s
        }

        // did some rules succeed?
        if (a.children.isEmpty) {
          //            if(! settings.sketchMode) {
          // oops - add test failure
          a.children append DomAst(
            TestResult(
              "fail",
              label("found", "danger") + " " + cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(",")).mkString),
            "test"
          ).withSpec(e)
          //            } else {
          //               or... fake it, sketch it
          //              todo should i add the test as a warning?
          //              a.children appendAll e.sketch(None).map(x => DomAst(x, AstKinds.GENERATED).withSpec(e))
          //            }
        }
        }
      }

      case e: ExpectV => {
        val cole = new MatchCollector()

        // identify sub-trees that it applies to
        val targets = if(e.when.isDefined) {
          // find generated messages that should be tested
          root.collect {
            case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(n)) => d
          }
        } else List(root)

        if(targets.size > 0) {
        // todo look at all possible targets - will need to create a matchCollector per etc
        val vals = targets.head.collect {
          case d@DomAst(n: EVal, k, _, _) if AstKinds.isGenerated(k) => n.p
        }

          // test each generated value
        if (e.test(vals, Some(cole)))
          a.children append DomAst(TestResult("ok"), AstKinds.TEST).withSpec(e)

        // did some rules succeed?
        if (a.children.isEmpty) {
          // oops - add test failure
//          if(! settings.sketchMode) {
            a.children append DomAst(TestResult("fail"), AstKinds.TEST).withSpec(e)
//          } else {
            // or... fake it, sketch it
            //todo should i add the test as a warning?
//            a.children appendAll e.sketch(None).map(x=>EVal(x)).map(x => DomAst(x, "generated.sketched").withSpec(e))
//          }
        }
        }
      }

      case s@_ => {
        clog << "NOT KNOWN: " + s.toString
      }
    }
}

