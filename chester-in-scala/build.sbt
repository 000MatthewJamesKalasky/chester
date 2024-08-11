import scala.scalanative.build._

val scala3Version = "3.4.2"
val graalVm = "graalvm-java22"
val graalVersion = "22.0.2"
val nativeImageOption = Seq(
  "--verbose",
  "--no-fallback",
  "--initialize-at-build-time=scopt,fastparse,scala,java,chester,org.eclipse,cats,fansi,sourcecode,com.monovore.decline"
)

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", xs@_*) => MergeStrategy.first
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}

ThisBuild / nativeConfig ~= (System.getProperty("os.name").toLowerCase match {
  case mac if mac.contains("mac") => { // mac has some bugs with optimizations
    _.withGC(GC.commix)
  }
  case _ => {
    _.withLTO(LTO.thin)
      .withMode(Mode.releaseFast)
      .withGC(GC.commix)
  }
})

lazy val common = crossProject(JSPlatform, JVMPlatform, NativePlatform).withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("common"))
  .settings(
    name := "ChesterCommon",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % "1.0.0" % Test,
    ),
    assembly / assemblyJarName := "common.jar",
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "fansi" % "0.5.0",
      "org.typelevel" %%% "cats-core" % "2.12.0",
      "com.lihaoyi" %%% "fastparse" % "3.1.0",
      "com.lihaoyi" %%% "pprint" % "0.9.0"
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "fansi" % "0.5.0",
      "org.typelevel" %%% "cats-core" % "2.12.0",
      "com.lihaoyi" %%% "fastparse" % "3.1.0",
      "com.lihaoyi" %%% "pprint" % "0.9.0"
    )
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "fansi" % "0.4.0",
      "org.typelevel" %%% "cats-core" % "2.11.0",
      "com.lihaoyi" %%% "fastparse" % "3.0.2",
      "com.lihaoyi" %%% "pprint" % "0.8.1"
    )
  )

lazy val cli = crossProject(JSPlatform, JVMPlatform, NativePlatform).withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("cli"))
  .enablePlugins(NativeImagePlugin)
  .dependsOn(common)
  .settings(
    name := "chester",
    scalaVersion := scala3Version,
    nativeImageVersion := graalVersion,
    nativeImageOptions := nativeImageOption,
    nativeImageJvm := graalVm,
    Compile / mainClass := Some("chester.cli.Main"),
    assembly / assemblyJarName := "chester.jar",
    nativeImageOutput := file("target") / "chester",
    libraryDependencies ++= Seq(
      "com.monovore" %%% "decline" % "2.4.1"
    )
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.jline" % "jline" % "3.26.2",
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
    )
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
    )
  )
lazy val lsp = crossProject(JVMPlatform).withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("lsp"))
  .enablePlugins(NativeImagePlugin)
  .dependsOn(common)
  .settings(
    name := "chester-lsp",
    scalaVersion := scala3Version,
    nativeImageVersion := graalVersion,
    nativeImageOptions := nativeImageOption,
    nativeImageJvm := graalVm,
    Compile / mainClass := Some("chester.lsp.Main"),
    libraryDependencies += "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.23.1",
    assembly / assemblyJarName := "chester-lsp.jar",
    nativeImageOutput := file("target") / "chester-lsp"
  )
  .jvmSettings(
  )

lazy val root = project
  .in(file("."))
  .aggregate(common.jvm, common.js, common.native, cli.jvm, cli.js, cli.native, lsp.jvm)
  .settings(
    name := "Chester",
    scalaVersion := scala3Version
  )