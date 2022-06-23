/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  * */
package controllers

import org.joda.time.DateTime
import razie.wiki.admin.Autosave
import razie.wiki.model.{WID, WikiEntry}
import razie.{Logging, js}

/** metadata about a we */
case class WEAbstract(id: String, cat: String, name: String, realm: String, ver: Int, updDtm: DateTime, hash: Int,
                      tags: String, drafts: Int) {

  def this(we: WikiEntry) = this(
    we._id.toString,
    we.category,
    we.name,
    we.realm,
    we.ver,
    we.updDtm,
    we.content.hashCode,
    we.tags.mkString,
    Autosave.allDrafts(we.wid).toList.size
  )

  def this(x: collection.Map[String, String]) = this(
    x("id"),
    x("cat"),
    x("name"),
    x("realm"),
    x("ver").toInt,
    new DateTime(x("updDtm")),
    x("hash").toInt,
    x("tags"),
    x("drafts").toInt
  )

  def j = js.tojson(
    Map("id" -> id, "cat" -> cat, "name" -> name, "realm" -> realm, "ver" -> ver.toString, "updDtm" -> updDtm,
      "hash" -> hash.toString, "tags" -> tags, "drafts" -> drafts.toString))
}

