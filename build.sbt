// rift-scala — official Scala 3 SDK for Rift.
//
// Multi-module layout (openfeature4s-proven shape): a pure `model`, a `bridge` over
// rift-java, and one thin module per effect system. Each module's *external* library
// dependencies (zio, cats-effect, fs2, kyo, rift-java, …) are added by that module's own
// feature issue — the bootstrap wires only the module graph and the shared toolchain, so
// `sbt compile` stays green before any effect backend is implemented.

lazy val scala3 = "3.3.4"

inThisBuild(
  Seq(
    organization := "io.github.etacassiopeia",
    organizationName := "EtaCassiopeia",
    homepage := Some(url("https://github.com/EtaCassiopeia/rift-scala")),
    licenses := Seq(
      "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := List(
      Developer(
        id = "EtaCassiopeia",
        name = "Mohsen Zainalpour",
        email = "zainalpour@gmail.com",
        url = url("https://github.com/EtaCassiopeia")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/EtaCassiopeia/rift-scala"),
        "scm:git@github.com:EtaCassiopeia/rift-scala.git"
      )
    ),
    // sbt-ci-release 1.11+ publishes to the Sonatype Central Portal by default
    // (io.github.etacassiopeia) from CI on a v* tag — the legacy OSSRH host was sunset.
    versionScheme := Some("early-semver")
  )
)

lazy val commonSettings = Seq(
  scalaVersion := scala3,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings"
  )
)

def riftModule(id: String, dir: String): Project =
  Project(id, file(dir))
    .settings(commonSettings)
    .settings(
      name := s"rift-scala-$dir",
      moduleName := s"rift-scala-$dir"
    )

// Enforces DESIGN.md §5.1's "zero dependencies" promise for `model`: the pure wire model is the
// shared base for the ZIO, Cats, Kyo and pure surfaces, so a compile-scope dep here would leak into
// all of them (the same reasoning that rejected zio-json in D1).
lazy val zeroDepCheck = taskKey[Unit]("Fail if the module declares any non-Test dependency.")

lazy val model = riftModule("model", "model")
  .settings(
    libraryDependencies ++= Dependencies.munitDeps,
    zeroDepCheck := {
      // sbt injects the Scala library itself via autoScalaLibrary; "zero dependencies" means zero
      // *third-party* ones.
      val offenders = libraryDependencies.value
        .filterNot(_.configurations.exists(_.contains("test")))
        .filterNot(_.organization == "org.scala-lang")
      if (offenders.nonEmpty)
        sys.error(
          s"rift-scala-model must be zero-dependency (DESIGN.md §5.1); found compile-scope: " +
            offenders.map(m => s"${m.organization}:${m.name}").mkString(", ")
        )
    },
    Test / test := (Test / test).dependsOn(zeroDepCheck).value
  )

lazy val bridge = riftModule("bridge", "bridge")
  .dependsOn(model)

lazy val zio = riftModule("zio", "zio")
  .dependsOn(bridge)

lazy val zioTestkit = riftModule("zioTestkit", "zio-testkit")
  .dependsOn(zio)

lazy val cats = riftModule("cats", "cats")
  .dependsOn(bridge)

lazy val catsTestkit = riftModule("catsTestkit", "cats-testkit")
  .dependsOn(cats)

lazy val fs2 = riftModule("fs2", "fs2")
  .dependsOn(cats)

lazy val kyo = riftModule("kyo", "kyo")
  .dependsOn(bridge)

lazy val pure = riftModule("pure", "pure")
  .dependsOn(bridge)

lazy val root = project
  .in(file("."))
  .aggregate(model, bridge, zio, zioTestkit, cats, catsTestkit, fs2, kyo, pure)
  .settings(
    name := "rift-scala",
    scalaVersion := scala3,
    publish / skip := true
  )
