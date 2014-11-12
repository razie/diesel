/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import com.mongodb.casbah.Imports._
import org.bson.types.ObjectId

import scala.Array.canBuildFrom
import com.mongodb.DBObject
import admin._
import model._
import db.{REntity, RazMongo, ROne, RMany}
import db.RazSalatContext.ctx
import model.Sec.EncryptedS
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.mvc.{SimpleResult, AnyContent, Action, Request}
import razie.{cout, Logging, clog}
import model.WikiAudit
import model.WikiLink

/** realm/reactor controller */
object Realm extends RazController with Logging {

  val addForm = Form(
    "name" -> nonEmptyText.verifying(vPorn, vSpec))

  def renameForm(realm:String) = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasporn(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name already in use", { t: (String, String) => !Wikis(realm).index.containsName(Wikis.formatName(t._2))
      })
  }

  import Visibility._

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  val RK: String = Wikis.RK

}



