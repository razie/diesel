/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki.model

import razie.diesel.dom.WikiDomain
import razie.hosting.WikiReactors
import razie.wiki.util.DslProps
import scala.concurrent.{Future, Promise}
import scala.util.Try

/** a hosted wiki instance, i.e. independent hosted website.
  *
  * It has its own index, domain and is independent of other wikis
  *
  * It has its own users and admins/mods etc
  *
  * Wikis can mixin other wikis - linearized multiple inheritance.
  */
trait Reactor {
  val ready: Promise[Boolean] = Promise[Boolean]()

  def realm: String

  def fallBacks: List[Reactor]

  def we: Option[WikiEntry]

  def wiki: WikiInst

  def domain: WikiDomain

  /** list of supers - all mixins reactors linearized */
  val supers: Array[String]

  /** all mixed in reactors, linearized */
  val mixins: Mixins[Reactor]

  def club: Option[WikiEntry]

  def userRoles: List[String]

  def adminEmails: List[String]

  /** Admin:UserHome if user or Admin:Home or Reactor:realm if nothing else is defined */
  def mainPage(au: Option[WikiUser]): WID

  def websiteProps: DslProps

  //todo fallback also in real time to rk, per prop
  // todo listen to updates and reload
  def props: DslProps

  /** the membership level of the owner (see if it's paid etc) */
  def membershipLevel:Option[String]
}

/** a hosted wiki instance, i.e. independent hosted website.
  *
  * It has its own index, domain and is independent of other wikis
  *
  * It has its own users and admins/mods etc
  *
  * Wikis can mixin other wikis - linearized multiple inheritance.
  */
abstract class ReactorImpl (val realm:String, val fallBacks:List[Reactor] = Nil, val we:Option[WikiEntry]) extends Reactor {
  override val mixins = new Mixins[Reactor](fallBacks)
  override lazy val club = props.wprop("club").flatMap(Wikis.find)

  override lazy val userRoles = websiteProps.prop("userRoles").toList.flatMap(_.split(','))
  override lazy val adminEmails = websiteProps.prop("adminEmails").toList.flatMap(_.split(','))

  // list of super reactors linearized
  override val supers : Array[String] = {
    if(realm == WikiReactors.RK) Array(WikiReactors.RK)
    else mixins.flattened.map(_.realm).toArray
  }

  /** Admin:UserHome if user or Admin:Home or Reactor:realm if nothing else is defined */
  override def mainPage(au:Option[WikiUser]) = {
    def dflt = WID("Admin", "UserHome").r(realm).page.map(_.wid)
//    val p = au.flatMap {user=>
//      if(adminEmails.contains(Dec(user.email)))
//        WID("Admin", "AdminHome").r(realm).page.map(_.wid) orElse dflt
//      else
//        club.flatMap(c=> user.myPages(c.realm, "Club").find(_.uwid.id == c._id)).flatMap {uw=>
//          WID("Admin", uw.role+"Home").r(realm).page.map(_.wid) orElse dflt
//        } orElse
//          dflt
      val p = au.flatMap {user=>
        dflt
    } orElse WID("Admin", "Home").r(realm).page.map(_.wid) getOrElse WID("Reactor", realm).r(realm)
    p
  }

  private def sectionProps(section:String) = {
    we.orElse(WID("Reactor", realm).r(realm).page).map{p=>
      new DslProps(Some(p), section)
    } getOrElse
      WikiReactors.fallbackProps
  }

  /* deprecated - use props */
  override lazy val websiteProps = sectionProps("website,properties")// :: sectionProps("properties")

  //todo fallback also in real time to rk, per prop
  // todo listen to updates and reload
  override lazy val props = sectionProps ("properties,website") //:: sectionProps("website")

  /** the membership level of the owner (see if it's paid etc) */
  override def membershipLevel:Option[String] = {
    we.flatMap(_.owner).map(_.membershipLevel)
  }
}

