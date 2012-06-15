package admin

import razie.Logging

  //=================== collecting errors 
  class VError(var err: List[Corr] = Nil) {
    def add(s: Corr) = { err = err ::: s :: Nil; s }
    def add(s: String) = { err = err ::: Corr(s) :: Nil; s }
    def reset { err = Nil }

    def mkString = err.mkString(",")
  }

  case class Corr(err: String, action: Option[String] = None) {
    def this(e: String, l: String) = this (e, Some(l))
    override def toString = "[" + err + action.map(" -> " + _).getOrElse("") + "]"
  }

/** common validation utilities */
trait Validation extends Logging {

  final val cNoAuth = new Corr("Not logged in", "Sorry - need to log in!"); //cNoAuth;
  final val cExpired = new Corr("token expired", "get another token")
  final val cNoProfile = InternalErr("can't load the user profile")
  final val cNoPermission = InternalErr("No permission!")

  object InternalErr {
    def apply(err: String) = Corr (err, Some("create a suppport request"))
  }

  def Nope(msg: String) = { error(msg); None }

  /** the laziness in orErr/orCorr means you can log security or other issues right there - it only calls it when failing */
  case class OptNope[A](o: Option[A]) {
    def logging(msg:String*)(implicit errCollector: VError = IgnoreErrors) = { log(msg.mkString(",")); this }
    def orErr(msg: => String)(implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg)) }
    def orCorr(msg: => Corr)(implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg).err) }
  }
  implicit def toON[A](o: Option[A]) = { OptNope[A](o) }
  implicit def toON2(o: Boolean) = { OptNope(if (o) Some(o) else None) }

}

/** void error collector */
object IgnoreErrors extends VError with Logging {
  override def add(s: Corr) = { log ("IGNORING error: " + s); s }
  override def add(s: String) = { log ("IGNORING error: " + s); s }
}

