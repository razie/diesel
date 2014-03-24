import sbt._
import Keys._
import PlayProject._

object V {
  val version      = "0.1.2-SNAPSHOT"
  val scalaVersion = "2.10.2" 
  val organization = "com.razie"

  def snap = (if (V.version endsWith "-SNAPSHOT") "-SNAPSHOT" else "")
}

object ApplicationBuild extends Build {

  val appName         = "racerkidz"
  val appVersion      = V.version

  val appDependencies = Seq(
    // Add your project dependencies here,

    "commons-codec"       % "commons-codec"      % "1.4",
    "javax.mail"          % "mail"               % "1.4.5",
    "org.mongodb"        %% "casbah"             % "2.5.0",
    "com.novus"          %% "salat-core"         % "1.9.2",
    "com.tristanhunt"    %% "knockoff"           % "0.8.1",
    "com.razie"          %% "base"               % "0.6.5-SNAPSHOT",
    "com.razie"          %% "snakked"            % "0.6.5-SNAPSHOT",
    "com.razie"          %% "scripster"          % "0.8.5-SNAPSHOT",
//    "com.razie"          %% "gremlins"           % "0.6.4-SNAPSHOT",
    "ch.qos.logback"      % "logback-classic"    % "1.0.13",
    //"org.scalaz"         %% "scalaz-core"        % "7.0-SNAPSHOT",
    "org.scalaz"         %% "scalaz-core"        % "7.0.3",
    "org.scalatest"      %% "scalatest"          % "1.9.2"
    )

    val repos = Seq("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
                    "releases"  at "http://oss.sonatype.org/content/repositories/releases")

  val main = play.Project(appName, appVersion, appDependencies).settings(
      routesImport  += "model.Binders._",
      resolvers    ++= repos,
      scalaVersion := V.scalaVersion,
      sources in doc in Compile := List()
    )

/*
  val wcommon = play.Project("wcommon", appVersion, appDependencies, path = file("modules/wcommon")).settings(
      routesImport  += "model.Binders._",
      resolvers    ++= repos,
      sources in doc in Compile := List()
    )

  val wiki = play.Project("wiki", appVersion, appDependencies, path = file("modules/wiki")).settings(
      routesImport  += "model.Binders._",
      resolvers    ++= repos,
      sources in doc in Compile := List()
    ).dependsOn (
      wcommon
    ).aggregate (
      wcommon
    )

  val main = play.Project(appName, appVersion, appDependencies).settings(
      routesImport  += "model.Binders._",
      resolvers    ++= repos,
      sources in doc in Compile := List()
    ).dependsOn (
       wcommon, wiki
    ).aggregate (
       wcommon, wiki
    )
*/

}

