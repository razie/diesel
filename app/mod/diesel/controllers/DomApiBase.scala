package mod.diesel.controllers

import controllers._
import model._
import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.SimpleECtx
import razie.wiki.Services
import razie.wiki.admin.Autosave
import razie.wiki.model._
import razie.wiki.util.PlayTools

/** controller for server side fiddles / services */
class DomApiBase extends mod.diesel.controllers.SFiddleBase  with Logging {

  final val NOUSER = new ObjectId()

  /** api - get content for a WID - either from page or autosaved */
  def getContent(what: String, wid: CMDWID) = FAUPR { implicit stok =>
    val DFS = if (what.toLowerCase == "spec") "DomFidSpec." else "DomFidStory."
    val spw = wid.wid.flatMap(_.page).map(_.content).getOrElse("")

    val ospec = wid.wpath.flatMap(specWpath => Autosave.find(DFS + stok.realm + "." + specWpath, stok.au.get._id).map(_.apply("content")))

    Ok(ospec getOrElse spw)
  }

  /** list cats */
  def domListCat(cat: String, reactor: String) = FAUR { implicit stok =>
    val what = cat.toLowerCase
    Ok(
      s"""<a href="/diesel/fiddle/playDom?$what=">none (fiddle)</a><br>""" +
        Wikis(reactor).pageNames(cat).map(s =>
          s"""<a href="/diesel/fiddle/playDom?$what=$reactor.$cat:$s">$s</a>"""
        ).mkString("<br>")
    )
  }

  def setHostname(ctx: SimpleECtx)(implicit stok: RazRequest): Unit = {
    ctx._hostname =
      Some(
        // on localhost, it shouldn't go out
        if (Services.config.isLocalhost) "localhost:9000"
        else PlayTools.getHost(stok.req).mkString
      )
  }
}


