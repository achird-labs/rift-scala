package rift.ziobdd

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import zio.*
import zio.test.*

import zio.bdd.mock as spi

import rift.zio.Rift

import io.github.etacassiopeia.rift.Rift as JRift

/** Live behavioral gate for the adapter (issue #18) — the MockControl contract against a real
  * embedded engine.
  *
  * Guarded exactly like `bridge.EmbeddedSmokeSpec` / the conformance G3 specs:
  * `JRift.isEmbeddedAvailable()` is checked before any layer is built, so a bare JVM (no
  * `rift-java-embedded` on the classpath — any JDK < 22) skips, and this spec's only job there is
  * to COMPILE. The JDK 22 CI job carries the embedded jars (wired in build.sbt), so the whole suite
  * executes there.
  */
object RiftScalaBackendLiveSpec extends ZIOSpecDefault:

  private val client = HttpClient.newHttpClient()

  private def get(baseUri: String, path: String, headers: (String, String)*): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val builder = HttpRequest.newBuilder(URI.create(s"$baseUri$path"))
      headers.foreach((k, v) => builder.header(k, v))
      val res = client.send(builder.GET.build(), HttpResponse.BodyHandlers.ofString())
      (res.statusCode(), res.body())
    }

  private def okRule(path: String, bodyText: String): spi.MockRule =
    spi.MockRule(
      spi.RequestMatch(method = Some(spi.Method.Get), path = spi.PathMatch.Exact(path)),
      spi.ResponseDef(body = spi.Body.Text(bodyText))
    )

  private def withControl[A](isolation: spi.Isolation = spi.Isolation.PerInstance)(
      use: spi.MockControl => ZIO[Scope, Any, A]
  ): ZIO[Scope, Any, A] =
    RiftScalaBackend.embedded(isolation).build.map(_.get[spi.MockControl]).flatMap(use)

  def spec = suite("RiftScalaBackend live (embedded engine, guarded — issue #18)")(
    suite("MockControl contract against a live engine")(
      test(
        "provision serves rules, 404s unmatched, records requests, and destroys exactly one space"
      ) {
        ZIO.scoped {
          withControl() { control =>
            for
              provisioned <- control.provision(
                spi.MockSource.Dsl(spi.MockSpec(List(okRule("/a", "base"))))
              )
              space = provisioned.head
              other <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/b", "other")))))
                .map(_.head)
              hit <- get(space.baseUri, "/a")
              miss <- get(space.baseUri, "/nope")
              received <- control.received(space)
              _ <- control.destroy(other)
              afterDestroy <- get(space.baseUri, "/a")
              gone <- control.received(other).exit
            yield assertTrue(
              hit == (200, "base"),
              miss._1 == 404,
              received.exists(r => r.uri == "/a" && r.method == spi.Method.Get),
              afterDestroy == (200, "base"),
              gone.causeOption
                .flatMap(_.failureOption)
                .contains(spi.MockError.SpaceNotFound(other.id))
            )
          }
        }
      },
      test(
        "overlay rules shadow base rules; removeRule lifts the overlay; replaceRules swaps the set"
      ) {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/a", "base")))))
                .map(_.head)
              overlayId <- control.addRule(space, okRule("/a", "overlay"), spi.Priority.Overlay)
              shadowed <- get(space.baseUri, "/a")
              _ <- control.removeRule(space, overlayId)
              restored <- get(space.baseUri, "/a")
              missingExit <- control.removeRule(space, spi.RuleId("no-such")).exit
              _ <- control.replaceRules(space, List(okRule("/b", "replaced")))
              oldGone <- get(space.baseUri, "/a")
              newServed <- get(space.baseUri, "/b")
            yield assertTrue(
              shadowed == (200, "overlay"),
              restored == (200, "base"),
              missingExit.causeOption
                .flatMap(_.failureOption)
                .contains(spi.MockError.RuleNotFound(space.id, spi.RuleId("no-such"))),
              oldGone._1 == 404,
              newServed == (200, "replaced")
            )
          }
        }
      },
      test("a connection fault makes the SUT's client observe a transport failure") {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/f", "fine")))))
                .map(_.head)
              faultsApi <- control.faults
              _ <- faultsApi.inject(
                space,
                spi.RequestMatch(path = spi.PathMatch.Exact("/f")),
                spi.FaultKind.ConnectionReset
              )
              outcome <- get(space.baseUri, "/f").exit
            yield assertTrue(outcome.isFailure)
          }
        }
      },
      test("stateful scenarios advance, inspect, pin, and reset") {
        ZIO.scoped {
          withControl() { control =>
            val scenario = spi.ScenarioDef(
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
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(Nil)))
                .map(_.head)
              scenarios <- control.scenarios
              inspection <- control.stateInspection
              _ <- scenarios.define(space, scenario)
              first <- get(space.baseUri, "/s")
              second <- get(space.baseUri, "/s")
              state <- inspection.currentState(space, "invoice")
              _ <- scenarios.reset(space, "invoice")
              afterReset <- get(space.baseUri, "/s")
              _ <- inspection.setState(space, "invoice", spi.ScenarioState("Paid"))
              pinned <- get(space.baseUri, "/s")
              unknown <- inspection.currentState(space, "nope").exit
            yield assertTrue(
              first == (200, "a"),
              second == (200, "b"),
              state.value == "Paid",
              afterReset == (200, "a"),
              pinned == (200, "b"),
              unknown.isFailure
            )
          }
        }
      },
      test("templating captures a path segment into the response body") {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(Nil)))
                .map(_.head)
              templating <- control.templating
              _ <- templating.inject(
                space,
                spi.RequestMatch(path = spi.PathMatch.Template("/hello/{name}")),
                spi.ResponseTemplate(
                  body = "Hello NAME",
                  captures = List(spi.TemplateCapture("NAME", spi.TemplateSource.Path, "[^/]+$"))
                )
              )
              greeted <- get(space.baseUri, "/hello/bob")
            yield assertTrue(greeted == (200, "Hello bob"))
          }
        }
      },
      test("a native raw imposter document provisions verbatim and records") {
        ZIO.scoped {
          withControl() { control =>
            val raw =
              """{"protocol":"http","name":"native",
              |"stubs":[{"predicates":[{"equals":{"path":"/n"}}],
              |          "responses":[{"is":{"statusCode":200,"body":"native"}}]}]}""".stripMargin
            for
              spacesList <- control.provisionNative(spi.NativeSpec.Rift(raw))
              space = spacesList.head
              hit <- get(space.baseUri, "/n")
              received <- control.received(space)
            yield assertTrue(hit == (200, "native"), received.exists(_.uri == "/n"))
          }
        }
      },
      test("base-priority addRule appends (an earlier matching rule still wins first-match)") {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/a", "first")))))
                .map(_.head)
              _ <- control.addRule(space, okRule("/a", "second"), spi.Priority.Base)
              _ <- control.addRule(space, okRule("/b", "appended"), spi.Priority.Base)
              stillFirst <- get(space.baseUri, "/a")
              appended <- get(space.baseUri, "/b")
            yield assertTrue(stillFirst == (200, "first"), appended == (200, "appended"))
          }
        }
      },
      test("scripting serves a Rhai-computed response") {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control.provision(spi.MockSource.Dsl(spi.MockSpec(Nil))).map(_.head)
              scripting <- control.scripting
              _ <- scripting.inject(
                space,
                spi.RequestMatch(path = spi.PathMatch.Exact("/js")),
                spi.Script(
                  spi.ScriptEngine.Rhai,
                  """fn respond(ctx) { http(200, #{engine: "rhai"}) }"""
                )
              )
              scripted <- get(space.baseUri, "/js")
            yield assertTrue(scripted._1 == 200, scripted._2.contains("rhai"))
          }
        }
      },
      test("proxyRecord proxies to an upstream and keeps serving the recorded response") {
        ZIO.scoped {
          withControl() { control =>
            for
              upstream <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/p", "from-upstream")))))
                .map(_.head)
              front <- control.provision(spi.MockSource.Dsl(spi.MockSpec(Nil))).map(_.head)
              proxyApi <- control.proxyRecord
              _ <- proxyApi.proxy(
                front,
                spi.RequestMatch(path = spi.PathMatch.Exact("/p")),
                upstream.baseUri
              )
              proxied <- get(front.baseUri, "/p")
              _ <- control.destroy(upstream)
              replayed <- get(front.baseUri, "/p")
            yield assertTrue(proxied == (200, "from-upstream"), replayed == (200, "from-upstream"))
          }
        }
      },
      test(
        "intercept starts lazily, memoizes its port, registers rules, and exports a truststore"
      ) {
        ZIO.scoped {
          withControl() { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/i", "hi")))))
                .map(_.head)
              interceptApi <- control.intercept
              port1 <- interceptApi.proxyPort
              port2 <- interceptApi.proxyPort
              _ <- interceptApi.add(
                spi.InterceptRule
                  .Serve(
                    "inline.example.com",
                    spi.InterceptStub(status = 203, body = Some("inline"))
                  )
              )
              _ <- interceptApi.add(spi.InterceptRule.Redirect("cdn.example.com", space))
              unknown = spi.MockSpace("http://localhost:0", identity, spi.SpaceId("ghost-0"))
              unknownExit <- interceptApi
                .add(spi.InterceptRule.Redirect("x.example.com", unknown))
                .exit
              store <- interceptApi.trustStore()
              storeExists <- ZIO.attemptBlocking(java.nio.file.Files.exists(store.path))
            yield assertTrue(
              port1 > 0,
              port1 == port2,
              unknownExit.causeOption.flatMap(_.failureOption).exists {
                case spi.MockError.InvalidDefinition(_) => true
                case _ => false
              },
              store.password.nonEmpty,
              storeExists
            )
          }
        }
      },
      test("a mid-batch provision failure rolls back the spaces already stood up") {
        ZIO.scoped {
          for
            riftEnv <- Rift.embedded
              .mapError(e => spi.MockError.ProvisionFailed(e.getMessage))
              .build
            controlEnv <- (ZLayer.succeedEnvironment(
              riftEnv
            ) >>> RiftScalaBackend.fromService).build
            control = controlEnv.get[spi.MockControl]
            rift = riftEnv.get[Rift]
            dir <- ZIO.attemptBlocking {
              val d = java.nio.file.Files.createTempDirectory("ziobdd-rollback")
              java.nio.file.Files.writeString(
                d.resolve("a-valid.json"),
                """{"protocol":"http","stubs":[]}"""
              )
              java.nio.file.Files.writeString(d.resolve("b-broken.json"), "not json")
              d
            }
            exit <- control.provision(spi.MockSource.Dir(dir.toString)).exit
            imposters <- rift.imposters.mapError(e =>
              spi.MockError.CommunicationError(e.getMessage)
            )
          yield assertTrue(
            exit.isFailure,
            // The valid first space was created, then rolled back when the malformed one failed.
            imposters.isEmpty
          )
        }
      },
      test(
        "correlated rule mutation rebuilds the flow: overlay shadows, remove restores, gaps are typed"
      ) {
        ZIO.scoped {
          withControl(spi.Isolation.Correlated) { control =>
            for
              space <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/m", "base")))))
                .map(_.head)
              hdr = space
                .inject(spi.HttpRequest(spi.Method.Get, "/m"))
                .headers
                .entries
                .map((k, vs) => k -> vs.head)
              _ <- control.addRule(space, okRule("/m2", "appended"), spi.Priority.Base)
              appended <- get(space.baseUri, "/m2", hdr*)
              overlayId <- control.addRule(space, okRule("/m", "overlay"), spi.Priority.Overlay)
              shadowed <- get(space.baseUri, "/m", hdr*)
              _ <- control.removeRule(space, overlayId)
              restored <- get(space.baseUri, "/m", hdr*)
              missing <- control.removeRule(space, spi.RuleId("no-such")).exit
              _ <- control.replaceRules(space, List(okRule("/m3", "swapped")))
              swapped <- get(space.baseUri, "/m3", hdr*)
              oldGone <- get(space.baseUri, "/m", hdr*)
              scenarios <- control.scenarios
              gap <- scenarios
                .define(space, spi.ScenarioDef("s", Nil))
                .exit
            yield assertTrue(
              appended == (200, "appended"),
              shadowed == (200, "overlay"),
              restored == (200, "base"),
              missing.causeOption
                .flatMap(_.failureOption)
                .contains(spi.MockError.RuleNotFound(space.id, spi.RuleId("no-such"))),
              swapped == (200, "swapped"),
              oldGone._1 == 404,
              gap.causeOption.flatMap(_.failureOption).exists {
                case spi.MockError.InvalidDefinition(reason) => reason.contains("per-flow")
                case _ => false
              }
            )
          }
        }
      },
      test("correlated spaces share one imposter but stay header-isolated") {
        ZIO.scoped {
          withControl(spi.Isolation.Correlated) { control =>
            for
              alice <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/c", "alice")))))
                .map(_.head)
              bob <- control
                .provision(spi.MockSource.Dsl(spi.MockSpec(List(okRule("/c", "bob")))))
                .map(_.head)
              aliceHeader = alice.inject(spi.HttpRequest(spi.Method.Get, "/c"))
              aliceRes <- get(
                alice.baseUri,
                "/c",
                aliceHeader.headers.entries.map((k, vs) => k -> vs.head)*
              )
              bobHeader = bob.inject(spi.HttpRequest(spi.Method.Get, "/c"))
              bobRes <- get(
                bob.baseUri,
                "/c",
                bobHeader.headers.entries.map((k, vs) => k -> vs.head)*
              )
              unmatched <- get(alice.baseUri, "/c")
              aliceSeen <- control.received(alice)
              _ <- control.destroy(bob)
              aliceStill <- get(
                alice.baseUri,
                "/c",
                aliceHeader.headers.entries.map((k, vs) => k -> vs.head)*
              )
            yield assertTrue(
              alice.baseUri == bob.baseUri,
              aliceRes == (200, "alice"),
              bobRes == (200, "bob"),
              unmatched._1 == 404,
              aliceSeen.nonEmpty,
              aliceStill == (200, "alice")
            )
          }
        }
      },
      test(
        "correlated scenarios advance + inspect per-flow; writes and custom initial are typed gaps"
      ) {
        ZIO.scoped {
          withControl(spi.Isolation.Correlated) { control =>
            def hdr(space: spi.MockSpace): Seq[(String, String)] =
              space
                .inject(spi.HttpRequest(spi.Method.Get, "/s"))
                .headers
                .entries
                .map((k, vs) => k -> vs.head)
            val scenario = spi.ScenarioDef(
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
            def isInvalidDef(e: Exit[Any, Any]): Boolean =
              e.causeOption.flatMap(_.failureOption).exists {
                case _: spi.MockError.InvalidDefinition => true
                case _ => false
              }
            for
              alice <- control.provision(spi.MockSource.Dsl(spi.MockSpec(Nil))).map(_.head)
              bob <- control.provision(spi.MockSource.Dsl(spi.MockSpec(Nil))).map(_.head)
              scenarios <- control.scenarios
              inspection <- control.stateInspection
              _ <- scenarios.define(alice, scenario)
              _ <- scenarios.define(bob, scenario)
              // One request on alice's flow advances only alice: Started -> Paid.
              aliceServed <- get(alice.baseUri, "/s", hdr(alice)*)
              aliceState <- inspection.currentState(alice, "invoice")
              bobState <- inspection.currentState(bob, "invoice")
              unknownState <- inspection.currentState(alice, "nope").exit
              // State writes and a custom initial state remain gapped (rift-java#151).
              resetGap <- scenarios.reset(alice, "invoice").exit
              setGap <- inspection.setState(alice, "invoice", spi.ScenarioState("Paid")).exit
              customGap <- scenarios
                .define(
                  bob,
                  spi.ScenarioDef(
                    "custom",
                    List(
                      spi.StatefulRule(
                        spi.ScenarioState("Custom"),
                        spi.RequestMatch(path = spi.PathMatch.Exact("/x")),
                        spi.ResponseDef(body = spi.Body.Text("x")),
                        None
                      )
                    ),
                    spi.ScenarioState("Custom")
                  )
                )
                .exit
              // A rule mutation rebuilds bob's flow; the scenario stubs must SURVIVE (issue #65
              // review — they were previously untracked and silently dropped). bob was never
              // advanced, so it re-serves "a" from the reset-to-Started scenario, and currentState
              // still finds it.
              _ <- control.addRule(bob, okRule("/other", "z"), spi.Priority.Overlay)
              bobScenarioSurvived <- get(bob.baseUri, "/s", hdr(bob)*)
              bobStillDefined <- inspection.currentState(bob, "invoice")
            yield assertTrue(
              aliceServed == (200, "a"),
              aliceState.value == "Paid",
              bobState.value == "Started",
              // undeclared scenario is a TYPED failure, not a defect (matches the write-gap arms)
              isInvalidDef(unknownState),
              isInvalidDef(resetGap),
              isInvalidDef(setGap),
              isInvalidDef(customGap),
              bobScenarioSurvived == (200, "a"),
              bobStillDefined.value == "Started"
            )
          }
        }
      }
    ) @@ (
      // Checked BEFORE any engine layer is built — the repo-standard guard shape (see
      // LedgerPatternSampleSpec's "WHY THIS TEST IS GUARDED"): a bare JVM skips, never fails.
      if JRift.isEmbeddedAvailable() then TestAspect.identity
      else TestAspect.ignore
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock,
    // Always runs (outside the ignore aspect): the #63 backstop. On the CI job that requires the
    // embedded lane (RIFT_G3_REQUIRE=embedded), a silently-unavailable engine — which would let the
    // 13 tests above `ignore`-skip green — fails HERE instead. Unset / other-lane jobs pass.
    test("embedded lane actually ran when this job required it (issue #63 backstop)") {
      // Don't weaken this to always-pass without re-running the RIFT_G3_REQUIRE=embedded red-proof.
      G3Require.decideEmbedded(JRift.isEmbeddedAvailable(), G3Require.required) match
        case G3Require.Decision.Fail(reason) => ZIO.die(new AssertionError(reason))
        case _ => ZIO.succeed(assertCompletes)
    }
  )
