package admin

import razie.Logging
import play.api.mvc.Request

//=================== collecting errors 
class VError(var err: List[Corr] = Nil) {
  def hasCorrections = err.foldLeft(false)((a,b)=>a || b.action.isDefined)
  def add(s: Corr) = { err = err ::: s :: Nil; s }
  def add(s: String) = { err = err ::: Corr(s) :: Nil; s }
  def reset { err = Nil }

  def mkString = err.mkString("<br>")
}

case class Corr(err: String, action: Option[String] = None) {
  def this(e: String, l: String) = this (e, Some(l))
  override def toString = "[" + err + action.map(" -> " + _).getOrElse("") + "]"
}

/** common validation utilities */
trait Validation extends Logging {

  final val cNoAuth = new Corr("Not logged in", """You need to <a href="/doe/join">log in or register</a>!"""); //cNoAuth;
  final val cExpired = new Corr("token expired", "get another token")
  final val cNoProfile = InternalErr("can't load the user profile")
  final val cNoPermission = InternalErr("No permission!")
  
  def cNotMember (oname:String) = new Corr(
      "This is a club-members only topic and you are not a club member", 
      """You need to request membership in this club <a href="/wikie/linkuser/Club:%s?wc=0">%s</a>!""".format(oname,oname))
  

  def cNoQuotaUpdates = new Corr(
      "You have exceeded your quota for now - awaiting review.", 
      """You will be notified when your quota has been restored. If you think something is not right, please send a support request!""".format())
  

  def checkActive(au: model.User)(implicit errCollector: VError = IgnoreErrors) = toON2(au.isActive) orCorr (
    if (au.userName == "HarryPotter")
      new Corr("This is a demo account", """You need to <a href="/do/signout">logout</a> and create your own account!""")
    else
      new Corr("This account is not active", """create a <a href="/doe/support">support request</a> to re-activate!"""))

  object InternalErr {
    def apply(err: String) = Corr (err, Some("""create a <a href="/doe/support">suppport request</a>"""))
  }

  def Nope(msg: String) = { error(msg); None }

  /** the laziness in orErr/orCorr means you can log security or other issues right there - it only calls it when failing */
  case class OptNope[A](o: Option[A]) {
    def logging(msg: String*)(implicit errCollector: VError = IgnoreErrors) = { log(msg.mkString(",")); this }
    def orErr(msg: => String)(implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg)) }
    def orCorr(msg: => Corr)(implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg).err) }
  }
  implicit def toON[A](o: Option[A]) = { OptNope[A](o) }
  implicit def toON2(o: Boolean) = { OptNope(if (o) Some(o) else None) }
  implicit def toC2 (t:(String,String)) = Corr(t._1, Some(t._2))

}

/** void error collector */
object IgnoreErrors extends VError with Logging {
  override def add(s: Corr) = { debug ("IGNORING error: " + s); s }
  override def add(s: String) = { debug ("IGNORING error: " + s); s }
}

