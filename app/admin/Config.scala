package admin

/** configuration settings */
object Config {
  lazy val hostport = System.getProperty("rk.hostport", "localhost:9000")
  lazy val safeMode = System.getProperty("safemode", "none")
  final val SUPPORT = "support@racerkidz.com"
  final val mongodb = System.getProperty("mongodb", "rk")
  final val mongohost = System.getProperty("mongohost", "localhost")
  final val mongouser = System.getProperty("mongouser", "r")
  final val mongopass = System.getProperty("mongopass", "r")
  final val analytics = System.getProperty("analytics", "false").toBoolean
  
  final val forcePhone = System.getProperty("forcePhone", "false").toBoolean
  
}

