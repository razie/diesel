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
import controllers.{RazController, CodePills, Club}
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.model._
import razie.|>._

import scala.collection.mutable.ListBuffer


/** a list of topics, picked up from another topic/section.
  *
  * this is caccehd, so not mingled with statuses
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
                  (f:PartialFunction[(Either[TopicList,UWID],Option[Progress], String), B]) : List[B] = {
    List(f(Left(this), p, path)) ++ topics.toList.flatMap{uwid=>
      val npath = path+"/"+uwid.page.get.wid.wpath
      if(uwid.cat == "Pathway") {
        Progress.topicList(uwid).toList.flatMap(_.traverse(p, npath)(f))
      } else {
        f(Right(uwid), p, npath) :: Nil
      }
    }
  }

  def contains (u:UWID, p:Progress) = {
    var found=false
    traverse(Some(p), "") {
      case (Left(t), pl, path) => {
      }
      case (Right(t), pl, path) => {
        if(t.id == u.id) // some of them come with NOCATS
          found=true
      }
    }
    found
  }

  def next (u:UWID, p:Progress) = {
    var found=false
    var prev:Option[UWID] = None
    var next:Option[UWID] = None
    var parent:Option[UWID] = None
    var curp : UWID = ownerTopic

    traverse(Some(p), "") {
      case (Left(t), pl, path) => {
        curp = ownerTopic
      }
      case (Right(t), pl, path) => {
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

  def current (p:Progress) = {
    var cur:Option[UWID] = None

    traverse(Some(p), "") {
      case (Left(t), pl, path) => {
      }
      case (Right(t), pl, path) => {
        if(! p.isComplete(t) && cur.isEmpty) {
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
    val res = ListBuffer[WikiSection]()

    traverse(p, "") {
      case (Left(t), _, _) => {
      }
      case (Right(t), _, _) => {
        if(p.isEmpty || p.exists(p=> p.isComplete(t) || p.isInProgress(t))) {
          res appendAll t.page.toList.flatMap{page=>
            page.preprocessed;
            val x = page.sections.filter{s=>
              cond(page, s)
            }
            x
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

/** either Entry or Level */
case class ProgressRecord (
  val topic: UWID,
  status: String, // 's' skipped, 'r' read, 'p' passed quiz
  val dtm: DateTime = DateTime.now()
  )

/** a user that may or may not have an account - or user group */
@RTable
case class Progress (
  // todo owner to be an RK
  ownerId: ObjectId, // user that owns this progress
  ownerTopic: UWID, // like book/section/course - must be a WID since it includes section
  records:Seq[ProgressRecord],
  lastTopic:Option[UWID] = None,
  lastDtm: DateTime = DateTime.now,
  crDtm:  DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Progress] {

  val ownerWid = ownerTopic.wid

  def rec   (uwid:UWID) = records.find(_.topic == uwid)
  def rec   (uwid:UWID, status:String) = records.find(x=> x.topic == uwid && x.status == status)
  def recs  (uwid:UWID) = records.filter(_.topic == uwid)
  def recs  (uwid:UWID, status:String) = records.filter(x=> x.topic == uwid && x.status == status)

  def isInProgress (uwid:UWID) = records.find(_.topic == uwid).exists(_.status == Progress.STATUS_IN_PROGRESS)
  def isComplete (uwid:UWID) =
    records.find(_.topic == uwid).exists(x=>
      x.status == Progress.STATUS_COMPLETE || x.status == Progress.STATUS_SKIPPED || x.status == Progress.STATUS_READ
    )

  def add (uwid:UWID, status:String) = {
    if (rec(uwid).exists(_.status == status))
      this
    else
      copy(records = new ProgressRecord(uwid, status) +: records)
  }
}

/** racer kid info utilities */
object Progress extends RazController with WikiMod {
  final val STATUS_SKIPPED = "s"
  final val STATUS_READ = "r"
  final val STATUS_PASSED = "p"
  final val STATUS_COMPLETE = "c"
  final val STATUS_NOT_STARTED = "n"
  final val STATUS_IN_PROGRESS = "i"

  def findById(id: ObjectId) = ROne[Progress]("_id" -> id)
  def findForUser(id: ObjectId) = RMany[Progress]("ownerId" -> id)

  def findByUserAndTopic(userId:ObjectId, uwid:UWID) = ROne[Progress]("ownerId" -> userId, "ownerTopic" -> uwid.grated)

  def findForTopic(userId:ObjectId, uwid:UWID) = RMany[Progress]("ownerId" -> userId).find(p=>
    Progress.topicList(p.ownerTopic).exists(tl=>
      tl.contains (uwid, p)
    )
  )

  def startProgress (ownerId:ObjectId, tl:TopicList): Progress = {
    val p = new Progress (ownerId, tl.ownerTopic, Seq(new ProgressRecord(tl.ownerTopic, STATUS_IN_PROGRESS)))
    p.create
    p
  }

  def topicList(w:UWID) : Option[TopicList] = {
    WikiRefinery.get[TopicList] (w) {uwid=>
       if(uwid.cat == "Pathway")
         (true, Some(new TopicList(uwid)))
      else
         (false, None)
    }
  }

  def init = {}

  def restart (pathway:String) = FAU ("you need a free account to track your progress.") {implicit au=>
    implicit errCollector=> implicit request=>
      val path = pathway.split("/")
      val pwid = WID.fromPath(path(0)).get.r(Website.realm)
      (for(
        pway <- pwid.page orErr "pathway not found";
        tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
      ) yield {
          findByUserAndTopic(au._id, tl.ownerTopic).map(_.delete)
          val p = startProgress(au._id, tl)
          Redirect(routes.Progress.view(pathway))
        })
  }

