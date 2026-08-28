val sparkVersion = settingKey[String]("Spark version")
val srcSparkVersion = settingKey[String]("Source Spark version")
val targetSparkVersion = settingKey[String]("Target Spark version")

// Spark versions must be compared numerically, not lexicographically: plain String
// comparison gets "10.0" < "3.1" wrong, and these guards decide which Scala versions
// we build against. sbt's own VersionNumber already implements this, so use it rather
// than hand-rolling a comparator we would then have to trust.
//
// Two caveats, both about values that only arrive via -DsparkVersion:
//   - SemanticSelector ranks a pre-release below the same numeric release, so
//     "3.0.0-preview2" would NOT be >= "3.0.0". Compare on the numeric part only,
//     which is what these Spark-capability floors actually mean.
//   - -DsparkVersion= sets the property to "", which System.getProperty returns in
//     preference to the default, and VersionNumber("") throws an opaque error.
//     Fail with a message that names the problem instead. Not a fallback on
//     purpose: there is no sensible Scala version for an unreadable Spark version,
//     and the old String comparison quietly chose 2.11 for it.
def sparkAtLeast(version: String, floor: String): Boolean = {
  val numeric = version.trim.takeWhile(c => c.isDigit || c == '.').stripSuffix(".")
  require(numeric.nonEmpty, s"could not read a Spark version from '$version'")
  VersionNumber(numeric).matchesSemVer(SemanticSelector(">=" + floor))
}

// One .scalafmt.conf at the repo root, shared with the WAP plugin's separate build.
// sbt-scalafmt defaults this to <build root>/.scalafmt.conf, which would be a
// scalafix/-local copy.
ThisBuild / scalafmtConfig := (ThisBuild / baseDirectory).value.getParentFile / ".scalafmt.conf"

lazy val V = _root_.scalafix.sbt.BuildInfo
inThisBuild(
  List(
    organization := "com.holdenkarau",
    homepage := Some(url("https://github.com/holdenk/spark-upgrade")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    srcSparkVersion := System.getProperty("sparkVersion", "2.4.8"),
    targetSparkVersion := System.getProperty("targetSparkVersion", "3.3.0"),
    sparkVersion := srcSparkVersion.value,
    // actual version is pulled from tags.
    versionScheme := Some("early-semver"),
    // publishTo/publishMavenStyle/signing are all set by sbt-ci-release.
    developers := List(
      Developer(
        "holdenk",
        "Holden Karau",
        "holden@pigscanfly.ca",
        url("https://github.com/holdenk/spark-upgrade")
      )
    ),
    scalaVersion := {
      if (sparkAtLeast(sparkVersion.value, "2.4")) {
        V.scala212
      } else {
        V.scala211
      }
    },
    // The Spark-facing projects (input/output) can only use the Scala versions
    // Spark itself publishes for. Spark dropped 2.11 in 3.0 and added 2.13 in 3.2.
    crossScalaVersions := {
      if (sparkAtLeast(sparkVersion.value, "3.2.0")) {
        List(V.scala212, V.scala213)
      } else if (sparkAtLeast(sparkVersion.value, "3.0.0")) {
        List(V.scala212)
      } else if (sparkAtLeast(sparkVersion.value, "2.4")) {
        List(V.scala211, V.scala212)
      } else {
        List(V.scala211)
      }
    },
    addCompilerPlugin(scalafixSemanticdb),
    scalacOptions ++= List(
      "-Yrangepos",
      "-P:semanticdb:synthetics:on"
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/holdenk/spark-upgrade"),
        "scm:git@github.com:holdenk/spark-upgrade.git"
      )
    ),
    publish / skip := false
  )
)

publish / skip := true

// `rules` depends on scalafix-core alone, not on Spark, so it can be built for any
// Scala version scalafix supports -- it does not inherit input/output's constraint.
// That is what kept us from ever publishing a 2.13 rules jar even though
// rules/src/main/scala-2.13/ exists: no sparkVersion we release with is >= 3.2, so
// 2.13 never entered crossScalaVersions, and Spark 4.x users (2.13-only) had nothing
// to depend on.
//
// It is still worth deriving from sparkVersion rather than flattening to all three,
// because moduleName carries the source Spark version: a blanket list would publish
// spark-scalafix-rules-2.1.1_2.13 -- a 2.13 build of rules for migrating off a Spark
// release that never had a 2.13 build. So: add 2.13 wherever 2.12 was already in
// play, and leave the genuinely 2.11-only source versions alone.
lazy val rules = project.settings(
  moduleName := s"spark-scalafix-rules-${sparkVersion.value}",
  crossScalaVersions := {
    if (sparkAtLeast(sparkVersion.value, "2.4")) {
      List(V.scala211, V.scala212, V.scala213)
    } else {
      List(V.scala211)
    }
  },
  libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % V.scalafixVersion
)

lazy val input = project.settings(
  publish / skip := true,
  sparkVersion := srcSparkVersion.value,
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % "1.14.0",
    "org.apache.spark" %% "spark-core" % sparkVersion.value,
    "org.apache.spark" %% "spark-sql" % sparkVersion.value,
    "org.apache.spark" %% "spark-hive" % sparkVersion.value,
    "org.scalatest" %% "scalatest" % "3.0.0"
  )
)

lazy val output = project.settings(
  publish / skip := true,
  sparkVersion := targetSparkVersion.value,
  scalaVersion := V.scala212,
  crossScalaVersions := {
    if (sparkAtLeast(sparkVersion.value, "3.2.0")) {
      List(V.scala212, V.scala213)
    } else if (sparkAtLeast(sparkVersion.value, "3.0.0")) {
      // Spark 3.0/3.1 are 2.12-only -- there is no spark-core_2.11 for them.
      List(V.scala212)
    } else {
      List(V.scala211, V.scala212)
    }
  },
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % "1.14.0",
    "org.apache.spark" %% "spark-core" % sparkVersion.value,
    "org.apache.spark" %% "spark-sql" % sparkVersion.value,
    "org.apache.spark" %% "spark-hive" % sparkVersion.value,
    "org.scalatest" %% "scalatest" % "3.2.14"
  )
)

lazy val tests = project
  .settings(
    publish / skip := true,
    libraryDependencies +=
      "ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test cross CrossVersion.full,
    Compile / compile := (Compile / compile)
      .dependsOn(input / Compile / compile, output / Compile / compile)
      .value,
    scalafixTestkitOutputSourceDirectories :=
      (output / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories :=
      (input / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath :=
      (input / Compile / fullClasspath).value
  )
  .dependsOn(rules)
  .enablePlugins(ScalafixTestkitPlugin)

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
