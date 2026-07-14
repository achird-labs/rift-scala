package rift.json

import scala.annotation.tailrec

/** Recursive-descent JSON parser (RFC 8259). Zero dependencies (D1).
  *
  * Nesting is capped: the parser recurses per level, so an adversarial document of deeply nested
  * arrays would otherwise exhaust the stack. Exceeding the cap is a normal parse failure, never a
  * `StackOverflowError`.
  */
private[json] object JsonParser:

  private final val MaxDepth = 512

  def parse(text: String): Either[JsonError.Parse, Json] =
    val p = new Parser(text)
    try
      p.skipWhitespace()
      val value = p.parseValue(0)
      p.skipWhitespace()
      if !p.atEnd then Left(JsonError.Parse(s"trailing content after the top-level value", p.pos))
      else Right(value)
    catch case e: ParseFailure => Left(JsonError.Parse(e.msg, e.offset))

  private final class ParseFailure(val msg: String, val offset: Int)
      extends RuntimeException(msg, null, false, false)

  private final class Parser(private val s: String):
    var pos: Int = 0

    def atEnd: Boolean = pos >= s.length

    private def fail(msg: String): Nothing = throw new ParseFailure(msg, pos)

    private def peek: Char = if atEnd then fail("unexpected end of input") else s.charAt(pos)

    private def expect(c: Char): Unit =
      if atEnd || s.charAt(pos) != c then fail(s"expected '$c'") else pos += 1

    @tailrec def skipWhitespace(): Unit =
      if !atEnd then
        s.charAt(pos) match
          case ' ' | '\t' | '\n' | '\r' => pos += 1; skipWhitespace()
          case _ => ()

    def parseValue(depth: Int): Json =
      if depth > MaxDepth then fail(s"nesting deeper than $MaxDepth levels")
      peek match
        case '{' => parseObject(depth)
        case '[' => parseArray(depth)
        case '"' => Json.Str(parseString())
        case 't' => keyword("true", Json.Bool(true))
        case 'f' => keyword("false", Json.Bool(false))
        case 'n' => keyword("null", Json.Null)
        case c if c == '-' || c.isDigit => parseNumber()
        case c => fail(s"unexpected character '$c'")

    private def keyword(word: String, value: Json): Json =
      if s.startsWith(word, pos) then
        pos += word.length
        value
      else fail(s"invalid literal, expected '$word'")

    private def parseObject(depth: Int): Json =
      expect('{')
      skipWhitespace()
      val fields = Vector.newBuilder[(String, Json)]
      val seen = scala.collection.mutable.HashSet.empty[String]
      if peek == '}' then
        pos += 1
        Json.Obj(Vector.empty)
      else
        var done = false
        while !done do
          skipWhitespace()
          if peek != '"' then fail("expected a string key")
          val keyStart = pos
          val key = parseString()
          if !seen.add(key) then
            pos = keyStart
            fail(s"duplicate object key '$key'")
          skipWhitespace()
          expect(':')
          skipWhitespace()
          fields += (key -> parseValue(depth + 1))
          skipWhitespace()
          peek match
            case ',' => pos += 1
            case '}' => pos += 1; done = true
            case c => fail(s"expected ',' or '}' but found '$c'")
        Json.Obj(fields.result())

    private def parseArray(depth: Int): Json =
      expect('[')
      skipWhitespace()
      val items = Vector.newBuilder[Json]
      if peek == ']' then
        pos += 1
        Json.Arr(Vector.empty)
      else
        var done = false
        while !done do
          skipWhitespace()
          items += parseValue(depth + 1)
          skipWhitespace()
          peek match
            case ',' => pos += 1
            case ']' => pos += 1; done = true
            case c => fail(s"expected ',' or ']' but found '$c'")
        Json.Arr(items.result())

    private def parseString(): String =
      expect('"')
      val sb = new StringBuilder
      var done = false
      while !done do
        if atEnd then fail("unterminated string")
        s.charAt(pos) match
          case '"' => pos += 1; done = true
          case '\\' => pos += 1; sb += parseEscape()
          case c if c < 0x20 => fail(s"unescaped control character 0x${c.toInt.toHexString}")
          case c => pos += 1; sb += c
      sb.result()

    private def parseEscape(): Char =
      if atEnd then fail("unterminated escape sequence")
      val c = s.charAt(pos)
      pos += 1
      c match
        case '"' => '"'
        case '\\' => '\\'
        case '/' => '/'
        case 'b' => '\b'
        case 'f' => '\f'
        case 'n' => '\n'
        case 'r' => '\r'
        case 't' => '\t'
        case 'u' => parseUnicode()
        case other => fail(s"invalid escape '\\$other'")

    /** Returns the code unit; surrogate pairs arrive as two `\\u` escapes and are appended
      * individually, which reconstitutes the original char sequence exactly.
      */
    private def parseUnicode(): Char =
      if pos + 4 > s.length then fail("truncated \\u escape")
      val hex = s.substring(pos, pos + 4)
      if !hex.forall(h => h.isDigit || ('a' to 'f').contains(h.toLower)) then
        fail(s"invalid \\u escape '$hex'")
      pos += 4
      Integer.parseInt(hex, 16).toChar

    private def parseNumber(): Json =
      val start = pos
      if !atEnd && s.charAt(pos) == '-' then pos += 1
      // JSON forbids leading zeros: "01" is two tokens, not one number.
      if atEnd then fail("expected a digit")
      if s.charAt(pos) == '0' then pos += 1
      else if s.charAt(pos).isDigit then while !atEnd && s.charAt(pos).isDigit do pos += 1
      else fail("expected a digit")
      if !atEnd && s.charAt(pos) == '.' then
        pos += 1
        if atEnd || !s.charAt(pos).isDigit then fail("expected a digit after '.'")
        while !atEnd && s.charAt(pos).isDigit do pos += 1
      if !atEnd && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E') then
        pos += 1
        if !atEnd && (s.charAt(pos) == '+' || s.charAt(pos) == '-') then pos += 1
        if atEnd || !s.charAt(pos).isDigit then fail("expected a digit in the exponent")
        while !atEnd && s.charAt(pos).isDigit do pos += 1
      val text = s.substring(start, pos)
      // A number immediately followed by a digit means a leading zero slipped through ("01").
      if !atEnd && s.charAt(pos).isDigit then fail("number has a leading zero")
      try Json.Num(BigDecimal(text))
      catch case _: NumberFormatException => fail(s"invalid number '$text'")
