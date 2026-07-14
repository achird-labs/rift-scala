package rift.model

final case class ScenarioRef(
    name: String,
    requiredState: Option[String],
    newState: Option[String]
)
