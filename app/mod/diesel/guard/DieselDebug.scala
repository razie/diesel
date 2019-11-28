package mod.diesel.guard

import razie.wiki.Config

/** concentrates flags that control critical services that may run amock */
object DieselDebug {

  val ALLENABLED = true
  val AUTO = true
  def DEVMODE = Config.isDevMode


  object Guardian {
    def ISAUTO = ALLENABLED && AUTO && Config.prop("diesel.guardian.auto", "true").toBoolean && !DEVMODE
    def ISENABLED_LOCALHOST = ALLENABLED && true
    def ISENABLED = ALLENABLED && Config.prop("diesel.guardian.enabled", "true").toBoolean
  }

  object Cron {
    def ISENABLED = ALLENABLED && Config.prop("diesel.cron.enabled", "true").toBoolean
  }

}


