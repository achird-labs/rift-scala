package rift.conformance

import java.lang.reflect.{Method, Modifier}
import java.nio.file.Paths
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*

import munit.FunSuite

import io.github.achirdlabs.rift.Rift as JRift

/** CI-safe facade-diff parity gate over rift-java-core's public API (issue #98, split out of #86).
  *
  * `ParitySpec` (the neighbour in this module) is a *self*-inventory: it checks rift-scala's own
  * `Inventory` against `docs/PARITY.md` and against the three `Rift` backends' reflected methods.
  * Nothing in that spec ever loads rift-java-core itself, so a facade capability rift-scala never
  * wrapped is invisible to CI — that is exactly how #80/#81/#86's rows shipped as silent gaps. This
  * spec closes that hole: it enumerates the facade jar directly and fails the build on any
  * capability that `FacadeCoverage.entries` neither `Wrapped`s nor `Excluded`s with a stated
  * reason.
  *
  * '''Enumeration scope''': every top-level class (nested classes included, e.g.
  * `InterceptOptions$Builder`, `Intercept$CaMaterial`, `MatchClause$Header`) directly under
  * `io.github.achirdlabs.rift` — i.e. jar entries matching
  * `io/github/achirdlabs/rift/[^/]+\.class`. Subpackages (`json`, `dsl`, `model`, `error`,
  * `verify`, `spawn`, `transport`, `codec`) are excluded by design, but the reasons differ and only
  * some of them are "already covered elsewhere":
  *   - `json`, `model`, `error` — genuinely covered: everything crosses via the D2 raw-JSON seam
  *     (`FacadeEncode`/`FacadeDecode`) and `RiftError.fromThrowable`'s total mapping, both gated by
  *     the existing translation and conformance suites.
  *   - `dsl`, `verify` — NOT covered by that argument. `FacadeEncode.isSpec` is an explicitly typed
  *     value translation (the facade has no raw-JSON overload there) and its own scaladoc records a
  *     residual it cannot express. These are a deliberate scope boundary for the first cut of this
  *     gate, not a claim of coverage — widening the regex to them is tracked by #130.
  *   - `transport`, `spawn`, `codec` — facade-internal plumbing rift-scala never calls directly.
  *
  * '''Capability keys''' are full erased signatures, `SimpleClassName#method(ParamSimpleName,...)`
  * — not bare names — because the CA rows in #86 were *overload* gaps on an already-wrapped method
  * name (`ca(Path,Path)` vs `ca(byte[],byte[])` vs `ca(KeyStore,char[])`); a name-level key would
  * have missed them.
  *
  * '''Method filter''' (per class survivors of the class filter below): public, non-synthetic,
  * non-bridge declared methods only. Skipped: constructors (facade capabilities enter via static
  * factories, never `new`), record/object boilerplate (`toString`/`hashCode`/`equals`), enum
  * machinery (`values`/`valueOf`), and anything whose name contains `$` (lambdas, compiler
  * forwarders — default-parameter accessors and the like).
  *
  * '''Class filter''': public classes only, and never a class whose simple name ends in `Impl` —
  * the facade's internal implementations (`RiftImpl`, `ImposterImpl`, `InterceptTrustImpl`, ...)
  * are reachable only through their public interface, so their methods would otherwise double-count
  * the same capability under two keys.
  */
