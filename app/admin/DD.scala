package admin

import org.joda.time.DateTime

  object GlobalData {
    // how many requests were served
    var served = 0L

    // how many threads are currently serving - if 0, there's none...
    var serving = 0
    
    val startedDtm = DateTime.now
  }

