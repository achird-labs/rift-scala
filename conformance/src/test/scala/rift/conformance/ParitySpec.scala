package rift.conformance

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import munit.FunSuite

/** Backend support marker for one row x column cell of the generated parity table
  * (`docs/PARITY.md`). `Yes`/`No` are real answers for zio/cats/pure — the three backends
  * `ParitySpec` reflects on. `ViaCats` marks a cell reachable through fs2 only because the fs2
  * module extends/reuses the cats module's types directly (`rift.fs2.syntax`'s extension methods
  * are defined on `rift.cats.ImposterHandle[F]` itself — there is no separate fs2 handle type), not
  * because fs2 declares the operation. `Parked` is kyo's fixed value on every row (issue #11 — no
  * `kyo` build exists yet, tracked separately from this table).
  */
enum Support:
  case Yes, No, ViaCats, Parked

  def cell: String = this match
    case Support.Yes => "✓"
    case Support.No => "✗"
    case Support.ViaCats => "via cats"
    case Support.Parked => "parked (#11)"

/** One row of the parity table.
  *
  * `reflectable` marks operations whose name shows up in `Class.forName("rift.zio.Rift").
  * getDeclaredMethods` (and the cats/pure equivalents) — `ParitySpec`'s anti-drift check enumerates
  * exactly that. This covers both the `Rift` trait/class's own instance methods (`create`,
  * `applyConfig`, `intercept`, ...) AND its companion object's factory methods (`embedded`,
  * `connect`, ...): javac-interop "static forwarders" put the companion object's public methods
  * onto the trait/class's own bytecode too, so they're visible via the same single `Class.forName`
  * call. Handle-level operations (`ImposterHandle`/`InterceptHandle`/... members) are declared on
  * *different* classes the check never loads, so those rows stay curated by hand, same as fs2/kyo
  * (see the reflection test's own comment for why).
  */
final case class Op(
    category: String,
    name: String,
    zio: Support,
    cats: Support,
    fs2: Support,
    pure: Support,
    reflectable: Boolean = false
)

/** The canonical rift-scala surface inventory — single source of truth for `docs/PARITY.md`. Every
  * row here is one operation from `rift.zio.Rift`/`ImposterHandle`/`InterceptHandle`,
  * `rift.cats.Rift[F]` + its handles, `rift.pure.Rift` + `Imposter`/`Intercept`, or `rift.fs2`
  * (`syntax`/`pipes`/`AwaitRequests`). Never hand-edit `docs/PARITY.md` — it is `render()`'s
  * output, verified byte-for-byte by `ParitySpec`.
  */
