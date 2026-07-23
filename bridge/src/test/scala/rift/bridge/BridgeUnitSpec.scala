package rift.bridge

import munit.FunSuite

import java.net.URI
import scala.concurrent.duration.*

import rift.RiftError
import rift.model.Port
import rift.json.Json

import io.github.achirdlabs.rift.error as jerr
import io.github.achirdlabs.rift.verify as jverify
import io.github.achirdlabs.rift.json.JsonValue

/** AC1 — total mapping from the rift-java boundary. Every sealed `RiftException` subtype and
  * `VerificationException` maps to a `RiftError`; anything unrecognised stays a defect (`None`).
  */
class RiftErrorMappingSpec extends FunSuite:

  test("InvalidDefinition maps and preserves the message"):
    RiftError.fromThrowable(jerr.InvalidDefinition("bad def")) match
      case Some(RiftError.InvalidDefinition(msg, _)) => assertEquals(msg, "bad def")
      case other => fail(s"expected InvalidDefinition, got $other")

  test("EngineUnavailable maps"):
    RiftError.fromThrowable(jerr.EngineUnavailable("no engine")) match
      case Some(RiftError.EngineUnavailable(msg, _)) => assertEquals(msg, "no engine")
      case other => fail(s"got $other")

  test("CommunicationError maps"):
    RiftError.fromThrowable(jerr.CommunicationError("boom")) match
      case Some(RiftError.CommunicationError(msg, _)) => assertEquals(msg, "boom")
      case other => fail(s"got $other")

  test("ImposterNotFound carries the typed Port"):
    RiftError.fromThrowable(jerr.ImposterNotFound(8080, "not found")) match
      case Some(RiftError.ImposterNotFound(port)) => assertEquals(Port.value(port), 8080)
      case other => fail(s"got $other")

  test("EngineError carries the code"):
    RiftError.fromThrowable(jerr.EngineError(503, "unavailable")) match
      case Some(RiftError.EngineError(code, msg)) =>
        assertEquals(code, 503)
        assertEquals(msg, "unavailable")
      case other => fail(s"got $other")

  test("cause is preserved when the Java exception has one"):
    val root = new RuntimeException("root")
    RiftError.fromThrowable(jerr.InvalidDefinition("x", root)) match
      case Some(RiftError.InvalidDefinition(_, Some(c))) => assertEquals(c.getMessage, "root")
      case other => fail(s"got $other")

  test("VerificationException maps to VerificationFailed with a report"):
    val vt = jverify.VerificationTimes.atLeast(1)
    val rm = jverify.RequestMatch.of(java.util.List.of())
    val res =
      new jverify.VerificationResult(0, 0, false, java.util.List.of(), java.util.Optional.empty())
    val ve = new jverify.VerificationException(0, java.util.Optional.of("mismatch"), rm, vt, res)
    RiftError.fromThrowable(ve) match
      case Some(RiftError.VerificationFailed(_)) => ()
      case other => fail(s"got $other")

  test("an unrelated Throwable is NOT swallowed — stays a defect (None)"):
    assertEquals(RiftError.fromThrowable(new IllegalStateException("unrelated")), None)

  test("an engine-reported out-of-range port downgrades to CommunicationError, never dropped"):
    RiftError.fromThrowable(jerr.ImposterNotFound(70000, "impossible port")) match
      case Some(RiftError.CommunicationError(_, _)) => ()
      case other => fail(s"expected CommunicationError (not a dropped/None), got $other")

  test("RiftError is a Throwable so backends can raise/refine it"):
    assert(RiftError.EngineError(500, "x").isInstanceOf[Throwable])

  test("the cause chains into getCause so logging/printStackTrace surfaces the root"):
    val root = new RuntimeException("root")
    val err = RiftError.fromThrowable(jerr.CommunicationError("boom", root)).get
    assertEquals(err.getCause.getMessage, "root")

/** AC2 — config case classes mirror rift-java's `*Options` builders with identical defaults, and
  * `toOptions` produces Java options whose read-back values match (guards against silent drift).
  */
