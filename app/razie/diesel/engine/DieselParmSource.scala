/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.diesel.dom.RDOM.{P}
import razie.diesel.dom.{ParmSource}
import razie.diesel.expr.{ECtx}
import razie.diesel.model.DieselMsg
import razie.hosting.{RkReactors, Website}
import razie.wiki.{Config, Services}
import scala.collection.JavaConverters.{mapAsScalaMapConverter, propertiesAsScalaMapConverter}

/** source for parms starting with "diesel"
  *
  * @param ctx is the root context of this engine
  */
class DieselParmSource (ctx:DomEngECtx) extends ParmSource {
  def name = "diesel"

  def remove(name: String): Option[P] = ???

  def getp(name: String): Option[P] = name match {

    case "env" =>
      // first overrides, then settings and lastly current envList setting
      None
          .orElse(ctx.settings.env.map(P("diesel.env", _)))
          .orElse(Some(P("diesel.env", ctx.dieselEnv(ctx))))

    case "user" => Some(P("diesel.user", ctx.dieselUser(ctx)))
    case "username" => Some(P("diesel.username", ctx.dieselUsername(ctx)))
    case "isLocalhost" => Some(P.fromTypedValue("diesel.isLocalhost", Services.config.isLocalhost))
    case "isLocaldevbox" => Some(P.fromTypedValue("diesel.isLocaldevbox", Services.config.isLocalhost && Services.config.isRazDevMode))

    // todo deprecated, remove - search in all realms
    case "realm.props" | "props.realm" => {
      val p = Website.getRealmProps(ctx.root.settings.realm.mkString)
      Some(P.fromTypedValue("diesel.realm.props", p))
    }

    case "props" => {
      next("diesel.props", Map(
        "system" -> (n => {
          val m = if (Config.isLocalhost) {
            System.getProperties.asScala
          } else {
            throw new IllegalArgumentException("Error: No permission")
          }
          Left(P.fromSmartTypedValue("diesel.props.system", m))
        }),
        "env" -> (n => {
          val m = if (Config.isLocalhost) {
            System.getenv().asScala
          } else {
            throw new IllegalArgumentException("Error: No permission")
          }
          Left(P.fromSmartTypedValue("diesel.props.env", m))
        }),
        "realm" -> (n => Right(new DieselRealmParmSource(ctx)))
      ))
    }

    case "realm" => {
      next("diesel.realm", Map(
        "name" -> (n => Left(P.fromSmartTypedValue("diesel.realm.name", ctx.settings.realm.mkString))),
        "local" -> {
          val p = if (Services.config.isLocalhost)
            P.fromSmartTypedValue("diesel.realm.local", RkReactors.forHost(Services.config.simulateHost).mkString)
          else P.undefined("diesel.realm.local")
          (n => Left(p))
        },
        "props" -> (n => Right(new DieselRealmParmSource(ctx)))
      ))
    }

    case "server" => Some(P.fromSmartTypedValue("diesel.server", Map(
      "node" -> Config.node,
      "host" -> java.net.InetAddress.getLocalHost.getCanonicalHostName,
      "hostName" -> java.net.InetAddress.getLocalHost.getHostName,
      "ip" -> java.net.InetAddress.getLocalHost.getHostAddress
    )))

    case "engine" => {
      next(DieselMsg.ENGINE.DIESEL_ENG, Map(
        "description" -> (n => Left(
          P(DieselMsg.ENGINE.DIESEL_ENG_DESC, ctx.engine.map(_.description).mkString)
        )),
        "id" -> (n => Left(
          P(DieselMsg.ENGINE.DIESEL_ENG + ".id", ctx.engine.map(_.id).mkString)
        )),
        "json" -> (n => Left(
          P.fromSmartTypedValue(DieselMsg.ENGINE.DIESEL_ENG + ".json", ctx.engine.get.toj)
        )),
        "html" -> (n => Left(
          P(DieselMsg.ENGINE.DIESEL_ENG + ".html", ctx.engine.get.root.toHtmlInPage)
        )),
        "settings" -> (n => Left(
          P.fromSmartTypedValue(DieselMsg.ENGINE.DIESEL_ENG + ".settings", ctx.engine.get.settings.toJson)
      ))
      ))
    }

    case _ => None
  }

  /** the values are lazy */
  def next(name: String, values: Map[String, String => Either[P, ParmSource]]) =
    Some(P.fromTypedValue(name, new NextDParmSource(ctx, name, values)))

  def put(p: P): Unit = p.name match {
    // for "env" we could override locally only
    case _ => throw new DieselException("Can't overwrite values in this context!")
  }

  def listAttrs: List[P] = Nil

}

/** source for parms static at realm level */
class DieselCtxParmSource(val name: String, ctx: ECtx, origCtx: ECtx) extends ParmSource {

  def remove(name: String): Option[P] = ctx.remove(name)

  def getp(name: String): Option[P] = ctx.getp(name)

  def put(p: P): Unit = {
    if ("dieselScope" == name) {
      // remove any overload from all contexts until the scope
      origCtx.allToScope.foreach(
        _.remove(p.name)
      )

      // scope vars are set in the closest enclosing ScopeECtx or EngCtx
      // the idea is to bypass the enclosing RuleScopeECtx
    }

    ctx.put(p)
  }

  def listAttrs: List[P] = ctx.listAttrs
}

/** source for parms static at realm level */
class DieselRealmParmSource(ctx: DomEngECtx) extends ParmSource {
  val realm = ctx.root.settings.realm.mkString

  def parms = {
    Website.getRealmProps(realm)
  }

  def name = "diesel.realm.props"

  def remove(name: String): Option[P] = ???

  def getp(name: String): Option[P] = name match {
    case _ => {
      val p = parms
      p.get(name)
    }
  }

  def put(p: P): Unit = {
    Website.putRealmProps(realm, p.name, p.calculatedP(ctx))
    Website.forRealm(realm).map(_.put(p.name, p.calculatedValue(ctx)))
  }

  def listAttrs: List[P] = {
    val p = parms
    p.values.toList
  }

}

/** todo hierarchical source for objects? */
class NextDParmSource(ctx: ECtx, pname: String, values: Map[String, String => Either[P, ParmSource]]) extends
    ParmSource {
  def name = pname

  def getp(name: String): Option[P] = values.get(name).flatMap(_.apply(name) match {
    case Left(p) => Some(p)
    case Right(ps) => Some(P.fromSmartTypedValue(pname + "." + name, ps))
  })

  def remove(name: String): Option[P] = ???

  def put(p: P): Unit = ???

  def listAttrs: List[P] = values.keys.map(x=> P.undefined(x)).toList
}

