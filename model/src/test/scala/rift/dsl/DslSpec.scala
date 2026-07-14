package rift.dsl

import rift.json.Json
import rift.model.*
import rift.model.Method.*
import scala.concurrent.duration.*

class DslSpec extends munit.FunSuite:

  private def parse(s: String): Json = Json.parse(s).fold(e => fail(e.toString), identity)

  // ── AC7: request matching grammar ─────────────────────────────────────────
  test("on(method, path) builds an equals predicate over method and path"):
    val stub = on(GET, "/api/users/1").reply(ok).build
    assert(
      stub.toJson.semanticEquals(
        parse(
          """{"predicates":[{"equals":{"method":"GET","path":"/api/users/1"}}],
        |"responses":[{"is":{"statusCode":200}}]}""".stripMargin.replace("\n", "")
        )
      )
    )

  test("per-method shorthands agree with on(...)"):
    assertEquals(get("/health").reply(ok).build, on(GET, "/health").reply(ok).build)
    assertEquals(post("/api/users").reply(ok).build, on(POST, "/api/users").reply(ok).build)

  test("onRequest matches anything until refined"):
    assertEquals(onRequest.reply(ok).build.predicates, Vector.empty)

  test("field selectors and operators build the documented predicates"):
    val stub = on(GET, "/x")
      .where(header("Accept").contains("json"))
      .where(query("page").is("2"))
      .where(path.matches("""/api/users/\d+"""))
      .where(body.jsonPath("$.user.role").is("admin"))
      .reply(ok)
      .build
    val ops = stub.predicates.map(_.op.getClass.getSimpleName.stripSuffix("$"))
    // the leading Equals is on(GET, "/x") — `.where` refines it, it is never replaced
    assertEquals(ops, Vector("Equals", "Contains", "Equals", "Matches", "Equals"))
    assertEquals(
      stub.predicates.last.params.selector,
      Some(PredicateSelector.JsonPath("$.user.role"))
    )

  test("`.where` refines on(method, path) rather than replacing it"):
    // Regression: dropping the on(...) predicate once a `.where` appeared silently widened the stub
    // to every method and path — the opposite of what the expression says.
    val stub = on(GET, "/api/users/1").where(header("Accept").contains("json")).reply(ok).build
    assertEquals(stub.predicates.size, 2)
    assertEquals(
      stub.predicates.head.op,
      PredicateOp.Equals(
        Fields(Vector("method" -> Json.Str("GET"), "path" -> Json.Str("/api/users/1")))
      )
    )

  test("a refined stub still constrains method and path"):
    import rift.model.matching.{MatchResult, RequestMatcher}
    val m = on(GET, "/api/users/1").where(header("Accept").contains("json"))
    val wrongPath = RecordedRequest(
      method = GET,
      path = "/totally/other",
      query = Map.empty,
      headers = Headers(Map("Accept" -> "application/json")),
      body = None,
      bodyText = None,
      timestamp = java.time.Instant.EPOCH,
      requestFrom = None,
      flowId = None,
      pathParams = Map.empty,
      raw = Json.Null
    )
    assertNotEquals(RequestMatcher.evaluate(m, wrongPath), MatchResult.Matched)
    assertNotEquals(RequestMatcher.evaluate(m, wrongPath.copy(method = POST)), MatchResult.Matched)

  test("not / allOf / anyOf nest arbitrarily"):
    val stub = on(GET, "/x")
      .where(not(header("X-Debug").exists))
      .where(anyOf(query("v").is("1"), query("v").is("2")))
      .where(allOf(header("A").exists, not(anyOf(header("B").exists))))
      .reply(ok)
      .build
    // predicates(0) is on(GET, "/x"); the `.where` clauses follow in order
    stub.predicates(1).op match
      case PredicateOp.Not(inner) => assert(inner.op.isInstanceOf[PredicateOp.Exists])
      case other => fail(s"expected Not, got $other")
    stub.predicates(2).op match
      case PredicateOp.Or(ps) => assertEquals(ps.size, 2)
      case other => fail(s"expected Or, got $other")
    stub.predicates(3).op match
      case PredicateOp.And(ps) => assertEquals(ps.size, 2)
      case other => fail(s"expected And, got $other")

  test("caseSensitive and except set predicate params"):
    val stub = on(GET, "/x").where(body.is("A")).caseSensitive.except("""\d+""").reply(ok).build
    assertEquals(stub.predicates.head.params.caseSensitive, true)
    assertEquals(stub.predicates.head.params.except, Some("""\d+"""))

  // ── AC7: responses ────────────────────────────────────────────────────────
  test("status helpers carry the documented codes"):
    assertEquals(statusOf(ok), 200)
    assertEquals(statusOf(created), 201)
    assertEquals(statusOf(accepted), 202)
    assertEquals(statusOf(noContent), 204)
    assertEquals(statusOf(badRequest), 400)
    assertEquals(statusOf(notFound), 404)
    assertEquals(statusOf(status(503)), 503)

  // statusCode is Option on the wire (an is-response may omit it); a status *helper* must always
  // set one explicitly, so unwrapping here is part of the assertion.
  private def statusOf(r: ResponseBuilder): Int = r.build match
    case Response.Is(is, _, _, _) =>
      is.statusCode.getOrElse(fail("a status helper must set an explicit statusCode"))
    case other => fail(s"expected an is-response, got $other")

  test("ok.json validates a raw JSON string at build time"):
    assert(ok.json("""{"id":1}""").build.isInstanceOf[Response])
    intercept[IllegalArgumentException](ok.json("{not json"))

  test("ok.json(a) uses the JsonBody instance"):
    given JsonBody[Int] with
      def encode(a: Int): Json = Json.Num(BigDecimal(a))
      def decode(j: Json): Either[String, Int] = j match
        case Json.Num(v) => Right(v.toInt)
        case _ => Left("not a number")
    assertEquals(
      ok.json(7).build.asInstanceOf[Response.Is].response.body,
      Some(Json.Num(BigDecimal(7)))
    )

  test("headers and templating land on the wire"):
    val r = status(503).header("Retry-After", "30").json("""{"error":"x"}""").templated.build
    val is = r.asInstanceOf[Response.Is]
    assertEquals(is.response.headers.get("Retry-After"), Some("30"))
    assertEquals(is.rift.map(_.templated), Some(true))

  test("behaviors build the Mountebank _behaviors block"):
    val r = ok.after(150.millis).build.asInstanceOf[Response.Is]
    assert(r.toJson.get("_behaviors", "wait").contains(Json.Num(BigDecimal(150))))
    assert(ok.repeat(2).build.toJson.get("_behaviors", "repeat").contains(Json.Num(BigDecimal(2))))

  /** `afterBetween` used to compile to a JS `inject` (`function () { return min + random*spread
    * }`), which needs the engine's injection support at serve time. The engine has a native
    * `{min,max}` wait, so the DSL emits that instead — no script, nothing to enable.
    */
  test("afterBetween emits the native range form, not a JS inject"):
    val ranged = ok.afterBetween(100.millis, 500.millis).build.asInstanceOf[Response.Is]
    assertEquals(
      ranged.toJson.get("_behaviors", "wait"),
      Some(parse("""{"min":100,"max":500}"""))
    )

  // The engine samples `min..=max` inclusively; that it *is* inclusive is an engine property this
  // test cannot observe (it is a conformance assertion, #6) — what it pins is that min == max is a
  // legal degenerate range rather than something the builder rejects.
  test("afterBetween tolerates min == max"):
    val same = ok.afterBetween(200.millis, 200.millis).build.asInstanceOf[Response.Is]
    assertEquals(same.toJson.get("_behaviors", "wait"), Some(parse("""{"min":200,"max":200}""")))

  test("afterBetween rejects a range the engine's u64 could not serve"):
    intercept[IllegalArgumentException](ok.afterBetween(500.millis, 100.millis))
    intercept[IllegalArgumentException](ok.afterBetween(-100.millis, 50.millis))

  test("afterInject emits the {inject: <script>} object form"):
    val injected = ok.afterInject("function () { return 42; }").build.asInstanceOf[Response.Is]
    assertEquals(
      injected.toJson.get("_behaviors", "wait"),
      Some(parse("""{"inject":"function () { return 42; }"}"""))
    )

  test("_rift probabilistic faults compose with an is-response"):
    val r = ok.withLatencyFault(probability = 0.5, 1.second).build.asInstanceOf[Response.Is]
    assert(r.rift.flatMap(_.fault).flatMap(_.latency).isDefined)
    val err = ok.withErrorFault(probability = 0.3, status = 503, body = """{"e":1}""").build
    assert(err.asInstanceOf[Response.Is].rift.flatMap(_.fault).flatMap(_.error).isDefined)

  test("withLatencyFault rejects an out-of-range probability"):
    intercept[IllegalArgumentException](ok.withLatencyFault(probability = 1.5, 1.second))

  test("other response kinds build their wire forms"):
    assert(fault(TcpFaultKind.ConnectionResetByPeer).build.isInstanceOf[Response.Fault])
    assert(inject("function (request) { return {}; }").build.isInstanceOf[Response.Inject])
    assert(proxyTo("https://real.example.com").proxyOnce.build.isInstanceOf[Response.Proxy])
    assert(script(Script.rhai("fn respond(ctx) {}")).build.isInstanceOf[Response.RiftScript])

  // ── AC7: cycling ──────────────────────────────────────────────────────────
  test("thenReply appends responses in cycling order"):
    val stub = on(GET, "/api/resource")
      .reply(status(503).header("Retry-After", "1"))
      .thenReply(status(503))
      .thenReply(ok.json("""{"ok":true}"""))
      .build
    assertEquals(stub.responses.size, 3)
    assertEquals(stub.responses.map(statusOfResponse), Vector(503, 503, 200))

  private def statusOfResponse(r: Response): Int = r match
    case Response.Is(is, _, _, _) =>
      is.statusCode.getOrElse(fail("a cycled response must set an explicit statusCode"))
    case other => fail(s"expected an is-response, got $other")

  // ── AC7: scenarios ────────────────────────────────────────────────────────
  test("scenario builds an FSM with the engine's wire triplet"):
    val stubs = scenario("checkout")
      .startingAt("Started")
      .when("Started", on(POST, "/pay"))
      .respond(ok.json("""{"paid":true}"""))
      .goTo("paid")
      .when("paid", on(GET, "/receipt"))
      .respond(ok.json("""{"receipt":"r-1"}"""))
      .stubs
    assertEquals(stubs.size, 2)
    assertEquals(stubs.head.scenario, Some(ScenarioRef("checkout", Some("Started"), Some("paid"))))
    assertEquals(stubs(1).scenario, Some(ScenarioRef("checkout", Some("paid"), None)))

  // ── AC7: imposters ────────────────────────────────────────────────────────
  test("imposter assembles the documented definition"):
    val d = imposter("users")
      .port(4545)
      .record
      .strictBehaviors
      .defaultResponse(notFound.text("no stub matched"))
      .stub(on(GET, "/api/users/1").reply(ok.json("""{"id":1}""")))
      .build
    assertEquals(d.name, Some("users"))
    assertEquals(d.port.map(Port.value), Some(4545))
    assertEquals(d.recordRequests, true)
    assertEquals(d.strictBehaviors, true)
    assertEquals(d.stubs.size, 1)
    assert(d.defaultResponse.isDefined)

  test("an imposter without an explicit port omits it"):
    assertEquals(imposter("x").build.port, None)

  test("imposter .stubs(...) accepts a scenario's stubs"):
    val d = imposter("s")
      .stubs(scenario("c").startingAt("Started").when("Started", get("/a")).respond(ok).stubs)
      .build
    assertEquals(d.stubs.size, 1)

  test("https carries TLS material"):
    val d = imposter("x").https("CERT", "KEY").build
    assertEquals(d.protocol, Protocol.Https)
    assertEquals(d.tls, Some(TlsMaterial("CERT", "KEY")))

  // ── AC4: escape hatches ───────────────────────────────────────────────────
  test("imposterFromJson accepts raw wire JSON"):
    val b = imposterFromJson("""{"port":4545,"protocol":"http","name":"raw"}""")
    assertEquals(b.map(_.build.name), Right(Some("raw")))

  test("stubFromJson accepts raw wire JSON"):
    val b = stubFromJson("""{"predicates":[],"responses":[{"is":{"statusCode":200}}]}""")
    assertEquals(b.map(_.responses.size), Right(1))

  test("the escape hatches return Left on bad input rather than throwing"):
    assert(imposterFromJson("{not json").isLeft)
    assert(stubFromJson("""{"predicates":"wrong type"}""").isLeft)

  // ── AC8: the same expression stubs and verifies ───────────────────────────
  test("a StubBuilder is usable as a RequestMatch"):
    val lookup = on(GET, "/api/users/1").where(header("Accept").contains("json"))
    val asMatch: RequestMatch = lookup
    assertEquals(asMatch.predicates, lookup.reply(ok).build.predicates)

  test("adding a response does not change the predicate part"):
    val lookup = on(GET, "/x").where(query("a").is("1"))
    assertEquals(lookup.reply(ok).build.predicates, (lookup: RequestMatch).predicates)

  // ── #20: proxy write-side combinators + imposter-level _rift.proxy ─────────
  test("proxyTo combinators build the write-side proxy fields"):
    val r = proxyTo("http://origin").proxyOnce.addWaitBehavior
      .injectHeader("X-Trace", "1")
      .injectHeader("X-Env", "test")
      .decorateWith("function () {}")
      .rewritePath("^/api", "/v2")
      .build
      .asInstanceOf[Response.Proxy]
    assertEquals(r.proxy.addWaitBehavior, true)
    assertEquals(r.proxy.injectHeaders, Vector("X-Trace" -> "1", "X-Env" -> "test"))
    assertEquals(r.proxy.addDecorateBehavior, Some("function () {}"))
    assertEquals(r.proxy.pathRewrite, Some(PathRewrite("^/api", "/v2")))

  test("proxyTo composes the write-side fields with mode and generators"):
    val r = proxyTo("http://origin").proxyTransparent
      .generateBy(RequestField.Method, RequestField.Path)
      .addWaitBehavior
      .build
      .asInstanceOf[Response.Proxy]
    assertEquals(r.proxy.mode, ProxyMode.ProxyTransparent)
    assertEquals(r.proxy.predicateGenerators.size, 2)
    assertEquals(r.proxy.addWaitBehavior, true)

  test("proxyTo emits nothing extra when no write-side field is set"):
    val json = proxyTo("http://origin").build.asInstanceOf[Response.Proxy].proxy.toJson
    assertEquals(json.get("addWaitBehavior"), None)
    assertEquals(json.get("injectHeaders"), None)
    assertEquals(json.get("pathRewrite"), None)

  test("imposter.proxyConfig builds the _rift.proxy block"):
    val d = imposter("api")
      .proxyConfig(upstream("origin.internal", 8443, "https"), connectionPool(50, 30))
      .build
    assertEquals(
      d.rift.flatMap(_.proxy),
      Some(
        ProxyConfig(
          Some(UpstreamConfig("origin.internal", 8443, "https")),
          Some(ConnectionPoolConfig(50, 30L))
        )
      )
    )

  test("imposter.proxyConfig composes with the other _rift blocks"):
    val d = imposter("api").flowState(inMemoryFlowState).proxyConfig(upstream("h", 80)).build
    assert(d.rift.flatMap(_.flowState).isDefined)
    assert(d.rift.flatMap(_.proxy).flatMap(_.upstream).isDefined)
