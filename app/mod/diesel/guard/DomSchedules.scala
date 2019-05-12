package mod.diesel.guard

import akka.actor.Cancellable
import java.util.concurrent.TimeUnit
import play.libs.Akka
import razie.Logging
import razie.diesel.model.{DieselMsg, DieselTarget}
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}

/** generic scheduler, using akka schedules
  *
  * a schedule has a handle and an update/create will reset/replace that handle.
  *
  * this is so that running the same logic all the time won't create million schedules
  * */
object DomSchedules extends Logging {

  val realmSchedules = new HashMap[String, DomSchedule]

  /** create a schedule for a realm - should be called once per realm */
  def createSchedule(schedId: String, schedExpr: String, realm: String, env: String, msg: DieselMsg) = {
    // remove existing
    val remove = realmSchedules.remove(schedId)

    remove.toList.map {
      _.ref.foreach(_.cancel())
    }

    val sc = DomSchedule(schedId, schedExpr, msg, realm, env)

    // free realms at least 5min
    var d = Duration.apply(schedExpr).asInstanceOf[FiniteDuration]
    val d5 = Duration.create(5, TimeUnit.MINUTES)
    val myRealms = "wiki,specs,oss,herc-cc" // todo better for me and paid realms
    if (d < d5 && !myRealms.contains(realm))
      d = d5

    // start in a random time from now, but not too far in the future
    val akkaRef = Akka.system.scheduler.schedule(
      Duration.create(30 + (Math.random() * 30).toInt, TimeUnit.SECONDS),
      d,
      DomGuardian.worker,
      sc
    )

    realmSchedules.put(schedId, sc.copy(ref = Some(akkaRef)))
    s"Schedule $schedId for $realm-$env at $schedExpr"
  }

  /** create a schedule for a realm - should be called once per realm */
  def createSchedule(schedId: String, schedExpr: String, realm: String, env: String, msg: String) = ???

}

// realm, env, CMD, schedStr, cancellable
case class DomSchedule(schedId: String, schedExpr: String, msg:DieselMsg, realm: String, env: String, ref: Option[Cancellable] = None)