object Inventory:

  import Support.*

  val operations: Vector[Op] = Vector(
    // ── Provisioning / factory — companion-object connectors. Reflectable: the compiler emits
    // static forwarders for these onto the Rift trait/class's own bytecode (see `Op.reflectable`'s
    // doc), so `Class.forName("rift.zio.Rift")` etc. sees them too. ────────────────────────────
    Op(
      "Provisioning / factory",
      "embedded",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Provisioning / factory",
      "connect",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Provisioning / factory",
      "spawn",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Provisioning / factory",
      "container",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Provisioning / factory",
      "isEmbeddedAvailable",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),

    // ── Imposter CRUD — the first 6 are declared on `Rift` itself (reflectable); the rest are
    // `ImposterHandle`/`Imposter` members. ──────────────────────────────────────────────────────
    Op(
      "Imposter CRUD",
      "create",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Imposter CRUD",
      "createFromJson",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Imposter CRUD",
      "imposter",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Imposter CRUD",
      "imposters",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Imposter CRUD",
      "deleteAll",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Imposter CRUD",
      "replaceAll",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op("Imposter CRUD", "addStub", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "addStub (indexed)", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "addStubFirst", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "replaceStubs", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "stubs", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "stub", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "clearRecorded (scoped)", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "clearProxyResponses", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "delete", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "enable", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Imposter CRUD", "disable", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),

    // ── Config / info — declared on `Rift` itself, reflectable. ────────────────────────────────
    Op(
      "Config / info",
      "applyConfig",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Config / info",
      "info",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Config / info",
      "adminUri",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),

    // ── Intercept — `intercept` (the entry point) is declared on `Rift` itself, reflectable; the
    // rest are `InterceptHandle`/`Intercept`/`InterceptRuleBuilder` members. ────────────────────
    Op(
      "Intercept",
      "intercept",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op(
      "Intercept",
      "interceptAttach",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes,
      reflectable = true
    ),
    Op("Intercept", "rule", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "when", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "serve", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "forward", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "redirectTo", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "rules", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "clearRules", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "proxySelector", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "caPem", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "sslContext", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "sslContextWithSystemCAs", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "exportTruststore", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op(
      "Intercept",
      "exportTruststoreWithSystemCAs",
      zio = Yes,
      cats = Yes,
      fs2 = ViaCats,
      pure = Yes
    ),
    Op("Intercept", "caMaterial", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "address", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Intercept", "proxyUri", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),

    // ── Recording — `ImposterHandle.startRecording` + `RecordingHandle` members. ────────────────
    Op("Recording", "startRecording", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Recording", "stop", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Recording", "snapshot", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Recording", "persist", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),

    // ── Handle ops — verification + the `scenarios`/`space`/`flowState` sub-handle namespaces. ──
    Op("Handle ops", "verify", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "verifyResult", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "verifyNoInteractions", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "recorded", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "clearRecorded", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "scenarios", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "space", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),
    Op("Handle ops", "flowState", zio = Yes, cats = Yes, fs2 = ViaCats, pure = Yes),

    // ── Streaming — the cursor request tail. zio has its own `ZStream` tail on `ImposterHandle`;
    // cats exposes only the baseline `recordedPage`/`recordedSince` pagination the tail is built on
    // (no bare-cats-effect `Stream` type to build one with); fs2 builds the actual `fs2.Stream` tail
    // on top of that cats pagination; pure has no effect system to drive polling with at all. ─────
    Op("Streaming", "requests / requestStream", zio = Yes, cats = No, fs2 = Yes, pure = No),
    Op("Streaming", "requestEvents", zio = Yes, cats = No, fs2 = Yes, pure = No),
    Op("Streaming", "recordedPage", zio = No, cats = Yes, fs2 = ViaCats, pure = No),
    Op("Streaming", "recordedSince", zio = No, cats = Yes, fs2 = ViaCats, pure = No),
    Op("Streaming", "pipes.matching", zio = No, cats = No, fs2 = Yes, pure = No),
    Op("Streaming", "pipes.received", zio = No, cats = No, fs2 = Yes, pure = No),
    Op("Streaming", "awaitRequests", zio = No, cats = No, fs2 = Yes, pure = No),

    // ── The same cursor tail, scoped to one flow space (#129). Availability mirrors the imposter
    // rows above exactly — zio streams, cats pages, fs2 streams over cats, pure has nothing to poll
    // with — because each is a verbatim mirror of the imposter shape. Unusable on the embedded
    // transport (it throws): see `rift.bridge.SpaceHandle`. ────────────────────────────────────
    Op(
      "Space streaming",
      "space.requests / requestStream",
      zio = Yes,
      cats = No,
      fs2 = Yes,
      pure = No
    ),
    Op("Space streaming", "space.requestEvents", zio = Yes, cats = No, fs2 = Yes, pure = No),
    Op("Space streaming", "space.recordedPage", zio = No, cats = Yes, fs2 = ViaCats, pure = No),
    Op("Space streaming", "space.recordedSince", zio = No, cats = Yes, fs2 = ViaCats, pure = No),

    // ── admin SSE event stream (issue #87) — declared directly on `Rift`/`Rift[F]` (reflectable),
    // but under two DIFFERENT names: zio/pure share the literal name `events`, cats names its
    // Resource-returning member `eventSource` (the cats module has no fs2 dependency, so it hands
    // back the bridge `EventSource` rather than a `Stream`). Two rows, not one, so each backend's
    // reflection check requires exactly the name it actually declares. fs2's own `events` extension
    // (built on cats' `eventSource`) isn't reflectable — no `rift.fs2.*` class is ever loaded here.
    Op("Streaming", "events", zio = Yes, cats = No, fs2 = Yes, pure = Yes, reflectable = true),
    Op("Streaming", "eventSource", zio = No, cats = Yes, fs2 = No, pure = No, reflectable = true)
  )

  /** Category display order — first-seen order in `operations`, so adding a new category only needs
    * a new group of rows above, never a separate ordering list to keep in sync.
    */
  private def categories: Vector[String] = operations.map(_.category).distinct

  private val header =
    """<!-- GENERATED FILE — do not hand-edit. See the note below for how to regenerate. -->
      |
      |# rift-scala surface parity
      |
      |Generated by [`ParitySpec`](https://github.com/achird-labs/rift-scala/blob/master/conformance/src/test/scala/rift/conformance/ParitySpec.scala)
      |from the `Inventory` object defined there — the op inventory is the single source of truth;
      |this file is its rendered output and must never be hand-edited. `ParitySpec`'s golden-drift
      |guard fails CI if this file no longer matches a fresh render, and its reflection-completeness
      |check fails if a `Rift` surface method is added or removed without updating `Inventory` to
      |match.
      |
      |The table tracks the primary cross-backend operations — it is representative, not an
      |exhaustive method list. Trivially-uniform accessors (`port`/`uri`/`definition`) and overloads
      |present identically on every surface are omitted; their absence never misrepresents parity.
      |
      |To regenerate after changing `Inventory`:
      |
      |```
      |sbt -Drift.parity.regen=true "conformance/testOnly rift.conformance.ParitySpec"
      |```
      |
      |then re-run `sbt "conformance/testOnly rift.conformance.ParitySpec"` (without the flag) to
      |confirm the golden check passes.
      |
      |Legend: `✓` supported · `✗` not supported · `via cats` reachable only because the module
      |extends/reuses the cats surface's types directly · `parked (#11)` no `kyo` build exists yet.
      |""".stripMargin

  private def renderSection(category: String): String =
    val rows = operations.filter(_.category == category)
    val body = rows
      .map { op =>
        s"| `${op.name}` | ${op.zio.cell} | ${op.cats.cell} | ${op.fs2.cell} | ${op.pure.cell} | " +
          s"${Support.Parked.cell} |"
      }
      .mkString("\n")
    s"### $category\n\n| Operation | zio | cats | fs2 | pure | kyo |\n|---|---|---|---|---|---|\n$body\n"

  /** Renders the full markdown document. Deterministic and idempotent: calling this twice (or
    * writing it and reading it back) yields byte-identical strings — `ParitySpec`'s golden check
    * depends on that.
    */
  def render(): String =
    header + "\n" + categories.map(renderSection).mkString("\n")

/** Two anti-drift checks for the four-and-a-half-backend rift-scala surface (issue #13):
  *
  *   - a golden guard that `docs/PARITY.md` is exactly `Inventory.render()`'s output, never
  *     hand-edited (regenerate via `-Drift.parity.regen=true`, see `Inventory`'s header note);
  *   - a reflection check that the *real* declared methods of `rift.zio.Rift`/`rift.cats.Rift`/
  *     `rift.pure.Rift` equal what `Inventory` claims those backends support, for the subset of
  *     operations declared directly on those types (`Op.reflectable`) — so a surface method can't
  *     be added or removed without this test going red until `Inventory` is updated to match.
  *
  * fs2 has no `Rift` trait of its own (it extends `rift.cats.ImposterHandle[F]` via extension
  * methods, per `rift.fs2.syntax`) and kyo has no build at all (issue #11) — both are curated by
  * hand in `Inventory` rather than reflection-checked, which is why only three `Class.forName`
  * calls appear below.
  */
class ParitySpec extends FunSuite:

  // `user.dir` is the build root under sbt (which runs `conformance/test` from the repo root), so
  // this resolves to <repo>/docs/PARITY.md. It would need revisiting only if the module were ever
  // tested from its own dir or a forked JVM with a different working directory.
  private val parityMdPath = Paths.get(sys.props("user.dir"), "docs", "PARITY.md")

  test("docs/PARITY.md is exactly Inventory.render() (golden drift guard)"):
    val rendered = Inventory.render()
    if sys.props.get("rift.parity.regen").isDefined then
      Files.writeString(parityMdPath, rendered, StandardCharsets.UTF_8)
    else
      assert(
        Files.exists(parityMdPath),
        s"docs/PARITY.md not found at $parityMdPath — regenerate with " +
          "-Drift.parity.regen=true first"
      )
      val committed = Files.readString(parityMdPath, StandardCharsets.UTF_8)
      assertEquals(
        committed,
        rendered,
        "docs/PARITY.md is stale relative to Inventory — regenerate with: " +
          "sbt -Drift.parity.regen=true \"conformance/testOnly rift.conformance.ParitySpec\""
      )

  /** Declared public method names of `className`, filtered to the ones that could plausibly be a
    * modeled operation: no synthetic/compiler-generated names (default-parameter accessors like
    * `intercept$default$1` all contain `$`), no `*Unsafe` throwing sugar (pure-only, not part of
    * the cross-backend surface this table tracks), and no `close` (lifecycle, not an operation).
    * `Class.forName` (not `classOf`) because `rift.cats.Rift[F[_]]` is higher-kinded — `classOf`
    * would need a type argument that doesn't mean anything at this reflective level.
    */
  private def reflectedOperationNames(className: String): Set[String] =
    Class
      .forName(className)
      .getDeclaredMethods
      .filter(m => Modifier.isPublic(m.getModifiers))
      .map(_.getName)
      .filterNot(_.contains("$"))
      .filterNot(_.endsWith("Unsafe"))
      .filterNot(_ == "close")
      .toSet

  private def inventoryOperationNames(support: Op => Support): Set[String] =
    Inventory.operations
      .filter(op => op.reflectable && support(op) == Support.Yes)
      .map(_.name)
      .toSet

  test("reflection completeness: rift.zio.Rift matches Inventory"):
    assertEquals(reflectedOperationNames("rift.zio.Rift"), inventoryOperationNames(_.zio))

  test("reflection completeness: rift.cats.Rift matches Inventory"):
    assertEquals(reflectedOperationNames("rift.cats.Rift"), inventoryOperationNames(_.cats))

  test("reflection completeness: rift.pure.Rift matches Inventory"):
    assertEquals(reflectedOperationNames("rift.pure.Rift"), inventoryOperationNames(_.pure))
