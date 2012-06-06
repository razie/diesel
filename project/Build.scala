import sbt._
import Keys._
import PlayProject._

object ApplicationBuild extends Build {

    val appName         = "racerkidz"
    val appVersion      = "1.0-SNAPSHOT"

    val appDependencies = Seq(
      // Add your project dependencies here,

       "commons-codec"       % "commons-codec"      % "1.4",
       "javax.mail"          % "mail"               % "1.4.5",
       "com.mongodb.casbah" %% "casbah"             % "2.1.5-1",
       "com.novus"          %% "salat-core"         % "0.0.8-SNAPSHOT",
       "com.tristanhunt"    %% "knockoff"           % "0.8.0-16",
       "com.razie"          %% "base"               % "0.6.2-SNAPSHOT",
       "com.razie"          %% "snakked"            % "0.4.3-SNAPSHOT",
       "ch.qos.logback"      % "logback-classic"    % "1.0.0",
       "org.scalatest"       % "scalatest_2.9.1"    % "1.6.1"
     )

    val main = PlayProject(appName, appVersion, appDependencies, mainLang = SCALA).settings(
      // Add your own project settings here      
       resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    )

}
