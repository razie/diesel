package mod.book

import model.{User, Website}
import play.api.mvc.Action
import razie.wiki.admin.{Audit, WikiRefinery, WikiRefined}
import razie.wiki.mods.{WikiMods, WikiMod}
import razie.wiki.parser.WAST
import razie.wiki.util.{IgnoreErrors, VErrors}
import views.html.modules.book.{viewSections, prevNext, viewProgress}

import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import admin.{Config}
import controllers.{ViewService, RazController, CodePills, Club}
import razie.db._
import razie.wiki.model._
import razie.|>._

import scala.collection.mutable.ListBuffer

/** either Entry or Level */
case class ProgressRecord (
  topic: UWID,
  section:Option[WID],
  status: String, // 's' skipped, 'r' read, 'p' passed quiz
  dtm: DateTime = DateTime.now()
  )

/** a user that may or may not have an account - or user group
  *
  * records are being added to - newest first.
  */
@RTable
case class Progress (
  // todo owner to be an RK
  ownerId: ObjectId, // user that owns this progress
  ownerTopic: UWID, // like book/section/course - must be a WID since it includes section
  status: String, // 's' skipped, 'r' read, 'p' passed quiz
  records:Seq[ProgressRecord],
  lastTopic:Option[UWID] = None,
  lastDtm: DateTime = DateTime.now,
  crDtm:  DateTime = DateTime.now,
  _id: ObjectId = new ObjectId()) extends REntity[Progress] {

  val ownerWid = ownerTopic.wid
  def pathway = ownerTopic.wid.map(_.wpath).mkString

  def rec   (uwid:UWID, section:Option[WID] = None) = records.find(x=> x.topic == uwid && x.section == section)
  def rec   (uwid:UWID, status:String) = records.find(x=> x.topic == uwid && x.status == status && x.section.isEmpty)
  def recs  (uwid:UWID, section:Option[WID] = None) = records.filter(x=> x.topic == uwid && x.section == section)
  def recs  (uwid:UWID, status:String) = records.filter(x=> x.topic == uwid && x.status == status && x.section.isEmpty)

  def percentage = (records.size-1).toString + "/" +
    Progress.topicList(ownerTopic).map(_.countSteps.toString).getOrElse("?")

  def isInProgress (uwid:UWID, section:Option[WID] = None) =
  //todo maybe READ topics are done?
    records.find(x=> x.topic == uwid && x.section == section).exists(x=> x.status == Progress.STATUS_IN_PROGRESS)// || x.status == Progress.STATUS_READ)

  def hasCollectables (uwid:UWID) =
    records.find(x=> x.topic == uwid && x.section == None).exists(x=>
      x.status == Progress.STATUS_READ
    )

  def isComplete (uwid:UWID, section:Option[WID] = None) =
    records.find(x=> x.topic == uwid && x.section == section).exists(x=>
      x.status == Progress.STATUS_PASSED || x.status == Progress.STATUS_COMPLETE || x.status == Progress.STATUS_SKIPPED
    )

  def isNext (uwid:UWID, section:Option[WID] = None) =
    ! records.find(x=> x.topic == uwid && x.section == section).exists(x=>
      x.status == Progress.STATUS_READ || x.status == Progress.STATUS_PASSED || x.status == Progress.STATUS_COMPLETE || x.status == Progress.STATUS_SKIPPED
    )

  def addAndUpdate (uwid:UWID, section:Option[WID], status:String) = {
    if (rec(uwid, status).isDefined)
      this
    else {
      Audit ! Audit("?", "PROGRESS_REC", s"User: ${ownerId.as[User].map(_.userName).mkString} Topic: ${uwid.nameOrId} Status: $status")
      val t = copy(records = new ProgressRecord(uwid, section, status) +: records)
      t.updateNoAudit
      t
    }
  }
}

/** racer kid info utilities */
object Progress extends RazController with WikiMod {
  final val STATUS_SKIPPED = "s"
  final val STATUS_READ = "r"
  final val STATUS_PASSED = "p"
  final val STATUS_COMPLETE = "c"
  final val STATUS_NOT_STARTED = "n"
  final val STATUS_PAUSED = "u" // pathway paused - another is current
  final val STATUS_IN_PROGRESS = "i" // also means current

  def findById(id: ObjectId) = ROne[Progress]("_id" -> id)
  def findCurrentForUser(id: ObjectId) = ROne[Progress]("ownerId" -> id, "status" -> STATUS_IN_PROGRESS)
  def findForUser(id: ObjectId) = RMany[Progress]("ownerId" -> id)
  def findByUserAndTopic(userId:ObjectId, uwid:UWID) = ROne[Progress]("ownerId" -> userId, "ownerTopic" -> uwid.grated)

