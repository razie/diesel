/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import org.bson.types.ObjectId
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.RDomain
import razie.diesel.engine.nodes.{EVal, EWarning}
import razie.diesel.engine.{DieselCtxParmSource, DieselParmSource, DieselRealmParmSource, DomAst, DomEngECtx}
import razie.diesel.model.DieselMsg
import razie.tconf.{DSpec, DTemplate}
import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}

/**
  * a context - LIST, use to see speed of list
  *
  * it's started with an initial set of values
  *
  * @param cur  - initial static values for this context
  * @param base - optional parent context
  * @param curNode
  */
class SimpleECtx(
  val cur: List[P] = Nil,
  val base: Option[ECtx] = None,
  val curNode: Option[DomAst] = None
) extends ECtx {

  protected var _domain: Option[RDomain] = None
  protected var _specs: List[DSpec] = Nil
  private var userId: Option[String] = None

  override def name = "Ctx"

  //payload is never static

  // overwritten values in this context
  var attrs: List[P] = Nil
  var _hostname: Option[String] = None

  // payload goes up always
  // only time this issue is when POST to diesel.rest and it will send it to the message as an attr PAYLOAD
  // that creates issues
//  if (cur.exists(_.name == Diesel.PAYLOAD))
//    root.engine.map(
//      _.warning(EWarning("It's non-intuitive to pass payload as argument! " + this.curNode.mkString.take(100))))

  override def credentials: Option[String] = userId orElse base.flatMap(_.credentials)
  // once given, you cannot change credentials

  def withP(p: P) = {
    put(p)
    this
  }

  def withHostname(s: String) = {
    _hostname = Option(s)
    this
  }

  def withCredentials(s: Option[String]) = {
    if (userId.isEmpty) userId = s
    this
  }

  def withDomain(r: RDomain) = {
    _domain = Option(r)
    this
  }

  override def flattenAllAttrs: List[P] = {
    val l = (attrs ++ cur ++ base.toList.flatMap(_.flattenAllAttrs)).distinct
    // distinct by name
    // copied distinct to do by name
    val b = new ListBuffer[P]()
    val seen = mutable.HashSet[String]()
    for (x <- l) {
      if (x.name.length > 0 && !seen(x.name)) {
        b += x
        seen += x.name
      }
    }
    b.toList
  }

  override def listAttrs: List[String] = flattenAllAttrs.map(_.name)

  override def domain: Option[RDomain] = _domain orElse base.flatMap(_.domain)

  override def specs: List[DSpec] = _specs ::: base.toList.flatMap(_.specs)

  override def hostname: Option[String] = _hostname orElse base.flatMap(_.hostname)

  override def findTemplate(ea: String, direction: String = ""): Option[DTemplate] = {
    specs.flatMap(_.findSection(ea, direction).toList).headOption
  }

  /** find template with predicate */
  override def findTemplate(p: DTemplate => Boolean): Option[DTemplate] = {
    specs.foldLeft[Option[DTemplate]](None)((a, s) =>
      // todo low stop searching when found
      a.orElse(s.findSection(p))
    )
  }

  /** check predicate on all values */
  override def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    cur.exists(f) || attrs.exists(f) || base.exists(_.existsNL(f))

  /** check predicate on all values, except locals */
  override def existsNL(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    attrs.exists(f) || base.exists(_.existsNL(f))

  /** check if parm has been overwritten*/
  override def isOverwritten(name:String): scala.Boolean =
    attrs.exists(_.name == name)

  /** check predicate on all values, only locals */
  override def existsL(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    cur.exists(f)

  override def remove(name: String): Option[P] = {
    attrs.find(a => a.name == name).map { p =>
      attrs = attrs.filter(_.name != name)
      // not removing from base
      p
    } orElse base.flatMap(_.remove(name))
  }

  override def clear: Unit = {
    attrs = Nil
    base.foreach(_.clear)
  }

  /** we delegate on empty values - empty is the same as missing then
    *
    * this is relevant - current message won't work otherwise, like ctx.echo(parm)
    * */
  def getp(name: String): Option[P] =
    None
        .orElse(attrs.find(a => a.name == name)) // was it overwritten?
        .orElse(cur.find(a => a.name == name && a.hasCurrentValue)) // or is it the static value?
//        .orElse(pu(name))
        .orElse(specpu(name))
        .orElse(base.flatMap(_.getp(name)))
        .orElse(getpFromDomain(name))

  // uniques
  val uid = P("DIESEL_UID", new ObjectId().toString)
  val millis = P.fromSmartTypedValue("DIESEL_MILLIS", System.currentTimeMillis())

  /** source some of the unique values to help rerun tests */
  protected def pu(s: String): Option[P] = s match {
    case "DIESEL_UID" => Option(uid)
    case "DIESEL_MILLIS" => Option(millis)
    case "DIESEL_CURMILLIS" => Option(P.fromSmartTypedValue("DIESEL_CURMILLIS", System.currentTimeMillis()))

    case "diesel.db.newId" => Option(P("diesel.db.newId", new ObjectId().toString))

    case DieselMsg.ENGINE.DIESEL_ENG_DESC => Option(
      P(DieselMsg.ENGINE.DIESEL_ENG_DESC, root.engine.map(_.description).mkString))

    case "diesel" => {
      Option(P.fromSmartTypedValue("diesel", new DieselParmSource(root)))
    }

    case "dieselRealm" => {
      Option(P.fromSmartTypedValue("dieselRealm", new DieselRealmParmSource(root)))
    }

    case "dieselRoot" => {
      Option(P.fromSmartTypedValue("dieselRoot", new DieselCtxParmSource("dieselRoot", root, this)))
    }

    case "ctx" | "dieselScope" => {
      Option(P.fromSmartTypedValue("dieselScope", new DieselCtxParmSource("dieselScope", this.getScopeCtx, this)))
    }

    case _ => None
  }

  /** just needed this for hacking purposes */
  protected def specpu(s: String): Option[P] = s match {
    case "ctx" | "dieselScope" => {
      Option(P.fromSmartTypedValue("dieselScope", new DieselCtxParmSource("dieselScope", this.getScopeCtx, this)))
    }

    case _ => None
  }

  /** see if we can source a value from the domain, i.e. static $val */
  protected def getpFromDomain(name: String): Option[P] = {
    domain.flatMap(_.moreElements.collectFirst {
      case v: EVal if v.p.name == name => v.p.copyFrom(v.p) // do NOT allow modifying of domain values.
      // So the copy can get recalculated without impacting the original
    })
  }

  /** propagates by default up - see the Scope context which will not */
  override def put(p: P): Unit = {
    if (base.isDefined)
      base.get.put(p)
    //    else if(p.ttype == WTypes.UNDEFINED)
    //    remove(p)

    // overwrite locally if static - so overwriting arguments works.
    if (base.isEmpty || cur.exists(_.name == p.name))
      attrs = p :: attrs.filter(_.name != p.name)
  }

  /** propagates by default up - see the Scope context which will not */
  override def putAll(p: List[P]): Unit = {
    p.foreach(put)
//    if (base.isDefined) base.get.putAll(p)
//    else attrs = p ::: attrs.filter(x => !p.exists(_.name == x.name))
  }

  /** copy curr values - when this is replacing another type of context */
  def replacing(other: SimpleECtx) = {
    this.attrs = other.attrs
    this
  }

  override def root: DomEngECtx = base.map(_.root).orNull

  override def toString = this.getClass.getSimpleName + ":cur==" +
      cur.mkString(",") + ":attrs==" + attrs.mkString(",") //+ base.map(_.toString).mkString
}
