/*
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import controllers.WikiUtil.{EditWiki, LinkWiki, ReportWiki}
import model._
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, text, tuple}
import play.api.mvc.Request
import razie.wiki.Services
import razie.wiki.model._

class WikieBase extends WikiBase {

  implicit def obtob(o: Option[Boolean]): Boolean = o.exists(_ == true)

  val editForm = Form {
    mapping(
      "label" -> nonEmptyText.verifying(vBadWords, vSpec),
      "markup" -> nonEmptyText.verifying("Unknown!", {x:String=> Wikis.markups.contains(x)}),
      "content" -> text,
      "visibility" -> nonEmptyText,
      "wvis" -> nonEmptyText,
      "oldVer" -> nonEmptyText,
      "tags" -> text.verifying(vBadWords, vSpec),
      "draft" -> text.verifying(vBadWords, vSpec))(EditWiki.apply)(EditWiki.unapply) verifying (
      "Your entry failed the obscenity filter", { ew: EditWiki => !Wikis.hasBadWords(ew.content)
    })
  }


  val reportForm = Form {
    mapping(
      "reason" -> nonEmptyText)(ReportWiki.apply)(ReportWiki.unapply) verifying (
      "Your entry failed the obscenity filter", { ew: ReportWiki => !Wikis.hasBadWords(ew.reason)
    })
  }


  def linkForm(implicit request: Request[_]) = Form {
    mapping(
      "how" -> nonEmptyText,
      "notif" -> nonEmptyText,
      "markup" -> text.verifying("Unknown!",
        request.queryString("wc").headOption.exists(_ == "0") || Wikis.markups.contains(_)),
      "comment" -> text)(LinkWiki.apply)(LinkWiki.unapply) verifying(
        "Your entry failed the obscenity filter", { ew: LinkWiki => !Wikis.hasBadWords(ew.comment)
    })
  }

  val addForm = Form(
    "name" -> nonEmptyText.verifying(vBadWords, vSpec))

  def renameForm(wid:WID) = Form {
    tuple(
      "oldlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "newlabel" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("Name in same category already in use", { t: (String, String) =>
//        !Wikis(wid.getRealm).index.containsName(Wikis.formatName(t._2))
        !Wikis(wid.getRealm).index.getWids(Wikis.formatName(t._2)).exists(w=> w.cat == wid.cat && w.getRealm == wid.getRealm)
      })
  }

  def replaceAllForm = Form {
    tuple(
      "realm" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_)),
      "old" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)),
      "new" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)),
      "action" -> text.verifying("Obscenity filter", !Wikis.hasBadWords(_)).verifying("Invalid characters", vldSpec(_))) verifying
      ("haha", { t: (String, String, String, String) => true
      })
  }
}


