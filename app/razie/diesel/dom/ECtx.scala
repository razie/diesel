package razie.diesel.dom

import razie.diesel.dom.RDOM.P
import mod.diesel.model.{DomAst, DomEngineSettings}

/** uniquely identifies a piece of specification
  *
  * @param source - the source system: inventory understands and delegates to
  * @param wpath - unique id of the spec
  * @param ver - optionally identify a version of the spec
  * @param draft - optionally identify a certain temporary variant (i.e. autosaved by username)
  */
case class SpecPath (source:String, wpath:String, ver:Option[String]=None, draft:Option[String]=None)

/** a specification - can be a text, a wiki or anything else we can parse to extract a diesel */
trait DSpec {
  def specPath : SpecPath
  def findTemplate (name:String) : Option[DTemplate]
}

/** a specification of a template */
trait DTemplate {
  def content : String
  def parmStr : String
  def specPath : SpecPath
  def pos : EPos

  def parms =
    if(parmStr.trim.length > 0)
      parmStr.split(",").map(s=>s.split("=")).map(a=> (a(0), a(1))).toMap
    else Map.empty[String,String]

}

/** can retrieve specs, by wpath and ver */
trait DSpecInventory {
  def find (path:SpecPath) : Option[DSpec]
}

/*
 * a map like context of attribute values.
 *
 * contexts are hierarchical, with inheritance
 *
 * They also capture a spec environment: a list of specs (could be drafts or a specific version)
 *
 * Also, they have an optional domain
 *
 * keep a ref to the original specs, to get more details
 *
 * So, for the duration of this context, the configuration is the right version
 */
trait ECtx {
  def root: ECtx
  def base: Option[ECtx]
  def hostname:Option[String]

  def credentials: Option[String] // original credentials
  def domain: Option[RDomain]
  def specs: List[DSpec]
  def domain_= (x:Option[RDomain])
  def specs_= (x : List[DSpec])

  def findTemplate (ea:String) : Option[DTemplate]

  def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean

  def apply  (name: String): String
  def get    (name: String): Option[String]
  def put    (p: P): Unit
  def putAll (p: List[P]): Unit

  def curNode : Option[DomAst]
}

// a context - LIST, use to see speed of list
class SimpleECtx(val cur: List[P] = Nil, val base: Option[ECtx] = None, val curNode:Option[DomAst]) extends ECtx {
  var attrs: List[P] = Nil
  var _domain: Option[RDomain] = None
  var _specs: List[DSpec] = Nil
  var _hostname:Option[String] = None
  private var userName:Option[String] = None

  def credentials: Option[String] = userName
  // once given, you cannot change credentials
  def withCredentials(s: String) = {
    if(userName.isEmpty) userName = Some(s)
    this
  }

  def domain: Option[RDomain] = base.map(_.domain) getOrElse _domain
  def specs: List[DSpec] = base.map(_.specs) getOrElse _specs

  def hostname: Option[String] = _hostname orElse base.flatMap(_.hostname)

  def domain_= (x:Option[RDomain])  = base.map(_.domain = x) getOrElse (_domain = x)
  def specs_= (x : List[DSpec]) = base.map(_.specs = x) getOrElse (_specs = x)

  def withSpecs(s: List[DSpec]) = {
    specs = s ::: specs
    this
  }

  def withDomain(r: RDomain) = {
    domain = Some(r)
    this
  }

  def findTemplate (ea:String) : Option[DTemplate] = {
    specs.flatMap(_.findTemplate(ea).toList).headOption
  }

  def apply(name: String): String = get(name).mkString

  def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    cur.exists(f) || attrs.exists(f) || base.exists(_.exists(f))

  def get(name: String): Option[String] =
    cur.find(_.name == name).orElse(
      attrs.find(_.name == name)).map(_.dflt).orElse(
      base.flatMap(_.get(name)))

  def put(p: P): Unit = attrs = p :: attrs.filter(_.name != p.name)

  def putAll(p: List[P]): Unit = attrs = p ::: attrs.filter(x => !p.exists(_.name == x.name))

  def root: ECtx = base.map(_.root).getOrElse(this)

  override def toString =
    cur.mkString(",") + attrs.mkString(",") + base.map(_.toString).mkString
}

/** static context will delegate updates to parent */
class StaticECtx(cur: List[P] = Nil, base: Option[ECtx] = None, curNode:Option[DomAst]=None) extends SimpleECtx(cur, base, curNode) {
  //todo should I throw up if no base?
  override def put(p: P): Unit = base.map(_.put(p))

  override def putAll(p: List[P]): Unit = base.map(_.putAll(p))
}

/** specific root context for an enging instance */
class DomEngECtx(val settings:DomEngineSettings, cur: List[P] = Nil, base: Option[ECtx] = None) extends SimpleECtx(cur, base, None) {
  var overwritten: Option[ECtx] = None
  var persisted: Boolean = false

  private def ps(name:String) : Option[String] = settings.postedContent.flatMap(_.get(name))

  override def apply(name: String): String = overwritten.map(_.apply(name)).orElse(ps(name)).getOrElse(super.apply(name))

  override def get(name: String): Option[String] = overwritten.flatMap(_.get(name)).orElse(ps(name)).orElse(super.get(name))

  override def put(p: P): Unit = overwritten.map(_.put(p)).getOrElse(super.put(p))

  override def putAll(p: List[P]): Unit = overwritten.map(_.putAll(p)).getOrElse(super.putAll(p))

  override def root: ECtx = overwritten.map(_.root).getOrElse(super.root)

  override def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    overwritten.map(_.exists(f)).orElse(settings.postedContent.map(_.exists(f))).getOrElse(super.exists(f))

  def overwrite(ctx: ECtx) =
    if (this != ctx)
      overwritten = Some(ctx)
}

/** context for an internal scope - parent is scope or Eng
  *
  * todo when saving a context, do I save children too?
  *
  * todo when loading context, how do I reover active scope contexts
  */
class ScopeECtx(cur: List[P] = Nil, base: Option[ECtx] = None, curNode:Option[DomAst]=None) extends SimpleECtx(cur, base, curNode) {
}

object ECtx {
  /** empty context */
  val empty = new StaticECtx()
}

/** reference where the item was defined, so we can scroll back to it */
case class EPos (wpath:String, line:Int, col:Int) {
  def this(o:Map[String, Any]) =
    this(
      o.getOrElse("wpath", "").toString,
      o.getOrElse("line", "0").toString.toInt,
      o.getOrElse("col", "0").toString.toInt
    )

  def toJmap = Map (
    "wpath" -> wpath,
    "line" -> line,
    "col" -> col
  )

  override def toString = s"""{wpath:"$wpath", line:$line, col:$col}"""
  def toRef = s"""weref('$wpath', $line, $col)"""
}

