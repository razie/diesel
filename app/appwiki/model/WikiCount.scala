/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model
import com.novus.salat.grater
import db.RazSalatContext.ctx
import db.Mongo
import org.bson.types.ObjectId
import db.ROne
import db.RUpdate
import db.RCreate

/** keep track of views, per wiki page id */
@db.RTable
case class WikiCount (
  pid: ObjectId, 
  count: Long = 1
  ) {
  
  def inc = {
    WikiCount.findOne (pid) map (p=> 
      RUpdate noAudit (Map("pid" -> pid), p.copy(count=p.count+1))
    ) orElse {
      RCreate noAudit this
      None
    }
  }
}

/** wiki factory and utils */
object WikiCount {
  def findOne(pid: ObjectId) = ROne[WikiCount] ("pid" -> pid)
}