  def view (pathway:String) = Action {implicit request=>
    implicit val errCollector = new VErrors()
 implicit val au = activeUser;
        val path = pathway.split("/")
        val pwid = WID.fromPath(path(0)).get.r(Website.realm)
        (for(
          pway <- pwid.page orErr "pathway not found";
          tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
        ) yield {
            val p = au.flatMap(u=> ROne[Progress]("ownerId" -> u._id, "ownerTopic" -> pway.uwid.grated))
            ROK(au, request) apply {implicit stok=>
              viewProgress(p, tl, path(0))
            }
          }) getOrElse unauthorized("oops")
  }

  def sayHi = Action {request=> Ok("hello")}

  CodePills.addString ("mod.progress/sayhi") {implicit request=>
    "ok"
  }

  // later load of the doNext links - needed later to reuse request
  CodePills.add("mod.progress/next") {implicit request=>
    implicit val errCollector = new VErrors()
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield {
        (for (
          pwid <- WID.fromPath(q("wpath")).map(_.r(Website.realm)) orErr "invalid wpath";
          uwid <- pwid.uwid;
          p <- findForTopic(au._id, uwid);
          tl <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            val n = tl.next(uwid,p)
            Ok(prevNext(n._1, p, uwid, n._2, n._3))
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse Ok(
        """
          |<div class="alert alert-info">
          |    <span style="font-weight : bold ;color:red">
          |        You need a <i>free</i> account to track progress - <a href="/doe/join">Join now</a>!
          |    </span>
          |</div>
        """.stripMargin)
  }

  CodePills.add("mod.progress/doNext") {implicit request=>
    implicit val errCollector = new VErrors()
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield {
        (for (
          widFrom <- WID.fromPath(q("from")).map(_.r(Website.realm))  orErr "invalid wpath";
          widTo   <- WID.fromPath(q("to")).map(_.r(Website.realm))  orErr "invalid wpath";
          uwid    <- widFrom.uwid;
          p       <- findForTopic(au._id, uwid);
          tl      <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            val st = q("status");
            val n = tl.next(uwid,p)
            p.add (uwid, st).update
            Redirect(widTo.urlRelative)
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse unauthorized("You need a free account to track your progress.")
  }

  CodePills.add("mod.progress/sections") {implicit request=>
    implicit val errCollector = new VErrors()
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    val all = q.getOrElse("all", "no")

    (for (
      au      <- activeUser;
      isA     <- checkActive(au);
      pathway <- q.get("pathway") orErr "missing pathway";
      sec     <- q.get("section") orErr "missing section"
    ) yield {
        val path = pathway.split("/")
        val pwid = WID.fromPath(path(0)).get.r(Website.realm)
        (for(
          pway <- pwid.page orErr "pathway not found";
          tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
        ) yield {
            val p = ROne[Progress]("ownerId" -> au._id, "ownerTopic" -> pway.uwid.grated)
            ROK()(au, request) apply {implicit stok=>
              if("yes" == all)
                viewSections(sec, None, tl, pathway)
              else
                viewSections(sec, p, tl, pathway)
            }
          }) getOrElse unauthorized("oops")
      }) getOrElse unauthorized("You need a free account to track your progress. ")
  }

  CodePills.add("api/wix/realm/count") {implicit request=>
    implicit val errCollector = new VErrors()
    api.wix.init(None, auth, Map.empty, Website.realm)
    Ok(api.wix.realm.count.toString).withHeaders("Access-Control-Allow-Origin" -> "*")
    // allow is for cross-site scripting
    }

  def modName:String = "mod.progress"
  def modProps:Seq[String] = Seq("mod.progress")
  def isInterested (we:WikiEntry) : Boolean =
    we.tags.contains("mod.progress")

  def modPreFormat (we:WikiEntry, content:Option[String]) : Option[String] = {
    content.orElse(Some(we.content)).map{c=>
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    //todo use the wiki parser later modifiers to load the sections, not a separate parser here
    val PATT1 = """(?s)\{\{\.*(section)([: ])?([^:}]*)?(:)?([^}]*)?\}\}((?>.*?(?=\{\{/[^`])))\{\{/\.*(section)?\}\}""".r //?s means DOTALL - multiline
    val PATT2 = PATT1

    val maps = Map("GETUP" -> "GET UP!", "ONSNOW" -> "ON SNOW!", "QUIZ" -> "QUIZ!")

    PATT1.replaceSomeIn(c, {m=>
      maps.foldLeft(None.asInstanceOf[Option[String]]) {(o, t) =>
        if(o.isDefined) o
        else if(m.group(3) startsWith t._1)
          Some("{div.alert.info}<b>"+t._2+"</b> <small>"+m.group(5)+"</small><p><p>" + m.group(6) + "{/div}")
        else None
      }
    })
    }
  }

  def modPostFormat (we:WikiEntry, html:String) : String =
    html.replaceAll("\\{div.alert.info}", "<div class=\"alert alert-info\">").
      replaceAll("\\{/div\\}", "</div>") +
      modProp(modName, "bottom", Some(we))

  def modProp (prop:String, value:String, we:Option[WikiEntry]) : String = {
    """<div id=""""+value+"""">div.later</div> <script>$("#"""+value+"""").load("/pill/mod.progress/next?wpath="""+we.map(_.wid.wpath).mkString+"""");</script> """
  }

  WikiMods register this
}

