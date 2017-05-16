package mod.diesel.controllers

import razie.diesel.dom.RDomain
import razie.wiki.model._

import scala.collection.mutable

/**
  * cache of domain and parsed specs and expiry
  *
  * it is important to cache specs, both draft and final, to ensure low latency
  * to users, for content assist and such
  */
object SpecCache {

  // stupid LRU expiry
  val cachel = new mutable.HashMap[String, Int]()
  val cachem = new mutable.HashMap[String,(WikiEntry,Option[RDomain])]()

  def orcached (we:WikiEntry, d: =>Option[RDomain]) : Option[RDomain] = {
    val res = cachem.get(we.content).flatMap(_._2).orElse {
      val x = d
      cachem.put(we.content, (we, d))
      cachel.put(we.content, 0)
      if(cachel.size > 100) {
        var min = 0
        var minc = ""
        cachel.foreach(x=> if(x._2 < min) {
          min = x._2
          minc = x._1
        })
        cachel.remove(minc)
        cachem.remove(minc)
      }
      d
    }
    cachel.update(we.content, cachel(we.content) + 1)
    res
  }
}


