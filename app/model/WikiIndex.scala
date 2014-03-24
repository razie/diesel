/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.novus.salat._
import com.novus.salat.annotations._
import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import scala.util.parsing.combinator.RegexParsers
import db.RTable
import razie.base.data.TripleIdx
import scala.collection.mutable.HashMap

/** 3 tuple indexed map */
class NewTripleIdx[A, B, C] extends TripleIdx[A, B, C] {
  def find(f: (A, B, C) => Boolean): Option[(A, B, C)] = {
    for (a <- idx; x <- a._2)
      if (f(a._1, x._1, x._2))
        return Some((a._1, x._1, x._2))
    None
  }

  def foreach(f: (A, B, C) => Unit): Unit = {
    for (a <- idx; x <- a._2)
      f(a._1, x._1, x._2)
  }

  def map[R](f: (A, B, C) => R): Seq[R] = {
    (for (a <- idx; x <- a._2)
      yield f(a._1, x._1, x._2)).toList
  }
}

/** the index is (name, WID, ID) */
object WikiIndex {
  case class PEntry(ilinks: List[ILink])
  private val parsed = scala.collection.mutable.Map[ObjectId, PEntry]()

  /** the index is (name, WID, ID) */
  def withIndex[A](f: NewTripleIdx[String, WID, ObjectId] => A) = {
    synchronized {
      f(WikiIndex.actualIndex)
    }
  }

  val lower = HashMap[String, String]() // for lowercase
  val labels = HashMap[String, String]() // quick label lookup
    
  private lazy val actualIndex = {
    val t = new NewTripleIdx[String, WID, ObjectId]()
    Wikis.table.find(Map()).foreach { db =>
      val w = WID(
          db.as[String]("category"), 
          db.as[String]("name"), 
          if (db.containsField("parent")) Some(db.as[ObjectId]("parent")) else None
          )
      t.put(w.name, w, db.as[ObjectId]("_id"))
      lower.put(w.name.toLowerCase(), w.name)
      labels.put(w.name, db.as[String]("label"))
    }
    t
  }

  private def up(we: WikiEntry) {
    parsed.put(we._id, PEntry(we.ilinks))
    if (we.wid.cat == "Category")
      Wikis.cats.put (we.wid.name, we)
  }

  def graph(oid: ObjectId) = synchronized {
    parsed.getOrElseUpdate(oid, PEntry(Wikis.find(oid).toList.flatMap(_.ilinks)))
  }

  def update(oldVer: WikiEntry, newVer: WikiEntry) = withIndex { idx =>
    if (oldVer.category != newVer.category || oldVer.name != newVer.name) {
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
  
  def containsName (name:String) = withIndex { idx =>
    idx.idx.contains(name)
    }

  def containsLower (name:String) = withIndex { idx =>
    lower.contains(name)
    }
  
  // TODO stupidest name this month
  def getForLower (name:String) = withIndex { idx =>
    lower.get(name)
    }

  def label (name:String) = withIndex { idx =>
    labels.get(name)
    }
}
