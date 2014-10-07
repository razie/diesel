/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package model

import com.novus.salat._
import com.novus.salat.annotations._
import com.mongodb.casbah.Imports._
import db.RazSalatContext._
import db.RTable

/** possible form states */
object FormStatus {
  final val CREATED = "created"
  final val EDITING = "editing"
  final val SUBMITTED = "submitted"
  final val APPROVED = "approved"
    
  final val FORM_ROLE = "formRole"
  final val FORM_STATE = "formState"
}

/** form utilities */
case class WikiForm(we: WikiEntry) {

  we.preprocessed // make sure it is parsed

  if (!we.fields.isEmpty) {
    // form design
    we.section("section", "formData").foreach { s =>
      // parse form data
      val data = razie.Snakk.jsonParsed(s.content)
      razie.MOLD(data.keys).map(_.toString).map { name =>
        val x = data.getString(name)
        we.fields.get(name) match {
          case Some(f) => we.fields.put(f.name, f.withValue(x))
          case None => we.fields.put(name, new FieldDef(name, x, Map.empty))
        }
      }
    }
  }

  def isFormData = we.content.contains("section:formData}}")
  def formState = we.fields.get(FormStatus.FORM_STATE).map(_.value)
  def canEdit = we.fields.get(FormStatus.FORM_STATE).exists(Array(FormStatus.EDITING, FormStatus.CREATED) contains _.value)
  def canBeApproved = we.fields.get(FormStatus.FORM_STATE).exists(_.value == FormStatus.SUBMITTED)
  def canBeRejected = we.fields.get(FormStatus.FORM_STATE).exists(_.value == FormStatus.APPROVED) 
  def formData = we.section("section", "formData")
  def formDataJson = we.section("section", "formData").map(s => razie.Snakk.jsonParsed(s.content))
  def fields = we.fields
}
