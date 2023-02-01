package services

import akka.actor.{Actor, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.google.inject.Singleton
import com.razie.pub.comms.CommRtException
import controllers.Emailer
import java.net.InetAddress
import model.EventNeedsQuota
import play.libs.Akka
import razie.audit.Audit
import razie.{Logging, Snakk, clog}
import razie.diesel.engine.exec.EEDbEvent
import razie.diesel.model.{DieselMsg, DieselMsgString, ScheduledDieselMsg, ScheduledDieselMsgString}
import razie.hosting.{Website, WikiReactors}
import razie.wiki.admin.SendEmail
import razie.wiki.model._
import razie.wiki.model.features.WikiCount
import razie.wiki.{Config, EventProcessor, Services}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, FiniteDuration}

object DieselCluster extends Logging {

 /** is this the master / singleton node in a cluster? */
  def isSingletonNode (w: Website) = isSingletonNodeApache(w)

  // todo optimize this - we need to avoid calling REST every time...
  var masterNodeStatus: Option[Boolean] = None

  /** cheap hot/cold singleton - is it me that Apache deems main? assumes proxy in +H mode */
  def isSingletonNodeApache (w: Website) = {
    // todo use akka singleton or something
    masterNodeStatus.getOrElse {
      val me = InetAddress.getLocalHost.getHostName
      val url =
        (if (Services.config.isLocalhost) "http://" + Services.config.hostport
        else
          w.url) + "/diesel/engine/whoami"

      var active = ""

      try {
        active = Snakk.body(Snakk.url(url))
      } catch {
        case ex: CommRtException if ex.httpCode == 302 => {
          // redirect
          log("================ REDIRECTING... " + ex.httpCode + ex.location302)
          active = Snakk.body(Snakk.url(ex.location302))
        }
        case ex: CommRtException => {
          log("================ SOME ERROR..." + ex.httpCode + ex.location302)
          throw ex
        }
      }

      val res = me equals active
      debug(s"isMasterNode: $res $me =? $active url = ${w.url}")
      masterNodeStatus = Some(me equals active)
      masterNodeStatus.get
    }
  }


}
