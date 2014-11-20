/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import db.RazSalatContext._
import model.DOM.D
import razie.Logging
import admin.VErrors
import admin.Validation
import db.RMany
import admin.Audit
import admin.Services
import db.RazMongo
import org.json.JSONArray
import org.json.JSONObject

/** encapsulates the knowledge to use the wiki-defined domain model */
class WikiDomain (realm:String, wi:WikiInst) {
  import DOM._

  //todo optimize
  private var idom : D = null

  /** have a neutral domain/meta model and load it from categories */
  def dom = {
    //todo async antip?
    if(idom == null) idom = wikid()
    idom
  }

  //todo rewrite these in terms of the neutral DOM

  /** get all zends as List (to, role) */
  def gzEnds(aEnd: String) =
    (for (
      c <- wi.categories if (c.contentTags.get("roles:" + aEnd).isDefined);
      t <- c.contentTags.get("roles:" + aEnd).toList;
      r <- t.split(",")
    ) yield (c.name, r)).toList

//    Wikis.categories.filter(_.contentTags.get("roles:" + aEnd).isDefined).flatMap(c=>c.contentTags.get("roles:" + aEnd).get.split(",").toList.map(x=>(c,x)))

  def gaEnds(zEnd: String) =
    for (
      c <- wi.category(zEnd).toList;
      t <- c.contentTags if (t._1 startsWith "roles:");
      r <- t._2.split(",")
    ) yield (t._1.split(":")(1), r)

  /** zEnds that link to ME as role */
  def zEnds(aEnd: String, role: String) =
    wi.categories.filter(_.contentTags.get("roles:" + aEnd).map(_.split(",")).exists(_.contains(role) || role=="")).toList

  /** aEnds that I link TO as role */
  def aEnds(zEnd: String, role: String) =
    for (
      c <- wi.category(zEnd).toList;
      t <- c.contentTags if (t._1.startsWith ("roles:") && (t._2.split(",").contains(role) || role==""))
    ) yield t._1.split(":")(1)

  def needsOwner(cat: String) =
    wi.category(cat).flatMap(_.contentTags.get("roles:" + "User")).exists(_.split(",").contains("Owner"))

  def noAds(cat: String) =
    wi.category(cat).flatMap(_.contentTags.get("noAds")).isDefined

  def needsParent(cat: String) =
    wi.category(cat).exists(_.contentTags.exists { t =>
      t._1.startsWith("roles:") && t._2.split(",").contains("Parent")
    })

  def labelFor(wid: WID, action: String) = wi.category(wid.cat) flatMap (_.contentTags.get("label." + action))

  /** parse categories into domain model */
  def wikid(): DOM.D = {
    val classes = for (cat <- wi.categories) yield {
      val assocs = for (t <- cat.contentTags if t._1 startsWith "roles:") yield {
        A(cat.name, t._1.split(":")(1), "", t._2)
      }
      C(cat.name, "?", Nil, Nil, assocs.toList)
    }

    D(realm, classes.map(c=>(c.name, c)).toMap, Map.empty)
  }
}

object WikiDomain {//extends WikiDomain (Wikis.RK) {
  def apply (realm:String) = Reactors(realm).domain
  val rk = Reactors("rk").domain
}

object DOM {
  // archtetypes
  val ARCH_SPEC = "Spec"
  val ARCH_ROLE = "Role"
  val ARCH_ENTITY = "Entity"
  val ARCH_MI = "MI"

  class CM // abstract class member
  case class P (name:String, ttype:String, multi:String, dflt:String, expr:String) extends CM // attr/parm spec
  case class F (name:String, parms:List[P]) extends CM // function/method
  case class V (name:String, value:String)  // attr value
  case class C (name:String, archetype:String, base:List[String], parms:List[P]=Nil, assocs:List[A]=Nil, methods:List[F]=Nil) //class
  case class O (name:String, base:String, parms:List[V]) { // object = instance of class
    def toJson = parms.map{p=> p.name -> p.value}.toMap
  }
  case class A (a:String, z:String, aRole:String, zRole:String, parms:List[P]=Nil, methods:List[F]=Nil) //class

  case class E (name:String, parms:List[P], methods:List[F]) //event
  case class R (name:String, parms:List[P], body:String) //rule
  case class X (body:String) //expression

  case class D (name:String, classes:Map[String,C], objects:Map[String,O]) {

    // compose domains parsed in differnt places
    def plus (newName:String, other:D) = {
      D(newName, classes ++ other.classes, objects ++ other.objects)
    }

    /** simple json like representation of domain for browsing */
    def tojmap = {
      Map("name"->name,
        "classes" -> classes.values.toList.map{c=>
          Map(
            "name"->c.name,
            "parms" -> c.parms.map{p=>
              Map(
                "name"->p.name,
                "t" -> p.ttype
              )
            },
            "assocs" -> c.assocs.map{a=>
              Map(
                "aname"->a.a,
                "zname"->a.z,
                "aRole"->a.aRole,
                "zRole"->a.zRole
              )
            }
          )},
        "objects" -> objects.values.toList.map{c=>
          Map(
            "name"->c.name,
            "parms" -> c.parms.toList.map{p=>
              Map(
                "name"->p.name,
                "value" -> p.value
              )
            }
          )}
      )
    }

//    def toJson = admin.js.tojson(tojmap)
    override def toString = admin.js.tojsons(tojmap)
  }
}


