package mod.map

import model.{User, Website}
import play.api.mvc.{Result, Request, Action}
import razie.wiki.mods.{WikiMods, WikiMod}

import org.joda.time.DateTime
import com.mongodb.casbah.Imports._
import admin.{Config}
import controllers.{RazController, CodePills, Club}

/** racer kid info utilities */
object Map extends RazController {

  def simpleMap = FAU { implicit au=> implicit errCollector=> implicit request=>
    ROK.s apply {implicit stok=>
      views.html.modules.map.simpleMap()
    }

  }

  // ----------------- pills

//  CodePills.addString ("mod.flow/sayhi") {implicit request=>
//    "ok"
//  }
}


