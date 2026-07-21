# rift-scala-circe

A **codec side-car**: bridges your circe `Encoder`/`Decoder`s to the model's `JsonBody[A]` typeclass,
so request/response bodies can be typed values instead of JSON strings.

```scala
libraryDependencies += "io.github.achird-labs" %% "rift-scala-circe" % riftScalaV
```

## Why it is a separate artifact

Decision **D7** (DESIGN.md §4): no backend forces a JSON library on users. `rift-scala-cats` depends
on cats-effect and nothing else, so a Cats user who already has circe opts in by adding this
artifact — and a ZIO user adds [`rift-scala-zio-json`](../zio-json) instead, on the same
`JsonBody[A]` seam. It also keeps "cats-core never leaks into other modules" true in both directions.

It depends on `model` + **circe-core** only, and **exposes no effect type** — ZIO, Kyo and `pure` code
can use it just as well. `circe-generic` (derivation) and `circe-parser` stay your choice; this
artifact bridges `Encoder`/`Decoder` and parses nothing, so it does not impose them.

## Usage

```scala
import rift.dsl.*
import rift.json.circe.given
import io.circe.Codec

case class User(id: Int, name: String) derives Codec.AsObject

// typed response body
ok.json(User(1, "Alice"))

// typed request matching
on(POST, "/users").where(body.deepEquals(User(2, "Bob"))).reply(ok)
```

`recorded.bodyAs[User]` decodes the same way once a backend surfaces recorded traffic.

> `import rift.json.circe.given` also re-routes the primitives `model` instantiates itself
> (`String`, `Int`, …) through circe, because a given imported into lexical scope outranks one found
> in a companion. They encode identically; only the `decode` failure messages change. Import
> `rift.json.circe.jsonBody` alone to keep the model's messages for primitives.

## AST converters

`Json <-> io.circe.Json`, for dropping down to the AST when a codec is not what you want:

```scala
import rift.json.circe.*

val cjson = riftJson.toCirceJson
val back  = cjson.toRiftJson
```

Both directions preserve object field order and `BigDecimal` precision — including scale and the
`MathContext`, so arithmetic on a decoded number does not round. The model's AST is the wire format,
so reshuffling or rounding it would change what the engine receives.

Both throw `IllegalArgumentException` rather than quietly mangling a value:

- **`toRiftJson`**, for a JSON number with no `BigDecimal` representation. circe keeps a parsed number
  lazily as its source text, so a literal whose scale overflows `BigDecimal`'s `Int` scale
  (`1e9999999999`) survives in circe's AST but has no counterpart in the model's `Num(BigDecimal)`.
  Ordinary numbers — including the `1e2147483648` boundary — convert fine.
- **`toCirceJson`**, for an object with duplicate keys. `io.circe.Json.fromFields` would keep the last
  value at the first key's position, dropping a field *and* reordering. The model rejects duplicates
  at its own entry points, so this only fires on a `Json.Obj` built through the bare enum case.

## Error semantics

- **`decode` returns `Either[String, A]`**, carrying circe's `DecodingFailure` message including its
  cursor path (`DecodingFailure at .tags: Missing required field`) — a body that does not match the
  codec is an ordinary failure, not an exception.
- **`encode` has no encoder-driven failure**: circe's `Encoder[A].apply` is total, so unlike the
  zio-json side-car there is no malformed-output path. It can still surface `toRiftJson`'s
  unrepresentable-number defect if a hand-written encoder emits one; derived codecs never do.

## Testing

Tests use **munit** + munit-scalacheck, not cats-effect's test kit — this artifact must be usable
without an effect system, so its own tests never import one. The suite mirrors `ZioJsonBodySpec` so
the two side-cars stay behaviourally identical on the shared `JsonBody` seam.
