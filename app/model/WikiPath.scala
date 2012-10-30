package model

object WikiPath {

}

import razie._

/** base class - can have a topic wrapper or a link wrapper */
abstract class WWrapper(val cat: String) {
  /** make the proper ILink for this element */
  def mkLink: ILink
  
  /** get the associated page, if any */
  def page : Option[WikiEntry]
}

class WikiWrapper(val wid:WID) extends WWrapper(wid.cat) {
  lazy val w = Wikis.find(wid)

  /** links from page only, if defined */
  protected lazy val wilinks = (w.map(realw=> realw.ilinks.map {ilink => 
    if (ilink.wid.cat == "any") Wikis.findAnyOne(ilink.wid.name).map(w => ILink(w.wid, w.label))
    else Some(ilink)
  }.flatMap(_.toList) ++ lfrom ++ lto ++ BADlto))

  // TODO optimize
  protected def lfrom = w.toList.flatMap(realw=>Wikis.linksFrom(realw.wid)).map(x=>new ILink(x.to, x.to.name))
  protected def lto = w.toList.flatMap(realw=>Wikis.linksTo(realw.wid)).map(x=>new ILink(x.from, x.from.name))
  
  // TODO this is like extremely bad !!!
  protected def BADallPages = Wikis.pageNames("Category").flatMap(cat=>Wikis.pageNames(cat).flatMap(name=>Wikis.find(cat, name).toList)).toList
  
  protected def BADlto = 
    if (admin.Config.sitecfg("searchall").isDefined)
    BADallPages.filter(_.ilinks.exists(_.wid.formatted.name == wid.name)).map(realw=>new ILink(realw.wid, realw.label))
    else Nil
//  protected def BADlto = BADallPages.map(realw=>new ILink(realw.wid, realw.label))
  
  lazy val ilinks = wilinks.toList.flatMap(_.toList)

  def tags = w.map(_.contentTags).getOrElse(Map())

  def mkLink = ILink (wid, w.map(_.label).getOrElse(wid.name))

  override def toString = "WikiWrapper(" + wid + ")"
  
  override def page : Option[WikiEntry] = w
}

case class IWikiWrapper(val ilink: ILink) extends WikiWrapper(ilink.wid) {
  override def mkLink = ilink
  override def tags = w.map(_.contentTags).getOrElse(ilink.tags)
  
  override lazy val ilinks = wilinks.getOrElse(ilink.ilinks)
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
        b.ilinks filter ("*" == tag || tag == _.wid.cat) map (n => Tuple2(n.wid.cat, n)) flatMap (t => t match {
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
      case o: WikiWrapper  => o.w.flatMap(_.contentTags.get(attr)).getOrElse("")
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
  val node = new WikiWrapper(WID("Calendar", "OO_Enduro_2012"))
  val root = new razie.Snakk.Wrapper(node, WikiXpSolver)

  // println (root)
  // println (root \ "*" \ "*")
  println (root \ "*" \ "Race" \@ "date")
}