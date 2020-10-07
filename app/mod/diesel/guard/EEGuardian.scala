/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.guard

import mod.diesel.guard.DomGuardian.GUARDIAN_POLL
import model.Users
import razie.Logging
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.utils.DieselData
import razie.hosting.Website

/** guardian actions */
class EEGuardian extends EExecutor(DieselMsg.GUARDIAN.ENTITY) with Logging {

  final val DG = DieselMsg.GUARDIAN.ENTITY

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity.startsWith(DG)
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    in.met match {

      case "schedule" => {
        var res : List[Any] = Nil

        // allow different realm only if trusted
        var realm = ctx
            .get("inRealm")
            .filter { x =>
              val ok = Website.forRealm(ctx.root.settings.realm.get)
                  .exists(_.dieselTrust.split(",").contains(x))
              if(!ok)
                res = EError(s"Can't trust realm $x") :: Nil
              ok
            }
            .getOrElse(ctx.root.settings.realm.get)

        if(! DomGuardian.ISAUTO) {
          res = EError(s"Guardian not on auto") :: Nil
        } else {
          val result = DomGuardian.createPollSchedule(
            ctx.getRequired("schedule"),
            realm,
            ctx.getRequired("env"),
            ctx.get("inLocal").mkString
          )

          res = res ::: EVal(P.fromTypedValue("payload", result)) :: Nil
        }

        res
      }

      case DieselMsg.GUARDIAN.STARTS => { // avoid error if not ruled
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
              .getOrElse("Fail")

          info(s"Guardian - ends $realm-$env - newStatus $newStatus vs oldStatus $oldStatus")

          // lazy to capture the newStatus
          def m = EMsg(
            DieselMsg.GUARDIAN.ENTITY,
            DieselMsg.GUARDIAN.NOTIFY,
            List(
              P("realm", ctx("realm")),
              P("env", ctx("env")),
              P("oldStatus", oldStatus),
              P("newStatus", newStatus),
              P("errors", engine.failedTestCount.toString),
              P("total", engine.totalTestCount.toString),
              P("report", s"""See details: <a href=\"$url/diesel/viewAst/${engine.id}\">${engine.id}</a></td>""")
            ))

          val res = if (engine.failedTestCount > 0) {
            newStatus = "Fail"
            EVal(P("guardianResult", s"Guardian - failed: ${engine.progress}")) ::
                m :: Nil
          } else {
            newStatus = "Success"
            // only send notification if it failed last time
            // or send them every time it detects a change, even if successful, to have a record of it having run?
            val mList = if(true || oldStatus == "Fail") m :: Nil else Nil
            EVal(P("guardianResult", s"Guardian - success: ${engine.progress}")) ::
                mList
          }

          // save new status
          DieselData.update(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("status" -> newStatus))

          res
        }
      }

      case "polled" => {
        //it was polled and here's the new stamp - shall we start a new check?
        val settings = ctx.root.settings
        val env = ctx.getRequired("env")
        val stamp = ctx.getRequired("stamp")
        val tq = ctx.get("tagQuery").mkString
        val inRealm = ctx.get("inRealm").getOrElse(settings.realm.get)
        var res : List[Any] = Nil

        // allow different realm only if trusted
        var r = ctx
            .get("inRealm")
            .filter { x =>
              val ok = Website.forRealm(ctx.root.settings.realm.get)
                  .exists(_.dieselTrust.split(",").contains(x))
              if (!ok)
                res = EError(s"Can't trust realm $x") :: Nil
              ok
            }
            .getOrElse(settings.realm.get)

        EVal(P("payload", res)) :: Nil
      }

      case "run" => {
        val env = ctx.getRequired("env")
        val realm = ctx.getRequired("realm") // altho you can only run your for now
        val settings = ctx.root.settings

        // admins get to run anywhere they want
        val inrealm = if (
          settings.userId
              .flatMap(Users.findUserById)
              .exists(_.isAdmin))
          realm
        else settings.realm.mkString

        val x @ (f, e) = DomGuardian.runReq(settings.userId.flatMap(Users.findUserById), inrealm, env, true)
        EVal(P("payload", s"""scheduled new run... <a href="/diesel/viewAst/${e.id}">view</a>""", WTypes.wt.HTML)) :: Nil
      }

      case "stats" => {
        EVal(P("payload", DomGuardian.stats)) :: Nil
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
        EMsg(DG, DieselMsg.GUARDIAN.STARTS) ::
        EMsg(DG, DieselMsg.GUARDIAN.NOTIFY) ::
        EMsg(DG, "clear") :: Nil
}

