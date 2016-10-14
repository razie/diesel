
scalaVersion := "2.11.8"

retrieveManaged := true // copy libs in lib_managed

routesImport  ++= Seq("model.Binders._")

libraryDependencies ++= Seq(cache)

