import sbt.*

/** Single source of truth for dependency pins (DESIGN.md §7).
  *
  * `riftJava` transitively pins the engine and the sdk-conformance corpus version — never pin the
  * engine separately (D9).
  */
object Dependencies {

  /** Pins the engine (0.15.0) and the conformance corpus transitively. */
  val riftJava = "0.1.3"

  /** `bridge` compile scope (D2): the JDK-17+ facade
    * (`Rift`/`Imposter`/`RiftException`/`JsonValue`/ `RiftVersion`) is all the bridge links against
    * — no FFM, no HTTP, no native resolution in Scala. Embedded (`rift-java-embedded`) and the
    * natives classifier jars are loaded at *runtime* via ServiceLoader, so they are never compile
    * deps of the bridge; a consumer adds them per DESIGN §5.2.
    */
  val riftJavaCoreDeps: Seq[ModuleID] = Seq(
    "io.github.achird-labs" % "rift-java-core" % riftJava
  )

  /** `container(...)` transport wraps `RiftContainer`, which lives in the testcontainers artifact.
    * `% Optional` keeps it off every downstream classpath (it drags in org.testcontainers +
    * Docker): the bridge links it, but consumers who never call `container` don't inherit it, and a
    * call without it on the classpath surfaces as `RiftError.EngineUnavailable` naming the missing
    * artifact.
    */
  val riftJavaTestcontainersDeps: Seq[ModuleID] = Seq(
    "io.github.achird-labs" % "rift-java-testcontainers" % riftJava % Optional
  )

  /** Embedded transport (in-process engine via stable FFM) + its native library, both `Test`-only,
    * for the conformance module's G3 replay (DESIGN §5.2). `rift-java-embedded` is JDK-22 bytecode,
    * so `build.sbt` adds these ONLY on a JDK that can classload them (the JDK 22 CI job); the JDK
    * 21 job replays G3 via `spawn` and must not carry them. `RiftNatives.currentClassifier` picks
    * the host's native jar (the natives artifact is classifier-per-platform, no default jar).
    */
  val riftJavaEmbeddedTestDeps: Seq[ModuleID] = Seq(
    "io.github.achird-labs" % "rift-java-embedded" % riftJava % Test,
    ("io.github.achird-labs" % "rift-java-natives" % riftJava % Test)
      .classifier(RiftNatives.currentClassifier)
  )

  /** `rift-scala-zio-bdd` (#18): the published `MockControl` SPI this module's adapter implements.
    * Standalone artifact (no zio-bdd core dependency) built with Scala 3.3.4 — matching the repo
    * pin — and zio 2.1.17, which sbt eviction bumps this module's zio to (a binary-compatible patch
    * bump within ZIO 2.1.x; the rest of the repo stays on the `zio` pin below).
    */
  val zioBdd = "1.4.4"

  val zioBddDeps: Seq[ModuleID] = Seq(
    "io.github.etacassiopeia" %% "zio-bdd-mock" % zioBdd
  )

  /** The published conformance scenario sets + `ConformanceHarness` (zio-bdd #332), Test-only — the
    * SPI's own compliance suite, run against `RiftScalaBackend` in `RiftScalaConformanceSpec`.
    */
  val zioBddConformanceDeps: Seq[ModuleID] = Seq(
    "io.github.etacassiopeia" %% "zio-bdd-mock-conformance" % zioBdd % Test
  )

  /** `rift-scala-zio` (#4): the ZIO surface is zio + zio-streams only (the cursor request tail is a
    * `ZStream`). No JSON library — codecs opt in via the `zio-json` side-car (D7).
    */
  val zio = "2.1.14"

