package rift.conformance

import rift.json.Json

/** One entry from `corpus/manifest.json` (the corpus's own README.md is the normative replay
  * contract). `requires` is a subset of `injection|proxy|redis|https|shell` — the closed set of
  * capability gates an SDK lane may use to skip a fixture, never for any other reason.
  */
final case class Fixture(
    file: String,
    port: Int,
    name: String,
    requires: Set[String],
    hasVerify: Boolean
):
  /** The manifest's paths are `corpus/imposters/NN-name.json`, relative to the vendored `corpus/`
    * root; this module's classpath resource root is that same `corpus/` directory (the default
    * `src/test/resources/corpus/` layout — no extra `unmanagedResourceDirectories` wiring needed),
    * so the leading `corpus/` is stripped before resolving as `/corpus/<rest>`.
    */
  def resourcePath: String = file.stripPrefix("corpus/")

  /** `corpus/imposters/01-basic-rest.json` -> `01-basic-rest` — the stable per-fixture identity G2
    * uses to key its expressible/not-expressible split.
    */
  def id: String = file.split('/').last.stripSuffix(".json")

final case class Manifest(schemaVersion: Int, engineVersion: String, fixtures: Vector[Fixture])

/** Loads the vendored conformance corpus (`conformance/src/test/resources/corpus/`) from the
  * classpath. A missing or malformed corpus is a load-time defect, not a test outcome to swallow —
  * `sys.error` here fails every spec loudly rather than silently producing zero fixtures (see
  * `corpus/PROVENANCE.md` for how to (re)vendor it).
  */
object Corpus:

  private def resourceText(path: String): String =
    val in = getClass.getResourceAsStream(s"/corpus/$path")
    if in == null then
      sys.error(
        s"conformance corpus resource missing: corpus/$path " +
          "— see conformance/src/test/resources/corpus/PROVENANCE.md to (re)vendor it"
      )
    else
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally in.close()

  private def parseOrDie(text: String, what: String): Json =
    Json.parse(text).fold(err => sys.error(s"$what: $err"), identity)

  private def obj(json: Json, what: String): Vector[(String, Json)] =
    json.asObject.getOrElse(sys.error(s"$what: expected a JSON object"))

  private def str(fields: Vector[(String, Json)], key: String): String =
    fields
      .collectFirst { case (k, Json.Str(v)) if k == key => v }
      .getOrElse(sys.error(s"manifest.json: missing/invalid string field '$key'"))

  private def int(fields: Vector[(String, Json)], key: String): Int =
    fields
      .collectFirst { case (k, Json.Num(n)) if k == key => n.toInt }
      .getOrElse(sys.error(s"manifest.json: missing/invalid int field '$key'"))

  // Strict-when-present, lenient-on-absent: a genuinely absent field is a domain default
  // (`hasVerify` false, `requires` empty), but a *present* field of the wrong shape is a malformed
  // manifest and must fail loudly rather than silently collapse to that default — a silent default
  // here would drop a fixture from G3's `hasVerify` replay set (or misread its capability gates)
  // with nothing to correlate. `str`/`int` above are already strict; these match them.
  private def bool(fields: Vector[(String, Json)], key: String): Boolean =
    fields.collectFirst { case (k, v) if k == key => v } match
      case None => false
      case Some(Json.Bool(b)) => b
      case Some(other) => sys.error(s"manifest.json: field '$key' must be a boolean, got $other")

  private def stringSet(fields: Vector[(String, Json)], key: String): Set[String] =
    fields.collectFirst { case (k, v) if k == key => v } match
      case None => Set.empty
      case Some(Json.Arr(items)) =>
        // Deliberately NOT validated against the closed capability vocabulary
        // (`injection|proxy|redis|https|shell`): a future engine may add a capability this SDK
        // doesn't know, and an unknown one must skip the fixture (`requires -- supportedCapabilities`
        // in G3), never hard-fail — that keeps the append-only corpus refresh forward-compatible.
        items.map {
          case Json.Str(s) => s
          case other => sys.error(s"manifest.json: '$key' entries must be strings, got $other")
        }.toSet
      case Some(other) => sys.error(s"manifest.json: field '$key' must be an array, got $other")

  private def decodeFixture(json: Json): Fixture =
    val fields = obj(json, "manifest.json fixture entry")
    Fixture(
      file = str(fields, "file"),
      port = int(fields, "port"),
      name = str(fields, "name"),
      requires = stringSet(fields, "requires"),
      hasVerify = bool(fields, "hasVerify")
    )

  lazy val manifest: Manifest =
    val fields = obj(parseOrDie(resourceText("manifest.json"), "manifest.json"), "manifest.json")
    val fixturesJson = fields
      .collectFirst { case ("fixtures", Json.Arr(items)) => items }
      .getOrElse(sys.error("manifest.json: missing 'fixtures' array"))
    Manifest(
      schemaVersion = int(fields, "schemaVersion"),
      engineVersion = str(fields, "engineVersion"),
      fixtures = fixturesJson.map(decodeFixture)
    )

  def fixtures: Vector[Fixture] = manifest.fixtures

  def imposterRaw(fixture: Fixture): String = resourceText(fixture.resourcePath)

  def imposterJson(fixture: Fixture): Json =
    parseOrDie(imposterRaw(fixture), s"corpus/${fixture.resourcePath}")
