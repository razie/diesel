/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.Diesel
import razie.diesel.dom.RDOM.{C, P}
import razie.diesel.dom.{DieselAsset, DomInvWikiPlugin, DomInventories, RDomain, WikiDomain}
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes.{EInfo, EMsg, EVal, EWarning, MatchCollector}
import razie.diesel.expr.{DieselExprException, ECtx}
import razie.tconf.FullSpecRef
import scala.collection.mutable

/* executors for inventories */
class EEDomInventory extends EExecutor("diesel.inv") {
  final val DB = "diesel.inv"

  final val TESTC = "diesel.inv.testConnection"
  final val UPSERT = "diesel.inv.upsert"
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
        val inv = ctx.getRequired("inventory")
        ctx.getRequired("classes").split(",").foreach(
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
                dom.allPlugins.find(_.isInstanceOf[DomInvWikiPlugin])
              )
              .flatMap(_.mkInstance(realm, conn, dom.wi, inv).headOption)
          pci.map(dom.addPlugin)
        }

        // now we have it, tell it to connect
        val res = pci.map(
          _.connect(dom.rdom, env)
              .fold(
                pp => EVal(pp),
                m => m
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
                m => m
              )
        ).getOrElse(
          EVal(P.undefined(Diesel.PAYLOAD))
        )

        List(res)
      }

      case UPSERT => {
        val entity = ctx.getp("entity").orElse(ctx.getp(Diesel.PAYLOAD))
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.get("class").getOrElse(entity.get.ttype.schema)

        // if class not known, make it up
        // todo or blow up?
        val cc = dom.rdom.classes.get(cls)
        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))

        val j = entity.get.value.get.asJson
        val jo = razie.js.tojson(j)
        val k = ctx.get("key")
            .orElse(
              j.get("assetRef")
                  .filter(_.isInstanceOf[collection.Map[_, _]])
                  .map(_.asInstanceOf[collection.Map[String, Any]])
                  .flatMap(_.get("key"))
            ).orElse(
          j.get("key")
        ).mkString

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
                  m => m
                )
            ).getOrElse(
          EVal(P.undefined(Diesel.PAYLOAD)
          )
        )

//        }).getOrElse(
//          P(Diesel.PAYLOAD, "??")
//        )

        List(res)
      }

      case LISTALL => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("class")
        val start = ctx.get("start").getOrElse("0").toLong
        val limit = ctx.get("limit").getOrElse("100").toLong

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

        val res = plugin.map(_.listAll(dom.rdom, ref, start, limit)
            .fold(
              lda => {
                List(EVal(
                  P.fromSmartTypedValue(
                    Diesel.PAYLOAD,
                    lda.map(x => EVal(x.getValueP))
                  )))
              },
              m => List(m)
            )).getOrElse(
          List(
            EWarning("No inventory found for class: " + ref.cls),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        )

        res
      }

      case FIND => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("class")
        val k = ctx.getRequired("key")

        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val ref = FullSpecRef(
          plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
          plugin.map(_.conn).getOrElse(conn),
          cls,
          k,
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )

        val res = plugin.map(_.findByRef(dom.rdom, ref)
            .fold(
              oda => {
                oda.map(x => EVal(x.getValueP)).getOrElse(
                  EVal(P.undefined(Diesel.PAYLOAD))
                )
              },
              m => m
            )).getOrElse(
          List(
            EWarning("No inventory found for class: " + ref.cls),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        )

        List(res)
      }

      case REMOVE => {
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.getRequired("class")
        val k = ctx.getRequired("key")

        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))
        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)

        val ref = FullSpecRef(
          plugin.map(_.name).orElse(ctx.get("inventory")).mkString,
          plugin.map(_.conn).getOrElse(conn),
          cls,
          k,
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )

        val res = plugin.map(_.remove(dom.rdom, ref)
            .fold(
              oda => {
                oda.map(x => EVal(x.getValueP)).getOrElse(
                  EVal(P.undefined(Diesel.PAYLOAD))
                )
              },
              m => m
            )).getOrElse(
          List(
            EWarning("No inventory found for class: " + ref.cls),
            EVal(P.undefined(Diesel.PAYLOAD))
          )
        )

        List(res)
      }

      case QUERY => {
        val ref = FullSpecRef(
          ctx.get("inventory").getOrElse(""),
          ctx.get("connection").getOrElse(""),
          ctx.getRequired("class"),
          ctx.get("key").getOrElse(""),
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )


        val res = DomInventories.findByQuery(ref, "?")
        P.of(Diesel.PAYLOAD, res) :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.inv "

  override val messages: List[EMsg] =
    EMsg(DB, "testConnection") ::
        EMsg(DB, "findByRef") ::
        EMsg(DB, "findByQuery") ::
        EMsg(DB, "listAll") ::
        EMsg(DB, "connect") ::
        EMsg(DB, "register") :: Nil
}

