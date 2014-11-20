/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \	   Read
 *   )	 / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.casbah.Imports._
import razie.base.data.TripleIdx
import scala.collection.mutable.HashMap

/** the index is (name, WID, ID) */
class WikiIndex (val realm:String) {
  case class PEntry(ilinks: List[ILink])
  private val parsed = scala.collection.mutable.Map[ObjectId, PEntry]()

  /** the index is (name, WID, ID) */
  def withIndex[A](f: TripleIdx[String, WID, ObjectId] => A) = {
    synchronized {
      f(actualIndex)
    }
  }

  private val lower = HashMap[String, String]() // for lowercase
  private val labels = HashMap[String, String]() // quick label lookup

  private lazy val actualIndex = {
    val t = new TripleIdx[String, WID, ObjectId]()
    // load it the first time
    Wikis(realm).foreach { db =>
      val w = WID(
	db.as[String]("category"),
	db.as[String]("name"),
	if (db.containsField("parent")) Some(db.as[ObjectId]("parent")) else None,
	None).r(db.as[String]("realm"))
      t.put(w.name, w, db.as[ObjectId]("_id"))
      lower.put(w.name.toLowerCase(), w.name)
      labels.put(w.name, db.as[String]("label"))
    }
    t
  }

  /** update an entry */
  private def up(we: WikiEntry) {
    parsed.put(we._id, PEntry(we.ilinks))
    if (we.wid.cat == "Category")
      Wikis(realm).cats.put(we.wid.name, we)
  }

  def graph(oid: ObjectId) = synchronized {
    parsed.getOrElseUpdate(oid, PEntry(Wikis(realm).find(oid).toList.flatMap(_.ilinks)))
  }

  def update(oldVer: WikiEntry, newVer: WikiEntry) = withIndex { idx =>
    if (oldVer.category != newVer.category || oldVer.name != newVer.name || oldVer.realm != newVer.realm) {
      idx.put(newVer.name, newVer.wid, oldVer._id)
      idx.remove2(oldVer.name, oldVer.wid)
      lower.put(newVer.name.toLowerCase(), newVer.name)
      lower.remove(oldVer.name)
      labels.put(newVer.name, newVer.label)
      labels.remove(oldVer.name)
    }
    up(newVer)
  }

  def create(we: WikiEntry) = withIndex { idx =>
    idx.put(we.name, we.wid, we._id)
    lower.put(we.name.toLowerCase(), we.name)
    labels.put(we.name, we.label)
    up(we)
  }

  def delete(we: WikiEntry) = withIndex { idx =>
    idx.remove2(we.name, we.wid)
    lower.remove(we.name.toLowerCase())
    labels.remove(we.name)
    parsed.remove(we._id)
  }

  def containsName(name: String) = withIndex { idx =>
    idx.idx.contains(name)
  }

  def containsLower(name: String) = withIndex { idx =>
    lower.contains(name)
  }

  // TODO stupidest name this month
  def getForLower(name: String) = withIndex { idx =>
    lower.get(name)
  }

  def label(name: String) = withIndex { idx =>
    labels.get(name)
  }

  /** returns a random WID from index - keep calling until isDefined */
  def random: Option[WID] = withIndex { idx =>
    val k = labels.keySet.toSeq
    val r = (math.random * k.size).toInt
    if (k.isDefinedAt(r)) {
      idx.get1k(k(r)).headOption
    } else None
  }
}
/** the index is (name, WID, ID) */
object WikiIndex {
  /** the index is (name, WID, ID) */
  def withIndex[A](realm:String=Wikis.DFLT)(f: TripleIdx[String, WID, ObjectId] => A) =
    Wikis(realm).index.withIndex(f)

  /** @deprecated the index is (name, WID, ID) */
//  def withIndex[A](f: TripleIdx[String, WID, ObjectId] => A) =
//    Wikis(Wikis.DFLT).index.withIndex(f)
}
