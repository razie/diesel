/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package mod.diesel.controllers

import razie.wiki.model.WikiEntry
import razie.wiki.mods.WikiMod

/** diesel controller */
class FiddleMod extends WikiMod {
  def modName:String = "FiddleMod"

  def modProps:Seq[String] = Seq()

  def isInterested (we:WikiEntry) : Boolean =
    we.tags.contains("fiddle") && we.tags.contains("js")

  override def modPostHtml (we:WikiEntry, html:String) : String = {
    s"""
      |<a href="/sfiddle/playinbrowser/js?wpath=${we.wid.wpath}" class="btn btn-info">Fiddle!</a>
      |<p></p>
    """.stripMargin + html
  }
}

/** diesel controller */
class DieselMod extends WikiMod {
  def modName:String = "DieselMod"

  def modProps:Seq[String] = Seq()

  def isInterested (we:WikiEntry) : Boolean =
    false
}


