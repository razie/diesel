/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import org.bson.types.ObjectId
import razie.diesel.dom._
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx

/** base common object-DB executor */
abstract class EEDieselDbExecutor (name:String) extends EExecutor(name) {

  /** default query limit */
  final val DEFAULT_SIZE = 500L

  override def isMock: Boolean = true

  def realm (implicit ctx: ECtx) = ctx.root.settings.realm.mkString

  /** the name of the connection as passed in, or "default" */
  def connectionName (implicit ctx: ECtx) =
    ctx.get("connection").getOrElse("default")

  /** size if passed in or default */
  def getQSize (implicit ctx: ECtx) = ctx.getp("size").map(_.calculatedTypedValue.asLong).getOrElse(DEFAULT_SIZE)
  /** from if passed in */
  def getQFrom (implicit ctx: ECtx) = ctx.getp("from").map(_.calculatedTypedValue.asLong)

  /** the collection/table/entity type */
  def coll   (implicit ctx: ECtx) = ctx.getRequired("collection")

  /** simple multi-tenant or multi-env support */
  def env    (implicit ctx: ECtx) =
    ctx.get("env").orElse(ctx.get("diesel.env")).getOrElse("local")

  /** entity key */
  def key    (implicit ctx: ECtx) = {
    // backward compliant for a while. Want to use id now not key
    ctx.get("id").orElse(ctx.get("key")).getOrElse(ctx.getRequired("id")) // funky to report missing id not key
  }

  /** optional entity key */
  def newKey    (implicit ctx: ECtx) = {
    // backward compliant for a while. Want to use id now not key
    ctx.get("id").orElse(ctx.get("key")).getOrElse(new ObjectId().toString)
  }

  /** todo per user ? */
  def userId(implicit ctx: ECtx) = None//ctx.root.settings.userId

  /** filter known parms and leave only query parms */
  def otherQueryParms (in: EMsg) (implicit ctx: ECtx) = in
      .attrs
      .filter(_.name != "connection")
      .filter(_.name != "collection")
      .filter(_.name != "size")
      .filter(_.name != "from")
      .filter(_.name != "id")
      .filter(_.ttype != WTypes.UNDEFINED)
      .map(p => ("content." + p.name, p.calculatedValue))
      .toMap

}

