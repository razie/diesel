import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "racerkidz"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      // Add your project dependencies here,

       "javax.mail" % "mail" % "1.4.5",
       "com.mongodb.casbah" %% "casbah" % "2.1.5-1",
       "com.novus" %% "salat-core" % "0.0.8-SNAPSHOT",
       "com.tristanhunt" %% "knockoff" % "0.8.0-16",
       "com.razie" %% "base" % "0.6-SNAPSHOT",
       "com.razie" %% "snakked" % "0.4-SNAPSHOT",
       "ch.qos.logback" % "logback-classic" % "1.0.0"
    )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here      
    )

}
