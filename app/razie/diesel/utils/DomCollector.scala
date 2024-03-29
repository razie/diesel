/**
  *   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.utils

import java.util.regex.Pattern
import org.joda.time.DateTime
import razie.diesel.engine.{DieselSLASettings, DomEngine, DomState}
import razie.diesel.model.DieselMsg
import scala.collection.mutable.{HashMap, ListBuffer}

// todo make instance in Reactor instead of a static
/** collects the last engine traces */
object DomCollector {

  case class ConfigEntry (descriptionMatch:Pattern, count:Int, group:String)

  final val MAX_SIZE = 200 // how many to keep overall
  final val MAX_SIZE_LOWER = 50 // how many to keep, for lower priority traces

  /** following a pattern momentarily overwrites the keep configuration */
  var following : Array[String] = new Array[String](0)

  /** following a pattern momentarily overwrites the keep configuration */
  var configPerRealm = new HashMap[String, ListBuffer[ConfigEntry]] ()

  /** a single collected trace */
  case class CollectedAst(stream: String, realm: String, id: String, userId: Option[String], engine: DomEngine,
                          details: String, dtm: DateTime = DateTime.now) {

    val (collectCount, collectGroup) = calculate

    def isLowerPriority = {
      val desc = engine.description // not collectGroup
      !following.exists(desc.contains) &&
          (
              desc.contains(DieselMsg.fiddleStoryUpdated) ||
                  desc.contains(DieselMsg.REALM.REALM_LOADED_MSG) ||
                  desc.endsWith(DieselMsg.ENGINE.DIESEL_PING) ||
                  desc.endsWith("diesel/ping") ||
                  desc.contains("$msg " + DieselMsg.GPOLL)
              )
    }

    /** how many of these to keep in trace collector? */
    private def calculate = {
      var group = engine.collectGroup

      val desc = engine.collectGroup
          .filter(x=> following.exists(x=> x.length > 3 && engine.description.contains(x)))

      val count = Option(100)
          .filter(x=> following.exists(x=> x.length > 3 && engine.description.contains(x)))
          .getOrElse (
            engine.settings.collectCount.filter(_ != 0).getOrElse {
              val res = if (
                desc.endsWith ("diesel/pingx") || desc.endsWith (DieselMsg.ENGINE.DIESEL_PING)
              ) 3 else if (
                desc.contains (DieselMsg.fiddleStoryUpdated) || desc.contains (DieselMsg.GPOLL)
              ) 6 else {
                if (engine.settings.slaSet.contains (DieselSLASettings.NOKEEP)) -1 else {
                  configPerRealm.get(engine.settings.realm.mkString)
                      .flatMap (_.find (_.descriptionMatch.matcher(engine.description).matches()))
                      .map {ce =>
                        group = ce.group
                        ce.count
                      }.getOrElse {
                    5
                  }
                }
                // 0  means no self-imposed limit, default
              }
              res
            })

      (count, group)
    }

    /** how many of these to keep in trace collector? */
    def getMaxCount = collectCount
  }

  /* statically collecting the last MAX_SIZE results sets **/
  private var asts: List[CollectedAst] = Nil

  /** encapsulate access to sync and collect resources */
  def withAsts[T](f: List[CollectedAst] => T): T = synchronized {
    f(asts)
  }

  /** find collected engine */
  def findAst(id:String): Option[CollectedAst] = {
    var engine : Option[CollectedAst] = None
    DomCollector.withAsts(_.find(_.id == id).map { eng =>
      engine = Option(eng)
    })
    engine
  }


  /** statically collect more asts
    *
    * @param stream
    * @param realm
    * @param xid
    * @param userId
    * @param eng
    * @param details
    */
  def collectAst(
    stream: String,
    realm: String,
    eng: DomEngine,
    details: String = ""): Unit = synchronized {
    val userId = eng.settings.userId
    val xid = eng.id

    val newOne = CollectedAst(stream, realm, xid, userId, eng, details)
    val collectGroup = newOne.collectGroup
    val count = newOne.getMaxCount

    if (count > 0) { // collect it

      var newAsts = newOne :: asts.filter(_.id != xid) // make sure no duplos

      // does it have collect settings? collect settings are per description
      if (count > 0) {
        //        var lesser = newAsts.filter(_.engine.description == eng.description)
        var lesser = newAsts.filter(_.collectGroup == collectGroup)

        // if it does and collected more in the same description, remove some
        var stupidLimit = MAX_SIZE // bug 636 - had a logic issue with 2.1.5.t6, too risky to rethink now, so put a
        // stupid limit in place
        while (lesser.size > count && stupidLimit > 0) {
          stupidLimit = stupidLimit - 1
          // remove one of this kind, done
          lesser.reverse.find(e => DomState.isDone(e.engine.status)).map(_.id).foreach { lastId =>
            newAsts = newAsts.filter(_.id != lastId).take(MAX_SIZE - 1)
            lesser = newAsts.filter(_.collectGroup == collectGroup)
          }

          // bug 636 - it was spinning if too many flows in progress with same description
          if (lesser.size > count) {
            // remove some of the in progress flows too
            lesser.lastOption.map(_.id).foreach { lastId =>
              newAsts = newAsts.filter(_.id != lastId).take(MAX_SIZE - 1)
              lesser = newAsts.filter(_.collectGroup == collectGroup)
            }
          }
        }
      }

      // if too many lower prio now, remove one
      val lower = asts.filter(_.isLowerPriority)

      if (lower.size > MAX_SIZE_LOWER) {
        lower.reverse.find(e=>DomState.isDone(e.engine.status)).map(_.id).foreach { lastId =>
          // try to remove first a lower priority one
          asts = newAsts.filter(_.id != lastId).take(MAX_SIZE - 1)
        }
      } else {
        asts = newAsts.take(MAX_SIZE - 1)
      }
    }
  }

  /** statically collect more asts */
  def cleanAst = synchronized {
    asts = asts.filter(x=> ! DomState.isDone(x.engine.status))
  }

}

