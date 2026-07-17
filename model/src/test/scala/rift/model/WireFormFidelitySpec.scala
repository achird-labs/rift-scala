package rift.model

import rift.json.Json

/** Targeted round-trip/rejection guards for the non-canonical wire forms fixed in issue #54, beyond
  * what the corpus fixtures happen to carry: the bare-vs-array `copy` disambiguation (the reason
  * `Behaviors.singletonVectorKeys` exists), and the loud-rejection branches added to the
  * `statusCode`/header decoders. A regression in these would otherwise ship silently — no corpus
  * fixture exercises the 1-element-array `copy` form or the invalid inputs.
  */
class WireFormFidelitySpec extends munit.FunSuite:

  private def parse(s: String): Json =
    Json.parse(s).fold(e => fail(s"not valid JSON: $e"), identity)

  private val copyEntry =
    """{"from":"path","into":"${x}","using":{"method":"regex","selector":"/(\\d+)/"}}"""

  test("_behaviors copy: a bare single object round-trips bare (not wrapped in a 1-element array)"):
    val j = parse(s"""{"copy":$copyEntry}""")
    val b = Behaviors.fromJson(j).fold(e => fail(e.toString), identity)
    assert(b.toJson.semanticEquals(j), s"bare copy did not round-trip:\n${b.toJson.renderPretty}")

  test("_behaviors copy: a 1-element array round-trips as a 1-element array (not unwrapped)"):
    val j = parse(s"""{"copy":[$copyEntry]}""")
    val b = Behaviors.fromJson(j).fold(e => fail(e.toString), identity)
    assert(b.toJson.semanticEquals(j), s"array copy did not round-trip:\n${b.toJson.renderPretty}")

  test("_behaviors copy: a non-array object missing the required fields is a decode error"):
    assert(Behaviors.fromJson(parse("""{"copy":{"a":1}}""")).isLeft)

  test("is.statusCode as a boolean is a decode error, not silently accepted"):
    assert(IsResponse.fromJson(parse("""{"statusCode":true}""")).isLeft)

  test("a header value that is an object is a decode error"):
    assert(Headers.fromJson(parse("""{"X":{"a":1}}""")).isLeft)

  test("a header value that is an empty array is a decode error"):
    assert(Headers.fromJson(parse("""{"X":[]}""")).isLeft)
