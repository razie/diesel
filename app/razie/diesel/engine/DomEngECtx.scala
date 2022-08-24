/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import api.dwix
import model.User
import org.bson.types.ObjectId
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.RDomain
import razie.diesel.engine.nodes.EVal
import razie.diesel.expr.{ECtx, SimpleECtx}
import razie.diesel.model.DieselMsg
import razie.hosting.{RkReactors, Website}
import razie.tconf.{DSpec, DUsers}
import razie.wiki.{Config, Services}
import scala.collection.JavaConverters.{mapAsScalaMapConverter, propertiesAsScalaMapConverter}


/** specific root context for an engine instance
  *
  * it can keep unique IDs and such, to help with tests ran continuously
  */
class DomEngECtx(val settings: DomEngineSettings, cur: List[P] = Nil, base: Option[ECtx] = None)
    extends SimpleECtx(cur, base, None) {
  var overwritten: Option[ECtx] = None
  var persisted: Boolean = false
  var engine: Option[DomEngine] = None

  override def name = "DomEngCtx"

  /** NOT for moving from engine to engine */
  def withEngine(e: DomEngine) = {
    this.engine = Some(e);
    this
  }

  def withSpecs(s: List[DSpec]) = {
    _specs = s ::: _specs
    this
  }

  def withDomain(r: RDomain) = {
    _domain = Some(r)
    this
  }

  override def apply(name: String): String =
    overwritten
        .map(_.apply(name))
        // todo not call ps to parse random payloads
        // have a separate expression for body access or are we adding each in ctx ?
//        .orElse(ps(name).map(_.currentStringValue))
        .orElse(pu(name).map(_.currentStringValue))
        .getOrElse(super.apply(name))

  override def getp(name: String): Option[P] =
    if(name.length > 0)
      overwritten
          .flatMap(_.getp(name))
          // todo not call ps to parse random payloads
          // have a separate expression for body access or are we adding each in ctx ?
//          .orElse(ps(name))
          .orElse(pu(name))
          .orElse(super.getp(name))
    else None

  override def put(p: P) {
    overwritten.map(_.put(p)).getOrElse(super.put(p))
  }

  override def putAll(p: List[P]) {
    overwritten.map(_.putAll(p)).getOrElse(super.putAll(p))
  }

  override def root = overwritten.map(_.root).getOrElse(super.root)


  override def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean =
    overwritten
        .map(_.exists(f))
        // todo remove use of postedContent - what the heck, see ps() as well
//        .orElse(settings.postedContent.map(_.exists(f)))
        .getOrElse(super.exists(f))


  /** set some of the unique values to help rerun tests */
  def setu (p:P) : Boolean = p.name match {
    case "diesel.settings.mockQuery" => true
    case _ => false
  }

  // figure out the environment for this user
  // todo once the engine starts, store the start value in settings - it can't change and we should store it,m if it's not overwritten...
  def dieselEnv(ctx:ECtx) = {
    val settings = ctx.root.engine.map(_.settings)
    val au = settings
        .flatMap(_.userId)
        .flatMap(Services.auth.cachedUserById)

    val ret = dwix.dieselEnvFor(settings.flatMap(_.realm).mkString, au)
    ret
  }

  /** user for engine - expensive! */
  def dieselAU(ctx: ECtx) = {
    val eu = ctx.root.engine.map(_.settings).flatMap(_.user)
    eu.orElse {
      val au = ctx.root.engine.map(_.settings).flatMap(_.userId)
      au.flatMap(id => DUsers.impl.findUserById(new ObjectId(id))).map(_.asInstanceOf[User])
    }
  }

  // user id if any
  def dieselUser(ctx: ECtx) = {
    val au = ctx.root.engine.map(_.settings).flatMap(_.userId)
    au.mkString
  }

  /** source from settings - only if there's some value... otherwise base won't cascade */
  private def ps(name: String): Option[P] =
    settings.postedContent.filter(_.body.length > 0).flatMap(_.getp(name))

  /** used for instance when persisting a context - will overwrite the default */
  def overwrite(ctx: ECtx): Unit =
    if (this != ctx)
      overwritten = Some(ctx)

  /** reset this engine's values */
  override def clear: Unit = {
    this.attrs = Nil
    this.overwritten.foreach(_.clear)
    this.base.foreach(_.clear)
  }

  override def toString = this.getClass.getSimpleName + ":cur==" +
      cur.mkString(",") + ":attrs==" + attrs.mkString(",") //+ base.map(_.toString).mkString
}
