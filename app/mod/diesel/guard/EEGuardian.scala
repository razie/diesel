/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.guard

import mod.diesel.guard.DieselDebug.Guardian
import mod.diesel.guard.DieselDebug.Guardian.{ISAUTO, ISSCHED}
import mod.diesel.guard.DomGuardian.GUARDIAN_POLL
import model.Users
import razie.Logging
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import razie.diesel.model.DieselMsg
import razie.diesel.utils.DieselData
import razie.hosting.Website
import scala.collection.mutable.ListBuffer

/** guardian actions */
class EEGuardian extends EExecutor(DieselMsg.GUARDIAN.ENTITY) with Logging {

  final val DG = DieselMsg.GUARDIAN.ENTITY

  def collect(x:Any) (implicit res:ListBuffer[Any]) : List[Any] = {
    res += x
    res.toList
  }

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity.startsWith(DG)
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = synchronized {
    implicit val res = new ListBuffer[Any]()

    in.met match {

      case "schedule" => {

        // allow different realm only if trusted, simple cloud protection
        var realm = ctx
            .get("inRealm")
            .filter { x =>
              val me = (ctx.root.settings.realm.get)
              val ok = Website.forRealm(ctx.root.settings.realm.get)
                  .exists(_.dieselTrust.split(",").contains(x))
              if (!ok)
                res += EError(s"Can't trust realm $x - I'm $me")
              ok
            }
            .getOrElse(ctx.root.settings.realm.get)

        if (!ISSCHED) {
          res += EError(s"Guardian not on auto/sched")
        } else {
          val result = DomGuardian.createPollSchedule(
            ctx.getRequired("schedule"),
            realm,
            ctx.getRequired("env"),
            ctx.get("inLocal").mkString
          )

          res += EVal(P.fromTypedValue("payload", result))
        }
      }

      case DieselMsg.GUARDIAN.STARTS => { // avoid error if not ruled
      }

      case "poll" => { // avoid error if not ruled
      }

      case "ends" => {
        // guardian ends a run - record and notify
        def url = Website.forRealm(ctx.root.settings.realm.mkString).map(_.url).mkString

        ctx.root.engine.foreach { engine =>
          val realm = ctx("realm")
          val env = ctx("env")
          var newStatus = ""

          val oldStatus = DieselData
              .find(GUARDIAN_POLL, realm, realm + "-" + env)
              .flatMap(t=> t.contents.get("status"))
              .getOrElse("Fail")

          res += EInfo(s"Guardian - ends $realm-$env - newStatus $newStatus vs oldStatus $oldStatus")

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

          if (engine.failedTestCount > 0) {
            newStatus = "Fail"

            res appendAll EVal(P("guardianResult", s"Guardian - failed: ${engine.progress}")) :: m :: Nil
          } else {
            newStatus = "Success"
            // only send notification if it failed last time
            // or send them every time it detects a change, even if successful, to have a record of it having run?
            val mList = if(true || oldStatus == "Fail") m :: Nil else Nil

            res appendAll EVal(P("guardianResult", s"Guardian - success: ${engine.progress}")) :: mList
          }

          // save new status
          DieselData.update(GUARDIAN_POLL, realm, realm + "-" + env, None, Map("status" -> newStatus))
        }
      }

      case "polled" => {
        //it was polled and here's the new stamp - shall we start a new check?
        val settings = ctx.root.settings
        val env = ctx.getRequired("env")
        val stamp = ctx.getRequired("stamp")
        val tq = ctx.get("tagQuery").getOrElse("story/sanity/-skip/-manual")
        val inRealm = ctx.get("inRealm").getOrElse(settings.realm.get)

        // allow different realm only if trusted, simple cloud protection
        var r = ctx
            .get("inRealm")
            .filter { x =>
              val me = (ctx.root.settings.realm.get)
              val ok = Website.forRealm(ctx.root.settings.realm.get)
                  .exists(_.dieselTrust.split(",").contains(x))
              if (!ok)
                res += EError(s"Can't trust realm $x - I'm $me")
              ok
            }
            .getOrElse(settings.realm.get)

        res appendAll DomGuardian.polled(inRealm, env, stamp, settings.userId.flatMap(Users.findUserById), tq)
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

        val tq = ctx.get("tagQuery").getOrElse(Guardian.autoQuery(inrealm))

        val x@(f, e, res1) = DomGuardian.runReq(settings.userId.flatMap(Users.findUserById), inrealm, env, tq, true, None)
        val linkMsg =
          e.map(eng=>
            s"""scheduled new run... <a href="/diesel/viewAst/${eng.id}">view</a>"""
          ).getOrElse(
            "could not schedule an engine? "
          )

        res appendAll res1
        res += EVal(
          P(
            Diesel.PAYLOAD,
            linkMsg + " realm:$inrealm , env=$env, tagQuery=$tq",
            WTypes.wt.HTML
          ))
      }

      case "stats" => {
        res += EVal(P("payload", DomGuardian.stats))
      }

      case "addTag" => {
        val r = ctx.getRequired("realm")
        val n = ctx.getRequired("name")

        removeTags(r,n).append((
            r,
            n,
            ctx.getRequired("tagQuery"),
            ""
        ))
        res += EVal(P("payload", "Ok, added"))
      }

      case "removeTag" => {
        val r = ctx.getRequired("realm")
        val n = ctx.getRequired("name")

        removeTags(r,n)
        res += EVal(P("payload", "Ok, removed"))
      }

      case "clear" => {
        ???
      }

      case DieselMsg.GUARDIAN.NOTIFY => {
        // default sink for notify, if user didn't confg something
      }

      case s@_ => {
        res += new EError(s"$DG.$s - unknown activity ")
      }
    }

   def removeTags(realm:String, name:String) = {
     DomGuardian.tagList
         .zipWithIndex
         .find(p => p._1._1 == realm && p._1._2 == name)
         .map(_._2)
         .foreach(DomGuardian.tagList.remove)
     DomGuardian.tagList
   }

    res.toList
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

