/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.guard

import mod.diesel.guard.DomGuardian.GUARDIAN_POLL
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

  final val DG = DieselMsg.GUARDIAN.ENTITY

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity.startsWith(DG)
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    in.met match {

      case "schedule" => {
        val res = DomGuardian.createPollSchedule(
          ctx.get("schedule").get,
          ctx.root.settings.realm.get,
          ctx.get("env").get
        )
        EVal(P.fromTypedValue("payload", res)) :: Nil
      }

      case "starts" => { // avoid error if not ruled
        Nil
      }

      case "poll" => { // avoid error if not ruled
        Nil
      }

      case "ends" => {
        // guardian ends a run - record and notify
        def url = Website.forRealm(ctx.root.settings.realm.mkString).map(_.url).mkString

        ctx.root.engine.toList.flatMap { engine =>
          val realm = ctx("realm")
          val env = ctx("env")
          var newStatus = ""

          var oldStatus = DieselData
              .find(GUARDIAN_POLL, realm, realm + "-" + env)
              .flatMap(t=> t.contents.get("status"))
              .getOrElse("Success")

          info(s"Guardian - ends $realm-$env - newStatus $newStatus vs oldStatus $oldStatus")

          // save new status
          DieselData.update(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("status" -> newStatus))

          // lazy to capture the newStatus
          def m = ext.EMsg(
            DieselMsg.GUARDIAN.ENTITY,
            DieselMsg.GUARDIAN.NOTIFY,
            List(
              P("realm", ctx("realm")),
              P("env", ctx("env")),
              P("oldStatus", oldStatus),
              P("newStatus", newStatus),
              P("errors", engine.failedTestCount.toString),
              P("report", s"""See details: <a href=\"$url/diesel/viewAst/${engine.id}\">${engine.id}</a></td>""")
            ))

          val res = if (engine.failedTestCount > 0) {
            newStatus = "Fail"
            EInfo(s"Guardian - failed ${engine.id} count: ${engine.failedTestCount}") ::
                m :: Nil
          } else {
            newStatus = "Success"
            // only send notification if it failed last time
            // or send them every time it detects a change, even if successful, to have a record of it having run?
            val mList = if(true || oldStatus == "Fail") m :: Nil else Nil
            EInfo(s"Guardian - success ${engine.id} count: ${engine.failedTestCount}") ::
                mList
          }

          // save new status
          DieselData.update(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("status" -> newStatus))

          res
        }
      }

      case "polled" => {
        //it was polled and here's the new stamp - shall we start a new check?
        val stamp = ctx.get("stamp").get
        val env = ctx.get("env").get
        val tq = ctx.get("tagQuery").mkString
        val settings = ctx.root.settings
        val res = DomGuardian.polled(settings.realm.get, env, stamp, settings.userId.flatMap(Users.findUserById), tq)

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

      case DieselMsg.GUARDIAN.NOTIFY => {
        // default sink for notify, if user didn't confg something
        Nil
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
        EMsg(DG, DieselMsg.GUARDIAN.NOTIFY) ::
        EMsg(DG, "clear") :: Nil
}

