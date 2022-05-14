/**
  *  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.dom.RDOM.P

/**
  * a generic parm source - use it to dynamically source parms from a sub-object like "diesel.xxx" or else.
  *
  * Could be a static/dynamic map, settings, proxy to objects etc
  */
trait ParmSource {
  /** name of this source */
  def name: String

  /** remove a value */
  def remove(name: String): Option[P]

  /** get a value */
  def getp(name: String): Option[P]

  /** set a value */
  def put(p: P): Unit

  /** list values */
  def listAttrs: List[P]

  /** itself as a P */
  def asP: P = P.fromSmartTypedValue(name, listAttrs.map(x => (x.name, x)).toMap)
}
