package diesel.controllers

import controllers._
import controllers.NotesLocker._
import org.bson.types.ObjectId
import org.joda.time.DateTime
import com.mongodb.casbah.Imports.map2MongoDBObject
import com.mongodb.casbah.Imports.wrapDBObj
import com.novus.salat.grater
import admin._
import model._
import db._
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import play.api.mvc._
import razie.{cout, Logging}
import javax.script.{ScriptEngineManager, ScriptEngine}
import scala.Some
import scala.util.parsing.input.{CharArrayReader, Positional}
import model.dom.DOM

/** controller for server side fiddles / services */
object SFiddles extends RazController with Logging {
  import NotesTags._

  def qtojson (q:Map[String,String]) = "{" + q.map(t=>s"""${t._1} : "${t._2}" """).mkString(",") + "}"
  def qtourl (q:Map[String,String]) = q.map(t=>s"""${t._1}=${t._2}""").mkString("&")

  /** run sfiddles by name, as REST services */
  def sfiddle(path: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      //todo can optimize to look for path at the same time
      val notes = (Notes.notesForTag(au._id,SFIDDLE).toList ::: Notes.sharedNotesByTag(au._id,SFIDDLE).toList).filter(_.content contains s".sfiddle $path")
      val q = request.queryString.map(t=>(t._1, t._2.mkString))

      notes.headOption.filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
	Ok(s"no sfiddle for $path")
      ) {we=>
	val script = we.content.lines.filterNot(_ startsWith ".").mkString("\n")
	val lang = if(we.tags contains "js") "js" else if(we.tags contains "scala") "scala" else ""
	val (_,res) = isfiddle(script, lang, Some(we))
	Ok(res)
      }
  }

  /** run a fiddle for testing */
  def sfiddle2(id: String) = FAU { implicit au =>
    implicit errCollector => implicit request =>

      Some(1).filter(x=>(au hasPerm Perm.codeMaster) || (au hasPerm Perm.adminDb)).fold(
	Ok(s"no sfiddle for id $id")
      ) {we=>
	val lang = request.body.asFormUrlEncoded.get.apply("l").mkString
	val j = ModTma.razscr.dec(request.body.asFormUrlEncoded.get.apply("j").mkString)
	val (_,res) = isfiddle(j, lang)
	Ok(res)
      }
  }

  /** run a fiddle */
  private def isfiddle(script: String, lang:String, we:Option[WikiEntry] = None)(implicit request:Request[_], au:User) = {
    val q = request.queryString.map(t=>(t._1, t._2.mkString))
    isfiddleMap (script, lang, we, q)
  }

  /** run a fiddle with a map of arguments "queryParms" */
  // todo protect calls to this
  def isfiddleMap(script: String, lang:String, we:Option[WikiEntry], q:Map[String,String])(implicit au:User) = {
    if(lang == "js") {
      val qj = qtojson(q)
      val jscript = s"""var queryParms = $qj;\n$script"""
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
	val res = WikiScripster.impl.runScript(script, we, Some(au), q, true)
	Audit.logdb("SFIDDLE_EXEC", "scala", script)
	(true, res.toString)
      } catch {
	case t:Throwable => {
	  log(s"while executing script\n$script",t)
//	    throw t
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


