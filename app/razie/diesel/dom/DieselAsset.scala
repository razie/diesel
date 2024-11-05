/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.diesel.dom.RDOM.{O, P}
import razie.tconf.{FullSpecRef, TSpecRef}

/**
  * wrapper asset class as a value object - it's not just the reference but the entire object
  *
  * the inventory is responsible to create an O representation
  *
  * @param ref    - reference to asset
  * @param value  - value object, not null
  * @param valueO - if there is an pre-built O model
  * @tparam T
  */
case class DieselAsset[T](
  ref: TSpecRef,
  value: T,
  valueO: Option[O] = None
) {

  // make sure it has a ref
  valueO.filter(_.ref.isEmpty && ref.isInstanceOf[FullSpecRef]).foreach(_.withRef(ref.asInstanceOf[FullSpecRef]))

  /** get the valoue object as an O */
  def getValueO: Option[O] =
    valueO.orElse(
      if (value != null && value.isInstanceOf[O])
        Option(value.asInstanceOf[O])
      else
        None
    )

  /** get the value object as a P */
  def getValueP: P =
    if (value != null && value.isInstanceOf[P])
      value.asInstanceOf[P]
    else
      asP

  /** return this as a regular P */
  def asP = {
    val p = P.fromSmartTypedValue(ref.category, getValueO.get.toJson)
    p.withSchema(ref.category)
  }
}
