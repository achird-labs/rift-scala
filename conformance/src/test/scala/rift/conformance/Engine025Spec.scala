package rift.conformance

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import scala.jdk.OptionConverters.*

import zio.*
import zio.test.*

import rift.RiftError
import rift.model.Port
import rift.zio.{ImposterHandle, Rift}

/** Live checks for engine behaviour rift-scala adopts at the 0.25.0 pin (rift-java 0.3.9). Same
  * lanes and `RIFT_G3_REQUIRE` guard as `Engine018Spec`/`Engine020Spec`.
  *
  * #219: since rift#1453 the engine accepts any JSON value for `allowCORS`, enables CORS by
  * JavaScript truthiness, and echoes the value verbatim in the imposter view. The view crosses
  * rift-java's decoder and then this SDK's, and a model-level test cannot show that both accept it.
  */
object Engine025Spec extends ZIOSpecDefault:

  def spec = suite("engine 0.25.0 live checks")(
    lane(
      "embedded",
      G3Require.decideEmbedded(rift.bridge.RiftConnector.isEmbeddedAvailable, G3Require.required),
      Rift.embedded
    ),
    lane("spawn", G3Require.decideSpawn(G3Require.required), Rift.spawn())
  ) @@ TestAspect.sequential

  private def lane(
      name: String,
      decision: G3Require.Decision,
      layer: ZLayer[Any, RiftError, Rift]
  ) =
    decision match
      case G3Require.Decision.Run =>
        (suite(s"$name lane")(allowCorsCases*) @@ TestAspect.sequential)
          .provideSomeLayerShared[Scope](layer.orDie)
      case G3Require.Decision.Skip =>
        suite(s"$name lane")(
          test("skipped: this lane is not required on this job") {
            ZIO
              .logWarning(s"engine 0.25.0 live checks skipped on the $name lane")
              .as(assertCompletes)
          }
        )
      case G3Require.Decision.Fail(reason) =>
        suite(s"$name lane")(test("required lane unavailable")(ZIO.die(new AssertionError(reason))))

  private val client = HttpClient.newHttpClient()

  private def createdFromJson(json: String): ZIO[Rift & Scope, RiftError, ImposterHandle] =
    ZIO.serviceWithZIO[Rift](r => ZIO.acquireRelease(r.createFromJson(json))(_.delete.orDie))

  private def preflight(imp: ImposterHandle): Task[HttpResponse[String]] =
    ZIO.attemptBlocking(
      client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${Port.value(imp.port)}/anything"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .header("Origin", "http://example.test")
          .header("Access-Control-Request-Method", "GET")
          // The engine answers a preflight only when it names request headers too (Mountebank's
          // rule), so without this even allowCORS:true would send no CORS headers.
          .header("Access-Control-Request-Headers", "content-type")
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    )

  private def allowOrigin(r: HttpResponse[String]): Option[String] =
    r.headers().firstValue("Access-Control-Allow-Origin").toScala

  private def allowCorsCases = Vector(
    test("allowCORS \"yes\": the engine enables CORS and its echoed view decodes as allowCors") {
      for
        imp <- createdFromJson("""{"protocol":"http","allowCORS":"yes"}""")
        definition <- imp.definition
        response <- preflight(imp)
      yield assertTrue(definition.allowCors, allowOrigin(response).isDefined)
    },
    test("allowCORS 0: falsy, so the engine leaves CORS off and the view decodes as disabled") {
      for
        imp <- createdFromJson("""{"protocol":"http","allowCORS":0}""")
        definition <- imp.definition
        response <- preflight(imp)
      yield assertTrue(!definition.allowCors, allowOrigin(response).isEmpty)
    }
  )
