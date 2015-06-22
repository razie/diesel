package mod.diesel.model

import razie.base.data.TripleIdx
import razie.js
import org.json.{JSONArray, JSONObject}

/**
 * Created by raz on 2015-06-09.
 */
trait Inventory {
  def get    (realm:String, cat:String, name:String) : Option[JSONObject]
  def put    (realm:String, cat:String, name:String, o: JSONObject) : JSONObject
  def delete (realm:String, cat:String, name:String) : Option[JSONObject]
  def list   (realm:String, cat:String) : List[String]
  def query  (realm:String, cat:String, criteria:Map[String, String]) : List[String]
  def path   (realm:String, cat:String, path:String) : List[String]

  protected implicit class k(realm:String) {
    def k(cat:String) = realm+"."+cat
  }

}

object TheInventory extends Inventory {
  def get    (realm:String, cat:String, name:String) : Option[JSONObject] =
    InMemInventory.get(realm, cat, name)
  def put    (realm:String, cat:String, name:String, o: JSONObject) : JSONObject =
    InMemInventory.put(realm, cat, name, o)
  def delete (realm:String, cat:String, name:String) : Option[JSONObject] =
    InMemInventory.delete(realm, cat, name)
  def list   (realm:String, cat:String) : List[String] =
    InMemInventory.list(realm, cat)
  def query  (realm:String, cat:String, criteria:Map[String, String]) : List[String] =
    InMemInventory.query(realm, cat, criteria)
  def path   (realm:String, cat:String, path:String) : List[String] =
    InMemInventory.path(realm, cat, path)
}

object InMemInventory extends Inventory {
  private val cache = new TripleIdx[String, String,JSONObject]()

  def get    (realm:String, cat:String, name:String) : Option[JSONObject] =
    cache.get2 (realm k cat, name)
  def put    (realm:String, cat:String, name:String, o: JSONObject) : JSONObject = {
    cache.put (realm k cat, name, o)
    o
  }
  def delete (realm:String, cat:String, name:String) : Option[JSONObject] =
    cache.remove2(realm k cat, name)
  def list   (realm:String, cat:String) : List[String] =
    cache.get1k(realm k cat)
  def query  (realm:String, cat:String, criteria:Map[String, String]) : List[String] =
    List.empty
  def path   (realm:String, cat:String, path:String) : List[String] =
    List.empty
}

