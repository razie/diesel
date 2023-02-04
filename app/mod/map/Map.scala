package mod.map

import controllers.RazController
import com.google.inject.Singleton

/** racer kid info utilities */
@Singleton
class Map extends RazController {

  def simpleMap = FAUR { implicit request=>
    ROK.k apply {
      views.html.modules.map.simpleMap()
    }
  }
}


