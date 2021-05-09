/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.Diesel
import razie.diesel.dom.RDOM.{C, P}
import razie.diesel.dom.{DieselAsset, DieselRulesInventory, DomInvWikiPlugin, DomInventories, RDomain, WikiDomain}
import razie.diesel.engine.{AstKinds, DomAst}
import razie.diesel.engine.nodes.{EError, EInfo, EMsg, EVal, EWarning, MatchCollector}
import razie.diesel.expr.{DieselExprException, ECtx}
import razie.tconf.FullSpecRef
import scala.collection.mutable

/* executors for inventories */
class EEDomInventory extends EExecutor("diesel.inv") {
  final val DB = "diesel.inv"

  final val TESTC = "diesel.inv.testConnection"
  final val UPSERT = "diesel.inv.upsert"
  final val UPSERT_BULK = "diesel.inv.upsert.bulk"
  final val REG = "diesel.inv.register"
  final val CONNECT = "diesel.inv.connect"
  final val LISTALL = "diesel.inv.listAll"
  final val FIND = "diesel.inv.find"
  final val REMOVE = "diesel.inv.remove"
  final val QUERY = "diesel.inv.query"

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val settings = ctx.root.engine.get.settings
    val realm = ctx.root.engine.get.settings.realm.get
    val dom = WikiDomain(realm)

    in.ea match {

      case REG => {
        //just register inv factory assocs
        val inv = ctx.getRequired("inventory")
        ctx.getRequired("classNames").split(",").foreach(
          DomInventories.registerPlugin(realm, _, inv)
        )
        Nil
      }

      case CONNECT => {
        // create and add to wikidom and connect it
        val inv = ctx.getRequired("inventory")
        val conn = ctx.get("connection").getOrElse("")
        val sup = ctx.get("super")
        val env = ctx.get("env").orElse(settings.env).mkString

        val i = dom.allPlugins.filter(_.name == inv)
        var pci = i.find(_.conn == conn || conn.length == 0)
        if (pci.isEmpty) {

          // if not already connected, connect - i.e. create an instance
          pci = i.headOption
              .orElse(
                DomInventories.pluginFactories
                    .find(x => x.name == inv || sup.isDefined && sup.get == x.name)
              )
              .orElse(
                // if not one of the knows factories, each registered repo is a rules inventory type
                dom.allPlugins.find(_.isInstanceOf[DieselRulesInventory])
              )
              .flatMap(_.mkInstance(realm, conn, dom.wi, inv).headOption)
          pci.map(dom.addPlugin)
        }

        // now we have it, tell it to connect
        val res = pci.map(
          _.connect(dom.rdom, env)
              .fold(
                pp => EVal(pp),
                m => m.withPos(in.pos)
              )
        ).getOrElse(P.undefined(Diesel.PAYLOAD))
        List(res)
      }

      case TESTC => {
        val plugin = DomInventories.getPlugin(
          realm,
          ctx.getRequired("inventory"),
          ctx.get("connection").getOrElse("")
        )

        val res = plugin.map(
          _.testConnection(dom.rdom, "")
              .fold(
                pp => EVal(pp),
                m => m.withPos(in.pos)
              )
        ).getOrElse(
          EVal(P.undefined(Diesel.PAYLOAD))
        )

        List(res)
      }

      case UPSERT | UPSERT_BULK => {
        val entity = ctx.getp("entity").orElse(ctx.getp(Diesel.PAYLOAD))
        val entities = ctx.getp("entities").orElse(ctx.getp(Diesel.PAYLOAD))
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.get("className").getOrElse(entity.get.ttype.schema)

        // if class not known, make it up
        // todo or blow up?
        val cc = dom.rdom.classes.get(cls)
        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))

        val j = entity.get.value.get.asJson
        val jo = razie.js.tojson(j)

        // we use the assetref, then key attribute then at last ask for a key input - avoids context junk
        val k =
          j.get("assetRef")
              .filter(_.isInstanceOf[collection.Map[_, _]])
              .map(_.asInstanceOf[collection.Map[String, Any]])
              .flatMap(_.get("key"))
              .orElse(
                j.get("key")
              )
              .orElse(
                ctx.get("key")
              )
              .mkString

        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        //          val o = DomInventories.oFromJ("x", jo, c, t, Array[String]())
        val plugin = DomInventories.getPluginForClass(realm, c, conn)
        val ref = FullSpecRef(
          plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
          plugin.map(_.conn).getOrElse(conn),
          cls,
          k,
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )
        val a = new DieselAsset[P](ref, entity.get)

        if (plugin.isEmpty) throw new DieselExprException(s"Inventory not found for $cls")

        val res = plugin
            .map(_.upsert(dom.rdom, ref, a)
                .fold(
                  oda => {
                    oda.map(x => EVal(x.getValueP)).getOrElse(
                      EVal(P.undefined(Diesel.PAYLOAD))
                    )
                  },
                  m => m.withPos(in.pos)
                )
            ).getOrElse(
          EVal(P.undefined(Diesel.PAYLOAD)
          )
        )

