import sbt._
import Keys._
import play.PlayScala._
import play.sbt.routes.RoutesKeys

object V {
  val version      = "0.9.1-SNAPSHOT"
  val scalaVersion = "2.11.8" 
  val organization = "com.razie"

  def snap = (if (V.version endsWith "-SNAPSHOT") "-SNAPSHOT" else "")
}

object ApplicationBuild extends Build {

  val appVersion      = V.version

  val appDependencies = Seq(
    //cache,

    "commons-codec"       % "commons-codec"      % "1.4",
    "javax.mail"          % "mail"               % "1.4.5",
    "ch.qos.logback"      % "logback-classic"    % "1.0.13",
    "org.mongodb"        %% "casbah"             % "2.8.2", //"2.6.5", //"2.5.0",
    "com.novus"          %% "salat-core"         % "1.9.9", //"1.9.2", //"1.9.6",
    "com.atlassian.commonmark"   % "commonmark"  % "0.5.0",
    "org.scalaz"         %% "scalaz-core"        % "7.2.1",
    "org.scalatest"      %% "scalatest"          % "2.1.3",
    "com.typesafe"        % "config"             % "1.2.1",
    "com.typesafe.akka"  %% "akka-cluster"       % "2.4.2", //2.2.0
    "com.typesafe.akka"  %% "akka-slf4j"         % "2.4.2", //2.2.0

    "com.googlecode.java-diff-utils"        % "diffutils"             % "1.2.1",

    "org.antlr" % "antlr4" % "4.5.3",

    "com.razie"          %% "base"               % "0.9.1",//-SNAPSHOT",
    "com.razie"          %% "snakked"            % "0.9.1",//-SNAPSHOT",
    "com.razie"          %% "scripster-core"     % "0.9.1"//-SNAPSHOT"
    )

    val repos = Seq("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
                    "releases"  at "https://oss.sonatype.org/content/repositories/releases"
    )

  lazy val root = Project("racerkidz", file(".")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
      RoutesKeys.routesImport  += "model.Binders._",
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../../w2/com.razie.dsl1/src-gen",
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common"
    )):_*
    ).dependsOn (
       wcommon, wiki
    ).aggregate (
       wcommon, wiki
    )

  lazy val wcommon = Project("wcommon", file("modules/wcommon")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
      RoutesKeys.routesImport  += "model.Binders._"
    )):_*
  )

  lazy val wiki = Project("wiki", file("modules/wiki")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
      RoutesKeys.routesImport  += "model.Binders._"
    )):_*
  ).dependsOn (
    wcommon
  ).aggregate (
    wcommon
  )

  def baseSettings = /*Defaults.defaultSettings ++*/ Seq (
    scalaVersion         := V.scalaVersion,
    version              := V.version,
    organization         := V.organization,
    organizationName     := "Razie's Pub",
    organizationHomepage := Some(url("http://www.razie.com")),

    sources in doc in Compile := List(),

    publishTo <<= version { (v: String) =>
      if(v endsWith "-SNAPSHOT")
        Some ("Sonatype" at "https://oss.sonatype.org/content/repositories/snapshots/")
      else
        Some ("Sonatype" at "https://oss.sonatype.org/content/repositories/releases/")
    } ,

    resolvers ++= Seq(
      "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
      "releases"  at "https://oss.sonatype.org/content/repositories/releases",
      "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
      "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"
    )

  )

}