class ConfigDefaultsSpec extends FunSuite:

  val uri = URI.create("http://127.0.0.1:2525")

  test("ConnectConfig defaults mirror ConnectOptions"):
    val c = ConnectConfig(adminUri = uri)
    assertEquals(c.requestTimeout, 30.seconds)
    assertEquals(c.versionCheck, VersionCheck.Fail)
    assertEquals(c.apiKey, None)
    val o = c.toOptions
    assertEquals(o.requestTimeout(), java.time.Duration.ofSeconds(30))
    assertEquals(o.adminUri(), uri)

  test("EmbeddedConfig defaults mirror EmbeddedOptions"):
    val c = EmbeddedConfig()
    assertEquals(c.adminHost, "127.0.0.1")
    assertEquals(c.adminPort, 0)
    assertEquals(c.serveAdminEagerly, false)
    val o = c.toOptions
    assertEquals(o.adminHost(), "127.0.0.1")
    assertEquals(o.adminPort(), 0)
    assertEquals(o.serveAdminEagerly(), false)

  test("SpawnConfig defaults mirror SpawnOptions (allowInjection/localOnly = true)"):
    val c = SpawnConfig()
    assertEquals(c.host, "127.0.0.1")
    assertEquals(c.allowInjection, true)
    assertEquals(c.localOnly, true)
    assertEquals(c.logLevel, "info")
    assertEquals(c.startupTimeout, 15.seconds)
    assertEquals(c.shutdownTimeout, 5.seconds)
    val o = c.toOptions
    assertEquals(o.startupTimeout(), java.time.Duration.ofSeconds(15))
    assertEquals(o.allowInjection(), true)

/** AC2 (extended) — `EventStreamConfig.toOptions` mirrors `EventStreamOptions.builder()...` with
  * identical defaults, and every field round-trips onto the built facade options (issue #87).
  */
class EventStreamConfigSpec extends FunSuite:
  import io.github.achirdlabs.rift.EventStreamOptions as JEventStreamOptions
  import io.github.achirdlabs.rift.EventStreamOptions.EventType as JEventType

  test("defaults leave the facade builder's own defaults untouched") {
    val facadeDefault = JEventStreamOptions.builder().build()
    val o = EventStreamConfig().toOptions
    assertEquals(o.types(), facadeDefault.types())
    assertEquals(o.port(), facadeDefault.port())
    assertEquals(o.`match`(), facadeDefault.`match`())
    // the assertion of interest: pinned against the facade's own (private) default rather than a
    // hardcoded literal, so a future change to that default can't silently drift from this config.
    assertEquals(o.idleTimeout(), facadeDefault.idleTimeout())
  }

  test("toOptions round-trips types/port/filters/idleTimeout onto the built options") {
    val config = EventStreamConfig(
      types = Set(EventType.Requests, EventType.Lifecycle),
      port = Port.from(8080).toOption,
      filters = Vector(TailFilter.Method("GET"), TailFilter.Header("X-Env", "prod")),
      idleTimeout = Some(45.seconds)
    )
    val o = config.toOptions
    assertEquals(o.types(), java.util.Set.of(JEventType.REQUESTS, JEventType.LIFECYCLE))
    assertEquals(o.port(), java.util.OptionalInt.of(8080))
    assertEquals(o.`match`().size(), 2)
    assertEquals(o.idleTimeout(), java.time.Duration.ofSeconds(45))
  }

  test("empty types/filters do not call the facade's varargs setters at all") {
    // `types()`/`match()` called with zero varargs would still overwrite the builder's own default
    // (an empty set/list) rather than leaving it alone — `toOptions` must skip the call entirely.
    val defaultTypes = JEventStreamOptions.builder().build().types()
    val defaultMatch = JEventStreamOptions.builder().build().`match`()
    val o = EventStreamConfig().toOptions
    assertEquals(o.types(), defaultTypes)
    assertEquals(o.`match`(), defaultMatch)
  }

/** AC3 — RiftVersions surfaces all three pins, read from rift-java's rift-version.properties (D9).
  */
class RiftVersionsSpec extends FunSuite:
  test("riftJava and engine come from the pinned rift-java jar"):
    assertEquals(RiftVersions.riftJava, "0.2.2")
    assertEquals(RiftVersions.engine, "0.16.0")
  test("riftScala is non-empty"):
    assert(RiftVersions.riftScala.nonEmpty)

/** AC4 — currentClassifier maps os.name/os.arch to one of the six published natives classifiers,
  * and refuses unsupported platforms (fail closed, not a wrong guess).
  */
