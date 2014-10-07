package model

import admin.VErrors
import db.RTable

/** wraper with form utils */
class WForm(val we: WikiEntry) {
  def isFormSpec = false

  def mkContent(j: org.json.JSONObject, content:String = we.content) = {
    val co = content.replaceFirst("(?s)\\]\\].*", "]]") +
      "\n" +
      "{{.section:formData}}\n" +
      j.toString +
      "\n{{/section}}\n"
    co
  }

  /** replace the fields in content with the respective html code */
  def formatFields(content: String) = {
    val FIELDS = """`\{\{\{(f):([^}]*)\}\}\}`""".r

    val ro = "" //if(we.exists(_.fields.get("formState").map(_.value).getOrElse("editing") != "editing")) "readonly" else ""

    val res = FIELDS replaceSomeIn (content, { m =>
      try {
        Some {
          val name = m group 2
          val f = we.form.fields.get(name)
          val t = f.flatMap(_.attributes.get("type")).getOrElse("text")
          val other = f.map(_.attributes.filter(t => t._1 != "type" && t._1 != "choices").foldLeft("")((s, t) => s + " " + t._1 + "=\"" + t._2 + "\"")).getOrElse("")

          f.flatMap(_.attributes.get("type")) match {
            case Some("select") => {
              val choices = (f.flatMap(_.attributes.get("choices"))).toList.flatMap(_.split("\\|")).map(
                  v=>if(v.contains(";")) v.split(";") else Array(v,v)
                  ).map(
                  a => "<option value=\"" +a(0) + "\" " + (if (f.get.value == a(0)) "selected>" else ">") + a(1) + "</option>").mkString
              s"<select $ro name=$name $other>$choices</select>"
            }
            case _ => {
              val checked = if (f.exists(_.value == "y")) "checked" else ""
              if (t == "textarea" || t == "memo")
                s"""<textarea $ro type="$t" name="$name" $other>${f.map(_.value).getOrElse("?")}</textarea>"""
              else if (t == "checkbox") {
                s"""<input $ro type="$t" name="$name" value="y" $checked $other></input>"""
              } else if (t == "date") {
                // <a href="#" class="btn btn-mini btn-info" title="Internet explorer likes yyyy-mm-dd format while other browsers like dd-mm-yyyy">?</a>"""
                s"""<input $ro type="text" name="$name" value="${f.map(_.value).getOrElse("?")}" $other></input>"""
              } else
                s"""<input $ro type="$t" name="$name" value="${f.map(_.value).getOrElse("?")}" $other></input>"""
            }
          }
        }
      } catch { case _: Throwable => Some("!?!") }
    })

    res
  }

  def validate(data: Map[String, String]) = {
    implicit val errCollector = new VErrors()
    val errors = collection.mutable.Map[String, String]()
    val newData = collection.mutable.Map[String, String]()

    data.foreach {
      case (n, iv) =>
        try {
          val v = iv.trim
          newData.put(n, v)

          // TODO this is stupid

          val SPEC = "[<>${}\\[\\]]"
          if (v.matches(".*" + SPEC + ".*")) {
            errors += n -> "Contains special characters - don't use dollar sign or (){} or <>"
            newData.put(n, v.replaceAll(SPEC, ""))
          }

          if (v.length > 200) {
            errors += n -> "Too long - max 200"
            newData.put(n, v.substring(0, 199))
          }

          val f = we.form.fields.get(n)

          val t = f.flatMap(_.attributes.get("type")).getOrElse("text")
          val req = f.flatMap(_.attributes.get("req")).exists(_ == "yes")
          val other = f.map(_.attributes.filter(t => !Array("type","choices","req").contains(t._1)).foldLeft("")((s, t) => s + " " + t._1 + "=\"" + t._2 + "\"")).getOrElse("")

          if(req && v.isEmpty)
            errors += n -> "Is required! Please fill in this field."

          t match {
            case "date" => {
              if (!(v.isEmpty || v.matches("""\d\d.\d\d.\d\d\d\d""") || v.matches("""\d\d\d\d.\d\d.\d\d""")))
                errors += n -> "Not a proper date format - please use yyyy-mm-dd"
            }
            case "select" => {
              //              val choices = (f.flatMap(_.attributes.get("choices"))).toList.flatMap(_.split("\\|")).map(v => "<option " + (if (f.get.value == v) "selected>" else ">") + v + "</option>").mkString
            }
            case "number" => {
              try {
              } catch { case e: Throwable => errors += n -> s"Not a number" }
            }
            case _ => {
              //              val checked = if (f.exists(_.value == "y")) "checked" else ""
              //              if (t == "textarea" || t == "memo")
              //                s"""<textarea $ro type="$t" name="$name" $other>${f.map(_.value).getOrElse("?")}</textarea>"""
              //              else if (t == "checkbox") {
              //                s"""<input $ro type="$t" name="$name" value="y" $checked $other></input>"""
              //              } else
              //                s"""<input $ro type="$t" name="$name" value="${f.map(_.value).getOrElse("?")}" $other></input>"""
            }
          }
        } catch { case e: Throwable => errors += n -> s"ERROR: ${e.getLocalizedMessage()}" }
    }
    (newData, errors)
  }

}
