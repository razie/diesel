package mod.diesel.controllers

import controllers._
import mod.notes.controllers.{Notes, NotesTags, NotesLocker}
import NotesLocker._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin._
import model._
import razie.db._
import razie.db.RazSalatContext.ctx
import razie.wiki.Sec.EncryptedS
import play.api.mvc._
import razie.wiki.util.VErrors
import razie.{cout, Logging}
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import scala.concurrent.Future
import scala.util.parsing.input.{CharArrayReader, Positional}
import model.dom.DOM
import razie.wiki.model.WikiEntry
import razie.wiki.admin.Audit

/** controller for server side fiddles / services */
object SFiddles extends RazController with Logging {
  import NotesTags._

  def test = "haha"

  def qtojson (q:Map[String,String]) = "{" + q.map(t=>s"""${t._1} : "${t._2}" """).mkString(",") + "}"
  def qtourl (q:Map[String,String]) = q.map(t=>s"""${t._1}=${t._2}""").mkString("&")

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(NotesLocker.book, au._id,SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id,SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      notes.headOption.filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for $path")
      ) {we=>
        val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
        val lang = if(we.tags contains "js") "js" else if(we.tags contains "scala") "scala" else ""
        val (_,res) = isfiddle(script, lang, Some(we))
        Ok(res.toString)
      }
  }

  /** run a fiddle for testing */
  def sfiddle2(id: String) = Action.async { implicit request =>
    implicit val errCollector = new VErrors()
    (for (
      au <- activeUser;
      isA <- checkActive(au)
    ) yield {
        Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
          Future.successful(Ok(s"no sfiddle for id $id"))
        ) {we=>
          val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
          val j = ModTma.razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
          val (_,res) = isfiddle(j, lang)(request,au)
          // special stuff for calling actions
          // todo is this a security hole?
          if(res.isInstanceOf[Action[_]])
            res.asInstanceOf[Action[_]](request).run
          else
            Future.successful(Ok(res.toString))
        }

      }
      ) getOrElse Future.successful(unauthorized("CAN'T"))
  }

  /** run a fiddle */
  private def isfiddle(script: String, lang:String, we:Option[WikiEntry] = None)(implicit request:Request[_], au:User) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    isfiddleMap (script, lang, we, q)
  }

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def isfiddleMap(script: String, lang:String, we:Option[WikiEntry], q:Map[String,String])(implicit request:Request[_], au:User) = {
    if(lang == "js") {
      val qj = qtojson(q)
      api.wix.init(None, Some(au), q, "")
      val wix = api.wix.json
      val jscript = s"""var queryParms = $qj;\n$wix;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("JavaScript")
        val res = engine.eval(jscript)
        Audit.logdb("SFIDDLE_EXEC", "JS", jscript)
        (true, res.toString)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript",t)
          (false, jscript + "\n\n" + t)
        }
      }
    } else if(lang == "ruby") {
      val qj = qtojson(q)
      val jscript = ""//s"""var queryParms = $qj;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("rb")
        val res = engine.eval(jscript)
        Audit.logdb("SFIDDLE_EXEC", "ruby", jscript)
        (true, res.toString)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript",t)
          (false, jscript + "\n\n" + t)
        }
      }
    } else if(lang == "scala") {
      try {
        val res = WikiScripster.impl.runScriptAny(script, we, Some(au), q, true)
        Audit.logdb("SFIDDLE_EXEC", "scala", script)
//        (true, res.toString)
        (true, res)
      } catch {
        case t:Throwable => {
          log(s"while executing script\n$script",t)
//          throw t
          (false, script+ "\n\n" + t)
        }
      }
    } else (false, script)
  }

  /** display the play sfiddle screen */
  def play2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>
      val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
      val j = ModTma.razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
        Ok(s"no sfiddle for ")
      ) {we =>
        Ok(views.html.fiddle.play2(lang, j, q, Some(au)))
      }
  }
}


