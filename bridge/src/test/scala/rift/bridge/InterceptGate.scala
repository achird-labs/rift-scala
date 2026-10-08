package rift.bridge

import io.github.achirdlabs.rift.{
  Imposter as JImposter,
  Intercept as JIntercept,
  InterceptRule as JInterceptRule,
  InterceptRuleBuilder as JInterceptRuleBuilder,
  InterceptTrust as JInterceptTrust
}
import io.github.achirdlabs.rift.dsl.IsSpec as JIsSpec

/** Engine-free observation of what the intercept builders actually hand the facade (#102, #101).
  *
  * Lives in `package rift.bridge` so the `private[bridge]` `InterceptConnector` constructor is
  * reachable, and in bridge's *test* scope so the effect modules can share it over `test->test`
  * rather than each re-deriving the reflection next to a `package rift.bridge` cheat-file.
  *
  * The technique: a real `JInterceptRuleBuilder` over a **null** `InterceptRules` (rift-java 0.3.6
  * moved rule building there from `InterceptImpl`). `host`/`when` are pure field writes and land
  * normally; a terminal NPEs when it finally reaches the engine. That NPE is the signal the call
  * got all the way through translation, and the window in which the facade's predicate list is
  * readable.
  */
object InterceptGate:

  /** Wraps a fake facade `Intercept` in the package-private connector. */
  def connector(j: JIntercept): InterceptConnector = new InterceptConnector(j)

  /** A real (final, package-private-ctor) facade builder over a null engine. */
  def facadeBuilder(): JInterceptRuleBuilder =
    val ctor = classOf[JInterceptRuleBuilder]
      .getDeclaredConstructor(Class.forName("io.github.achirdlabs.rift.InterceptRules"))
    ctor.setAccessible(true)
    ctor.newInstance(null.asInstanceOf[AnyRef])

  /** Reflective read of the facade builder's private `predicates` list. */
  def facadePredicates(builder: JInterceptRuleBuilder): java.util.List[?] =
    val field = classOf[JInterceptRuleBuilder].getDeclaredField("predicates")
    field.setAccessible(true)
    field.get(builder).asInstanceOf[java.util.List[?]]

  /** Reflective read of the facade builder's private `host` (facade contract: `null` = catch-all).
    *
    * Both seed branches call `Intercept.rule()` and differ only in whether `.host(h)` follows, so
    * counting `rule()` calls cannot tell an all-hosts rule from a host-scoped one — only this can.
    */
  def facadeHost(builder: JInterceptRuleBuilder): Option[String] =
    val field = classOf[JInterceptRuleBuilder].getDeclaredField("host")
    field.setAccessible(true)
    Option(field.get(builder).asInstanceOf[String])

  /** Writes a recognisable predicate into a fresh facade builder, so that a later "the facade
    * received no predicates" assertion observes an *overwrite* rather than the empty list the
    * facade's constructor already installs.
    */
  private def seedSentinel(builder: JInterceptRuleBuilder): Unit =
    val field = classOf[JInterceptRuleBuilder].getDeclaredField("predicates")
    field.setAccessible(true)
    val seeded = new java.util.ArrayList[AnyRef]()
    seeded.add("SENTINEL-not-overwritten")
    field.set(builder, seeded)

  /** A facade `Intercept` whose `rule()` mints a fresh readable builder and remembers the last one,
    * so a caller can inspect what an effect surface's replay fold ultimately sent.
    */
  final class BuilderRecordingIntercept extends JIntercept:
    private var last: Option[JInterceptRuleBuilder] = None
    var ruleCalls: Int = 0

    /** The most recently minted builder. `Option`, not `null`: "no terminal ever ran" is a real
      * state a broken fold can produce, and it should fail as a missing value at the call site
      * rather than as a reflective NPE from inside this object.
      */
    def lastBuilder: JInterceptRuleBuilder =
      last.getOrElse(
        throw new NoSuchElementException(
          "no facade builder was minted — the terminal never reached Intercept.rule()"
        )
      )

    def rule(): JInterceptRuleBuilder =
      ruleCalls += 1
      // Pre-seed a sentinel predicate so "the facade got an empty list" is a real observation:
      // the facade's own constructor already initialises `predicates` to an empty list, so an
      // emptiness assertion against a virgin builder passes whether or not the bridge assigned it.
      val b = facadeBuilder()
      seedSentinel(b)
      last = Some(b)
      b

    private def nope[A]: A = throw new NotImplementedError(
      "BuilderRecordingIntercept: only rule() is exercised"
    )
    def address(): java.net.InetSocketAddress = nope
    def uri(): java.net.URI = nope
    def proxySelector(): java.net.ProxySelector = nope
    def serve(host: String, response: JIsSpec): JInterceptRule = nope
    def forward(host: String, hostPort: String): JInterceptRule = nope
    def redirectTo(host: String, imposter: JImposter): JInterceptRule = nope
    def rules(): java.util.List[JInterceptRule] = nope
    def clearRules(): Unit = nope
    var replaceCalls: Int = 0
    var replacedWith: Option[java.util.List[JInterceptRule]] = None
    var removed: Option[JInterceptRule] = None

    /** Runs `declare` against a staged set whose `rule()` is this fake's own `rule()`, so a staged
      * terminal is as observable as a direct one (and NPEs at the null engine just the same).
      */
    def replaceRules(
        declare: java.util.function.Consumer[? >: io.github.achirdlabs.rift.InterceptRuleSet]
    ): java.util.List[JInterceptRule] =
      replaceCalls += 1
      val outer = this
      declare.accept(new io.github.achirdlabs.rift.InterceptRuleSet:
        def rule(): JInterceptRuleBuilder = outer.rule()
        def serve(host: String, response: JIsSpec): JInterceptRule = nope
        def forward(host: String, target: String): JInterceptRule = nope
        def redirectTo(host: String, imposter: JImposter): JInterceptRule = nope
      )
      java.util.List.of()

    /** What the facade answers, deliberately unlike the input so a test can tell a value the bridge
      * took from the facade from one it echoed.
      */
    var replaceAnswer: java.util.List[JInterceptRule] = java.util.List.of()
    var removeAnswer: Boolean = false
    def replaceRules(rules: java.util.List[JInterceptRule]): java.util.List[JInterceptRule] =
      replacedWith = Some(rules)
      replaceAnswer
    def removeRule(rule: JInterceptRule): Boolean =
      removed = Some(rule)
      removeAnswer
    def trust(): JInterceptTrust = nope
    def caMaterial(): java.util.Optional[JIntercept.CaMaterial] = nope
    def close(): Unit = ()

  /** A facade `Intercept` whose `rule()` builds over a REAL `InterceptRules` whose sink captures
    * each finished rule instead of sending it, so a terminal completes and the exact wire JSON the
    * facade built (a forward target's `host`/`scheme`, the predicates) can be read back. The
    * engine-version gate is a no-op here.
    */
  final class CapturingIntercept extends JIntercept:
    val captured = scala.collection.mutable.ArrayBuffer.empty[JInterceptRule]
    private val realRules: AnyRef =
      val ctor = Class
        .forName("io.github.achirdlabs.rift.InterceptRules")
        .getDeclaredConstructor(
          classOf[java.util.function.Consumer[?]],
          classOf[java.util.function.Consumer[?]],
          classOf[Runnable]
        )
      ctor.setAccessible(true)
      val sink: java.util.function.Consumer[JInterceptRule] = r => captured += r
      val gate: java.util.function.Consumer[AnyRef] = _ => ()
      val open: Runnable = () => ()
      ctor.newInstance(sink, gate, open).asInstanceOf[AnyRef]

    def rule(): JInterceptRuleBuilder =
      val ctor = classOf[JInterceptRuleBuilder]
        .getDeclaredConstructor(Class.forName("io.github.achirdlabs.rift.InterceptRules"))
      ctor.setAccessible(true)
      ctor.newInstance(realRules)

    /** The `action.forward` object of the last captured rule, as the facade rendered it. */
    def lastForward: Option[String] =
      captured.lastOption
        .flatMap(r => FacadeDecode.json(r.raw()).get("action", "forward"))
        .map(_.render)

    /** How many predicates the last captured rule carries. */
    def lastPredicateCount: Int =
      captured.lastOption
        .flatMap(r => FacadeDecode.json(r.raw()).get("predicates"))
        .flatMap(_.asArray)
        .fold(0)(_.size)

    private def nope[A]: A = throw new NotImplementedError("CapturingIntercept: only rule()")
    def address(): java.net.InetSocketAddress = nope
    def uri(): java.net.URI = nope
    def proxySelector(): java.net.ProxySelector = nope
    def serve(host: String, response: JIsSpec): JInterceptRule = nope
    def forward(host: String, hostPort: String): JInterceptRule = nope
    def redirectTo(host: String, imposter: JImposter): JInterceptRule = nope
    def rules(): java.util.List[JInterceptRule] = nope
    def clearRules(): Unit = nope
    def replaceRules(
        declare: java.util.function.Consumer[? >: io.github.achirdlabs.rift.InterceptRuleSet]
    ): java.util.List[JInterceptRule] = nope
    def replaceRules(rules: java.util.List[JInterceptRule]): java.util.List[JInterceptRule] = nope
    def removeRule(rule: JInterceptRule): Boolean = nope
    def trust(): JInterceptTrust = nope
    def caMaterial(): java.util.Optional[JIntercept.CaMaterial] = nope
    def close(): Unit = ()
