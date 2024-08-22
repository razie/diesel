/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import razie.Debug._
import razie._
import razie.audit.Audit
import razie.diesel.dom.RDOM.P
import razie.diesel.expr.{ECtx, Expr}
import razie.tconf.SpecRef

/** an xpath expr resolved with diesel ki categories AND/OR domain and inventory elements
  *
  * @param prefix is one of xp, xpl, xpa, xpla
  * @param expr is the rest of the expression
  */
case class XPathIdent (val prefix:String, override val expr: String) extends Expr {
  val g = GPath(expr)

  override def apply(v: Any)(implicit ctx: ECtx) = ctx.apply(expr)

  override def applyTyped (v: Any)(implicit ctx: ECtx): P = {

    val startName = g.head.name
    val startp = ctx.getp(startName)
    startp.map {p=>
        // we found a root object to start from
      xpl (expr, startp)
    } getOrElse {
      // the root must be a class?
      val cat = startName
      val rdom = ctx.root.domain.get
      val realm = (ctx.root.settings.realm.mkString)
      val dom = WikiDomain(ctx.root.settings.realm.mkString)
      val c = rdom.classes.get(cat)

      // known class but not wikicat
      val roots = if (c.isDefined && !dom.isWikiCategory(cat)) {
        val ref = SpecRef.make(realm, "", "", cat, "")
        val q = Option (g.head.cond).map(_.asMap).getOrElse(Map.empty)

        // todo stich the sub-trace in the main tree
        val res = DomInventories.findByQuery (ref, Right(q), 0, 100, Array.empty[String])
        val le = res.data.map(_.asP)//.map(x=> new DASWrapper(x))

        if(g.exceptFirst.size > 0) {
          val p = prefix match {
            case "xp" | "xpl" | "xpla" => {
              val res = le.map(p => xpl(expr, Option(p)))
              // type to copy or not?
              if (res.size > 0) P.fromTypedValue("", res.flatten(_.value.get.asArray), res.head.ttype)
              else P.fromTypedValue("", res)
            }
            case "xpe" | "xpa" => xpl(expr, le.headOption)
          }
          p
        } else {
          // it was just a class name - found all, better be xple:
          prefix match {
            case "xp" | "xpl" => P.fromTypedValue("", le).withSchema(cat)
            case "xpe"        => le.headOption.map(x=> P.fromSmartTypedValue("", x)).getOrElse(P.undefined(""))
          }
        }
      } else {
        P.undefined("") // start from wiki
      }
      roots
    }
  }

  // solve path from a root from a parameter
  private def xpl(path: String, p: Option[P] = None)(implicit ctx: ECtx):P = {
    (for (
      worig <- xpRoot(p)
    ) yield {
      val cat = worig.ttype.schema
      val rdom = ctx.domain.orElse(ctx.root.domain).get
      val dom = WikiDomain(rdom.name)
      val c = rdom.classes.get(worig.ttype.schema)

      val root = if(c.isDefined && !dom.isWikiCategory(cat))
        new razie.XpWrapper(new DASWrapper(worig), DOMXpSolver)
      else
        new razie.XpWrapper(new DASWrapper(worig), DASXpSolver)

      Audit.logdb("XP-L", p.mkString + "/xpl/" + path)

      val xpath = "*/" + g.elements.tail.mkString("/")

      // solve it

      val res = if(!g.isAttr) {
        // map each entry to its own p in an array
        val le = (root xpl xpath).map(_.p)
        val p = prefix match {
          case "xp" | "xpl" => { // p of list
            val t = P.inferArrayTypeFromP(le)
            P.fromTypedValue("", le, t)
          }
          case "xpe"        => le.headOption.getOrElse(P.undefined("")) // one entity
        }
        p
      } else {
        val la = (root xpla xpath)
        val p = prefix match {
          case "xpa"         => la.headOption.map(x=> P.fromTypedValue("", x)).getOrElse(P.undefined(""))
          case "xp" | "xpla" => P.fromTypedValue("", la) // todo infer simple type of the array?
        }
        p
      }

//      val tags = res.flatMap(_._3).filter(_ != "").groupBy(identity).map(t => (t._1, t._2.size)).toSeq.sortBy(_._2).reverse
      res
    }) getOrElse
        P.undefined("")
  }

  private def xpRoot(p: Option[P]) = {
    p
  }

}

/** base class - can have a topic wrapper or a link wrapper */
class DASWrapper(val p:P) {

  /** special node means all */
  def isAll = "*" == p.name

  def childrenByTag (tag:String) = {
    if (isAll && "*" == tag)
      Nil
    else if (isAll)
      Nil
    else
      Nil
  }

  def getElem(name:String) = {
    val v = p.value
    if(v.exists(_.contentType == WTypes.JSON)) {
      v.get.asJson.get(name)
    } else {
      None
    }
  }

  def getAttr(name:String) = getElem(name).mkString

}

/**
  *  todo reconcile and replace with WikiPath / WikiWrapper
  */
object DASXpSolver extends XpSolver[DASWrapper] {

  type T = DASWrapper
  type CONT = PartialFunction[(String, String), List[DASWrapper]]  // getNext
  type U = CONT

  val debug = true

