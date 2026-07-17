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

// Codec side-cars (D7): depend on `model` only — never on a backend. They exist so that
// `rift-scala-zio`/`rift-scala-cats` never force a JSON library on users; a user opts in by
// adding the side-car that matches the codecs they already have.
lazy val zioJson = riftModule("zioJson", "zio-json")
  .dependsOn(model)
  .settings(libraryDependencies ++= Dependencies.zioJsonDeps ++ Dependencies.munitDeps)

lazy val circe = riftModule("circe", "circe")
  .dependsOn(model)
  .settings(libraryDependencies ++= Dependencies.circeDeps ++ Dependencies.munitDeps)

lazy val bridge = riftModule("bridge", "bridge")
  .dependsOn(model)
  .settings(
    libraryDependencies ++=
      Dependencies.riftJavaCoreDeps ++
        Dependencies.riftJavaTestcontainersDeps ++
        Dependencies.munitDeps,
    // RiftVersions.riftScala (DESIGN.md §5.2) reads this instead of hand-maintaining a version
    // string that would drift from the actual build.
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "rift-scala-version.properties"
      IO.write(file, s"version=${version.value}\n")
      Seq(file)
    }.taskValue
  )

lazy val zio = riftModule("zio", "zio")
  .dependsOn(bridge)
  .settings(
    libraryDependencies ++= Dependencies.zioDeps ++ Dependencies.zioTestDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val zioTestkit = riftModule("zioTestkit", "zio-testkit")
  .dependsOn(zio)
  .settings(
    // zio-test is a COMPILE dep here (not Test-scoped, unlike in `zio`): the testkit exposes
    // zio-test aspects/assertions as part of its own public API, so downstream test suites need it
    // on their compile classpath transitively.
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % Dependencies.zio,
      "dev.zio" %% "zio-test-sbt" % Dependencies.zio % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val cats = riftModule("cats", "cats")
  .dependsOn(bridge)
  .settings(
    libraryDependencies ++=
      Dependencies.catsEffectDeps ++ Dependencies.munitDeps ++ Dependencies.munitCatsEffectDeps
  )

lazy val catsTestkit = riftModule("catsTestkit", "cats-testkit")
  .dependsOn(cats)
  .settings(
    libraryDependencies ++=
      Dependencies.catsTestkitDeps ++ Dependencies.munitDeps ++ Dependencies.munitCatsEffectDeps,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val fs2 = riftModule("fs2", "fs2")
  .dependsOn(cats)
  .settings(
    libraryDependencies ++=
      Dependencies.fs2Deps ++ Dependencies.munitDeps ++ Dependencies.munitCatsEffectDeps
  )

lazy val kyo = riftModule("kyo", "kyo")
  .dependsOn(bridge)

lazy val pure = riftModule("pure", "pure")
  .dependsOn(bridge)
  .settings(libraryDependencies ++= Dependencies.munitDeps)

// Test-only conformance corpus replay (#6, extended to the cats surface + parity table by #13):
// proves DSL <-> engine parity against the vendored sdk-conformance corpus (README.md under its
// resources for the replay contract). No `main` sources — every fixture-decode/DSL-reauthoring/
// engine-replay assertion lives under `src/test`, so this module contributes nothing to the
// published artifact set (`publish / skip`). Runs BOTH test frameworks: zio-test for the #6 specs
// (G1/G2/G3), munit for the #13 cats G3 replay and the parity-drift guard.
lazy val conformance = riftModule("conformance", "conformance")
  .dependsOn(zio, zioTestkit, cats, fs2, pure)
  .settings(
    publish / skip := true,
    libraryDependencies ++=
      Dependencies.zioTestDeps ++ Dependencies.munitDeps ++ Dependencies.munitCatsEffectDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    model,
    zioJson,
    circe,
    bridge,
    zio,
    zioTestkit,
    cats,
    catsTestkit,
    fs2,
    kyo,
    pure,
    conformance
  )
  .settings(
    name := "rift-scala",
    scalaVersion := scala3,
    publish / skip := true
  )
