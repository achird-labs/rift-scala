package rift.zio.testkit

import zio.*
import zio.test.*

import rift.json.Json
import rift.model.*
// `ZIOSpecDefault` itself declares a member named `aspects` (the default `TestAspect`s applied to
// every spec), which shadows the top-level `rift.zio.testkit.aspects` object under test — aliased
// here so calls below resolve to the module under test, not the inherited default-aspects list.
import rift.zio.testkit.aspects as testedAspects

/** Covers `aspects.withLatencyFault` (the pure fault-merge `withLatency` folds over every stub) in
  * isolation from the engine — no `Rift` layer, no I/O, just the `Stub => Stub` mapping. The
  * engine-dependent path (`withLatency` actually reaching a live imposter) is covered by the
  * conformance corpus (#6), not here.
  */
object AspectsSpec extends ZIOSpecDefault:

  private val min = 100.millis
  private val max = 200.millis
  private val probability = 0.5

  private def isStub(response: Response.Is): Stub =
    Stub(predicates = Vector.empty, responses = Vector(response))

  def spec = suite("aspects")(
    suite("withLatencyFault")(
      test("an Is response gains the latency fault") {
        val stub = isStub(Response.Is(IsResponse(statusCode = Some(200))))

        val result = testedAspects.withLatencyFault(stub, min, max, probability)

        val fault = result.responses.collect { case Response.Is(_, _, Some(ext), _) =>
          ext.fault.flatMap(_.latency)
        }.flatten

        assertTrue(
          fault == Vector(
            LatencyFault(probability, minMs = Some(min.toMillis), maxMs = Some(max.toMillis))
          )
        )
      },
      test("existing behaviors, extra, and other fault config survive the merge") {
        val existingFault =
          FaultConfig(
            error = Some(ErrorFault(probability = 0.1, status = 500)),
            tcp = Some(TcpFault(TcpFaultKind.ConnectionResetByPeer))
          )
        val response: Response.Is = Response.Is(
          response = IsResponse(statusCode = Some(200)),
          behaviors = Behaviors(waitFor = Some(WaitBehavior.Fixed(50))),
          rift = Some(RiftResponseExt(fault = Some(existingFault))),
          extra = Vector("x-custom" -> Json.Str("kept"))
        )
        val stub = isStub(response)

        val result = testedAspects.withLatencyFault(stub, min, max, probability)

        result.responses match
          case Vector(Response.Is(is, behaviors, Some(ext), extra)) =>
            assertTrue(
              is == response.response,
              behaviors == response.behaviors,
              extra == response.extra,
              ext.fault.flatMap(_.error) == existingFault.error,
              ext.fault.flatMap(_.tcp) == existingFault.tcp,
              ext.fault.flatMap(_.latency).isDefined
            )
          case other => assertTrue(false)
      },
      test("non-Is responses are left unchanged") {
        val proxy = Response.Proxy(ProxyResponse("http://example.com"))
        val inject = Response.Inject("function () { return true; }")
        val stub = Stub(predicates = Vector.empty, responses = Vector(proxy, inject))

        val result = testedAspects.withLatencyFault(stub, min, max, probability)

        assertTrue(result.responses == Vector(proxy, inject))
      }
    )
  )
