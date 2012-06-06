package model

object WikiPath {

}

import razie._

abstract class WWrapper(val cat: String) {
  /** make the proper ILink for this element */
  def mkLink: ILink
}

class WikiWrapper(override val cat: String, val name: String) extends WWrapper(cat) {
  lazy val w = Wikis.find(cat, Wikis.formatName(name))

  lazy val ilinks = (w.map(_.ilinks.map(ilink => {
    if (ilink.cat == "any") Wikis.findAnyOne(ilink.name).map(w => ILink(w.category, w.name, w.label))
    else Some(ilink)
  })).getOrElse(Nil)).flatMap(_.toList)

  def tags = w.map(_.tags).getOrElse(Map())

  def mkLink = ILink (cat, name, w.map(_.label).getOrElse(name))

  override def toString = "WikiWrapper(" + cat + "," + name + ")"
}

case class IWikiWrapper(val ilink: ILink) extends WikiWrapper(ilink.cat, ilink.name) {
  override def mkLink = ilink
  override def tags = w.map(_.tags).getOrElse(ilink.tags)
}

/** NOTE that JSON xpath must start with "/root/..."
 *
 *  resolving JSON structures
 *
 *  NOTE to use JSON you need the json library, add this SBT/maven dependency:
 *
 *  val json = "org.json" % "json" % "20090211"
 *
 *  In Eclipse, pick up this library from lib_managed/
 */
object WikiXpSolver extends XpSolver[WWrapper] {

  type T = WWrapper
  type CONT = List[WWrapper]
  type U = CONT

  import razie.Debug._

  override def children(root: T): (T, U) =
    root match {
      case x: WikiWrapper => (x, children2(x, "*").toList.tee("C").asInstanceOf[U])
      case _              => throw new IllegalArgumentException()
    }

  override def getNext(o: (T, U), tag: String, assoc: String): List[(T, U)] =
    // 1. all children of type
    o._2.asInstanceOf[List[WWrapper]].filter(zz => XP.stareq(zz.asInstanceOf[WWrapper].cat, tag)).tee("D").flatMap (_ match {
      // 2. wrap them
      case x: WikiWrapper => (x, children2(x, "*").toList.asInstanceOf[U]) :: Nil
    }).tee("E").toList

  private def children2(node: WWrapper, tag: String): Seq[WWrapper] = {
    val x = node match {
      case b: WikiWrapper => {
        b.ilinks filter ("*" == tag || tag == _.cat) map (n => Tuple2(n.cat, n)) flatMap (t => t match {
          case (name: String, o: ILink) => IWikiWrapper(o) :: Nil
          case _ => Nil
        })
      }
      case what @ _ => throw new IllegalArgumentException("Unsupported json type here: " + what)
    }
//    println("KKKKKKKKKKKKKKKK"+x)
    x tee "KK"
  }

  override def getAttr(o: T, attr: String): String = {
    val ret = o match {
      case o: IWikiWrapper => o.tags.get(attr).getOrElse("")
      case o: WikiWrapper  => o.w.flatMap(_.tags.get(attr)).getOrElse("")
      case _               => null
    }
    ret.toString
  }

  override def reduce(curr: Iterable[(T, U)], xe: XpElement): Iterable[(T, U)] =
    (xe.cond match {
      case null => curr.asInstanceOf[List[(T, U)]]
      case _    => curr.asInstanceOf[List[(T, U)]].filter(x => xe.cond.passes(x._1, this))
    }).filter(gaga => XP.stareq(gaga._1.asInstanceOf[WWrapper].cat, xe.name))

}

object TestSWA extends App {
  // val node = new WikiWrapper("Club", "Offroad_Ontario") 
  val node = new WikiWrapper("Season", "OO_Enduro_2012")
  val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

  // println (root)
  // println (root \ "*" \ "*")
  println (root \ "*" \ "Race" \@ "date")
}