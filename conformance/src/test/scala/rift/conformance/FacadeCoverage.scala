package rift.conformance

/** One policy row over a rift-java-core facade capability (`FacadeParitySpec`'s enumeration key,
  * `SimpleClassName#method(ParamSimpleName,...)`).
  *
  *   - `Coverage.Wrapped(facade, by)` — rift-scala wraps `facade`; `by` names the real wrapping
  *     member as `fully.qualified.ClassName#methodName` (e.g.
  *     `"rift.bridge.InterceptConnector#exportTruststore"`), checked to resolve reflectively so the
  *     pointer cannot rot silently.
  *   - `Coverage.Excluded(facade, reason)` — `facade` is deliberately unwrapped; `reason` must be a
  *     true, non-blank statement of why (a tracking issue number, a stated non-goal, ...). An
  *     `Excluded` whose reason does not hold is worse than an unmapped capability: it hides the gap
  *     instead of flagging it.
  *   - `ExcludedClass(facadeClass, reason)` — every capability declared on `facadeClass` is
  *     excluded for the one stated reason, rather than repeating the same reason per method
  *     (`RiftAsync`: each rift-scala effect surface supplies its own async, so wrapping the
  *     facade's own `CompletableFuture` surface is a non-goal).
  */
enum Coverage:
  case Wrapped(facade: String, by: String)
  case Excluded(facade: String, reason: String)
  case ExcludedClass(facadeClass: String, reason: String)

/** The facade-diff parity allow-list (issue #98) — single source of truth for what rift-scala wraps
  * or deliberately excludes from rift-java-core's top-level public API (pinned at
  * `Dependencies.riftJava`, currently 0.2.1). `FacadeParitySpec` fails the build on any capability
  * that isn't covered here exactly once.
  *
  * Seeded from the mechanism's own red output against the actual current state of the bridge module
  * (issue #98's Test plan: "the initial red output *is* the seed list") — never hand-typed ahead of
  * running it, and never copied from a design doc's example list. Every `Wrapped` row below was
  * checked against the bridge source (and, where the call shape was ambiguous — Scala default
  * parameters always filling every argument, `*`-spliced varargs always taking the array-typed
  * overload even when empty — against `javap` on the compiled bridge classes and the facade jar) so
  * that `by` names a real call site, not a guess. A recurring pattern worth noting for future
  * seeding: a bridge method with a Scala default parameter (`def foo(x: X = ...)`) always compiles
  * to a call against the facade's *full-arity* Java overload — so the facade's own reduced-arity
  * "convenience" overload for the same capability is structurally unreachable from rift-scala and
  * is correctly `Excluded`, not a gap.
  */
