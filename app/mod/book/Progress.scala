package mod.book

import razie.hosting.Website
import model.{User, Users}
import org.apache.commons.lang3.StringUtils
import org.bson.types.ObjectId
import play.api.mvc.Action
import razie.js
import razie.wiki.Services
import razie.wiki.mods.{WikiMod, WikiMods}
import razie.wiki.parser.WAST
import views.html.modules.book.{prevNext, viewGuide, viewProgress, viewSections}
import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import controllers._
import razie.audit.Audit
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
  ) {
  def toj = Map(
    "topic" -> topic.wid.map(_.wpath).getOrElse(topic.toString),
    "section" -> section.map(_.wpath).mkString,
    "status" -> status,
    "dtm" -> dtm.toString
  )
}

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

  // only the last record - the rest are history and not searched
  def rec   (uwid:UWID, section:Option[WID] = None) =
    records.collectFirst{ case x if x.topic == uwid && x.section == section => x}
  def rec   (uwid:UWID, status:String) =
    records.collectFirst{ case x if x.topic == uwid && x.section.isEmpty => x}.filter(_.status == status)

  def percentage = (records.map(_.topic).distinct.size-1).toString + "/" +
    Progress.topicList(ownerTopic).map(_.countSteps.toString).getOrElse("?")

  def isInProgress (uwid:UWID, section:Option[WID] = None) =
  //todo maybe READ topics are done?
    records.find(x=> x.topic == uwid && x.section == section).exists(x=> x.status == Progress.STATUS_IN_PROGRESS)// || x.status == Progress.STATUS_READ)

  /** does it contain topics in READ status, which may have collectables */
  def hasCollectables (uwid:UWID) =
    records.find(x=> x.topic == uwid && x.section == None).exists(x=>
      x.status == Progress.STATUS_READ
    )

  /** nothing else to do */
  def isDone (uwid:UWID, section:Option[WID] = None) =
    records.find(x=> x.topic == uwid && x.section == section).exists(x=>
      x.status == Progress.STATUS_READ || x.status == Progress.STATUS_PASSED || x.status == Progress.STATUS_COMPLETE
    )

  /** includes skipped */
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
      Services ! Audit("?", "PROGRESS_REC", s"User: ${ownerId.as[User].map(_.userName).mkString} Topic: ${uwid.nameOrId} Section: ${section.mkString} Status: $status")
      val t = copy(records = new ProgressRecord(uwid, section, status) +: records)
      t.updateNoAudit(tx.auto)
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

  def XX(s1:String, s2:String) = {
    val x = s1 diff s2
    val y=  s1.toSeq.diff(s2.toSeq)
    val z=  s1.toCharArray.diff(s2.toCharArray)
    val xxx= StringUtils.indexOfDifference(s1, s2)
    val xxxxx= StringUtils.difference(s1,s2)
    x
  }

  def startProgress (ownerId:ObjectId, tl:TopicList): Progress = {
    //suspend anything in progress
    findForUser(ownerId).filter(_.status == STATUS_IN_PROGRESS).foreach{p=>
      p.copy(status=STATUS_PAUSED).updateNoAudit(tx.auto)
    }
    Services ! Audit("?", "PROGRESS_START", s"User: ${ownerId.as[User].map(_.userName).mkString} TopicList: ${tl.uwid.nameOrId}")
    val p = new Progress (ownerId, tl.ownerTopic, STATUS_IN_PROGRESS, Seq(new ProgressRecord(tl.ownerTopic, None, STATUS_IN_PROGRESS)))
    p.createNoAudit(tx.auto)
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
          "You were already progressing along this pathway.\n\n" +
            "Click Reset to delete all progress history and start again!!!\n\n" +
            "Or just Continue the progression!",
          Some("/improve/skiing/view"), Some(au),
          Some("Reset" -> s"/improve/skiing/restart1?pathway=$pathway"))
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
      ) yield     razie.db.tx("restart1", au.userName) { implicit txn =>
          findByUserAndTopic(au._id, tl.ownerTopic).map(_.delete)
          val p = startProgress(au._id, tl)
          Redirect(routes.Progress.view(pathway))
        })
  }

  def view (pathway:String) = RAction {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val au = request.au; // there is result for non AU

    def viewPathway (p:String) = {
      val path = p.split("/")
      val pwid = WID.fromPath(path(0)).get.r(Website.realm)
      (for(
        pway <- pwid.page orErr "pathway not found";
        tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
      ) yield {
          val p = au.flatMap(u=> findByUserAndTopic(u._id, pway.uwid))
          ROK.k apply {implicit stok=>
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

  def doeViewGuide = FAUR("view progress map") {implicit request=>
    for(
      x <- request.au
    ) yield {

      // label - tag
      val skills = List (
        "Stance",
        "Tipping",
        "Flexing",
        "CB",
        "CA",
        "Fore/aft",
        "Carving",
        "Speed control"
      )

          // label - tag
          val tags = Map (
            "Stance" -> "stance",
            "Tipping" -> "tipping",
            "Flexing" -> "flexing",
            "CB" -> "counterbalancing",
            "CA" -> "counteraction",
            "Fore/aft" -> "fore-aft",
            "Carving" -> "carving",
            "Speed control" -> "speed-control",
            "" -> ""
          )

          // label - percent
          val mm1 = Map (
            "Stance" -> 2,
            "Tipping" -> 26,
            "Flexing" -> 26,
            "CB" -> 65,
            "Fore/aft" -> 26
          )


      findForUser(x._id) map {p =>
        p
      }

          val m1 = skills.flatMap {skill=>
            tags(skill).map{tag=>
              1
            }
          }

          // label - percent
          val m2 = Map (
            "Stancxe" -> 65,
            "Tipping" -> 65,
            "Flexing" -> 65,
            "CB" -> 65,
            "Fore/aft" -> 65
          )

          ROK.k apply {implicit stok=>
            viewGuide(skills, tags, mm1, m2)
          }
      }
  }

  def switchTo (pathway:String)= FAUR {implicit request=>
    WID.fromPath(pathway).flatMap(_.uwid).flatMap(u=> findByUserAndTopic(request.au.get._id, u)).map{p=>
      findForUser(request.au.get._id).filter(_.status == STATUS_IN_PROGRESS).foreach{p=>
        p.copy(status=STATUS_PAUSED).updateNoAudit
      }
      p.copy(status=STATUS_IN_PROGRESS).updateNoAudit
    }
    Redirect(routes.Progress.view(pathway))
  }

  val DFLT_PATHWAY = "Pathway:Effective"

  // ----------------- pills
  final val PILL = "improve.skiing"

  def sayHi = Action {request=> Ok("hello")}

  def pathwayNotStarted =
    Ok(
      """<b><small>{{ No pathway
        |<span class="glyphicon glyphicon-info-sign" title="You need to login and start a progression to improve your skiing!"></span>
        |}}</small></b>""".stripMargin)

  CodePills.addString (s"$PILL/sayhi") {implicit request=>
    "ok"
  }

  // later load of the doNext links - needed later to reuse request
  CodePills.add(s"$PILL/next") {implicit request=>
    implicit val errCollector = new VErrors()
    val q = request.ireq.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- request.au
    ) yield {
        (for (
          pwid <- q.get("wpath") flatMap WID.fromPath map (_.r(request.realm)) orErr "invalid wpath";
          uwid <- pwid.uwid;
          p <- Progress.findCurrentForUser(au._id);// orElse findForTopic(au._id, uwid);
          tl <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            val n = tl.next(uwid,p)
            Ok(prevNext(n._1, p, uwid, n._2, n._3))
          }) getOrElse pathwayNotStarted
      }) getOrElse Ok(
        """
          |<div class="alert alert-warning">
          |    <span style="font-weight : bold ;color:red">
          |        You need a <i>free</i> account to track progress and access the the on-snow sessions and start improving your skiing!
          |        <p><a href="/doe/join">Join now!</a> Checkout the <a href="/improve/skiing/view">contents</a>
          |         or the public <a href="/browse">wiki!</a>
          |    </span>
          |</div>
        """.stripMargin)
  }

  /** mark as read and collect drills or complete if no drills */
  CodePills.add(s"$PILL/doNext") {implicit request=>
    implicit val errCollector = new VErrors()
    implicit val q = request.ireq.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- request.au
    ) yield {
        (for (
          widFrom <- q.get("from") flatMap WID.fromPath map (_.r(request.realm)) orErr "invalid wpath";
          widTo   <- q.get("to") flatMap WID.fromPath map(_.r(request.realm)) orErr "invalid wpath";
          uwid    <- widFrom.uwid;
          page    <- uwid.page orErr "page not found: "+uwid;
          p       <- findForTopic(au._id, uwid);
          tl      <- Progress.topicList(p.ownerTopic) orErr "topic list not found"
        ) yield {
            var st = q("status");

            //if there are no drills incomplete then mark as complete
            if(
              st == STATUS_READ &&
              page.sections.filter{s=>
                SECTIONS.keys.exists(s.signature startsWith _) && !p.isDone(page.uwid, Some(s.wid))
              }.isEmpty
            )
              st = STATUS_COMPLETE

            val n = tl.next(uwid,p)
            p.addAndUpdate (uwid, None, st)
            Redirect(widTo.urlRelative(request.realm))
          }) getOrElse pathwayNotStarted
      }) getOrElse unauthorized("You need a free account to track your progress.")
  }

  private def findCurr(uid:ObjectId, pathway:Option[String]) = {
    if(pathway.nonEmpty && pathway.get.nonEmpty)
      pathway
    else
      findCurrentForUser(uid).flatMap(_.ownerWid).map(_.wpath)
  }

  private def findCurrOrDefault(uid:ObjectId, pathway:Option[String]) = {
    if(pathway.nonEmpty && pathway.get.nonEmpty)
      pathway
    else
      findCurrentForUser(uid).flatMap(_.ownerWid).map(_.wpath).orElse(Some(DFLT_PATHWAY))
  }

  /** find all the sections in progress for user */
  CodePills.add(s"$PILL/sections") {implicit request=>
    implicit val q = request.ireq.queryString.map(t=>(t._1, t._2.mkString))
    val all = q.getOrElse("all", "no")
    val query = q.filter(_._1 startsWith "q.").map(t=>(t._1.substring(2), t._2))

    (for (
      au      <- request.au;
      pathway <- findCurrOrDefault(au._id, q.get("pathway"));
      sec     <- qget("section")
    ) yield {
        val path = pathway.split("/")
        val pwid = WID.fromPath(path(0)).get.r(request.realm)
        (for(
          pway <- pwid.page orErr "pathway not found";
          tl <- Progress.topicList(pway.uwid) orErr "topic list not found"
        ) yield {
            val p = ROne[Progress]("ownerId" -> au._id, "ownerTopic" -> pway.uwid.grated)
            ROK.k apply {implicit stok=>
              if("yes" == all)
                viewSections(sec, None, tl, pathway, query)
              else
                viewSections(sec, p, tl, pathway, query)
            }
          }) getOrElse unauthorized("oops")
      }) getOrElse
      unauthorized("You need a free account to track your progress. ")
  }

  private def qget (name:String)(implicit q:Map[String,String], errCollector: VErrors) =
    q.get(name) orErr s"missing $name"

  /** make up html sequence for done/skip buttons for drills */
  CodePills.add(s"$PILL/section/buttons") {implicit request=>
    implicit val q = request.ireq.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- request.au
    ) yield {
        (for (
          wid   <- q.get("wid") flatMap WID.fromPath map(_.r(request.realm)) orErr "invalid wpath";
          uwid    <- wid.uwid;
          we <- wid.page;
          p       <- findForTopic(au._id, uwid);
          tl      <- Progress.topicList(p.ownerTopic) orErr "topic list not found";
          name     <- qget("section")
        ) yield {
            val disabled = if(p.isComplete(uwid, Some(wid.copy(section=Some(name))))) "disabled" else ""
            Ok(s"""<a class="btn btn-danger btn-xs $disabled" href="/pill/$PILL/section/done?status=s&section=$name&wid=${we.wid.wpath}">Skip</a>
                   <a class="btn btn-success btn-xs $disabled" href="/pill/$PILL/section/done?status=c&section=$name&wid=${we.wid.wpath}">Done</a>""")
          }) getOrElse pathwayNotStarted
      }) getOrElse Ok("""<b><span style="color:red">{{Login to track progress}}</span></b>""")
