package admin

/** all audit events - some of these may end up as emails or alerts.
 *
 *  TODO should have a configurable workflow for each of these - what's the pattern?
 */
object Audit {
  def log(msg: String*) {}
  def alert(msg: String*) {}

  def missingPage(url: String) { alert(url) }

  def userNew(email: String) { log(USER_NEW, email) }
  def userLogin(email: String) { log(USER_LOGIN, email) }
  def userLogout(email: String) { log(USER_LOGOUT, email) }

  final val USER_NEW = "USER_NEW"
  final val USER_LOGIN = "USER_LOGIN"
  final val USER_LOGOUT = "USER_LOGOUT"
}