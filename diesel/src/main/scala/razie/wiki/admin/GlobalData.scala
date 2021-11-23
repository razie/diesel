/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.admin

import java.util.concurrent.atomic.AtomicLong
import org.joda.time.DateTime
import razie.db.RazMongo
import razie.diesel.DieselRateLimiter
import razie.diesel.engine.DieselAppContext
import razie.diesel.utils.DomCollector
import razie.hosting.WikiReactors
import razie.wiki.Config
import razie.wiki.model.WikiCache
import scala.concurrent.{Future, Promise}

/** current ops data is updated here from all over - you can inspect this in a page
  *
  * not nice, poor man's JMX
  */
object GlobalData {
  //todo use proper JMX istead

  // how many requests were served
  val served = new AtomicLong(0)
  val servedRequests = new AtomicLong(0)
  val maxServing = new AtomicLong(0) // how many threads are currently serving - if 0, there's none...
  val maxServingApiRequests = new AtomicLong(0) // how many threads are currently serving - if 0, there's none...
  val serving = new AtomicLong(0) // how many threads are currently serving - if 0, there's none...
  val servingApiRequests = new AtomicLong(0) // how many threads are currently serving - if 0, there's none...
  val servedApiRequests = new AtomicLong(0)
  val limitedApiRequests = new AtomicLong(0) // how many were kicked off under load

  val wikiCacheMisses = new AtomicLong(0) // wiki cache misses
  val wikiCacheSets = new AtomicLong(0) // wiki cache misses

  val dieselEnginesTotal = new AtomicLong(0) // how many engines created since start
  val dieselEnginesActive = new AtomicLong(0) // how many engines active now
  val dieselStreamsTotal = new AtomicLong(0) // how many streams created since start
  val dieselStreamsActive = new AtomicLong(0) // how many streams active now

  val dieselCronsActive = new AtomicLong(0) // how many crons active
  val dieselCronsTotal = new AtomicLong(0)   // how many crons triggered since start

  /** how many wiki options have been requested - simple stats */
  var wikiOptions = 0L

  val startedDtm = DateTime.now

  var clusterStatus: String = "-"

  var reactorsLoaded = false
  val reactorsLoadedP: Promise[Boolean] = Promise[Boolean]()

  /** wait here if you need reactors */
  def reactorsLoadedF: Future[Boolean] = reactorsLoadedP.future

  def toMap() = {
    Map(
      "global" -> Map(
        "maxDefaultThreads" -> Config.prop("akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size"),
        "maxDieselThreads" -> Config.prop("diesel-dispatcher.thread-pool-executor.fixed-pool-size"),
        "serving" -> GlobalData.serving.get(),
        "served" -> GlobalData.served.get(),
        "wikiCacheMisses" -> GlobalData.wikiCacheMisses.get(),
        "wikiCacheSets" -> GlobalData.wikiCacheSets.get(),
        "servingApiRequests" -> GlobalData.servingApiRequests.get(),
        "servedApiRequests" -> GlobalData.servedApiRequests.get(),
        "limitedApiRequests" -> GlobalData.limitedApiRequests.get(),
        "maxServing" -> GlobalData.maxServing.get(),
        "maxServingApiRequests" -> GlobalData.maxServingApiRequests.get(),
        "dieselEnginesTotal" -> GlobalData.dieselEnginesTotal.get(),
        "dieselEnginesActive" -> GlobalData.dieselEnginesActive.get(),
        "servedPages" -> GlobalData.servedRequests.get(),
        "startedDtm" -> GlobalData.startedDtm,
        "clusterStatus" -> GlobalData.clusterStatus,
        "sendEmailCurCount" -> SendEmail.curCount,
        "sendEmailState" -> SendEmail.state
    ),
    "diesel" -> Map(
      "allReactors" -> WikiReactors.allReactors.keys.mkString(","),
      "loadedReactors" -> WikiReactors.reactors.keys.mkString(","),
      "wikiCount" -> RazMongo("WikiEntry").size,
      "wikiOptions" -> GlobalData.wikiOptions,

      "collectedAst" -> DomCollector.withAsts(_.size),
      "activeEngines" -> DieselAppContext.activeEngines.size,
      "activeActors" -> DieselAppContext.activeActors.size,
      "activeStreams" -> DieselAppContext.activeStreams.size
      )
    ) ++ DieselRateLimiter.toj
  }

  def perfMap() = {
    Map(
      "maxConfThreads" -> Config.prop("akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size"),
      "serving" -> GlobalData.serving.get(),
      "servingApiRequests" -> GlobalData.servingApiRequests.get(),
      "limitedApiRequests" -> GlobalData.limitedApiRequests.get(),
      "maxServing" -> GlobalData.maxServing.get(),
      "maxServingApiRequests" -> GlobalData.maxServingApiRequests.get(),
      "dieselEnginesTotal" -> GlobalData.dieselEnginesTotal.get(),
      "dieselEnginesActive" -> GlobalData.dieselEnginesActive.get(),
      "activeEngines" -> DieselAppContext.activeEngines.size,
      "activeActors" -> DieselAppContext.activeActors.size,
      "activeStreams" -> DieselAppContext.activeStreams.size
    )
  }
}

