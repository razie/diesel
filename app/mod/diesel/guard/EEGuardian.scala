/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.guard

import model.Users
import razie.Logging
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.ext
import razie.diesel.ext.{MatchCollector, _}
import razie.diesel.model.DieselMsg
import razie.hosting.Website

/** guardian actions */
class EEGuardian extends EExecutor("diesel.guardian") with Logging {

  final val DG = "diesel.guardian"

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity startsWith DG
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    in.met match {

      case "schedule" => {
        val res = DomGuardian.createPollSchedule(
          ctx.get("schedule").get,
          ctx.root.settings.realm.get,
          ctx.get("env").get
        )
        EVal(P("payload", res)) :: Nil
      }

      case "starts" => { // avoid error if not ruled
        Nil
      }

      case "poll" => { // avoid error if not ruled
        Nil
      }

      case "ends" => { // avoid error if not ruled
        def url = Website.forRealm(ctx.root.settings.realm.mkString).map(_.url).mkString

        ctx.root.engine.toList.flatMap { engine =>
          if (engine.failedTestCount > 0) {
            val m = ext.EMsg(
              DieselMsg.GUARDIAN.ENTITY,
              DieselMsg.GUARDIAN.NOTIFY,
              List(
                P("realm", ctx("realm")),
                P("env", ctx("env")),
                P("errors", engine.failedTestCount.toString),
                P("report", s"""See details: <a href=\"$url/diesel/viewAst/${engine.id}\">${engine.id}</a></td>""")
              ))
            EInfo(s"Guardian - failed ${engine.id} count: ${engine.failedTestCount}") ::
                m :: Nil
          } else {
            EInfo(s"Guardian - success ${engine.id} count: ${engine.failedTestCount}") :: Nil
          }
        }
      }

      case "polled" => {
        val stamp = ctx.get("payload").get
        val env = ctx.get("env").get
        val settings = ctx.root.settings
        val res = DomGuardian.polled(settings.realm.get, env, stamp, settings.userId.flatMap(Users.findUserById))
        EVal(P("payload", res)) :: Nil
      }

      case "run" => {
        val realm = ctx.get("realm").get // altho you can only run your for now
        val env = ctx.get("env").get
        val settings = ctx.root.settings

        // admins get to run anywhere they want
        val inrealm = if (
          settings.userId
              .flatMap(Users.findUserById)
              .exists(_.isAdmin))
          realm
        else settings.realm.mkString

        DomGuardian.runReq(settings.userId.flatMap(Users.findUserById), inrealm, env, true)
        EVal(P("payload", "scheduled new run...")) :: Nil
      }

      case "stats" => {
        EVal(P("payload", DomGuardian.stats)) :: Nil
      }

      case "schedules" => {
        EVal(P(
          "payload",
          DomSchedules.realmSchedules.map(_._2.toString).mkString("\n")
        )) :: Nil
      }

      case "clear" => {
        ???
      }

      case s@_ => {
        new EError(s"$DG.$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::diesel.guardian "

  override val messages: List[EMsg] =
    EMsg(DG, "poll") ::
        EMsg(DG, "ends") ::
        EMsg(DG, "run") ::
        EMsg(DG, "report") ::
        EMsg(DG, "schedule") ::
        EMsg(DG, "stats") ::
        EMsg(DG, "starts") ::
        EMsg(DG, "clear") :: Nil
}

