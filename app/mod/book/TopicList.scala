package mod.book

import razie.wiki.model.{WikiRefined, _}
import razie.wiki.parser.WAST
import scala.collection.mutable.ListBuffer

// for traversal - type of visit callback
abstract class TLNode
case class TLFolder(x:TopicList) extends TLNode
case class TLTopic(x:UWID)  extends TLNode
case class TLFolderEnd(x:TopicList)  extends TLNode
case class TLFolderEndRoot(x:TopicList)  extends TLNode

/** a list of topics, picked up from another topic/section. useful for Progress tracking, table of contents etc
  *
  * this is cached, so not mingled with statuses, see Progress
  */
case class TopicList (
  ownerTopic: UWID, // like book/section/course
  topics: Seq[UWID] // child progresses
  ) extends WikiRefined(ownerTopic, topics.filter(_.cat == "Pathway")) {

  def page = ownerTopic.page

  def this (ownerTopic:UWID) = this (ownerTopic, {
    val wid = ownerTopic.wid.get
    val res = Wikis.preprocess(wid, "md", wid.content.get, None)._1.fold(WAST.context(None))

    def ilinks = res.ilinks.filter(_.isInstanceOf[ILink]).asInstanceOf[List[ILink]]

//    cdebug << "TOPICLIST: " + wid.wpathFull + "\n" + ilinks.mkString(" \n ")

    val rr1 = ilinks.filter(_.role.exists(_ == "step"))
    // really need to get my act together here in these lookups...
    val ret = rr1.flatMap(x => x.wid.uwid)
    ret
  })

  /** traverse topic lists recursively while matching it up with progress records - use it to paint
    *
    * Left is for folder nodes, Right is for leaf nodes
    */
  def traverse[B] (p:Option[Progress], path:String)
                  (f:PartialFunction[(TLNode,Option[Progress], String), B]) : List[B] = {

//    cdebug << "TT-TOPICLIST: " + ownerTopic.wid.get.wpathFull + "\n" + topics.flatMap(_.page.map(_.wid.wpathFull).toList).mkString(" \n ")

    (
      if(f.isDefinedAt(TLFolder(this), p, path))
        List(f(TLFolder(this), p, path))
      else Nil
    ) ++
      topics.toList.flatMap{uwid=>
        //todo client must at least respond to each node or should I check it
        val npath = path+"/"+uwid.page.get.wid.wpath
        if(uwid.cat == "Pathway") {
          Progress.topicList(uwid).toList.flatMap(_.traverse(p, npath)(f))
        } else {
          f(TLTopic(uwid), p, npath) :: Nil
        }
      } ++
        (
          if(f.isDefinedAt(TLFolderEnd(this), p, path))
            List(f(TLFolderEnd(this), p, path))
          else Nil
        )
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
  def sections (p:Option[Progress], seeAll:Boolean, cond:(WikiEntry, WikiSection) => Boolean) = {
    // todo could cache this under progress as we go along, I guess
    val res = ListBuffer[(String, WikiSection)]()

    traverse(p, "") {
      case (TLFolder(t), _, _) => {
      }
      case (TLTopic(t), _, path) => {
        if(p.isEmpty || seeAll || p.exists(_.hasCollectables(t))) {
          res appendAll t.page.toList.flatMap{page=>
            page.preprocessed; // todo needs au
            val x = page.sections.filter{s=>
              !res.exists(_._2.name == s.name) && cond(page, s)
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


