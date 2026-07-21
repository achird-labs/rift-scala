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
    organization := "io.github.achird-labs",
    organizationName := "Achird Labs",
    homepage := Some(url("https://github.com/achird-labs/rift-scala")),
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
        url("https://github.com/achird-labs/rift-scala"),
        "scm:git@github.com:achird-labs/rift-scala.git"
      )
    ),
    // sbt-ci-release 1.11+ publishes to the Sonatype Central Portal by default
    // (io.github.achird-labs) from CI on a v* tag — the legacy OSSRH host was sunset.
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

// The embedded transport (in-process engine via FFM) needs JDK 22+, so its runtime jars are added to
// `conformance` only when the build JDK can classload them (DESIGN §5.2 / D8). Read the running JDK's
// spec version once here: modern JDKs report a bare major ("21", "22"); the legacy "1.8" form maps to
// 8. On JDK 21 the embedded jars are absent, `JRift.isEmbeddedAvailable()` is false, embedded G3 skips,
// and the spawn lane (CorpusG3SpawnSpec) covers replay instead.
lazy val buildJavaSpec: Int = {
  val parts = sys.props.getOrElse("java.specification.version", "0").split("\\.")
  if (parts.headOption.contains("1")) parts.lift(1).map(_.toInt).getOrElse(0)
  else parts.headOption.map(_.toInt).getOrElse(0)
}

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

// MockControl adapter over the published zio-bdd SPI (#18, DESIGN §5.12). The guarded live spec
// needs a real engine, so it borrows the conformance module's JDK-gated embedded recipe — on the
// JDK 22 CI job it runs against the in-process engine; elsewhere it compiles and skips.
lazy val zioBdd = riftModule("zioBdd", "zio-bdd")
  .dependsOn(zio)
  .settings(
    libraryDependencies ++=
      Dependencies.zioBddDeps ++ Dependencies.zioBddConformanceDeps ++ Dependencies.zioTestDeps,
    libraryDependencies ++=
      (if (buildJavaSpec >= 22) Dependencies.riftJavaEmbeddedTestDeps else Seq.empty),
    Test / fork := true,
    Test / javaOptions ++=
      (if (buildJavaSpec >= 22) Seq("--enable-native-access=ALL-UNNAMED") else Seq.empty),
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
    // G3 replay runs against a live engine: embedded (in-process FFM) on JDK 22+, spawn (out-of-process
    // child) on JDK 21. The embedded jars + `--enable-native-access` are wired only on a JDK that can
    // load them; the forked test JVM inherits the runner's env, so `RIFT_G3_REQUIRE` reaches the
    // spawn spec. Tests fork regardless so the native-access flag and the spawned child both get a
    // clean JVM.
    libraryDependencies ++=
      (if (buildJavaSpec >= 22) Dependencies.riftJavaEmbeddedTestDeps else Seq.empty),
    Test / fork := true,
    // ParitySpec resolves `docs/PARITY.md` against `user.dir`, relying on sbt's unforked default of
    // the repo root (its own comment predicts breakage under fork). Forking — required so the
    // embedded lane gets `--enable-native-access` and the spawn lane a clean child JVM — moves the
    // working directory to the module dir, so pin it back to the build root.
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    Test / javaOptions ++=
      (if (buildJavaSpec >= 22) Seq("--enable-native-access=ALL-UNNAMED") else Seq.empty),
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
    zioBdd,
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