class FacadeParitySpec extends FunSuite:

  private def paramTypeName(p: Class[?]): String =
    if p.isArray then paramTypeName(p.getComponentType) + "[]" else p.getSimpleName

  private def isCapabilityMethod(m: Method): Boolean =
    Modifier.isPublic(m.getModifiers)
      && !m.isSynthetic
      && !m.isBridge
      && !m.getName.contains("$")
      && !Set("toString", "hashCode", "equals", "values", "valueOf").contains(m.getName)

  /** `Enclosing.Nested` rather than the bare simple name: the facade has three distinct nested
    * `Builder` types (`InterceptOptions`, `SpawnOptions`, `EventStreamOptions`), and
    * `getSimpleName` renders all of them `Builder`. Collapsing them would let coverage of one class
    * silently satisfy the key for another — the exact blind spot this gate exists to close.
    */
  private def capabilityOwner(cls: Class[?]): String =
    Option(cls.getEnclosingClass) match
      case Some(outer) => s"${capabilityOwner(outer)}.${cls.getSimpleName}"
      case None => cls.getSimpleName

  /** Enum constants as capabilities too. `values`/`valueOf` are filtered out by name and fields are
    * never enumerated, so without this a constant added by a rift-java bump is invisible — and
    * `FacadeDecode.imposterAction` is an exhaustive match that would turn into a `MatchError`
    * defect on the event stream the moment one appeared. Exactly the #86-shaped regression this
    * gate exists to surface.
    */
  private def enumConstantCapabilities(cls: Class[?]): Vector[String] =
    Option(cls.getEnumConstants).toVector.flatten
      .map(c => s"${capabilityOwner(cls)}#${c.asInstanceOf[Enum[?]].name}")

  private def capabilitySignature(cls: Class[?], m: Method): String =
    s"${capabilityOwner(cls)}#${m.getName}(${m.getParameterTypes.map(paramTypeName).mkString(",")})"

  private def publicCapabilityMethods(cls: Class[?]): Vector[String] =
    cls.getDeclaredMethods.toVector
      .filter(isCapabilityMethod)
      .map(capabilitySignature(cls, _))

  /** Enumerates the facade jar's top-level public API as full-signature capability keys — see the
    * class doc for the enumeration/method/class filters. Locating the jar via the loaded `Rift`
    * class's own code source (rather than a hardcoded path) means this tracks whatever
    * rift-java-core version `project/Dependencies.scala` actually resolves, with no separate pin to
    * keep in sync.
    */
  private def facadeCapabilities: Set[String] =
    val location = classOf[JRift].getProtectionDomain.getCodeSource.getLocation
    val jarFile = new JarFile(Paths.get(location.toURI).toFile)
    try
      jarFile
        .entries()
        .asScala
        .map(_.getName)
        .filter(n => n.matches("io/github/achirdlabs/rift/[^/]+\\.class"))
        .map(n => Class.forName(n.stripSuffix(".class").replace('/', '.')))
        .filter(c => Modifier.isPublic(c.getModifiers) && !c.getSimpleName.endsWith("Impl"))
        .flatMap(c => publicCapabilityMethods(c) ++ enumConstantCapabilities(c))
        .toSet
    finally jarFile.close()

  /** Blank OR a placeholder. An `Excluded` whose reason does not actually hold is worse than an
    * unmapped capability — it hides the gap behind a green build — so "TODO" must not satisfy this.
    */
  private def isPlaceholder(reason: String): Boolean =
    val r = reason.trim
    r.isEmpty || Set("todo", "fixme", "xxx", "???").exists(r.toLowerCase.startsWith)

  private def classPart(capability: String): String =
    capability.substring(0, capability.indexOf('#'))

  /** How many `Coverage` rows in `coverage` cover `capability` — `Wrapped`/`Excluded` by exact key
    * match, `ExcludedClass` by class-part match. Gate test (a) requires this to be exactly 1 for
    * every enumerated capability: 0 is an unmapped gap, >1 is a redundant/conflicting mapping that
    * would silently mask a future stale-key removal of one of the duplicates.
    */
  private def coverageCount(capability: String, coverage: Vector[Coverage]): Int =
    coverage.count {
      case Coverage.Wrapped(facade, _) => facade == capability
      case Coverage.Excluded(facade, _) => facade == capability
      case Coverage.ExcludedClass(facadeClass, _) => facadeClass == classPart(capability)
    }

  private def unmappedCapabilities(
      capabilities: Set[String],
      coverage: Vector[Coverage]
  ): Vector[String] =
    capabilities.toVector.filter(c => coverageCount(c, coverage) == 0).sorted

  private def duplicateCoverage(
      capabilities: Set[String],
      coverage: Vector[Coverage]
  ): Vector[String] =
    capabilities.toVector.filter(c => coverageCount(c, coverage) > 1).sorted

  /** A mapping's `facade`/`facadeClass` key naming a capability the current jar no longer has — a
    * rift-java bump that removed or renamed a method must fail this exactly like a new addition
    * fails (a), not silently keep granting coverage to a key nothing enumerates anymore.
    */
  private def staleEntries(capabilities: Set[String], coverage: Vector[Coverage]): Vector[String] =
    coverage.collect {
      case Coverage.Wrapped(facade, by) if !capabilities.contains(facade) =>
        s"""Wrapped("$facade", "$by") — "$facade" no longer exists in the enumerated facade API"""
      case Coverage.Excluded(facade, reason) if !capabilities.contains(facade) =>
        s"""Excluded("$facade", "$reason") — "$facade" no longer exists in the enumerated facade API"""
      case Coverage.ExcludedClass(facadeClass, reason)
          if !capabilities.exists(classPart(_) == facadeClass) =>
        s"""ExcludedClass("$facadeClass", "$reason") — no enumerated capability is declared on """ +
          s""""$facadeClass" anymore"""
    }

  /** `by` is `fully.qualified.ClassName#methodName`; resolves when the class loads and declares a
    * method of that name (any overload — decision (c) only asks the *name* still exists, since that
    * is what proves the pointer hasn't rotted after a rename/move).
    */
  private def wrappedByResolves(by: String): Boolean =
    val hash = by.indexOf('#')
    hash > 0 && {
      val className = by.substring(0, hash)
      val methodName = by.substring(hash + 1)
      try Class.forName(className).getDeclaredMethods.exists(_.getName == methodName)
      catch case _: ClassNotFoundException => false
    }

  private def unresolvedWrapped(coverage: Vector[Coverage]): Vector[String] =
    coverage.collect {
      case Coverage.Wrapped(facade, by) if !wrappedByResolves(by) =>
        s"""Wrapped("$facade", "$by") — "$by" does not resolve to a declared method"""
    }

  private def blankReasons(coverage: Vector[Coverage]): Vector[String] =
    coverage.collect {
      case Coverage.Excluded(facade, reason) if isPlaceholder(reason) =>
        s"""Excluded("$facade", ...) has a blank reason"""
      case Coverage.ExcludedClass(facadeClass, reason) if isPlaceholder(reason) =>
        s"""ExcludedClass("$facadeClass", ...) has a blank reason"""
    }

  /** The unmapped-capability failure message: ready-to-paste `Excluded("<sig>", "")` lines, sorted
    * so a re-run against an unchanged jar produces an identical diff.
    */
  private def unmappedMessage(unmapped: Vector[String]): String =
    val lines = unmapped.map(sig => s"""Coverage.Excluded("$sig", ""),""")
    s"${unmapped.size} facade capability(ies) are neither Wrapped nor Excluded in FacadeCoverage. " +
      "Either wrap them (Coverage.Wrapped) or add a truthful Excluded reason. Paste-ready seed " +
      s"rows:\n${lines.mkString("\n")}"

  // ── Gate tests — the deliverable. Each runs CI-safe: pure reflection over the classpath jar, no
  // engine required. ─────────────────────────────────────────────────────────────────────────────

  test("(a) every facade capability is covered exactly once by FacadeCoverage"):
    val capabilities = facadeCapabilities
    val unmapped = unmappedCapabilities(capabilities, FacadeCoverage.entries)
    assert(unmapped.isEmpty, unmappedMessage(unmapped))
    val duplicates = duplicateCoverage(capabilities, FacadeCoverage.entries)
    assert(
      duplicates.isEmpty,
      s"capability(ies) covered by more than one FacadeCoverage entry: ${duplicates.mkString(", ")}"
    )

  test("(b) no FacadeCoverage entry names a capability the facade no longer has"):
    val stale = staleEntries(facadeCapabilities, FacadeCoverage.entries)
    assert(stale.isEmpty, s"stale FacadeCoverage entries:\n${stale.mkString("\n")}")

  test("(c) every Wrapped.by resolves to a real declared method"):
    val unresolved = unresolvedWrapped(FacadeCoverage.entries)
    assert(unresolved.isEmpty, s"unresolved Wrapped.by entries:\n${unresolved.mkString("\n")}")

  test("(d) every Excluded/ExcludedClass reason is non-blank"):
    val blanks = blankReasons(FacadeCoverage.entries)
    assert(blanks.isEmpty, s"blank-reason FacadeCoverage entries:\n${blanks.mkString("\n")}")

  // ── Negative self-tests — what stops the gate itself from rotting (issue #98's Test plan). Each
  // exercises the same helper the real gate test above uses, against a small fabricated coverage
  // vector, so a change that silently weakens a check (e.g. `reason.trim.isEmpty` → `reason.isEmpty`)
  // is caught here even though the seeded `FacadeCoverage.entries` stays green. ──────────────────────

  test("self-test: a blank reason fails check (d)"):
    val bad = Vector(Coverage.Excluded("Fake#m()", "   "))
    assert(blankReasons(bad).nonEmpty, "blank reason must be flagged")

  test("self-test: a blank ExcludedClass reason fails check (d)"):
    val bad = Vector(Coverage.ExcludedClass("Fake", ""))
    assert(blankReasons(bad).nonEmpty, "blank ExcludedClass reason must be flagged")

  test("self-test: a Wrapped.by naming a nonexistent method fails check (c)"):
    val bad =
      Vector(Coverage.Wrapped("Fake#m()", "rift.bridge.RiftConnector#thisMethodDoesNotExist"))
    assert(
      unresolvedWrapped(bad).nonEmpty,
      "a Wrapped.by naming a nonexistent method must be flagged"
    )

  test("self-test: a Wrapped.by naming a nonexistent class fails check (c)"):
    val bad = Vector(Coverage.Wrapped("Fake#m()", "rift.bridge.ThisClassDoesNotExist#m"))
    assert(
      unresolvedWrapped(bad).nonEmpty,
      "a Wrapped.by naming a nonexistent class must be flagged"
    )

  test("self-test: a fabricated stale key fails check (b)"):
    val realCapabilities = Set("Real#cap()")
    val bad = Vector(Coverage.Excluded("TotallyMadeUp#nope()", "fabricated for the self-test"))
    assert(
      staleEntries(realCapabilities, bad).nonEmpty,
      "a key absent from the enumeration must be flagged stale"
    )

  test("self-test: a fabricated stale ExcludedClass fails check (b)"):
    val realCapabilities = Set("Real#cap()")
    val bad = Vector(Coverage.ExcludedClass("TotallyMadeUp", "fabricated for the self-test"))
    assert(
      staleEntries(realCapabilities, bad).nonEmpty,
      "an ExcludedClass naming a class with no enumerated capability must be flagged stale"
    )

  test("self-test: an unmapped capability is flagged by check (a)"):
    val capabilities = Set("Real#cap()")
    assert(
      unmappedCapabilities(capabilities, Vector.empty).nonEmpty,
      "an uncovered capability must be flagged"
    )

  test("self-test: a capability covered twice is flagged by check (a)"):
    val capabilities = Set("Real#cap()")
    val doubled = Vector(
      Coverage.Excluded("Real#cap()", "first reason"),
      Coverage.Excluded("Real#cap()", "second, conflicting reason")
    )
    assert(
      duplicateCoverage(capabilities, doubled).nonEmpty,
      "a doubly-covered capability must be flagged"
    )
