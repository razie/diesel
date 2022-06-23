/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package razie.wiki

import com.typesafe.config.ConfigFactory
import play.Configuration
import razie.wiki.model.{WikiConfigChanged, WikiEntry, WikiEvent, WikiObservers}

/**
  * configuration static
  *
  * todo config should be injected not static
  *
  * Use Services.config instead of Config
  */
object Config extends WikiConfig {

  val verMap = {
    val ver = ConfigFactory.load("ver.conf")

    def prop(n: String) = if (ver.hasPath(n)) ver.getString(n) else ""

    Map(
      "buildBaseDtm" -> prop("diesel.build.base.dtm"),
      "buildBaseVer" -> prop("diesel.build.base.ver"),
      "buildImplDtm" -> prop("diesel.build.impl.dtm"),
      "buildImplVer" -> prop("diesel.build.impl.ver")
    )
  }

  override def simulateHost = isimulateHost

  var trustLocalUsers = prop("diesel.trustLocalUsers", "true").toBoolean

  WikiObservers mini {
    case WikiEvent(_, "WikiEntry", _, Some(x), _, _, _)
      if "Admin" == x.asInstanceOf[WikiEntry].category && WikiConfig.CFG_PAGES.contains(
        x.asInstanceOf[WikiEntry].name) => {
      reloadUrlMap
    }

    case WikiConfigChanged(node, config) => {
      trustLocalUsers = prop("diesel.trustLocalUsers", "true").toBoolean
    }
  }
}

