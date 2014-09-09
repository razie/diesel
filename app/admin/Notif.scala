/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package admin

import scala.collection.mutable.ListBuffer

/** simple notification observers attempt - should really move to an actor implementation */
trait Notif {
  def entityCreateBefore[A](e: A)(implicit errCollector: VErrors = IgnoreErrors): Boolean = { true }
  def entityCreateAfter[A](e: A)(implicit errCollector: VErrors = IgnoreErrors) = {}

  def entityUpdateBefore[A](e: A, what: String)(implicit errCollector: VErrors = IgnoreErrors): Boolean = { true }
  def entityUpdateAfter[A](e: A, what: String)(implicit errCollector: VErrors = IgnoreErrors) = {}
}

/** simple notification observers attempt */
object Notif {
  val notifieds = new ListBuffer[Notif]()

  def add(n: Notif) { notifieds append n }

  def entityCreateBefore[A](e: A)(implicit errCollector: VErrors = IgnoreErrors): Boolean = { notifieds.foldLeft(true)((x, y) => x && y.entityCreateBefore(e)(errCollector)) }
  def entityCreateAfter[A](e: A)(implicit errCollector: VErrors = IgnoreErrors) = { notifieds map (_.entityCreateAfter(e)(errCollector)) }

  def entityUpdateBefore[A](e: A, what: String)(implicit errCollector: VErrors = IgnoreErrors): Boolean = { notifieds.foldLeft(true)((x, y) => x && y.entityUpdateBefore(e, what)) }
  def entityUpdateAfter[A](e: A, what: String)(implicit errCollector: VErrors = IgnoreErrors) = { notifieds map (_.entityUpdateAfter(e, what)) }
}
