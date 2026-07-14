package rift.json

/** A minimal JSON AST (D1): vendored so that `rift-scala-model` stays dependency-free.
  *
  * Objects keep their fields in insertion order — round-tripping a fixture must not reshuffle it —
  * and reject duplicate keys, which JSON permits but no wire consumer agrees on.
  */
enum Json:
  case Obj(fields: Vector[(String, Json)])
  case Arr(items: Vector[Json])
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null

object Json:

  def parse(text: String): Either[JsonError.Parse, Json] = JsonParser.parse(text)

  def obj(fields: (String, Json)*): Json =
    val v = fields.toVector
    val dupes = v.map(_._1).groupBy(identity).collect { case (k, ks) if ks.size > 1 => k }
    require(dupes.isEmpty, s"duplicate JSON object keys: ${dupes.mkString(", ")}")
    Obj(v)

  def arr(items: Json*): Json = Arr(items.toVector)

  def fromString(s: String): Json = Str(s)
  def fromInt(i: Int): Json = Num(BigDecimal(i))
  def fromBoolean(b: Boolean): Json = Bool(b)

  extension (self: Json)

    def render: String =
      val sb = new StringBuilder
      write(self, sb)
      sb.result()

    def renderPretty: String =
      val sb = new StringBuilder
      writePretty(self, sb, 0)
      sb.result()

    /** Key-order-insensitive, numerically tolerant equality — mirrors rift-java's
      * `JsonValue.semanticEquals`, and is the comparator for the conformance expressibility gate.
      * Arrays stay order-sensitive: response cycling and predicate order are semantic.
      */
    def semanticEquals(other: Json): Boolean = (self, other) match
      case (Obj(a), Obj(b)) =>
        a.size == b.size && {
          val bm = b.toMap
          a.forall((k, v) => bm.get(k).exists(v.semanticEquals))
        }
      case (Arr(a), Arr(b)) => a.size == b.size && a.lazyZip(b).forall(_.semanticEquals(_))
      case (Num(a), Num(b)) => a.compare(b) == 0
      case (Str(a), Str(b)) => a == b
      case (Bool(a), Bool(b)) => a == b
      case (Null, Null) => true
      case _ => false

    def get(path: String*): Option[Json] =
      path.foldLeft(Option(self)):
        case (Some(Obj(fields)), key) => fields.collectFirst { case (k, v) if k == key => v }
        case _ => None

    def asObject: Option[Vector[(String, Json)]] = self match
      case Obj(f) => Some(f)
      case _ => None

    def asArray: Option[Vector[Json]] = self match
      case Arr(i) => Some(i)
      case _ => None

    def asString: Option[String] = self match
      case Str(s) => Some(s)
      case _ => None

  private def write(json: Json, sb: StringBuilder): Unit = json match
    case Json.Null => sb ++= "null"
    case Json.Bool(b) => sb ++= (if b then "true" else "false")
    case Json.Num(n) => sb ++= renderNumber(n)
    case Json.Str(s) => writeString(s, sb)
    case Json.Arr(items) =>
      sb += '['
      var first = true
      items.foreach: i =>
        if !first then sb += ','
        first = false
        write(i, sb)
      sb += ']'
    case Json.Obj(fields) =>
      sb += '{'
      var first = true
      fields.foreach: (k, v) =>
        if !first then sb += ','
        first = false
        writeString(k, sb)
        sb += ':'
        write(v, sb)
      sb += '}'

  private def writePretty(json: Json, sb: StringBuilder, depth: Int): Unit =
    def pad(d: Int): Unit = sb ++= "  " * d
    json match
      case Json.Arr(items) if items.nonEmpty =>
        sb ++= "[\n"
        items.zipWithIndex.foreach: (item, i) =>
          pad(depth + 1)
          writePretty(item, sb, depth + 1)
          if i < items.size - 1 then sb += ','
          sb += '\n'
        pad(depth)
        sb += ']'
      case Json.Obj(fields) if fields.nonEmpty =>
        sb ++= "{\n"
        fields.zipWithIndex.foreach { case ((k, v), i) =>
          pad(depth + 1)
          writeString(k, sb)
          sb ++= ": "
          writePretty(v, sb, depth + 1)
          if i < fields.size - 1 then sb += ','
          sb += '\n'
        }
        pad(depth)
        sb += '}'
      case other => write(other, sb)

  /** Renders without an exponent where practical so fixtures compare byte-for-byte. */
  private def renderNumber(n: BigDecimal): String =
    if n.isWhole && n.abs < BigDecimal(1e18) then n.toBigInt.toString
    else n.bigDecimal.toPlainString

  private def writeString(s: String, sb: StringBuilder): Unit =
    sb += '"'
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '"' => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case '\n' => sb ++= "\\n"
        case '\r' => sb ++= "\\r"
        case '\t' => sb ++= "\\t"
        case '\b' => sb ++= "\\b"
        case '\f' => sb ++= "\\f"
        case c if c < 0x20 => sb ++= f"\\u${c.toInt}%04x"
        case c => sb += c
      i += 1
    sb += '"'
