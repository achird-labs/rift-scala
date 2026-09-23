package rift.model

import rift.json.{Json, JsonError}
import rift.json.JsonError.under
import JsonSupport.*

/** An HTTPS imposter's client-certificate settings (engine 0.18.0, rift#977): `mutualAuth`,
  * `rejectUnauthorized` and `ca`, flat keys on the imposter.
  *
  *   - `mutualAuth: true` requires a client certificate; a client presenting none fails the
  *     handshake. Without `rejectUnauthorized` the chain is not validated (the signature still is).
  *   - `rejectUnauthorized: true` with `ca` validates the chain against those anchors.
  *
  * The engine refuses at create every combination that cannot take effect: `rejectUnauthorized` or
  * `ca` without `mutualAuth`; `ca` without `rejectUnauthorized`; `rejectUnauthorized` without `ca`
  * (including `ca: []`); a `ca` holding no certificate; and `mutualAuth: true` on http. Engines
  * before 0.18.0 dropped the keys and accepted every client, which is why rift-java refuses them
  * there. This type validates nothing, so any engine output decodes;
  * `rift.dsl.ImposterBuilder.requireClientCertificate` builds only the two accepted states.
  */
final case class ClientAuth(
    mutualAuth: Option[Boolean] = None,
    rejectUnauthorized: Option[Boolean] = None,
    ca: Option[CaCertificates] = None
):
  def isEmpty: Boolean = mutualAuth.isEmpty && rejectUnauthorized.isEmpty && ca.isEmpty

  private[model] def fields: Vector[(String, Json)] =
    Vector(
      mutualAuth.map(b => "mutualAuth" -> Json.Bool(b)),
      rejectUnauthorized.map(b => "rejectUnauthorized" -> Json.Bool(b)),
      ca.map(c => "ca" -> c.toJson)
    ).flatten

object ClientAuth:
  val none: ClientAuth = ClientAuth()

  private[model] val keys: Set[String] = Set("mutualAuth", "rejectUnauthorized", "ca")

  private[model] def fromFields(
      fields: Vector[(String, Json)]
  ): Either[JsonError.Decode, ClientAuth] =
    for
      mutualAuth <- optBoolean(fields, "mutualAuth")
      reject <- optBoolean(fields, "rejectUnauthorized")
      ca <- fields.field("ca") match
        case Some(c) => CaCertificates.fromJson(c).map(Some(_)).left.map(_.under("ca"))
        case None => Right(None)
    yield ClientAuth(mutualAuth, reject, ca)

  /** A present boolean, kept distinct from absent so `"mutualAuth": false` round-trips. */
  private def optBoolean(
      fields: Vector[(String, Json)],
      key: String
  ): Either[JsonError.Decode, Option[Boolean]] = fields.field(key) match
    case Some(Json.Bool(b)) => Right(Some(b))
    case Some(_) => Left(JsonError.Decode("expected a boolean", Vector.empty).under(key))
    case None => Right(None)

/** The `ca` trust anchors, keeping the engine's two spellings: one PEM string, or an array. */
enum CaCertificates:
  case Single(pem: String)
  case Many(pems: Vector[String])

  def anchors: Vector[String] = this match
    case Single(pem) => Vector(pem)
    case Many(pems) => pems

  def toJson: Json = this match
    case Single(pem) => Json.Str(pem)
    case Many(pems) => Json.Arr(pems.map(Json.Str(_)))

object CaCertificates:
  def fromJson(json: Json): Either[JsonError.Decode, CaCertificates] = json match
    case Json.Str(pem) => Right(Single(pem))
    case arr: Json.Arr =>
      decodeArray(
        arr,
        {
          case Json.Str(pem) => Right(pem)
          case _ => Left(JsonError.Decode("expected a PEM string", Vector.empty))
        }
      ).map(Many(_))
    case _ => Left(JsonError.Decode("expected a PEM string or an array of them", Vector.empty))
