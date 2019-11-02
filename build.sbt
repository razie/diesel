import play.PlayScala._
import play.sbt.routes.RoutesKeys
import play.twirl.sbt.Import.TwirlKeys

scalaVersion := "2.11.12"

name := "racerkidz"

routesImport  ++= Seq("model.Binders._")

lazy val commonSettings = Seq(
  organization := "com.razie",
  version := "0.9.2-SNAPSHOT",
  scalaVersion := "2.11.12",

  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in packageDoc := false,
  sources in (Compile,doc) := Seq.empty,

  organizationName     := "Razie's Pub",
  organizationHomepage := Some(url("http://www.razie.com")),
  licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
  homepage := Some(url("http://www.razie.com"))
)

libraryDependencies in Global ++= Seq(
  cache,

// for snakked
  "org.json"                  % "json"             % "20160810",
  "commons-jxpath"            % "commons-jxpath"   % "1.3",
  "org.scala-lang.modules"   %% "scala-xml"        % "1.0.3",
  "ch.qos.logback"            % "logback-classic"  % "1.0.13",
  "commons-codec"             % "commons-codec"    % "1.4",
  "javax.mail"                % "mail"             % "1.4.5",
  "com.googlecode.java-diff-utils" % "diffutils"   % "1.2.1",
  "org.antlr"                 % "antlr4"           % "4.5.3",

  "org.mongodb"              %% "casbah"           % "2.8.2",
  "com.novus"                %% "salat-core"       % "1.9.9",

  "com.typesafe"              % "config"           % "1.2.1",

  "com.atlassian.commonmark"  % "commonmark"                % "0.9.0",
  "com.atlassian.commonmark"  % "commonmark-ext-gfm-tables" % "0.9.0",

  "com.typesafe.akka"        %% "akka-cluster"       % "2.4.2",
  "com.typesafe.akka"        %% "akka-cluster-tools" % "2.4.2",
  "com.typesafe.akka"        %% "akka-contrib"       % "2.4.2",
  "com.typesafe.akka"        %% "akka-slf4j"         % "2.4.2",
  "com.typesafe.akka"        %% "akka-camel"         % "2.4.2",
  "com.typesafe.akka"        %% "akka-testkit"       % "2.4.2" % Test,

  "org.scalaz"               %% "scalaz-core"        % "7.2.1",

  "org.scalatest"            %% "scalatest"          % "2.1.3",
  "org.scalatestplus.play"   %% "scalatestplus-play" % "1.5.0" % Test
)

lazy val root = (project in file(".")).enablePlugins(PlayScala)
  .settings(
    commonSettings,

    unmanagedSourceDirectories in Compile += baseDirectory.value / "../snakked/base/src/main/scala",
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../snakked/core/src/main/scala",

    unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/diesel/src/main/scala",
    unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki/app",

    unmanagedSourceDirectories in Test += baseDirectory.value / "../coolscala/diesel/src/test/scala",

    sourceDirectories in (Compile, TwirlKeys.compileTemplates) += baseDirectory.value / "../coolscala/wiki/app"

  )//.aggregate(wcommon, wiki)

/*lazy val wcommon = (project in file("modules/wcommon")).enablePlugins(PlayScala)
  .settings(
    commonSettings,

    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../snakked/base/src/main/scala",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../snakked/core/src/main/scala",

    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/diesel/src/main/scala",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common/app",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki/app",

    RoutesKeys.routesImport  += "model.Binders._"
  )

lazy val wiki = (project in file("modules/wiki")).enablePlugins(PlayScala)
  .settings(
    commonSettings,

    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../../snakked/base/src/main/scala",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../../snakked/core/src/main/scala",

    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/diesel/src/main/scala",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/common/app",
    //unmanagedSourceDirectories in Compile += baseDirectory.value / "../coolscala/wiki/app"
  )
  .dependsOn(wcommon).aggregate(wcommon)*/

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

publishTo in Global := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { x => false }

val printTests = taskKey[Unit]("something")

printTests := {
  val tests = (definedTests in Test).value
  tests map { t =>
    println(t.name)
  }
}

