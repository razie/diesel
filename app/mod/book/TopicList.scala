package mod.book

import model.Website
import play.api.mvc.Action
import razie.wiki.admin.{WikiRefinery, WikiRefined}
import razie.wiki.mods.{WikiMods, WikiMod}
import razie.wiki.parser.WAST
import razie.wiki.util.{IgnoreErrors, VErrors}
import views.html.modules.book.{viewSections, prevNext, viewProgress}

import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import admin.{Config}
import controllers.{ViewService, RazController, CodePills, Club}
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.model._
import razie.|>._

import scala.collection.mutable.ListBuffer

// for traversal
abstract class TLNode
case class TLFolder(x:TopicList) extends TLNode
case class TLTopic(x:UWID)  extends TLNode
case class TLFolderEnd(x:TopicList)  extends TLNode

/** a list of topics, picked up from another topic/section.
  *
  * this is cached, so not mingled with statuses
  */
case class TopicList (
  ownerTopic: UWID, // like book/section/course
  topics: Seq[UWID] // child progresses
  ) extends WikiRefined(ownerTopic, topics.filter(_.cat == "Pathway")) {

  def page = ownerTopic.page

  def this (ownerTopic:UWID) = this (ownerTopic, {
    val wid = ownerTopic.wid.get
    val res = Wikis.preprocess(wid, "md", wid.content.get).fold(WAST.context(None))
    res.ilinks.filter(_.role.exists(_ == "step")).flatMap(_.wid.uwid).toSeq
  })

  /** traverse topic lists recursively while matching it up with progress records - use it to paint
    *
    * Left is for folder nodes, Right is for leaf nodes
    */
  def traverse[B] (p:Option[Progress], path:String)
                  (f:PartialFunction[(TLNode,Option[Progress], String), B]) : List[B] = {
    (if(f.isDefinedAt(TLFolder(this), p, path)) List(f(TLFolder(this), p, path))  else Nil) ++
    //todo client must at least respond to each node or should I check it
      topics.toList.flatMap{uwid=>
        val npath = path+"/"+uwid.page.get.wid.wpath
        if(uwid.cat == "Pathway") {
          Progress.topicList(uwid).toList.flatMap(_.traverse(p, npath)(f))
        } else {
          f(TLTopic(uwid), p, npath) :: Nil
        }
      } ++
        (if(f.isDefinedAt(TLFolderEnd(this), p, path)) List(f(TLFolderEnd(this), p, path)) else Nil)
  }

  def contains (u:UWID, p:Progress) = {
    var found=false
    traverse(Some(p), "") {
      case (TLFolder(t), pl, path) => {
      }
      case (TLTopic(t), pl, path) => {
        if(t.id == u.id) // some of them come with NOCATS
          found=true
      }
    }
    found
  }

  // find the next topic - todo read or unread?
  def next (u:UWID, p:Progress) = {
    var found=false
    var prev:Option[UWID] = None
    var next:Option[UWID] = None
    var parent:Option[UWID] = None
    var curp : UWID = ownerTopic

    traverse(Some(p), "") {
      case (TLFolder(t), pl, path) => {
        curp = ownerTopic
      }
      case (TLTopic(t), pl, path) => {
        if(t.id == u.id) {
          parent = Some(curp)
          found=true
        } else if(!found) prev = Some(t)
        else if(found && next.isEmpty) next = Some(t)
      }
    }
    (found, prev, next, parent)
  }

//  def update (u:UWID, p:Progress, status:String) = {
//    var found=false
//
//    traverse(Some(p), "") {
//      case (Left(t), pl, path) => {
//      }
//      case (Right(t), pl, path) => {
//        if(t == u && !found) {
//          found=true
//        }
//      }
//    }
//  }

  def countSteps = {
    var ctr = 0

    traverse(None, "") {
      case (TLFolder(t), pl, path) => {
      }
      case (TLTopic(t), pl, path) => {
        ctr += 1
        }
      }

    ctr
  }

  def current (p:Progress) = {
    var cur:Option[UWID] = None

    traverse(Some(p), "") {
      case (TLFolder(t), pl, path) => {
      }
      case (TLTopic(t), pl, path) => {
        if(p.isNext(t) && cur.isEmpty) {
          cur = Some(t)
        }
      }
    }
    cur
  }

  /** find a certain type of section that was not completed in the completed topics.
    *
    * If no progress passed, then find all sections */
  def sections (p:Option[Progress], cond:(WikiEntry, WikiSection) => Boolean) = {
    // todo could cache this under progress as we go along, I guess
    val res = ListBuffer[(String, WikiSection)]()

    traverse(p, "") {
      case (TLFolder(t), _, _) => {
      }
      case (TLTopic(t), _, path) => {
        if(p.isEmpty || p.exists(p=> p.hasCollectables(t))) {
          res appendAll t.page.toList.flatMap{page=>
            page.preprocessed; // todo needs au
            val x = page.sections.filter{s=>
              cond(page, s)
            }
            x.map(x=>(path,x))
          }
        }
      }
    }
    res
  }

  def findPath (p:List[String]) = {
//    if(p.size <= 1)
      this
//    else

  }
}


