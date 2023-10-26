package mod.diesel.controllers

import com.google.inject.Singleton
import controllers.{RazController, RazRequest}
import mod.diesel.model.DomEngineHelper
import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.{RDomain, WikiDomain}
import razie.diesel.engine.RDExt
import razie.diesel.engine.nodes.EnginePrep
import razie.diesel.utils.SpecCache
import razie.wiki.model._
import scala.io.Source

/** controller for server side fiddles / services */
@Singleton
class DomSwagger extends RazController with Logging {

  /** display the play sfiddle screen */
  def collectDom (stok:RazRequest, scope:String) = {
    if(scope == "local") {
      val pages = Wikis(stok.realm).pages("Spec").toList

      val dom = pages.flatMap(p =>
        SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
      ).foldLeft(
        RDomain.empty
      )((a, b) => a.plus(b)).revise.addRoot

      dom
    } else {
      val engine = EnginePrep.prepEngine(
        new ObjectId().toString,
        DomEngineHelper.settingsFrom(stok),
        stok.realm,
        None,
        false,
        stok.au,
        "DomApi.navigate")

      engine.dom
    }
  }

  /** display the play sfiddle screen */
  def swaggerMsgJson(scope:String = "local") = FAUR { implicit stok =>
    val dom = collectDom(stok, scope)

    val msgs = RDExt.summarizeSwagger(dom, rest = false, title=s"All messages ($scope)")

    retj << msgs
  }

  /** display the play sfiddle screen */
  def swaggerRestJson(scope:String = "local") = FAUR { implicit stok =>
    val dom = collectDom(stok, scope)

    val msgs = RDExt.summarizeSwaggerRest(dom, rest = false, title=s"Rest messages ($scope)")

    retj << msgs
  }

  /** display the play sfiddle screen */
  def swaggerJson() = FAUR { implicit stok =>
    val reactor = stok.realm

    val id = java.lang.System.currentTimeMillis().toString()

    val j = Source.fromURL("https://gist.githubusercontent.com/lenage/08964335de9064540c8c335fb849c5da/raw/6d63e3546897356882ed7e30cd48891a24e2b354/feature.swagger.json").mkString

    Ok (j).as("text/json")
  }

  /**
   * show ui
   */
  def swaggerUi(what:String = "msg") = FAUR { implicit stok =>

    val url = s"/diesel/swagger/${what}.json"

    ROK.k noLayout {
      views.html.modules.diesel.swaggerUi(url)
    }
  }
}


