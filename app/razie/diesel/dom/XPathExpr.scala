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
import razie.diesel.engine.nodes.{ETrace, EWarning}
import razie.diesel.expr.{DieselExprException, ECtx, Expr, SimpleExprParser}
import razie.tconf.SpecRef

/** an xpath expr resolved with diesel ki categories AND/OR domain and inventory elements
  *
  * @param prefix is one of xp, xpl, xpa, xpla
  * @param expr is the rest of the expression
  */
case class XPathIdent (val prefix:String, override val expr: String) extends Expr {
  val g = GPath(expr)

  override def toDsl = prefix + ":" + expr

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

      // accept nost just engine ctx = may be called from elsewhere, use the realm domain then
      val rdom = if(ctx.root != null) ctx.root.domain else None
      val realm = (ctx.root.settings.realm.mkString)
      val dom = WikiDomain(realm)

      // todo this engine's dom may have classes not defined in the big dom - BUT WHY does the big dom may have classes not defined in this engine?
      val c = rdom.flatMap(_.classes.get(cat)).orElse(dom.rdom.classes.get(cat))

      ctx.curNode.foreach {_.appendVerbose(
        ETrace(s"Domain found: [${c.map(_.name)}] isWikiCat:${dom.isWikiCategory(cat)}")
      )}

      // known class but not wikicat
      val roots = if (c.isDefined && !dom.isWikiCategory(cat)) {
        val ref = SpecRef.make(realm, "", "", cat, "")
        val q = Option (g.head.cond).map(_.asMap(new DOMXpSolver(ctx))).getOrElse(Map.empty)
        // get the XPCond as a map query, but calculate values

        // we don't query all and evaluate XPCond, we just transform xp cond into a map query for the inventory

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
      val c = rdom.classes.get(cat)

      val root = if(c.isDefined && !dom.isWikiCategory(cat)) {
        // dom classes
        new razie.XpWrapper(new DASWrapper(worig), new DOMXpSolver(ctx))
      } else {
        // wiki categories
        new razie.XpWrapper(new DASWrapper(worig), DASXpSolver)
      }

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

  def getAttr(name:String) = getElem(name)

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
    o.getAttr(attr).mkString
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
class DOMXpSolver (ctx: ECtx) extends XpSolver[DASWrapper] {

  type T = DASWrapper
  type CONT = PartialFunction[(String, String, Option[XpElement]), List[DASWrapper]]  // getNext:(String,String) => children
  type U = CONT

  val debug = true

  /** don't look them up yet - just return a continuation */
  override def children(root: T, xe:Option[XpElement]): (T, U) = (root, {
    case (tag, assoc, xe2) if root.isInstanceOf[DASWrapper] =>
      children2 (root, tag, assoc, xe2).toList.teeIf(debug,"C").toList
  })

  override def getNext(o: (T, U), tag: String, assoc: String, xe:Option[XpElement]): List[(T, U)] = {
    if(debug) println("-getNext ("+o.toString+") ("+tag+")")
    // 1. apply continuation and filter just in case... all children of type
    o._2.apply(tag, assoc, xe)
        .filter(zz =>
          XP.stareq(zz.p.ttype.schema, tag))
        .teeIf(debug,"D")
        .map(children(_, xe))
        .tee("E").toList
  }

  private def children2(node: T, tag: String, assoc:String, xe:Option[XpElement]): Seq[DASWrapper] = {
    if(debug) println("---CHILDREN2 ("+node+") ("+tag+")")

    val cat = node.p.ttype.getClassName
    val realm = ctx.root.settings.realm.mkString
    val dom = WikiDomain(realm)
    val c = dom.rdom.classes.get(cat)
    val ass = Option(assoc).mkString.trim // may be null because I am an ass

    if (c.isEmpty) throw new DieselExprException(s"Class $cat is not found in domain - define it with `$$class $cat`!")

    // todo resolve conditions here with query
    val cond = xe.flatMap(x=> Option(x.cond))

    // we're looking down to a ref? Are there assocs to target? Or user forced lookup?
    val list = if(c.get.parms.exists(_.ttype.getClassName == tag) && ass != "domLookup") {
      // use the first assoc if not specified
      // follow all associations of type if none specified
      val assocNames = if (ass.trim == "") c.get.parms.filter(_.ttype.getClassName == tag).map(_.name) else List(assoc)
      assocNames.flatMap {a=>
        val v = getAttr(node, a)

        // todo this is wrong - what if it's a <> and/or a list?
        // <> that's all that's accepted here
        // if it's a list, then this should loop through an array of references
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

      val otherCat = dom.rdom.classes.get(tag)
      if (otherCat.isEmpty) throw new DieselExprException(s"Class $otherCat is not found in domain - define it with `$$class $otherCat`!")

      val a = if (ass == "") otherCat.get.parms.find(_.ttype.getClassName == cat).map(_.name).mkString else assoc

      val ref = SpecRef.make(realm, "", "", tag, "")
//      val res = DomInventories.findByQuery(ref, Left(tag + "/" + a + "/" + v), 0, 100, Array.empty[String])
      // todo add cond as map to the query, no?
      val cq = if(xe.isDefined && xe.get.cond != null) xe.get.cond.asMap(this) else Map.empty
      val query = Map(a -> kvalue) ++ cq
      val res = DomInventories.findByQuery(ref, Right(query), 0, 100, Array.empty[String])
      val list = res.data.map(_.asP).map(x=> new T(x))

      list.toList
    }
    list
  }

  // get attr from context, unrelated to an object we traversed
  override def getOptAttr(attr: String): Option[String] = {
    (new SimpleExprParser().parseExpr(attr).map(_.apply("")(ctx))).map(_.toString)
  }

  override def getOptAttr(o: T, attr: String): Option[String] = {
    if(debug) println("-getAttr ("+o.toString+") ("+attr+")")
    Option(o).flatMap(_.getAttr(attr)).orElse(ctx.get(attr)).map(_.toString)
  }

  override def getAttr(o: T, attr: String): String = {
    getOptAttr(o, attr).mkString
  }

  override def reduce(curr: Iterable[(T, U)], xe: razie.XpElement): Iterable[(T, U)] =
    (xe.cond match {
      case null => curr.asInstanceOf[List[(T, U)]]
      case _    => curr.asInstanceOf[List[(T, U)]].filter(x => xe.cond.passes(x._1, this))
    }).filter (gaga => XP.stareq(gaga._1.asInstanceOf[DASWrapper].p.ttype.getClassName, xe.name))
}
