/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.admin

import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongUnaryOperator
import org.joda.time.DateTime
import razie.db.RazMongo
import razie.diesel.DieselRateLimiter
import razie.diesel.engine.DieselAppContext
import razie.diesel.utils.DomCollector
import razie.hosting.WikiReactors
import razie.wiki.Config
import razie.wiki.model.WikiCache
import scala.concurrent.{Future, Promise}

/** simple stats encapsulated */
class StatsAtomicLong (
  val curr : AtomicLong = new AtomicLong(0),
  val imax  : AtomicLong = new AtomicLong(0) ) {

  def get() = curr.get()

  def max() = imax.get()

  def incrementAndGet() = {
    val c = curr.incrementAndGet()

    imax.getAndUpdate{
      new LongUnaryOperator {
        override def applyAsLong(prev: Long): Long = if(c > prev) c else prev
      }
    }
  }

  def decrementAndGet() = {
    curr.decrementAndGet()
  }

  override def toString = curr.toString
}

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
  val limitedRequests = new AtomicLong(0) // how many were kicked off under load

  val wikiCacheMisses = new AtomicLong(0) // wiki cache misses
  val wikiCacheHits = new AtomicLong(0) // wiki cache hits
  val wikiCacheSets = new AtomicLong(0) // wiki cache misses

  val dieselEnginesTotal = new AtomicLong(0) // how many engines created since start
  val dieselEnginesActive = new StatsAtomicLong() // how many engines active now
  val dieselStreamsTotal = new AtomicLong(0) // how many streams created since start
  val dieselStreamsActive = new StatsAtomicLong() // how many streams active now

  val dieselCronsActive = new StatsAtomicLong() // how many crons active
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
        "wikiCacheHits" -> GlobalData.wikiCacheHits.get(),
        "servingApiRequests" -> GlobalData.servingApiRequests.get(),
        "servedApiRequests" -> GlobalData.servedApiRequests.get(),
        "limitedRequests" -> GlobalData.limitedRequests.get(),
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
      "activeStreams" -> DieselAppContext.activeStreams.size,
      "activeCrons" -> GlobalData.dieselCronsActive.get(),
      "maxActiveEngines" -> GlobalData.dieselEnginesActive.max(),
      "maxActiveStreams" -> GlobalData.dieselStreamsActive.max(),
      "maxActiveCrons" -> GlobalData.dieselCronsActive.max()
    )
    ) ++ DieselRateLimiter.toj
  }

  def perfMap() = {
    Map(
      "maxConfThreads" -> Config.prop("akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size"),
      "serving" -> GlobalData.serving.get(),
      "servingApiRequests" -> GlobalData.servingApiRequests.get(),
      "limitedRequests" -> GlobalData.limitedRequests.get(),
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

