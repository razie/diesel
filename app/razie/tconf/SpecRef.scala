/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package razie.tconf

import razie.diesel.dom.DomInventories

/** uniquely identifies a piece of specification, an object or an asset
  *
  * for identifying sections inside a larger document, the wpath will include like a section pointer (...#section)
  *
  * source can be domain plugin and connection ID inside the target realm, "plugin:conn"
  * wpath can be at minimum "class:id" or qualified "realm.class:id"
  * wpath could include a section Id, like "realm.class:id#section"
  *
  * @param source - the source system: inventory understands and delegates to (preferably URL)
  * @param wpath  - unique long id of the spec
  * @param key    - unique short id of the spec (within realm/cat)
  * @param realm  - optionally identify a realm within the source (multi-tenancy)
  * @param ver    - optionally identify a version of the spec
  * @param draft  - optionally identify a certain temporary variant (i.e. autosaved by username)
  */
trait TSpecRef {
  def source: String

  def wpath: String

  def key: String

  def realm: String

  def category: String

  def ver: Option[String]

  def draft: Option[String]

  def ahref: Option[String]

  def toJson: Map[String, Any] = {
    Map(
      "source" -> source,
      "wpath" -> wpath,
      "realm" -> realm,
      "key" -> wpath,
      "class" -> category
    ) ++ ver.map(x => Map("ver" -> x)
    ).getOrElse(Map.empty) ++ draft.map(x => Map("draft" -> x)
    ).getOrElse(Map.empty) ++ ahref.map(x => Map("ahref" -> x)
    ).getOrElse(Map.empty)
  }
}

/** basic implmentation */
case class SpecRef(
  realm: String,
  wpath: String,
  key: String,
  source: String = "",
  ver: Option[String] = None,
  draft: Option[String] = None)
    extends TSpecRef {

  def ahref: Option[String] = None

  def category: String = wpath.replaceFirst(":.*", "")
}

/**
  * generic complete implementation for an asset ref
  *
  * (realm,plugin,conn) uniquely identify the domain and source
  * (cls,id,section,ver,draft) uniquely identify the asset inside there
  */
case class FullSpecRef(
  inventory: String,
  conn: String,
  cls: String,
  key: String,
  section: String,
  realm: String,
  ver: Option[String] = None,
  draft: Option[String] = None)

    extends TSpecRef {

  override def category: String = cls

  override def source: String = inventory + ":" + conn

  override def wpath: String = cls + ":" + key

  override def ahref: Option[String] = None

  override def toJson: Map[String, Any] = {
    Map(
      "inventory" -> inventory,
      "conn" -> conn,
      "source" -> source,
      "wpath" -> wpath,
      "realm" -> realm,
      "key" -> key,
      "class" -> category
    ) ++ ver.map(x => Map("ver" -> x)
    ).getOrElse(Map.empty) ++ draft.map(x => Map("draft" -> x)
    ).getOrElse(Map.empty) ++ ahref.map(x => Map("ahref" -> x)
    ).getOrElse(Map.empty)
  }
}

/** utilities */
object SpecRef {

  /** make a generic specref */
  def make(realm: String, inventory: String, conn: String, cls: String, id: String, section: String = "") = {
    new FullSpecRef(
      inventory,
      conn,
      cls,
      id,
      section,
      realm
    )
  }

  /** make a generic specref */
  def fromJson(j: Map[String, Any]) = {
    new FullSpecRef(
      j.getOrElse("inventory", "").toString,
      j.getOrElse("conn", "").toString,
      j.getOrElse("class", "").toString,
      j.getOrElse("key", "").toString,
      j.getOrElse("section", "").toString,
      j.getOrElse("realm", "").toString
    )
  }

  /** decompose into a full spec */
  def full(ref: TSpecRef) = {
    val s1 = ref.source.split(":")
    val s2 = ref.wpath.split(":")
    val inv = if (s1.length > 1) s1(0) else ""
    val conn = if (s1.length > 1) s1(1) else s1(0)
    var cls = if (s2.length > 1) s1(0) else ""
    var id = if (s2.length > 1) s1(0) else s2(0)
    var sec = ""

    if (cls.contains(".")) {
      cls = cls.split('.')(1)
    }

    if (id.contains("#")) {
      id = id.split('#')(0)
      sec = id.split('#')(1)
    }

    new FullSpecRef(
      inv,
      conn,
      cls,
      id,
      sec,
      ref.realm,
      ref.ver,
      ref.draft
    )
  }

  val sCLS_FIELD_VALUE = """([^/]+)/([^/]+)/(.+)"""
  val CLS_FIELD_VALUE = sCLS_FIELD_VALUE.r
  val sEPATH_ID = """([^/]+)/([^/]+)"""
  val EPATH_ID = sEPATH_ID.r

  /** parse an epath. Epath is either cls only or cls/key or cls/attr/value */
  def parseEpath (epath:String) : (String, String, String) = {

    if(epath.matches(sCLS_FIELD_VALUE)) {
      val CLS_FIELD_VALUE (cls, field, id) = epath
      (cls, field, id)
    } else if(epath.matches(sEPATH_ID)) {
      val EPATH_ID (cls, id) = epath
      (cls, "key", id)
    } else {
      val cat = epath.replaceFirst("/.*", "")
      (cat, "", "")
    }
  }
}


