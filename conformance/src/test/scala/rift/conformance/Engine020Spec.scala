package rift.conformance

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant

import scala.jdk.OptionConverters.*

import zio.*
import zio.test.*

import rift.RiftError
import rift.bridge.{ForwardTarget, RiftVersions}
import rift.dsl.*
import rift.json.Json
import rift.model.{Method, Port}
import rift.zio.{ImposterHandle, InterceptHandle, Rift}

/** Live checks that engine 0.20.0 does what rift-scala writes for the features adopted in #206:
  * `_rift.conditional`, atomic intercept rule replace, and a forward target's host and scheme.
  * JSON-level tests cannot catch a key the engine parses and ignores; these assert effects only a
  * live engine produces.
  *
  * Same lanes and `RIFT_G3_REQUIRE` guard as `Engine018Spec`: the conditional cases run on both,
  * the intercept cases only on the embedded lane (the spawn lane runs no intercept).
  */
object Engine020Spec extends ZIOSpecDefault:

  def spec = suite("engine 0.20.0 live checks (issue #206)")(
    test("the pinned engine is at least 0.20.0") {
      val parts = RiftVersions.engine.split('.').toList.flatMap(_.toIntOption)
      assertTrue(parts.take(2) match
        case major :: minor :: Nil => major > 0 || minor >= 20
        case _ => false
      )
    },
    lane(
      "embedded",
      G3Require.decideEmbedded(rift.bridge.RiftConnector.isEmbeddedAvailable, G3Require.required),
      Rift.embedded,
      intercept = true
    ),
    lane("spawn", G3Require.decideSpawn(G3Require.required), Rift.spawn(), intercept = false)
  ) @@ TestAspect.sequential

  private def lane(
      name: String,
      decision: G3Require.Decision,
      layer: ZLayer[Any, RiftError, Rift],
      intercept: Boolean
  ) =
    val label = s"$name lane (intercept ${if intercept then "on" else "off"})"
    decision match
      case G3Require.Decision.Run =>
        (suite(label)(
          (conditionalCases ++ (if intercept then interceptCases else Vector.empty))*
        ) @@ TestAspect.sequential).provideSomeLayerShared[Scope](layer.orDie)
      case G3Require.Decision.Skip =>
        suite(label)(
          test("skipped: this lane is not required on this job") {
            ZIO
              .logWarning(s"engine 0.20.0 live checks skipped on the $name lane")
              .as(assertCompletes)
          }
        )
      case G3Require.Decision.Fail(reason) =>
        suite(label)(test("required lane unavailable")(ZIO.die(new AssertionError(reason))))

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────

  private val client = HttpClient.newHttpClient()

  private def created(b: ImposterBuilder): ZIO[Rift & Scope, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](r => ZIO.acquireRelease(r.create(b))(_.delete.orDie))

  private def send(
      imp: ImposterHandle,
      path: String,
      method: String = "GET",
      headers: Map[String, String] = Map.empty
  ): Task[HttpResponse[String]] =
    ZIO.attemptBlocking {
      val b = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${Port.value(imp.port)}$path"))
        .method(method, HttpRequest.BodyPublishers.noBody())
      headers.foreach((k, v) => b.header(k, v))
      client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

  private def header(r: HttpResponse[String], name: String): Option[String] =
    r.headers().firstValue(name).toScala

  private def viaIntercept(ic: InterceptHandle, url: String): Task[HttpResponse[String]] =
    for
      ssl <- ic.sslContext
      proxy <- ic.proxySelector
      r <- ZIO.attemptBlocking(
        HttpClient
          .newBuilder()
          .proxy(proxy)
          .sslContext(ssl)
          .build()
          .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
          )
      )
    yield r

  private def hosts(rules: Iterable[rift.bridge.InterceptRule]): List[Option[String]] =
    rules.map(_.host).toList

  // ── conditional GET, every lane ────────────────────────────────────────────────────────────────

  private def conditionalCases = Vector(
    test("conditional: 200 with validators, then 304 to a matching If-None-Match") {
      for
        imp <- created(imposter("cond").stub(get("/data").reply(ok.text("v1").conditional)))
        first <- send(imp, "/data")
        etag = header(first, "ETag")
        second <- send(imp, "/data", headers = etag.map("If-None-Match" -> _).toMap)
      yield assertTrue(
        first.statusCode() == 200,
        first.body() == "v1",
        etag.isDefined,
        header(first, "Last-Modified").isDefined,
        second.statusCode() == 304,
        second.body().isEmpty
      )
    },
    test(
      "conditionalWithoutEtag(fixed): the fixed date verbatim, no ETag, 304 on If-Modified-Since"
    ) {
      val fixed = Instant.parse("2026-01-05T08:09:10Z")
      for
        imp <- created(
          imposter("cond-fixed").stub(
            get("/fixed").reply(ok.text("x").conditionalWithoutEtag(fixed))
          )
        )
        first <- send(imp, "/fixed")
        second <- send(
          imp,
          "/fixed",
          headers = Map("If-Modified-Since" -> "Mon, 05 Jan 2026 08:09:10 GMT")
        )
      yield assertTrue(
        header(first, "Last-Modified").contains("Mon, 05 Jan 2026 08:09:10 GMT"),
        header(first, "ETag").isEmpty,
        second.statusCode() == 304
      )
    },
    test("conditional: HEAD revalidates to 304, and a POST carries no validators") {
      for
        imp <- created(
          imposter("cond-methods")
            .stub(head("/data").reply(ok.text("v1").conditional))
            .stub(on(Method.POST, "/data").reply(ok.text("v1").conditional))
        )
        first <- send(imp, "/data", "HEAD")
        etag = header(first, "ETag")
        revalidated <- send(imp, "/data", "HEAD", etag.map("If-None-Match" -> _).toMap)
        post <- send(imp, "/data", "POST")
      yield assertTrue(
        etag.isDefined,
        revalidated.statusCode() == 304,
        post.statusCode() == 200,
        header(post, "ETag").isEmpty,
        header(post, "Last-Modified").isEmpty
      )
    }
  )

  // ── intercept, embedded lane ──────────────────────────────────────────────────────────────────

  private def interceptCases = Vector(
    test("replaceRules swaps the served rule set in one call, and an empty declare clears it") {
      for
        ic <- ZIO.serviceWithZIO[Rift](_.intercept())
        _ <- ic.rule("a.example.com").serve(ok.text("A"))
        installed <- ic.replaceRules { set =>
          set.rule("b.example.com").serve(ok.text("B"))
          ()
        }
        afterSwap <- ic.rules
        served <- viaIntercept(ic, "https://b.example.com/")
        // The swapped-out host no longer gets its rule's answer (it falls through to the engine's
        // no-rule handling, which may be an error or a failed connection).
        old <- viaIntercept(ic, "https://a.example.com/").either
        // A refusal while staging sends nothing: the rule staged before it never lands.
        refused <- ic.replaceRules { set =>
          set.rule("c.example.com").serve(ok.text("C"))
          set.rule().serve(ok.conditional)
          ()
        }.exit
        afterRefusal <- ic.rules
        _ <- ic.replaceRules(_ => ())
        afterClear <- ic.rules
      yield assertTrue(
        hosts(installed) == List(Some("b.example.com")),
        hosts(afterSwap) == List(Some("b.example.com")),
        served.statusCode() == 200,
        served.body() == "B",
        !old.exists(r => r.statusCode() == 200 && r.body() == "A"),
        refused.causeOption
          .flatMap(_.failureOption)
          .exists(_.isInstanceOf[RiftError.InvalidDefinition]),
        hosts(afterRefusal) == List(Some("b.example.com")),
        afterClear.isEmpty
      )
    },
    test("removeRule drops a rule read back from the engine and keeps the rest in order") {
      for
        ic <- ZIO.serviceWithZIO[Rift](_.intercept())
        _ <- ic.replaceRules { set =>
          set.rule("x.example.com").serve(ok.text("X"))
          set.rule("y.example.com").when(get("/y")).serve(ok.text("Y"))
          set.rule("z.example.com").serve(ok.text("Z"))
          ()
        }
        before <- ic.rules
        removed <- ic.removeRule(before(1))
        missing <- ic.removeRule(before(1))
        after <- ic.rules
        reordered <- ic.replaceRules(after.reverse)
        last <- ic.rules
      yield assertTrue(
        removed,
        !missing,
        hosts(after) == List(Some("x.example.com"), Some("z.example.com")),
        hosts(reordered) == List(Some("z.example.com"), Some("x.example.com")),
        hosts(last) == List(Some("z.example.com"), Some("x.example.com"))
      )
    },
    test("removeRule matches an all-hosts rule read back from the engine") {
      for
        ic <- ZIO.serviceWithZIO[Rift](_.intercept())
        _ <- ic.replaceRules { set =>
          set.rule().when(get("/any")).serve(ok.text("ANY"))
          set.rule("k.example.com").serve(ok.text("K"))
          ()
        }
        before <- ic.rules
        catchAll = before.find(_.host.isEmpty)
        removed <- ZIO.foreach(catchAll)(ic.removeRule)
        after <- ic.rules
        // A rule as a staging terminal returned it, not read back, matches too.
        staged <- ic.replaceRules { set =>
          set.rule("m.example.com").when(get("/m")).serve(ok.text("M"))
          set.rule("n.example.com").serve(ok.text("N"))
          ()
        }
        removedStaged <- ic.removeRule(staged.head)
        afterStaged <- ic.rules
      yield assertTrue(
        catchAll.isDefined,
        removed.contains(true),
        hosts(after) == List(Some("k.example.com")),
        removedStaged,
        hosts(afterStaged) == List(Some("n.example.com"))
      )
    },
    test("forward(ForwardTarget) stores the target's host and https scheme on the engine's rule") {
      for
        imp <- created(imposter("fwd-target").stub(get("/").reply(ok)))
        ic <- ZIO.serviceWithZIO[Rift](_.intercept())
        target <- ZIO
          .fromEither(ForwardTarget.from("127.0.0.1", imp.port, https = true))
          .orDieWith(new AssertionError(_))
        _ <- ic.rule("tls.example.com").forward(target)
        _ <- ic.rule("plain.example.com").forward(imp.port)
        rules <- ic.rules
        forwardOf = (host: String) =>
          rules.find(_.host.contains(host)).flatMap(_.raw.get("action", "forward"))
      yield assertTrue(
        forwardOf("tls.example.com").flatMap(_.get("host")).contains(Json.Str("127.0.0.1")),
        forwardOf("tls.example.com").flatMap(_.get("scheme")).contains(Json.Str("https")),
        forwardOf("plain.example.com")
          .flatMap(_.get("port"))
          .contains(
            Json.Num(BigDecimal(Port.value(imp.port)))
          ),
        forwardOf("plain.example.com").exists(_.get("host").isEmpty),
        forwardOf("plain.example.com").exists(_.get("scheme").isEmpty)
      )
    }
  )
