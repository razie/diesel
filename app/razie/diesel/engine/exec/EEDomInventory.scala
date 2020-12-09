/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.Diesel
import razie.diesel.dom.RDOM.{C, P}
import razie.diesel.dom.{DefaultRDomainPlugin, DieselAsset, DomInventories, WikiDomain}
import razie.diesel.engine.DomAst
import razie.diesel.engine.nodes.{EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import razie.tconf.FullSpecRef

/* executors for inventories */
class EEDomInventory extends EExecutor("diesel.inv") {
  final val DB = "diesel.inv"

  final val TESTC = "diesel.inv.testConnection"
  final val CREATE = "diesel.inv.upsert"
  final val REG = "diesel.inv.register"
  final val CONNECT = "diesel.inv.connect"
  final val FIND = "diesel.inv.find"
  final val QUERY = "diesel.inv.query"

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == DB
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val settings = ctx.root.engine.get.settings
    val realm = ctx.root.engine.get.settings.realm.get

    in.ea match {

      case REG => {
        val inv = ctx.getRequired("inventory")
        ctx.getRequired("classes").split(",").foreach(
          DomInventories.invRegistry.put(_, inv)
        )
        Nil
      }

      case CONNECT => {
        // create and add to wikidom and connect it
        val inv = ctx.getRequired("inventory")
        val conn = ctx.get("connection").getOrElse("")
        val sup = ctx.get("super")
        val env = ctx.get("env").orElse(settings.env).mkString
        val dom = WikiDomain(realm)

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
                dom.allPlugins.find(_.isInstanceOf[DefaultRDomainPlugin])
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
        val dom = WikiDomain(realm)
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

      case CREATE => {
        val entity = ctx.getp("entity").orElse(ctx.getp(Diesel.PAYLOAD))
        val conn = ctx.get("connection").getOrElse("")
        val cls = ctx.get("class").getOrElse(entity.get.ttype.schema)
        val dom = WikiDomain(realm)

        // if class not known, make it up
        // todo or blow up?
        val c = dom.rdom.classes.get(cls).getOrElse(new C(cls))

        val j = entity.get.value.get.asJson
        val jo = razie.js.tojson(j)
        val k = ctx.get("key")
            .orElse(
              j.get("assetRef")
                  .filter(_.isInstanceOf[Map[String, _]])
                  .map(_.asInstanceOf[Map[String, _]])
                  .flatMap(_.get("key"))
            ).orElse(
          j.get("key")
        ).mkString

        val ref = FullSpecRef(
          ctx.get("inventory").getOrElse(""),
          conn,
          cls,
          k,
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )

        val t = c.props.find(_.name == "table").map(_.calculatedValue(ECtx.empty)).getOrElse(c.name)
        //          val o = DomInventories.oFromJ("x", jo, c, t, Array[String]())
        val a = new DieselAsset[P](ref, entity.get)
        val plugin = DomInventories.getPluginForClass(realm, c, conn)
        val res = plugin.map(_.upsert(dom.rdom, ref, a)
            .fold(
              oda => {
                oda.map(x => EVal(x.getValueP)).getOrElse(
                  EVal(P.undefined(Diesel.PAYLOAD))
                )
              },
              m => m
            )).getOrElse(
          EVal(P.undefined(Diesel.PAYLOAD))
        )

//        }).getOrElse(
//          P(Diesel.PAYLOAD, "??")
//        )

        List(res)
      }

      case FIND => {
        val ref = FullSpecRef(
          ctx.get("inventory").getOrElse("diesel"),
          ctx.get("connection").getOrElse(""),
          ctx.getRequired("class"),
          ctx.getRequired("key"),
          ctx.get("section").getOrElse(""),
          ctx.root.engine.get.settings.realm.get
        )

        val res = DomInventories.findByRef(ref)
        res
            .map(x => P.of(Diesel.PAYLOAD, List(x)))
            .getOrElse(P.of(Diesel.PAYLOAD, Nil)) ::
            Nil
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

