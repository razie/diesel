import sbt._
import Keys._
import play.PlayScala._
import play.sbt.routes.RoutesKeys
import play.twirl.sbt.Import.TwirlKeys

object V {
  val version      = "0.9.2-SNAPSHOT"
  val scalaVersion = "2.11.8"
  val organization = "com.razie"

  def snap = (if (V.version endsWith "-SNAPSHOT") "-SNAPSHOT" else "")
}

object ApplicationBuild extends Build {

  val appVersion      = V.version

  val appDependencies = Seq(
    //cache,

//    "com.github.fge"      % "json-patch"    % "1.9",

//    "org.apache.camel"   %% "camel-core"         % "2.18.3",

    "com.googlecode.java-diff-utils"        % "diffutils"             % "1.2.1",

// for snakked
   "org.json"       % "json"            % "20160810",
   "commons-jxpath" % "commons-jxpath"  % "1.3",
   "org.scala-lang.modules" %% "scala-xml" % "1.0.3",

//    "com.razie"          %% "base"               % "0.9.2-SNAPSHOT",
//    "com.razie"          %% "snakked"            % "0.9.2-SNAPSHOT",
//    "com.razie"          %% "scripster-core"     % "0.9.2-SNAPSHOT"

    "org.antlr" % "antlr4" % "4.5.3"
    )

    val repos = Seq("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
                    "releases"  at "https://oss.sonatype.org/content/repositories/releases"
    )

  lazy val wcommon = Project("wcommon", file("modules/wcommon")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common/app",
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki/app",
      sourceDirectories in Compile += baseDirectory.value / "../coolscala/common/app",
      // sourceDirectories in Compile += baseDirectory.value / "../coolscala/diesel/app",
      sourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki/app",
      RoutesKeys.routesImport  += "model.Binders._"
    )):_*
  )

  lazy val wiki = Project("wiki", file("modules/wiki")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common",
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki",
    sourceDirectories in (Compile, TwirlKeys.compileTemplates) += baseDirectory.value / "../coolscala/wiki/app",
      RoutesKeys.routesImport  += "model.Binders._"
    )):_*
  ).dependsOn (
    wcommon
  ).aggregate (
    wcommon
  )

  lazy val root = Project("racerkidz", file(".")).enablePlugins(play.PlayScala).settings(
    (baseSettings ++ Seq(
      libraryDependencies ++= appDependencies,
//      unmanagedSourceDirectories in Compile += baseDirectory.value / "../../w2/com.razie.dsl1/src-gen",
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../razbase/base/src/main/scala",
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../snakked/core/src/main/scala",
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common",
      // unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/diesel",
      unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki",
    sourceDirectories in (Compile, TwirlKeys.compileTemplates) += baseDirectory.value / "../coolscala/wiki/app",
//      unmanagedSourceDirectories in TwirlKeys.compileTemplates += baseDirectory.value / "../coolscala/wiki/app",
      RoutesKeys.routesImport  += "model.Binders._"
    )):_*
    ).dependsOn (
       wcommon, wiki //, file("../coolscala/common"), file("../coolscala/wiki")
    ).aggregate (
       wcommon, wiki //, file("../coolscala/common"), file("../coolscala/wiki")
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
