package rift.model

/** TCP port an imposter binds to. `1..65535`; an absent `Port` means "let the engine assign one".
  */
opaque type Port = Int

object Port:
  def from(value: Int): Either[String, Port] =
    if value >= 1 && value <= 65535 then Right(value)
    else Left(s"port out of range 1..65535: $value")

  def value(port: Port): Int = port

/** Correlates stubs into an isolated `_rift` flow-state space. Must be non-empty. */
opaque type FlowId = String

object FlowId:
  def from(value: String): Either[String, FlowId] =
    if value.nonEmpty then Right(value) else Left("flow id must not be empty")

  def value(id: FlowId): String = id

/** Identifies a stub for later PATCH/DELETE against the admin API. */
opaque type StubId = String

object StubId:
  def apply(value: String): StubId = value
  def value(id: StubId): String = id

/** A scenario's named state. Convenience wrapper — plain strings are accepted everywhere. */
opaque type ScenarioState = String

object ScenarioState:
  def apply(value: String): ScenarioState = value
  def value(state: ScenarioState): String = state
