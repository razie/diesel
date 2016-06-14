package mod.diesel.controllers

import java.io.File
import java.util

import controllers._
import difflib.{Patch, DiffUtils}
import mod.diesel.model.RDExt._
import mod.diesel.model._
import mod.notes.controllers.{Notes, NotesTags, NotesLocker}
import org.antlr.v4.tool.{ANTLRMessage, ANTLRToolListener}
import admin._
import model._
import org.scalatest.fixture
import razie.db._
import razie.db.RazSalatContext.ctx
import razie.diesel.RDOM.O
import razie.diesel.RDomain
import razie.wiki.Enc
import razie.wiki.Sec.EncryptedS
import play.api.mvc._
import razie.wiki.dom.WikiDomain
import razie.wiki.util.VErrors
import razie.{js, cout, Logging}
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Try
import scala.util.parsing.input.{CharArrayReader, Positional}
import razie.wiki.model.{WID, Wikis, WikiEntry, WikiUser}
import razie.wiki.admin.Audit

/** controller for server side fiddles / services */
object DomFiddles extends SFiddleBase  with Logging {

  /** display the play sfiddle screen */
  def playDom(reactor: String, iSpecWpath:String, iStoryWpath:String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        //1. which wids were you looking at last?
        val wids = Autosave.OR("DomFidPath."+reactor, au._id, Map(
          "specWpath"  -> """""",
          "storyWpath" -> """"""
        ))

        var specWpath = wids("specWpath")
        var storyWpath = wids("storyWpath")

        // need settings?
        if(iSpecWpath != "?" && iSpecWpath != specWpath) {
          Autosave.set("DomFidPath."+reactor, au._id, Map(
            "specWpath"  -> iSpecWpath,
            "storyWpath" -> storyWpath
          ))
          specWpath = iSpecWpath
        }

        // need settings?
        if(iStoryWpath != "?" && iStoryWpath != storyWpath) {
          Autosave.set("DomFidPath."+reactor, au._id, Map(
            "specWpath"  -> specWpath,
            "storyWpath" -> iStoryWpath
          ))
          storyWpath = iStoryWpath
        }

        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
        val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

        //2 their contents
        val spec = Autosave.OR("DomFidSpec."+reactor+"."+specWpath, au._id, Map(
          "content"  -> spw
        )).apply("content")

        val story = Autosave.OR("DomFidStory."+reactor+"."+storyWpath, au._id, Map(
          "content"  -> stw
        )).apply("content")

        val id = java.lang.System.currentTimeMillis().toString()

        ROK() reactorLayout12 { implicit stok =>
          views.html.fiddle.playDomFiddle(reactor, q, spec, story, specWpath, storyWpath, (spw != story), (stw != story), auth, Some(""), id)
        }
      }
  }

  object retj {
    def <<(x: List[Any]) = Ok(js.tojsons(x, 0).toString).as("application/json")
    def <<(x: Map[String, Any]) = Ok(js.tojson(x).toString).as("application/json")
  }

  /** list cats */
  def domListCat(cat: String, reactor:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val what=cat.toLowerCase
    Ok(
      s"""<a href="/dfiddle/playDom/$reactor?$what=">none (fiddle)</a><br>"""+
      Wikis(reactor).pageNames(cat).map(s=>
      s"""<a href="/dfiddle/playDom/$reactor?$what=$reactor.$cat:$s">$s</a>"""
    ).mkString("<br>")
    )
  }

  /** display the play sfiddle screen */
  def buildDom1(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = razscr.dec(request.body.asFormUrlEncoded.get.apply("reactor").mkString)
    val specWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("specWpath").mkString)
    val storyWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("storyWpath").mkString)
    val spec = razscr.dec(request.body.asFormUrlEncoded.get.apply("spec").mkString)
    val story = razscr.dec(request.body.asFormUrlEncoded.get.apply("story").mkString)

    //1. which wids were you looking at last?
    Autosave.set("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    //2 their contents
    Autosave.set("DomFidSpec."+reactor+"."+specWpath, au._id, Map(
      "content"  -> spec
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    val page = new WikiEntry("temp", "temp", "temp", "md", spec, au._id, Seq("dslObject"), "")
    val dom = WikiDomain.domFrom(page).get.revise addRoot

    var res = Wikis.format(page.wid, page.markup, null, Some(page), auth)
    retj << Map(
      "res" -> res,
      "ca" -> RDExt.toCAjmap(dom),
    "specChanged" -> (specWpath.length > 0 && spw.replaceAllLiterally("\r", "") != spec)
    )
  }

  /** compute the instance
    *
    * todo perf: DB - parsing about 50-50
    * todo perf actor for async queued DB updates
    * todo perf specialized parser with just the DOM rules and no wiki/markdown, using WikiParserMini instead of WikiParserT
    *
    * @param id
    * @return
    */
  def buildDom2(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String)= razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val sketchMode = fParm("sketchMode").toBoolean
    val mockMode = fParm("mockMode").toBoolean
    val blenderMode = fParm("blenderMode").toBoolean
    val draftMode = fParm("draftMode").toBoolean
    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    //1. which wids were you looking at last?
    val wids = Autosave.set("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    //2 their contents
    Autosave.set("DomFidStory."+reactor+"."+storyWpath, au._id, Map(
      "content"  -> story
    ))

    val page = new WikiEntry("temp", "temp", "temp", "md", spec, au._id, Seq("dslObject"), "")
    val dom = if(blenderMode) {
      val spw = WID.fromPath(specWpath)
      val d = Wikis(reactor).pages("Spec").filter(_.name != spw.get.name).toList.map{
        // if draft mode, find the auto-saved version if any
        p=> if(draftMode) {
          val c = Autosave.find("DomFidSpec."+reactor+"."+p.wid.wpath, au._id).flatMap(_.get("content")).mkString
          if(c.length > 0)  p.copy(content=c)
          else p
        } else p
      }.flatMap(
        // to domain
        p=> WikiDomain.domFrom(p).toList
      ).foldLeft(WikiDomain.domFrom(page).get)((a,b) => a.plus(b)).revise.addRoot
      d
    } else
      WikiDomain.domFrom(page).get.revise addRoot

    val ipage = new WikiEntry("temp", "temp", "temp", "md", story, au._id, Seq("dslObject"), "")
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    var res = ""

    val root=DomAst("root", "root")

    // not again.. take from dom
    root.children appendAll WikiDomain.domFilter(ipage) {
      case o:O if o.name != "context" => DomAst(o, "input")
      case v:EMsg => DomAst(v, "input")
      case v:EMock => DomAst(v, "mock")
      case e:ExpectM => DomAst(e, "test")
      case e:ExpectV => DomAst(e, "test")
    }

    // start processing all elements
    val engine = new DomEngine(dom, root, mockMode, sketchMode)

    root.children.foreach(engine.expand(_, true, 1))
    res += root.toString

    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    //    val diff =
    //      if (stw == story) 0
    //      else {
    //        import scala.collection.JavaConversions._
    //        val p = DiffUtils.diff(stw.lines.toList, story.lines.toList)
    //        p.getDeltas().size();
    //      }

    retj << Map(
      "res" -> res,
      "ca" -> RDExt.toCAjmap(dom plus idom),
      "failureCount" -> (root.collect {
        case d@DomAst(n:TestResult, _, _) if n.value.startsWith("fail") => n
      }).size,
      "storyChanged" -> (storyWpath.length > 0 && stw.replaceAllLiterally("\r", "") != story)
    )
  }

  /** display the play sfiddle screen */
  def diffDom(id: String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    def fParm(name:String)= razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    //1. which wids were you looking at last?
    val wids = Autosave.set("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
    val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")

    import scala.collection.JavaConversions._

    def diffTable (p:Patch) = s"""<small>${views.html.admin.diffTable(p, Some(("How", "Orig", "Autosaved")))}</small>"""

    def diffT = diffTable(DiffUtils.diff(stw.lines.toList, story.lines.toList))
    def diffP = diffTable(DiffUtils.diff(spw.lines.toList, spec.lines.toList))

    retj << Map(
      "specDiff" -> (if(specWpath.length > 0) diffP else ""),
      "storyDiff" -> (if(storyWpath.length > 0) diffT else "")
    )
  }

  /** display the play sfiddle screen */
  def save(id: String, what:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    /** get parm from formrequest body or empty string */
    def fParm(name:String) =
      razscr.dec(request.body.asFormUrlEncoded.get.apply(name).mkString)


    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val newName = fParm("newName")
    val reactor = fParm("reactor")
    val specWpath = fParm("specWpath")
    val storyWpath = fParm("storyWpath")
    val spec = fParm("spec")
    val story = fParm("story")

    //1. which wids were you looking at last?
    val wids = Autosave.set("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    import razie.db.tx.txn

    if(newName.length > 0) {
      if(what == "Spec") {
        var we = WikiEntry("Spec", newName, newName, "md", spec, au._id, Seq("spec", "dsl"), Website.realm, 1)
        we.create
      } else if (what == "Story") {
        var we = WikiEntry("Story", newName, newName, "md", story, au._id, Seq("story", "dsl"), Website.realm, 1)
        we.create
      }
    } else {
      if(what == "Spec") {
        val spw = WID.fromPath(specWpath).flatMap(_.page)
        spw.map(_.update(spw.get.copy(content=spec), Some("saved diesel fiddle")))
      } else if (what == "Story") {
        val stw = WID.fromPath(storyWpath).flatMap(_.page)
        stw.map(_.update(stw.get.copy(content=story), Some("saved diesel fiddle")))
      }
    }

    Ok("done")
  }

  /** revert the temp/auto to the original */
  def revert(id: String, what:String) = FAU { implicit au => implicit errCollector => implicit request =>
    val q = request.queryString.map(t=>(t._1, t._2.mkString))

    //todo this
    //    Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(

    val reactor = razscr.dec(request.body.asFormUrlEncoded.get.apply("reactor").mkString)
    val specWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("specWpath").mkString)
    val storyWpath = razscr.dec(request.body.asFormUrlEncoded.get.apply("storyWpath").mkString)
    val spec = razscr.dec(request.body.asFormUrlEncoded.get.apply("spec").mkString)
    val story = razscr.dec(request.body.asFormUrlEncoded.get.apply("story").mkString)

    //1. which wids were you looking at last?
    val wids = Autosave.set("DomFidPath."+reactor, au._id, Map(
      "specWpath"  -> specWpath,
      "storyWpath" -> storyWpath
    ))

    if(what == "Spec") {
      //2 their contents
      val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse("Sample spec\n\n$when home.guest_arrived(name) => lights.on\n")
      Autosave.set("DomFidSpec."+reactor+"."+specWpath, au._id, Map(
        "content"  -> spw
      ))
    } else if (what == "Story") {
      //2 their contents
      val stw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse("Sample story\n\n$msg home.guest_arrived(name=\"Jane\")\n\n$expect $msg lights.on\n")
      Autosave.set("DomFidStory."+reactor+"."+storyWpath, au._id, Map(
        "content"  -> stw
      ))
    }

    Ok("done")
  }

}


