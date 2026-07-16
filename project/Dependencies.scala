import sbt.*

/** Single source of truth for dependency pins (DESIGN.md §7).
  *
  * `riftJava` transitively pins the engine and the sdk-conformance corpus version — never pin the
  * engine separately (D9).
  */
object Dependencies {

  /** Pins the engine (0.14.0) and the conformance corpus transitively. */
  val riftJava = "0.1.2"

  /** `bridge` compile scope (D2): the JDK-17+ facade
    * (`Rift`/`Imposter`/`RiftException`/`JsonValue`/ `RiftVersion`) is all the bridge links against
    * — no FFM, no HTTP, no native resolution in Scala. Embedded (`rift-java-embedded`) and the
    * natives classifier jars are loaded at *runtime* via ServiceLoader, so they are never compile
    * deps of the bridge; a consumer adds them per DESIGN §5.2.
    */
  val riftJavaCoreDeps: Seq[ModuleID] = Seq(
    "io.github.etacassiopeia" % "rift-java-core" % riftJava
  )

  /** `container(...)` transport wraps `RiftContainer`, which lives in the testcontainers artifact.
    * `% Optional` keeps it off every downstream classpath (it drags in org.testcontainers +
    * Docker): the bridge links it, but consumers who never call `container` don't inherit it, and a
    * call without it on the classpath surfaces as `RiftError.EngineUnavailable` naming the missing
    * artifact.
    */
  val riftJavaTestcontainersDeps: Seq[ModuleID] = Seq(
    "io.github.etacassiopeia" % "rift-java-testcontainers" % riftJava % Optional
  )

  /** Codec side-car (D7): `rift-scala-zio-json` is the *only* place zio-json is allowed, so that
    * `rift-scala-zio` stays zio + zio-streams and no backend forces a JSON library on users.
    */
  val zioJson = "0.9.2"

  val zioJsonDeps: Seq[ModuleID] = Seq("dev.zio" %% "zio-json" % zioJson)

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