  val zioDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio" % zio,
    "dev.zio" %% "zio-streams" % zio
  )

  val zioTestDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio-test" % zio % Test,
    "dev.zio" %% "zio-test-sbt" % zio % Test
  )

  /** Codec side-car (D7): `rift-scala-zio-json` is the *only* place zio-json is allowed, so that
    * `rift-scala-zio` stays zio + zio-streams and no backend forces a JSON library on users.
    */
  val zioJson = "0.9.2"

  val zioJsonDeps: Seq[ModuleID] = Seq("dev.zio" %% "zio-json" % zioJson)

  /** `rift-scala-cats` (#8): tagless over `Async[F]` (the CE ecosystem convention) — cats-effect
    * only, no cats-core data types in the public surface. This is the only module (besides
    * `cats-testkit`/`fs2`, which depend on it) allowed to pull in cats-effect.
    */
  val catsEffect = "3.5.7"

  val catsEffectDeps: Seq[ModuleID] = Seq("org.typelevel" %% "cats-effect" % catsEffect)

  /** Test-only. Runs `IO`-returning munit tests directly (`Rift.embedded[IO].use { ... }`) instead
    * of hand-rolled `.unsafeRunSync()` calls in every test body.
    */
  val munitCatsEffect = "2.0.0"

  val munitCatsEffectDeps: Seq[ModuleID] =
    Seq("org.typelevel" %% "munit-cats-effect" % munitCatsEffect % Test)

  /** `rift-scala-cats-testkit` (#10): glue for **munit-cats-effect** and **weaver-cats**, both `%
    * Optional` (a user pulls the one they use; the module itself compiles against both).
    * `munit-cats-effect` is already pinned above (`munitCatsEffect`, 2.0.0) — it is also listed
    * here at `Optional` because the testkit's own public API (`RiftSuite`) exposes it, on top of
    * using it at `Test` scope for the module's own gate tests; sbt tolerates the same artifact at
    * both scopes on one module.
    *
    * `weaver-cats` 0.8.4 is the latest 0.8.x on Maven Central as of this pin (Jan 2024) and
    * resolves cleanly against `cats-effect` 3.5.7: it declares `cats-effect` 3.5.3 transitively,
    * which sbt's default eviction bumps to the build's 3.5.7 (a binary-compatible patch bump within
    * CE3's own compatibility guarantee), verified via `coursier fetch` and a local
    * `catsTestkit/test` run against the bumped version.
    */
  val weaver = "0.8.4"

  val catsTestkitDeps: Seq[ModuleID] = Seq(
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffect % Optional,
    "com.disneystreaming" %% "weaver-cats" % weaver % Optional
  )

  /** `rift-scala-fs2` (#9): the cursor request tail as an `fs2.Stream`, built on the cats module's
    * `ImposterHandle[F]` (cats-effect comes transitively via `.dependsOn(cats)`). fs2-core only —
    * no fs2-io, this module never touches a socket or a file.
    */
  val fs2 = "3.11.0"

  val fs2Deps: Seq[ModuleID] = Seq("co.fs2" %% "fs2-core" % fs2)

  /** Codec side-car (D7): the only place circe is allowed, keeping `rift-scala-cats`
    * cats-effect-only and honouring "cats-core never leaks into other modules" in both directions.
    */
  val circe = "0.14.16"

  /** Compile scope is `circe-core` alone — the side-car bridges `Encoder`/`Decoder` and the two
    * JSON ASTs, and parses nothing, so it does not impose circe-parser downstream. Derivation needs
    * nothing extra either: `derives Codec.AsObject` lives in circe-core (`io.circe.derivation`),
    * and the tests use it rather than circe-generic precisely so the build proves that claim.
    */
  val circeDeps: Seq[ModuleID] = Seq(
    "io.circe" %% "circe-core" % circe,
    "io.circe" %% "circe-parser" % circe % Test
  )

  /** Test-only. `model` is zero-dependency at compile scope, and munit keeps it effect-agnostic:
    * the module is the shared base for the ZIO, Cats, Kyo and pure surfaces, so its own tests must
    * not import an effect system (same reasoning as D1's rejection of zio-json).
    */
  val munit = "1.0.2"
  val munitScalacheck = "1.0.0"

  val munitDeps: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit" % munit % Test,
    "org.scalameta" %% "munit-scalacheck" % munitScalacheck % Test
  )
}
