package rift.json

/** Failures from the JSON layer. `Parse` is a syntax failure; `Decode` is a well-formed document
  * that does not match a wire type.
  */
enum JsonError:
  case Parse(message: String, offset: Int)
  case Decode(message: String, path: Vector[String])

  override def toString: String = this match
    case Parse(m, o) => s"JSON parse error at offset $o: $m"
    case Decode(m, p) =>
      s"JSON decode error at ${if p.isEmpty then "<root>" else p.mkString(".")}: $m"

object JsonError:
  extension (self: Decode)
    /** Prefixes the path so nested decoders report where the failure actually happened. */
    def under(field: String): Decode = Decode(self.message, field +: self.path)
