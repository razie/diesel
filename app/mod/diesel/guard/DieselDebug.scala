package mod.diesel.guard

import razie.wiki.Config

/** concentrates flags that control critical services that may run amock */
object DieselDebug {

  def ALLENABLED = true
  def AUTO = true

  object Guardian {
    def ISAUTO = ALLENABLED && AUTO && Config.prop("diesel.guardian.auto", "true").toBoolean
    def ISENABLED_LOCALHOST = ALLENABLED && true
    def ISENABLED = ALLENABLED && Config.prop("diesel.guardian.enabled", "true").toBoolean
  }

  object Cron {
    def ISENABLED = ALLENABLED && Config.prop("diesel.cron.enabled", "true").toBoolean
  }

}


