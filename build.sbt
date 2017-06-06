
scalaVersion := "2.11.8"

retrieveManaged := true // copy libs in lib_managed

routesImport  ++= Seq("model.Binders._")

libraryDependencies ++= Seq(
  cache,
  "commons-codec"         % "commons-codec"      % "1.4",
  "javax.mail"            % "mail"               % "1.4.5",
  "ch.qos.logback"        % "logback-classic"    % "1.0.13",
  "org.mongodb"          %% "casbah"             % "2.8.2",
  "com.novus"            %% "salat-core"         % "1.9.9",

  "com.atlassian.commonmark"   % "commonmark"    % "0.9.0",
  "com.atlassian.commonmark"   % "commonmark-ext-gfm-tables"    % "0.9.0",

  "org.scalaz"           %% "scalaz-core"        % "7.2.1",
  "org.scalatest"        %% "scalatest"          % "2.1.3",
  "com.typesafe.akka"    %% "akka-cluster"       % "2.4.2",
  "com.typesafe.akka"    %% "akka-cluster-tools" % "2.4.2",
  "com.typesafe.akka"    %% "akka-contrib"       % "2.4.2",
  "com.typesafe.akka"    %% "akka-slf4j"         % "2.4.2",
  "com.typesafe.akka"    %% "akka-camel"         % "2.4.2",

  "com.typesafe"          % "config"             % "1.2.1" // last one, eh?

)
