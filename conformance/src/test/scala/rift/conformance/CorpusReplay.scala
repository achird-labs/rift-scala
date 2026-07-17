package rift.conformance

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import rift.json.Json

/** Shared G3 (corpus replay) machinery, factored out of the zio `CorpusG3Spec` (#6) so the cats
  * `CorpusG3CatsSpec` (#13) replays the exact same `_verify.sequence` semantics instead of a
  * hand-rolled second copy that could drift. Deliberately effect-agnostic: `fireBlocking` is a
  * plain synchronous call, and each backend's spec wraps it in its own blocking constructor
  * (`ZIO.attemptBlocking`, `IO.blocking`) — that's the only piece a shared object can't do for both
  * without importing one effect system into the other's test sources.
  */
object CorpusReplay:

  /** Capabilities the embedded transport can serve today. Empty for now: `injection`/`proxy`/
    * `redis`/`https`/`shell` aren't wired through the embedded connector's config surface yet in
    * this SDK (tracked separately). Backend-agnostic — the embedded engine's capability set doesn't
    * depend on which Scala surface is driving it. Extend this set as embedded gains each
    * capability; every fixture whose `requires` is a subset of this set gets replayed
    * automatically, no other change needed.
    */
  val supportedCapabilities: Set[String] = Set.empty

  private val client = HttpClient.newHttpClient()

  /** One `_verify.sequence[]` entry — a request to fire and the response to assert against it. Kept
    * here rather than in `rift.model`: `_verify` is corpus/test-harness replay metadata, not a
    * modeled wire type the DSL ever authors (see `CorpusG2Spec`'s `dslExpressibleModuloVerify`
    * note).
    */
  final case class VerifyStep(
      method: String,
      path: String,
      headers: Vector[(String, String)],
      requestBody: Option[String],
      expectStatus: Int,
      expectBodyContains: Option[String],
      expectBodyEquals: Option[String]
  )

  def verifySteps(imposterJson: Json): Vector[VerifyStep] =
    val stubs = imposterJson.get("stubs").flatMap(_.asArray).getOrElse(Vector.empty)
    stubs.flatMap { stub =>
      val sequence = stub.get("_verify", "sequence").flatMap(_.asArray).getOrElse(Vector.empty)
      sequence.map(decodeStep)
    }

  def decodeStep(step: Json): VerifyStep =
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

  /** Fires one `VerifyStep` against `127.0.0.1:port` and blocks for the response. Synchronous by
    * design — callers lift it into their own effect system's blocking constructor.
    */
  def fireBlocking(port: Int, step: VerifyStep): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port${step.path}"))
    step.headers.foreach((k, v) => builder.header(k, v))
    builder.method(step.method.toUpperCase, bodyPublisher(step.requestBody))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
