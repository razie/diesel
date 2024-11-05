/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import controllers.{Corr, Emailer}
import controllers.WikiUtil.{applyStagedLinks, before}
import mod.wiki.EditLock
import model.{Tags, Users}
import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.json.JSONObject
import razie.db.{RDelete, ROne}
import razie.diesel.Diesel
import razie.diesel.dom.DomInventories.oFromJ
import razie.diesel.dom.RDOM.P.asSimpleString
import razie.diesel.dom.RDOM._
import razie.diesel.engine.nodes.EMsg
import razie.diesel.expr.ECtx
import razie.tconf.{DSpecInventory, FullSpecRef}
import razie.wiki.Services
import razie.wiki.model.{Perm, UWID, WID, WikiAudit, WikiEntry, WikiLink, Wikis}
import scala.collection.mutable

object DomWikiJSONInventory {
  val INV = "diesel.inv.wikij"
  val instance:DomWikiJSONInventory = new DomWikiJSONInventory()
}

/** this default inventory creates topics of the respective class as JSON documents */
class DomWikiJSONInventory (
  override val name: String = DomWikiJSONInventory.INV,
  var props: Map[String, String] = Map.empty
) extends
    DomInventory {

  override val env: String = ""
  override val conn: String = "default"
  override val realm: String = ""

  /**
    * make instance for specific realm - read the specs and if any applicable, create a plugin and initialize it's
    * connections
    * Each plugin is responsible to configure itself, there's no standard -
    * usually they're added as ReactorMod, see the OdataCrmPlugin for example
    *
    * @param realm the realm this is for
    * @param wi - spec inventory, use it to lookup configuration topics, diesel plugin topics etc
    * @param iprops initial properties, when created via diesel message
    */
  override def mkInstance(realm: String, env:String, wi: DSpecInventory, newName:String, iprops: Map[String, String]): List[DomInventory] = {
    List(this)
  }

  override def connect(dom: RDomain, env: String): Either[P, EMsg] =
    Left(P.of(Diesel.PAYLOAD, "ok"))

  /** html for the supported actions */
  override def htmlActions(elem: DE, ref:Option[FullSpecRef]): String = {
    elem match {
      case c: C => {
        def mkList = s"""<a href="/diesel/dom/list/${c.name}">list</a>"""

        // todo delegate decision to tconf domain - when domain is refactored into tconf
        def mkNew =
          //todo move to RDomain
          // if (ctx.we.exists(w => WikiDomain.canCreateNew(w.specPath.realm.mkString, name)))
          s""" <a href="/doe/diesel/dom/startCreate/${c.name}">new</a>"""

        def mkEdit =
          //todo move to RDomain
          // if (ctx.we.exists(w => WikiDomain.canCreateNew(w.specPath.realm.mkString, name)))
            ref.map(x=> s"""| <a href="/doe/diesel/dom/startEdit/${x.category}/${x.key}">edit</a>""").getOrElse("")

        s"$mkList | $mkNew $mkEdit"
      }

      case _ => "?"
    }
  }


  override def doAction(r: RDomain, conn: String, action: String, uri: String, epath: String) = ???

  /** create an element
    *
    * @param dom
    * @param ref
    * @param asset
    * @return
    */
  override def upsert(dom: RDomain, ref: FullSpecRef, asset: DieselAsset[_]): Either[Option[DieselAsset[_]], EMsg] = {
    // todo use userid
    val wid = WID(ref.category, ref.key).r(ref.realm)
    val content = asset.asP.currentStringValue
    var we = new WikiEntry(ref.category, ref.key, ref.key, "json", content, new ObjectId(), Seq("dslObject"), ref.realm)

    Wikis.find(wid).filter(wid.realm.isEmpty || _.realm == wid.realm.get) match {

      case Some(w) => {
        // edited topic
//          conflict <- (oldVer == w.ver.toString) orCorr
//              new Corr (
//                s"Topic modified in between ($oldVer ${w.ver})",
//                "Edit this last vesion and make your changes again.");
        // keep existing tags
        var newVer = w.cloneNewVer(we.label, we.markup, we.content, we.by);
        before(newVer, WikiAudit.UPD_CONTENT)

//          if (we.tags.mkString(",") != tags)
//            we = we.withTags(Tags(tags), au._id)

        Wikis.clearCache(we.wid, we.wid.r(ref.realm))

        razie.db.tx("Wiki.Save", "anon") { implicit txn =>
          w.update(newVer, Some("edited"))
        }
        Services ! WikiAudit(WikiAudit.UPD_EDIT, w.wid.wpathFull, None, None, Some(we), Some(w))
      }

      case None => {   // create a new topic

        // special properties
        we.preprocess(None)
        razie.db.tx("wiki.create", "anon") { implicit txn =>
          we.create()
          // todo increase quota and check quota - we.create will check at 5000 entries in index
          Services ! WikiAudit(WikiAudit.CREATE_WIKI, we.wid.wpathFull, None, None, Some(we))
        }
      }
    }

    Left(Some(asset))
  }

  def wetoa (dom:RDomain, ref: FullSpecRef, we:WikiEntry) : DieselAsset[O] = {
    val newref = ref.copy(key = we.name)
    val o = oFromJ(we.name: String, new JSONObject(we.content), dom.classes(ref.category), ref.category: String, Array())
    DieselAsset[O](newref, o, Some(o))
  }

  /** list all elements of class
    *
    * @param dom  current domain
    * @param ref  reference with basic info
    * @param from pagination start from index
    * @param size pagination size
    * @param sort list of fields to sort by, format: field:desc or field:asc
    * @param collectRefs
    * @return either a result or a future message which will give the result
    */
  override def listAll(dom: RDomain, ref: FullSpecRef,
                       start: Long, limit: Long,
                       sort: Array[String],
                       countOnly: Boolean = false,
                       collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[DIQueryResult, EMsg] = {
    val res = Wikis(ref.realm).pages(ref.category).map{wetoa (dom, ref, _)}.toList
    Left(DIQueryResult(res.size, res))
  }

  /**
    * can this support the class? You can look at it's annotations etc
    *
    * You can either use the `diesel.inv.register` message or annotate your known classes withe a
    * specific annotation like `odata.name` etc
    */
  override def isRegisteredFor(realm: String, c: C): Boolean = {
    // no other inv reg and this is a domain class
//    ! DomInventories.invRegistry.contains(realm+"."+c.name) &&
//        ! c.stereotypes.contains(razie.diesel.dom.WikiDomain.WIKI_CAT)
    super.isRegisteredFor(realm, c)
  }

  /** find an element by ref
    *
    * @param dom current domain
    * @param ref ref to find
    * @param collectRefs
    * @return
    */
  override def findByRef(dom: RDomain, ref: FullSpecRef, collectRefs: Option[mutable.HashMap[String, String]] = None)
  : Either[Option[DieselAsset[_]], EMsg] = {
    val res = Wikis(ref.realm).find(WID(ref.category, ref.key).r(ref.realm)).map {wetoa(dom, ref, _)}
    Left(res)
  }

  /** find an element by query
    *
    * @param dom   current domain
    * @param ref   reference with basic info
    * @param epath either a query, query path or list of attributes with AND
    * @param from  pagination start from index
    * @param size  pagination size
    * @param sort  list of fields to sort by, format: field:desc or field:asc
    * @param collectRefs
    * @return either a result or a future message which will give the result
    */
  override def findByQuery(dom: RDomain, ref: FullSpecRef, epath: Either[String, collection.Map[String, Any]],
                           from: Long = 0, size: Long = 100,
                           sort: Array[String],
                           countOnly: Boolean = false,
                           collectRefs: Option[mutable.HashMap[String, String]] = None
                 ):
  Either[DIQueryResult, EMsg] = {
    var res = Wikis(ref.realm).pages(ref.category).map{wetoa (dom, ref, _)}.toList

    res = epath.fold(
      s => {
        // query by path
        val PAT = DomInventories.CLS_FIELD_VALUE
        val PAT(cls, field, id) = epath.left.get

        {
          if (id == "" || id == "*" || id == "'*'") res // nothing to filter
          else {
            res.filter (_.value.parms.find(_.name == field).exists(_.currentStringValue == id))
          }
        }
      },
      m => {
        m.foreach { t =>
          val v = t._2.toString
          if(t._2.toString.length > 0)
            res = res.filter (_.value.parms.find(_.name == t._1).exists(_.currentStringValue == v))
          else // empty or not defined
            res = res.filter (p=>
              p.value.parms.find(_.name == t._1).exists(_.currentStringValue == v) ||
              !p.value.parms.exists(_.name == t._1)
            )
        }
        res
      }.toList
    )

    Left(DIQueryResult(res.size, res))
  }

  /** we're not backing up this time */
  override def remove(dom: RDomain, ref: FullSpecRef)
  : Either[Option[DieselAsset[_]], EMsg] = {
    val res = Wikis(ref.realm).find(WID(ref.category, ref.key).r(ref.realm))

    res.foreach {w=>
      razie.db.tx("Wiki.delete", "anon") { implicit txn =>
        val key = Map("realm" -> w.realm, "category" -> w.category, "name" -> w.name)
        RDelete.apply(Wikis(realm).weTables(w.category), key)
        Services ! WikiAudit(WikiAudit.DELETE_WIKI, w.wid.wpathFull, None, None, Some(w), None,
          Some(w._id.toString))
      }
    }

    Left(res.map {wetoa(dom, ref, _)})
  }

}

