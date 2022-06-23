/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.utils

import razie.diesel.engine.DomState

/** DOM Html helpers
  */
object DomHtml {

  def quickBadge(failed:Int, total:Int, duration:Long, all:String="", status:String="") = {
    if (failed > 0) {
      val color = if(status == DomState.STARTED) "orange" else "red"
        s"""<a href="/diesel/guard/report$all"><span class="badge" style="background-color: $color" title="Guardian: tests failed ($duration msec)">$failed / $total </span></a>"""
    } else if(total > 0) {
      val color = if(status == DomState.STARTED) "orange" else "green"
      s"""<a href="/diesel/guard/report$all"><span class="badge" style="background-color: $color" title="Guardian: all tests passed ($duration msec)">$total </span></a>"""
    } else
      s"""<a href="/diesel/guard/report$all"><span class="badge" style="background-color: orange" title="No tests run!"><small>... </small></span></a>"""
  }

}
