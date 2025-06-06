/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.dom

import razie.diesel.dom.RDOM._
import razie.diesel.engine.nodes.EVal
import razie.tconf.DSpec
import scala.collection.mutable.{HashMap, ListBuffer}

/** a domain or sub-domain specification. Think UML.
  *
  * in DDD parlance, this is a bounded domain.
  *
  * these are composable: bigger = root plus more
  */
class RDomain(
  val name: String,
  val classes: Map[String, C],
  val assocs: List[A],
  val diamonds:List[D] = List.empty,
  val objects: Map[String, O] = Map.empty,
  val funcs: Map[String, F] = Map.empty) {

  /** not always populated - used in DomDocs to scope */
  var specs : ListBuffer[DSpec] = new ListBuffer[DSpec]()

  def withSpecs (s:TraversableOnce[DSpec]) = {
    this.specs.clear()
    this.specs.appendAll(s)
    this
  }

  /** use this for other elements */
  val moreElements = new ListBuffer[Any]()

  def nz (s:String) = s != null && s.length > 0

  /** compose domains parsed in different places */
  def plus(other: RDomain, newName:String=name) = {
    // filter out the artificial roots if any - they will need to be added back
    val x = new RDomain(
      newName,
      if(other.name != "root") classes.filter(_._1 != "Domain") ++ other.classes.filter(_._1 != "Domain")
      else classes ++ other.classes,
      if(other.name != "root") assocs.filter(_.aRole != "root") ++ other.assocs.filter(_.aRole != "root")
      else assocs ++ other.assocs,
      diamonds ++ other.diamonds,
      objects ++ other.objects,
      funcs ++ other.funcs)
        .withSpecs(specs)

    x.moreElements.appendAll(moreElements)
    x.moreElements.appendAll(other.moreElements)
    x.specs.appendAll(other.specs)
    x
  }

  /** COPY and clean the model - generate new assocs from members, expand class extensions etc */
  def revise = {
    // derive assocs first so we don't point to ever derive class, just the base
    val newAssocs = classes.values.toList.flatMap{c=>
      c.parms.filter(p=> nz(p.ttype) &&
        !RDomain.isDataType(p.ttype.getClassName) &&
        !assocs.exists(a=> a.a == c.name && a.z == p.ttype.getClassName && a.zRole==p.name)).map{p=>
        A("", c.name, p.ttype.getClassName, (if (!p.ttype.isRef) "Parent" else ""), p.name)
      }
    }

    // expand base params into the class
    val newClasses = classes.values.toList.map {c=>
      if(c.base.size <= 0) c
      else {
        val basep = c.base.foldLeft(List.empty[P])((a,b)=>classes(b).parms.filter(p=> !a.exists(p.name != _.name ))).distinct
        val newc = c.copy (
          parms = c.parms ::: basep.filter(p=> !c.parms.exists(p.name == _.name ))
        )
        newc.pos = c.pos
        newc
      }
    }

    val mnewClasses = new HashMap[String, C]()
    newClasses.foreach(c=>mnewClasses.put(c.name, c))

    val x=new RDomain(name, mnewClasses.toMap, assocs ++ newAssocs, diamonds, objects, funcs)
    x.moreElements.appendAll(moreElements)
    x.specs.appendAll(specs)
    x
  }

  /** add a fake Domain root so you can start browsing it there */
  def addRoot : RDomain = this plus new RDomain("root",
    Map("Domain" -> C("Domain", "", "", Nil, "")),
    classes
        .values
        .toList
        .filter(c=> ! needsParentBool(c.name))
        .map(c =>
      A("", "Domain", c.name, "root", "synth"))
  )

  /** simple json like representation of domain for browsing */
  def tojmap : Map[String, Any] = {
    Map("name" -> name,
      "classes" -> classes.values.toList.map { c =>
        tojmap(c)
      },
      "assocs" -> assocs.map {a=> Map(
        "aname" -> a.a,
        "zname" -> a.z,
        "aRole" -> a.aRole,
        "zRole" -> a.zRole,
        "assocClass" -> a.ac.map(_.name).mkString
      )
      },
      "diamonds" -> diamonds.map {d=> Map(
        "roles" -> d.roles.map { t => Map("className" -> t._1, "role" -> t._2) },
        "assocClass" -> d.ac.map(_.name).mkString
      )
      },
      "objects" -> objects.values.toList.map { c =>
        Map(
          "name" -> c.name,
          "parms" -> c.parms.map { p =>
            Map(
              "name" -> p.name,
              "value" -> p.value
            )
          }
        )
      },
      "funcs" -> funcs.values.toList.map { c =>
        Map(
          "name" -> c.name,
          "parms" -> c.parms.map { p =>
            Map(
              "name" -> p.name,
              "t" -> p.ttype
            )
          }
        )
      }
    )
  }

  /** simple json like representation of domain for browsing */
  def tojmap(c:C) : Map[String, Any] = {
        Map(
          "name" -> c.name,
          "parms" -> c.parms.map { p =>
            Map(
              "name" -> p.name,
              "t" -> p.ttype,
              "e" -> p.expr.map(_.toDsl).mkString
            )
          },
          "assocs" -> c.assocs.map { a => Map(
              "aname" -> a.a,
              "zname" -> a.z,
              "aRole" -> a.aRole,
              "zRole" -> a.zRole,
              "assocClass" -> a.ac.map(_.name).mkString
            )
          },
        "props" -> c.props.map { p =>
          Map(
            "name" -> p.name,
            "dflt" -> p.dflt
          )
        }
      )
  }

  /** aEnds that I link TO as role */
  def assocsWhereTheyHaveRole(cat: String, role: String): List[String] =
    this.assocs.filter(t => t.z == cat && t.aRole == role).map(_.a) :::
        this.assocs.filter(t => t.a == cat && t.zRole == role).map(_.z)

  /** aEnds that I link TO as role */
  def assocsWhereIHaveRole(cat: String, role: String) =
    this.assocs.filter(t => t.z == cat && t.zRole == role).map(_.a) :::
        this.assocs.filter(t => t.a == cat && t.aRole == role).map(_.z)

  /** aEnds that I link TO as role */
  def aEnds(zEnd: String, zRole: String) =
    this.assocs.filter(t => t.z == zEnd && t.zRole == zRole).map(_.a)

  /** zEnds that link to ME and I have role */
  def zEnds(aEnd: String, zRole: String) =
    this.assocs.filter(t => t.a == aEnd && t.zRole == zRole).map(_.z)

  def needsOwner(cat: String) =
    this.assocs.exists(t => t.a == cat && t.z == "User" && t.zRole == "Owner")

  def prop(cat: String, name: String): Option[String] =
    this.classes.get(cat).flatMap(_.props.find(_.name == name).map(_.currentStringValue))

  def needsParentBool(cat: String) =
    this.assocs.exists(t => t.a == cat && t.zRole == "Parent" && !Array("User", "Person").contains(t.z)) ||
    this.assocs.exists(t => t.z == cat && t.aRole == "Parent" && !Array("User", "Person").contains(t.a))

  def needsParent(cat: String) =
    this.assocs.filter(t => t.a == cat && t.zRole == "Parent" && !Array("User", "Person").contains(t.z)).map(_.z) :::
    this.assocs.filter(t => t.z == cat && t.aRole == "Parent" && !Array("User", "Person").contains(t.a)).map(_.a)

  /** basic subtype check
    *
    * @param what possible parent type
    * @param cat  subtype to check
    * @return
    */
  def isA(what: String, cat: String): Boolean =
    what.length > 0 &&
        cat.length > 0 && (
        what == cat ||
            this.classes.get(cat).toList.flatMap(_.base).foldLeft(false)((a, b) => a || isA(what, b))
        )

  override def toString = razie.js.tojsons(tojmap)

  def mkCompiler(lang: String): RCompiler = {
    if (lang == "js") new RJSCompiler(this)
    else throw new IllegalArgumentException("can't comile to language " + lang)
  }

}

