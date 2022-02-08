/**
  *    ____    __    ____  ____  ____,,___     ____  __  __  ____
  *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \          Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.model

import razie.diesel.engine.DomEngineSettings.DEFAULT_TQ_SPECS
import razie.diesel.engine.nodes.EnginePrep
import razie.tconf.{SpecRef, TSpecRef, TagQuery}
import razie.wiki.model.{WID, WikiSearch, Wikis}
import scala.::

/** a target for a message: either a specified list of config, or a realm
  *
  * @param realm   - the target realm
  * @param env     - the target env inside the target realm
  * @param specs   - list of specifications to get the rules from
  * @param stories - optional list of stories to execute and validate
  */
class DieselTarget(
  val realm: String,
  val env: String = DieselTarget.DEFAULT) {

  def specs: List[TSpecRef] = Nil

  def stories: List[TSpecRef] = Nil
}

object DieselTarget {
  final val DEFAULT = "default"

  /** NOTE THIS may be SLOW if you use it to compile - the WIDs are fresh with non-cached pages, make sure you cache via
    * WikiUtil.cachedPage
    *
    * find all specs starting from realm and tq, including mixins except private
    *
    * @param realm
    * @param tq
    * @return
    */
  def tqSpecs(realm: String, tq: TagQuery) = {
    // this and mixins
    val wes = if(false && tq.isEmpty) {
      EnginePrep.catPages("Spec", realm, overwriteTopics = true)
    } else {
      val w = Wikis(realm)
      val tags = tq.and("spec").tags
      val irdom = (
          WikiSearch.getList(realm, "", "", tags) :::
              w.mixins.flattened.flatMap(r =>
                WikiSearch
                    .getList(r.realm, "", "", tags)
                    .filter(x => !(x.tags.contains("private")))
              )
          )

      EnginePrep.mixinEntries(irdom)
    }

    wes.map(_.wid.toSpecPath)
  }

  /** the environment settings - most common target */
  def ENV_SETTINGS(realm: String) =
    SpecRef(realm, realm + ".Spec:EnvironmentSettings", "EnvironmentSettings")

  /** specific list of specs to use */
  def from(realm: String, env: String, specs: List[TSpecRef], stories: List[TSpecRef]) =
    new DieselTargetList(realm, env, specs, stories)

  /** all specs in a realm and mixins */
  def ENV(realm: String, env: String = DEFAULT) =
    new DieselTarget(realm, env) {
      override def specs = {
        ENV_SETTINGS(realm) :: tqSpecs(realm, TagQuery.EMPTY)
      }
    }

  /** the diesel section from the reactor topic */
  def REALMDIESEL(realm: String, env: String = DEFAULT) =
    DieselTarget.from(
      realm,
      env,
      WID.fromPath(s"${realm}.Reactor:${realm}#diesel").map(_.toSpecPath).toList,
      Nil)

  /** list of topics by tq */
  def TQSPECS(realm: String, env: String, tq: TagQuery) =
    new DieselTarget(realm, env) {

      override def specs = {
        ENV_SETTINGS(realm) :: tqSpecs(realm, tq)
      }
    }

  /** list of topics by tq */
  def TQSPECSANDSTORIES(realm: String, env: String, tq: TagQuery) =
    new DieselTarget(realm, env) {

      override def specs = {
        ENV_SETTINGS(realm) :: tqSpecs(realm, tq)
      }

      override def stories = {
        // stories just in current realm
        val irdom = WikiSearch.getList(realm, "", "", tq.and("story").tags)

        ENV_SETTINGS(realm) :: irdom.map(_.wid.toSpecPath)
      }
    }

  /** the environment settings - most common target */
  def RK = new DieselTarget("rk")
}

/** target a specified set of specs */
case class DieselTargetList(
  override val realm: String,
  override val env: String,
  override val specs: List[TSpecRef],
  override val stories: List[TSpecRef]) extends DieselTarget(realm)

