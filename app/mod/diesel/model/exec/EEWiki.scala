/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model.exec

import controllers.Wikil
import mod.diesel.controllers.SFiddles
import mod.diesel.model.RDExt.{spec}
import mod.diesel.model.{DEStartTimer, DieselAppContext}
import model.Users
import razie.clog
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.ext._
import razie.wiki.model._
import mod.diesel.controllers.DomGuardian
import razie.diesel.model.DieselMsgString
import razie.wiki.Services

import scala.collection.mutable

// the context persistence commands
class EEWiki extends EExecutor("rk.wiki") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "rk.wiki" || m.entity == "wiki"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    in.met match {
      case "follow" => {
        //todo auth
        clog << "DIESEL.wiki.follow"
        val res = Wikil.wikiFollow(
          ctx("userName"),
          ctx("wpath"),
          ctx("how")
        )
        List(new EVal("result", res))
      }
      case _ => {
        Nil
      }
    }
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
    EMsg("", "wiki", "follow", Nil) ::
    EMsg("", "wiki", "updated", Nil) :: Nil
}
