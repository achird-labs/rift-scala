package rift.model

import rift.json.{Json, JsonError}
import JsonSupport.*

final case class TlsMaterial(certPem: String, keyPem: String):
  def toJson: Json = Json.obj("cert" -> Json.Str(certPem), "key" -> Json.Str(keyPem))

object TlsMaterial:
  def fromJson(json: Json): Either[JsonError.Decode, TlsMaterial] =
    for
      fields <- asObj(json, "tls")
      cert <- reqString(fields, "cert")
      key <- reqString(fields, "key")
    yield TlsMaterial(cert, key)
