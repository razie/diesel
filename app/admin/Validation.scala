/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import razie.Logging
import play.api.mvc.Request
import play.api.data.validation.Constraint
import play.api.data.validation.Invalid
import play.api.data.validation.ValidationError
import play.api.data.validation.Valid

/** a correction - meaning an error has a known corrective action */
case class Corr(err: String, action: Option[String] = None) {
  def this(e: String, l: String) = this (e, Some(l))
  override def toString = "[" + err + action.map(" -> " + _).getOrElse("") + "]"
  def apply (moreInfo:String) = Corr (s"$err ($moreInfo)", action)
}

/** common validation utilities - utilities and constants for errors/corrections and so on */
trait Validation extends Logging {

  final val cNoAuth = new Corr("Not logged in", """You need to <a href="/doe/join">log in or register</a>!"""); //cNoAuth;
  final val cExpired = new Corr("token expired", "get another token")
  final val cNoProfile = InternalErr("can't load the user profile")
  final def cNoPermission = InternalErr("No permission!")
  final val cDemoAccount = new Corr("This is a demo account", """You need to <a href="/do/signout">logout</a> and create your own account!""")
  final val cAccountNotActive = new Corr("This account is not active", """create a <a href="/doe/support">support request</a> to re-activate!""")
  
  def cNotMember (oname:String) = new Corr(
      "This is a club-members only topic and you are not a club member", 
      """You need to request membership in this club <a href="/wikie/linkuser/Club:%s?wc=0">%s</a>!""".format(oname,oname))
  
  def cNoQuotaUpdates = new Corr(
      "You have exceeded your quota for now - awaiting review.", 
      """You will be notified when your quota has been restored. If you think something is not right, please send a support request!""".format())
  
  object InternalErr {
    def apply(err: String) = Corr (err, Some("""create a <a href="/doe/support">suppport request</a>"""))
  }

  def Nope(msg: String) = { debug("ERR Nope: "+msg); None }

  /** the laziness in orErr/orCorr means you can log security or other issues right there - it only calls it when failing */
  case class OptNope[A](o: Option[A]) {
    def logging (msg: String*) (implicit errCollector: VError = IgnoreErrors) = { log(msg.mkString(",")); this }
    def orErr   (msg: => String) (implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg)) }
    def orCorr  (msg: => Corr) (implicit errCollector: VError = IgnoreErrors): Option[A] = { if (o.isDefined) o else Nope(errCollector.add(msg).err) }
    def map[B]  (f: A => B) (implicit errCollector: VError = IgnoreErrors): Option[B] = { o map f }
  }
  implicit def toON[A](o: Option[A]) = { OptNope[A](o) }
  implicit def toON2(o: Boolean) = { OptNope(if (o) Some(o) else None) }
  implicit def toC2 (t:(String,String)) = Corr(t._1, Some(t._2))

  //============= FORM validations

//  def vPorn: Constraint[String] = Constraint[String]("constraint.noporn") { o =>
//    if (Wikis.hasporn(o))
//      Invalid(ValidationError("Failed obscenity filter, eh?")) else Valid
//  }
  def vPostalCode: Constraint[String] = Constraint[String]("constraint.postalCode") { o =>
    if (o.length > 0 && !o.toUpperCase.matches("[A-Z]\\d[A-Z] \\d[A-Z]\\d"))
      Invalid(ValidationError("postalCode should be like A1A 2B2, eh?")) else Valid
  }
  def vSpec: Constraint[String] = Constraint[String]("constraint.specialChars") { o =>
    if (o.contains('<') || o.contains('>'))
      Invalid(ValidationError("specialChars not allowed, eh?")) else Valid
  }
  def vEmail: Constraint[String] = Constraint[String]("constraint.emailFormat") { o =>
    if (o.length > 0 && !o.matches("[^@]+@[^@]+\\.[^@]+"))
      Invalid(ValidationError("email format is bad, eh?")) else Valid
  }

  def vldSpec(s: String) = !(s.contains('<') || s.contains('>'))
  def vldEmail(s: String) = s.matches("[^@]+@[^@]+\\.[^@]+")
}

/** collecting errors - the idea is that errors are collected throughout the code and then presented at the end */
class VError(var err: List[Corr] = Nil) {
  def hasCorrections = err.foldLeft(false)((a,b)=>a || b.action.isDefined)
  def add(s: Corr) = { err = err ::: s :: Nil; s }
  def add(s: String) = { err = err ::: Corr(s) :: Nil; s }
  def reset { err = Nil }

  def mkString = err.mkString("<br>")
}

/** void error collector */
object IgnoreErrors extends VError with Logging {
  override def add (s: Corr) = { debug ("IGNORING error: " + s); s }
  override def add (s: String) = { debug ("IGNORING error: " + s); s }
}

