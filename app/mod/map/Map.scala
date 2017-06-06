package mod.map

import controllers.RazController

/** racer kid info utilities */
object Map extends RazController {

  def simpleMap = FAUR { implicit request=>
    ROK.k apply {
      views.html.modules.map.simpleMap()
    }
  }
}