        List(res)
      }

      case FIND => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("className")
        val k = ctx.get("key")

        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val res = k.map { key =>
          val ref = FullSpecRef(
            plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
            plugin.map(_.conn).getOrElse(conn),
            cls,
            key,
            ctx.get("section").getOrElse(""),
            ctx.root.engine.get.settings.realm.get
          )

          plugin.map(_.findByRef(dom.rdom, ref)
              .fold(
                oda => {
                  oda.map(x => EVal(x.getValueP)).getOrElse(
                    EVal(P.undefined(Diesel.PAYLOAD))
                  )
                },
                m => m.withPos(in.pos)
              )).getOrElse(
            List(
              EWarning("No inventory found for class: " + ref.cls),
              EVal(P.undefined(Diesel.PAYLOAD))
            )
          )
        }.getOrElse {
          // flatmap like behavior
          List(
            EWarning("key missing!"),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        }

        List(res)
      }

      case REMOVE => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("className")
        val k = ctx.get("key")

        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val res = k.map { key =>
          val ref = FullSpecRef(
            plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
            plugin.map(_.conn).getOrElse(conn),
            cls,
            key,
            ctx.get("section").getOrElse(""),
            ctx.root.engine.get.settings.realm.get
          )

          plugin.map(_.remove(dom.rdom, ref)
              .fold(
                oda => {
                  oda.map(x => EVal(x.getValueP)).getOrElse(
                    EVal(P.undefined(Diesel.PAYLOAD))
                  )
                },
                m => m.withPos(in.pos)
              )).getOrElse(
            List(
              EWarning("No inventory found for class: " + ref.cls),
              EVal(P.undefined(Diesel.PAYLOAD))
            )
          )
        }.getOrElse {
          // flatmap like behavior
          List(
            EWarning("key missing!"),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        }

        List(res)
      }

      case LISTALL => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("className")
        val countOnly = ctx.get("countOnly").getOrElse("false").toBoolean
        val start = ctx.get("from").getOrElse("0").toLong
        val limit = ctx.get("size").getOrElse("100").toLong
        val sort = ctx.get("sort").getOrElse("")

        // if class not known, make it up
        // todo or blow up?
        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)

        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val ref = FullSpecRef(
          plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
          plugin.map(_.conn).getOrElse(conn),
          cls,
          "",
          "",
          ctx.root.engine.get.settings.realm.get
        )

        val res = plugin.map(_.listAll(dom.rdom, ref, start, limit, sort.split(","), countOnly)
            .fold(
              lda => {
                List(
                  EVal(P.fromSmartTypedValue(
                    Diesel.PAYLOAD,
                    // todo object with total etc
                    Map(
                      "total" -> lda.total,
                      "data" -> lda.data.map(x => EVal(x.getValueP))
                    )
                  ))
                )
              },
              m => List(m.withPos(in.pos))
            )).getOrElse(
          List(
            EWarning("No inventory found for class: " + ref.cls),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        )

        res
      }

      case QUERY => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("className")
        val start = ctx.get("from").getOrElse("0").toLong
        val limit = ctx.get("size").getOrElse("1000").toLong
        val countOnly = ctx.get("countOnly").getOrElse("false").toBoolean
        val sort = ctx.get("sort").getOrElse("")

        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val q = ctx.getp("query").getOrElse(P.fromSmartTypedValue(Diesel.PAYLOAD, "{}"))
//        val sort = ctx.getp("sort").getOrElse(P.fromSmartTypedValue(Diesel.PAYLOAD, "{}"))

        val ref = FullSpecRef(
          plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
          plugin.map(_.conn).getOrElse(conn),
          cls,
          "",
          "",
          ctx.root.engine.get.settings.realm.get
        )

        val epath = Right(q.calculatedTypedValue.asJson)

        val res = plugin.map(_.findByQuery(dom.rdom, ref, epath, start, limit, sort.split(","), countOnly)
            .fold(
              lda => {
                val data = if (countOnly) Nil else lda.data.map(x => EVal(x.getValueP))

                List(EVal(
                  P.fromSmartTypedValue(
                    Diesel.PAYLOAD,
                    // todo object with total etc
                    Map(
                      "total" -> lda.total,
                      "data" -> data
                    )
                  )))
              },
              m => List(m.withPos(in.pos))
            )).getOrElse(
          List(
            EWarning("No inventory found for class: " + ref.cls),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        )

        res
      }

      case _ => throw new DieselExprException("Msg not known: " + in.ea)
    }
  }

  override def toString = "$executor::diesel.inv "

  override val messages: List[EMsg] =
    EMsg(DB, "register") ::
        EMsg(DB, "connect") ::
        EMsg(DB, "testConnection") ::
        EMsg(DB, "upsert") ::
        EMsg(DB, "find") ::
        EMsg(DB, "query") ::
        EMsg(DB, "remove") ::
        EMsg(DB, "listAll") ::
        Nil
}

