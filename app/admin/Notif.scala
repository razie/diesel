package admin

import scala.collection.mutable.ListBuffer

trait Notif {
  def entityCreateBefore[A](e: A)(implicit errCollector: VError = IgnoreErrors): Boolean = { true }
  def entityCreateAfter[A](e: A)(implicit errCollector: VError = IgnoreErrors) = {}

  def entityUpdateBefore[A](e: A, what:String)(implicit errCollector: VError = IgnoreErrors): Boolean = { true }
  def entityUpdateAfter[A](e: A, what:String)(implicit errCollector: VError = IgnoreErrors) = {}
}

object Notif {
  val notifieds = new ListBuffer[Notif]()
  
  def add (n:Notif) {notifieds append n}

  def entityCreateBefore[A](e: A)(implicit errCollector: VError = IgnoreErrors): Boolean = { notifieds.foldLeft(true)((x,y)=>x && y.entityCreateBefore(e)(errCollector)) }
  def entityCreateAfter[A](e: A)(implicit errCollector: VError = IgnoreErrors) = { notifieds map (_.entityCreateAfter(e)(errCollector)) }

  def entityUpdateBefore[A](e: A, what:String)(implicit errCollector: VError = IgnoreErrors): Boolean = { notifieds.foldLeft(true)((x,y)=>x && y.entityUpdateBefore(e, what)) }
  def entityUpdateAfter[A](e: A, what:String)(implicit errCollector: VError = IgnoreErrors) = { notifieds map (_.entityUpdateAfter(e, what)) }
}
