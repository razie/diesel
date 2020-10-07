/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model.exec

import controllers.Wikil
import razie.clog
import razie.diesel.dom.RDOM.P
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import razie.diesel.model.{DieselMsgString, DieselTarget}
import razie.wiki.Services
import razie.wiki.model._
import scala.collection.mutable.ListBuffer

// the context persistence commands
class EEWiki extends EExecutor("diesel.wiki") {

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "rk.wiki" || m.entity == "wiki" || m.entity == "diesel.wiki"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val res = in.met match {
      case "content" => {
        clog << "diesel.wiki.content wpath=" + ctx.get("wpath").mkString

        val errors = new ListBuffer[Any]()
        // todo auth
        ctx
          .get("wpath")
          .flatMap(WID.fromPath(_, ctx.root.settings.realm.mkString))
          .orElse{errors.append(EError("no wid")); None}
          .map(_.r(ctx.root.settings.realm.mkString))
          .map{wid=> errors.append(EInfo("final wid: "+wid)); wid}
          .flatMap(Wikis.find)
          .orElse{errors.append(EError("ERR - No wiki found for "+ctx.get("wpath"))); None}
          .toList
          .map{w=>
              // typed value?
              val p =
                if(in.attrs.exists(_.name == "type"))
                  P.fromTypedValue("", w.content, ctx.getRequired("type"))
                else P("", w.content)

            in.attrs.find(_.name == "result").map(_.calculatedValue).map { output=>
              new EVal(p.copy(name = output))
            } getOrElse new EVal(p.copy(name="payload"))
          } ::: errors.toList
      }
      case "follow" => {
        //todo auth
        clog << "diesel.wiki.follow"
        val res = Wikil.wikiFollow(
          ctx("userName"),
          ctx("wpath"),
          ctx("how")
        )
        List(new EVal("payload", res))
      }
      case _ => {
        Nil
      }
    }
    res
  }

  // listen to topic changes and send event
  WikiObservers mini {
    case ev@WikiEvent(action, "WikiEntry", _, entity, oldEntity, _, _) => {
      val wid = WID.fromPath(ev.id).get
      val oldWid = ev.oldId.flatMap(WID.fromPath)

      if (entity.isDefined && entity.get.isInstanceOf[WikiEntry]) {
        val we = entity.get.asInstanceOf[WikiEntry]
        Services ! DieselMsgString(
          s"""$$msg diesel.wiki.updated (wpath="${we.wid.wpath}", realm="${we.realm}", event="${ev.action}")""",
          DieselTarget.ENV(we.realm)
        )
      }
    }
  }

  override def toString = "$executor::rk.wiki "

  override val messages: List[EMsg] =
    EMsg("wiki", "content") ::
    EMsg("wiki", "follow") ::
    EMsg("wiki", "updated") :: Nil
}
