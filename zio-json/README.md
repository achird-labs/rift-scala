# rift-scala-zio-json

A **codec side-car**: bridges your zio-json `JsonCodec`s to the model's `JsonBody[A]` typeclass, so
request/response bodies can be typed values instead of JSON strings.

```scala
libraryDependencies += "io.github.etacassiopeia" %% "rift-scala-zio-json" % riftScalaV
```

## Why it is a separate artifact

Decision **D7** (DESIGN.md §4): no backend forces a JSON library on users. `rift-scala-zio` depends
on zio + zio-streams and nothing else, so a ZIO user who already has zio-json opts in by adding this
artifact — and a user who prefers circe adds [`rift-scala-circe`](../circe) instead, on the same
`JsonBody[A]` seam.

It depends on `model` + zio-json only, and **exposes no effect type**. Despite the name, it is not
ZIO-specific: `pure`, `cats` and `kyo` code can use it just as well — the "zio" in the name is the
codec library, not the effect system.

## Usage

```scala
import rift.dsl.*
import rift.json.ziojson.given
import zio.json.{DeriveJsonCodec, JsonCodec}

case class User(id: Int, name: String)
object User:
  given JsonCodec[User] = DeriveJsonCodec.gen[User]

// typed response body
ok.json(User(1, "Alice"))

// typed request matching
on(POST, "/users").where(body.deepEquals(User(2, "Bob"))).reply(ok)
```

`recorded.bodyAs[User]` decodes the same way once a backend surfaces recorded traffic.

### One import gotcha

**`import rift.json.ziojson.given` also re-routes the primitives.** A given imported into lexical
scope outranks one found in a companion, so `JsonBody[String]`, `JsonBody[Int]` and friends resolve
to zio-json's codecs rather than the instances `model` ships. They encode identically — only the
`decode` failure messages change (zio-json's `(expected string)` instead of model's
`expected a JSON string, got: 1`). Import `rift.json.ziojson.jsonBody` alone if you would rather keep
the model's messages for primitives.

> **Why `ziojson`, not `zio`?** A package segment spelled `zio` would shadow the ZIO root package for
> anyone writing `import rift.json.*` (which is a natural line — that package holds the `Json` AST),
> and the resulting error never hints at the cause. `rift.json.ziojson` clashes with nothing, and
> follows sttp-client's `sttp.client4.ziojson`. See [#24](https://github.com/EtaCassiopeia/rift-scala/issues/24).

## AST converters

`Json <-> zio.json.ast.Json`, for dropping down to the AST when a codec is not what you want:

```scala
import rift.json.ziojson.*

val zjson = riftJson.toZioJson
val back  = zjson.toRiftJson
```

Both directions preserve object field order and `BigDecimal` precision (including scale) — the
model's AST is the wire format, so reshuffling or rounding it would change what the engine receives.

`toRiftJson` throws `IllegalArgumentException` on an object with **duplicate keys**. zio-json keeps
every occurrence, but the model AST rejects duplicates at both of its own entry points (`Json.obj`
and `Json.parse`) because no wire consumer agrees on what they mean — so there is no legal model
value to convert to. Derived codecs never produce them.

## Error semantics

- **`decode` returns `Either[String, A]`** — a body that does not match the codec is an ordinary
  failure, not an exception.
- **`encode` throws `IllegalArgumentException`** if the codec cannot render the value as JSON at
  all. That is a defect in the encoder rather than a recoverable outcome, and it matches
  `ok.json(raw)`, which throws the same way on a malformed literal. A derived codec does not do
  this.

## Testing

Tests use **munit** + munit-scalacheck, not zio-test — the same rule as `model`: this artifact must
be usable without an effect system, so its own tests never import one. The round-trip properties
cover derived case-class and enum codecs, and the AST conversion is property-tested for identity.
