package admin

/** configuration settings */
object Config {
  lazy val hostport = System.getProperty("rk.hostport", "localhost:9000")
  lazy val safeMode = System.getProperty("safemode", "none")
  final val SUPPORT = "support@racerkidz.com"
}