  /** tricky - will prefer the one in progress */
  def findForTopic(userId:ObjectId, uwid:UWID) = {
    val all = RMany[Progress]("ownerId" -> userId).filter(p=>
      Progress.topicList(p.ownerTopic).exists(tl=>
        tl.contains (uwid, p)
      )
    ).toList
    //todo optimize - this loads all progresses
    all.find(_.status == STATUS_IN_PROGRESS).orElse(all.headOption)
  }

  import RMongo.as

  def startProgress (ownerId:ObjectId, tl:TopicList): Progress = {
    //suspend anything in progress
    findForUser(ownerId).filter(_.status == STATUS_IN_PROGRESS).foreach{p=>
      p.copy(status=STATUS_PAUSED).update
    }
    Audit ! Audit("?", "PROGRESS_START", s"User: ${ownerId.as[User].map(_.userName).mkString} TopicList: ${tl.uwid.nameOrId}")
    val p = new Progress (ownerId, tl.ownerTopic, STATUS_IN_PROGRESS, Seq(new ProgressRecord(tl.ownerTopic, None, STATUS_IN_PROGRESS)))
    p.createNoAudit
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

  def restart (pathway:String) = FAU("Can't start a custom progression without an account!") {implicit au=> implicit errCollector=> implicit request=>
    val path = pathway.split("/")
    val pwid = WID.fromPath(path(0)).get.r(Website.realm)
    (for(
      pway <- pwid.page orErr "pathway not found";
      tl <- Progress.topicList(pway.uwid) orErr ("topic list not found: "+pwid.wpath);
      prog <- findByUserAndTopic(au._id, tl.ownerTopic)
    ) yield {
        ViewService.impl.utilMsg(
          "Are you sure?",
          "Click Reset to delete all progress history !!!\n\n Or just Continue and go back !",
          Some("/book/progress/view"), Some(au),
          Some("Reset" -> s"/book/progress/restart1?pathway=$pathway"))
      }) orElse
    Some(Redirect(routes.Progress.restart1(pathway)))
  }

  def restart1 (pathway:String) = FAU ("you need a free account to track your progress.") {implicit au=>
    implicit errCollector=> implicit request=>
      val path = pathway.split("/")
      val pwid = WID.fromPath(path(0)).get.r(Website.realm)
      (for(
        pway <- pwid.page orErr "pathway not found";
        tl <- Progress.topicList(pway.uwid) orErr ("topic list not found: "+pwid.wpath)
      ) yield {
          findByUserAndTopic(au._id, tl.ownerTopic).map(_.delete)
          val p = startProgress(au._id, tl)
          Redirect(routes.Progress.view(pathway))
        })
  }

  def view (pathway:String) = Action {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val au = activeUser;

    def viewPathway (p:String) = {
      val path = p.split("/")
      val pwid = WID.fromPath(path(0)).get.r(Website.realm)
      (for(
        pway <- pwid.page orErr "pathway not found";
        tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
      ) yield {
          val p = au.flatMap(u=> findByUserAndTopic(u._id, pway.uwid))
          ROK(au, request) apply {implicit stok=>
            viewProgress(p, tl, path(0))
          }
        }) getOrElse unauthorized("oops")
    }

    if(pathway.isEmpty) {
      // todo find current pathway or list current pathways or list all pathways
      au.flatMap {u=> findForUser(u._id).find(_.status == STATUS_IN_PROGRESS)} flatMap (_.ownerWid) map {p=>
        viewPathway (p.wpath)
      } getOrElse
      viewPathway (DFLT_PATHWAY)
    } else {
      viewPathway (pathway)
    }
  }

  def switchTo (pathway:String)= FAU {implicit au=> implicit errCollector=> implicit request=>
    WID.fromPath(pathway).flatMap(_.uwid).flatMap(u=> findByUserAndTopic(au._id, u)).map{p=>
      findForUser(au._id).filter(_.status == STATUS_IN_PROGRESS).foreach{p=>
        p.copy(status=STATUS_PAUSED).update
      }
      p.copy(status=STATUS_IN_PROGRESS).update
    }
    Redirect(routes.Progress.view(pathway))
  }

  val DFLT_PATHWAY = "Pathway:Effective"

  // ----------------- pills

  def sayHi = Action {request=> Ok("hello")}

  CodePills.addString ("mod.progress/sayhi") {implicit request=>
    "ok"
  }

  // later load of the doNext links - needed later to reuse request
  CodePills.add("mod.progress/next") {implicit request=>
    implicit val errCollector = new VErrors()
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser
    ) yield {
        (for (
          pwid <- q.get("wpath") flatMap WID.fromPath map (_.r(Website.realm)) orErr "invalid wpath";
          uwid <- pwid.uwid;
          p <- findForTopic(au._id, uwid);
          tl <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            val n = tl.next(uwid,p)
            Ok(prevNext(n._1, p, uwid, n._2, n._3))
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse Ok(
        """
          |<div class="alert alert-warning">
          |    <span style="font-weight : bold ;color:red">
          |        You need a <i>free</i> account to track progress and access the first of 9 levels and start improving your skiing!
          |        <p><a href="/doe/join">Join now!</a> Checkout the <a href="/book/progress/view">contents</a>
          |         or the public <a href="/browse">wiki!</a>
          |    </span>
          |</div>
        """.stripMargin)
  }

  /** mark as read and collect drills or complete if no drills */
  CodePills.add("mod.progress/doNext") {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser
    ) yield {
        (for (
          widFrom <- q.get("from") flatMap WID.fromPath map (_.r(Website.realm)) orErr "invalid wpath";
          widTo   <- q.get("to") flatMap WID.fromPath map(_.r(Website.realm)) orErr "invalid wpath";
          uwid    <- widFrom.uwid;
          page    <- uwid.page orErr "page not found: "+uwid;
          p       <- findForTopic(au._id, uwid);
          tl      <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            var st = q("status");

            //if there are no drills incomplete then mark as complete
            if(st == STATUS_READ && page.sections.filter{s=>
              SECTIONS.keys.exists(s.signature startsWith _) && !p.isComplete(page.uwid, Some(s.wid))
            }.isEmpty)
              st = STATUS_COMPLETE

            val n = tl.next(uwid,p)
            p.addAndUpdate (uwid, None, st)
            Redirect(widTo.urlRelative)
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse unauthorized("You need a free account to track your progress.")
  }

  private def findCurrOrDefault(uid:ObjectId, pathway:Option[String]) = {
    if(pathway.nonEmpty && pathway.get.nonEmpty)
      pathway
    else
      findCurrentForUser(uid).flatMap(_.ownerWid).map(_.wpath).orElse(Some(DFLT_PATHWAY))
  }

  /** find all the sections in progress for user */
  CodePills.add("mod.progress/sections") {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val q = request.queryString.map(t=>(t._1, t._2.mkString))
    val all = q.getOrElse("all", "no")

    (for (
      au      <- activeUser;
      pathway <- findCurrOrDefault(au._id, q.get("pathway"));
      sec     <- qget("section")
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
      }) getOrElse
      unauthorized("You need a free account to track your progress. ")
  }

  private def qget (name:String)(implicit q:Map[String,String], errCollector: VErrors) =
    q.get(name) orErr s"missing $name"

  /** make up html sequence for done/skip buttons for drills */
  CodePills.add("mod.progress/section/buttons") {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser
    ) yield {
        (for (
          wid   <- q.get("wid") flatMap WID.fromPath map(_.r(Website.realm)) orErr "invalid wpath";
          uwid    <- wid.uwid;
          we <- wid.page;
          p       <- findForTopic(au._id, uwid);
          tl      <- Progress.topicList(p.ownerTopic) orErr "topic list not found";
          name     <- qget("section")
        ) yield {
            val disabled = if(p.isComplete(uwid, Some(wid.copy(section=Some(name))))) "disabled" else ""
            Ok(s"""<a class="btn btn-danger btn-xs $disabled" href="/pill/mod.progress/section/done?status=s&section=$name&wid=${we.wid.wpath}">Skip</a>
                   <a class="btn btn-success btn-xs $disabled" href="/pill/mod.progress/section/done?status=c&section=$name&wid=${we.wid.wpath}">Done</a>""")
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse Ok("""<b><span style="color:red">{{Login to track}}</span></b>""")
//      unauthorized("You need a free account to track your progress.")
  }

  CodePills.add("mod.progress/section/done") {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val q = request.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- activeUser
    ) yield {
        (for (
          wid    <- q.get("wid") flatMap WID.fromPath map(_.r(Website.realm)) orErr "invalid wpath";
          uwid   <- wid.uwid;
          page   <- wid.page;
          p      <- findForTopic(au._id, uwid);
          tl     <- Progress.topicList(p.ownerTopic) orErr "topic list not found";
          name   <- qget("section")
        ) yield {
            val st = q("status");

            val p1 = p.addAndUpdate (uwid, Some(wid.copy(section=Some(name))), st)

            // re-evaluate the topic and mark it done if no other sections outstanding
            //if there are no drills incomplete then mark as complete
            if(st == STATUS_COMPLETE && p.rec(uwid, STATUS_READ).isDefined && page.sections.filter{s=>
              Array("GETUP", "ONSNOW").exists(s.signature startsWith _) && !p1.isComplete(page.uwid, Some(s.wid))
            }.isEmpty)
              p1.addAndUpdate (uwid, None, STATUS_COMPLETE)

            Ok(s"""Ok - completed $name""")
          }) getOrElse Ok("<b>{{Pathway not started}}</b>")
      }) getOrElse unauthorized("You need a free account to track your progress.")
  }

  CodePills.add("api/wix/realm/count") {implicit request=>
    implicit val errCollector = new VErrors()
    api.wix.init(None, auth, Map.empty, Website.realm)
    Ok(api.wix.realm.count.toString).withHeaders("Access-Control-Allow-Origin" -> "*")
    // allow is for cross-site scripting
  }


  // ------------ mods

  def modName:String = "mod.progress"
  def modProps:Seq[String] = Seq("mod.progress")
  def isInterested (we:WikiEntry) : Boolean =
    we.tags.contains("mod.progress")

  /** format a section for display, with done/skip buttons and state */
  def formatSec (we:WikiEntry, cur:WikiSection, what:String, path:String) : String = {
    val (kind, name) = (cur.signature, cur.name)

    val c = s"""`{{section $name:$kind}}`${cur.content}`{{/section}}`"""

    // this will callback the modPreFormat below
    val f = Wikis.format(we.wid, we.markup, c, Some(we))
    val r = modPostHtmlNoBottom(we, f)
    r
  }

  val SECTIONS = Map("GETUP" -> "GET UP!", "ONSNOW" -> "ON SNOW!", "QUIZ" -> "QUIZ!")

  override def modPreHtml (we:WikiEntry, content:Option[String]) : Option[String] = {
    content.orElse(Some(we.content)).map{c=>
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    //todo use the wiki parser later modifiers to load the sections, not a separate parser here
      // sections with {{` are escaped inside other sections
    val PATT1 = """(?s)`\{\{\.*(section)([: ])?([^:}]*)?(:)?([^}]*)?\}\}`((?>.*?(?=`\{\{/[^`])))`\{\{/\.*(section)?\}\}`""".r //?s means DOTALL - multiline

    def div (kind:String, name:String, content:String) =
      s"""{div.alert.info}<b>$kind</b> <small>$name</small>&nbsp;&nbsp;<div align="right" style="display:inline">${buttons(name)}</div><br>$content{/div}"""

    def buttons (name:String) = {
      s"""<a class="btn btn-danger btn-xs" href="/pill/mod.progress/box?what=s&name=$name&wid=${we.wid.wpath}">Skip</a>
      <a class="btn btn-success btn-xs" href="/pill/mod.progress/box?what=c&name=$name&wid=${we.wid.wpath}">Done</a>"""
      s"""<span id="$name">...</span>"""+
     Wikis.propLater(name, s"/pill/mod.progress/section/buttons?section=$name&wid=${we.wid.wpath}}}")
    }

    PATT1.replaceSomeIn(c, {m=>
      SECTIONS.foldLeft(None.asInstanceOf[Option[String]]) {(o, t) =>
        if(o.isDefined) o
        else if(m.groupCount > 4 && m.group(5).startsWith(t._1)) {
          Some(div(t._2, m.group(3), m.group(6)).replaceAll("\\$", "\\\\\\$"))
        }
        else None
      }
    })
    }
  }

  def modPostHtmlNoBottom (we:WikiEntry, html:String) : String =
    html.replaceAll("\\{div.alert.info}", "<div class=\"alert alert-info\">").
    replaceAll("\\{/div\\}", "</div>")

  override def modPostHtml (we:WikiEntry, html:String) : String =
    modPostHtmlNoBottom(we, html) +
    modProp(modName, "bottom", Some(we))

  // get the prev/next later...
  override def modProp (prop:String, value:String, we:Option[WikiEntry]) : String = {
    """<div id=""""+
      value+
      """">div.later</div> <script>$("#"""+
      value+
      """").load("/pill/mod.progress/next?wpath="""+
      we.map(_.wid.wpath).mkString+
      """");</script> """
  }

  WikiMods register this
}

