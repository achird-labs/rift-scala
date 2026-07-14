package rift.json.circe

import io.circe.{Decoder, Encoder, Json as CJson, JsonNumber}
import rift.json.Json
import rift.model.JsonBody

/** Bridges circe to the model's [[rift.model.JsonBody]] typeclass (DESIGN.md §5.9, D7).
  *
  * `rift-scala-cats` stays cats-effect-only, so this side-car is what lets a Cats user write
  * `ok.json(User(1, "Alice"))` and `recorded.bodyAs[User]` with the `Encoder`/`Decoder`s they
  * already have. It depends on `model` + circe-core and exposes no effect type, so ZIO, Kyo and
  * `pure` code can use it just as well.
  *
  * {{{
  * import rift.dsl.*
  * import rift.json.circe.given
  * import io.circe.Codec
  *
  * case class User(id: Int, name: String) derives Codec.AsObject
  *
  * ok.json(User(1, "Alice"))
  * }}}
  *
  * Importing this given also routes the primitives `model` instantiates itself (`String`, `Int`,
  * `Boolean`, …) through circe, because a given imported into lexical scope outranks one found in a
  * companion. The encoding is identical either way; only the `decode` failure messages differ.
  */
given jsonBody[A](using encoder: Encoder[A], decoder: Decoder[A]): JsonBody[A] with
  /** @throws IllegalArgumentException
    *   only by way of [[toRiftJson]] — circe's `Encoder[A].apply` itself cannot fail, so a derived
    *   codec never hits this. A hand-written encoder that emits an unrepresentable number can.
    */
  def encode(a: A): Json = encoder(a).toRiftJson

  def decode(json: Json): Either[String, A] =
    // `getMessage` already carries circe's cursor path ("DecodingFailure at .tags: ..."); `.show`
    // would mean depending on cats-core, which this side-car deliberately does not.
    decoder.decodeJson(json.toCirceJson).left.map(_.getMessage)

extension (self: Json)
  /** Converts the model AST to circe's. Object field order is preserved.
    *
    * @throws IllegalArgumentException
    *   if an object carries duplicate keys. `CJson.fromFields` would silently keep the last value
    *   at the first key's position — dropping a field *and* reordering. The model rejects
    *   duplicates at its own entry points, so this only fires on an `Obj` built through the bare
    *   enum case.
    */
  def toCirceJson: CJson = self match
    case Json.Null => CJson.Null
    case Json.Bool(b) => CJson.fromBoolean(b)
    case Json.Str(s) => CJson.fromString(s)
    case Json.Num(n) => CJson.fromBigDecimal(n)
    case Json.Arr(items) => CJson.fromValues(items.map(_.toCirceJson))
    case Json.Obj(fields) =>
      val dupes = fields.map(_._1).groupBy(identity).collect { case (k, ks) if ks.size > 1 => k }
      require(dupes.isEmpty, s"duplicate JSON object keys: ${dupes.mkString(", ")}")
      CJson.fromFields(fields.map((k, v) => k -> v.toCirceJson))

extension (self: CJson)
  /** Converts circe's AST to the model's. Object field order is preserved.
    *
    * @throws IllegalArgumentException
    *   if a number has no `BigDecimal` representation. circe keeps a parsed number lazily as its
    *   source text, so a literal whose exponent overflows `BigDecimal`'s `Int` scale survives in
    *   circe's AST but has no counterpart in the model's `Num(BigDecimal)`.
    */
  def toRiftJson: Json =
    self.fold(
      Json.Null,
      b => Json.Bool(b),
      n => Json.Num(bigDecimalOrThrow(n)),
      s => Json.Str(s),
      items => Json.Arr(items.map(_.toRiftJson)),
      // Via `Json.obj`, not the bare case, so the model's duplicate-key check stays in one place.
      // circe collapses duplicates itself, so this cannot fire today — it is here so both codec
      // side-cars enforce the model's invariant the same way rather than relying on circe's.
      obj => Json.obj(obj.toVector.map((k, v) => k -> v.toRiftJson)*)
    )

/** circe hands back a `BigDecimal` carrying the *default* `MathContext` (34 digits), which would
  * silently round a longer value on the user's first arithmetic op. `exact` re-widens the context
  * to the value's own precision — the same thing the zio-json side-car does, so both agree.
  */
private def bigDecimalOrThrow(n: JsonNumber): BigDecimal =
  n.toBigDecimal
    .map(bd => BigDecimal.exact(bd.underlying))
    .getOrElse(
      throw new IllegalArgumentException(s"JSON number has no BigDecimal representation: $n")
    )
