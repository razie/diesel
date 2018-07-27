/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model.exec

import controllers.Wikil
import razie.clog
import razie.diesel.dom._
import razie.diesel.engine.DomEngECtx
import razie.diesel.ext._
import razie.diesel.model.DieselMsgString
import razie.wiki.Services
import razie.wiki.model._
import razie.diesel.ext.EVal

import scala.collection.mutable.ListBuffer

// the context persistence commands
class EEWiki extends EExecutor("rk.wiki") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "rk.wiki" || m.entity == "wiki" || m.entity == "diesel.wiki"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    val res = in.met match {
      case "content" => {
        clog << "DIESEL.wiki.content"

        val errors = new ListBuffer[Any]()
        // todo auth
        ctx
          .get("wpath")
          .flatMap(WID.fromPath(_, ctx.root.settings.realm.mkString))
          .orElse{errors.append(EError("no wid")); None}
          .map(_.r(ctx.root.settings.realm.mkString))
          .map{wid=> errors.append(EInfo("final wid: "+wid)); wid}
          .flatMap(Wikis.find)
          .orElse{errors.append(EError("no wiki")); None}
          .toList
          .map{w=>
            in.attrs.find(_.name == "result").map(_.calculatedValue).map { output=>
              new EVal(output, w.content)
            } getOrElse new EVal("payload", w.content)
          } ::: errors.toList
      }
      case "follow" => {
        //todo auth
        clog << "DIESEL.wiki.follow"
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
        Services ! DieselMsgString(s"""$$msg rk.wiki.updated (wpath="${we.wid.wpath}", realm="${we.realm}", event="${ev.action}")""")
      }
    }
  }

  override def toString = "$executor::rk.wiki "

  override val messages: List[EMsg] =
    EMsg("wiki", "content") ::
    EMsg("wiki", "follow") ::
    EMsg("wiki", "updated") :: Nil
}
