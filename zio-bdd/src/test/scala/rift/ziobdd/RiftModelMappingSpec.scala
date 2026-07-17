package rift.ziobdd

import zio.durationInt
import zio.test.*

import zio.bdd.mock as spi

import rift.RiftError
import rift.json.Json
import rift.model.{Port, StubId}

/** Engine-free gate for the portable-model → rift wire translation (issue #18).
  *
  * Every assertion compares the built wire JSON against a hand-written expected document mirroring
  * the shapes zio-bdd's own engine-direct adapter (`zio.bdd.mock.rift.RiftProtocol`, v1.4.3) puts
  * on the wire — so a suite written against upstream's Rift adapter observes the same engine
  * behavior through this one. Where this adapter deliberately differs (per-field proxy
  * `predicateGenerators`), the test pins our shape and the divergence is called out inline.
  */
object RiftModelMappingSpec extends ZIOSpecDefault:

  private def parse(s: String): Json = Json.parse(s).fold(e => sys.error(e.toString), identity)

  private def rid(s: String): spi.RuleId = spi.RuleId(s)

  def spec = suite("RiftModelMapping (engine-free, issue #18)")(
    suite("stubFor — MockRule → stub wire JSON")(
      test("full match + response maps to upstream-parity JSON (predicate order pinned)") {
        val rule = spi.MockRule(
          spi.RequestMatch(
            method = Some(spi.Method.Get),
            path = spi.PathMatch.Exact("/users"),
            query = Map("page" -> spi.ValueMatch.Equals("2")),
            headers = Map("Accept" -> spi.ValueMatch.Contains("json")),
            body = Some(spi.BodyMatch.JsonPath("$.role", Some("admin")))
          ),
          spi.ResponseDef(
            status = 201,
            headers = spi.Headers.multi("set-cookie" -> List("a", "b")),
            body = spi.Body.Json("""{"x":1}"""),
            delay = Some(150.millis)
          )
        )
        val expected = parse(
          """{"id":"r1",
            |"predicates":[
            |  {"equals":{"method":"GET"}},
            |  {"equals":{"path":"/users"}},
            |  {"equals":{"query":{"page":"2"}}},
            |  {"contains":{"headers":{"Accept":"json"}}},
            |  {"jsonpath":{"selector":"$.role"},"equals":{"body":"admin"}}],
            |"responses":[
            |  {"is":{"statusCode":201,"headers":{"set-cookie":["a","b"]},"body":"{\"x\":1}"},
            |   "_behaviors":{"wait":150}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping.stubFor(rule, rid("r1")).map(_.build.toJson.semanticEquals(expected)) ==
            Right(true)
        )
      },
      test("regex and template paths become matches predicates (template anchored)") {
        val regex = spi.MockRule(
          spi.RequestMatch(path = spi.PathMatch.Regex("^/id/[0-9]+$")),
          spi.ResponseDef()
        )
        val template = spi.MockRule(
          spi.RequestMatch(path = spi.PathMatch.Template("/users/{id}/posts/{post}")),
          spi.ResponseDef()
        )
        val expectedRegex = parse(
          """{"id":"r1","predicates":[{"matches":{"path":"^/id/[0-9]+$"}}],
            |"responses":[{"is":{"statusCode":200}}]}""".stripMargin
        )
        val expectedTemplate = parse(
          """{"id":"r2","predicates":[{"matches":{"path":"^/users/[^/]+/posts/[^/]+$"}}],
            |"responses":[{"is":{"statusCode":200}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping
            .stubFor(regex, rid("r1"))
            .map(_.build.toJson.semanticEquals(expectedRegex)) == Right(true),
          RiftModelMapping
            .stubFor(template, rid("r2"))
            .map(_.build.toJson.semanticEquals(expectedTemplate)) == Right(true)
        )
      },
      test("xpath body match without expected value becomes an exists predicate") {
        val rule = spi.MockRule(
          spi.RequestMatch(body = Some(spi.BodyMatch.XPath("//user/@role", None))),
          spi.ResponseDef()
        )
        val expected = parse(
          """{"id":"r1",
            |"predicates":[{"xpath":{"selector":"//user/@role"},"exists":{"body":true}}],
            |"responses":[{"is":{"statusCode":200}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping.stubFor(rule, rid("r1")).map(_.build.toJson.semanticEquals(expected)) ==
            Right(true)
        )
      },
      test("base64 body round-trips through binary mode; invalid base64 is a typed error") {
        val payload = java.util.Base64.getEncoder.encodeToString("hello".getBytes)
        val ok = spi.MockRule(spi.RequestMatch(), spi.ResponseDef(body = spi.Body.Base64(payload)))
        val bad =
          spi.MockRule(spi.RequestMatch(), spi.ResponseDef(body = spi.Body.Base64("!!not-b64!!")))
        val expected = parse(
          s"""{"id":"r1","predicates":[],
             |"responses":[{"is":{"statusCode":200,"body":"$payload","_mode":"binary"}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping.stubFor(ok, rid("r1")).map(_.build.toJson.semanticEquals(expected)) ==
            Right(true),
          RiftModelMapping
            .stubFor(bad, rid("r2"))
            .left
            .exists(_.isInstanceOf[spi.MockError.InvalidDefinition])
        )
      }
    ),
    suite("capability stubs")(
      test("LatencySpike maps to _rift.fault.latency at probability 1") {
        val expected = parse(
          """{"id":"f1","predicates":[{"equals":{"path":"/slow"}}],
            |"responses":[{"is":{"statusCode":200},
            |  "_rift":{"fault":{"latency":{"probability":1.0,"ms":250}}}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping
            .faultStub(
              spi.RequestMatch(path = spi.PathMatch.Exact("/slow")),
              spi.FaultKind.LatencySpike(250.millis),
              rid("f1")
            )
            .map(_.build.toJson.semanticEquals(expected)) == Right(true)
        )
      },
      test("the four connection kinds map to the exact _rift.fault.tcp tokens") {
        val kinds = Map(
          spi.FaultKind.ConnectionReset -> "CONNECTION_RESET_BY_PEER",
          spi.FaultKind.EmptyResponse -> "EMPTY_RESPONSE",
          spi.FaultKind.MalformedChunk -> "MALFORMED_RESPONSE_CHUNK",
          spi.FaultKind.RandomThenClose -> "RANDOM_DATA_THEN_CLOSE"
        )
        val results = kinds.map { (kind, token) =>
          RiftModelMapping.faultStub(spi.RequestMatch(), kind, rid("f")) match
            case Right(b) => b.build.toJson.render.contains(s""""tcp":"$token"""")
            case Left(_) => false
        }
        assertTrue(results.forall(identity))
      },
      test("Script maps to _rift.script with the engine token") {
        val expected = parse(
          """{"id":"s1","predicates":[{"equals":{"path":"/js"}}],
            |"responses":[{"_rift":{"script":{"engine":"rhai","code":"fn respond(ctx) {}"}}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping
            .scriptStub(
              spi.RequestMatch(path = spi.PathMatch.Exact("/js")),
              spi.Script(spi.ScriptEngine.Rhai, "fn respond(ctx) {}"),
              rid("s1")
            )
            .map(_.build.toJson.semanticEquals(expected)) == Right(true)
        )
      },
      test("proxy maps to proxyOnce with method+path generators (one generator per field)") {
        // Upstream emits one two-field generator; rift.dsl emits one generator per field — the two
        // shapes produce equivalent recorded-stub predicates (ANDed), so behavior parity holds.
        val expected = parse(
          """{"id":"p1","predicates":[{"equals":{"path":"/api"}}],
            |"responses":[{"proxy":{"to":"http://real.example.com","mode":"proxyOnce",
            |  "predicateGenerators":[
            |    {"matches":{"method":true}},
            |    {"matches":{"path":true}}]}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping
            .proxyStub(
              spi.RequestMatch(path = spi.PathMatch.Exact("/api")),
              "http://real.example.com",
              rid("p1")
            )
            .map(_.build.toJson.semanticEquals(expected)) == Right(true)
        )
      },
      test("ResponseTemplate maps to an is + _behaviors.copy regex capture per token") {
        val template = spi.ResponseTemplate(
          body = "Hello NAME",
          captures = List(spi.TemplateCapture("NAME", spi.TemplateSource.Path, "[^/]+$")),
          status = 200
        )
        val expected = parse(
          """{"id":"t1","predicates":[{"equals":{"path":"/hello"}}],
            |"responses":[{"is":{"statusCode":200,"body":"Hello NAME"},
            |  "_behaviors":{"copy":[
            |    {"from":"path","into":"NAME","using":{"method":"regex","selector":"[^/]+$"}}]}}]}""".stripMargin
        )
        assertTrue(
          RiftModelMapping
            .templateStub(
              spi.RequestMatch(path = spi.PathMatch.Exact("/hello")),
              template,
              rid("t1")
            )
            .map(_.build.toJson.semanticEquals(expected)) == Right(true)
        )
      }
    ),
    suite("scenario stubs")(
      test("StatefulRule maps to the engine's scenario wire triplet") {
        val sc = spi.ScenarioDef(
          "invoice",
          List(
            spi.StatefulRule(
              spi.ScenarioState("Started"),
              spi.RequestMatch(path = spi.PathMatch.Exact("/s")),
              spi.ResponseDef(body = spi.Body.Text("a")),
              Some(spi.ScenarioState("Paid"))
            ),
            spi.StatefulRule(
              spi.ScenarioState("Paid"),
              spi.RequestMatch(path = spi.PathMatch.Exact("/s")),
              spi.ResponseDef(body = spi.Body.Text("b")),
              None
            )
          )
        )
        val expectedFirst = parse(
          """{"id":"r1","scenarioName":"invoice","requiredScenarioState":"Started",
            |"newScenarioState":"Paid",
            |"predicates":[{"equals":{"path":"/s"}}],
            |"responses":[{"is":{"statusCode":200,"body":"a"}}]}""".stripMargin
        )
        val expectedSecond = parse(
          """{"id":"r2","scenarioName":"invoice","requiredScenarioState":"Paid",
            |"predicates":[{"equals":{"path":"/s"}}],
            |"responses":[{"is":{"statusCode":200,"body":"b"}}]}""".stripMargin
        )
        val stubs = RiftModelMapping.scenarioStubs(sc, Vector(rid("r1"), rid("r2")))
        assertTrue(
          stubs.map(_.size) == Right(2),
          stubs.map(_(0).toJson.semanticEquals(expectedFirst)) == Right(true),
          stubs.map(_(1).toJson.semanticEquals(expectedSecond)) == Right(true)
        )
      }
    ),
    suite("imposter shell + raw sources")(
      test("shell carries 404 default, recording, flow state, and the ephemeral-port convention") {
        val json = RiftModelMapping.imposterShell("web", None, None).build.toJson
        val correlated =
          RiftModelMapping.imposterShell("correlated", None, Some("X-Mock-Space")).build.toJson
        assertTrue(
          json.get("port").isEmpty, // no authored port → engine-assigned ephemeral
          json.get("name").contains(Json.Str("web")),
          json.get("recordRequests").contains(Json.Bool(true)),
          json.get("defaultResponse", "statusCode").contains(Json.Num(BigDecimal(404))),
          json.get("_rift", "flowState").isDefined,
          correlated
            .get("_rift", "flowState", "flowIdSource")
            .contains(Json.Str("header:X-Mock-Space"))
        )
      },
      test("authored port is honoured verbatim") {
        val json = RiftModelMapping.imposterShell("web", Some(4599), None).build.toJson
        assertTrue(json.get("port").contains(Json.Num(BigDecimal(4599))))
      },
      test("fromRaw forces recording on unless the document explicitly opts out") {
        val bare = """{"protocol":"http","stubs":[]}"""
        val optOut = """{"protocol":"http","recordRequests":false,"stubs":[]}"""
        val withPort = """{"port":4598,"protocol":"http","stubs":[]}"""
        assertTrue(
          RiftModelMapping.fromRaw(bare, honourDocPort = false).map(_.recordRequests) ==
            Right(true),
          RiftModelMapping.fromRaw(optOut, honourDocPort = false).map(_.recordRequests) ==
            Right(false),
          RiftModelMapping.fromRaw(withPort, honourDocPort = false).map(_.port) == Right(None),
          RiftModelMapping.fromRaw(withPort, honourDocPort = true).map(_.port.map(Port.value)) ==
            Right(Some(4598)),
          RiftModelMapping
            .fromRaw("not json", honourDocPort = true)
            .left
            .exists(_.isInstanceOf[spi.MockError.InvalidDefinition])
        )
      }
    ),
    suite("readback + errors")(
      test("toRecorded maps method, path→uri, multi-value headers, and text body") {
        val rec = rift.model.RecordedRequest(
          method = rift.model.Method.POST,
          path = "/orders",
          query = Map.empty,
          headers = rift.model.Headers(
            Vector("Set-Cookie" -> "a", "Set-Cookie" -> "b", "Accept" -> "json")
          ),
          body = None,
          bodyText = Some("""{"n":1}"""),
          timestamp = java.time.Instant.EPOCH,
          requestFrom = None,
          flowId = None,
          pathParams = Map.empty,
          raw = Json.Null
        )
        val mapped = RiftModelMapping.toRecorded(rec)
        assertTrue(
          mapped.method == spi.Method.Post,
          mapped.uri == "/orders",
          mapped.headers.values("set-cookie") == List("a", "b"),
          mapped.headers.first("accept").contains("json"),
          mapped.body.contains("""{"n":1}""")
        )
      },
      test("Custom-wrapped verbs resolve structurally — TRACE/CONNECT are never corrupted to Get") {
        def rec(method: rift.model.Method) = rift.model.RecordedRequest(
          method = method,
          path = "/",
          query = Map.empty,
          headers = rift.model.Headers(Vector.empty),
          body = None,
          bodyText = None,
          timestamp = java.time.Instant.EPOCH,
          requestFrom = None,
          flowId = None,
          pathParams = Map.empty,
          raw = Json.Null
        )
        assertTrue(
          RiftModelMapping.toRecorded(rec(rift.model.Method.Custom("TRACE"))).method ==
            spi.Method.Trace,
          RiftModelMapping.toRecorded(rec(rift.model.Method.Custom("CONNECT"))).method ==
            spi.Method.Connect,
          // A verb the SPI cannot represent keeps upstream's read-tolerance: Get, not a failure.
          RiftModelMapping.toRecorded(rec(rift.model.Method.Custom("PROPFIND"))).method ==
            spi.Method.Get,
          RiftModelMapping.toRecorded(rec(rift.model.Method.DELETE)).method == spi.Method.Delete
        )
      },
      test("toMockError is total over RiftError and follows the documented mapping") {
        import spi.MockError.*
        val space = spi.SpaceId("s-1")
        val port = Port.from(4501).toOption.get
        def m(e: RiftError) = RiftModelMapping.toMockError(Some(space))(e)
        assertTrue(
          m(RiftError.InvalidDefinition("bad", None)) == InvalidDefinition("bad"),
          m(RiftError.EngineUnavailable("down", None)) == ProvisionFailed("down"),
          m(RiftError.CommunicationError("io", None)) == CommunicationError("io"),
          m(RiftError.ImposterNotFound(port)) == SpaceNotFound(space),
          RiftModelMapping.toMockError(None)(RiftError.ImposterNotFound(port)) match
            case CommunicationError(_) => true
            case _ => false
          ,
          m(RiftError.EngineError(500, "boom")) == CommunicationError("engine error 500: boom"),
          m(RiftError.DecodeFailed("junk", None)) == InvalidDefinition("junk"),
          m(
            RiftError.VerificationFailed(
              rift.model.matching.VerificationReport(Vector.empty, Vector.empty)
            )
          ) == CommunicationError(
            rift.model.matching.VerificationReport(Vector.empty, Vector.empty).render
          )
        )
      }
    )
  )
