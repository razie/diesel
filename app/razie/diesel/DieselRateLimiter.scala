/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <    /README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/   /LICENSE.txt
  * */
package razie.diesel

import java.util.concurrent.atomic.AtomicLong
import play.api.mvc.RequestHeader
import razie.diesel.DieselRateLimiter.globalGroup
import razie.wiki.{Config, Services}
import razie.wiki.admin.GlobalData
import razie.wiki.model.{WikiConfigChanged, WikiObservers}
import scala.collection.concurrent.TrieMap

/** a rate limit group */
case class RateLimitGroup(
  name: String,
  limit: Int,
  var regex: List[String],
  var headers: List[(String, String)],
  maxServedCount: AtomicLong = new AtomicLong(0),
  servingCount: AtomicLong = new AtomicLong(0),
  servedCount: AtomicLong = new AtomicLong(0),
  limitedCount: AtomicLong = new AtomicLong(0)
) {
  def decServing() = {
    servingCount.decrementAndGet()
  }
}

/** rate limiting stats and config */
object DieselRateLimiter extends razie.Logging {
  // rate limit groups
  val rateLimits = new TrieMap[String, RateLimitGroup]()

  // NOTE the drawback to configuring the global limit is dropping requests without the benefit of waiting on socket

  // rate limit ALL requests
  var LIMIT_ALL = Services.config.prop("diesel.staticRateLimitAll", "false").toBoolean
  // rate limit API requests
  var LIMIT_API = Services.config.prop("diesel.staticRateLimit", "80").toInt
  // big switch on/off
  var RATELIMIT = Services.config.prop("diesel.staticRateLimiting", "true").toBoolean

  // create a global group
  var globalGroup = if(LIMIT_ALL) Some(new RateLimitGroup(name="global", limit=LIMIT_API, regex=Nil, headers=Nil)) else None
  globalGroup.foreach(rateLimits.put("global", _))

  WikiObservers mini {
    case WikiConfigChanged (node, config) if config != null => {
      val threads = config.prop("akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size", "25").toInt
      LIMIT_ALL = config.prop("diesel.staticRateLimitAll", "false").toBoolean
      LIMIT_API = config.prop("diesel.staticRateLimit", "80").toInt
      RATELIMIT = config.prop("diesel.staticRateLimiting", "true").toBoolean

      // todo erases stats as it replaces old object
      globalGroup = if(LIMIT_ALL) Some(new RateLimitGroup(name="global", limit=LIMIT_API, regex=Nil, headers=Nil)) else None
      globalGroup.foreach(rateLimits.put("global", _))

      log(s"Updated RATE LIMITS to: $LIMIT_API , $RATELIMIT , $threads")
    }
  }

  /** API requests may be rate limited separately */
  def isApiRequest(path: String) = {
    // how do we speed this up?
//    path.contains("/elkpt/") ||
    path.startsWith("/diesel/rest/") ||
        path.startsWith("/diesel/mock/") ||
        path.startsWith("/razadmin/") ||
        path.startsWith("/diesel/status")
//        path.startsWith("/diesel/react/") ||
//        path.startsWith("/diesel/start/") ||
//        path.startsWith("/diesel/fiddle/react/") ||
//        path.startsWith("/diesel/wreact/")
  }

  /** sol(rq, serve, limited) main call to serve or limit
    *
    * @param rh          request
    * @param preliminary true if I should not incremeent served stats
    * @param serve       serve request callback
    * @param limited     limited request callback
    * @tparam T
    * @return
    */
  def serveOrLimit[T](rh: RequestHeader, preliminary: Boolean = false)(serve: (RequestHeader, Option[RateLimitGroup])
      => T)(limited: (String,
      Long) => T): T = {

    val apiRequest = isApiRequest(rh.path)

    def serveIt(rateLimitGroup: Option[RateLimitGroup]) = {
      // only update stats if not preliminary
      if (!preliminary) {
        GlobalData.serving.incrementAndGet()

        if (apiRequest) {
          GlobalData.servingApiRequests.incrementAndGet()
        }
      }

      serve(rh, rateLimitGroup)
    }

    // find group and rate limit
    getGroup(rh).map { group =>
      val count = group.servingCount.incrementAndGet()

      // note that this is called twice per request
      // when preliminary, the servingCount is decremented at call site...

      if (RATELIMIT && count > group.limit) {
        GlobalData.limitedRequests.incrementAndGet()
        group.decServing()
        group.limitedCount.incrementAndGet()

        limited.apply(group.name, count)
      } else {
        serveIt(Some(group))
      }
    }.getOrElse {
      // never gets here when global limiting is on
      serveIt(None)
    }
  }

  def getGroup(rh: RequestHeader): Option[RateLimitGroup] = {
    rateLimits.find(t => t._2.regex.exists(rh.path.matches)).map(_._2).orElse(globalGroup)
//    if (isApiRequest(rh.path)) Some("api")
//    else None
  }

  def toj = {
    Map(
      "rateLimits" ->
          rateLimits.map(t =>
            t._1 -> Map(
              "maxServed" -> t._2.maxServedCount.get(),
              "serving" -> t._2.servingCount.get(),
              "limit" -> t._2.limit,
              "served" -> t._2.servedCount.get(),
              "limited" -> t._2.limitedCount.get()
            )).toList
              .toMap
    )
  }
}
