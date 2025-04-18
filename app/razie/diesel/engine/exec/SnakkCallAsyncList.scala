/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import org.bson.types.ObjectId
import razie.{Logging, SnakkResponse}
import scala.collection.mutable
import scala.concurrent.Future

/** picked up and executed elsewhere - works with the SnakkCallServer */
object SnakkCallAsyncList extends Logging {
  case class Rec (id:String, sc:SnakkCall, time:Long = System.currentTimeMillis())

  var calls = new mutable.ListBuffer[Rec]()
  val inProgress = new mutable.HashMap[String, Rec]()

  val EXPIRY = 20000 // clean in 12 sec

  private def clean : Unit = {
    val now = System.currentTimeMillis()
    calls = calls.filter(now - _.time < EXPIRY)
    inProgress.find(now - _._2.time > EXPIRY).toList.foreach(t=>inProgress.remove(t._1))
  }

  def put (sc:SnakkCall) : Future[SnakkResponse] = {
    val rec = Rec(new ObjectId().toString, sc)
    log("SNAKKPROXY ASCL put " + rec.id + " - " + sc.toString)
    calls.synchronized {
      clean
      calls.append(rec)
    }
    sc.promise.get.future
  }

  /** next req waiting for host */
  def next(env:String, host:String) : Option[(String, SnakkCall)] = {
    calls.synchronized {
      log("SNAKKPROXY ASCL get " + env + " - " + host)
      clean
      val idx = calls.indexWhere(x=>(host == "*" || x.sc.url.startsWith(host)) && (env == "*" || x.sc.env == env))
      if(calls.size > 0 && idx >= 0) {
        val rec = calls.remove(idx)
        log("SNAKKPROXY ASCL next " + rec.id + " - " + rec.sc.toString)
        inProgress.synchronized {
          inProgress.put(rec.id, rec)
        }
        Some((rec.id, rec.sc))
      } else
        None
    }
  }

  /** call complete, notify reciver */
  def complete (id:String, resp:SnakkResponse) : Boolean = {
    log("SNAKKPROXY Completing " + id + " with " + resp)
      inProgress.synchronized {
        val rec = inProgress.remove(id)
        rec.map {rec=>
          info("SNAKKPROXY  Completing - found" + id)
          rec.sc.promise.foreach(_.success(resp))
          clean
          true
      } getOrElse {
          info("SNAKKPROXY  Completing - NOT found" + id)
          clean
          false
        }
      }
  }
}