/** some helpers */
object RDomain {
  final val DATA_TYPES = "String,Int,DateTime,JSON,XML,Image,URL,WID,UWID,WPATH".split(",")

  /** we cache key */
  final val DOM_LIST = "dom.list"
  final val DOM_ANNO_LIST = "dom.anno.list"

  /** an empty domain - use it to fold */
  final val empty = new RDomain("EMPTY", Map.empty, Nil)

  def isDataType (t:String) = t != null && (DATA_TYPES contains t)

  /** todo does it really need to start with one */
  def domFrom (first:DSpec, pages:List[DSpec]) : RDomain = {
    val dom = pages.flatMap(p=>
      domFrom(p).toList
    ).foldLeft(domFrom(first).get)((a, b) => a.plus(b)).revise.addRoot
    dom
  }

  /** crawl all domain pieces and build a domain */
  def domFrom (we:DSpec, filter:Option[PartialFunction[Any,Any]] = None) : Option[RDomain] = {
    val domList =
      if (filter.isDefined) {
        domFilter (we)(filter.get)
      } else {
        // copy paste but faster when no filter used...

        // it will always make a point of calling parsed before looking in the cache
        if(we.parsed.contains("CANNOT PARSE"))
          we.collector.put(
            DOM_LIST,
            List(
              EVal(
                P.of("error", "ERROR: " + we.parsed))))

        we.collector.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].reverse
      }

