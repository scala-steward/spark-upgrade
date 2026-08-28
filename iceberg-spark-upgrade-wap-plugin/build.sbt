import Dependencies._

// Everything here resolves from Maven Central. Resolver.sonatypeRepo is
// deprecated (and gone in sbt 2), the typesafe/sbt-plugin repos were only ever
// needed for plugins this build does not use, and Resolver.mavenLocal in a
// checked-in build makes the build depend on whatever happens to be in the
// developer's ~/.m2.

// Share the repo-root .scalafmt.conf rather than keeping a second copy here; this
// is a separate sbt build, so it does not find the root one on its own.
ThisBuild / scalafmtConfig := (ThisBuild / baseDirectory).value.getParentFile / ".scalafmt.conf"

// 2.12.8 dates from 2018 and its compiler bridge cannot be built by JDK 21
// ("Error compiling the sbt component 'compiler-bridge_2.12'"), so this module only
// ever built because the runner image happened to default to JDK 11. CI now pins
// JDK 11 explicitly, and this bump means a later JDK will not detonate.
//
// 2.12.15 rather than the newest 2.12.x, deliberately: this jar is a -javaagent
// loaded into somebody else's JVM (pipelinecompare/domagic.py attaches it to both
// the Spark 2.4.8 and 3.3.1 pipelines) and it bundles no scala-library of its own,
// so it links against whatever the host provides. Scala's 2.12.x compatibility
// guarantee is backward-only, and 3.3.1 ships scala-library 2.12.15 -- compiling
// against exactly that means the agent can never reference a member the host lacks.
// Verified both 2.12.15 and 2.12.18 build the compiler bridge fine on JDK 21, so
// the newer one buys nothing here.
ThisBuild / scalaVersion := "2.12.15"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.holdenkarau"
ThisBuild / organizationName := "holdenkarau"

Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
Test / parallelExecution := false
Test / fork := true
// Ask sbt where the jar is instead of restating the Scala binary version, the
// artifact name and the project version by hand -- that spelling silently went
// stale on any of the three changing.
Test / javaOptions += s"-javaagent:${(Compile / packageBin / artifactPath).value}"
Test / compile := ((Test / compile) dependsOn (Compile / Keys.`package`)).value

lazy val root = (project in file("."))
  .settings(
    name := "Iceberg Spark Upgrade WAP Plugin",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += icebergSparkRuntime % Test,
    libraryDependencies += sparkTestingBase % Test,
    libraryDependencies += iceberg % Provided
  )

// Since sbt generates a MANIFEST.MF file rather than storing one in resources and dealing the conflict
// just add our properties to the one sbt generates for us.
Compile / packageBin / packageOptions ++= List(
  Package.ManifestAttributes("Premain-Class" -> "com.holdenkarau.spark.upgrade.wap.plugin.Agent"),
  Package.ManifestAttributes("Agent-Class" -> "com.holdenkarau.spark.upgrade.wap.plugin.Agent"),
  // The JVM agent spec defines this one as a boolean, so the class name that used
  // to be here parsed as false and isRedefineClassesSupported() was quietly off.
  // Nothing needs redefinition today (Agent.premain ignores its Instrumentation and
  // only registers an Iceberg listener), but a wrong value is worse than either
  // answer: it would fail at the point someone adds a retransform, in the last
  // place they would look.
  Package.ManifestAttributes("Can-Redefine-Classes" -> "true")
)