class RiftNativesSpec extends FunSuite:
  test("maps the six supported platforms"):
    assertEquals(RiftNatives.classifierFor("Mac OS X", "aarch64"), Some("darwin-aarch64"))
    assertEquals(RiftNatives.classifierFor("Mac OS X", "x86_64"), Some("darwin-x86_64"))
    assertEquals(RiftNatives.classifierFor("Linux", "amd64"), Some("linux-x86_64"))
    assertEquals(RiftNatives.classifierFor("Linux", "aarch64"), Some("linux-aarch64"))
    assertEquals(RiftNatives.classifierFor("Windows 11", "amd64"), Some("windows-x86_64"))
  test("arch aliases normalise (arm64→aarch64, x64→x86_64)"):
    assertEquals(RiftNatives.classifierFor("Mac OS X", "arm64"), Some("darwin-aarch64"))
    assertEquals(RiftNatives.classifierFor("Linux", "x86_64"), Some("linux-x86_64"))
  test("an unsupported platform yields None, never a wrong classifier"):
    assertEquals(RiftNatives.classifierFor("SunOS", "sparc"), None)

/** AC6 — the D2 raw-JSON seam is lossless: a model definition survives model→JSON→facade
  * JsonValue→JSON→model unchanged, including `extra`/`_rift` fields a Java-model translation loses.
  */
class JsonSeamSpec extends FunSuite:
  import rift.dsl.*

  test("ImposterDefinition round-trips through the facade JsonValue seam"):
    val original = imposter("svc").port(8080).record.build
    val viaFacade = JsonValue.parse(original.toJson.render).toJson()
    Json.parse(viaFacade).flatMap(ImposterDefinition.fromJson) match
      case Right(back) => assertEquals(back, original)
      case Left(err) => fail(s"seam lost data: $err")

  test(
    "extra/_rift fields survive the JSON seam (the StubSpec-translation loss the bridge avoids)"
  ):
    val original = ImposterDefinition(
      port = Port.from(9090).toOption,
      extra = Vector("_note" -> Json.Str("keep me"))
    )
    val viaFacade = JsonValue.parse(original.toJson.render).toJson()
    Json.parse(viaFacade).flatMap(ImposterDefinition.fromJson) match
      case Right(back) => assertEquals(back.extra, original.extra)
      case Left(err) => fail(s"seam lost extra: $err")

  test("Stub round-trips through the facade JsonValue seam (the addStub/replaceStubs path)"):
    val original = get("/ping").reply(ok).build
    val viaFacade = JsonValue.parse(original.toJson.render).toJson()
    Json.parse(viaFacade).flatMap(rift.model.Stub.fromJson) match
      case Right(back) => assertEquals(back, original)
      case Left(err) => fail(s"seam lost stub data: $err")

/** AC2 (extended) — the FacadeEncode adapters mirror the model→facade value mappings the connector
  * relies on: `Times` → `VerificationTimes` and `VersionCheck` → the Java enum. Wrong wiring here
  * silently verifies against the wrong count / connects with the wrong version policy.
  */
class FacadeMappingSpec extends FunSuite:
  import rift.model.Times
  import io.github.achirdlabs.rift.VersionCheck as JVersionCheck

  test("Times maps to the matching VerificationTimes predicate"):
    assert(FacadeEncode.times(Times.Exactly(3)).matches(3))
    assert(!FacadeEncode.times(Times.Exactly(3)).matches(2))
    assert(FacadeEncode.times(Times.AtLeast(2)).matches(5))
    assert(!FacadeEncode.times(Times.AtLeast(2)).matches(1))
    assert(FacadeEncode.times(Times.AtMost(2)).matches(0))
    assert(!FacadeEncode.times(Times.AtMost(2)).matches(3))
    assert(FacadeEncode.times(Times.Between(1, 3)).matches(2))
    assert(!FacadeEncode.times(Times.Between(1, 3)).matches(4))

  test("VersionCheck maps to the rift-java enum"):
    assertEquals(VersionCheck.Fail.toJava, JVersionCheck.FAIL)
    assertEquals(VersionCheck.Warn.toJava, JVersionCheck.WARN)
    assertEquals(VersionCheck.Off.toJava, JVersionCheck.OFF)

/** verifyResult (issue #88) — `FacadeDecode.verificationResult` decodes the facade's non-throwing
  * verification record without dropping anything, unlike `RiftError.translateReport`'s best-effort,
  * drop-on-decode-failure `VerificationReport`. Mirrors the "VerificationException maps to
  * VerificationFailed" fixture shape above.
  */
