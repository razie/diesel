/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.model

import com.mongodb.DBObject
import razie.wiki.Services
import razie.wiki.admin.GlobalData
import scala.concurrent.duration.DurationInt
import razie.{cdebug, clog, ctrace}

/** wrapper and utils for caching wikis
  *
  * Using the underlying Play cache API
  *
  * Wikis are cached inside the WID as well as this singleton cache
  */
object WikiCache {

  private var CACHE_EXP:Option[Int] = None // 10 minutes?

  // lame lazy
  def cacheExp = CACHE_EXP.getOrElse {
    CACHE_EXP = Some(Services.config.prop("wiki.cache.expiry", "500").toInt)
    CACHE_EXP.get
  }

  def set[T](id:String, w:T, i:Int = cacheExp) = {
    clog << "WIKI_CACHE_SET   - "+id
    Services.cache.set(id, w, i.seconds)
    GlobalData.wikiCacheSets.incrementAndGet()
  }

  /** get by key which is val id = wid.wpathFullNoSection + ".page"
    *
    * don't use this - use Wiki.getCached instead, this doesn't populate the cache
    */
  def getEntry(id:String) : Option[WikiEntry] = {
    Services.cache.get[WikiEntry](id).map{x=>
      ctrace << "WIKI_CACHE_FOUND FULL - "+id
      GlobalData.wikiCacheHits.incrementAndGet()
      x
    }.orElse {
      clog << "WIKI_CACHE_MISS  FULL - "+id
      GlobalData.wikiCacheMisses.incrementAndGet()
      None
    }
  }

  def getDb(id:String) : Option[DBObject] = {
    Services.cache.get[DBObject](id).map{x=>
      ctrace << "WIKI_CACHE_FOUND DB   - "+id
      x
    }.orElse {
      clog << "WIKI_CACHE_MISS  DB   - "+id
      None
    }
  }

  def getString(id:String) : Option[String] = {
    Services.cache.get[String](id).map{x=>
      ctrace << "WIKI_CACHE_FOUND FRM  - "+id
      x
    }.orElse {
      clog << "WIKI_CACHE_MISS  FRM  - "+id
      None
    }
  }

  def remove(id:String) = {
    clog << "WIKI_CACHE_CLEAR - "+id
    Services.cache.remove(id)
  }
}