    // this causes the underlying fire to avoid fallen capter Y and focus on fighter 2

    //    if(we.tags.contains(R_DOM) || we.tags.contains(DSL_DOM))
    Some(
      we.collector.getOrElseUpdate ("razie/diesel/dom/dom", {
        val d = new RDomain("-",
          domList.collect {
            case c:C => (c.name, c)
          }.toMap,
          domList.collect {
            case c:A => c
          },
          domList.collect {
            case c:D if !c.isInstanceOf[A] => c
          },
          domList.collect {
            case o:O => (o.name, o)
          }.toMap,
          domList.collect {
            case f:F => (f.name, f)
          }.toMap).withSpecs(List(we))

        // now collect everything else in more
        d.moreElements.appendAll(
          domList.filter {e=>
            !(e.isInstanceOf[A] ||
              e.isInstanceOf[C] ||
              e.isInstanceOf[D] ||
              e.isInstanceOf[O] ||
              e.isInstanceOf[F])
          })

        d
      }
      )) collect {
      case d:RDomain => d
    }
    //    else None
  }

  /** crawl all domain pieces and build a domain */
  def domFilter[T] (we:DSpec)(p:PartialFunction[Any,T]) : List[T] = {
    //    if(!we.cache.contains(DOM_LIST) && we.preprocessed.s.contains("CANNOT PARSE"))
    // todo this can keep adding errors - check that there isn't one already
    if(we.parsed.contains("CANNOT PARSE"))
      we.collector.put(
        DOM_LIST,
        List(
          EVal(
            new P("error", "ERROR: "+we.parsed))))

    we.collector.getOrElse(DOM_LIST, List[Any]()).asInstanceOf[List[Any]].reverse.collect {
      case x if(p.isDefinedAt(x)) => p(x)
    }
  }
}

/** base idea of a compiler - implement one per language and - I don't remember how they're plugged in
  *
  * todo compiler factory
  */
trait RCompiler {
  def lang : String

  // c.compileAll ( c.not {case fx:RDOM.F if fx.name == f.name => true})
  def not (filter: PartialFunction[Any,Boolean]) : PartialFunction[Any,Boolean] = {
    case x if filter.isDefinedAt(x) => !filter(x)
    case x if ! filter.isDefinedAt(x) => true
  }

  def compileAll (filter: PartialFunction[Any,Boolean]) : String
  def compile (elem:Any) : String
  def call (f:F) : String
  def callInContext (f:F) : String
}

/** JS compiler - used for domain functionality defined in JS
  */
class RJSCompiler (val dom:RDomain) extends RCompiler {
  override def lang = "js"

  override def compileAll (filter: PartialFunction[Any,Boolean]) = {
    val f = dom.funcs.values.filter(filter).map(compile).mkString("\n")
    f
  }

  override def compile (elem:Any) = {
    elem match {
      case f:F => {
        // prepare the func body - put a return on it and stuff
        val b = f.script.lines.toList
        val bs = b.mkString("\n")
//        val bs = (
//          (if(b.size > 0) b.take(b.size-1) else Nil).mkString("\n") +
//            (if(b.size > 0) "\n  return "+b(b.size-1) else ""))
        val s = s"function ${f.name.replaceAllLiterally(".", "_")} (${f.parms.map(_.name).mkString(",")}) {$bs\n}"
        s
      }
    }
  }

  override def call (f:F) = {
    s"${f.name.replaceAllLiterally(".", "_")}(${f.parms.map(_.currentStringValue).mkString(",")});"
  }


  // call assuming all the parms are vars in context
  override def callInContext (f:F) = {
    s"${f.name.replaceAllLiterally(".", "_")}(${f.parms.map(_.name).mkString(",")});"
  }
}

