package rift.dsl

import rift.json.{Json, JsonError}
import rift.model.*

/** The phantom-typed stub builder (DESIGN.md §5.1.3): a stub without a response does not compile.
  * `S` tracks whether a response has been attached: `StubPhase.Matching` (none yet, usable only as
  * a `RequestMatch`) or `StubPhase.Complete` (buildable). Every step returns a new, immutable
  * value.
  */
final class StubBuilder[S <: StubPhase] private[dsl] (
    private val methodValue: Option[Method],
    private val pathValue: Option[String],
    private val wherePredicates: Vector[Predicate],
    private val caseSensitiveFlag: Boolean,
    private val exceptField: Option[String],
    private val responsesValue: Vector[ResponseBuilder],
    private val nameValue: Option[String],
    private val idValue: Option[StubId],
    private val routePatternValue: Option[String],
    private val spaceValue: Option[FlowId],
    private val scenarioValue: Option[ScenarioRef]
) extends RequestMatch:

  private def withState[S2 <: StubPhase](
      methodValue: Option[Method] = this.methodValue,
      pathValue: Option[String] = this.pathValue,
      wherePredicates: Vector[Predicate] = this.wherePredicates,
      caseSensitiveFlag: Boolean = this.caseSensitiveFlag,
      exceptField: Option[String] = this.exceptField,
      responsesValue: Vector[ResponseBuilder] = this.responsesValue,
      nameValue: Option[String] = this.nameValue,
      idValue: Option[StubId] = this.idValue,
      routePatternValue: Option[String] = this.routePatternValue,
      spaceValue: Option[FlowId] = this.spaceValue,
      scenarioValue: Option[ScenarioRef] = this.scenarioValue
  ): StubBuilder[S2] =
    new StubBuilder[S2](
      methodValue,
      pathValue,
      wherePredicates,
      caseSensitiveFlag,
      exceptField,
      responsesValue,
      nameValue,
      idValue,
      routePatternValue,
      spaceValue,
      scenarioValue
    )

  /** The predicate part — the `RequestMatch` vocabulary.
    *
    * `on(method, path)` contributes an equality predicate that `.where(...)` clauses *refine*,
    * never replace: dropping it once a `.where` appeared would silently widen the stub to every
    * method and path. `onRequest` contributes nothing, so it matches anything until refined.
    */
  def predicates: Vector[Predicate] =
    val fromOn = (methodValue, pathValue) match
      case (Some(m), Some(p)) =>
        Vector(
          Predicate(PredicateOp.Equals(Fields(Vector("method" -> m.toJson, "path" -> Json.Str(p)))))
        )
      case (Some(m), None) =>
        Vector(Predicate(PredicateOp.Equals(Fields(Vector("method" -> m.toJson)))))
      case (None, Some(p)) =>
        Vector(Predicate(PredicateOp.Equals(Fields(Vector("path" -> Json.Str(p))))))
      case (None, None) => Vector.empty
    val base = fromOn ++ wherePredicates
    if !caseSensitiveFlag && exceptField.isEmpty then base else base.map(applyStubParams)

  private def applyStubParams(p: Predicate): Predicate =
    p.copy(params =
      p.params.copy(
        caseSensitive = p.params.caseSensitive || caseSensitiveFlag,
        except = exceptField.orElse(p.params.except)
      )
    )

  def where(predicate: PredicateBuilder): StubBuilder[S] =
    withState(wherePredicates = wherePredicates :+ predicate.build)

  def caseSensitive: StubBuilder[S] = withState(caseSensitiveFlag = true)

  def except(jsonField: String): StubBuilder[S] = withState(exceptField = Some(jsonField))

  def named(name: String): StubBuilder[S] = withState(nameValue = Some(name))

  def withId(id: StubId): StubBuilder[S] = withState(idValue = Some(id))

  def route(pattern: String): StubBuilder[S] = withState(routePatternValue = Some(pattern))

  def inSpace(flowId: FlowId): StubBuilder[S] = withState(spaceValue = Some(flowId))

  private[dsl] def inScenario(ref: ScenarioRef): StubBuilder[S] =
    withState(scenarioValue = Some(ref))

  def reply(response: ResponseBuilder): StubBuilder[StubPhase.Complete] =
    withState[StubPhase.Complete](responsesValue = Vector(response))

  def thenReply(response: ResponseBuilder)(using
      S =:= StubPhase.Complete
  ): StubBuilder[StubPhase.Complete] =
    withState[StubPhase.Complete](responsesValue = responsesValue :+ response)

  def build(using S =:= StubPhase.Complete): Stub =
    Stub(
      predicates = predicates,
      responses = responsesValue.map(_.build),
      name = nameValue,
      id = idValue,
      routePattern = routePatternValue,
      space = spaceValue,
      scenario = scenarioValue
    )

private def emptyStub(
    method: Option[Method],
    path: Option[String]
): StubBuilder[StubPhase.Matching] =
  new StubBuilder(
    method,
    path,
    Vector.empty,
    false,
    None,
    Vector.empty,
    None,
    None,
    None,
    None,
    None
  )

def on(method: Method, path: String): StubBuilder[StubPhase.Matching] =
  emptyStub(Some(method), Some(path))
def onRequest: StubBuilder[StubPhase.Matching] = emptyStub(None, None)

def get(path: String): StubBuilder[StubPhase.Matching] = on(Method.GET, path)
def post(path: String): StubBuilder[StubPhase.Matching] = on(Method.POST, path)
def put(path: String): StubBuilder[StubPhase.Matching] = on(Method.PUT, path)
def delete(path: String): StubBuilder[StubPhase.Matching] = on(Method.DELETE, path)
def patch(path: String): StubBuilder[StubPhase.Matching] = on(Method.PATCH, path)
def head(path: String): StubBuilder[StubPhase.Matching] = on(Method.HEAD, path)
def options(path: String): StubBuilder[StubPhase.Matching] = on(Method.OPTIONS, path)

def stubFromJson(raw: String): Either[JsonError, Stub] =
  for
    json <- Json.parse(raw)
    stub <- Stub.fromJson(json)
  yield stub