class VerifyResultDecodeSpec extends FunSuite:
  import rift.model.{Predicate, VerifyDetail}
  import io.github.achirdlabs.rift.model.Predicate as JPredicate
  import io.github.achirdlabs.rift.RecordedRequest as JRecordedRequest

  /** Builds a facade `RecordedRequest` whose `.raw()` is the given document — `FacadeDecode`
    * decodes exclusively off `.raw()`, so the canonical constructor's other (unused) fields are
    * filled with harmless placeholders rather than mirrored from `rawJson`.
    */
  private def jreqRaw(rawJson: String): JRecordedRequest =
    new JRecordedRequest(
      "GET",
      "/placeholder",
      java.util.Map.of(),
      java.util.Map.of(),
      "",
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      java.util.Map.of(),
      JsonValue.parse(rawJson)
    )

  private def jreq(path: String): JRecordedRequest =
    jreqRaw(s"""{"method":"GET","path":"$path","timestamp":"1970-01-01T00:00:00Z"}""")

  private def expectPredicate(raw: String): Predicate =
    Json.parse(raw).flatMap(Predicate.fromJson) match
      case Right(p) => p
      case Left(err) => fail(s"test setup: bad predicate fixture: $err")

  test("verificationResult decodes matched/total/satisfied/requests, and closest round-trips"):
    val jr = new jverify.VerificationResult(
      2,
      3,
      false,
      java.util.List.of(jreq("/x")),
      java.util.Optional.of(
        new jverify.ClosestMiss(
          jreq("/x"),
          java.util.List.of(
            new jverify.FailedPredicate(
              JPredicate.fromJson("""{"equals":{"path":"/x"}}"""),
              JsonValue.parse("\"/y\"")
            )
          )
        )
      )
    )
    val result = FacadeDecode.verificationResult(jr)
    assertEquals(result.matched, 2)
    assertEquals(result.total, 3)
    assertEquals(result.satisfied, false)
    assertEquals(result.requests.map(_.path), Vector("/x"))
    result.closest match
      case Some(closest) =>
        assertEquals(closest.request.path, "/x")
        assertEquals(
          closest.failedPredicates.map(_.predicate),
          Vector(
            expectPredicate(
              """{"equals":{"path":"/x"}}"""
            )
          )
        )
        assertEquals(closest.failedPredicates.map(_.actual), Vector(Json.Str("/y")))
      case None => fail("expected a closest miss, got None")

  test("empty shape: requests decodes to Vector.empty, closest to None"):
    val jr =
      new jverify.VerificationResult(0, 0, true, java.util.List.of(), java.util.Optional.empty())
    val result = FacadeDecode.verificationResult(jr)
    assertEquals(result.matched, 0)
    assertEquals(result.total, 0)
    assertEquals(result.satisfied, true)
    assertEquals(result.requests, Vector.empty)
    assertEquals(result.closest, None)

  test("a decode failure inside requests propagates as RiftError.DecodeFailed, never dropped"):
    val badRequest = jreqRaw("""{"path":"/x"}""") // missing the required "method" field
    val jr = new jverify.VerificationResult(
      1,
      1,
      true,
      java.util.List.of(badRequest),
      java.util.Optional.empty()
    )
    intercept[RiftError.DecodeFailed](FacadeDecode.verificationResult(jr))

  // `closest` is the deepest decode path and the easiest place for a future edit to reintroduce the
  // drop-on-failure behaviour this whole type exists to avoid — the error-path `VerificationReport`
  // does exactly that by design, so the two shapes are one careless `.toOption` apart. One case per
  // decode call inside `closestMiss`.
  test("a decode failure in the closest miss's own request propagates, never dropped"):
    val jr = new jverify.VerificationResult(
      0,
      1,
      false,
      java.util.List.of(),
      java.util.Optional.of(
        new jverify.ClosestMiss(jreqRaw("""{"path":"/x"}"""), java.util.List.of())
      )
    )
    intercept[RiftError.DecodeFailed](FacadeDecode.verificationResult(jr))

  // The third decode call in `closestMiss` — `Predicate.fromJson` over `fp.predicate().toJson()` —
  // has no negative test because no reachable input produces one: the JSON always comes from a
  // `JPredicate` the facade already validated, and probing for a shape the facade accepts but
  // rift-scala rejects (unknown operator, several operators, empty object, a non-integer
  // statusCode) found none — the facade is at least as strict on every candidate. The call still
  // goes through the same `decodeOrThrow` the two tests above exercise, so a `.toOption` added
  // there would fail them.

  test("FacadeEncode.verifyDetails is total: Requests -> REQUESTS, Closest -> CLOSEST"):
    assertEquals(
      FacadeEncode.verifyDetails(Seq(VerifyDetail.Requests, VerifyDetail.Closest)).toVector,
      Vector(jverify.VerifyDetail.REQUESTS, jverify.VerifyDetail.CLOSEST)
    )
    assertEquals(FacadeEncode.verifyDetails(Seq.empty).toVector, Vector.empty)
