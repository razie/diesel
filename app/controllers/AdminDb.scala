package controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports._
import org.bson.types.ObjectId
import razie.audit.Audit
import razie.db.RazMongo
import razie.hosting.WikiReactors
import razie.wiki.model._

/** admin the mongo db directly */
@Singleton
class AdminDb extends AdminBase {

  /** view a table */
  def col(name: String) = FADR { implicit request =>
    ROK.k admin { implicit stok => views.html.admin.adminDbCol(name, RazMongo(name).findAll) }
  }

  /** find a value across all records */
  def dbFind(value: String) = FA { implicit request =>
    ROK.r noLayout { implicit stok => views.html.admin.adminDbFind(value) }
  }

  /** look at a record */
  def colEntity(name: String, id: String) = FA { implicit request =>
    ROK.r admin { implicit stok => views.html.admin.adminDbColEntity(name, id, RazMongo(name).findOne(Map("_id" -> new ObjectId(id)))) }
  }

  /** view a table in table format */
  def colTab(name: String, cols: String) = FA { implicit request =>
    ROK.r admin { implicit stok => views.html.admin.adminDbColTab(name, RazMongo(name).findAll, cols.split(",")) }
  }

  /** delete a record from a table */
  def delcoldb(table: String, id: String) = FA { implicit request =>
    Audit.logdb("ADMIN_DELETE", "Table:" + table + " json:" + RazMongo(table).findOne(Map("_id" -> new ObjectId(id))))
    RazMongo(table).remove(Map("_id" -> new ObjectId(id)))
    ROK.r admin { implicit stok => views.html.admin.adminDbCol(table, RazMongo(table).findAll) }
  }

  /** delete a record from a table */
  def updcoldb(table: String, id: String) = FADR { implicit request =>
    val field = request.formParm("field")
    val value = request.formParm("value")
    val ttype = request.formParm("type")

    if(field.length > 0) {
      Audit.logdb("ADMIN_UPDATE", "Table:" + table + s"$field:$value")

      ttype match {
        case "Number" =>
          clog << RazMongo(table).update(Map("_id" -> new ObjectId(id)), Map("$set" -> Map(field -> value.toFloat)))
        case _ =>
          clog << RazMongo(table).update(Map("_id" -> new ObjectId(id)), Map("$set" -> Map(field -> value)))
      }
    }

    Redirect(routes.AdminDb.colEntity(table, id))
  }

  def listIdx = FA { implicit request =>
    Audit.logdb("ADMIN_IDX")

    Ok(WikiReactors.reactors.keys.map {r=>
      s"""<a href="/razadmin/viewIdx/$r">$r</a><br>"""

    }.mkString).as("text/html")
  }

  def viewIdx(realm: String) = FA { implicit request =>
    Audit.logdb("ADMIN_IDX")

    Ok(Wikis(realm).index.withIndex {idx=>
      idx.idx.map {e=>
        e._1.toString + " \n   " + e._2.map{ee=>
          ee._1.toString + " - " + ee._2.toString
        }.mkString + "\n"
      }
    }.mkString)
  }

}