object FacadeCoverage:

  // Shared reason strings, declared before `entries` so the Vector literal below can reference them —
  // Scala initializes object members in declaration order, and a forward reference to a not-yet-run
  // val would compile to `null` (a source of real bugs elsewhere) were it not for -Xfatal-warnings
  // turning that exact forward-reference warning into a build failure here.

  /** Shared reason: rift-scala crosses this boundary only through the D2 raw-JSON seam
    * (`FacadeEncode`/`FacadeDecode`); the facade's own typed DSL/model constructor for the same
    * capability is never invoked.
    */
  private val d2Seam =
    "rift-scala crosses this boundary only through the D2 raw-JSON seam " +
      "(FacadeEncode/FacadeDecode); the facade's own typed DSL/model constructor for this " +
      "capability is never invoked"

  /** Shared reason: the bridge method has a Scala default parameter, so the compiled call always
    * supplies every argument and always hits the facade's full-arity overload — the reduced-arity
    * "convenience" overload the facade also exposes is structurally unreachable.
    */
  private val defaultArityUnreachable =
    "unreachable from rift-scala: the wrapping bridge method has a Scala default parameter, so "
      + "every call fills the full arity and only the widest facade overload is ever selected"

  private val varargsSpliceUnreachable =
    "unreachable from rift-scala: the wrapping bridge method takes Scala varargs and splices them "
      + "with `*`, which always selects the array overload — even for an empty splice"

  private val explicitOverloadDelegates =
    "unreachable from rift-scala: the bridge declares its own reduced-arity overload that delegates "
      + "to the widest one, so this facade overload is never selected directly"

  /** Shared reason: a read-back accessor on a facade `*Options` value that rift-scala only builds
    * via its `Builder` and passes by reference into connect/spawn/embedded/intercept/events — the
    * Scala-side `*Config` case class stays the source of truth, so the constructed object's own
    * fields are never read back.
    */
  private val optionsReadback =
    "a read-back accessor on a facade *Options value; rift-scala only builds these via their " +
      "Builder and passes the immutable result by reference into " +
      "connect/spawn/embedded/intercept/events — the Scala-side Config case class stays the " +
      "source of truth, so the constructed object's fields are never read back"

  /** Shared reason: `FacadeDecode.recordedRequest` decodes entirely through the D2 raw-JSON seam
    * (`raw().toJson()` + the Scala model's own `fromJson`); the facade record's individual typed
    * accessors are never read.
    */
  private val recordedRequestSeam =
    "rift-scala decodes RecordedRequest entirely through the D2 raw-JSON seam (raw().toJson() + " +
      "the Scala model's own fromJson) in FacadeDecode.recordedRequest; this typed accessor on " +
      "the facade record itself is never read"

  /** Shared reason: an `Intercept`-level `(host, ...)` convenience shortcut; `InterceptConnector`
    * always routes through `rule(host)`/`rule()` to build an `InterceptRuleBuilder` first, so only
    * that builder's single-target overload is ever called.
    */
  private val interceptShortcut =
    "an Intercept-level (host, ...) convenience shortcut; InterceptConnector always routes " +
      "through rule(host)/rule() to build an InterceptRuleBuilder first, so only that builder's " +
      "single-target overload is ever called"

  val entries: Vector[Coverage] = Vector(
    Coverage.Wrapped("RuleKind#SERVE", "rift.bridge.RuleKind#fromJava"),
    Coverage.Wrapped("RuleKind#FORWARD", "rift.bridge.RuleKind#fromJava"),
    Coverage.Wrapped("RuleKind#REDIRECT", "rift.bridge.RuleKind#fromJava"),
    Coverage.Wrapped("TruststoreFormat#PKCS12", "rift.bridge.TruststoreFormat#toJava"),
    Coverage.Wrapped("TruststoreFormat#JKS", "rift.bridge.TruststoreFormat#toJava"),
    Coverage.Wrapped("VersionCheck#FAIL", "rift.bridge.VersionCheck#toJava"),
    Coverage.Wrapped("VersionCheck#WARN", "rift.bridge.VersionCheck#toJava"),
    Coverage.Wrapped("VersionCheck#OFF", "rift.bridge.VersionCheck#toJava"),
    Coverage.Wrapped("RecordMode#ONCE", "rift.bridge.RecordMode#toJava"),
    Coverage.Wrapped("RecordMode#ALWAYS", "rift.bridge.RecordMode#toJava"),
    Coverage.Wrapped("RecordMode#TRANSPARENT", "rift.bridge.RecordMode#toJava"),
    Coverage.Wrapped("EventStreamOptions.EventType#REQUESTS", "rift.bridge.EventType#toJava"),
    Coverage.Wrapped("EventStreamOptions.EventType#LIFECYCLE", "rift.bridge.EventType#toJava"),
    Coverage.Wrapped(
      "RiftEvent.ImposterChanged.Action#CREATED",
      "rift.bridge.FacadeDecode#riftEvent"
    ),
    Coverage.Wrapped(
      "RiftEvent.ImposterChanged.Action#REPLACED",
      "rift.bridge.FacadeDecode#riftEvent"
    ),
    Coverage.Wrapped(
      "RiftEvent.ImposterChanged.Action#STUBS_CHANGED",
      "rift.bridge.FacadeDecode#riftEvent"
    ),
    Coverage.Wrapped(
      "RiftEvent.ImposterChanged.Action#DELETED",
      "rift.bridge.FacadeDecode#riftEvent"
    ),
    Coverage.Wrapped(
      "RiftEvent.ImposterChanged.Action#ALL_DELETED",
      "rift.bridge.FacadeDecode#riftEvent"
    ),
    Coverage.Excluded(
      "InterceptOptions.Builder#ca(byte[],byte[])",
      "CaMaterial.fromPemBytes performs the facade's own UTF-8 decode and routes through the "
        + "ca(String,String) overload, so this one is never called (#95)"
    ),
    Coverage.Excluded(
      "MatchClause.FlowId#value()",
      "a match-clause field getter — clauses only ever travel one way, built Scala-side and encoded by FacadeEncode.matchClause; nothing reads them back"
    ),
    Coverage.Excluded(
      "MatchClause.Header#name()",
      "a match-clause field getter — clauses only ever travel one way, built Scala-side and encoded by FacadeEncode.matchClause; nothing reads them back"
    ),
    Coverage.Excluded(
      "MatchClause.Header#value()",
      "a match-clause field getter — clauses only ever travel one way, built Scala-side and encoded by FacadeEncode.matchClause; nothing reads them back"
    ),
    Coverage.Excluded(
      "MatchClause.Method#value()",
      "a match-clause field getter — clauses only ever travel one way, built Scala-side and encoded by FacadeEncode.matchClause; nothing reads them back"
    ),
    Coverage.Excluded(
      "MatchClause.Path#value()",
      "a match-clause field getter — clauses only ever travel one way, built Scala-side and encoded by FacadeEncode.matchClause; nothing reads them back"
    ),
    Coverage.Excluded(
      "RiftEvent.Hello#seq()",
      "FacadeDecode.riftEvent does not read seq for Hello — the record carries seqAtConnect, which is the field that means something at connect time"
    ),
    Coverage.Excluded(
      "RiftEvent.Lagged#seq()",
      "FacadeDecode.riftEvent does not read seq for Lagged — the facade always reports it empty; `missed` is the payload"
    ),
    Coverage.ExcludedClass(
      "RiftAsync",
      "each rift-scala effect surface supplies its own async semantics over the blocking bridge " +
        "(DESIGN.md non-goals) — wrapping the facade's own CompletableFuture surface is a " +
        "deliberate non-goal, not a gap"
    ),
    Coverage.Wrapped("ApplyResult#created()", "rift.bridge.RiftConnector#applyConfig"),
    Coverage.Wrapped("ApplyResult#deleted()", "rift.bridge.RiftConnector#applyConfig"),
    Coverage.Wrapped("ApplyResult#failed()", "rift.bridge.RiftConnector#applyConfig"),
    Coverage.Excluded(
      "ApplyResult#read(JsonValue)",
      "a static JSON-deserializing factory rift-scala never calls; every instance of this type " +
        "arrives live from a facade call (info()/applyConfig()/the request journal), never " +
        "round-tripped from JSON built on the Scala side"
    ),
    Coverage.Wrapped("ApplyResult#replaced()", "rift.bridge.RiftConnector#applyConfig"),
    Coverage.Wrapped("ApplyResult#stubPatched()", "rift.bridge.RiftConnector#applyConfig"),
    Coverage
      .Wrapped("RecordSpec.Builder#addWaitBehavior(boolean)", "rift.bridge.RecordSpec#toJava"),
    Coverage
      .Wrapped("EmbeddedOptions.Builder#adminHost(String)", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage
      .Wrapped("EmbeddedOptions.Builder#adminPort(int)", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage.Wrapped("SpawnOptions.Builder#adminPort(int)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage
      .Wrapped("SpawnOptions.Builder#allowInjection(boolean)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage
      .Wrapped("ConnectOptions.Builder#apiKey(String)", "rift.bridge.ConnectConfig#toOptions"),
    Coverage
      .Wrapped("EmbeddedOptions.Builder#apiKey(String)", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage.Wrapped("SpawnOptions.Builder#binaryPath(Path)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped("ConnectOptions.Builder#build()", "rift.bridge.ConnectConfig#toOptions"),
    Coverage.Wrapped("EmbeddedOptions.Builder#build()", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage
      .Wrapped("EventStreamOptions.Builder#build()", "rift.bridge.EventStreamConfig#toOptions"),
    Coverage.Wrapped("InterceptOptions.Builder#build()", "rift.bridge.InterceptConfig#toOptions"),
    Coverage.Wrapped("RecordSpec.Builder#build()", "rift.bridge.RecordSpec#toJava"),
    Coverage.Wrapped("SpawnOptions.Builder#build()", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped(
      "InterceptOptions.Builder#ca(KeyStore,char[])",
      "rift.bridge.InterceptConfig#toOptions"
    ),
    Coverage
      .Wrapped("InterceptOptions.Builder#ca(Path,Path)", "rift.bridge.InterceptConfig#toOptions"),
    Coverage.Wrapped(
      "InterceptOptions.Builder#ca(String,String)",
      "rift.bridge.InterceptConfig#toOptions"
    ),
    Coverage.Wrapped("SpawnOptions.Builder#env(Map)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage
      .Wrapped("RecordSpec.Builder#generateBy(RequestField[])", "rift.bridge.RecordSpec#toJava"),
    Coverage
      .Wrapped("InterceptOptions.Builder#generateCa()", "rift.bridge.InterceptConfig#toOptions"),
    Coverage
      .Wrapped("InterceptOptions.Builder#host(String)", "rift.bridge.InterceptConfig#toOptions"),
    Coverage.Wrapped("SpawnOptions.Builder#host(String)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped(
      "ConnectOptions.Builder#hostResolver(IntFunction)",
      "rift.bridge.ConnectConfig#toOptions"
    ),
    Coverage.Wrapped(
      "EventStreamOptions.Builder#idleTimeout(Duration)",
      "rift.bridge.EventStreamConfig#toOptions"
    ),
    Coverage.Wrapped("RecordSpec.Builder#ignoreHeaders(String[])", "rift.bridge.RecordSpec#toJava"),
    Coverage
      .Wrapped("SpawnOptions.Builder#inheritLog(boolean)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage
      .Wrapped("EmbeddedOptions.Builder#libraryPath(Path)", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage
      .Wrapped("SpawnOptions.Builder#localOnly(boolean)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped("SpawnOptions.Builder#logLevel(String)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped(
      "EventStreamOptions.Builder#match(MatchClause[])",
      "rift.bridge.EventStreamConfig#toOptions"
    ),
    Coverage.Wrapped("SpawnOptions.Builder#mirrorUrl(URI)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped("RecordSpec.Builder#mode(RecordMode)", "rift.bridge.RecordSpec#toJava"),
    Coverage
      .Wrapped("EventStreamOptions.Builder#port(int)", "rift.bridge.EventStreamConfig#toOptions"),
    Coverage.Wrapped("InterceptOptions.Builder#port(int)", "rift.bridge.InterceptConfig#toOptions"),
    Coverage.Wrapped(
      "ConnectOptions.Builder#requestTimeout(Duration)",
      "rift.bridge.ConnectConfig#toOptions"
    ),
    Coverage.Wrapped(
      "EmbeddedOptions.Builder#serveAdminEagerly(boolean)",
      "rift.bridge.EmbeddedConfig#toOptions"
    ),
    Coverage.Wrapped(
      "SpawnOptions.Builder#shutdownTimeout(Duration)",
      "rift.bridge.SpawnConfig#toOptions"
    ),
    Coverage.Wrapped(
      "SpawnOptions.Builder#startupTimeout(Duration)",
      "rift.bridge.SpawnConfig#toOptions"
    ),
    Coverage.Wrapped(
      "EventStreamOptions.Builder#types(EventType[])",
      "rift.bridge.EventStreamConfig#toOptions"
    ),
    Coverage.Wrapped("SpawnOptions.Builder#version(String)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped(
      "ConnectOptions.Builder#versionCheck(VersionCheck)",
      "rift.bridge.ConnectConfig#toOptions"
    ),
    Coverage.Wrapped(
      "EmbeddedOptions.Builder#versionCheck(VersionCheck)",
      "rift.bridge.EmbeddedConfig#toOptions"
    ),
    Coverage.Wrapped("SpawnOptions.Builder#workingDir(Path)", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Wrapped("Intercept.CaMaterial#certPem()", "rift.bridge.InterceptConnector#caMaterial"),
    Coverage.Wrapped("Intercept.CaMaterial#keyPem()", "rift.bridge.InterceptConnector#caMaterial"),
    Coverage.Excluded("ConnectOptions#adminUri()", optionsReadback),
    Coverage.Excluded("ConnectOptions#apiKey()", optionsReadback),
    Coverage.Wrapped("ConnectOptions#builder(URI)", "rift.bridge.ConnectConfig#toOptions"),
    Coverage.Excluded("ConnectOptions#hostResolver()", optionsReadback),
    Coverage.Excluded("ConnectOptions#requestTimeout()", optionsReadback),
    Coverage.Excluded("ConnectOptions#versionCheck()", optionsReadback),
    Coverage.Excluded("EmbeddedOptions#adminHost()", optionsReadback),
    Coverage.Excluded("EmbeddedOptions#adminPort()", optionsReadback),
    Coverage.Excluded("EmbeddedOptions#apiKey()", optionsReadback),
    Coverage.Wrapped("EmbeddedOptions#builder()", "rift.bridge.EmbeddedConfig#toOptions"),
    Coverage.Excluded("EmbeddedOptions#libraryPath()", optionsReadback),
    Coverage.Excluded("EmbeddedOptions#serveAdminEagerly()", optionsReadback),
    Coverage.Excluded("EmbeddedOptions#versionCheck()", optionsReadback),
    Coverage.Wrapped("EngineInfo#commit()", "rift.bridge.RiftConnector#info"),
    Coverage.Wrapped("EngineInfo#features()", "rift.bridge.RiftConnector#info"),
    Coverage.Excluded(
      "EngineInfo#read(JsonValue)",
      "a static JSON-deserializing factory rift-scala never calls; every instance of this type " +
        "arrives live from a facade call (info()/applyConfig()/the request journal), never " +
        "round-tripped from JSON built on the Scala side"
    ),
    Coverage.Wrapped("EngineInfo#version()", "rift.bridge.RiftConnector#info"),
    Coverage.Wrapped("EventStream#close()", "rift.bridge.EventStreamConnector#close"),
    Coverage.Wrapped("EventStream#iterator()", "rift.bridge.EventStreamConnector#poll"),
    Coverage.Wrapped("EventStreamOptions#builder()", "rift.bridge.EventStreamConfig#toOptions"),
    Coverage.Excluded("EventStreamOptions#idleTimeout()", optionsReadback),
    Coverage.Excluded("EventStreamOptions#match()", optionsReadback),
    Coverage.Excluded("EventStreamOptions#port()", optionsReadback),
    Coverage.Excluded("EventStreamOptions#types()", optionsReadback),
    Coverage.Wrapped("FlowState#delete(String)", "rift.bridge.FlowStateHandle#delete"),
    Coverage.Wrapped("FlowState#get(String)", "rift.bridge.FlowStateHandle#get"),
    Coverage.Wrapped("FlowState#put(String,JsonValue)", "rift.bridge.FlowStateHandle#put"),
    Coverage.Wrapped("FlowState#put(String,String)", "rift.bridge.FlowStateHandle#put"),
    Coverage.Wrapped("RiftEvent.Hello#engineVersion()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.Hello#port()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.Hello#seqAtConnect()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.Hello#types()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("Imposter#addStub(JsonValue)", "rift.bridge.ImposterConnector#addStub"),
    Coverage.Wrapped("Imposter#addStub(JsonValue,int)", "rift.bridge.ImposterConnector#addStub"),
    Coverage.Excluded("Imposter#addStub(StubSpec)", d2Seam),
    Coverage.Excluded("Imposter#addStub(StubSpec,int)", d2Seam),
    Coverage
      .Wrapped("Imposter#addStubFirst(JsonValue)", "rift.bridge.ImposterConnector#addStubFirst"),
    Coverage.Excluded("Imposter#addStubFirst(StubSpec)", d2Seam),
    Coverage.Wrapped(
      "Imposter#clearProxyResponses()",
      "rift.bridge.ImposterConnector#clearProxyResponses"
    ),
    Coverage.Wrapped("Imposter#clearRecorded()", "rift.bridge.ImposterConnector#clearRecorded"),
    Coverage.Wrapped(
      "Imposter#clearRecorded(MatchClause[])",
      "rift.bridge.ImposterConnector#clearRecorded"
    ),
    Coverage.Wrapped("Imposter#definition()", "rift.bridge.ImposterConnector#definition"),
    Coverage.Wrapped("Imposter#delete()", "rift.bridge.ImposterConnector#delete"),
    Coverage.Wrapped("Imposter#disable()", "rift.bridge.ImposterConnector#disable"),
    Coverage.Wrapped("Imposter#enable()", "rift.bridge.ImposterConnector#enable"),
    Coverage.Wrapped("Imposter#flowState(String)", "rift.bridge.ImposterConnector#flowState"),
    Coverage.Wrapped("Imposter#name()", "rift.bridge.ImposterConnector#name"),
    Coverage.Wrapped("Imposter#port()", "rift.bridge.ImposterConnector#port"),
    Coverage.Wrapped("Imposter#recorded()", "rift.bridge.ImposterConnector#recorded"),
    Coverage.Wrapped("Imposter#recorded(RequestMatch)", "rift.bridge.ImposterConnector#recorded"),
    Coverage.Excluded("Imposter#recordedPage()", varargsSpliceUnreachable),
    Coverage.Wrapped(
      "Imposter#recordedPage(MatchClause[])",
      "rift.bridge.ImposterConnector#recordedPage"
    ),
    Coverage.Excluded("Imposter#recordedSince(long)", varargsSpliceUnreachable),
    Coverage.Wrapped(
      "Imposter#recordedSince(long,MatchClause[])",
      "rift.bridge.ImposterConnector#recordedSince"
    ),
    Coverage
      .Wrapped("Imposter#replaceStubs(JsonValue)", "rift.bridge.ImposterConnector#replaceStubs"),
    Coverage.Excluded("Imposter#replaceStubs(List)", d2Seam),
    Coverage.Wrapped("Imposter#scenarios()", "rift.bridge.ImposterConnector#scenarios"),
    Coverage.Wrapped("Imposter#space(String)", "rift.bridge.ImposterConnector#space"),
    Coverage.Excluded("Imposter#startRecording(String)", defaultArityUnreachable),
    Coverage.Wrapped(
      "Imposter#startRecording(String,RecordSpec)",
      "rift.bridge.ImposterConnector#startRecording"
    ),
    Coverage.Wrapped("Imposter#stub(String)", "rift.bridge.ImposterConnector#stub"),
    Coverage.Wrapped("Imposter#stubs()", "rift.bridge.ImposterConnector#stubs"),
    Coverage.Wrapped("Imposter#uri()", "rift.bridge.ImposterConnector#uri"),
    Coverage.Excluded("Imposter#verify(RequestMatch)", defaultArityUnreachable),
    Coverage.Wrapped(
      "Imposter#verify(RequestMatch,VerificationTimes)",
      "rift.bridge.ImposterConnector#verify"
    ),
    Coverage.Wrapped(
      "Imposter#verifyNoInteractions()",
      "rift.bridge.ImposterConnector#verifyNoInteractions"
    ),
    Coverage.Wrapped(
      "Imposter#verifyResult(RequestMatch,VerificationTimes,VerifyDetail[])",
      "rift.bridge.ImposterConnector#verifyResult"
    ),
    Coverage
      .Excluded("Imposter#verifyResult(RequestMatch,VerifyDetail[])", defaultArityUnreachable),
    Coverage.Wrapped("RiftEvent.ImposterChanged#action()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.ImposterChanged#port()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.ImposterChanged#seq()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("Intercept#address()", "rift.bridge.InterceptConnector#address"),
    Coverage.Wrapped("Intercept#caMaterial()", "rift.bridge.InterceptConnector#caMaterial"),
    Coverage.Wrapped("Intercept#clearRules()", "rift.bridge.InterceptConnector#clearRules"),
    Coverage.Wrapped("Intercept#close()", "rift.bridge.InterceptConnector#close"),
    Coverage.Excluded("Intercept#forward(String,String)", interceptShortcut),
    Coverage.Wrapped("Intercept#proxySelector()", "rift.bridge.InterceptConnector#proxySelector"),
    Coverage.Excluded("Intercept#redirectTo(String,Imposter)", interceptShortcut),
    Coverage.Wrapped("Intercept#rule()", "rift.bridge.InterceptConnector#rule"),
    Coverage.Wrapped("Intercept#rules()", "rift.bridge.InterceptConnector#rules"),
    Coverage.Excluded("Intercept#serve(String,IsSpec)", interceptShortcut),
    Coverage.Wrapped("Intercept#trust()", "rift.bridge.InterceptConnector#caPem"),
    Coverage.Wrapped("Intercept#uri()", "rift.bridge.InterceptConnector#proxyUri"),
    Coverage
      .Wrapped("InterceptOptions#attach(String,int)", "rift.bridge.RiftConnector#interceptAttach"),
    Coverage.Wrapped("InterceptOptions#builder()", "rift.bridge.InterceptConfig#toOptions"),
    Coverage.Excluded("InterceptOptions#toJson()", optionsReadback),
    Coverage.Wrapped("InterceptRule#host()", "rift.bridge.InterceptRule#fromJava"),
    Coverage.Wrapped("InterceptRule#kind()", "rift.bridge.InterceptRule#fromJava"),
    Coverage.Excluded(
      "InterceptRule#predicates()",
      "InterceptRule.fromJava translates only host()/kind()/raw() — the raw wire JSON (via raw()) " +
        "already carries the predicates, so this typed accessor is redundant and unread"
    ),
    Coverage.Wrapped("InterceptRule#raw()", "rift.bridge.InterceptRule#fromJava"),
    Coverage
      .Wrapped("InterceptRuleBuilder#forward(String)", "rift.bridge.InterceptRuleBuilder#forward"),
    Coverage.Wrapped("InterceptRuleBuilder#host(String)", "rift.bridge.InterceptConnector#rule"),
    Coverage.Wrapped(
      "InterceptRuleBuilder#redirectTo(Imposter)",
      "rift.bridge.InterceptRuleBuilder#redirectTo"
    ),
    Coverage
      .Wrapped("InterceptRuleBuilder#serve(IsSpec)", "rift.bridge.InterceptRuleBuilder#serve"),
    Coverage.Wrapped(
      "InterceptRuleBuilder#when(RequestMatch)",
      "rift.bridge.InterceptRuleBuilder#applied"
    ),
    Coverage.Wrapped("InterceptTrust#caPem()", "rift.bridge.InterceptConnector#caPem"),
    Coverage.Wrapped(
      "InterceptTrust#exportTruststore(TruststoreFormat,String,Path)",
      "rift.bridge.InterceptConnector#exportTruststore"
    ),
    Coverage.Wrapped(
      "InterceptTrust#exportTruststoreWithSystemCAs(TruststoreFormat,String,Path)",
      "rift.bridge.InterceptConnector#exportTruststoreWithSystemCAs"
    ),
    Coverage.Wrapped("InterceptTrust#sslContext()", "rift.bridge.InterceptConnector#sslContext"),
    Coverage.Wrapped(
      "InterceptTrust#sslContextWithSystemCAs()",
      "rift.bridge.InterceptConnector#sslContextWithSystemCAs"
    ),
    Coverage.Wrapped("RiftEvent.Lagged#missed()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("MatchClause#flowId(String)", "rift.bridge.FacadeEncode#matchClause"),
    Coverage.Wrapped("MatchClause#header(String,String)", "rift.bridge.FacadeEncode#matchClause"),
    Coverage.Wrapped("MatchClause#method(String)", "rift.bridge.FacadeEncode#matchClause"),
    Coverage.Wrapped("MatchClause#path(String)", "rift.bridge.FacadeEncode#matchClause"),
    Coverage.Excluded("RecordSpec#addWaitBehavior()", optionsReadback),
    Coverage.Wrapped("RecordSpec#builder()", "rift.bridge.RecordSpec#toJava"),
    Coverage.Excluded("RecordSpec#generators()", optionsReadback),
    Coverage.Excluded("RecordSpec#ignoreHeaders()", optionsReadback),
    Coverage.Excluded("RecordSpec#mode()", optionsReadback),
    Coverage.Wrapped("RecordedPage#nextIndex()", "rift.bridge.FacadeDecode#recordedPage"),
    Coverage.Wrapped("RecordedPage#requests()", "rift.bridge.FacadeDecode#recordedPage"),
    Coverage.Wrapped("RecordedPage#truncated()", "rift.bridge.FacadeDecode#recordedPage"),
    Coverage.Excluded("RecordedRequest#body()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#bodyAs(Class)", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#bodyAsJson()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#flowId()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#header(String)", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#headers()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#method()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#path()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#pathParams()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#query()", recordedRequestSeam),
    Coverage.Wrapped("RecordedRequest#raw()", "rift.bridge.FacadeDecode#recordedRequest"),
    Coverage.Excluded(
      "RecordedRequest#read(JsonValue)",
      "a static JSON-deserializing factory rift-scala never calls; every instance of this type " +
        "arrives live from a facade call (info()/applyConfig()/the request journal), never " +
        "round-tripped from JSON built on the Scala side"
    ),
    Coverage.Excluded("RecordedRequest#requestFrom()", recordedRequestSeam),
    Coverage.Excluded("RecordedRequest#timestamp()", recordedRequestSeam),
    Coverage.Wrapped("Recording#close()", "rift.bridge.RecordingConnector#close"),
    Coverage.Wrapped("Recording#persist(Path)", "rift.bridge.RecordingConnector#persist"),
    Coverage.Wrapped("Recording#snapshot()", "rift.bridge.RecordingConnector#snapshot"),
    Coverage.Wrapped("Recording#stop()", "rift.bridge.RecordingConnector#stop"),
    Coverage.Wrapped("RiftEvent.RequestRecorded#flowId()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.RequestRecorded#index()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.RequestRecorded#port()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.RequestRecorded#request()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftEvent.RequestRecorded#seq()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("Rift#adminUri()", "rift.bridge.RiftConnector#adminUri"),
    Coverage.Wrapped("Rift#applyConfig(JsonValue)", "rift.bridge.RiftConnector#applyConfig"),
    Coverage.Excluded(
      "Rift#async()",
      "returns the facade's RiftAsync surface; each rift-scala effect surface supplies its own " +
        "async semantics over the blocking bridge (DESIGN.md non-goals) — the same non-goal as " +
        "ExcludedClass(\"RiftAsync\", ...), since calling async() would only reach a type this " +
        "build deliberately never wraps"
    ),
    Coverage.Wrapped("Rift#close()", "rift.bridge.RiftConnector#close"),
    Coverage.Wrapped("Rift#connect(ConnectOptions)", "rift.bridge.RiftConnector#connect"),
    Coverage.Excluded(
      "Rift#connect(URI)",
      s"$defaultArityUnreachable (ConnectConfig always builds a full ConnectOptions)"
    ),
    Coverage.Excluded(
      "Rift#create(ImposterDefinition)",
      s"$d2Seam (RiftConnector.create always calls the JsonValue overload)"
    ),
    Coverage.Excluded("Rift#create(ImposterSpec)", d2Seam),
    Coverage.Wrapped("Rift#create(JsonValue)", "rift.bridge.RiftConnector#create"),
    Coverage.Excluded(
      "Rift#create(String)",
      s"$d2Seam (RiftConnector.create always calls the JsonValue overload)"
    ),
    Coverage.Wrapped("Rift#deleteAll()", "rift.bridge.RiftConnector#deleteAll"),
    Coverage.Excluded("Rift#embedded()", defaultArityUnreachable),
    Coverage.Wrapped("Rift#embedded(EmbeddedOptions)", "rift.bridge.RiftConnector#embedded"),
    Coverage.Wrapped("Rift#events(EventStreamOptions)", "rift.bridge.RiftConnector#events"),
    Coverage.Wrapped("Rift#imposter(int)", "rift.bridge.RiftConnector#imposter"),
    Coverage.Wrapped("Rift#imposters()", "rift.bridge.RiftConnector#imposters"),
    Coverage.Wrapped("Rift#info()", "rift.bridge.RiftConnector#info"),
    Coverage.Excluded("Rift#intercept()", defaultArityUnreachable),
    Coverage.Wrapped("Rift#intercept(InterceptOptions)", "rift.bridge.RiftConnector#intercept"),
    Coverage.Wrapped("Rift#isEmbeddedAvailable()", "rift.bridge.RiftConnector#isEmbeddedAvailable"),
    Coverage.Wrapped("Rift#replaceAll(List)", "rift.bridge.RiftConnector#replaceAll"),
    Coverage.Excluded("Rift#spawn()", defaultArityUnreachable),
    Coverage.Wrapped("Rift#spawn(SpawnOptions)", "rift.bridge.RiftConnector#spawn"),
    Coverage.Wrapped("RiftEvent#seq()", "rift.bridge.FacadeDecode#riftEvent"),
    Coverage.Wrapped("RiftVersion#engineVersion()", "rift.bridge.RiftVersions#engine"),
    Coverage.Wrapped("RiftVersion#get()", "rift.bridge.RiftVersions#riftJava"),
    Coverage.Wrapped("Scenarios#list()", "rift.bridge.ScenariosHandle#list"),
    Coverage.Wrapped("Scenarios#list(String)", "rift.bridge.ScenariosHandle#list"),
    Coverage.Wrapped("Scenarios#reset()", "rift.bridge.ScenariosHandle#reset"),
    Coverage.Wrapped("Scenarios#setState(String,String)", "rift.bridge.ScenariosHandle#setState"),
    Coverage
      .Wrapped("Scenarios#setState(String,String,String)", "rift.bridge.ScenariosHandle#setState"),
    Coverage.Wrapped("Scenarios#state(String)", "rift.bridge.ScenariosHandle#state"),
    Coverage.Wrapped("Space#addStub(JsonValue)", "rift.bridge.SpaceHandle#addStub"),
    Coverage.Excluded("Space#addStub(StubSpec)", d2Seam),
    Coverage.Wrapped("Space#delete()", "rift.bridge.SpaceHandle#delete"),
    Coverage.Excluded(
      "Space#flowId()",
      "SpaceHandle carries the FlowId its caller passed to ImposterConnector.space(flowId) " +
        "directly; the facade's own Space.flowId() readback is never queried since the " +
        "caller-supplied value is already authoritative"
    ),
    Coverage.Wrapped("Space#recorded()", "rift.bridge.SpaceHandle#recorded"),
    Coverage.Wrapped("Space#recorded(RequestMatch)", "rift.bridge.SpaceHandle#recorded"),
    Coverage.Wrapped("Space#recordedPage(MatchClause[])", "rift.bridge.SpaceHandle#recordedPage"),
    Coverage.Wrapped(
      "Space#recordedSince(long,MatchClause[])",
      "rift.bridge.SpaceHandle#recordedSince"
    ),
    Coverage.Wrapped("Space#stubs()", "rift.bridge.SpaceHandle#stubs"),
    Coverage.Excluded("Space#verify(RequestMatch)", defaultArityUnreachable),
    Coverage
      .Wrapped("Space#verify(RequestMatch,VerificationTimes)", "rift.bridge.SpaceHandle#verify"),
    Coverage.Wrapped(
      "Space#verifyResult(RequestMatch,VerificationTimes,VerifyDetail[])",
      "rift.bridge.SpaceHandle#verifyResult"
    ),
    Coverage.Excluded("Space#verifyResult(RequestMatch,VerifyDetail[])", explicitOverloadDelegates),
    Coverage.Excluded("SpawnOptions#adminPort()", optionsReadback),
    Coverage.Excluded("SpawnOptions#allowInjection()", optionsReadback),
    Coverage.Excluded("SpawnOptions#binaryPath()", optionsReadback),
    Coverage.Wrapped("SpawnOptions#builder()", "rift.bridge.SpawnConfig#toOptions"),
    Coverage.Excluded("SpawnOptions#env()", optionsReadback),
    Coverage.Excluded("SpawnOptions#host()", optionsReadback),
    Coverage.Excluded("SpawnOptions#inheritLog()", optionsReadback),
    Coverage.Excluded("SpawnOptions#localOnly()", optionsReadback),
    Coverage.Excluded("SpawnOptions#logLevel()", optionsReadback),
    Coverage.Excluded("SpawnOptions#mirrorUrl()", optionsReadback),
    Coverage.Excluded("SpawnOptions#shutdownTimeout()", optionsReadback),
    Coverage.Excluded("SpawnOptions#startupTimeout()", optionsReadback),
    Coverage.Excluded("SpawnOptions#version()", optionsReadback),
    Coverage.Excluded("SpawnOptions#workingDir()", optionsReadback),
    Coverage.Wrapped("Scenarios.State#name()", "rift.bridge.ScenariosHandle#list"),
    Coverage.Wrapped("Scenarios.State#state()", "rift.bridge.ScenariosHandle#list"),
    Coverage.Wrapped("StubRef#definition()", "rift.bridge.StubHandle#definition"),
    Coverage.Wrapped("StubRef#delete()", "rift.bridge.StubHandle#delete"),
    Coverage.Wrapped("StubRef#id()", "rift.bridge.StubHandle#id"),
    Coverage.Wrapped("StubRef#index()", "rift.bridge.StubHandle#index"),
    Coverage.Wrapped("StubRef#replace(JsonValue)", "rift.bridge.StubHandle#replace"),
    Coverage.Excluded("StubRef#replace(StubSpec)", d2Seam),

    // ── issue #130: the facade DSL/verify subpackages ───────────────────────────────────────
    // Seeded from the bytecode, not by hand: check (c2) verifies every Wrapped row below
    // actually reaches its capability, which is the whole reason the truth-check landed first.

    Coverage
      .Wrapped("ClosestMiss#failedPredicates()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("ClosestMiss#request()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("FailedPredicate#actual()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("FailedPredicate#predicate()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("Fault#CONNECTION_RESET_BY_PEER", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("Fault#EMPTY_RESPONSE", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("Fault#MALFORMED_RESPONSE_CHUNK", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("Fault#RANDOM_DATA_THEN_CLOSE", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#decorate(String)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#repeat(int)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#shellTransform(String[])", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#templated()", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#waitBetween(long,long)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#waitInject(String)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#waitMs(long)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#waitScript(String)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withBinaryBody(byte[])", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withErrorFault(double,int)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withErrorFault(double,int,String)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage
      .Wrapped("IsSpec#withErrorFault(double,int,String,Map)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withHeader(String,String[])", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withJsonBody(JsonValue)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withLatencyFault(double,Duration)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped(
      "IsSpec#withLatencyFault(double,Duration,Duration)",
      "rift.bridge.FacadeEncode#isSpec"
    ),
    Coverage.Wrapped("IsSpec#withTcpFault(Fault)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withTcpFault(double,Fault)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("IsSpec#withTextBody(String)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("RequestMatch#ofJson(JsonValue)", "rift.bridge.FacadeEncode#requestMatch"),
    Coverage.Wrapped("RiftDsl#status(int)", "rift.bridge.FacadeEncode#isSpec"),
    Coverage.Wrapped("VerificationResult#closest()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("VerificationResult#matched()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage
      .Wrapped("VerificationResult#requests()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage
      .Wrapped("VerificationResult#satisfied()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("VerificationResult#total()", "rift.bridge.FacadeDecode#verificationResult"),
    Coverage.Wrapped("VerificationTimes#atLeast(int)", "rift.bridge.FacadeEncode#times"),
    Coverage.Wrapped("VerificationTimes#atMost(int)", "rift.bridge.FacadeEncode#times"),
    Coverage.Wrapped("VerificationTimes#between(int,int)", "rift.bridge.FacadeEncode#times"),
    Coverage.Wrapped("VerificationTimes#exactly(int)", "rift.bridge.FacadeEncode#times"),
    Coverage.Wrapped("VerifyDetail#CLOSEST", "rift.bridge.FacadeEncode#verifyDetails"),
    Coverage.Wrapped("VerifyDetail#REQUESTS", "rift.bridge.FacadeEncode#verifyDetails"),
    Coverage.ExcludedClass(
      "CopySpec",
      "the copy behavior's spec type. FacadeEncode.isSpec explicitly refuses copy/lookup rather than degrading them (its own scaladoc records the residual), and redirectTo carries full stub fidelity instead, so this is never constructed"
    ),
    Coverage.ExcludedClass(
      "FaultSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "FlowStateSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "ImposterSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "InjectSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.Excluded(
      "IsSpec#after(Duration)",
      "the facade's absolute-time scheduling knob; rift.model.Behaviors models waits (waitMs/waitBetween) and has no `after` construct to translate"
    ),
    Coverage.Excluded(
      "IsSpec#build()",
      "the builder's terminal. FacadeEncode.isSpec hands the IsSpec itself to the facade call that consumes it, which builds internally"
    ),
    Coverage.Excluded(
      "IsSpec#copy(CopySpec[])",
      "the copy residual FacadeEncode.isSpec refuses loudly rather than degrading — use redirectTo"
    ),
    Coverage.Excluded(
      "IsSpec#copyObject(CopySpec)",
      "the copy residual FacadeEncode.isSpec refuses loudly rather than degrading — use redirectTo"
    ),
    Coverage.Excluded(
      "IsSpec#lookup(LookupSpec[])",
      "the lookup residual FacadeEncode.isSpec refuses loudly rather than degrading — use redirectTo"
    ),
    Coverage.Excluded(
      "IsSpec#lookupObject(LookupSpec)",
      "the lookup residual FacadeEncode.isSpec refuses loudly rather than degrading — use redirectTo"
    ),
    Coverage.Excluded(
      "IsSpec#withBodyFromCodec(Object)",
      "the ServiceLoader-global body codec, rejected by D7 in favour of per-call JsonBody[A] side-cars"
    ),
    Coverage.Excluded(
      "IsSpec#withJsonBody(String)",
      "the String overload; FacadeEncode.isSpec builds a JsonValue and calls withJsonBody(JsonValue) instead, so the parse happens once on our side"
    ),
    Coverage.ExcludedClass(
      "LookupSpec",
      "the lookup behavior's spec type — same residual as CopySpec: FacadeEncode.isSpec refuses it loudly rather than degrading it, and redirectTo is the full-fidelity route"
    ),
    Coverage.ExcludedClass(
      "PredicateEvaluator",
      "client-side predicate evaluation in the facade. rift-scala evaluates matches with its own rift.model.matching.RequestMatcher (gated by RequestMatcherSpec), so this is never called"
    ),
    Coverage.ExcludedClass(
      "PredicateGeneratorSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "PredicateSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "ProxySpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "RequestField",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.Excluded(
      "RequestMatch#of(List)",
      "the typed-predicate constructors. FacadeEncode.requestMatch renders rift.model.Predicate to JSON and uses ofJson(JsonValue), the D2 seam, so the facade's Predicate type is never constructed"
    ),
    Coverage.Excluded(
      "RequestMatch#of(Predicate[])",
      "the typed-predicate constructors. FacadeEncode.requestMatch renders rift.model.Predicate to JSON and uses ofJson(JsonValue), the D2 seam, so the facade's Predicate type is never constructed"
    ),
    Coverage.Excluded(
      "RequestMatch#ofJson(String)",
      "the String overload; FacadeEncode.requestMatch already holds a JsonValue and calls ofJson(JsonValue), avoiding a re-parse"
    ),
    Coverage.Excluded(
      "RequestMatch#predicates()",
      "the read-back accessor. rift-scala only ever sends a RequestMatch to the facade and keeps its own rift.dsl.RequestMatch as the readable form"
    ),
    Coverage.ExcludedClass(
      "ResponseSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.Excluded(
      "RiftDsl#and(PredicateSpec[])",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#atLeast(int)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#atMost(int)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#between(int,int)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#body(Matcher)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#contains(JsonValue)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#contains(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#copyFrom(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#copyFromHeader(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#copyFromQuery(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#created()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#deepEquals(JsonValue)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#deepEquals(Object)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#deepEquals(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#endsWith(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#eq(JsonValue)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#eq(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#equalTo(JsonValue)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#equalTo(Object)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#equalTo(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#exactly(int)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#exists()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#fault(Fault)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#header(String,Matcher)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#header(String,String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#imposter(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#inMemoryFlowState()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#inject(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#json(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#jsonPath(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#lookupKey(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#matches(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#method(Matcher)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#never()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#noContent()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#not(PredicateSpec)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#notExists()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#notFound()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#ok()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#okJson(JsonValue)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#okJson(Object)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#okJson(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#okJsonRaw(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#on(String,String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onDelete(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onGet(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onHead(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onOptions(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onPatch(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onPost(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onPut(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#onRequest()",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#or(PredicateSpec[])",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#path(Matcher)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#proxyTo(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#query(String,Matcher)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#query(String,String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#redisFlowState(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#regex(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#regex(String,boolean,boolean)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#scenario(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#script(Script)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#startsWith(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#times(int)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#useBodyCodec(RiftBodyCodec)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#xPath(String)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.Excluded(
      "RiftDsl#xPath(String,Map)",
      "the facade's static builder DSL. rift-scala ships its own rift.dsl and builds the model directly, crossing to the engine as raw JSON (D2), so the facade's combinators are bypassed — only status(int) is used, inside FacadeEncode.isSpec"
    ),
    Coverage.ExcludedClass(
      "ScenarioSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "ScenarioSpec.RespondedTransition",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "ScenarioSpec.Transition",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "Script",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "ScriptEngine",
      "the facade DSL's script-engine enum, reachable only from ScriptSpec, which rift-scala never builds — rift.model.ScriptEngine is the Scala-side model and travels as raw JSON"
    ),
    Coverage.ExcludedClass(
      "ScriptSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "StubSpec",
      "the facade's typed builder DSL, which rift-scala replaces wholesale: rift.dsl builds the model directly and it crosses to the engine as raw JSON (D2), so this builder is never constructed. FacadeEncode.isSpec is the one typed translation that remains, and it targets IsSpec alone"
    ),
    Coverage.ExcludedClass(
      "VerificationException",
      "the facade's throwing verification failure. RiftError.fromThrowable translates it at the boundary into RiftError.VerificationFailed; nothing else in rift-scala touches the type"
    ),
    Coverage.Excluded(
      "VerificationResult#read(JsonValue,VerificationTimes)",
      "the facade's own JSON decoder for the result. The facade returns an already-decoded VerificationResult from verifyResult, which FacadeDecode.verificationResult translates, so this entry point is never called"
    ),
    Coverage.Excluded(
      "VerificationTimes#describe()",
      "the facade's human-readable rendering; RiftError.VerificationFailed carries the decoded VerificationReport and renders Scala-side"
    ),
    Coverage.Excluded(
      "VerificationTimes#matches(int)",
      "client-side evaluation of a times constraint; rift-scala asks the engine to verify and decodes the result instead"
    ),
    Coverage.Excluded(
      "VerificationTimes#never()",
      "rift.model.Times models this as Exactly(0), which FacadeEncode.times sends through exactly(int) — one encoding path rather than two"
    ),
    Coverage.Excluded(
      "VerificationTimes#times(int)",
      "an alias for exactly(int), which FacadeEncode.times uses"
    )
  )