//      unauthorized("You need a free account to track your progress.")
  }

  CodePills.add(s"$PILL/section/done") {implicit request=>
    implicit val q = request.ireq.queryString.map(t=>(t._1, t._2.mkString))

    (for (
      au <- request.au
    ) yield {
        (for (
          wid    <- q.get("wid") flatMap WID.fromPath map(_.r(request.realm)) orErr "invalid wpath";
          uwid   <- wid.uwid;
          page   <- wid.page;
          p      <- findForTopic(au._id, uwid);
          tl     <- Progress.topicList(p.ownerTopic) orErr "topic list not found";
          name   <- qget("section")
        ) yield {
            val st = q("status");

          // mark section as done
          val p1 = p.addAndUpdate (uwid, Some(wid.copy(section=Some(name))), st)

          // re-evaluate the topic and mark it done if no other sections outstanding
          // if there are no drills incomplete then mark as complete
          if(
            st == STATUS_COMPLETE &&
            p.rec(uwid, STATUS_READ).isDefined &&
            page.sections.filter{s=>
              Array("GETUP", "ONSNOW").exists(s.signature startsWith _) &&
              !p1.isComplete(page.uwid, Some(s.wid))
            }.isEmpty
          )
            p1.addAndUpdate (uwid, None, STATUS_COMPLETE)

          val completed = if(st == STATUS_COMPLETE) "completed" else "skipped"
          Ok(s"""Ok - $completed $name""")

        }) getOrElse
          pathwayNotStarted

    }) getOrElse
      unauthorized("You need a free account to track your progress.")(request.ireq)
  }

  CodePills.add(s"$PILL/misc/hasMain") {implicit stok=>
    val res = stok.au.flatMap { u =>
      findForUser(u._id) filter
        (x => (x.status == STATUS_IN_PROGRESS || x.status == STATUS_PAUSED)) flatMap
        (_.ownerWid.toList) find
        (_.wpath contains DFLT_PATHWAY)
    }

    if(res.isDefined)
      Ok("").withHeaders("Access-Control-Allow-Origin" -> "*")
    else
      Ok(
        s"""
          <div class="alert alert-warning">
            You did not start any pathway...
            <a class="btn btn-success" href="/wiki/Category/Pathway">See the available pathways</a>
        <br>
        Following the main pathway will guide you through the topics and sessions, in order.
           |</div>
        """.stripMargin).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  CodePills.add("api/wix/realm/count") {implicit stok=>
    Ok(api.wix(None, stok.au, Map.empty, stok.realm).realm.count.toString).withHeaders("Access-Control-Allow-Origin" -> "*")
    // allow is for cross-site scripting
  }

  CodePills.add("api/wix/realm/tagcount") {implicit stok=>
    stok.fqhParm("tag").map { tag=>
      Ok(api.wix(None, stok.au, Map.empty, stok.realm).realm.count.toString).withHeaders("Access-Control-Allow-Origin" -> "*")
    } getOrElse
      Ok("").withHeaders("Access-Control-Allow-Origin" -> "*").withHeaders("Error" -> "you need to pass in a tag in query")
  }


  // ------------ mods

  def modName:String = PILL
  def modProps:Seq[String] = Seq(PILL)
  def isInterested (we:WikiEntry) : Boolean =
    we.tags.contains("improve-skiing")

  /** format a section for display, with done/skip buttons and state */
  def formatSec (we:WikiEntry, cur:WikiSection, what:String, path:String)(implicit stok:StateOk) : String = {
    val (kind, name) = (cur.signature, cur.name)

    val c =
      s"""`{{section $name:$kind}}`${cur.content}
         |<small>Read original <a href="${we.wid.urlRelative(stok.realm)}">topic</a></small> `{{/section}}`""".stripMargin

    // this will callback the modPreFormat below
    val f = Wikis.format(we.wid, we.markup, c, Some(we), stok.au)
    val r = modPostHtmlNoBottom(we, f)
    r
  }

  val SECTIONS = Map("GETUP" -> "GET UP!", "ONSNOW" -> "ON SNOW!", "QUIZ" -> "QUIZ!")

  // replace the sections in content to add blue boxes and stuff
  override def modPreHtml (we:WikiEntry, content:Option[String]) : Option[String] = {
    content.orElse(Some(we.content)).map{c=>
    // this ((?>.*?(?=\{\{/))) means non-greedy lookahead
    //todo use the wiki parser later modifiers to load the sections, not a separate parser here
      // sections with {{` are escaped inside other sections
    val PATT1 = """(?s)`\{\{\.*(section)([: ])?([^:}]*)?(:)?([^}]*)?\}\}`((?>.*?(?=`\{\{/[^`])))`\{\{/\.*(section)?\}\}`""".r //?s means DOTALL - multiline

    def div (kind:String, name:String, content:String) =
      s"""{div.alert.info}<b>$kind</b> <small>$name</small>&nbsp;&nbsp;<div align="right" style="display:inline">${buttons(name)}</div>$content{/div}"""

    def buttons (name:String) = {
//      if(we.sections.find(_.name == name).exists(_.args.contains("when")))
//        ""
//      else
        s"""<span id="$name">...</span>\n"""+
        Wikis.propLater(name, s"/pill/$PILL/section/buttons?section=$name&wid=${we.wid.wpath}")
    }

    PATT1.replaceSomeIn(c, {m=>
      SECTIONS.foldLeft(None.asInstanceOf[Option[String]]) {(o, t) =>
        if(o.isDefined) o
        else if(m.groupCount > 4 && m.group(5).startsWith(t._1)) {
          // the replace $ is because the regexp gets pissed
          Some(div(t._2, m.group(3), /*we.sections.find(_.name == m.group(3)).map(_.args.mkString).mkString + */ m.group(6)).replaceAll("\\$", "\\\\\\$"))
        }
        else None
      }
    })
    }
  }

  // need to temp subst this - the markdown formatter goes nuts if I put div tags pre-html
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
      """">div.later</div> <script>require(['jquery'],function($){$("#"""+
      value+
      s"""").load("/pill/$PILL/next?wpath="""+
      we.map(_.wid.wpath).mkString+
      """");});</script> """
  }

  WikiMods register this

  def apiHistory (forUser:String) = FAUR {implicit stok=>
    val user = if(forUser.length > 0) Users.findUserById(forUser) else stok.au
    var ret = "[]"
    user.map {u=>
      findCurrentForUser(u._id).map {p=>
        ret = js.tojsons(p.records.map (_.toj).toList, 1)
      }
    }
    Ok(ret).as("application/json")
  }

  def apiNext (forUser:String) = FAUR {implicit stok=>
    val user = if(forUser.length > 0) Users.findUserById(forUser) else stok.au
    var ret = "[]"
    user.map {u=>
      findCurrentForUser(u._id).map {p=>
        val tl = Progress.topicList(p.ownerTopic)
        tl.flatMap(_.current(p)).map { cur =>
          cur.wid.flatMap(_.page).map {p=>
            ret = js.tojsons(List(Map(
              "label" -> p.label,
              "wpath" -> p.wid.wpath,
              "url" -> p.wid.urlRelative(stok.realm),
              "id" -> p._id.toString
            )), 1)
          }
        }
      }
    }
    Ok(ret).as("application/json")
  }

}