  override def children(root: T, xe:Option[XpElement]): (T, U) = (root, {
    case (tag, assoc) if root.isInstanceOf[DASWrapper] =>
      children2(root, tag).toList.teeIf(debug,"C").toList
  })

  override def getNext(o: (T, U), tag: String, assoc: String, xe:Option[XpElement]): List[(T, U)] = {
    if(debug) println("-getNext ("+o.toString+") ("+tag+")")
    // 1. apply continuation and filter just in case... all children of type
    o._2.apply(tag, assoc).filter(zz => XP.stareq(zz.p.ttype.name, tag)).teeIf(debug,"D").map(children(_, xe)).tee("E").toList
  }

  private def children2(node: DASWrapper, tag: String): Seq[DASWrapper] = {
    if(debug) println("---CHILDREN2 ("+node+") ("+tag+")")
    Nil
  }

  override def getAttr(o: T, attr: String): String = {
    if(debug) println("-getAttr ("+o.toString+") ("+attr+")")
    o.getAttr(attr)
  }

  override def reduce(curr: Iterable[(T, U)], xe: razie.XpElement): Iterable[(T, U)] =
    (xe.cond match {
      case null => curr.asInstanceOf[List[(T, U)]]
      case _    => curr.asInstanceOf[List[(T, U)]].filter(x => xe.cond.passes(x._1, this))
    }).filter(gaga => XP.stareq(gaga._1.asInstanceOf[DASWrapper].p.ttype.name, xe.name))
}


/**
  *  solver for wiki xp
  */
object DOMXpSolver extends XpSolver[DASWrapper] {

  type T = DASWrapper
  type CONT = PartialFunction[(String, String), List[DASWrapper]]  // getNext:(String,String) => children
  type U = CONT

  val debug = true

  override def children(root: T, xe:Option[XpElement]): (T, U) = (root, {
    case (tag, assoc) if root.isInstanceOf[DASWrapper] =>
      children2 (root, tag, assoc, xe).toList.teeIf(debug,"C").toList
  })

  override def getNext(o: (T, U), tag: String, assoc: String, xe:Option[XpElement]): List[(T, U)] = {
    if(debug) println("-getNext ("+o.toString+") ("+tag+")")
    // 1. apply continuation and filter just in case... all children of type
    o._2.apply(tag, assoc)
        .filter(zz =>
          XP.stareq(zz.p.ttype.schema, tag))
        .teeIf(debug,"D")
        .map(children(_, xe))
        .tee("E").toList
  }

  private def children2(node: T, tag: String, assoc:String, xe:Option[XpElement]): Seq[DASWrapper] = {
    if(debug) println("---CHILDREN2 ("+node+") ("+tag+")")

    val cat = node.p.ttype.getClassName
    val realm = "devlakomka" //ctx.root.settings.realm.mkString
    val dom = WikiDomain(realm)
    val c = dom.rdom.classes.get(cat)

    // todo resolve conditions here with query
    val cond = xe.flatMap(x=> Option(x.cond))

    // we're looking down to a ref? Are there assocs to target?
    val list = if(c.get.parms.exists(_.ttype.getClassName == tag)) {
      // use the first assoc if not specified
      val ass = Option(assoc).mkString
      // follow all associations of type if none specified
      val assocNames = if (ass.trim == "") c.get.parms.filter(_.ttype.getClassName == tag).map(_.name) else List(assoc)
      assocNames.flatMap {a=>
        val v = getAttr(node, a)

        val ref = SpecRef.make(realm, "", "", tag, v)
        val res = DomInventories.findByRef(ref)
        val list = res.map(_.asP).map(x => new T(x))

        //    val res = DomInventories.findByQuery(ref, Left(tag + "/" + a + "/" + v), 0, 100, Array.empty[String])
        //val list = res.data.map(_.asP).map(x=> new T(x))

        list.toList
      }
    } else {
      // we're maybe looking up - who is associated to us:
      // use the first assoc if not specified
      val kparm = c.map(_.key).getOrElse("key")
      val kvalue = getAttr(node, kparm)
      val ass = Option(assoc).mkString
      val a = if (ass.trim == "") c.get.parms.find(_.ttype.getClassName == tag).map(_.name).mkString else assoc

      val v = getAttr(node, a)

      val ref = SpecRef.make(realm, "", "", tag, v)
      //    val res = DomInventories.findByQuery(ref, Left(tag + "/" + a + "/" + v), 0, 100, Array.empty[String])
      //val list = res.data.map(_.asP).map(x=> new T(x))
      val res = DomInventories.findByRef(ref)
      val list = res.map(_.asP).map(x => new T(x))

      list.toList
    }
    list
  }

  override def getAttr(o: T, attr: String): String = {
    if(debug) println("-getAttr ("+o.toString+") ("+attr+")")
    o.getAttr(attr)
  }

  override def reduce(curr: Iterable[(T, U)], xe: razie.XpElement): Iterable[(T, U)] =
    (xe.cond match {
      case null => curr.asInstanceOf[List[(T, U)]]
      case _    => curr.asInstanceOf[List[(T, U)]].filter(x => xe.cond.passes(x._1, this))
    }).filter (gaga => XP.stareq(gaga._1.asInstanceOf[DASWrapper].p.ttype.getClassName, xe.name))
}
