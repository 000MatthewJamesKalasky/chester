val scala3Version = "3.4.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "Chester Language Interpreter",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies += "com.lihaoyi" %% "fastparse" % "3.1.0",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test
  )
