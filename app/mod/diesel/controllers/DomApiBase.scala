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
    val spw = wid.wid.flatMap(_.page).map(_.content).getOrElse("")

    val ospec = wid.wpath.flatMap(specWpath => Autosave.find("wikie", WID.fromPathWithRealm(specWpath, stok.realm).get, stok.au.get._id).map(_.apply("content")))

    Ok(ospec getOrElse spw)
  }

  /** list cats */
  def domListCat(cat: String, reactor: String) = FAUR { implicit stok =>
    val what = cat.toLowerCase
    val names = Wikis(reactor).pageNames(cat).toList.sorted

    val isStory = ("Story" == cat)

    def spec (n:String) =
      if("Story" == cat) Wikis(reactor).find("Spec", n) else None

    def hasDraft (w:WID):String =
      if(Autosave.findAll("wikie", w, stok.au.get._id)
        .exists(x=> w.content.exists(_ != x.contents("content")))) " (*)" else ""

    // list the storyes anskipd specs side by side
    Ok(
        (s"""<tr><td><a href="/diesel/fiddle/playDom?$what=">none (fiddle)</a></td></tr>""" ::
       names.map(s =>
          s"""<tr><td><a href="/diesel/fiddle/playDom?$what=$reactor.$cat:$s">$s</a></td>""" +
          spec(s.replaceFirst("story", "spec")).map(sp=>
            s"""<td><a href="/diesel/fiddle/playDom?$what=$reactor.$cat:$s&spec=$reactor.Spec:${sp.name}">${sp.name}</a></td>"""
          ).getOrElse("<td></td>") + "</tr>"
//        ).mkString("<br>") +
        )
          ).mkString("""<table class="table table-condensed">""", "", "</table>")
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


