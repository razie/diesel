/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.diesel.dom.RDOM.P
import razie.diesel.dom.RDOM.P.undefined
import razie.diesel.dom.{ParmSource, RDOM}
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.hosting.{RkReactors, Website}
import razie.wiki.{Config, Services}
import scala.collection.JavaConverters.{asScalaSetConverter, mapAsScalaMapConverter, propertiesAsScalaMapConverter}
import services.DieselCluster

/** source for parms starting with "diesel"
  *
  * keep this up to date: [[http://specs.dieselapps.com/Topic/Diesel_data_model]]
  *
  * @param ctx is the root context of this engine
  */
class DieselParmSource (ctx:DomEngECtx) extends ParmSource {
  override def name = "diesel"

  override def remove(name: String): Option[P] = ???

  override def getp(name: String): Option[P] = name match {

    case "env" =>
      // first overrides, then settings and lastly current envList setting
      None
          .orElse(ctx.settings.env.map(new P("diesel.env", _)))
          .orElse(Option(new P("diesel.env", ctx.dieselEnv(ctx))))

    // todo deprecated - remove
    case "userid" => Some(new P("diesel.userid", ctx.dieselUser(ctx)))
    case "username" => Some(new P("diesel.username", ctx.dieselAU(ctx).map(_.userName).mkString))

    case "user" => {

      if(ctx.dieselAU(ctx).isEmpty) Option(P.undefined("diesel.user"))
      else next("diesel.user", Map(
        "id" -> (n => {
          Left(new P("diesel.user.id", ctx.dieselUser(ctx)))
        }),

        "username" -> (n => {
          // leave this lazy - it is expensive
          Left(new P("diesel.user.username", ctx.dieselAU(ctx).map(_.userName).mkString))
        }),

        "name" -> (n => {
          // leave this lazy - it is expensive
          Left(new P("diesel.user.name", ctx.dieselAU(ctx).map(_.userName).mkString))
        }),

        "xapikey" -> (n => {
          // leave this lazy - it is expensive
          Left(new P("diesel.user.xapikey", ctx.dieselAU(ctx).flatMap(_.apiKey).mkString))
        }),

        "roles" -> (n => {
          // leave this lazy - it is expensive
          Left(P.fromSmartTypedValue("diesel.user.roles", ctx.dieselAU(ctx).toList.flatMap(_.roles.toList)))
        }),

        "authClient" -> (n => {
          // leave this lazy - it is expensive
          Left(P.fromSmartTypedValue("diesel.user.authClient", ctx.dieselAU(ctx).flatMap(_.authClient).mkString))
        }),

        "authRealm" -> (n => {
          // leave this lazy - it is expensive
          Left(P.fromSmartTypedValue("diesel.user.authRealm", ctx.dieselAU(ctx).flatMap(_.authRealm).mkString))
        }),

        "authMethod" -> (n => {
          // leave this lazy - it is expensive
          Left(P.fromSmartTypedValue("diesel.user.authMethod", ctx.dieselAU(ctx).flatMap(_.authMethod).mkString))
        }),

        "eName" -> (n => {
          // leave this lazy - it is expensive
          Left(new P("diesel.user.eName", ctx.dieselAU(ctx).map(_.ename).mkString))
        }),

        "token" -> (n => {
          // leave this lazy - it is expensive
          ctx.dieselAU(ctx).flatMap(_.token).map {tok=>
            Left(P.fromSmartTypedValue("diesel.user.token", tok))
          }.getOrElse {
            Left(P.undefined("diesel.user.token"))
          }
        })
      ))
    }

    case "isLocalhost" => Some(P.fromTypedValue("diesel.isLocalhost", Services.config.isLocalhost))
    case "isLocaldevbox" => Some(P.fromTypedValue("diesel.isLocaldevbox", Services.config.isLocalhost && Services.config.isRazDevMode))

    // todo deprecated, remove - search in all realms
    // this is used like diesel["realm.props"]
    case "realm.props" | "props.realm" => {
      val p = Website.getRealmProps(ctx.root.settings.realm.mkString)
      Some(P.fromTypedValue("diesel.realm.props", p))
    }

    case "props" => {

      next("diesel.props", Map(

        "config" -> (n => {
//          val m = if(Services.config.pconfig.hasPath(n)) Services.config.pconfig.getString(n) else P.undefined(n)
          val m = Services.config.pconfig.entrySet().asScala
              .filter(! _.getKey.startsWith("wiki.mongo"))
              .map( t =>(t.getKey -> t.getValue.unwrapped().toString)).toMap
          Left(P.fromSmartTypedValue("diesel.props.config", m))
        }),

        "system" -> (n => {
          val m = if (Services.config.isLocalhost) {
            System.getProperties.asScala
          } else {
            throw new IllegalArgumentException("Error: No permission")
          }
          Left(P.fromSmartTypedValue("diesel.props.system", m))
        }),

        "env" -> (n => {
          val m = if (Services.config.isLocalhost) {
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

        "props" -> (n => Right(new DieselRealmParmSource(ctx))),

        "events" -> (n => Left(
          P.fromSmartTypedValue("diesel.realm.events", Website.getRealmEvents(ctx.root.settings.realm.mkString))))
      ))
    }

    case "cluster" =>  next("diesel.cluster", Map(
      "me" -> (n => Left(
        P.fromSmartTypedValue("diesel.cluster.me", DieselCluster.clusterNodeSimple)
      )),
      "singleton" -> (n => Left(
        P.fromSmartTypedValue("diesel.cluster.singleton", DieselCluster.singleton.currentSingletonNode)
      )),
      "nodes" -> (n => Left(
        P.fromSmartTypedValue("diesel.cluster.nodes", DieselCluster.clusterNodesJson)
      )),
      "masterNode" -> (n => Left(
        P.fromSmartTypedValue("diesel.cluster.masterNode", Services.config.node
//          Map(
//          "name" -> Services.config.node,
//          "node" -> Services.config.node
//        )
      )
      ))
    ))

    case "server" => Some(P.fromSmartTypedValue("diesel.server", Map(
      "name" -> Services.config.node,
      "node" -> Services.config.node,
      "host" -> java.net.InetAddress.getLocalHost.getCanonicalHostName,
      "hostport" -> Services.config.hostport,
      "hostName" -> java.net.InetAddress.getLocalHost.getHostName,
      "ip" -> java.net.InetAddress.getLocalHost.getHostAddress
    )))

    case "engine" => {
      next(DieselMsg.ENGINE.DIESEL_ENG, Map(
        "description" -> (n => Left(
          new P(DieselMsg.ENGINE.DIESEL_ENG_DESC, ctx.engine.map(_.description).mkString)
        )),
        "status" -> (n => Left(
          new P(DieselMsg.ENGINE.DIESEL_ENG + ".status", ctx.engine.map(_.status).mkString)
        )),
        "id" -> (n => Left(
          new P(DieselMsg.ENGINE.DIESEL_ENG + ".id", ctx.engine.map(_.id).mkString)
        )),
        "json" -> (n => Left(
          P.fromSmartTypedValue(DieselMsg.ENGINE.DIESEL_ENG + ".json", ctx.engine.get.toj)
        )),
        "html" -> (n => Left(
          new P(DieselMsg.ENGINE.DIESEL_ENG + ".html", ctx.engine.get.root.toHtmlInPage)
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
    case _ => throw new DieselException(s"Can't overwrite values in this context (DieselParmSource:$name)!")
  }

  def listAttrs: List[String] = List("env", "user", "isLocalhost", "isLocaldevbox", "props", "realm", "server", "engine")

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

  def listAttrs: List[String] = ctx.listAttrs
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
    Website.forRealm(realm).foreach(_.put(p.name, p.calculatedValue(ctx)))
  }

  def listAttrs: List[String] = parms.keys.toList
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

  def put(p: P): Unit = throw new DieselException(s"Cannot set values here: ${p.name} in source ${pname} !")

  def listAttrs: List[String] = values.keys.toList

  /** itself as a P */
  override def asP: P = P.fromSmartTypedValue(
    // override to avoid calculating stuff on display toHtml
    name,
    listAttrs
        .map(p => (p, "..."))
        .toMap
  )
}

