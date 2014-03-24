/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package controllers

import admin.IgnoreErrors
import admin.VError
import db.RTable
import model.WID
import model.WikiEntry
import model.WikiUser

/** minimum authorization functionality required - provide your own in Global::beforeStart */
trait WikiAuthorization {
  
  /** can user see a topic with the given properties? */
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry]=None)(implicit errCollector: VError = IgnoreErrors): Boolean

  /**
   * can the user see the topic - a little more checks than isVisibile - this is the one to use
   *
   * can pass admin.IgnoreErrors as an errCollector
   */
  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VError): Option[Boolean]

  /**
   * can the user edit the topic
   *
   *  can pass admin.IgnoreErrors as an errCollector
   */
  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VError): Option[Boolean]

  protected def wvis(props: Option[Map[String, String]]): Option[String] =
    props.flatMap(p => p.get("wvis").orElse(p.get("visibility"))).map(_.asInstanceOf[String])
}

/** stub sample authorization implementation */
class NoWikiAuthorization extends WikiAuthorization {
  import Visibility._

  /** can user see a topic with the given properties? */
  def isVisible(u: Option[WikiUser], props: Map[String, String], visibility: String = "visibility", we: Option[WikiEntry]=None)(implicit errCollector: VError = IgnoreErrors): Boolean = {
    true
  }

  /**
   * can the user see the topic - a little more checks than isVisibile - this is the one to use
   *
   * can pass admin.IgnoreErrors as an errCollector
   */
  def canSee(wid: WID, au: Option[WikiUser], w: Option[WikiEntry])(implicit errCollector: VError): Option[Boolean] = {
    Some(true)
  }

  /**
   * can the user edit the topic
   *
   *  can pass admin.IgnoreErrors as an errCollector
   */
  def canEdit(wid: WID, u: Option[WikiUser], w: Option[WikiEntry], props: Option[Map[String, String]] = None)(implicit errCollector: VError): Option[Boolean] = {
    Some(true)
  }
}
