package rift.json.ziojson

// The package is `rift.json.ziojson`, not `rift.json.zio`: a segment spelled `zio` would shadow the
// ZIO root package for anyone wildcard-importing this one's parent (#24).
import zio.Chunk
import zio.json.JsonCodec
import zio.json.ast.Json as ZJson
import rift.json.Json
import rift.model.JsonBody

/** Bridges zio-json to the model's [[rift.model.JsonBody]] typeclass (DESIGN.md §5.5, D7).
  *
  * `rift-scala-zio` deliberately depends on zio + zio-streams only, so this side-car is what lets a
  * ZIO user write `ok.json(User(1, "Alice"))` and `recorded.bodyAs[User]` with the `JsonCodec`s
  * they already have. It depends on `model` + zio-json and exposes no effect type, so `pure`,
  * `cats` and `kyo` code can use it just as well.
  *
  * {{{
  * import rift.dsl.*
  * import rift.json.ziojson.given
  * import zio.json.{DeriveJsonCodec, JsonCodec}
  *
  * case class User(id: Int, name: String) derives JsonCodec
  *
  * ok.json(User(1, "Alice"))
  * }}}
  *
  * Importing this given also routes the primitives `model` instantiates itself (`String`, `Int`,
  * `Boolean`, …) through zio-json, because a given imported into lexical scope outranks one found
  * in a companion. The encoding is identical either way; only the `decode` failure messages differ.
  */
given jsonBody[A](using codec: JsonCodec[A]): JsonBody[A] with

  /** @throws IllegalArgumentException
    *   if the codec cannot render `a` as JSON at all. That is a defect in the encoder rather than a
    *   recoverable outcome, so it is thrown rather than returned — matching `ok.json(raw)`, which
    *   throws the same way on a malformed literal. A derived codec does not do this.
    */
  def encode(a: A): Json =
    codec.encoder.toJsonAST(a) match
      case Right(ast) => ast.toRiftJson
      case Left(err) => throw new IllegalArgumentException(err)

  def decode(json: Json): Either[String, A] =
    codec.decoder.fromJsonAST(json.toZioJson)

extension (self: Json)
  /** Converts the model AST to zio-json's. Object field order is preserved. */
  def toZioJson: ZJson = self match
    case Json.Null => ZJson.Null
    case Json.Bool(b) => ZJson.Bool(b)
    case Json.Str(s) => ZJson.Str(s)
    case Json.Num(n) => ZJson.Num(n)
    case Json.Arr(items) => ZJson.Arr(Chunk.fromIterable(items.map(_.toZioJson)))
    case Json.Obj(fields) => ZJson.Obj(Chunk.fromIterable(fields.map((k, v) => k -> v.toZioJson)))

extension (self: ZJson)
  /** Converts zio-json's AST to the model's. Object field order is preserved.
    *
    * @throws IllegalArgumentException
    *   if an object carries duplicate keys. zio-json keeps every occurrence, but the model AST
    *   rejects duplicates at both of its own entry points (`Json.obj` and `Json.parse`) because no
    *   wire consumer agrees on what they mean — so there is no legal model value to convert to.
    */
  def toRiftJson: Json = self match
    case ZJson.Null => Json.Null
    case ZJson.Bool(b) => Json.Bool(b)
    case ZJson.Str(s) => Json.Str(s)
    // `exact` so a value that overflows the default MathContext keeps its precision.
    case ZJson.Num(n) => Json.Num(BigDecimal.exact(n))
    case ZJson.Arr(items) => Json.Arr(items.map(_.toRiftJson).toVector)
    // Via `Json.obj`, not the bare case, so the duplicate-key check stays in one place.
    case ZJson.Obj(fields) => Json.obj(fields.map((k, v) => k -> v.toRiftJson).toSeq*)
