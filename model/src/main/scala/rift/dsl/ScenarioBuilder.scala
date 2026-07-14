package rift.dsl

import rift.model.*

/** A stateful scenario (FSM): a named starting state and a sequence of `when(state, matcher)
  * .respond(response).goTo(nextState)` transitions, each compiled to a `Stub` carrying the engine's
  * `scenarioName`/`requiredScenarioState`/`newScenarioState` wire triplet.
  */
final class ScenarioBuilder private[dsl] (
    private val name: String,
    private val startState: Option[String] = None,
    private val transitions: Vector[ScenarioTransition] = Vector.empty
):
  /** Declares the state the scenario begins in.
    *
    * The engine seeds every scenario at `"Started"` (`INITIAL_SCENARIO_STATE`) and exposes no wire
    * field to change it, so this cannot move the starting point — it declares intent and is checked
    * when the stubs are built.
    */
  def startingAt(state: String): ScenarioBuilder =
    new ScenarioBuilder(name, Some(state), transitions)

  def when(state: String, matcher: RequestMatch): ScenarioWhenBuilder =
    new ScenarioWhenBuilder(this, state, matcher)

  private[dsl] def addTransition(t: ScenarioTransition): ScenarioBuilder =
    new ScenarioBuilder(name, startState, transitions :+ t)

  /** A declared start state with no transition leaving it is a scenario that can never fire — the
    * engine would sit in that state forever with nothing to match. Catch it here rather than at
    * three in the morning in a test that silently 404s.
    */
  def stubs: Vector[Stub] =
    startState.foreach { s =>
      require(
        transitions.exists(_.state == s),
        s"scenario '$name' starts at '$s' but no `.when(\"$s\", ...)` transition leaves that state"
      )
    }
    transitions.map(_.toStub(name))

private[dsl] final case class ScenarioTransition(
    state: String,
    matcher: RequestMatch,
    response: ResponseBuilder,
    newState: Option[String]
):
  def toStub(scenarioName: String): Stub =
    Stub(
      predicates = matcher.predicates,
      responses = Vector(response.build),
      scenario = Some(ScenarioRef(scenarioName, Some(state), newState))
    )

final class ScenarioWhenBuilder private[dsl] (
    private val scenario: ScenarioBuilder,
    private val state: String,
    private val matcher: RequestMatch
):
  def respond(response: ResponseBuilder): ScenarioRespondBuilder =
    new ScenarioRespondBuilder(scenario, state, matcher, response)

final class ScenarioRespondBuilder private[dsl] (
    private val scenario: ScenarioBuilder,
    private val state: String,
    private val matcher: RequestMatch,
    private val response: ResponseBuilder
):
  private def committed(newState: Option[String]): ScenarioBuilder =
    scenario.addTransition(ScenarioTransition(state, matcher, response, newState))

  def goTo(newState: String): ScenarioBuilder = committed(Some(newState))

  def when(state: String, matcher: RequestMatch): ScenarioWhenBuilder =
    committed(None).when(state, matcher)

  def stubs: Vector[Stub] = committed(None).stubs

def scenario(name: String): ScenarioBuilder = new ScenarioBuilder(name)
