package mod.map

import controllers.RazController

/** racer kid info utilities */
object Map extends RazController {

  def simpleMap = FAU { implicit au=> implicit errCollector=> implicit request=>
    ROK.s apply {implicit stok=>
      views.html.modules.map.simpleMap()
    }
  }
}


