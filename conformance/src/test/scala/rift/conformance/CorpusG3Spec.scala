package rift.conformance

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import zio.*
import zio.test.*

import rift.RiftError
import rift.json.Json
import rift.model.Port
import rift.zio.Rift

import io.github.etacassiopeia.rift.Rift as JRift

/** G3 — replay (engine-required, GUARDED).
  *
  * Guarded exactly like `bridge.EmbeddedSmokeSpec` / `zio-testkit`'s `LedgerPatternSampleSpec`:
  * `JRift.isEmbeddedAvailable()` is checked BEFORE any engine layer is built, so a bare CI JVM (no
  * `rift-java-natives` / `--enable-native-access`) skips (never fails) and this spec's only job on
  * such a JVM is to COMPILE. When an embedded engine IS present, it creates every `hasVerify`
  * fixture the embedded lane can serve (skipping only fixtures whose `requires` names a capability
  * outside `supportedCapabilities` — the README's "capability skips only per the manifest" rule)
  * and replays each stub's `_verify.sequence` over real HTTP.
  */
object CorpusG3Spec extends ZIOSpecDefault:

  /** Capabilities the embedded transport can serve today. Empty for now: `injection`/`proxy`/
    * `redis`/`https`/`shell` aren't wired through the embedded connector's config surface yet in
    * this SDK (tracked separately — out of scope for #6, which only needs this guarded spec to
    * compile and skip cleanly). Extend this set as embedded gains each capability; every fixture
    * whose `requires` is a subset of this set gets replayed automatically, no other change needed.
    */
  private val supportedCapabilities: Set[String] = Set.empty

  private val client = HttpClient.newHttpClient()

  /** One `_verify.sequence[]` entry — a request to fire and the response to assert against it. Kept
    * local to G3 rather than added to `rift.model`: `_verify` is corpus/test-harness replay
    * metadata, not a modeled wire type the DSL ever authors (see `CorpusG2Spec`'s
    * `dslExpressibleModuloVerify` note).
    */
  private final case class VerifyStep(
      method: String,
      path: String,
      headers: Vector[(String, String)],
      requestBody: Option[String],
      expectStatus: Int,
      expectBodyContains: Option[String],
      expectBodyEquals: Option[String]
  )

  private def verifySteps(imposterJson: Json): Vector[VerifyStep] =
    val stubs = imposterJson.get("stubs").flatMap(_.asArray).getOrElse(Vector.empty)
    stubs.flatMap { stub =>
      val sequence = stub.get("_verify", "sequence").flatMap(_.asArray).getOrElse(Vector.empty)
      sequence.map(decodeStep)
    }

  private def decodeStep(step: Json): VerifyStep =
    val request = step.get("request").getOrElse(Json.Obj(Vector.empty))
    val expect = step.get("expect").getOrElse(Json.Obj(Vector.empty))
    VerifyStep(
      method = request.get("method").flatMap(_.asString).getOrElse("GET"),
      path = request.get("path").flatMap(_.asString).getOrElse("/"),
      headers = request
        .get("headers")
        .flatMap(_.asObject)
        .getOrElse(Vector.empty)
        .collect { case (k, Json.Str(v)) => k -> v },
      requestBody = request.get("body").flatMap(_.asString),
      expectStatus = expect
        .get("status")
        .collect { case Json.Num(n) => n.toInt }
        .getOrElse(200),
      expectBodyContains = expect.get("bodyContains").flatMap(_.asString),
      expectBodyEquals = expect.get("bodyEquals").flatMap(_.asString)
    )

  private def bodyPublisher(body: Option[String]): HttpRequest.BodyPublisher =
    body match
      case Some(b) => HttpRequest.BodyPublishers.ofString(b, StandardCharsets.UTF_8)
      case None => HttpRequest.BodyPublishers.noBody()

  private def fire(port: Int, step: VerifyStep): Task[HttpResponse[String]] =
    ZIO.attemptBlocking {
      val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port${step.path}"))
      step.headers.foreach((k, v) => builder.header(k, v))
      builder.method(step.method.toUpperCase, bodyPublisher(step.requestBody))
      client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

  private def assertStep(step: VerifyStep, response: HttpResponse[String]): TestResult =
    assertTrue(response.statusCode() == step.expectStatus) &&
      assertTrue(step.expectBodyContains.forall(response.body().contains)) &&
      assertTrue(step.expectBodyEquals.forall(_ == response.body()))

  private def replayFixture(rift: Rift, fixture: Fixture): IO[RiftError, TestResult] =
    // Bracket the imposter so a `fire` defect (`.orDie`) can't leak it: the release deletes it even
    // when the replay unwinds on a defect, not only on the happy path.
    ZIO.acquireReleaseWith(rift.createFromJson(Corpus.imposterRaw(fixture)))(_.delete.orDie) {
      imp =>
        val steps = verifySteps(Corpus.imposterJson(fixture))
        val port = Port.value(imp.port)
        ZIO
          .foreach(steps)(step => fire(port, step).orDie.map(assertStep(step, _)))
          .map(_.reduceOption(_ && _).getOrElse(assertCompletes))
    }

  private def replayOrSkip(rift: Rift, fixture: Fixture): IO[RiftError, TestResult] =
    val ungated = fixture.requires -- supportedCapabilities
    if ungated.nonEmpty then
      ZIO
        .logInfo(
          s"G3: skipping ${fixture.name} — requires ${ungated.mkString(",")}, beyond the embedded " +
            "lane's supported capability set"
        )
        .as(assertCompletes)
    else replayFixture(rift, fixture)

  def spec = suite("G3 — corpus replay over the embedded engine (issue #6, guarded)")(
    test("replay every hasVerify fixture the embedded lane supports") {
      // Checked BEFORE any layer is forced — see `LedgerPatternSampleSpec`'s "WHY THIS TEST IS
      // GUARDED" for the full rationale this spec borrows verbatim.
      if !JRift.isEmbeddedAvailable() then
        ZIO.logWarning("G3 skipped: no embedded engine on this JVM") *> ZIO.succeed(assertCompletes)
      else
        ZIO.scoped {
          for
            env <- Rift.embedded.build
            rift = env.get[Rift]
            outcomes <- ZIO.foreach(Corpus.fixtures.filter(_.hasVerify))(replayOrSkip(rift, _))
          yield outcomes.reduceOption(_ && _).getOrElse(assertCompletes)
        }
    }
  )
