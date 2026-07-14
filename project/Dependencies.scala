import sbt.*

/** Single source of truth for dependency pins (DESIGN.md §7).
  *
  * `riftJava` transitively pins the engine and the sdk-conformance corpus version — never pin the
  * engine separately (D9).
  */
object Dependencies {

  /** Pins the engine (0.13.5) and the conformance corpus transitively. */
  val riftJava = "0.1.1"

  /** Codec side-car (D7): `rift-scala-zio-json` is the *only* place zio-json is allowed, so that
    * `rift-scala-zio` stays zio + zio-streams and no backend forces a JSON library on users.
    */
  val zioJson = "0.9.2"

  val zioJsonDeps: Seq[ModuleID] = Seq("dev.zio" %% "zio-json" % zioJson)

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
