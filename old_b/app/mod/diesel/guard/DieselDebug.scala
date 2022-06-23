package mod.diesel.guard

import razie.hosting.Website
import razie.wiki.{Config, Services}

/** concentrates flags that control critical services that may run amock */
object DieselDebug {

  private val ALLENABLED = true
  private val AUTO = false
  private val SCHEDULED = true // allow scheduled pollers

  private def DEVMODE = Services.config.isDevMode

  /** guardian settings */
  object Guardian {

    /** is in auto mode */
    def ISAUTO = ALLENABLED && AUTO && Services.config.prop("diesel.guardian.auto", "false").toBoolean && DEVMODE

    /** is in auto mode */
    def ISSCHED = ALLENABLED && SCHEDULED && Services.config.prop("diesel.guardian.scheduled", "true").toBoolean //&& !DEVMODE

    /** sometimes you want it disabled in development in localhost */
    def ISENABLED_LOCALHOST = ALLENABLED && true

    /** master enabled setting */
    def ISENABLED = ALLENABLED && Services.config.prop("diesel.guardian.enabled", "true").toBoolean

    def autoQuery(realm: String) =
      Website.forRealm(realm)
          .flatMap(_.prop("guardian.settings.query"))
          .getOrElse("story/sanity/-skip/-manual")

  }

  object Cron {
    /** master enabled setting */
    def ISENABLED = ALLENABLED && Services.config.prop("diesel.cron.enabled", "true").toBoolean
  }

}


